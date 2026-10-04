package com.megadreamsstudios.treefalling.client;

import com.megadreamsstudios.treefalling.TreeFallingEntity;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public final class TreeFallingClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(TreeFallingEntity.TYPE, TreeFallingEntityRenderer::new);
    }
}
