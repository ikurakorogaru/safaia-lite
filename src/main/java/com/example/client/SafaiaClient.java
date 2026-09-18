package com.example.client;

import com.example.client.ChunkOffsets;
import com.example.client.SafaiaScreen;
import com.example.optimization.SafaiaCacheManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Properties;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.LoggerFactory;

public class SafaiaClient
implements ClientModInitializer {
    private static boolean optimizationEnabled;
    private static boolean openRequested;
    private static int cleanupTicks;
    private static ClientLevel previousLevel;
    private static final Path CONFIG;

    public void onInitializeClient() {
        if (Files.exists(CONFIG)) {
            try (BufferedReader reader = Files.newBufferedReader(CONFIG);){
                Properties properties = new Properties();
                properties.load(reader);
                optimizationEnabled = Boolean.parseBoolean(properties.getProperty("autoCleanup", "false"));
            } catch (Exception e) {
                LoggerFactory.getLogger("safaia00").warn("Cannot read Safaia settings", e);
            }
        }
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.safaia.open", 297, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != previousLevel) {
                SafaiaCacheManager.clear();
                previousLevel = client.level;
                cleanupTicks = 0;
            }
            while (key.consumeClick()) {
                openRequested = true;
            }
            if (openRequested) {
                openRequested = false;
                client.gui.setScreen(new SafaiaScreen(client.gui.screen()));
            }
            if (optimizationEnabled && client.level != null && ++cleanupTicks >= 1200) {
                cleanupTicks = 0;
                if (SafaiaCacheManager.getCacheCount() != 0) SafaiaCacheManager.cleanup();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(ClientCommands.literal("safaiatest")
                .executes(c -> feedback(c, status())));
            dispatcher.register(ClientCommands.literal("safaia")
                .executes(c -> requestOpen())
                .then(ClientCommands.literal("gui").executes(c -> requestOpen()))
                .then(ClientCommands.literal("preset")
                    .then(ClientCommands.literal("lite").executes(c -> feedback(c, PerformancePreset.apply())))
                    .then(ClientCommands.literal("restore").executes(c -> feedback(c, PerformancePreset.restore()))))
                .then(ClientCommands.literal("optimize")
                    .then(ClientCommands.literal("on").executes(c -> feedback(c, setOptimization(true))))
                    .then(ClientCommands.literal("off").executes(c -> feedback(c, setOptimization(false))))
                    .then(ClientCommands.literal("status").executes(c -> feedback(c,
                        "自動キャッシュ整理: " + (optimizationEnabled ? "ON" : "OFF")))))
                .then(ClientCommands.literal("memory")
                    .executes(c -> feedback(c, memoryInfo()))
                    .then(ClientCommands.literal("optimize").executes(c -> feedback(c, cleanup())))
                    .then(ClientCommands.literal("clear").executes(c -> feedback(c, clearCache()))))
                .then(ClientCommands.literal("chunks").executes(c -> feedback(c, chunkInfo())))
                .then(ClientCommands.literal("chunksdrop")
                    .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                            .executes(c -> feedback(c, dropChunk(IntegerArgumentType.getInteger(c, "x"),
                                IntegerArgumentType.getInteger(c, "z")))))))
                .then(ClientCommands.literal("chunksunload")
                    .executes(c -> feedback(c, unloadFarChunks(20)))
                    .then(ClientCommands.argument("limit", IntegerArgumentType.integer(1, 100))
                        .executes(c -> feedback(c, unloadFarChunks(IntegerArgumentType.getInteger(c, "limit")))))));
        });
    }

    private static int requestOpen() {
        openRequested = true;
        return 1;
    }

    private static int feedback(CommandContext<FabricClientCommandSource> c, String text) {
        c.getSource().sendFeedback(Component.literal(("[Safaia] " + text)));
        return 1;
    }

    public static boolean isOptimizationEnabled() {
        return optimizationEnabled;
    }

    public static String setOptimization(boolean enabled) {
        optimizationEnabled = enabled;
        cleanupTicks = 0;
        Properties properties = new Properties();
        properties.setProperty("autoCleanup", Boolean.toString(enabled));
        try {
            Files.createDirectories(CONFIG.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG);){
                properties.store(writer, "Safaia settings");
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("safaia00").warn("Cannot save Safaia settings", e);
            return "設定は適用済みですが、保存できませんでした。";
        }
        return "自動キャッシュ整理を" + (enabled ? "ON" : "OFF") + "にしました。";
    }

    public static String cleanup() {
        return "期限切れキャッシュを " + SafaiaCacheManager.cleanup() + " 件整理しました。";
    }

    public static String clearCache() {
        return "Safaiaのキャッシュを " + SafaiaCacheManager.clear() + " 件削除しました。";
    }

    public static long usedMemory() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / 0x100000L;
    }

    public static long maxMemory() {
        return Runtime.getRuntime().maxMemory() / 0x100000L;
    }

    public static String memoryInfo() {
        return "メモリ " + SafaiaClient.usedMemory() + " / " + SafaiaClient.maxMemory() + " MB・キャッシュ " + SafaiaCacheManager.getCacheCount() + " 件";
    }

    public static int ping() {
        Minecraft c = Minecraft.getInstance();
        if (c.player == null || c.getConnection() == null) {
            return -1;
        }
        PlayerInfo info = c.getConnection().getPlayerInfo(c.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    public static String status() {
        int ping = ping();
        return Minecraft.getInstance().getFps() + " FPS・Ping " + (ping < 0 ? "—" : ping + " ms") + "・" + SafaiaClient.memoryInfo();
    }

    public static boolean inWorld() {
        Minecraft c = Minecraft.getInstance();
        return c.level != null && c.player != null;
    }

    public static String chunkInfo() {
        if (!SafaiaClient.inWorld()) {
            return "ワールドに入ってください。";
        }
        ChunkScan scan = SafaiaClient.scanChunks();
        return "周辺 " + scan.loaded + "・保護範囲 " + scan.near + "・範囲外 " + (scan.loaded - scan.near) + " チャンク（半径 " + scan.radius + "）";
    }

    public static ChunkScan scanChunks() {
        if (!SafaiaClient.inWorld()) {
            return new ChunkScan(0, 0, 0, new boolean[1], 0, 0);
        }
        Minecraft c = Minecraft.getInstance();
        ChunkPos pos = c.player.chunkPosition();
        int radius = Math.min(16, c.options.getEffectiveRenderDistance());
        int side = radius * 2 + 1;
        int loaded = 0;
        int near = 0;
        boolean[] cells = new boolean[side * side];
        for (int dz = -radius; dz <= radius; ++dz) {
            for (int dx = -radius; dx <= radius; ++dx) {
                if (c.level.getChunkSource().getChunk(pos.x() + dx, pos.z() + dz, ChunkStatus.FULL, false) == null) continue;
                cells[(dz + radius) * side + dx + radius] = true;
                ++loaded;
                if (dx * dx + dz * dz > 64) continue;
                ++near;
            }
        }
        return new ChunkScan(radius, pos.x(), pos.z(), cells, loaded, near);
    }

    public static String dropChunk(int x, int z) {
        if (!SafaiaClient.inWorld()) {
            return "ワールドに入ってください。";
        }
        Minecraft c = Minecraft.getInstance();
        ChunkPos pos = c.player.chunkPosition();
        long dx = (long)x - (long)pos.x();
        long dz = (long)z - (long)pos.z();
        if (Math.abs(dx) <= 8L && Math.abs(dz) <= 8L && dx * dx + dz * dz <= 64L) {
            return "現在地から8チャンク以内は保護されています。";
        }
        if (c.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
            return "指定したチャンクは読み込まれていません。";
        }
        c.level.getChunkSource().drop(new ChunkPos(x, z));
        return "チャンク " + x + ", " + z + " を解放しました。";
    }

    public static String unloadFarChunks(int limit) {
        if (!SafaiaClient.inWorld()) {
            return "ワールドに入ってください。";
        }
        Minecraft c = Minecraft.getInstance();
        ChunkPos pos = c.player.chunkPosition();
        int radius = Math.min(16, c.options.getEffectiveRenderDistance());
        int dropped = 0;
        int boundedLimit = Math.clamp(limit, 1, 100);
        for (int index = 0; index < ChunkOffsets.pairCount(); index++) {
            int dx = ChunkOffsets.x(index), dz = ChunkOffsets.z(index);
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) continue;
            if (dropped >= boundedLimit) break;
            int x = pos.x() + dx;
            int z = pos.z() + dz;
            if (c.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) continue;
            c.level.getChunkSource().drop(new ChunkPos(x, z));
            ++dropped;
        }
        return "遠方のチャンクを " + dropped + " 個解放しました。";
    }

    static {
        CONFIG = FabricLoader.getInstance().getConfigDir().resolve("safaia.properties");
    }

    public record ChunkScan(int radius, int centerX, int centerZ, boolean[] cells, int loaded, int near) {
    }
}

