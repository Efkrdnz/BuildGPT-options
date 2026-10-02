package dev.jonasjones.mcp;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Starts the MCP server when a Minecraft server (dedicated or single-player) starts, and stops it with the server.
 */
public final class BuildGptMcp {
	private static final Logger LOGGER = LoggerFactory.getLogger("buildgpt-mcp");

	private static McpServer running;
	private static McpConfig config;

	private BuildGptMcp() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(BuildGptMcp::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
	}

	private static synchronized void start(MinecraftServer server) {
		stop();
		try {
			config = McpConfig.load(FabricLoader.getInstance().getConfigDir());
		} catch (IOException e) {
			LOGGER.error("Could not load {}; MCP server not started", McpConfig.FILE_NAME, e);
			return;
		}
		if (!config.enabled) {
			LOGGER.info("BuildGPT MCP server is disabled in {}", config.file);
			return;
		}
		if (config.token.isEmpty()) {
			LOGGER.warn("BuildGPT MCP server has no token set: anything that can reach {} can edit the world", config.endpoint());
		}

		McpServer mcpServer = new McpServer(config.host, config.port, config.token, modVersion(),
				WorldTools.INSTRUCTIONS, new WorldTools(server, config).tools());
		try {
			mcpServer.start();
			running = mcpServer;
			LOGGER.info("BuildGPT MCP server listening on {} (access token is in {})", config.endpoint(), config.file);
		} catch (IOException e) {
			LOGGER.error("Could not start BuildGPT MCP server on {}:{}: {}", config.host, config.port, e.toString());
		}
	}

	private static synchronized void stop() {
		if (running != null) {
			running.stop();
			running = null;
			LOGGER.info("BuildGPT MCP server stopped");
		}
	}

	/** One-line status for the /buildgpt mcp command. */
	public static synchronized String status() {
		if (running != null) {
			return "MCP server running at " + config.endpoint() + " - the access token is in config/" + McpConfig.FILE_NAME;
		}
		if (config != null && !config.enabled) {
			return "MCP server is disabled (enabled=false in config/" + McpConfig.FILE_NAME + ")";
		}
		return "MCP server is not running - check the log for errors (e.g. port already in use)";
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer("buildbpt")
				.or(() -> FabricLoader.getInstance().getModContainer("buildgpt"))
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}
}
