# Gesomon (`gesomon`)

Species notes for Gesomon: its seat and swimming pitch, the tentacle body's clip mixing, the whip (Devil
Bashing, the only whip so far) and Deadly Shade's ink. Shared mechanics: sea mounts and jet swimming in
[mounts.md](../mounts.md#sea-mounts), the Inked mark in
[combat.md](../combat.md#tactics-dodging-wraps-and-ink).

## Seat and pitch

Gesomon (25 September 2026) is a sea mount: the rider sits on the mantle's peak near its front edge, legs
close together down the crest's front face (`rider` on `mantle_tier_5` at `(0, 0.05, 0.28)`, pose
`(-0.35, 0.15, 0.3, 0.3)`; sheet seat `[0, 4.16, 0.26]`, `water_seat_offset` `[0, -0.25, 1.01]` because a
ridden swimmer keeps its swim pose, which carries the peak forward). Found offline by
`../harness/v2/out/gesomon/mount_02` (the first seat, `mount_01`, splayed the legs 0.9 rad down the flanks and
read as the splits; `mount_01/fk.py` poses the installed mesh as `NativeGroundModel` does,
`mount_02/review2.py` checks every clip and prints the sheet numbers).

In water the body pitches with the view about the rider's seat (`ground_models.json` `pitch_path` `["root"]`,
`pitch_at_rider`, `ridden_pitch` 45; `swim_pitch` 0 keeps the wild one upright as before): the rider and the
first-person eye stay put and the body swings under them, and an attack or the whip takes the body level as it
blends in (`divePitch`).

## Tentacle body and clips

A tentacle body (`support_floor`) mixes its idle, walk and swim clips by the shortest turn between whole poses
(`NativeGroundModel.mixGround`), never by adding up weighted Euler angles: an arm's angles in a clip may be
any triple that makes its turn, and half a walk of them (setting off, slowing) turned the arms any which way.

Its clips are generated without Blender by `../harness/v2/out/gesomon/motion_03` (`rig.py`: each arm on one
smooth bow, sections rolled with their undersides, local -z, to the ground, every part's Euler angles
continuous with the frame before, keys added wherever the game's interpolation would stray; `strays()` must be
0 on every clip, after `simplify` too; motion_02's clips spun parts half a turn between keys and flickered):
the walk is a calm crawl (the short arms ripple back to front, four low planted steps a cycle; the long arms
take turns reaching in a raised arch and gripping about five blocks ahead on their claw tips, heel down, palm
to the ground, hauling while the arch folds, then peeling off onto the claws; the body glides with about 1.5
degrees of roll; drift 0.000; `cycle_ticks` 38, `stride` 9.88, 0.13 blocks a tick alone and ridden,
`max_playback_rate` 2.6 and `tactics.fight_speed` 2.5), the swim is one jet pulse (see sea mounts) whose pads
swing about the arm's own root and stream straight back at the squeeze.

## The whip

Devil Bashing is a whip (`DigimonAttack.Kind.WHIP`, the move itself declared in
`data/digicube/whip_attacks.json`, which `motion_03/whip_data.py` writes from the mesh; `WhipAttacks`,
`WhipArm`) for the rider (`aim: whip`, input `hold`) and the AI alike; the authored four-beat clip, volumes
and motion are gone. Held, the long arm across from the aim swings back behind its own side and coils there,
further back and tighter toward the pad as the charge builds (full at 20 ticks), with a slow flex; let go, the
coil springs open and the root lashes on a spring at the aim (the crosshair seen from the arm's root,
`whipAim`; on land never steeper than lets the pad scrape the floor), follows it while the lash lasts (11
ticks) and every section trails the one before by `lag` ticks, so the wave cracks at the pad. The server
sweeps the arm between ticks and strikes each body it meets once a lash (never through a wall from the root),
at `power` [0.55, 1.15] by the charge times the pad's speed against the body it meets (a charger running into
the lash takes both) over `reference_speed`, slapped along the swing (less the bulkier the body). The rider's
client starts the wind-up and the lash itself (`predictWhip`), the others follow `DATA_WHIP` (the AI's aim
through `DATA_WHIP_YAW`/`PITCH`); `NativeGroundModel.whip` poses every section from its yaw and pitch alone,
keeping its rest roll to that direction's frame (`whipFrame`), with the pad's palm to the way the lash sweeps,
so nothing rolls or spins (the old wind-up twirled and the palm followed the swing round, a turn every 11
ticks).

The AI (`beginAiWhip`, `aiWhipAim`) winds the arm across from its prey, holds it 5 ticks against an enemy
whose blow is coming, 20 (full) against one hampered or committed to a long move, 11 otherwise, winds early
for a charger it sees coming, closes in while wound, leads the prey by `TargetMotion` and sweeps its aim 24
degrees through it, gives the arm up after 30 ticks out of reach, and strikes only what it fights; an enemy
reads its wind-up by `attackLandsIn`. A whip works up close, so its wielder never backs off for its other
moves (`minimumAttackSpacing`). Balance at 300 rounds: 49 % against Golemon (17.3 s), 52 % against Seadramon
(13.3 s), 22 % against Centarumon (open: its jet charge and Exposed cannon outpace the whip).

## Deadly Shade and ink

Deadly Shade is the special (`shot`, down or up at any angle under water), drawn and hit with at
`projectile_scale` 0.62 of its export (a kinetic shot may shrink: model and cuboids scale together); its
victim bursts with ink (`DCParticles.INK_SPLASH`) and, while Inked, is drawn stained and drips ink that lies
on the ground (`InkedVisuals`, `InkParticle`, `MixinLivingEntityRenderer`), and an inked player sees splats
over the screen's edges (`textures/gui/ink_splatter.png`, `../harness/v2/art/ink_overlay/make_ink.py`).

## Checks

`:fabric:nativeGesomonTest` pins the drawn seat to the sheet's, on land and afloat, that the pitch leaves the
rider in the saddle, the shrunken ink against its cuboids, the drawn whip against the arm it strikes with
(0.006 blocks), that a held pad keeps its face (under 10 degrees a tick) and that half a walk is the shortest
turn between idle and walk for every part; `rider_checks` whips a dummy, lets go on a left dummy and sweeps
the view onto a right one (both struck), and holds the view (only the left one); `gesomon_checks` has the AI
whip a cow and a Golemon from eight headings, still and moving (one strike a lash), and the boundaries (ally,
invulnerable, interrupted with its cooldown kept, walled off, lost prey); `DIGICUBE_WHIP_TRACE=true` logs
every tick of a rider's lash, and the combat trace (on in a development environment) the AI's wind-ups
(`[whip-ai]`).
