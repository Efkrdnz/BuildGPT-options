package dev.jonasjones.mcp;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Settings for the built-in MCP server, stored in {@code config/buildgpt-mcp.properties}.
 * The file is created with a random access token on first load.
 */
public final class McpConfig {
	public static final String FILE_NAME = "buildgpt-mcp.properties";

	public final Path file;
	public boolean enabled = true;
	public String host = "127.0.0.1";
	public int port = 25590;
	public String token = "";
	public boolean announceInChat = true;
	public int maxBlocksPerCall = 10000;
	public int maxFillVolume = 131072;
	public int maxReadVolume = 32768;
	public int undoHistory = 10;

	private McpConfig(Path file) {
		this.file = file;
	}

	public static McpConfig load(Path configDir) throws IOException {
		McpConfig config = new McpConfig(configDir.resolve(FILE_NAME));
		Properties props = new Properties();

		if (Files.exists(config.file)) {
			try (Reader reader = Files.newBufferedReader(config.file, StandardCharsets.UTF_8)) {
				props.load(reader);
			}
		}

		config.enabled = Boolean.parseBoolean(props.getProperty("enabled", String.valueOf(config.enabled)));
		config.host = props.getProperty("host", config.host).trim();
		config.port = intProp(props, "port", config.port);
		config.token = props.getProperty("token", "").trim();
		config.announceInChat = Boolean.parseBoolean(props.getProperty("announce_in_chat", String.valueOf(config.announceInChat)));
		config.maxBlocksPerCall = intProp(props, "max_blocks_per_call", config.maxBlocksPerCall);
		config.maxFillVolume = intProp(props, "max_fill_volume", config.maxFillVolume);
		config.maxReadVolume = intProp(props, "max_read_volume", config.maxReadVolume);
		config.undoHistory = intProp(props, "undo_history", config.undoHistory);

		boolean firstRun = !Files.exists(config.file);
		if (firstRun || !props.containsKey("token")) {
			if (config.token.isEmpty()) {
				config.token = generateToken();
			}
			config.save();
		}

		return config;
	}

	public void save() throws IOException {
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			writer.write("# BuildGPT MCP server settings. Changes apply the next time a world/server starts.\n");
			writer.write("enabled=" + enabled + "\n");
			writer.write("# Keep 127.0.0.1 unless you know what you are doing: anyone who can reach this port\n");
			writer.write("# and knows the token can edit your world.\n");
			writer.write("host=" + host + "\n");
			writer.write("port=" + port + "\n");
			writer.write("# Clients must send the header 'Authorization: Bearer <token>'. Leave empty to disable auth (not recommended).\n");
			writer.write("token=" + token + "\n");
			writer.write("# Show a chat message to players whenever the AI changes blocks.\n");
			writer.write("announce_in_chat=" + announceInChat + "\n");
			writer.write("max_blocks_per_call=" + maxBlocksPerCall + "\n");
			writer.write("max_fill_volume=" + maxFillVolume + "\n");
			writer.write("max_read_volume=" + maxReadVolume + "\n");
			writer.write("undo_history=" + undoHistory + "\n");
		}
	}

	public String endpoint() {
		String shownHost = host.equals("0.0.0.0") || host.isEmpty() ? "127.0.0.1" : host;
		if (shownHost.contains(":")) {
			shownHost = "[" + shownHost + "]";
		}
		return "http://" + shownHost + ":" + port + "/mcp";
	}

	private static int intProp(Properties props, String key, int fallback) {
		String value = props.getProperty(key);
		if (value == null) return fallback;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String generateToken() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}
}
