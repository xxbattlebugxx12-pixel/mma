package com.dayssky.mma.features;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.MMAConfig.Waypoints;
import com.dayssky.mma.util.BlockPosAdapter;
import com.dayssky.mma.util.ChatUtil;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WaypointManager {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("mma");
    private static final Path WAYPOINTS_DIR = CONFIG_DIR.resolve("waypoints");
    private static final Path SELECTIONS_FILE = CONFIG_DIR.resolve("waypoint_selections.json");
    private static final Path OLD_PATH = FabricLoader.getInstance().getConfigDir().resolve("mma-waypoint.json");
    private static final Path OLD_FMA_PATH = FabricLoader.getInstance().getConfigDir().resolve("fma-waypoint.json");
    private static final Gson COMPACT_GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(BlockPos.class, new BlockPosAdapter())
            .create();

    private static final Codec<Map<ResourceLocation, List<BlockPos>>> CODEC = Codec.unboundedMap(
            ResourceLocation.CODEC,
            BlockPos.CODEC.listOf()
    );

    private final Minecraft minecraft = Minecraft.getInstance();

    // data stores
    private Map<ResourceLocation, Set<WaypointEntry>> byWorld = new HashMap<>();
    private Map<ResourceLocation, Set<BlockPos>> brokenByWorld = new HashMap<>();
    private Map<ResourceLocation, String> currentWaypointFiles = new HashMap<>();

    // pending removal (double‑tap protection)
    private BlockPos pendingRemovePos = null;
    private long pendingRemoveTime = 0;
    private static final long REMOVE_CONFIRM_TICKS = 100; // 5 seconds

    private CompletableFuture<Void> currCompletionToken = CompletableFuture.completedFuture(null);
    private final WaypointRenderer renderer = new WaypointRenderer(this);

    // keybinds
    private final KeyMapping toggleRecordingKey = new KeyMapping(
            "key.mma.toggleChestWaypointRecording",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "category.mma");
    private final KeyMapping toggleKey = new KeyMapping(
            "key.mma.toggleChestWaypoint",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "category.mma");

    private Waypoints getConfig() {
        return MMAClient.config().waypoints;
    }

    // ------------------------------------------------------------------------
    // Path helpers
    // ------------------------------------------------------------------------
    private Path getWorldDir(ResourceLocation worldId) {
        String safe = worldId.toString().replace(':', '_');
        return WAYPOINTS_DIR.resolve(safe);
    }

    private Path getWaypointFilePath(ResourceLocation worldId, String filename) {
        String safe = filename.endsWith(".json") ? filename : filename + ".json";
        return getWorldDir(worldId).resolve(safe);
    }

    private Path getDefaultWaypointFilePath(ResourceLocation worldId) {
        return getWaypointFilePath(worldId, "default.json");
    }

    private Path getCurrentWaypointFilePath(ResourceLocation worldId) {
        String f = currentWaypointFiles.getOrDefault(worldId, "default");
        return getWaypointFilePath(worldId, f);
    }

    // ------------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------------
    public void init() {
        try {
            Files.createDirectories(WAYPOINTS_DIR);
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to create waypoints directory", e);
        }
        load();
        KeyBindingHelper.registerKeyBinding(toggleRecordingKey);
        KeyBindingHelper.registerKeyBinding(toggleKey);

        AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            add(level, pos, true);
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            if (player.isCrouching()) {
                remove(world.dimension().location(), hit.getBlockPos());
            } else {
                add(world, hit.getBlockPos(), false);
            }
            return InteractionResult.PASS;
        });
    }

    public void clientInit() { }

    public void tick() {
        if (toggleRecordingKey.consumeClick()) {
            getConfig().recordChests = !getConfig().recordChests;
            ChatUtil.send(Component.literal("chest recording: " + (getConfig().recordChests ? "enabled" : "disabled")));
        }
        if (toggleKey.consumeClick()) {
            getConfig().enable = !getConfig().enable;
            ChatUtil.send(Component.literal("chest waypoints: " + (getConfig().enable ? "enabled" : "disabled")));
        }
    }

    // ------------------------------------------------------------------------
    // Core data operations
    // ------------------------------------------------------------------------
    private void add(Level level, BlockPos pos, boolean isBreaking) {
        if (!getConfig().enable) return;
        if (level.getBlockState(pos).getBlock() != Blocks.CHEST) return;

        var dim = level.dimension().location();
        if (getConfig().disableInPlots && dim.getPath().contains("plot")) return;
        if (getConfig().disabledWorlds.contains(dim.toString())) return;

        if (!byWorld.containsKey(dim)) loadWorldData(dim);

        if (getConfig().skipBrokenChests) {
            brokenByWorld.computeIfAbsent(dim, k -> new HashSet<>()).add(pos);
        }

        var entries = byWorld.computeIfAbsent(dim, k -> new HashSet<>());
        entries.add(new WaypointEntry(pos));
        save(dim);
    }

    void remove(ResourceLocation worldId, BlockPos pos) {
        var entries = byWorld.get(worldId);
        if (entries != null) {
            entries.removeIf(e -> e.pos().equals(pos));
            save(worldId);
        }
        var broken = brokenByWorld.get(worldId);
        if (broken != null) broken.remove(pos);
    }

    public void clearCurrentWorld() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var id = level.dimension().location();
        byWorld.put(id, new HashSet<>());
        brokenByWorld.put(id, new HashSet<>());
        save(id);
        ChatUtil.send(Component.literal("Cleared all waypoints for current world."));
    }

    public void reloadCurrentWorld() {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var id = level.dimension().location();
        byWorld.remove(id);
        brokenByWorld.remove(id);
        loadWorldData(id);
        ChatUtil.send(Component.literal("Reloaded waypoints from current file."));
    }

    public List<String> listWaypointFiles() {
        var level = MMAClient.level();
        if (level == null) return List.of();
        return getWaypointFileNames(level.dimension().location());
    }

    // ------------------------------------------------------------------------
    // Removal keybind handler
    // ------------------------------------------------------------------------
    public void handleRemoveWaypoint() {
        BlockPos target = findClosestWaypointInSight(64, Math.cos(Math.toRadians(30)));
        if (target == null) {
            ChatUtil.send(Component.literal("No waypoint in sight."));
            pendingRemovePos = null;
            return;
        }

        long now = minecraft.level != null ? minecraft.level.getGameTime() : 0;
        if (pendingRemovePos != null && pendingRemovePos.equals(target) && (now - pendingRemoveTime) < REMOVE_CONFIRM_TICKS) {
            var level = MMAClient.level();
            if (level != null) {
                remove(level.dimension().location(), target);
                ChatUtil.send(Component.literal("Waypoint removed."));
            }
            pendingRemovePos = null;
        } else {
            pendingRemovePos = target;
            pendingRemoveTime = now;
            ChatUtil.send(Component.literal("Press again within 5 seconds to remove waypoint at " + target.toShortString()));
        }
    }

    // ------------------------------------------------------------------------
    // Find waypoint in crosshair
    // ------------------------------------------------------------------------
    public BlockPos findClosestWaypointInSight(double maxDistance, double maxAngleCos) {
        var player = minecraft.player;
        if (player == null) return null;
        var worldId = player.level().dimension().location();
        var entries = byWorld.get(worldId);
        if (entries == null || entries.isEmpty()) return null;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();

        BlockPos best = null;
        double bestDot = maxAngleCos;

        for (WaypointEntry entry : entries) {
            BlockPos pos = entry.pos();
            Vec3 center = Vec3.atCenterOf(pos);
            Vec3 to = center.subtract(eye);
            double distance = to.length();
            if (distance > maxDistance) continue;

            Vec3 direction = to.normalize();
            double dot = look.dot(direction);
            if (dot > bestDot) {
                bestDot = dot;
                best = pos;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------
    // Color (always base color)
    // ------------------------------------------------------------------------
    public int getColorForWaypoint(WaypointEntry entry, Vec3 playerPos) {
        return getConfig().baseColor;
    }

    // ------------------------------------------------------------------------
    // Public accessors for renderer
    // ------------------------------------------------------------------------
    public Iterable<WaypointEntry> getEntries() {
        var level = MMAClient.level();
        if (level == null) return Set.of();
        var worldId = level.dimension().location();
        if (!byWorld.containsKey(worldId)) loadWorldData(worldId);
        var entries = byWorld.getOrDefault(worldId, Set.of());
        var broken = brokenByWorld.computeIfAbsent(worldId, k -> new HashSet<>());

        Vec3 playerPos = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        long radiusSq = (long) getConfig().radius * getConfig().radius;

        return entries.stream()
                .filter(e -> {
                    if (getConfig().skipBrokenChests && broken.contains(e.pos())) return false;
                    if (getConfig().radius <= 0) return true;
                    double dx = e.pos().getX() - playerPos.x;
                    double dy = e.pos().getY() - playerPos.y;
                    double dz = e.pos().getZ() - playerPos.z;
                    return dx*dx + dy*dy + dz*dz <= radiusSq;
                })
                .toList();
    }

    public void renderFilled(WorldRenderContext context) {
        renderer.renderFilled(context);
    }

    public void renderOutline(WorldRenderContext context) {
        renderer.renderOutline(context);
    }

    public void renderLabels(WorldRenderContext context) {
        renderer.renderLabels(context);
    }

    // ------------------------------------------------------------------------
    // File I/O (world waypoints)
    // ------------------------------------------------------------------------
    private void loadWorldData(ResourceLocation worldId) {
        String current = getCurrentFilename(worldId);
        Path path = getWaypointFilePath(worldId, current);
        if (!Files.exists(path)) {
            byWorld.put(worldId, new HashSet<>());
            brokenByWorld.put(worldId, new HashSet<>());
            return;
        }
        try (var in = Files.newBufferedReader(path)) {
            JsonElement json = MMAClient.GSON.fromJson(in, JsonElement.class);
            Set<WaypointEntry> loaded = new HashSet<>();
            if (json.isJsonArray()) {
                List<BlockPos> old = MMAClient.GSON.fromJson(json, new TypeToken<List<BlockPos>>() {}.getType());
                if (old != null) old.forEach(pos -> loaded.add(new WaypointEntry(pos)));
            } else if (json.isJsonObject()) {
                var array = json.getAsJsonObject().getAsJsonArray("entries");
                if (array != null) {
                    for (var elem : array) {
                        var obj = elem.getAsJsonObject();
                        BlockPos pos = MMAClient.GSON.fromJson(obj.get("pos"), BlockPos.class);
                        loaded.add(new WaypointEntry(pos));
                    }
                }
            }
            byWorld.put(worldId, loaded);
            brokenByWorld.put(worldId, new HashSet<>());
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load waypoints for " + worldId, e);
            byWorld.put(worldId, new HashSet<>());
            brokenByWorld.put(worldId, new HashSet<>());
        }
    }

    private void save(ResourceLocation worldId) {
        Preconditions.checkState(minecraft.isSameThread());
        Set<WaypointEntry> entries = byWorld.getOrDefault(worldId, Set.of());
        Path path = getCurrentWaypointFilePath(worldId);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                JsonWriter writer = new JsonWriter(w);
                writer.setIndent("  ");
                writer.beginObject();
                writer.name("version").value(1);
                writer.name("entries");
                writer.beginArray();
                for (WaypointEntry entry : entries) {
                    writer.jsonValue(COMPACT_GSON.toJson(entry.pos()));
                }
                writer.endArray();
                writer.endObject();
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save waypoints for " + worldId, e);
        }
    }

    // ------------------------------------------------------------------------
    // Convenience methods for current world (called from Commands)
    // ------------------------------------------------------------------------
    public void createWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot create file named 'default' – reserved."));
            return;
        }
        Path path = getWaypointFilePath(worldId, filename);
        if (Files.exists(path)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' already exists."));
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("entries", MMAClient.GSON.toJsonTree(new ArrayList<BlockPos>()));
            try (var w = Files.newBufferedWriter(path)) {
                MMAClient.GSON.toJson(root, w);
            }
            currentWaypointFiles.put(worldId, filename);
            saveSelections();
            loadWorldData(worldId);
            ChatUtil.send(Component.literal("Created and switched to waypoint file: " + filename));
        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to create waypoint file: " + e.getMessage()));
            MMAClient.LOGGER.warn("Failed to create waypoint file", e);
        }
    }

    public void loadWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        Path path = getWaypointFilePath(worldId, filename);
        if (!Files.exists(path)) {
            ChatUtil.send(Component.literal("Waypoint file '" + filename + "' does not exist."));
            return;
        }
        currentWaypointFiles.put(worldId, filename);
        saveSelections();
        loadWorldData(worldId);
        ChatUtil.send(Component.literal("Loaded waypoint file: " + filename));
    }

    public void deleteWaypointFile(String filename) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();
        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot delete default waypoint file."));
            return;
        }
        String current = getCurrentFilename(worldId);
        if (filename.equals(current)) {
            ChatUtil.send(Component.literal("Cannot delete the currently selected waypoint file. Switch to another file first."));
            return;
        }
        Path path = getWaypointFilePath(worldId, filename);
        try {
            Files.deleteIfExists(path);
            ChatUtil.send(Component.literal("Deleted waypoint file: " + filename));
        } catch (IOException e) {
            ChatUtil.send(Component.literal("Failed to delete waypoint file: " + e.getMessage()));
        }
    }

    public List<String> getCurrentWorldWaypointFiles() {
        var level = MMAClient.level();
        if (level == null) return List.of();
        return getWaypointFileNames(level.dimension().location());
    }

    public void mergeWaypointFiles(String source, String destination) {
        mergeWaypointFilesForce(source, destination, false);
    }

    public void mergeWaypointFilesForce(String sourceFilename, String destinationFilename, boolean replaceDuplicates) {
        var level = MMAClient.level();
        if (level == null) {
            ChatUtil.send(Component.literal("Not in a world."));
            return;
        }
        var worldId = level.dimension().location();

        if (sourceFilename.equalsIgnoreCase(destinationFilename)) {
            ChatUtil.send(Component.literal("Cannot merge a file into itself."));
            return;
        }

        Path sourcePath = getWaypointFilePath(worldId, sourceFilename);
        Path destinationPath = getWaypointFilePath(worldId, destinationFilename);

        if (!Files.exists(sourcePath)) {
            ChatUtil.send(Component.literal("Source file '" + sourceFilename + "' does not exist."));
            return;
        }
        if (!Files.exists(destinationPath)) {
            ChatUtil.send(Component.literal("Destination file '" + destinationFilename + "' does not exist."));
            return;
        }

        try {
            Set<WaypointEntry> sourceData = loadWaypointFileData(worldId, sourceFilename);
            if (sourceData.isEmpty()) {
                ChatUtil.send(Component.literal("Source file is empty. Nothing to merge."));
                return;
            }

            Set<WaypointEntry> destinationData = loadWaypointFileData(worldId, destinationFilename);
            int before = destinationData.size();

            if (replaceDuplicates) {
                Map<BlockPos, WaypointEntry> destMap = new HashMap<>();
                for (WaypointEntry e : destinationData) destMap.put(e.pos(), e);
                for (WaypointEntry e : sourceData) destMap.put(e.pos(), e);
                destinationData = new HashSet<>(destMap.values());
            } else {
                destinationData.addAll(sourceData);
            }

            int added = destinationData.size() - before;
            saveWaypointFileData(worldId, destinationFilename, destinationData);

            String current = getCurrentFilename(worldId);
            if (destinationFilename.equals(current)) {
                loadWorldData(worldId);
            }

            ChatUtil.send(Component.literal("Merged " + added + " waypoints into '" + destinationFilename + "'. Total: " + destinationData.size()));
        } catch (Exception e) {
            ChatUtil.send(Component.literal("Failed to merge waypoint files: " + e.getMessage()));
            MMAClient.LOGGER.warn("Failed to merge waypoint files", e);
        }
    }

    // ------------------------------------------------------------------------
    // Cross-world editing
    // ------------------------------------------------------------------------
    public Set<WaypointEntry> loadWaypointFileData(ResourceLocation worldId, String filename) {
        Path path = getWaypointFilePath(worldId, filename);
        if (!Files.exists(path)) return new HashSet<>();
        try (var in = Files.newBufferedReader(path)) {
            JsonElement json = MMAClient.GSON.fromJson(in, JsonElement.class);
            Set<WaypointEntry> loaded = new HashSet<>();
            if (json.isJsonArray()) {
                List<BlockPos> old = MMAClient.GSON.fromJson(json, new TypeToken<List<BlockPos>>() {}.getType());
                if (old != null) old.forEach(pos -> loaded.add(new WaypointEntry(pos)));
            } else if (json.isJsonObject()) {
                var array = json.getAsJsonObject().getAsJsonArray("entries");
                if (array != null) {
                    for (var elem : array) {
                        var obj = elem.getAsJsonObject();
                        BlockPos pos = MMAClient.GSON.fromJson(obj.get("pos"), BlockPos.class);
                        loaded.add(new WaypointEntry(pos));
                    }
                }
            }
            return loaded;
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load file " + filename + " for world " + worldId, e);
            return new HashSet<>();
        }
    }

    public void saveWaypointFileData(ResourceLocation worldId, String filename, Set<WaypointEntry> data) {
        Path path = getWaypointFilePath(worldId, filename);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                JsonWriter writer = new JsonWriter(w);
                writer.setIndent("  ");
                writer.beginObject();
                writer.name("version").value(1);
                writer.name("entries");
                writer.beginArray();
                for (WaypointEntry entry : data) {
                    writer.jsonValue(COMPACT_GSON.toJson(entry.pos()));
                }
                writer.endArray();
                writer.endObject();
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save file " + filename + " for world " + worldId, e);
        }
    }

    public List<String> getWaypointFileNames(ResourceLocation worldId) {
        Path dir = getWorldDir(worldId);
        if (!Files.exists(dir)) return List.of();
        try {
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString())
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to list waypoint files for " + worldId, e);
            return List.of();
        }
    }

    public void deleteWaypointFile(ResourceLocation worldId, String filename) {
        if (filename.equalsIgnoreCase("default")) {
            ChatUtil.send(Component.literal("Cannot delete default waypoint file."));
            return;
        }
        Path path = getWaypointFilePath(worldId, filename);
        try {
            Files.deleteIfExists(path);
            ChatUtil.send(Component.literal("Deleted waypoint file: " + filename));
        } catch (IOException e) {
            ChatUtil.send(Component.literal("Failed to delete: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------
    // Helper: current filename, selections
    // ------------------------------------------------------------------------
    private String getCurrentFilename(ResourceLocation worldId) {
        return currentWaypointFiles.getOrDefault(worldId, "default");
    }

    private void loadSelections() {
        if (!Files.exists(SELECTIONS_FILE)) return;
        try (var in = Files.newBufferedReader(SELECTIONS_FILE)) {
            Map<String, String> sel = MMAClient.GSON.fromJson(in, new TypeToken<Map<String, String>>() {}.getType());
            if (sel != null) {
                currentWaypointFiles.clear();
                sel.forEach((k, v) -> currentWaypointFiles.put(new ResourceLocation(k), v));
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to load selections", e);
        }
    }

    private void saveSelections() {
        Map<String, String> out = new HashMap<>();
        currentWaypointFiles.forEach((k, v) -> out.put(k.toString(), v));
        try {
            Files.createDirectories(SELECTIONS_FILE.getParent());
            try (var w = Files.newBufferedWriter(SELECTIONS_FILE)) {
                MMAClient.GSON.toJson(out, w);
            }
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to save waypoint selections", e);
        }
    }

    public static List<String> getWorldsWithWaypointFiles() {
        try {
            return Files.list(WAYPOINTS_DIR)
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString().replace('_', ':'))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            MMAClient.LOGGER.warn("Failed to list worlds with waypoint files", e);
            return List.of();
        }
    }

    private void load() {
        migrateOldData();
        loadSelections();
        byWorld.clear();
        brokenByWorld.clear();
    }

    // Migration functions
    private void migrateOldData() {
        if (Files.exists(OLD_FMA_PATH)) {
            try {
                migrateFromOldFile(OLD_FMA_PATH);
                Files.deleteIfExists(OLD_FMA_PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from fma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from fma-waypoint.json", e);
            }
        }
        if (Files.exists(OLD_PATH)) {
            try {
                migrateFromOldFile(OLD_PATH);
                Files.deleteIfExists(OLD_PATH);
                MMAClient.LOGGER.info("Migrated waypoint data from mma-waypoint.json");
            } catch (Exception e) {
                MMAClient.LOGGER.warn("Failed to migrate waypoint data from mma-waypoint.json", e);
            }
        }
    }

    private void migrateFromOldFile(Path oldPath) {
        try (var in = Files.newBufferedReader(oldPath)) {
            var obj = MMAClient.GSON.fromJson(in, JsonElement.class);
            Map<ResourceLocation, List<BlockPos>> oldData = CODEC.decode(JsonOps.INSTANCE, obj)
                    .result().orElseThrow().getFirst();

            for (Map.Entry<ResourceLocation, List<BlockPos>> entry : oldData.entrySet()) {
                Path worldPath = getDefaultWaypointFilePath(entry.getKey());
                Files.createDirectories(worldPath.getParent());
                try (var w = Files.newBufferedWriter(worldPath)) {
                    MMAClient.GSON.toJson(entry.getValue(), w);
                }
                currentWaypointFiles.put(entry.getKey(), "default");
            }
            saveSelections();
        } catch (Exception e) {
            MMAClient.LOGGER.warn("Failed to migrate from old file", e);
        }
    }
}