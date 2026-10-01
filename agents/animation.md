# Animation and model assets

Read this before adding or changing a model, a clip, `ground_models.json` or any other model asset: how models
and clips ship as native JSON, the `assetTest` gates, clip naming for attacks, the generic catalog keys (paws heard
where they land among them), cloth, tails, sleeve bends and rope chains, and rescaling. How gaits follow the body's travel is in [locomotion.md](locomotion.md).

## Attack clips

The client animation is looked up by the attack id path, so an attack named `digicube:claw` needs a clip called
`claw` in the species' animation file (plus `claw_mirrored` when it alternates sides).

## Native JSON and the asset gates

Animations ship as data, never as Java: `assets/digicube/models/entity/<name>.animation.json`, read by
`NativeAnimationSet` (linear keys, or `"interpolation":"catmullrom"` for Minecraft's own spline); no
`*Animations.java` keyframe class is committed. The `assetTest` build check fails on dense or unrounded tables
(keys that interpolating their neighbours reproduces, motion values with more decimals than they need), because
they multiply the jar size for no visible gain. A mesh's baked layer (`NativeModelGeometry.createLayer`) holds only a
placeholder face per quad, which samples empty texels; `NativeModelGeometry.apply` installs the real faces, so anything
that draws a baked native root passes it through `apply` first, as the model classes do (without it the model draws
invisible); `FrostBreathRenderer` reads its boxes from the mesh instead. `assetTest` also fails on z-fighting: a species mesh (one with an
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
- `stomps` may name its `sound` and `pitch` (a ravager's step at 0.5 by default), and the `roar` and `roar_pitch` a
  ridden body gives breaking into its run (a ravager's roar at 0.72 by default; Ikkakumon bellows). A gait whose run
  lands its feet on other beats (a bound) gives `run_down` and `run_feet`, played past half of the run.
- `paws` (`NativeGroundModel.Paws`, heard through the client's `PawFalls`) names each paw's `path`, its `toe` (model
  px in the paw's frame: the line that stays on the ground as the paw rolls onto its toes) and `hind`. The model samples
  every looping clip's toe tracks at load; `PawFalls` follows the toes through the blend the model plays
  (`NativeGroundModel.toes`, `applyGround`'s own weights, the pivot included), so each paw sounds the block it lands on,
  where and when it lands, walk to gallop, back and aside, louder at a gallop and hardest from the hind paws; a leap's
  hind push-off and its landing (forepaws, then hind paws two ticks later) sound too. `sound` (optional, at `volume`
  and `pitch`) is the species' own step under the ground's; with `replaces` (grounds named by their step sound, e.g.
  `minecraft:block.grass.step`) it plays in place of the ground's step on those and nowhere else (`Paws.padsOn`,
  `replacesStep`). Past six beats a second (landings in one tick are one beat) each step is softened by the square
  root of the excess (`PawFalls.balance`), so a sprint sounds about as full as a trot. Pair it with
  `ground_gait.footfalls`. A skid on ice
  (a `skid` clip, [locomotion.md](locomotion.md#sure-footing)) mixes into the toes too, and its braced forepaws throw
  chips of the ground and scrape (the block's hit sound, soft, every three ticks).
- `bank` lets a model that does not gallop lean into its turns under a rider at a run, as a galloper does.
- `swim_bank` (degrees) lets a swimmer bank that far into a hard turn, ridden or not (`getTurnBank`), a third as far
  afloat at its float line (`SURFACE_BANK_LOSS`: the rider stays out of the water); without it a ridden swimmer banks a
  quarter of vanilla-sized turns.
- `swim_wake` (`SwimWakeSpec`) gives a swimmer its water on every client (`SwimWake`): a bow wave and wake along the
  surface and a splash and a slap at its side on every paddle, bubbles off the flippers under it and a swish a stroke
  (a burst as a dash sets off), splashes leaving and falling back into the water, a spray on surfacing after a dive
  (and its breath out, if an optional `blow` sound id is given), a corkscrew of bubbles on a barrel roll. Falling back
  in dips the rider's view. Its `stroke`, `paddle` and `surge_paddle` are the lengths of the `swim`, `swim_surface`
  and `swim_surface_dash` clips (a paddle each side a cycle); `nativeIkkakumonTest` holds them equal.
- An amphibious model with `swim_dash` and `swim_leap` clips plays the first over its stroke past its cruise and the
  second out of the water on a breach. A sea mount's `swim_surface` (and `swim_surface_dash` past its cruise) takes
  over from both strokes afloat at its float line (`getSwimSurface`: 1 there, 0 a fifth of its height under it).

## Cloth

Hanging cloth is client data: `cloth` in `ground_models.json` names a hinged chain of parts (never keyed by
any clip) and collider points (model px in a part's own frame); `ClothChains` simulates pitch and roll
pendulums under gravity, the hinge's smoothed acceleration and air drag, then holds every segment in front of the
colliders within its reach. The pendulums are damped close to critically, as heavy fabric in air is: lightly damped,
the footfalls pumped the swing and the cloth rocked for seconds after a stop. A segment folds at most 15 degrees
forward of the one above it; a knee pushing harder lifts the chain above, so the cloth drapes over it instead of the
lower segment flipping level. State is per entity in `DigimonRenderer`.

## Tails

A tail that holds its own line and swings with the body is `tails` in `ground_models.json` (`TailChains`): links that
are siblings under one frame part (the tail's root, which the clips move) and the `tip` of the last one (model px in
the frame). The joints and the tip are points in the world, each pulled toward the clip's line relative to the joint
above, as a joint holds its own angle (`stiffness` at the root to `tip_stiffness`, per tick squared), damped about that
line (`damping`: relative to the clip's own motion, so a steady run is not dragged back), pushed by the air (`drag`,
per block of speed) and sagging (`sag`, blocks a tick squared); a joint bends at most `bend` degrees from the one
above (`root_bend` off the clip's line) and no point goes under the ground the body stands on. No other gravity pulls
the points, so a body that falls lifts its tail and one that lands whips it down, and a turn at speed swings it
outward. `carried` parts ride on the last link. Clip keys on the links are overwritten. A move from the ground to the
ground (`groundedMove`: a step up or down, which the game makes in one tick) is felt eased in (`FOLLOW`, 0.3 a tick,
critically damped); felt raw, every step of a hillside swung the tail like a leap. Garurumon's is pinned by
`nativeGarurumonTest` (the sag, the swing out of a turn, the lift in a fall, a wobble under 5 px over one-block steps
up and down, 62 felt raw, whole links, and the same path at one frame a tick and at six within 4 px: a frame a tick
sees the pose's path through the tick as a straight line).

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

## Serpent spines

A serpent's body is `spine` in `ground_models.json` (`SerpentSpine`): the `path` to the part its first link hangs in,
the `chain` of links (each the next one's parent) and the `tip` part after the last. After the pose each frame, the
chain is laid along the drawn body's trail ([locomotion.md](locomotion.md#serpents)): the clips give each point its
height above the feet and its spacing, the trail where along the ground it lies, and a sway its place aside. On land the
sway is fixed to the ground (`land_wave` blocks, over `wavelength`), so the body slides through it without slipping
aside, as a snake does; in the water it runs back along the body faster than the body swims (`slip`), stronger with its
pace (`rest_wave`, `swim_wave`, `dash_wave`), and slowly at rest (`rest_pace`, blocks a tick). Within `neck` blocks of
the head the body keeps the head's own heading, each point swung round the head's feet from the head's frame to the
trail's: a head turned off its trail bends the neck round in an arc.

The body is laid as a fine level line first (`SerpentTrail.line`): a point at every tenth of a block of the way the head
went over the ground, so each keeps its place on the ground as the head goes on (laid a step from the head, they slid
over every edge and the body shivered); straight up or down is no distance at all, so a climb given up or a jump stands
nothing off the ground; its plan is smoothed over 0.4 blocks, less by the head. The line settles on what lies under it:
the ground (the highest of three columns across its thickness, and where the head stood its feet's height: walking an
edge on its box's corner, the body flipped off it and back) or, in the water, the path the head swam, no lower than the
bed. It settles like a stiff rope (`rope`): on or over what it lies on, bending no tighter than `ROPE_RADIUS` (1.25
blocks), so it ramps up to a step from before it, rounds an edge (its lower side rises), bridges a dip and drapes over a
thin wall; its hold grows over the first half block behind the head (held from the second point, the body stuttered),
and it lifts no point over the ground within `ROPE_SPAN` (2.5 blocks). The clips' chain lies over that line, along the
neck over the head's own feet instead, easing onto the line by the neck's end (kept to the ground under it, the neck
stepped at every edge), never under the ground (by the head, that under the head's way), every dip bridged exactly
(`bridge`: a head come down a face, the body still on top, dipped its neck and curled). Each point eases into a new
place over `FILTER` (2 ticks), by its step, past the neck's first block. The sway gives way, spread along the body,
where it would push the body's side into a wall; the neck swings round the way nearest the head's turn off the line
(`NECK_READ` behind it), followed round frame to frame up to 270 degrees (read afresh, it flipped at half round). The
joints are laid on a path that sets out along the clips' way out of the head and steers for the line `PATH_LEAD` (0.85
blocks) on, bending no tighter than `PATH_BEND` (0.72 blocks, 0.4 by the head), so it rounds a turn too tight for the
body and swings wide round a hairpin (laid straight on it, links curled and flipped); each link keeps its length, bends
no more than 40 degrees (the first 30 off the clips: the head rides on it), and has its back carried on from the one
before and turned toward up, rolled only as far as the clips roll it, by at most `ROLL_STEP` (8 degrees) a link, not at
all up a face (paid at once, a roll twisted the link). A wrap winds the body off its trail and round its prey
(`SerpentCoil`, the coil `ConstrictionCoil` shapes): each point swings round the prey's axis onto its loop, the front
first, and back off it the same way on release ([combat.md](combat.md#wraps)).

So a serpent's clips keep the chain straight in plan (no sway of their own, and not the rest pose's) and give only
heights, head, jaw, fins and frills; a move played on the run (`upper_body`) adds to the swimming head instead of
replacing it. A serpent's shadow is one soft band along its body where it lies (`SerpentShadow`: the laid joints,
`serpentLine` on the render state, drawn onto the tops of the blocks under them with vanilla's own shadow texture across
the band, fainter the higher the body's underside is over the face, faint under the reared neck and head, thinning to a
point past the tail), not vanilla's round one under its reared head, and not blobs along it, which read as several
shadows. A screen preview lies straight back in its rest sway. `nativeSeadramonTest` pins the lengths, the path round a
bend, the sway on land and in the water, a dive, the seat, the clips' pose with no weight on the trail; a head turned
round, a tight turn, a knock aside (no link belly up, no joint past 40 degrees, no fold); a ledge, a stair, a pit, a log
wall (no point in them, no fold); a climb given up, a head lowered off a ledge (nothing standing up); a dive off a bank;
no twist past the roll's step; down a bush and away (no curl); frame by frame no joint shaking; a head turned round on
the spot (no joint jumping); and the shadow band under every joint, fainter under the neck.

## Rescaling a species

Scaling a species changes exported geometry baked at the old scale (`kinetic_motion.json`, `attack_motion`,
`model_scale` in the attack data, the seat): Centarumon went .325 -> .36 on 22 September 2026.
