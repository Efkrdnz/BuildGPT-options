package dev.jonasjones.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.jonasjones.mcp.McpTool.ToolException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The MCP tools that read and edit the Minecraft world. Every world access is run on the server thread.
 */
public final class WorldTools {
	public static final String INSTRUCTIONS = """
			You are building structures in a live Minecraft (Java Edition 1.21) world through these tools.
			Coordinates are absolute block coordinates; Y is up. Recommended workflow:
			1. Call get_world_info to find the player position, facing direction and the world's height limits.
			2. Call get_surface (and get_blocks for detail) to understand the terrain where you will build.
			3. Build with fill_region for walls, floors, roofs and clearing space, and place_blocks for details.
			   Block ids may carry states, e.g. minecraft:oak_stairs[facing=east,half=bottom] or
			   minecraft:oak_door[half=upper,facing=north]. Use search_blocks if unsure of an id or its states.
			   Two-block-tall blocks (doors, beds, tall flowers) need both halves placed explicitly.
			4. Inspect your work with get_blocks and fix mistakes. Use undo to revert your last edits.
			Prefer a few large calls over many tiny ones. Do not build where players are standing unless asked.""";

	private static final long SERVER_TIMEOUT_SECONDS = 60;

	private final MinecraftServer server;
	private final McpConfig config;
	// Only accessed on the server thread.
	private final Deque<UndoEntry> undoStack = new ArrayDeque<>();

	private record UndoEntry(ResourceKey<Level> dimension, String description, long[] positions, BlockState[] states) {
	}

	public WorldTools(MinecraftServer server, McpConfig config) {
		this.server = server;
		this.config = config;
	}

	public List<McpTool> tools() {
		return List.of(worldInfoTool(), surfaceTool(), getBlocksTool(), searchBlocksTool(),
				placeBlocksTool(), fillRegionTool(), undoTool());
	}

	// ---------------------------------------------------------------- tools

	private McpTool worldInfoTool() {
		return new McpTool("get_world_info", "Get world info",
				"Lists online players (position, dimension, facing direction), the available dimensions with their " +
						"build height limits, this server's limits per call and how many undo steps are available. " +
						"Call this first.",
				schema(new JsonObject()), true, false,
				args -> onServer(() -> {
					JsonObject out = new JsonObject();

					JsonArray players = new JsonArray();
					for (ServerPlayer player : server.getPlayerList().getPlayers()) {
						BlockPos pos = player.blockPosition();
						JsonObject p = new JsonObject();
						p.addProperty("name", player.getName().getString());
						p.addProperty("dimension", dimensionId(player.level()));
						p.addProperty("x", pos.getX());
						p.addProperty("y", pos.getY());
						p.addProperty("z", pos.getZ());
						p.addProperty("facing", player.getDirection().getSerializedName());
						players.add(p);
					}
					out.add("players", players);

					JsonArray dimensions = new JsonArray();
					for (ServerLevel level : server.getAllLevels()) {
						JsonObject d = new JsonObject();
						d.addProperty("id", dimensionId(level));
						d.addProperty("min_y", level.getMinY());
						d.addProperty("max_y", level.getMaxY());
						dimensions.add(d);
					}
					out.add("dimensions", dimensions);
					out.addProperty("default_dimension", dimensionId(resolveLevel(new JsonObject())));

					JsonObject limits = new JsonObject();
					limits.addProperty("max_blocks_per_call", config.maxBlocksPerCall);
					limits.addProperty("max_fill_volume", config.maxFillVolume);
					limits.addProperty("max_read_volume", config.maxReadVolume);
					out.add("limits", limits);
					out.addProperty("undo_steps_available", undoStack.size());
					return out;
				}));
	}

