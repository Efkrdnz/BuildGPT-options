package dev.jonasjones.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A tool exposed over MCP. {@code handler} receives the call arguments and returns a JSON value
 * that is sent back to the client as text. Throw {@link ToolException} for errors the model should see.
 */
public record McpTool(String name, String title, String description, JsonObject inputSchema,
					  boolean readOnly, boolean destructive, Handler handler) {

	@FunctionalInterface
	public interface Handler {
		JsonElement call(JsonObject arguments) throws Exception;
	}

	public JsonObject describe() {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("title", title);
		tool.addProperty("description", description);
		tool.add("inputSchema", inputSchema);

		JsonObject annotations = new JsonObject();
		annotations.addProperty("title", title);
		annotations.addProperty("readOnlyHint", readOnly);
		annotations.addProperty("destructiveHint", destructive);
		annotations.addProperty("openWorldHint", false);
		tool.add("annotations", annotations);
		return tool;
	}

	/** An error caused by bad arguments or world state; its message is returned to the model. */
	public static class ToolException extends Exception {
		public ToolException(String message) {
			super(message);
		}
	}
}
