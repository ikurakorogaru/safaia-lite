package com.example.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import net.minecraft.server.level.ParticleStatus;
import org.slf4j.LoggerFactory;

/** Only runs when explicitly selected in the GUI or via a command. */
public final class PerformancePreset {
    private static final Path BACKUP = FabricLoader.getInstance().getConfigDir()
        .resolve("safaia-video-backup.properties");
    private static final Path LAST_RESTORED = BACKUP.resolveSibling("safaia-video-last-restored.properties");
    private PerformancePreset() {}

    public static String apply() {
        Options options = Minecraft.getInstance().options;
        try {
            if (Files.exists(BACKUP)) {
                readBackup(BACKUP); // Do not apply over an unreadable existing recovery file.
            } else {
                VideoBackup.saveOriginal(BACKUP, capture(options).properties());
            }
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger("safaia00").warn("Cannot preserve video settings", e);
            return "元の画質設定を保存できないため、変更しませんでした。";
        }
        // Never increase a distance the user already set lower.
        options.renderDistance().set(Math.min(options.renderDistance().get(), 8));
        options.simulationDistance().set(Math.min(options.simulationDistance().get(), 5));
        options.entityDistanceScaling().set(Math.min(options.entityDistanceScaling().get(), 0.5));
        options.cloudStatus().set(CloudStatus.OFF);
        options.particles().set(ParticleStatus.MINIMAL);
        options.entityShadows().set(false);
        options.ambientOcclusion().set(false);
        options.biomeBlendRadius().set(0);
        options.weatherRadius().set(3);
        options.chunkSectionFadeInTime().set(0.0);
        options.save();
        return "軽量プリセットを適用しました。「画質設定を元に戻す」で復元できます。";
    }

    public static String restore() {
        Path source = Files.exists(BACKUP) ? BACKUP : LAST_RESTORED;
        if (!Files.exists(source)) return "復元する画質設定がありません。";
        try {
            Saved values = readBackup(source); // Parse every value before applying any of them.
            Options options = Minecraft.getInstance().options;
            valid(options.renderDistance(), values.render);
            valid(options.simulationDistance(), values.simulation);
            valid(options.entityDistanceScaling(), values.entities);
            valid(options.cloudStatus(), values.clouds);
            valid(options.particles(), values.particles);
            valid(options.entityShadows(), values.shadows);
            valid(options.ambientOcclusion(), values.ambient);
            valid(options.biomeBlendRadius(), values.biome);
            valid(options.weatherRadius(), values.weather);
            valid(options.chunkSectionFadeInTime(), values.fade);
            options.renderDistance().set(values.render);
            options.simulationDistance().set(values.simulation);
            options.entityDistanceScaling().set(values.entities);
            options.cloudStatus().set(values.clouds);
            options.particles().set(values.particles);
            options.entityShadows().set(values.shadows);
            options.ambientOcclusion().set(values.ambient);
            options.biomeBlendRadius().set(values.biome);
            options.weatherRadius().set(values.weather);
            options.chunkSectionFadeInTime().set(values.fade);
            options.save();
            // Options.save() logs I/O failures internally. Keep a recovery copy even after restore.
            if (source.equals(BACKUP)) VideoBackup.retainRestored(BACKUP, LAST_RESTORED);
            return "軽量プリセット適用前の画質設定に戻しました。";
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger("safaia00").warn("Cannot restore video settings", e);
            return "画質設定の復元またはバックアップの後処理に失敗しました。ログを確認してください。";
        }
    }

    private static Saved capture(Options o) {
        return new Saved(o.renderDistance().get(), o.simulationDistance().get(),
            o.entityDistanceScaling().get(), o.cloudStatus().get(), o.particles().get(),
            o.entityShadows().get(), o.ambientOcclusion().get(), o.biomeBlendRadius().get(),
            o.weatherRadius().get(), o.chunkSectionFadeInTime().get());
    }
    private static <T> void valid(OptionInstance<T> option, T value) {
        if (option.values().validateValue(value).filter(value::equals).isEmpty()) {
            throw new IllegalArgumentException("Backup value is outside the current option range: " + value);
        }
    }
    private static Saved readBackup(Path source) throws IOException {
        Properties p = VideoBackup.read(source);
        return new Saved(Integer.parseInt(required(p,"render")), Integer.parseInt(required(p,"simulation")),
            finite(p,"entities"), CloudStatus.valueOf(required(p,"clouds")),
            ParticleStatus.valueOf(required(p,"particles")), bool(p,"shadows"), bool(p,"ambient"),
            Integer.parseInt(required(p,"biome")), Integer.parseInt(required(p,"weather")), finite(p,"fade"));
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static double finite(Properties p, String key) {
        double value = Double.parseDouble(required(p,key));
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key);
        return value;
    }
    private static boolean bool(Properties p, String key) {
        String value = required(p,key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key);
        return Boolean.parseBoolean(value);
    }
    private record Saved(int render, int simulation, double entities, CloudStatus clouds,
                         ParticleStatus particles, boolean shadows, boolean ambient,
                         int biome, int weather, double fade) {
        Properties properties() {
            Properties p = new Properties();
            p.setProperty("render", Integer.toString(render));
            p.setProperty("simulation", Integer.toString(simulation));
            p.setProperty("entities", Double.toString(entities));
            p.setProperty("clouds", clouds.name());
            p.setProperty("particles", particles.name());
            p.setProperty("shadows", Boolean.toString(shadows));
            p.setProperty("ambient", Boolean.toString(ambient));
            p.setProperty("biome", Integer.toString(biome));
            p.setProperty("weather", Integer.toString(weather));
            p.setProperty("fade", Double.toString(fade));
            return p;
        }
    }
}
