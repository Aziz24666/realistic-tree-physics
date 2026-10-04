package com.megadreamsstudios.treefalling.client;

import com.megadreamsstudios.treefalling.TreeFallingEntity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

public final class TreeFallingRenderState extends EntityRenderState {
    private TreeFallingEntity.TreeData treeData = TreeFallingEntity.TreeData.empty();
    private float angle;
    private float axisYaw;
    private int fractureRoot = -1;
    private float fractureProgress;

    public TreeFallingEntity.TreeData treeData() {
        return treeData;
    }

    public void treeData(TreeFallingEntity.TreeData treeData) {
        this.treeData = treeData;
    }

    public float angle() {
        return angle;
    }

    public void angle(float angle) {
        this.angle = angle;
    }

    public float axisYaw() {
        return axisYaw;
    }

    public void axisYaw(float axisYaw) {
        this.axisYaw = axisYaw;
    }

    public int fractureRoot() {
        return fractureRoot;
    }

    public void fractureRoot(int fractureRoot) {
        this.fractureRoot = fractureRoot;
    }

    public float fractureProgress() {
        return fractureProgress;
    }

    public void fractureProgress(float fractureProgress) {
        this.fractureProgress = fractureProgress;
    }
}