	private McpTool surfaceTool() {
		JsonObject props = new JsonObject();
		props.add("from", columnSchema("One corner of the area (x, z)."));
		props.add("to", columnSchema("The opposite corner of the area (x, z)."));
		props.add("dimension", dimensionSchema());

		return new McpTool("get_surface", "Get terrain surface",
				"Returns a height map of an area: for every column the Y of the topmost solid or liquid block " +
						"(ignoring leaves) and which block it is. heights[i][j] and blocks[i][j] are for " +
						"z = min_z + i, x = min_x + j; blocks are indexes into palette. Use it to find flat ground " +
						"or to fit a build onto the terrain.",
				schema(props, "from", "to"), true, false,
				args -> {
					int x1 = readInt(args, "from", "x"), z1 = readInt(args, "from", "z");
					int x2 = readInt(args, "to", "x"), z2 = readInt(args, "to", "z");
					int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
					int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
					long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
					if (area > config.maxReadVolume) {
						throw new ToolException("Area " + area + " is larger than max_read_volume (" + config.maxReadVolume + "). Query a smaller area.");
					}

					return onServer(() -> {
						ServerLevel level = resolveLevel(args);
						Palette palette = new Palette();
						JsonArray heights = new JsonArray();
						JsonArray blocks = new JsonArray();
						BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

						for (int z = minZ; z <= maxZ; z++) {
							JsonArray heightRow = new JsonArray();
							JsonArray blockRow = new JsonArray();
							for (int x = minX; x <= maxX; x++) {
								int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
								pos.set(x, y, z);
								heightRow.add(y);
								blockRow.add(palette.indexOf(level.getBlockState(pos)));
							}
							heights.add(heightRow);
							blocks.add(blockRow);
						}

						JsonObject out = new JsonObject();
						out.addProperty("dimension", dimensionId(level));
						out.addProperty("min_x", minX);
						out.addProperty("min_z", minZ);
						out.addProperty("max_x", maxX);
						out.addProperty("max_z", maxZ);
						out.add("palette", palette.toJson());
						out.add("heights", heights);
						out.add("blocks", blocks);
						return out;
					});
				});
	}

	private McpTool getBlocksTool() {
		JsonObject props = new JsonObject();
		props.add("from", posSchema("One corner of the box."));
		props.add("to", posSchema("The opposite corner of the box (inclusive)."));
		props.add("include_air", prop("boolean", "Also list air blocks. Default false."));
		props.add("dimension", dimensionSchema());

		return new McpTool("get_blocks", "Read blocks in a box",
				"Returns the blocks inside a box as a palette plus a list of [x, y, z, palette_index] entries, " +
						"ordered by y, then z, then x. Air is left out unless include_air is true.",
				schema(props, "from", "to"), true, false,
				args -> {
					Box box = Box.of(readPos(args, "from"), readPos(args, "to"));
					if (box.volume() > config.maxReadVolume) {
						throw new ToolException("Box volume " + box.volume() + " is larger than max_read_volume (" + config.maxReadVolume + "). Read a smaller box.");
					}
					boolean includeAir = readBoolean(args, "include_air", false);

					return onServer(() -> {
						ServerLevel level = resolveLevel(args);
						Palette palette = new Palette();
						JsonArray blocks = new JsonArray();
						BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

						for (int y = box.minY; y <= box.maxY; y++) {
							for (int z = box.minZ; z <= box.maxZ; z++) {
								for (int x = box.minX; x <= box.maxX; x++) {
									pos.set(x, y, z);
									BlockState state = level.getBlockState(pos);
									if (!includeAir && state.isAir()) continue;
									JsonArray entry = new JsonArray();
									entry.add(x);
									entry.add(y);
									entry.add(z);
									entry.add(palette.indexOf(state));
									blocks.add(entry);
								}
							}
						}

						JsonObject out = new JsonObject();
						out.addProperty("dimension", dimensionId(level));
						out.add("from", box.minJson());
						out.add("to", box.maxJson());
						out.add("palette", palette.toJson());
						out.add("blocks", blocks);
						out.addProperty("count", blocks.size());
						return out;
					});
				});
	}

	private McpTool searchBlocksTool() {
		JsonObject props = new JsonObject();
		props.add("query", prop("string", "Part of a block id, e.g. 'stairs', 'oak', 'glass_pane'. Empty lists all blocks."));
		props.add("limit", prop("integer", "Maximum results (default 50, max 300)."));

		return new McpTool("search_blocks", "Search block ids",
				"Finds block ids containing the query text. For up to the first 10 results it also lists each " +
						"block's states and their allowed values, plus its default state.",
				schema(props), true, false,
				args -> {
					String query = args.has("query") ? args.get("query").getAsString().toLowerCase(Locale.ROOT).trim() : "";
					int limit = Math.max(1, Math.min(300, readOptionalInt(args, "limit", 50)));

					JsonArray results = new JsonArray();
					int total = 0;
					for (Block block : BuiltInRegistries.BLOCK) {
						String id = BuiltInRegistries.BLOCK.getKey(block).toString();
						if (!query.isEmpty() && !id.contains(query)) continue;
						total++;
						if (results.size() >= limit) continue;

						JsonObject entry = new JsonObject();
						entry.addProperty("id", id);
						if (results.size() < 10) {
							JsonObject states = new JsonObject();
							for (Property<?> property : block.getStateDefinition().getProperties()) {
								states.add(property.getName(), possibleValues(property));
							}
							entry.add("states", states);
							entry.addProperty("default", serialize(block.defaultBlockState()));
						}
						results.add(entry);
					}

					JsonObject out = new JsonObject();
					out.add("results", results);
					out.addProperty("total_matches", total);
					return out;
				});
	}

