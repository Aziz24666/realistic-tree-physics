# Realistic Tree Physics — Fabric 26.3 v2.0.1

A Fabric mod for Minecraft Java Edition 26.3 that replaces the usual instant “block stack disappears” feeling with a physics-driven, stylized-realistic tree fall.

## What is different in v2

The falling tree is **not rendered as a pile of vanilla cubes**.

At the moment the player cuts a supporting log, the mod scans the connected trunk/branches and nearby leaves, removes the detached blocks from the world, and builds a compact procedural tree representation. The client then renders that representation as:

- tapered, low-poly cylindrical trunk and branch meshes;
- progressively smaller branch diameters;
- irregular bark rings and subtle surface variation;
- a wider root flare at the cut base;
- a visible fresh-cut wood surface;
- clustered, volumetric foliage instead of a wall of leaf cubes;
- a separate bark pass for darker trunk variation.

The result remains compatible with vanilla Minecraft tree blocks while looking substantially more like an actual tree.

## Falling and fracture behavior

- The detached tree falls away from the player who chopped it.
- Angular acceleration and air damping drive the fall.
- The tree flexes more toward the crown than at the base.
- Branch systems flex more strongly than the trunk.
- On larger trees, one slender branch can fracture during the fall.
- The fractured branch gets a visible opening/sag and fracture particles/sound.
- Ground and obstacle collision is sampled along the trunk/branch centerlines using the target block collision shapes.
- The first substantial collision produces a heavy impact sound, particles, and a small rebound.
- The tree settles before converting its saved log states back into item drops.

## Vanilla tree families

The visual palette detects the main vanilla log families by block ID and adjusts bark/foliage tint for oak, birch, spruce, jungle, acacia, dark oak, mangrove, cherry, and pale oak.

The block states of the original logs are preserved in the saved tree data, so the final item count and wood type come from the actual scanned logs rather than from a generated approximation.

## Stump behavior

By default, the log the player actually broke is restored as a permanent stump while the detached upper tree falls. This avoids the common “the entire tree vanished from the ground” look.

Set `restore_stump=false` to disable this.

## Multiplayer and persistence

The falling tree is one server-authoritative entity instead of one entity per log. Only compact tree geometry data and the fall state need to be synchronized.

Falling-tree state can also be saved and loaded using the modern 26.3 `ValueInput` / `ValueOutput` entity persistence system when `persist_falling_trees=true`.

## Configuration

The config is created at:

```text
config/treefalling.properties
```

Main controls:

```properties
enabled=true
minimum_blocks=3
maximum_blocks=768
leaf_radius=3
maximum_leaves=768
initial_angular_velocity=0.008
angular_acceleration=0.028
air_damping=0.992
settle_ticks=16
trunk_flex=0.20
branch_flex=0.38
fracture_angle=0.34
fracture_delay_ticks=8
impact_bounce=0.014
collision_samples=5
restore_stump=true
persist_falling_trees=true
```

For better performance on a weaker machine, reduce `maximum_blocks`, `maximum_leaves`, and `collision_samples`.

## Build

This project targets Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18-SNAPSHOT, Gradle 9.6, and Java 25.

Minecraft 26.3 uses the newer submit-node rendering architecture; the client renderer uses `submitCustomGeometry` to feed generated vertices to the rendering pipeline instead of depending on the old block-render reflection approach.

### GitHub Actions

The repository includes `.github/workflows/build.yml`.

1. Upload the project to a GitHub repository.
2. Open **Actions**.
3. Run **Build Realistic Tree Physics**.
4. Download the `realistic-tree-physics` workflow artifact.
5. Put the produced mod JAR in `.minecraft/mods` with Fabric API.

### Local build

Install Java 25 and Gradle 9.6, then run:

```text
gradle build
```

The compiled JAR is created in `build/libs/`.

## Scope and technical notes

This is intentionally a procedural rendering system rather than a replacement resource pack or a custom imported mesh for every tree. It makes arbitrary scanned vanilla trees look organic without shipping a separate static model for each possible tree shape.

The branch fracture is currently a visual/physics deformation of a selected branch system. The branch does not yet become a second independently simulated rigid-body entity. The rest of the tree remains one efficient physics entity.


## CI build notes

Minecraft 26.3 is built with Java 25. This project pins Fabric Loom 1.17.21 and uses Gradle 9.6-era tooling, matching the Fabric 26.3 development guidance.

The GitHub Actions workflow uses current Node 24-capable action releases and prints the full Gradle stacktrace if compilation fails.
