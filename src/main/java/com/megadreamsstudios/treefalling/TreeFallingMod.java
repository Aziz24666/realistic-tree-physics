package com.megadreamsstudios.treefalling;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class TreeFallingMod implements ModInitializer {
    public static final String MOD_ID = "treefalling";
    public static final TreeFallingConfig CONFIG = TreeFallingConfig.load();

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        TreeFallingEntity.register();

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel)) return;
            if (!CONFIG.enabled() || !state.is(BlockTags.LOGS)) return;

            TreeFallingScanner.ScanResult scan = TreeFallingScanner.scan(serverLevel, pos, state, CONFIG);
            if (!scan.naturalTree() || scan.tree() == null) return;

            TreeFallingEntity.launch(serverLevel, pos, scan.tree(), fallDirection(pos, player));
        });
    }

    private static Vec3 fallDirection(BlockPos cutPos, Player player) {
        double x = cutPos.getX() + 0.5 - player.getX();
        double z = cutPos.getZ() + 0.5 - player.getZ();
        double length = Math.sqrt(x * x + z * z);
        if (length < 0.001) {
            double yaw = Math.toRadians(player.getYRot());
            return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        return new Vec3(x / length, 0.0, z / length);
    }
}
