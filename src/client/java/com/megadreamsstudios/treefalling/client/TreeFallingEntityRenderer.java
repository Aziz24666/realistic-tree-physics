package com.megadreamsstudios.treefalling.client;

import com.megadreamsstudios.treefalling.TreeFallingEntity;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

public final class TreeFallingEntityRenderer extends EntityRenderer<TreeFallingEntity, TreeFallingRenderState> {
    private static final Identifier BARK_TEXTURE = Identifier.fromNamespaceAndPath("treefalling", "textures/entity/bark.png");
    private static final Identifier BARK_DARK_TEXTURE = Identifier.fromNamespaceAndPath("treefalling", "textures/entity/bark_dark.png");
    private static final Identifier LEAF_TEXTURE = Identifier.fromNamespaceAndPath("treefalling", "textures/entity/leaf.png");
    private static final Identifier CUT_TEXTURE = Identifier.fromNamespaceAndPath("treefalling", "textures/entity/cut_wood.png");

    private static final RenderType BARK = RenderTypes.entitySolid(BARK_TEXTURE);
    private static final RenderType BARK_DARK = RenderTypes.entitySolid(BARK_DARK_TEXTURE);
    private static final RenderType LEAF = RenderTypes.entityCutout(LEAF_TEXTURE);
    private static final RenderType CUT = RenderTypes.entitySolid(CUT_TEXTURE);

    private static final int SIDES = 10;
    private static final int LEAF_SIDES = 9;
    private static final int LEAF_RINGS = 5;

