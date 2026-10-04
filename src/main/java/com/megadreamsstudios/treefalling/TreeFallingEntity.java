package com.megadreamsstudios.treefalling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TreeFallingEntity extends Entity {
    private static final ResourceKey<EntityType<?>> ENTITY_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, TreeFallingMod.id("falling_tree"));

    private static final EntityDataAccessor<byte[]> TREE_DATA =
            SynchedEntityData.defineId(TreeFallingEntity.class, EntityDataSerializers.BYTE_ARRAY);
    private static final EntityDataAccessor<Float> ANGLE =
            SynchedEntityData.defineId(TreeFallingEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AXIS_YAW =
            SynchedEntityData.defineId(TreeFallingEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> FRACTURE_ROOT =
            SynchedEntityData.defineId(TreeFallingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> FRACTURE_PROGRESS =
            SynchedEntityData.defineId(TreeFallingEntity.class, EntityDataSerializers.FLOAT);

    public static final EntityType<TreeFallingEntity> TYPE = registerType();

    private TreeData treeData = TreeData.empty();
    private double angularVelocity;
    private int impactTicks;
    private int settleTicks;
    private boolean finished;
    private boolean fractureTriggered;
    private long physicsSeed;

    public TreeFallingEntity(EntityType<? extends TreeFallingEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    private static EntityType<TreeFallingEntity> registerType() {
        return net.minecraft.core.Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                ENTITY_KEY,
                EntityType.Builder.<TreeFallingEntity>of(TreeFallingEntity::new, MobCategory.MISC)
                        .sized(128.0F, 128.0F)
                        .clientTrackingRange(96)
                        .updateInterval(1)
                        .build(ENTITY_KEY)
        );
    }

    public static void register() {
        // Force class initialization.
    }

    /** A tapered organic branch/trunk section in local tree coordinates. */
    public record TreeSegment(
            float sx, float sy, float sz,
            float ex, float ey, float ez,
            float startRadius, float endRadius,
            int palette,
            boolean branch,
            int branchRoot,
            int logCount,
            int stateId
    ) {}

    /** An ellipsoidal foliage mass assembled from the scanned vanilla leaves. */
    public record LeafCluster(
            float x, float y, float z,
            float rx, float ry, float rz,
            int palette,
            int seed
    ) {}

    public record TreeData(
            List<TreeSegment> segments,
            List<LeafCluster> leaves,
            int[] logStateIds,
            int palette,
            float treeHeight,
            int branchCount
    ) {
        public static TreeData empty() {
            return new TreeData(List.of(), List.of(), new int[0], 0, 1.0F, 0);
        }

        public boolean empty() {
            return segments.isEmpty();
        }
    }

    public TreeData getTreeData() {
        if (treeData.empty()) {
            TreeData decoded = decode(this.entityData.get(TREE_DATA));
            if (!decoded.empty()) {
                treeData = decoded;
            }
        }
        return treeData;
    }

    public float getAngle() {
        return entityData.get(ANGLE);
    }

    public float getAxisYaw() {
        return entityData.get(AXIS_YAW);
    }

    public int getFractureRoot() {
        return entityData.get(FRACTURE_ROOT);
    }

    public float getFractureProgress() {
        return entityData.get(FRACTURE_PROGRESS);
    }

    public static void launch(ServerLevel level, BlockPos cutPos, TreeData tree, Vec3 fallDirection) {
        if (tree == null || tree.empty()) {
            return;
        }

        Vec3 direction = new Vec3(fallDirection.x, 0.0, fallDirection.z);
        if (direction.lengthSqr() < 1.0E-8) {
            direction = new Vec3(1.0, 0.0, 0.0);
        } else {
            direction = direction.normalize();
        }

        TreeFallingEntity entity = new TreeFallingEntity(TYPE, level);
        entity.treeData = tree;
        entity.physicsSeed = level.random.nextLong();
        entity.angularVelocity = TreeFallingMod.CONFIG.initialAngularVelocity();

        // Rotation axis is perpendicular to the desired fall direction.
        double axisYaw = Math.atan2(-direction.x, direction.z);
        entity.setPos(cutPos.getX() + 0.5, cutPos.getY(), cutPos.getZ() + 0.5);
        entity.entityData.set(ANGLE, 0.0F);
        entity.entityData.set(AXIS_YAW, (float) axisYaw);
        entity.entityData.set(FRACTURE_ROOT, -1);
        entity.entityData.set(FRACTURE_PROGRESS, 0.0F);
        entity.entityData.set(TREE_DATA, encode(tree));
        level.addFreshEntity(entity);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(TREE_DATA, new byte[0]);
        builder.define(ANGLE, 0.0F);
        builder.define(AXIS_YAW, 0.0F);
        builder.define(FRACTURE_ROOT, -1);
        builder.define(FRACTURE_PROGRESS, 0.0F);
    }

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide()) {
            getTreeData();
            return;
        }
        if (finished) {
            return;
        }

        if (treeData.empty()) {
            treeData = decode(entityData.get(TREE_DATA));
            if (treeData.empty()) {
                discard();
                return;
            }
        }

        maybeFractureBranch();

        if (getFractureRoot() >= 0) {
            float progress = Math.min(1.0F, getFractureProgress() + 1.0F /
                    Math.max(1, TreeFallingMod.CONFIG.fractureDelayTicks()));
            entityData.set(FRACTURE_PROGRESS, progress);
        }

        if (impactTicks > 0) {
            entityData.set(ANGLE, getAngle() + (float) angularVelocity);
            angularVelocity *= 0.58;
            impactTicks--;
            if (impactTicks == 0) {
                angularVelocity = 0.0;
            }
            return;
        }

        double angle = getAngle();
        double maxAngle = Math.toRadians(86.5);
        if (angle < maxAngle) {
            double torque = TreeFallingMod.CONFIG.angularAcceleration() *
                    Math.sin(Math.min(maxAngle, angle + 0.07));
            double nextVelocity = (angularVelocity + torque) * TreeFallingMod.CONFIG.airDamping();
            double nextAngle = Math.min(maxAngle, angle + nextVelocity);

            if (wouldCollide(nextAngle)) {
                double safeAngle = findSafeAngle(angle, nextAngle);
                entityData.set(ANGLE, (float) safeAngle);
                angularVelocity = -Math.min(0.025, Math.max(0.004,
                        Math.abs(angularVelocity) * TreeFallingMod.CONFIG.impactBounce()));
                impactTicks = 6;
                playImpact((float) safeAngle);
            } else {
                angularVelocity = nextVelocity;
                entityData.set(ANGLE, (float) nextAngle);
            }
            return;
        }

        finishFalling();
    }

    private void maybeFractureBranch() {
        if (fractureTriggered || treeData.branchCount() <= 0 || getAngle() < 0.38F || treeData.treeHeight() < 4.0F) {
            return;
        }

        int candidate = -1;
        float bestScore = -Float.MAX_VALUE;
        for (int i = 0; i < treeData.segments().size(); i++) {
            TreeSegment segment = treeData.segments().get(i);
            if (!segment.branch() || segment.branchRoot() != i || segment.endRadius() > 0.24F) {
                continue;
            }
            float horizontal = (float) Math.sqrt(segment.ex() * segment.ex() + segment.ez() * segment.ez());
            float score = segment.sy() + horizontal * 0.8F;
            score += ((mix(i ^ (int) physicsSeed) & 1023) / 1023.0F) * 2.0F;
            if (score > bestScore) {
                bestScore = score;
                candidate = i;
            }
        }

        if (candidate >= 0) {
            fractureTriggered = true;
            entityData.set(FRACTURE_ROOT, candidate);
            entityData.set(FRACTURE_PROGRESS, 0.0F);
            ServerLevel server = (ServerLevel) level();
            Vec3 root = branchRootPoint(candidate);
            Vec3 worldRoot = transformLocalPoint(root, getAngle(), getAxisYaw(), treeData.treeHeight(), candidate, candidate, 0.0F);
            BlockPos soundPos = BlockPos.containing(getX() + worldRoot.x, getY() + worldRoot.y, getZ() + worldRoot.z);
            server.playSound(null, soundPos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS,
                    0.9F, 0.74F + server.random.nextFloat() * 0.16F);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                    getX() + worldRoot.x, getY() + worldRoot.y, getZ() + worldRoot.z,
                    7, 0.12, 0.12, 0.12, 0.03);
        }
    }

    private Vec3 branchRootPoint(int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= treeData.segments().size()) {
            return Vec3.ZERO;
        }
        TreeSegment segment = treeData.segments().get(segmentIndex);
        return new Vec3(segment.sx(), segment.sy(), segment.sz());
    }

    private void playImpact(float angle) {
        ServerLevel server = (ServerLevel) level();
        Vec3 contact = findLowestPoint(angle);
        BlockPos pos = blockPosAt(contact);
        float volume = Mth.clamp(0.9F + treeData.treeHeight() * 0.035F, 0.9F, 1.75F);
        server.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS,
                volume, 0.56F + server.random.nextFloat() * 0.10F);
        server.playSound(null, pos, SoundEvents.GENERIC_SMALL_FALL, SoundSource.BLOCKS,
                volume * 0.75F, 0.80F + server.random.nextFloat() * 0.08F);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                getX() + contact.x, getY() + Math.max(0.02, contact.y), getZ() + contact.z,
                Math.min(28, 8 + treeData.segments().size() / 14),
                0.35, 0.08, 0.35, 0.035);
    }

    private boolean wouldCollide(double angle) {
        int samples = Math.max(2, TreeFallingMod.CONFIG.collisionSamples());
        for (TreeSegment segment : treeData.segments()) {
            // Root-flare geometry is decorative and the cut pivot is intentionally allowed to overlap the stump.
            if (segment.logCount() == 0) continue;
            for (int i = 0; i <= samples; i++) {
                // Do not let the first centerline point collide with the permanent stump at the pivot.
                if (i == 0) continue;
                double t = i / (double) samples;
                Vec3 local = lerpSegment(segment, t);
                Vec3 world = transformLocalPoint(local, (float) angle, getAxisYaw(), treeData.treeHeight(),
                        segment.branchRoot(), getFractureRoot(), getFractureProgress());
                double radius = Mth.lerp((float) t, segment.startRadius(), segment.endRadius());
                if (touchesSolid(world.x, world.y, world.z, Math.max(0.08, radius * 0.62))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Vec3 lerpSegment(TreeSegment segment, double t) {
        return new Vec3(
                Mth.lerp((float) t, segment.sx(), segment.ex()),
                Mth.lerp((float) t, segment.sy(), segment.ey()),
                Mth.lerp((float) t, segment.sz(), segment.ez())
        );
    }

    private boolean touchesSolid(double x, double y, double z, double radius) {
        int minX = Mth.floor(x - radius);
        int maxX = Mth.floor(x + radius);
        int minZ = Mth.floor(z - radius);
        int maxZ = Mth.floor(z + radius);
        int minY = Mth.floor(y - radius - 0.12);
        int maxY = Mth.floor(y + radius + 0.12);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    BlockPos pos = BlockPos.containing(getX() + bx, getY() + by, getZ() + bz);
                    BlockState state = level().getBlockState(pos);
                    if (state.is(BlockTags.LEAVES) || state.isAir()) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level(), pos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    double localX = getX() + x - pos.getX();
                    double localY = getY() + y - pos.getY();
                    double localZ = getZ() + z - pos.getZ();
                    double dx = clampToRange(localX, shape.min(Direction.Axis.X), shape.max(Direction.Axis.X)) - localX;
                    double dy = clampToRange(localY, shape.min(Direction.Axis.Y), shape.max(Direction.Axis.Y)) - localY;
                    double dz = clampToRange(localZ, shape.min(Direction.Axis.Z), shape.max(Direction.Axis.Z)) - localZ;
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double clampToRange(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double findSafeAngle(double low, double high) {
        for (int i = 0; i < 10; i++) {
            double mid = (low + high) * 0.5;
            if (wouldCollide(mid)) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return low;
    }

    private Vec3 findLowestPoint(float angle) {
        Vec3 lowest = new Vec3(0.0, 9999.0, 0.0);
        for (TreeSegment segment : treeData.segments()) {
            Vec3 a = transformLocalPoint(new Vec3(segment.sx(), segment.sy(), segment.sz()), angle, getAxisYaw(),
                    treeData.treeHeight(), segment.branchRoot(), getFractureRoot(), getFractureProgress());
            Vec3 b = transformLocalPoint(new Vec3(segment.ex(), segment.ey(), segment.ez()), angle, getAxisYaw(),
                    treeData.treeHeight(), segment.branchRoot(), getFractureRoot(), getFractureProgress());
            if (a.y < lowest.y) lowest = a;
            if (b.y < lowest.y) lowest = b;
        }
        return lowest;
    }

    private void finishFalling() {
        if (finished) {
            return;
        }
        if (settleTicks++ < TreeFallingMod.CONFIG.settleTicks()) {
            return;
        }

        ServerLevel server = (ServerLevel) level();
        Map<Item, Integer> drops = new HashMap<>();
        for (int stateId : treeData.logStateIds()) {
            BlockState state = Block.stateById(stateId);
            Item item = state.getBlock().asItem();
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            drops.merge(item, 1, Integer::sum);
        }

        Vec3 dropPoint = findLowestPoint(getAngle());
        for (Map.Entry<Item, Integer> entry : drops.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                int count = Math.min(64, remaining);
                remaining -= count;
                ItemEntity itemEntity = new ItemEntity(
                        server,
                        getX() + dropPoint.x,
                        Math.max(getY() + 0.15, getY() + dropPoint.y + 0.15),
                        getZ() + dropPoint.z,
                        new ItemStack(entry.getKey(), count),
                        (server.random.nextDouble() - 0.5) * 0.09,
                        0.12 + server.random.nextDouble() * 0.07,
                        (server.random.nextDouble() - 0.5) * 0.09
                );
                server.addFreshEntity(itemEntity);
            }
        }

        // A small final crack/impact sound makes the landing feel like a weighty tree rather than a falling model.
        server.playSound(null, blockPosAt(dropPoint), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS,
                Mth.clamp(0.65F + treeData.treeHeight() * 0.02F, 0.65F, 1.35F), 0.62F);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                getX() + dropPoint.x, getY() + Math.max(0.02, dropPoint.y), getZ() + dropPoint.z,
                Math.min(24, 6 + treeData.segments().size() / 18), 0.28, 0.07, 0.28, 0.025);

        finished = true;
        discard();
    }

    /**
     * Transforms one local tree point into falling-world-local coordinates.
     * The trunk bends progressively toward the top, while branch systems flex more strongly.
     */
    public static Vec3 transformLocalPoint(
            Vec3 local,
            float angle,
            float axisYaw,
            float treeHeight,
            int branchRoot,
            int fractureRoot,
            float fractureProgress
    ) {
        double height = Math.max(1.0, treeHeight);
        double h = Mth.clamp(local.y / (float) height, 0.0F, 1.0F);
        double bend = TreeFallingMod.CONFIG.trunkFlex() * Math.pow(h, 1.72) * angle;
        double effectiveAngle = angle + bend;

        if (branchRoot >= 0) {
            effectiveAngle += TreeFallingMod.CONFIG.branchFlex() * angle * Math.pow(h, 1.25) * 0.72;
            if (branchRoot == fractureRoot) {
                double fracture = TreeFallingMod.CONFIG.fractureAngle() * fractureProgress * (0.55 + h * 0.45);
                effectiveAngle += fracture;
                double lift = Math.sin(fractureProgress * Math.PI) * 0.06;
                local = local.add(0.0, -lift, 0.0);

                // A tiny sideways opening appears at the snapped branch joint.
                local = local.add(
                        Math.cos(axisYaw) * 0.11 * fractureProgress,
                        0.0,
                        Math.sin(axisYaw) * 0.11 * fractureProgress
                );
            }
        }

        double ax = Math.cos(axisYaw);
        double az = Math.sin(axisYaw);
        double c = Math.cos(effectiveAngle);
        double s = Math.sin(effectiveAngle);
        double dot = local.x * ax + local.z * az;

        double rx = local.x * c + (-az * local.y) * s + ax * dot * (1.0 - c);
        double ry = local.y * c + (az * local.x - ax * local.z) * s;
        double rz = local.z * c + (ax * local.y) * s + az * dot * (1.0 - c);

        // Subtle sag only affects real branches, not the main trunk.
        if (branchRoot >= 0) {
            ry -= 0.025 * Math.sin(Math.PI * h) * h;
        }
        return new Vec3(rx, ry, rz);
    }

    private BlockPos blockPosAt(Vec3 local) {
        return BlockPos.containing(getX() + local.x, getY() + local.y, getZ() + local.z);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (!TreeFallingMod.CONFIG.persistFallingTrees() || treeData.empty()) {
            return;
        }
        output.putIntArray("tree_data", intArrayFromBytes(encode(treeData)));
        output.putDouble("angular_velocity", angularVelocity);
        output.putFloat("angle", getAngle());
        output.putFloat("axis_yaw", getAxisYaw());
        output.putInt("fracture_root", getFractureRoot());
        output.putFloat("fracture_progress", getFractureProgress());
        output.putBoolean("fracture_triggered", fractureTriggered);
        output.putLong("physics_seed", physicsSeed);
        output.putInt("impact_ticks", impactTicks);
        output.putInt("settle_ticks", settleTicks);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        int[] encoded = input.getIntArray("tree_data").orElse(new int[0]);
        if (encoded.length > 0) {
            treeData = decode(bytesFromIntArray(encoded));
            entityData.set(TREE_DATA, encode(treeData));
        }
        angularVelocity = input.getDoubleOr("angular_velocity", TreeFallingMod.CONFIG.initialAngularVelocity());
        entityData.set(ANGLE, input.getFloatOr("angle", 0.0F));
        entityData.set(AXIS_YAW, input.getFloatOr("axis_yaw", 0.0F));
        entityData.set(FRACTURE_ROOT, input.getIntOr("fracture_root", -1));
        entityData.set(FRACTURE_PROGRESS, input.getFloatOr("fracture_progress", 0.0F));
        fractureTriggered = input.getBooleanOr("fracture_triggered", false);
        physicsSeed = input.getLongOr("physics_seed", 0L);
        impactTicks = input.getIntOr("impact_ticks", 0);
        settleTicks = input.getIntOr("settle_ticks", 0);
    }

    private static byte[] encode(TreeData tree) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x54524633); // TRF3
            out.writeInt(tree.palette());
            out.writeFloat(tree.treeHeight());
            out.writeInt(tree.branchCount());
            out.writeInt(tree.segments().size());
            for (TreeSegment s : tree.segments()) {
                out.writeFloat(s.sx()); out.writeFloat(s.sy()); out.writeFloat(s.sz());
                out.writeFloat(s.ex()); out.writeFloat(s.ey()); out.writeFloat(s.ez());
                out.writeFloat(s.startRadius()); out.writeFloat(s.endRadius());
                out.writeInt(s.palette()); out.writeBoolean(s.branch());
                out.writeInt(s.branchRoot()); out.writeInt(s.logCount()); out.writeInt(s.stateId());
            }
            out.writeInt(tree.leaves().size());
            for (LeafCluster leaf : tree.leaves()) {
                out.writeFloat(leaf.x()); out.writeFloat(leaf.y()); out.writeFloat(leaf.z());
                out.writeFloat(leaf.rx()); out.writeFloat(leaf.ry()); out.writeFloat(leaf.rz());
                out.writeInt(leaf.palette()); out.writeInt(leaf.seed());
            }
            out.writeInt(tree.logStateIds().length);
            for (int id : tree.logStateIds()) out.writeInt(id);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static TreeData decode(byte[] data) {
        if (data == null || data.length == 0) {
            return TreeData.empty();
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 0x54524633) return TreeData.empty();
            int palette = in.readInt();
            float height = in.readFloat();
            int branches = in.readInt();
            int segmentCount = Math.min(4096, Math.max(0, in.readInt()));
            List<TreeSegment> segments = new java.util.ArrayList<>(segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                segments.add(new TreeSegment(
                        in.readFloat(), in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(),
                        in.readInt(), in.readBoolean(), in.readInt(), in.readInt(), in.readInt()
                ));
            }
            int leafCount = Math.min(4096, Math.max(0, in.readInt()));
            List<LeafCluster> leaves = new java.util.ArrayList<>(leafCount);
            for (int i = 0; i < leafCount; i++) {
                leaves.add(new LeafCluster(
                        in.readFloat(), in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(), in.readFloat(),
                        in.readInt(), in.readInt()
                ));
            }
            int logCount = Math.min(4096, Math.max(0, in.readInt()));
            int[] logs = new int[logCount];
            for (int i = 0; i < logCount; i++) logs[i] = in.readInt();
            return new TreeData(List.copyOf(segments), List.copyOf(leaves), logs, palette, height, branches);
        } catch (IOException | RuntimeException e) {
            return TreeData.empty();
        }
    }

    private static int[] intArrayFromBytes(byte[] data) {
        int[] ints = new int[(data.length + 3) / 4];
        for (int i = 0; i < data.length; i++) {
            ints[i / 4] |= (data[i] & 0xFF) << ((i % 4) * 8);
        }
        return ints;
    }

    private static byte[] bytesFromIntArray(int[] data) {
        byte[] bytes = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            bytes[i * 4] = (byte) data[i];
            bytes[i * 4 + 1] = (byte) (data[i] >>> 8);
            bytes[i * 4 + 2] = (byte) (data[i] >>> 16);
            bytes[i * 4 + 3] = (byte) (data[i] >>> 24);
        }
        return bytes;
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }
}
