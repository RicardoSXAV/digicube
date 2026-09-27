# Animation and model assets

Read this before adding or changing a model, a clip, `ground_models.json` or any other model asset: how models
and clips ship as native JSON, the `assetTest` gates, clip naming for attacks, the generic catalog keys, cloth,
sleeve bends and rope chains, and rescaling. How gaits follow the body's travel is in [locomotion.md](locomotion.md).

## Attack clips

The client animation is looked up by the attack id path, so an attack named `digicube:claw` needs a clip called
`claw` in the species' animation file (plus `claw_mirrored` when it alternates sides).

## Native JSON and the asset gates

Animations ship as data, never as Java: `assets/digicube/models/entity/<name>.animation.json`, read by
`NativeAnimationSet` (linear keys, or `"interpolation":"catmullrom"` for Minecraft's own spline); no
`*Animations.java` keyframe class is committed. The `assetTest` build check fails on dense or unrounded tables
(keys that interpolating their neighbours reproduces, motion values with more decimals than they need), because
they multiply the jar size for no visible gain. `assetTest` also fails on z-fighting: a species mesh (one with an
`idle` clip) may have no same-facing faces on one plane that overlap in the rest pose (`MeshSurfaceCheck`; they
flicker in game). All nine species are clean since 2026-09-18 and `AssetRegressionTest.KNOWN_COPLANAR_PAIRS`
stays empty; the failure names each pair.

## Generic catalog keys

Two catalog keys in `ground_models.json` are generic: `look` turns one part by vanilla's head yaw and pitch
within limits (faded out as an attack blends in; `carry` hands shares of it to parts further down the chain,
each within its own limits: Mojyamon's face sits on its chest, so its waist takes three quarters and the head
six degrees, and a head turned alone buried the face in the fur; `body.head_turn` on the sheet,
`getMaxHeadYRot`, then brings the whole body round past 24 degrees), and `attack_effects` draws clips of one
effect model in the caster's frame while the attack animation of the same name plays (Agumon's mouth ember and
claw streaks).

- `stomps` (`NativeGroundModel.Stomps`, played by the client's `Stomps`) gives a heavy walker's footfalls:
  `down` is where each foot lands in the gait's phase (0 to 1), and `feet` is where it then stands, x and
  forward in blocks at yaw 0. Each landing plays a low ravager step over the ground's own step sound, raises a
  puff of that block, and dips the rider's view (`RiderControls.cameraKick`, deeper at a run). A ridden body
  that breaks into its run roars, at most every 12 s. Pair it with `ground_gait.footfalls` on the sheet so
  vanilla's step per block stays silent.
- `bank` lets a model that does not gallop lean into its turns under a rider at a run, as a galloper does.

## Cloth

Hanging cloth is client data: `cloth` in `ground_models.json` names a hinged chain of parts (never keyed by
any clip) and collider points (model px in a part's own frame); `ClothChains` simulates pitch and roll
pendulums under gravity, the hinge's smoothed acceleration and air drag, then holds every segment in front of the
colliders within its reach. The pendulums are damped close to critically, as heavy fabric in air is: lightly damped,
the footfalls pumped the swing and the cloth rocked for seconds after a stop. A segment folds at most 15 degrees
forward of the one above it; a knee pushing harder lifts the chain above, so the cloth drapes over it instead of the
lower segment flipping level. State is per entity in `DigimonRenderer`.

## Sleeve bends

A garment that must read as one piece over a joint (trousers over hip and knee) is `bends` in `ground_models.json`
(`SleeveBends`): each entry names the `joint` (path to the lower bone), an `upper` sleeve on its parent and a `lower`
sleeve on the joint, open tubes of one rectangular section cut square at the pivot (the upper along its bone's y
axis, the lower from the joint's origin). After the pose and the cloth, each frame, the corners of the cut go where
the upper sleeve's edges meet the plane halving the two bones, a mitre shared by both sleeves, and every other vertex
on the cut goes on the straight edges between them: no gap, no step, no patch buried under the seam at any angle.
Inside a deep bend a corner slides back along the shorter sleeve's edge, keeping 1 px of it, so no face turns inside
out; a twist of the lower bone is taken up by the lower sleeve. Split both sleeves' faces at the same columns (no
vertex of one ring in the middle of the other's edge), close each chain's free ends, keep the skin inside clear of the
sleeves, and never key a sleeve. Rigid boxes cannot do this: a thigh box and a shin box open a wedge at the knee, and
a patch filling it shows as a step.

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
