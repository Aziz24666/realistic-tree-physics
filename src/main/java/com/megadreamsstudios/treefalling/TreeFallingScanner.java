package com.megadreamsstudios.treefalling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class TreeFallingScanner {
    private TreeFallingScanner() {}

    public record ScanResult(TreeFallingEntity.TreeData tree, List<BlockPos> logs, List<BlockPos> leaves, boolean naturalTree) {}
    private record LeafBucket(int x, int y, int z) {}
    private record Vec3f(float x, float y, float z) {}

    public static ScanResult scan(ServerLevel level, BlockPos cutPos, BlockState cutState, TreeFallingConfig config) {
        Set<BlockPos> logs = new HashSet<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        Map<BlockPos, Integer> originalStateIds = new HashMap<>();
        Queue<BlockPos> queue = new ArrayDeque<>();

        // Only scan above the cut. We never pull logs downward through the stump or into roots below it.
        for (Direction direction : Direction.values()) {
            BlockPos next = cutPos.relative(direction);
            if (next.getY() >= cutPos.getY() && level.getBlockState(next).is(BlockTags.LOGS)) {
                if (parent.putIfAbsent(next, cutPos) == null) {
                    depth.put(next, 1);
                    queue.add(next);
                }
            }
        }

        while (!queue.isEmpty() && logs.size() < config.maximumBlocks()) {
            BlockPos current = queue.remove();
            if (!logs.add(current)) continue;
            BlockState state = level.getBlockState(current);
            originalStateIds.put(current, Block.getId(state));

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (next.equals(cutPos) || next.getY() < cutPos.getY()) continue;
                if (Math.abs(next.getX() - cutPos.getX()) > 20 || Math.abs(next.getZ() - cutPos.getZ()) > 20) continue;
                if (level.getBlockState(next).is(BlockTags.LOGS) && parent.putIfAbsent(next, current) == null) {
                    depth.put(next, depth.getOrDefault(current, 1) + 1);
                    queue.add(next);
                }
            }
        }

        if (logs.size() < config.minimumBlocks()) {
            return new ScanResult(null, List.of(), List.of(), false);
        }

        BlockPos[] ordered = logs.stream()
                .sorted(Comparator.comparingInt((BlockPos p) -> depth.getOrDefault(p, 1))
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toArray(BlockPos[]::new);

        int palette = TreeFallingScanner.paletteFor(cutState);
        Map<BlockPos, Integer> branchRoot = new HashMap<>();
        Map<BlockPos, Vec3f> points = new HashMap<>();
        Map<BlockPos, Float> radiusAtNode = new HashMap<>();
        Map<BlockPos, Integer> segmentIndex = new HashMap<>();

        points.put(cutPos, new Vec3f(0.0F, 0.0F, 0.0F));
        radiusAtNode.put(cutPos, 0.48F);

        List<TreeFallingEntity.TreeSegment> segments = new ArrayList<>(logs.size() + 12);
        int branchSequence = 0;
        float treeHeight = 1.0F;

        for (BlockPos current : ordered) {
            BlockPos p = parent.getOrDefault(current, cutPos);
            Vec3f parentPoint = points.getOrDefault(p, points.get(cutPos));

            float jitterX = ((hash(current, 31) & 31) / 31.0F - 0.5F) * 0.13F;
            float jitterZ = ((hash(current, 47) & 31) / 31.0F - 0.5F) * 0.13F;
            float jitterY = ((hash(current, 17) & 31) / 31.0F - 0.5F) * 0.05F;
            if (current.getY() == cutPos.getY() + 1 && p.equals(cutPos)) {
                jitterX *= 0.25F;
                jitterZ *= 0.25F;
            }

            Vec3f currentPoint = new Vec3f(
                    current.getX() - cutPos.getX() + 0.5F + jitterX,
                    current.getY() - cutPos.getY() + 0.5F + jitterY,
                    current.getZ() - cutPos.getZ() + 0.5F + jitterZ
            );
            points.put(current, currentPoint);
            treeHeight = Math.max(treeHeight, currentPoint.y);

            float dx = currentPoint.x - parentPoint.x;
            float dz = currentPoint.z - parentPoint.z;
            float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
            boolean branch = horizontal > 0.30F || !isMostlyVertical(current, p);

            int inheritedBranchRoot = branchRoot.getOrDefault(p, -1);
            int currentBranchRoot;
            if (branch && inheritedBranchRoot < 0) {
                currentBranchRoot = segments.size();
                branchSequence++;
            } else {
                currentBranchRoot = inheritedBranchRoot;
            }
            branchRoot.put(current, currentBranchRoot);

            int currentDepth = depth.getOrDefault(current, 1);
            float heightFactor = Math.min(1.0F, currentPoint.y / 18.0F);
            float radius = 0.45F - heightFactor * 0.20F - currentDepth * 0.0065F;
            if (branch) radius *= 0.67F;
            radius = Math.max(branch ? 0.07F : 0.105F, radius);
            float parentRadius = radiusAtNode.getOrDefault(p, 0.44F);
            float startRadius = Math.min(0.49F, Math.max(radius * 1.05F, parentRadius * 0.90F));
            radiusAtNode.put(current, radius);

            BlockState state = level.getBlockState(current);
            segmentIndex.put(current, segments.size());
            segments.add(new TreeFallingEntity.TreeSegment(
                    parentPoint.x, parentPoint.y, parentPoint.z,
                    currentPoint.x, currentPoint.y, currentPoint.z,
                    startRadius, radius, palette, branch, currentBranchRoot, 1, Block.getId(state)
            ));
        }

        // Root flare: visual only, attached to the cut pivot, making the base much more tree-like.
        int rootCount = Math.min(7, Math.max(4, logs.size() / 35));
        for (int i = 0; i < rootCount; i++) {
            double a = (Math.PI * 2.0 * i) / rootCount + ((hash(cutPos, 100 + i) & 255) / 255.0) * 0.55;
            float length = 0.62F + ((hash(cutPos, 200 + i) & 255) / 255.0F) * 0.64F;
            float height = 0.08F + ((hash(cutPos, 300 + i) & 255) / 255.0F) * 0.11F;
            segments.add(new TreeFallingEntity.TreeSegment(
                    0.0F, 0.025F, 0.0F,
                    (float) Math.cos(a) * length, height, (float) Math.sin(a) * length,
                    0.37F, 0.11F, palette, false, -1, 0, Block.getId(cutState)
            ));
        }

        Set<BlockPos> leaves = collectLeaves(level, logs, config.leafRadius(), config.maximumLeaves());
        List<TreeFallingEntity.LeafCluster> foliage = buildFoliageClusters(leaves, cutPos, palette);

        // Keep a permanent stump at the cut location while the detached portion falls.
        if (config.restoreStump()) {
            level.setBlock(cutPos, cutState, 3);
        }

        for (BlockPos log : logs) {
            level.setBlock(log, Blocks.AIR.defaultBlockState(), 3);
        }
        for (BlockPos leaf : leaves) {
            level.setBlock(leaf, Blocks.AIR.defaultBlockState(), 3);
        }

        int[] logStates = new int[logs.size()];
        int cursor = 0;
        for (BlockPos log : logs) {
            logStates[cursor++] = originalStateIds.getOrDefault(log, Block.getId(cutState));
        }

        TreeFallingEntity.TreeData tree = new TreeFallingEntity.TreeData(
                List.copyOf(segments), List.copyOf(foliage), logStates,
                palette, treeHeight, branchSequence
        );
        return new ScanResult(tree, List.copyOf(logs), List.copyOf(leaves),
                logs.size() >= config.minimumBlocks() && !foliage.isEmpty());
    }

    private static boolean isMostlyVertical(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        int dy = Math.abs(a.getY() - b.getY());
        return dy >= Math.abs(dx) + Math.abs(dz);
    }

    private static Set<BlockPos> collectLeaves(ServerLevel level, Set<BlockPos> logs, int radius, int maxLeaves) {
        Set<BlockPos> leaves = new HashSet<>();
        if (maxLeaves <= 0) return leaves;

        for (BlockPos log : logs) {
            for (int dx = -radius; dx <= radius && leaves.size() < maxLeaves; dx++) {
                for (int dy = -radius; dy <= radius && leaves.size() < maxLeaves; dy++) {
                    for (int dz = -radius; dz <= radius && leaves.size() < maxLeaves; dz++) {
                        BlockPos check = log.offset(dx, dy, dz);
                        if (level.getBlockState(check).is(BlockTags.LEAVES)) leaves.add(check);
                    }
                }
            }
            if (leaves.size() >= maxLeaves) break;
        }
        return leaves;
    }

    private static List<TreeFallingEntity.LeafCluster> buildFoliageClusters(Set<BlockPos> leaves, BlockPos cutPos, int palette) {
        Map<LeafBucket, List<BlockPos>> buckets = new HashMap<>();
        for (BlockPos leaf : leaves) {
            LeafBucket key = new LeafBucket(
                    Math.floorDiv(leaf.getX() - cutPos.getX(), 3),
                    Math.floorDiv(leaf.getY() - cutPos.getY(), 3),
                    Math.floorDiv(leaf.getZ() - cutPos.getZ(), 3)
            );
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(leaf);
        }

        List<TreeFallingEntity.LeafCluster> clusters = new ArrayList<>(buckets.size());
        for (Map.Entry<LeafBucket, List<BlockPos>> entry : buckets.entrySet()) {
            List<BlockPos> positions = entry.getValue();
            float cx = 0, cy = 0, cz = 0;
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (BlockPos p : positions) {
                float x = p.getX() - cutPos.getX() + 0.5F;
                float y = p.getY() - cutPos.getY() + 0.5F;
                float z = p.getZ() - cutPos.getZ() + 0.5F;
                cx += x; cy += y; cz += z;
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
            float inv = 1.0F / positions.size();
            cx *= inv; cy *= inv; cz *= inv;
            float rx = Math.max(0.72F, Math.min(2.65F, (maxX - minX + 1.0F) * 0.60F));
            float ry = Math.max(0.62F, Math.min(2.35F, (maxY - minY + 1.0F) * 0.56F));
            float rz = Math.max(0.72F, Math.min(2.65F, (maxZ - minZ + 1.0F) * 0.60F));
            int seed = hash((int) (cx * 31 + cy * 17 + cz * 13 + positions.size()), 991);
            clusters.add(new TreeFallingEntity.LeafCluster(cx, cy, cz, rx, ry, rz, palette, seed));
        }
        return clusters;
    }

    public static int paletteFor(BlockState state) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (id.contains("birch")) return 1;
        if (id.contains("spruce")) return 2;
        if (id.contains("jungle")) return 3;
        if (id.contains("acacia")) return 4;
        if (id.contains("dark_oak")) return 5;
        if (id.contains("mangrove")) return 6;
        if (id.contains("cherry")) return 7;
        if (id.contains("pale_oak")) return 8;
        return 0;
    }

    private static int hash(BlockPos pos, int salt) {
        long x = pos.asLong() ^ (salt * 0x9E3779B97F4A7C15L);
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return (int) x;
    }

    private static int hash(int value, int salt) {
        int x = value * 0x45d9f3b + salt;
        x ^= x >>> 16;
        x *= 0x45d9f3b;
        x ^= x >>> 16;
        return x;
    }
}