	private McpTool placeBlocksTool() {
		JsonObject blockObject = new JsonObject();
		blockObject.addProperty("type", "object");
		JsonObject blockObjectProps = new JsonObject();
		blockObjectProps.add("x", prop("integer", null));
		blockObjectProps.add("y", prop("integer", null));
		blockObjectProps.add("z", prop("integer", null));
		blockObjectProps.add("block", prop("string", "Block id with optional states, e.g. minecraft:oak_log[axis=y]."));
		blockObject.add("properties", blockObjectProps);
		blockObject.add("required", stringArray("x", "y", "z", "block"));

		JsonObject blockTuple = new JsonObject();
		blockTuple.addProperty("type", "array");
		blockTuple.addProperty("description", "Compact form: [x, y, z, \"block\"].");
		blockTuple.addProperty("minItems", 4);
		blockTuple.addProperty("maxItems", 4);

		JsonArray anyOf = new JsonArray();
		anyOf.add(blockObject);
		anyOf.add(blockTuple);
		JsonObject item = new JsonObject();
		item.add("anyOf", anyOf);

		JsonObject blocks = new JsonObject();
		blocks.addProperty("type", "array");
		blocks.addProperty("description", "Blocks to place. Each is {x, y, z, block} or the compact [x, y, z, block].");
		blocks.add("items", item);

		JsonObject props = new JsonObject();
		props.add("blocks", blocks);
		props.add("origin", posSchema("Optional offset added to every block position, so blocks can use coordinates relative to it."));
		props.add("update_shapes", prop("boolean", "After placing, update fences/walls/panes/stairs connections and remove " +
				"blocks that cannot survive where they are (e.g. a torch on air). Default true."));
		props.add("dimension", dimensionSchema());

		return new McpTool("place_blocks", "Place blocks",
				"Places individual blocks, replacing what is there. All block ids are validated before anything is " +
						"placed. The whole call can be reverted with undo. Use fill_region for large solid areas.",
				schema(props, "blocks"), false, true,
				args -> {
					if (!args.has("blocks") || !args.get("blocks").isJsonArray()) {
						throw new ToolException("'blocks' must be an array.");
					}
					JsonArray list = args.getAsJsonArray("blocks");
					if (list.size() > config.maxBlocksPerCall) {
						throw new ToolException(list.size() + " blocks is more than max_blocks_per_call (" + config.maxBlocksPerCall + "). Split the build into several calls.");
					}
					BlockPos origin = args.has("origin") ? readPos(args, "origin") : BlockPos.ZERO;
					boolean updateShapes = readBoolean(args, "update_shapes", true);

					// Parse everything up front so a typo doesn't leave a half-built structure.
					Map<String, BlockState> cache = new HashMap<>();
					List<BlockPos> positions = new ArrayList<>(list.size());
					List<BlockState> states = new ArrayList<>(list.size());
					for (int i = 0; i < list.size(); i++) {
						JsonElement element = list.get(i);
						int x, y, z;
						String block;
						try {
							if (element.isJsonArray() && element.getAsJsonArray().size() == 4) {
								JsonArray tuple = element.getAsJsonArray();
								x = tuple.get(0).getAsInt();
								y = tuple.get(1).getAsInt();
								z = tuple.get(2).getAsInt();
								block = tuple.get(3).getAsString();
							} else if (element.isJsonObject()) {
								JsonObject object = element.getAsJsonObject();
								x = object.get("x").getAsInt();
								y = object.get("y").getAsInt();
								z = object.get("z").getAsInt();
								block = object.get("block").getAsString();
							} else {
								throw new IllegalArgumentException();
							}
						} catch (RuntimeException e) {
							throw new ToolException("blocks[" + i + "] is malformed; expected {x, y, z, block} or [x, y, z, block].");
						}
						positions.add(new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z));
						states.add(parseBlock(block, cache));
					}

					return onServer(() -> {
						Edit edit = new Edit(resolveLevel(args));
						for (int i = 0; i < positions.size(); i++) {
							edit.set(positions.get(i), states.get(i));
						}
						return edit.finish("place_blocks", updateShapes);
					});
				});
	}

	private McpTool fillRegionTool() {
		JsonObject mode = prop("string", "replace (default): set every block in the box. keep: only replace air. " +
				"outline: only the box's outer shell, inside untouched. hollow: outer shell of the block, inside filled with air.");
		mode.add("enum", stringArray("replace", "keep", "outline", "hollow"));

		JsonObject props = new JsonObject();
		props.add("from", posSchema("One corner of the box."));
		props.add("to", posSchema("The opposite corner of the box (inclusive)."));
		props.add("block", prop("string", "Block id with optional states. Use minecraft:air to clear an area."));
		props.add("mode", mode);
		props.add("only_replace", prop("string", "Optional block id: only blocks of this type are replaced (e.g. replace all dirt with grass)."));
		props.add("update_shapes", prop("boolean", "Same as in place_blocks. Default true."));
		props.add("dimension", dimensionSchema());

		return new McpTool("fill_region", "Fill a box",
				"Fills a box with one block, like /fill. Good for floors, walls, roofs and clearing space " +
						"(block minecraft:air). Can be reverted with undo.",
				schema(props, "from", "to", "block"), false, true,
				args -> {
					Box box = Box.of(readPos(args, "from"), readPos(args, "to"));
					if (box.volume() > config.maxFillVolume) {
						throw new ToolException("Box volume " + box.volume() + " is larger than max_fill_volume (" + config.maxFillVolume + "). Use several smaller fills.");
					}
					Map<String, BlockState> cache = new HashMap<>();
					BlockState state = parseBlock(readString(args, "block"), cache);
					Block onlyReplace = args.has("only_replace") && !args.get("only_replace").isJsonNull()
							? parseBlock(args.get("only_replace").getAsString(), cache).getBlock() : null;
					String fillMode = args.has("mode") ? args.get("mode").getAsString().toLowerCase(Locale.ROOT) : "replace";
					if (!List.of("replace", "keep", "outline", "hollow").contains(fillMode)) {
						throw new ToolException("Unknown mode '" + fillMode + "'. Use replace, keep, outline or hollow.");
					}
					boolean updateShapes = readBoolean(args, "update_shapes", true);

					return onServer(() -> {
						Edit edit = new Edit(resolveLevel(args));
						BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
						for (int y = box.minY; y <= box.maxY; y++) {
							for (int z = box.minZ; z <= box.maxZ; z++) {
								for (int x = box.minX; x <= box.maxX; x++) {
									boolean shell = box.isShell(x, y, z);
									pos.set(x, y, z);
									BlockState target = state;
									switch (fillMode) {
										case "keep" -> {
											if (!edit.level.getBlockState(pos).isAir()) continue;
										}
										case "outline" -> {
											if (!shell) continue;
										}
										case "hollow" -> {
											if (!shell) target = Blocks.AIR.defaultBlockState();
										}
										default -> {
										}
									}
									if (onlyReplace != null && !edit.level.getBlockState(pos).is(onlyReplace)) continue;
									edit.set(pos.immutable(), target);
								}
							}
						}
						return edit.finish("fill_region", updateShapes);
					});
				});
	}

	private McpTool undoTool() {
		JsonObject props = new JsonObject();
		props.add("steps", prop("integer", "How many edits (place_blocks / fill_region calls) to revert. Default 1."));

		return new McpTool("undo", "Undo edits",
				"Reverts the most recent place_blocks / fill_region calls, newest first. Restores block types and " +
						"states; container contents (chest items etc.) are not restored.",
				schema(props), false, true,
				args -> {
					int steps = Math.max(1, readOptionalInt(args, "steps", 1));
					return onServer(() -> {
						int reverted = 0;
						int blocks = 0;
						JsonArray descriptions = new JsonArray();
						while (reverted < steps && !undoStack.isEmpty()) {
							UndoEntry entry = undoStack.pop();
							ServerLevel level = server.getLevel(entry.dimension());
							if (level == null) continue;
							for (int i = entry.positions().length - 1; i >= 0; i--) {
								level.setBlock(BlockPos.of(entry.positions()[i]), entry.states()[i], Block.UPDATE_CLIENTS);
							}
							blocks += entry.positions().length;
							descriptions.add(entry.description());
							reverted++;
						}
						if (reverted == 0) {
							throw new ToolException("Nothing to undo.");
						}
						announce("AI undid " + reverted + " edit(s), restoring " + blocks + " blocks");

						JsonObject out = new JsonObject();
						out.addProperty("edits_reverted", reverted);
						out.addProperty("blocks_restored", blocks);
						out.add("reverted", descriptions);
						out.addProperty("undo_steps_available", undoStack.size());
						return out;
					});
				});
	}

	// ---------------------------------------------------------------- editing

	/** Collects block changes made by one tool call so they can be undone together. */
	private final class Edit {
		final ServerLevel level;
		final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		int unchanged;
		int outsideWorld;

		Edit(ServerLevel level) {
			this.level = level;
		}

		void set(BlockPos pos, BlockState state) {
			if (level.isOutsideBuildHeight(pos)) {
				outsideWorld++;
				return;
			}
			BlockState old = level.getBlockState(pos);
			if (old == state) {
				unchanged++;
				return;
			}
			before.putIfAbsent(pos, old);
			level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		}

		JsonObject finish(String toolName, boolean updateShapes) {
			int removed = 0;
			JsonArray removedSamples = new JsonArray();
			if (updateShapes) {
				for (BlockPos pos : before.keySet()) {
					BlockState current = level.getBlockState(pos);
					BlockState updated = Block.updateFromNeighbourShapes(current, level, pos);
					if (updated != current) {
						level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
						if (updated.isAir() && !current.isAir()) {
							removed++;
							if (removedSamples.size() < 20) {
								removedSamples.add(serialize(current) + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
							}
						}
					}
				}
			}

			int changed = before.size();
			if (changed > 0) {
				long[] positions = new long[changed];
				BlockState[] states = new BlockState[changed];
				int i = 0;
				for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
					positions[i] = entry.getKey().asLong();
					states[i] = entry.getValue();
					i++;
				}
				undoStack.push(new UndoEntry(level.dimension(), toolName + " (" + changed + " blocks)", positions, states));
				while (undoStack.size() > Math.max(0, config.undoHistory)) {
					undoStack.removeLast();
				}
				announce("AI changed " + changed + " blocks (" + toolName + ")");
			}

			JsonObject out = new JsonObject();
			out.addProperty("dimension", dimensionId(level));
			out.addProperty("changed", changed);
			out.addProperty("already_correct", unchanged);
			if (outsideWorld > 0) out.addProperty("skipped_outside_build_height", outsideWorld);
			if (removed > 0) {
				out.addProperty("removed_unsupported", removed);
				out.add("removed_examples", removedSamples);
			}
			out.addProperty("undo_steps_available", undoStack.size());
			return out;
		}
	}

	private void announce(String message) {
		if (config.announceInChat) {
			server.getPlayerList().broadcastSystemMessage(Component.literal("[BuildGPT] " + message), false);
		}
	}

	// ---------------------------------------------------------------- helpers

	@FunctionalInterface
	private interface ServerTask<T> {
		T run() throws Exception;
	}

	/** Runs a task on the server thread and waits for the result. */
	private <T> T onServer(ServerTask<T> task) throws Exception {
		CompletableFuture<T> future = new CompletableFuture<>();
		server.execute(() -> {
			try {
				future.complete(task.run());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Exception cause) throw cause;
			throw new RuntimeException(e.getCause());
		} catch (TimeoutException e) {
			throw new ToolException("Timed out waiting for the Minecraft server thread.");
		}
	}

	private ServerLevel resolveLevel(JsonObject args) throws ToolException {
		if (args.has("dimension") && !args.get("dimension").isJsonNull()) {
			String raw = args.get("dimension").getAsString().trim();
			Identifier id = Identifier.tryParse(raw);
			ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
			if (level == null) {
				List<String> known = new ArrayList<>();
				for (ServerLevel l : server.getAllLevels()) known.add(dimensionId(l));
				throw new ToolException("Unknown dimension '" + raw + "'. Available: " + String.join(", ", known));
			}
			return level;
		}
		// Default: the dimension of the first online player, so single-player "build next to me" just works.
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (!players.isEmpty()) {
			return (ServerLevel) players.get(0).level();
		}
		return server.overworld();
	}

	private static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}

	private static BlockState parseBlock(String input, Map<String, BlockState> cache) throws ToolException {
		BlockState cached = cache.get(input);
		if (cached != null) return cached;
		try {
			StringReader reader = new StringReader(input.trim());
			BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, reader, false).blockState();
			if (reader.canRead()) {
				throw new ToolException("Invalid block '" + input + "': unexpected trailing text.");
			}
			cache.put(input, state);
			return state;
		} catch (CommandSyntaxException e) {
			throw new ToolException("Invalid block '" + input + "': " + e.getMessage() + ". Use search_blocks to find valid ids and states.");
		}
	}

	static String serialize(BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		Map<Property<?>, Comparable<?>> values = state.getValues();
		if (values.isEmpty()) return id;
		return id + values.entrySet().stream()
				.map(StateHolder.PROPERTY_ENTRY_TO_STRING_FUNCTION)
				.collect(Collectors.joining(",", "[", "]"));
	}

	private static <T extends Comparable<T>> JsonArray possibleValues(Property<T> property) {
		JsonArray values = new JsonArray();
		for (T value : property.getPossibleValues()) {
			values.add(property.getName(value));
		}
		return values;
	}

	/** Assigns small integer ids to block states for compact output. */
	private static final class Palette {
		private final Map<BlockState, Integer> ids = new LinkedHashMap<>();

		int indexOf(BlockState state) {
			return ids.computeIfAbsent(state, s -> ids.size());
		}

		JsonArray toJson() {
			JsonArray out = new JsonArray();
			for (BlockState state : ids.keySet()) out.add(serialize(state));
			return out;
		}
	}

	private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		static Box of(BlockPos a, BlockPos b) {
			return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
					Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		}

		long volume() {
			return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		}

		boolean isShell(int x, int y, int z) {
			return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
		}

		JsonObject minJson() {
			return xyz(minX, minY, minZ);
		}

		JsonObject maxJson() {
			return xyz(maxX, maxY, maxZ);
		}

		private static JsonObject xyz(int x, int y, int z) {
			JsonObject o = new JsonObject();
			o.addProperty("x", x);
			o.addProperty("y", y);
			o.addProperty("z", z);
			return o;
		}
	}

	private static BlockPos readPos(JsonObject args, String key) throws ToolException {
		JsonElement element = args.get(key);
		try {
			if (element != null && element.isJsonArray() && element.getAsJsonArray().size() == 3) {
				JsonArray a = element.getAsJsonArray();
				return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
			}
			if (element != null && element.isJsonObject()) {
				JsonObject o = element.getAsJsonObject();
				return new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
			}
		} catch (RuntimeException ignored) {
			// fall through to the error below
		}
		throw new ToolException("'" + key + "' must be an object {x, y, z} with integer coordinates.");
	}

	private static int readInt(JsonObject args, String key, String axis) throws ToolException {
		try {
			return args.getAsJsonObject(key).get(axis).getAsInt();
		} catch (RuntimeException e) {
			throw new ToolException("'" + key + "' must be an object with an integer '" + axis + "'.");
		}
	}

	private static int readOptionalInt(JsonObject args, String key, int fallback) throws ToolException {
		if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
		try {
			return args.get(key).getAsInt();
		} catch (RuntimeException e) {
			throw new ToolException("'" + key + "' must be an integer.");
		}
	}

	private static boolean readBoolean(JsonObject args, String key, boolean fallback) {
		if (!args.has(key) || args.get(key).isJsonNull()) return fallback;
		return args.get(key).getAsBoolean();
	}

	private static String readString(JsonObject args, String key) throws ToolException {
		if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
			throw new ToolException("'" + key + "' is required.");
		}
		return args.get(key).getAsString();
	}

	// ---------------------------------------------------------------- JSON schema helpers

	private static JsonObject schema(JsonObject properties, String... required) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		if (required.length > 0) schema.add("required", stringArray(required));
		return schema;
	}

	private static JsonObject prop(String type, String description) {
		JsonObject prop = new JsonObject();
		prop.addProperty("type", type);
		if (description != null) prop.addProperty("description", description);
		return prop;
	}

	private static JsonObject posSchema(String description) {
		JsonObject props = new JsonObject();
		props.add("x", prop("integer", null));
		props.add("y", prop("integer", null));
		props.add("z", prop("integer", null));
		JsonObject schema = schema(props, "x", "y", "z");
		schema.addProperty("description", description);
		return schema;
	}

	private static JsonObject columnSchema(String description) {
		JsonObject props = new JsonObject();
		props.add("x", prop("integer", null));
		props.add("z", prop("integer", null));
		JsonObject schema = schema(props, "x", "z");
		schema.addProperty("description", description);
		return schema;
	}

	private static JsonObject dimensionSchema() {
		return prop("string", "Dimension id such as minecraft:overworld or minecraft:the_nether. " +
				"Defaults to the dimension of the first online player.");
	}

	private static JsonArray stringArray(String... values) {
		JsonArray array = new JsonArray();
		for (String value : values) array.add(value);
		return array;
	}
}
