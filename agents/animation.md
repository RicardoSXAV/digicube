# Animation and model assets

Read this before any Blender session and before installing or changing a model, a clip, `ground_models.json`
or anything else the harness exports: how models and clips ship as native JSON, the reduction tools and the
`assetTest` gates, clip naming for attacks, the generic catalog keys, cloth and rope chains, and rescaling.
How gaits follow the body's travel is in [locomotion.md](locomotion.md).

## Blender sessions

For Blender/model/animation work, first read [the shared Blender session
instructions](C:/Users/Administrador/Desktop/Coding/harness/BLENDER_SESSIONS.md). Claim a task-owned session
with the canonical harness `tools/blender_slots.py`; it atomically assigns one of five sessions (`blender_a`
through `blender_e`, ports 9877 through 9881 respectively) using the current Codex task ID. Use only that
connection and its ownership guards, then save and release it before handing work back. Never use the old
shared `blender:9876` connection or another task's slot. Use the canonical harness paths even from a Git
worktree so all chats coordinate through the same reservation registry.

A clip that a script generates is never edited by hand in its installed JSON: rerun the script (each species
doc names it).

## Attack clips

The client animation is looked up by the attack id path, so an attack named `digicube:claw` needs a harness
animation called `claw` (plus `claw_mirrored` when it alternates sides). Author animations in `../harness`
(README §3c), never by hand in Java.

## Native JSON and the asset gates

Animations ship as data: `assets/digicube/models/entity/<name>.animation.json`, read by `NativeAnimationSet`
(linear keys, or `"interpolation":"catmullrom"` for Minecraft's own spline). A harness export that still
produces a `*Animations.java` keyframe class is converted with
`../harness/tools/native_animation.py java-to-native` and the class is never committed. Every export goes
through that tool's `simplify` (bounded-error key reduction) and motion tables through `round-motion`; the
`assetTest` build check fails on dense or unrounded tables, because they multiply the jar size for no visible
gain. `assetTest` also fails on z-fighting: a species mesh (one with an `idle` clip) may have no same-facing
faces on one plane that overlap in the rest pose (`MeshSurfaceCheck`; they flicker in game). All nine species
are clean since 2026-09-18 and `AssetRegressionTest.KNOWN_COPLANAR_PAIRS` stays empty. Find with
`../harness/v2/tools/coplanar_poses.py <mesh> <animation>`, repair an export with `fix_coplanar.py` next to it
(rule and Blender-side check: `../harness/v2/docs/surfaces.md`); `install_assets.py` refuses such a mesh too.

## Generic catalog keys

Two catalog keys in `ground_models.json` are generic: `look` turns one part by vanilla's head yaw and pitch
within limits (faded out as an attack blends in; `carry` hands shares of it to parts further down the chain,
each within its own limits: Mojyamon's face sits on its chest, so its waist takes three quarters and the head
six degrees, and a head turned alone buried the face in the fur; `body.head_turn` on the sheet,
`getMaxHeadYRot`, then brings the whole body round past 24 degrees), and `attack_effects` draws clips of one
effect model in the caster's frame while the attack animation of the same name plays (Agumon's mouth ember and
claw streaks).

## Cloth

Hanging cloth is client data: `cloth` in `ground_models.json` names a hinged chain of parts (never keyed by
any clip) and collider points (model px in a part's own frame); `ClothChains` simulates pitch and roll
pendulums under gravity, the hinge's acceleration and air drag, then holds every segment in front of the
colliders within its reach. State is per entity in `DigimonRenderer`.

## Rope chains

A hanging chain is `ropes` in `ground_models.json` (`RopeChains`): links that are siblings under one frame
part, each placed on a verlet rope in the world, pinned at the first link's rest place, kept their rest
distance apart, damped in their swing about the anchor (world damping is drag and blows a galloping chain out
level) and by friction between links (each link's speed eased toward its neighbours', so a bend never runs
down the chain and cracks the end like a whip), bent at most 30 degrees a joint, pushed out of collider boxes
through the face they came in by without being flung (contact keeps the link's speed); a collider's own
`radius` overrides the rope's, and one right beside the anchor (the palm) needs a thin one or its margin snaps
the top links round its corner on every stride. Links are posed with their side axis carried down the chain,
never taken from a fixed axis (that flipped links half a turn a tick); clip keys on the links are overwritten.
Centarumon's wrist chain is one, pinned by `NativeCentalmonRegressionTest` (not yet registered as a task: run
it with an init script).

## Rescaling a species

Scaling a species changes exported geometry baked at the old scale (`kinetic_motion.json`, `attack_motion`,
`model_scale` in the attack data, the seat): Centarumon went .325 -> .36 on 22 September 2026.
