package dev.jonasjones;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.jonasjones.mcp.BuildGptMcp;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class BuildGPT implements ModInitializer {
	public static final String MOD_ID = "buildgpt";

	public static final String GPT_PROMPT_BOUND = "Imagine, You're an architect. Design the structure of a minecraft " +
			"%s within the coordinate range x: %d-%d, y: %d-%d, z: %d-%d in minecraft. Return the blocks in a json " +
			"list of objects {x:1,y:1,z:1,block:minecraft:block}. Return only the json without any formatting or " +
			"explanation as plaintext.";

	public static final String GPT_PROMPT_UNBOUND = "Imagine, You're an architect. Design the structure of a %s at " +
			"the coordinate x: %d, y: %d, z: %d in minecraft. Return the blocks in a json list of objects " +
			"{x:1,y:1,z:1,block:minecraft:block}. Return only the json without any formatting or explanation as " +
			"plaintext.";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final String API_KEY_FILE = FabricLoader.getInstance().getConfigDir() + "/.openaikey";

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("buildgpt")
					.then(Commands.literal("bound")
						.then(Commands.argument("start_pos", BlockPosArgument.blockPos())
								.then(Commands.argument("end_pos", BlockPosArgument.blockPos())
										.then(Commands.argument("building", StringArgumentType.greedyString())
																			.executes(BuildGPT::executeBuildGptdBound)))))
					.then(Commands.literal("unbound")
							.then(Commands.argument("start_pos", BlockPosArgument.blockPos())
									.then(Commands.argument("building", StringArgumentType.greedyString())
										.executes(BuildGPT::executeBuildGptUnboud))))
					.then(Commands.literal("mcp")
							.executes(context -> {
								context.getSource().sendSystemMessage(Component.literal(BuildGptMcp.status()));
								return 1;
							})));
		});

		BuildGptMcp.register();
	}

	private static int executeBuildGptdBound(CommandContext<CommandSourceStack> context) {
		String building = StringArgumentType.getString(context, "building");
		BlockPos start_pos = BlockPosArgument.getBlockPos(context, "start_pos");
		String prompt;
		int x1 = start_pos.getX();
		int y1 = start_pos.getY();
		int z1 = start_pos.getZ();
		BlockPos end_pos = BlockPosArgument.getBlockPos(context, "end_pos");
		int x2 = end_pos.getX();
		int y2 = end_pos.getY();
		int z2 = end_pos.getZ();

		prompt = String.format(GPT_PROMPT_BOUND, building, x1, x2, y1, y2, z1, z2);

		return executeBuildGpt(context, prompt);
	}

	private static int executeBuildGptUnboud(CommandContext<CommandSourceStack> context) {
		String building = StringArgumentType.getString(context, "building");
		BlockPos start_pos = BlockPosArgument.getBlockPos(context, "start_pos");
		int x1 = start_pos.getX();
		int y1 = start_pos.getY();
		int z1 = start_pos.getZ();

		String prompt = String.format(GPT_PROMPT_UNBOUND, building, x1, y1, z1);

		return executeBuildGpt(context, prompt);
	}

	private static int executeBuildGpt(CommandContext<CommandSourceStack> context, String prompt) {
		CommandSourceStack source = context.getSource();

		source.sendSystemMessage(Component.literal("Requesting Structure from GPT..."));

		CompletableFuture
				.supplyAsync(() -> requestGpt(prompt))
				.thenApply(json -> {
					if (json == null) return null;
					try {
						return parseJson(json);
					} catch (JsonSyntaxException e) {
						return null;
					}
				})
				.thenAccept(blocks -> {
					source.getServer().execute(() -> {
						if (blocks == null) {
							source.sendFailure(Component.literal("Failed to get or parse GPT response."));
							return;
						}

						for (Map<String, Object> block : blocks) {
							int x = ((Number) block.get("x")).intValue();
							int y = ((Number) block.get("y")).intValue();
							int z = ((Number) block.get("z")).intValue();
							String blockType = (String) block.get("block");

							source.getServer().getCommands().performPrefixedCommand(
									source,
									String.format("/setblock %d %d %d %s", x, y, z, blockType)
							);
						}

						source.sendSystemMessage(Component.literal("Done building!"));
					});
				});

		return 1;
	}

	private static String requestGpt(String prompt) {
		try {
			String apiKey = getApiKey();
			if (apiKey == null) {
				LOGGER.error("API key not found. Please provide a valid API key.");
				return null;
			}

			HttpClient client = HttpClient .newBuilder()
					.connectTimeout(Duration.ofSeconds(60))
					.build();

			JsonObject payload = new JsonObject();
			payload.addProperty("model", "gpt-4o");

			JsonArray messages = new JsonArray();
			JsonObject userMessage = new JsonObject();
			userMessage.addProperty("role", "user");
			userMessage.addProperty("content", prompt);
			messages.add(userMessage);

			payload.add("messages", messages);

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://api.openai.com/v1/chat/completions"))
					.timeout(Duration.ofSeconds(60))
					.header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
					.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 429) {
				LOGGER.error("Rate limit exceeded or no tokens left.");
				return null;
			}

			if (response.statusCode() != 200) {
				LOGGER.error("Unexpected response code: " + response.statusCode());
				LOGGER.error(response.body());
				return null;
			}

			JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();

			return jsonResponse.get("choices").getAsJsonArray()
					.get(0).getAsJsonObject()
					.get("message").getAsJsonObject()
					.get("content").getAsString();

		} catch (HttpTimeoutException e) {
			LOGGER.error("Request timed out.");
			e.printStackTrace();
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}

		return null;
	}

	private static List<Map<String, Object>> parseJson(String jsonResponse) {
		// Use Gson to parse JSON string to List<Map>
		Gson gson = new Gson();
		return gson.fromJson(jsonResponse, List.class);
	}

	public static String getApiKey() {
		File keyFile = new File(API_KEY_FILE);

		if (keyFile.exists()) {
			// Read the API key from the file
			try {
				String apiKey = new String(Files.readAllBytes(Paths.get(API_KEY_FILE))).trim();
				if (!apiKey.isEmpty()) {
					return apiKey;
				} else {
					LOGGER.error("API key file is empty. Please provide a valid API key.");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			// File doesn't exist, create it and prompt user for the key
			try {
				LOGGER.warn("No API key found. Creating .openaikey file...");

				// Write the API key to the file
				Files.write(Paths.get(API_KEY_FILE), "".getBytes(), StandardOpenOption.CREATE);

				LOGGER.warn("Add your OpenAI API key to " + API_KEY_FILE);
				return null;
			} catch (IOException e) {
				LOGGER.error("Failed to create API key file.");
				LOGGER.error("Create it manually as '.openaikey' in the config directory.");
			}
		}

		return null;
	}
}