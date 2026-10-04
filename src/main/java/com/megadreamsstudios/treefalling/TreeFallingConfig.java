package com.megadreamsstudios.treefalling;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record TreeFallingConfig(
        boolean enabled,
        int minimumBlocks,
        int maximumBlocks,
        int leafRadius,
        int maximumLeaves,
        double initialAngularVelocity,
        double angularAcceleration,
        double airDamping,
        int settleTicks,
        double trunkFlex,
        double branchFlex,
        double fractureAngle,
        int fractureDelayTicks,
        double impactBounce,
        int collisionSamples,
        boolean restoreStump,
        boolean persistFallingTrees
) {
    public static TreeFallingConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("treefalling.properties");
        Properties p = defaults();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Properties loaded = new Properties();
                loaded.load(reader);
                p.putAll(loaded);
            } catch (IOException ignored) {
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    p.store(writer, "Realistic Tree Physics configuration");
                }
            } catch (IOException ignored) {
            }
        }

        return new TreeFallingConfig(
                bool(p, "enabled", true),
                integer(p, "minimum_blocks", 3, 1, 64),
                integer(p, "maximum_blocks", 768, 16, 4096),
                integer(p, "leaf_radius", 3, 1, 8),
                integer(p, "maximum_leaves", 768, 0, 8192),
                decimal(p, "initial_angular_velocity", 0.008, 0.0, 0.20),
                decimal(p, "angular_acceleration", 0.028, 0.0, 0.15),
                decimal(p, "air_damping", 0.992, 0.85, 1.0),
                integer(p, "settle_ticks", 16, 1, 200),
                decimal(p, "trunk_flex", 0.20, 0.0, 0.80),
                decimal(p, "branch_flex", 0.38, 0.0, 1.20),
                decimal(p, "fracture_angle", 0.34, 0.05, 1.50),
                integer(p, "fracture_delay_ticks", 8, 1, 40),
                decimal(p, "impact_bounce", 0.014, 0.0, 0.08),
                integer(p, "collision_samples", 5, 2, 12),
                bool(p, "restore_stump", true),
                bool(p, "persist_falling_trees", true)
        );
    }

    private static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("minimum_blocks", "3");
        p.setProperty("maximum_blocks", "768");
        p.setProperty("leaf_radius", "3");
        p.setProperty("maximum_leaves", "768");
        p.setProperty("initial_angular_velocity", "0.008");
        p.setProperty("angular_acceleration", "0.028");
        p.setProperty("air_damping", "0.992");
        p.setProperty("settle_ticks", "16");
        p.setProperty("trunk_flex", "0.20");
        p.setProperty("branch_flex", "0.38");
        p.setProperty("fracture_angle", "0.34");
        p.setProperty("fracture_delay_ticks", "8");
        p.setProperty("impact_bounce", "0.014");
        p.setProperty("collision_samples", "5");
        p.setProperty("restore_stump", "true");
        p.setProperty("persist_falling_trees", "true");
        return p;
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(fallback)));
    }

    private static int integer(Properties p, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double decimal(Properties p, String key, double fallback, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(p.getProperty(key, Double.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
