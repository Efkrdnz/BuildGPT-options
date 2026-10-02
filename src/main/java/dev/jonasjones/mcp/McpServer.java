package dev.jonasjones.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal MCP server using the Streamable HTTP transport (JSON responses only, no SSE stream).
 * Implements initialize, ping, tools/list and tools/call. Has no Minecraft dependencies.
 */
public final class McpServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("buildgpt-mcp");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
	private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

	private final String host;
	private final int port;
	private final String token;
	private final String version;
	private final String instructions;
	private final Map<String, McpTool> tools = new LinkedHashMap<>();
	private HttpServer http;
	private ExecutorService executor;

	public McpServer(String host, int port, String token, String version, String instructions, List<McpTool> tools) {
		this.host = host;
		this.port = port;
		this.token = token == null ? "" : token;
		this.version = version;
		this.instructions = instructions;
		for (McpTool tool : tools) {
			this.tools.put(tool.name(), tool);
		}
	}

	public void start() throws IOException {
		http = HttpServer.create(new InetSocketAddress(host, port), 0);
		AtomicInteger threadId = new AtomicInteger();
		executor = Executors.newFixedThreadPool(4, runnable -> {
			Thread thread = new Thread(runnable, "BuildGPT-MCP-" + threadId.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		http.setExecutor(executor);
		http.createContext("/", this::handle);
		http.start();
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
			http = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String path = exchange.getRequestURI().getPath();
			if (!path.equals("/mcp") && !path.equals("/mcp/")) {
				sendText(exchange, 404, "Not found. The MCP endpoint is /mcp");
				return;
			}

			// Block browser pages from talking to the server (DNS rebinding protection).
			String origin = exchange.getRequestHeaders().getFirst("Origin");
			if (origin != null && !isLocalOrigin(origin)) {
				sendText(exchange, 403, "Forbidden origin");
				return;
			}

			if (!isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
				sendText(exchange, 401, "Missing or invalid 'Authorization: Bearer <token>' header. " +
						"The token is in config/" + McpConfig.FILE_NAME);
				return;
			}

			if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
				exchange.getResponseHeaders().set("Allow", "POST");
				sendText(exchange, 405, "Only POST is supported");
				return;
			}

			JsonElement message;
			try {
				message = JsonParser.parseString(readBody(exchange.getRequestBody()));
			} catch (JsonParseException | IllegalStateException e) {
				sendJson(exchange, 400, error(JsonNull.INSTANCE, -32700, "Parse error"));
				return;
			}

			JsonElement response;
			if (message.isJsonArray()) {
				JsonArray responses = new JsonArray();
				for (JsonElement item : message.getAsJsonArray()) {
					JsonObject itemResponse = process(item);
					if (itemResponse != null) responses.add(itemResponse);
				}
				response = responses.isEmpty() ? null : responses;
			} else {
				response = process(message);
			}

			if (response == null) {
				// Notifications and responses get no body.
				exchange.sendResponseHeaders(202, -1);
			} else {
				sendJson(exchange, 200, response);
			}
		} catch (BodyTooLargeException e) {
			sendText(exchange, 413, "Request body too large");
		} catch (Exception e) {
			LOGGER.error("Unexpected MCP transport error", e);
			try {
				sendText(exchange, 500, "Internal error");
			} catch (IOException ignored) {
				// Response was already (partially) sent.
			}
		} finally {
			exchange.close();
		}
	}

	/** Returns the JSON-RPC response for one message, or null if none is needed. */
	private JsonObject process(JsonElement element) {
		if (!element.isJsonObject()) {
			return error(JsonNull.INSTANCE, -32600, "Invalid request");
		}
		JsonObject message = element.getAsJsonObject();
		JsonElement id = message.get("id");
		boolean isRequest = id != null && !id.isJsonNull();

		if (!message.has("method") || !message.get("method").isJsonPrimitive()) {
			// A response from the client, or garbage. Nothing to answer either way.
			return isRequest && !message.has("result") && !message.has("error")
					? error(id, -32600, "Invalid request") : null;
		}
		if (!isRequest) {
			return null; // notification, e.g. notifications/initialized
		}

		String method = message.get("method").getAsString();
		JsonObject params = message.has("params") && message.get("params").isJsonObject()
				? message.getAsJsonObject("params") : new JsonObject();

		try {
			return switch (method) {
				case "initialize" -> result(id, initialize(params));
				case "ping" -> result(id, new JsonObject());
				case "tools/list" -> result(id, listTools());
				case "tools/call" -> callTool(id, params);
				default -> error(id, -32601, "Method not found: " + method);
			};
		} catch (Exception e) {
			LOGGER.error("Error handling MCP method {}", method, e);
			return error(id, -32603, "Internal error: " + e.getMessage());
		}
	}

	private JsonObject initialize(JsonObject params) {
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
		String negotiated = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);

		JsonObject toolsCapability = new JsonObject();
		toolsCapability.addProperty("listChanged", false);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", toolsCapability);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "buildgpt");
		serverInfo.addProperty("title", "BuildGPT Minecraft Builder");
		serverInfo.addProperty("version", version);

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", negotiated);
		result.add("capabilities", capabilities);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", instructions);
		return result;
	}

	private JsonObject listTools() {
		JsonArray list = new JsonArray();
		for (McpTool tool : tools.values()) {
			list.add(tool.describe());
		}
		JsonObject result = new JsonObject();
		result.add("tools", list);
		return result;
	}

	private JsonObject callTool(JsonElement id, JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		McpTool tool = tools.get(name);
		if (tool == null) {
			return error(id, -32602, "Unknown tool: " + name);
		}
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();

		try {
			JsonElement output = tool.handler().call(arguments);
			return result(id, toolResult(GSON.toJson(output), false));
		} catch (McpTool.ToolException e) {
			return result(id, toolResult(e.getMessage(), true));
		} catch (Exception e) {
			LOGGER.error("MCP tool {} failed", name, e);
			String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return result(id, toolResult("Tool failed: " + reason, true));
		}
	}

	private static JsonObject toolResult(String text, boolean isError) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonArray contentList = new JsonArray();
		contentList.add(content);

		JsonObject result = new JsonObject();
		result.add("content", contentList);
		result.addProperty("isError", isError);
		return result;
	}

	private static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return response;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", error);
		return response;
	}

	private boolean isAuthorized(String header) {
		if (token.isEmpty()) return true;
		if (header == null) return false;
		byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, header.trim().getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isLocalOrigin(String origin) {
		try {
			String originHost = URI.create(origin).getHost();
			return originHost != null && (originHost.equals("localhost") || originHost.equals("127.0.0.1")
					|| originHost.equals("[::1]") || originHost.equals("::1"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String readBody(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (out.size() + read > MAX_BODY_BYTES) throw new BodyTooLargeException();
			out.write(buffer, 0, read);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
		send(exchange, status, "application/json", GSON.toJson(body));
	}

	private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
		send(exchange, status, "text/plain; charset=utf-8", body);
	}

	private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static final class BodyTooLargeException extends IOException {
	}
}