    public TreeFallingEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
        this.shadowStrength = 0.0F;
    }

    @Override
    public TreeFallingRenderState createRenderState() {
        return new TreeFallingRenderState();
    }

    @Override
    public void extractRenderState(TreeFallingEntity entity, TreeFallingRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.treeData(entity.getTreeData());
        state.angle(entity.getAngle());
        state.axisYaw(entity.getAxisYaw());
        state.fractureRoot(entity.getFractureRoot());
        state.fractureProgress(entity.getFractureProgress());
    }

    @Override
    public void submit(TreeFallingRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        TreeFallingEntity.TreeData data = state.treeData();
        if (data.empty()) {
            return;
        }

        collector.submitCustomGeometry(poseStack, BARK, (pose, consumer) ->
                renderBark(data, state, pose, consumer, state.lightCoords, false));
        collector.submitCustomGeometry(poseStack, BARK_DARK, (pose, consumer) ->
                renderBark(data, state, pose, consumer, state.lightCoords, true));
        collector.submitCustomGeometry(poseStack, CUT, (pose, consumer) ->
                renderCutSurface(data, pose, consumer, state.lightCoords));
        collector.submitCustomGeometry(poseStack, LEAF, (pose, consumer) ->
                renderLeaves(data, state, pose, consumer, state.lightCoords));
        if (state.fractureRoot() >= 0 && state.fractureProgress() > 0.01F) {
            collector.submitCustomGeometry(poseStack, CUT, (pose, consumer) ->
                    renderFracture(data, state, pose, consumer, state.lightCoords));
        }

        super.submit(state, poseStack, collector, camera);
    }

    private static void renderBark(
            TreeFallingEntity.TreeData data,
            TreeFallingRenderState state,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int light,
            boolean darkPass
    ) {
        for (int index = 0; index < data.segments().size(); index++) {
            TreeFallingEntity.TreeSegment segment = data.segments().get(index);
            if (segment.logCount() == 0) {
                // Root flare is only rendered in the base bark pass.
                if (!darkPass) {
                    renderTaperedCylinder(segment, data, state, pose, consumer, light, false, index);
                }
                continue;
            }

            // Alternate subtle bark passes instead of drawing every section identically.
            boolean dark = ((index * 31 + segment.branchRoot() * 17) & 3) == 0;
            if (dark != darkPass) continue;
            renderTaperedCylinder(segment, data, state, pose, consumer, light, darkPass, index);
        }
    }

    private static void renderTaperedCylinder(
            TreeFallingEntity.TreeSegment segment,
            TreeFallingEntity.TreeData data,
            TreeFallingRenderState state,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int light,
            boolean darkPass,
            int segmentIndex
    ) {
        Vec3 a = TreeFallingEntity.transformLocalPoint(
                new Vec3(segment.sx(), segment.sy(), segment.sz()), state.angle(), state.axisYaw(),
                data.treeHeight(), segment.branchRoot(), state.fractureRoot(), state.fractureProgress());
        Vec3 b = TreeFallingEntity.transformLocalPoint(
                new Vec3(segment.ex(), segment.ey(), segment.ez()), state.angle(), state.axisYaw(),
                data.treeHeight(), segment.branchRoot(), state.fractureRoot(), state.fractureProgress());

        Vec3 axis = b.subtract(a);
        double length = Math.max(0.0001, axis.length());
        Vec3 d = axis.scale(1.0 / length);
        Vec3 helper = Math.abs(d.y) < 0.90 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 side = d.cross(helper).normalize();
        Vec3 up = side.cross(d).normalize();

        float startRadius = segment.startRadius();
        float endRadius = segment.endRadius();
        float barkTint = darkPass ? 0.76F : 1.0F;
        int[] color = barkColor(segment.palette(), barkTint);
        int ringCount = segment.branch() ? 2 : 3;

        for (int ring = 0; ring < ringCount; ring++) {
            float t = ring / (float) (ringCount - 1);
            Vec3 center = a.add(axis.scale(t));
            float radius = Mth.lerp(t, startRadius, endRadius);
            // Tiny deterministic irregularity is much more organic than a perfect mathematical pipe.
            float irregular = 1.0F + (((mix(segmentIndex, ring) & 31) / 31.0F) - 0.5F) * 0.08F;
            radius *= irregular;

            for (int sideIndex = 0; sideIndex < SIDES; sideIndex++) {
                float next = (sideIndex + 1) / (float) SIDES;
                float u = sideIndex / (float) SIDES;
                addCylinderVertexPair(consumer, pose, center, side, up, d, radius, sideIndex / (float) SIDES,
                        color, light, barkU(u, length), barkU(next, length),
                        sideIndex, segmentIndex, a, b);
            }
        }

        // End caps are only needed on root flare and short branch tips. Main trunk caps are handled by the cut surface.
        if (segment.logCount() == 0 || segment.branch()) {
            renderCap(consumer, pose, b, d, endRadius, color, light, false);
        }
    }

    private static void addCylinderVertexPair(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 center,
            Vec3 side,
            Vec3 up,
            Vec3 axis,
            float radius,
            float u0,
            float u1,
            int[] color,
            int light,
            float texU0,
            float texU1,
            int sideIndex,
            int segmentIndex,
            Vec3 start,
            Vec3 end
    ) {
        double a0 = Math.PI * 2.0 * u0;
        double a1 = Math.PI * 2.0 * u1;
        float seam = (((sideIndex + segmentIndex * 3) & 3) * 0.08F);
        Vec3 p0 = center.add(side.scale(Math.cos(a0) * radius)).add(up.scale(Math.sin(a0) * radius));
        Vec3 p1 = center.add(side.scale(Math.cos(a1) * radius)).add(up.scale(Math.sin(a1) * radius));
        Vec3 n0 = side.scale(Math.cos(a0)).add(up.scale(Math.sin(a0))).normalize();
        Vec3 n1 = side.scale(Math.cos(a1)).add(up.scale(Math.sin(a1))).normalize();
        float v0 = seam + (center.distanceTo(start) / Math.max(0.001, start.distanceTo(end))) * 1.75F;
        float v1 = seam + (center.distanceTo(start) / Math.max(0.001, start.distanceTo(end))) * 1.75F + 0.04F;

        putVertex(consumer, pose, p0, color, texU0, v0, light, n0);
        putVertex(consumer, pose, p1, color, texU1, v0, light, n1);
        putVertex(consumer, pose, p1, color, texU1, v1, light, n1);
        putVertex(consumer, pose, p0, color, texU0, v1, light, n0);
    }

    private static void renderCap(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 center,
            Vec3 normal,
            float radius,
            int[] color,
            int light,
            boolean underside
    ) {
        Vec3 n = normal.normalize();
        Vec3 helper = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 tangent = n.cross(helper).normalize();
        Vec3 bitangent = tangent.cross(n).normalize();
        int[] capColor = new int[]{color[0], color[1], color[2], 255};
        putVertex(consumer, pose, center, capColor, 0.5F, 0.5F, light, n);
        for (int i = 0; i <= SIDES; i++) {
            double a = Math.PI * 2.0 * i / SIDES;
            Vec3 p = center.add(tangent.scale(Math.cos(a) * radius)).add(bitangent.scale(Math.sin(a) * radius));
            float u = 0.5F + (float) Math.cos(a) * 0.5F;
            float v = 0.5F + (float) Math.sin(a) * 0.5F;
            putVertex(consumer, pose, p, capColor, u, v, light, n);
        }
    }

    private static void renderCutSurface(
            TreeFallingEntity.TreeData data,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int light
    ) {
        // The physical pivot is the freshly cut face: render a broad, irregular wood disc with inner rings.
        Vec3 center = Vec3.ZERO;
        Vec3 n = new Vec3(0, 1, 0);
        Vec3 tangent = new Vec3(1, 0, 0);
        Vec3 bitangent = new Vec3(0, 0, 1);
        int[] color = barkColor(data.palette(), 1.0F);

        for (int ring = 0; ring < 3; ring++) {
            float radius = 0.12F + ring * 0.15F;
            int[] capColor = new int[]{
                    Mth.clamp(208 - ring * 8 + color[0] / 12, 80, 245),
                    Mth.clamp(164 - ring * 6 + color[1] / 14, 65, 220),
                    Mth.clamp(103 - ring * 4 + color[2] / 16, 45, 170),
                    255
            };
            putVertex(consumer, pose, center, capColor, 0.5F, 0.5F, light, n);
            for (int i = 0; i <= SIDES; i++) {
                double a = Math.PI * 2 * i / SIDES;
                float irregular = 1.0F + (((mix(data.palette() * 97 + ring, i) & 15) / 15.0F) - 0.5F) * 0.10F;
                Vec3 p = center
                        .add(tangent.scale(Math.cos(a) * radius * irregular))
                        .add(bitangent.scale(Math.sin(a) * radius * irregular));
                putVertex(consumer, pose, p, capColor,
                        0.5F + (float) Math.cos(a) * radius * 1.7F,
                        0.5F + (float) Math.sin(a) * radius * 1.7F,
                        light, n);
            }
        }

        // Bark rim around the cut.
        renderRim(consumer, pose, light, color);
    }

    private static void renderRim(VertexConsumer consumer, PoseStack.Pose pose, int light, int[] bark) {
        Vec3 top = Vec3.ZERO;
        Vec3 bottom = new Vec3(0, -0.09, 0);
        float radius = 0.47F;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 colorVec = new Vec3(bark[0], bark[1], bark[2]);
        int[] dark = new int[]{
                (int) (colorVec.x * 0.82), (int) (colorVec.y * 0.82), (int) (colorVec.z * 0.82), 255
        };
        for (int i = 0; i < SIDES; i++) {
            double a0 = Math.PI * 2 * i / SIDES;
            double a1 = Math.PI * 2 * (i + 1) / SIDES;
            Vec3 p0 = new Vec3(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
            Vec3 p1 = new Vec3(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            Vec3 n = new Vec3(Math.cos((a0 + a1) * 0.5), 0, Math.sin((a0 + a1) * 0.5)).normalize();
            putVertex(consumer, pose, top.add(p0), dark, 0, 0, light, n);
            putVertex(consumer, pose, top.add(p1), dark, 1, 0, light, n);
            putVertex(consumer, pose, bottom.add(p1), dark, 1, 1, light, n);
            putVertex(consumer, pose, bottom.add(p0), dark, 0, 1, light, n);
        }
    }

    private static void renderFracture(
            TreeFallingEntity.TreeData data,
            TreeFallingRenderState state,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int light
    ) {
        int root = state.fractureRoot();
        if (root < 0 || root >= data.segments().size()) return;
        TreeFallingEntity.TreeSegment segment = data.segments().get(root);
        Vec3 a = TreeFallingEntity.transformLocalPoint(
                new Vec3(segment.sx(), segment.sy(), segment.sz()), state.angle(), state.axisYaw(),
                data.treeHeight(), segment.branchRoot(), root, state.fractureProgress());
        Vec3 b = TreeFallingEntity.transformLocalPoint(
                new Vec3(segment.ex(), segment.ey(), segment.ez()), state.angle(), state.axisYaw(),
                data.treeHeight(), segment.branchRoot(), root, state.fractureProgress());
        Vec3 n = b.subtract(a).normalize();
        Vec3 helper = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 tangent = n.cross(helper).normalize();
        Vec3 bitangent = tangent.cross(n).normalize();
        float radius = Math.max(0.065F, segment.endRadius() * 0.94F);
        int[] darkWood = new int[]{72, 48, 29, 255};
        float opening = Mth.clamp(state.fractureProgress(), 0.0F, 1.0F) * 0.10F;
        Vec3 center = a.add(n.scale(opening));
        putVertex(consumer, pose, center, darkWood, 0.5F, 0.5F, light, n);
        for (int i = 0; i <= SIDES; i++) {
            double theta = Math.PI * 2.0 * i / SIDES;
            Vec3 p = center
                    .add(tangent.scale(Math.cos(theta) * radius))
                    .add(bitangent.scale(Math.sin(theta) * radius));
            putVertex(consumer, pose, p, darkWood,
                    0.5F + (float)Math.cos(theta) * 0.5F,
                    0.5F + (float)Math.sin(theta) * 0.5F,
                    light, n);
        }
    }

    private static void renderLeaves(
            TreeFallingEntity.TreeData data,
            TreeFallingRenderState state,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int light
    ) {
        int clusterIndex = 0;
        for (TreeFallingEntity.LeafCluster leaf : data.leaves()) {
            Vec3 center = TreeFallingEntity.transformLocalPoint(
                    new Vec3(leaf.x(), leaf.y(), leaf.z()), state.angle(), state.axisYaw(),
                    data.treeHeight(), -1, state.fractureRoot(), state.fractureProgress());

            int[] baseColor = leafColor(leaf.palette(), leaf.seed());
            Vec3 scale = new Vec3(leaf.rx(), leaf.ry(), leaf.rz());
            renderLeafEllipsoid(consumer, pose, center, scale, baseColor, light, leaf.seed(), clusterIndex++);
        }
    }

    private static void renderLeafEllipsoid(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 center,
            Vec3 scale,
            int[] color,
            int light,
            int seed,
            int clusterIndex
    ) {
        for (int ring = 0; ring < LEAF_RINGS - 1; ring++) {
            float v0 = ring / (float) (LEAF_RINGS - 1);
            float v1 = (ring + 1) / (float) (LEAF_RINGS - 1);
            double phi0 = Math.PI * (v0 - 0.5);
            double phi1 = Math.PI * (v1 - 0.5);
            for (int side = 0; side < LEAF_SIDES; side++) {
                float u0 = side / (float) LEAF_SIDES;
                float u1 = (side + 1) / (float) LEAF_SIDES;
                Vec3 p00 = ellipsoidPoint(center, scale, phi0, u0, seed, clusterIndex, 1.0F);
                Vec3 p10 = ellipsoidPoint(center, scale, phi0, u1, seed, clusterIndex, 0.985F);
                Vec3 p11 = ellipsoidPoint(center, scale, phi1, u1, seed, clusterIndex, 1.0F);
                Vec3 p01 = ellipsoidPoint(center, scale, phi1, u0, seed, clusterIndex, 0.99F);

                Vec3 n00 = ellipsoidNormal(center, scale, p00);
                Vec3 n10 = ellipsoidNormal(center, scale, p10);
                Vec3 n11 = ellipsoidNormal(center, scale, p11);
                Vec3 n01 = ellipsoidNormal(center, scale, p01);

                putVertex(consumer, pose, p00, color, u0, v0, light, n00);
                putVertex(consumer, pose, p10, color, u1, v0, light, n10);
                putVertex(consumer, pose, p11, color, u1, v1, light, n11);
                putVertex(consumer, pose, p01, color, u0, v1, light, n01);
            }
        }
    }

    private static Vec3 ellipsoidPoint(Vec3 center, Vec3 scale, double phi, double u,
                                       int seed, int clusterIndex, float irregularBase) {
        double theta = Math.PI * 2.0 * u;
        int h = mix(seed + clusterIndex * 37, (int) (u * 1000));
        double irregular = irregularBase * (1.0 + (((h & 31) / 31.0) - 0.5) * 0.14);
        double cp = Math.cos(phi);
        double x = Math.cos(theta) * cp * scale.x * irregular;
        double y = Math.sin(phi) * scale.y * irregular;
        double z = Math.sin(theta) * cp * scale.z * irregular;
        return center.add(x, y, z);
    }

    private static Vec3 ellipsoidNormal(Vec3 center, Vec3 scale, Vec3 point) {
        double x = (point.x - center.x) / Math.max(0.001, scale.x);
        double y = (point.y - center.y) / Math.max(0.001, scale.y);
        double z = (point.z - center.z) / Math.max(0.001, scale.z);
        return new Vec3(x, y, z).normalize();
    }

    private static void putVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 position,
            int[] color,
            float u,
            float v,
            int light,
            Vec3 normal
    ) {
        consumer.addVertex(pose, (float) position.x, (float) position.y, (float) position.z)
                .setColor(color[0], color[1], color[2], color[3])
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static float barkU(float side, double length) {
        return side * 2.0F + (float) Math.min(1.5, length * 0.25);
    }

    private static int[] barkColor(int palette, float multiplier) {
        int[] c = switch (palette) {
            case 1 -> new int[]{210, 196, 165, 255}; // birch
            case 2 -> new int[]{103, 76, 52, 255}; // spruce
            case 3 -> new int[]{132, 87, 51, 255}; // jungle
            case 4 -> new int[]{111, 87, 69, 255}; // acacia
            case 5 -> new int[]{82, 57, 41, 255}; // dark oak
            case 6 -> new int[]{112, 61, 53, 255}; // mangrove
            case 7 -> new int[]{117, 83, 74, 255}; // cherry
            case 8 -> new int[]{177, 157, 112, 255}; // pale oak
            default -> new int[]{145, 91, 51, 255}; // oak
        };
        return new int[]{
                Mth.clamp((int) (c[0] * multiplier), 0, 255),
                Mth.clamp((int) (c[1] * multiplier), 0, 255),
                Mth.clamp((int) (c[2] * multiplier), 0, 255),
                255
        };
    }

    private static int[] leafColor(int palette, int seed) {
        int[] base = switch (palette) {
            case 1 -> new int[]{135, 173, 81, 238};
            case 2 -> new int[]{53, 101, 50, 238};
            case 3 -> new int[]{52, 128, 57, 238};
            case 4 -> new int[]{94, 132, 62, 238};
            case 5 -> new int[]{57, 102, 45, 238};
            case 6 -> new int[]{65, 126, 68, 238};
            case 7 -> new int[]{185, 119, 132, 238};
            case 8 -> new int[]{133, 157, 83, 238};
            default -> new int[]{77, 133, 57, 238};
        };
        int variation = ((mix(seed, 17) & 15) - 7);
        return new int[]{
                Mth.clamp(base[0] + variation, 0, 255),
                Mth.clamp(base[1] + variation, 0, 255),
                Mth.clamp(base[2] + variation, 0, 255),
                base[3]
        };
    }

    private static int mix(int a, int b) {
        int x = a ^ (b * 0x9E3779B9);
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }
}
