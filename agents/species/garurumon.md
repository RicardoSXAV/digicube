# Garurumon (`garurumon`)

Species notes for Garurumon: its body and seat, the planted walk lattice, the gallop and the pivot, its grip and skid on ice,
tail and paws, the leap, Freeze Fang (a pounce) and Howling Blaster (a breath of puffs), and the Freeze mark they fill. Shared mechanics: pounces and breaths
as data and the Freeze mark in [combat.md](../combat.md), rider aims and leaps in [mounts.md](../mounts.md), gaits in
[locomotion.md](../locomotion.md).

## Body and seat

- Native model at `model_scale` 0.35 (1 px = 0.021875 blocks); hitbox 1.8 wide (off 2.0 for pathing), 2.1 tall, eye
  1.85. The model's root sits 20 px behind the entity position, so the box is centred between the paws, a little
  toward the head: the nose is 2.47 blocks ahead of it.
- The Freeze Fang canines are part of the body mesh (`ff_*`, children of `head` and `jaw`), membranes hidden unless
  the bite's clip shows them, so they follow the head and jaw through any blend. Their atlas sits under the body's
  (1024 x 384).
- The rider sits on the back behind the withers (`rider` on `torso` at `(0, 0.759, 0.638)`, pose
  `(-0.4, 0.12, 0.62, 1.5)`; sheet seat `[0, 1.82, -0.2]`). Voice: the big wolf's at 0.8, its move cry the angry
  wolf's growl (`voices.json`).

## Gaits and the leap

All clips are planted by leg IK on one shared phase (`cycle_ticks` 14):

- `walk` lattice (`walk_2` to `walk`): a lateral-sequence four-beat walk at low amplitude that becomes a trot at full
  amplitude (`stride` 9.6, 0.24 blocks a tick). `walk_back` (`back_stride` 4) and `strafe_left` / `strafe_right`
  (`side_stride` 3.5) for the AI's footwork; a rider turns to travel instead.
- `run`: a rotary gallop with two suspensions (`run_stride` 16 on the walk's phase, full at 0.4 blocks a tick; faster
  it quickens its cadence up to `max_playback_rate` 3). It takes over at once from 0.3 blocks a tick and hands back
  under 0.26 (`run_from`, `run_until`), so the ridden cruise and the AI's run are a whole gallop.
- `pivot_left` / `pivot_right`: turning on the spot round the centre, 44 degrees a cycle at full amplitude (the fore toe
  line, `pivot_reach` 1.27, sweeps `pivot_stride` 2.8), one paw at a time (for a right turn the right fore, the left
  fore, the left hind, the right hind; each stands 0.68 of the cycle), the forepaws 5 px and the hind paws 8 px wider
  than at rest so a pair never touches, the spine bent 7 degrees into the turn, the head leading. `pivot_cadence` 1.6:
  it turns about 5 degrees a tick standing, ridden or wild, and 7.6 hurried ([locomotion.md](../locomotion.md#steady-turning)).
- `ice_grip` 0.4 ([locomotion.md](../locomotion.md#sure-footing)): on ice it gathers pace more slowly, runs no faster
  than on stone, skids about 3.6 blocks from a gallop and drifts through a hard bend. `skid`: the forepaws braced 30 px
  ahead, the hind paws 24 px under, 3 px wider, the haunches 9 px down, a 10-degree lean back, the head up.
- `jump` (22 ticks, `tickLeapPose`'s clock): a gather, the hind drive, the forelegs tucked then reaching, forefeet
  first on landing. `body.mount.jump` 0.6 with `leap_carry` 0.96, so a leap at the gallop flies far (the checks below).
- Sheet: `base_speed` 0.31, `run_speed` 1.34 (the AI's run and the ridden cruise, about 0.38 blocks a tick), `sprint`
  2.2 built over 30 ticks (about 0.82), `turn_rate` 18, `turn_to_travel`, `camera_distance` 6; tactics `hold_range`
  4-9, `fight_speed` 1.6, `gallop`, `press_impaired`, dodge 0.5.
- Tail (`tails`, [animation.md](../animation.md#tails)): `tail_segment_0` to `_2` and `tail_tuft` under `tail`, tip at
  121 px, the tuft's fur cards carried; stiffness 0.65 to 0.35, damping 0.75, drag 0.08, sag 0.015, bends 25 (40 at the
  root): a 4-degree droop standing, a swing out of a turn, a lift in a fall; steps up and down a hillside eased in.
- Paws (`paws`, toe line 11 px down and 22 ahead in the paw's frame) with `ground_gait.footfalls`: each paw sounds the
  ground where it lands; on grass, moss, leaf litter, petals and leaves its own step takes the ground's place
  (`garurumon_step`: eight takes of a soft swish through grass, cut from CC0 recordings of steps in grass, lowered),
  and quicker landings are each softer.

## Freeze Fang

A pounce (`pounce_attacks.json`, clip `freeze_fang`, 16 ticks): a 2-tick gather (skipped in the air), a 6-tick burst
of about 5 blocks eased from 1.35 to 0.6 blocks a tick, the jaws open through the burst and shutting at tick 8, two
stacked uses 40 ticks apart. A bite pays 45 into the victim's Freeze gauge; a Frozen victim takes double and is broken
out of the ice. The body pitches along the burst (`pouncePitch` in the model, about the middle of the back), the jaws
stream frost, and the bite bursts `freeze_fang_impact_fx` where the jaws met.

- AI: dashes at where its prey will be (`PounceLines.ai`), turning up to 6 degrees a tick after it; from under 3.5
  blocks, at Frozen or frost-resistant prey, or when the breath is not worth it. Prey on a ledge above, or just out of
  reach with nothing better to do, is leapt at and pounced on from the top of the leap (`leapToPounce`).
- Rider: the quick button; the rider's client flies the dash along the crosshair from the press (bent toward the
  outlined enemy), on the ground (pitch -35 to 35) or mid-leap (-70 to 60, the fall held through the burst); the
  server bites along the path the body takes.

## Howling Blaster

A breath of puffs (`breath_attacks.json`, clip `howling_blaster`, the neck's subtree only so the legs keep their
gait): each tick the mouth sheds two puffs at 1.55 blocks a tick with the body's own motion; they slow (0.9 a tick),
sink a little, slide along what they hit (a wall met head-on splashes them over it) and die after 16 ticks (a reach of
about 12.9 blocks). Their radius follows the drawn flame's outline: 0.2 at the mouth, 0.56 about six blocks out
(age 5), 0.34 at the tips. Contact pays 3.5 a tick into the Freeze gauge and a damage pulse every 10 ticks per victim;
still water it crosses turns to frosted ice and fire goes out (with `mobGriefing`). The neck turns to the aim up to 60
degrees (`twist`); the rider's own client aims the newest puffs from the mouse. Drawn by `FrostBreathRenderer` as a
stream of `howling_blaster_fx`'s glowing boxes, each puff trailing its own along its flight, about a block across at
its broadest ([effects.md](../effects.md#breaths-and-pounces)); snowflakes and cold smoke stream off its skin and burst
where it strikes. It sounds as an icy howl (`howling_blaster_start`, `_loop` and `_end`: original, generated, a low
roar under slowly gliding whistles with glassy ice chips).

- AI: breathes on prey from 3.5 blocks out while its gauge can fill and the tank holds 45 %, sweeping after it with a
  lead, its body stepping round after the aim (at most 7.6 degrees a tick) and its neck turning the rest of the way (up
  to 60 degrees); the pounce that follows the freeze shatters.
- Rider: the special button, held, on the run (`move`), from the ground or mid-leap.

## Checks

- `garurumon_checks` ([testing.md](../testing.md)): cruise and gallop pace, the gallop on ice (no faster than on
  stone, gathered more slowly), a stop there that skids 2.5 to 6.5 blocks with its legs braced, a 60-degree bend at the
  gallop (its travel within 12 degrees of its heading) and the same on ice (a drift, the legs along the heading), a
  turn on the spot (at most its pivot's rate, gathering in and braking out), a standing leap and a leap at the gallop (long, high and unhurt), a pounce ahead, one steered by the
  crosshair and one from a leap, the breath steered across two dummies and one started at the top of a leap, a breath
  that freezes and the pounce that shatters, fire put out and water frozen; then wild, its body after its head at its
  pivot's rate, eased, and onto a path behind it (stepping round on the spot first, never walking sideways) at a walk and
  hurried; the AI's pounce, its breath at prey off to its side (the body at most its hurried rate, the aim within the
  neck's twist), its freeze and shatter, and its leap onto a ledge.
- `:fabric:nativeGarurumonTest`: every paw's toe line stands still on the ground at its lattice's pace (the walk's
  columns, the gallop, back and aside), and turning on the spot still in the world as the body turns over it (a pair
  never touching), each paw is heard once a stride in its gait's order (the walk's four beats, the gallop's hind pair
  then fore pair, the pivot's one paw at a time), the skid stands every paw on the
  ground braced, the tail sags a little, swings out of a turn, lifts in a fall, wobbles under 5 px over steps, keeps its
  links whole and moves the same at any frame rate, the rider is drawn at the sheet's seat, the canines show only through the bite,
  a pounce's pitch tips the body about its back, and Howling Blaster's flame (`FrostBreathRenderer.place`): every box
  whole at the flame's pixel and every face on the flame's art, broadest partway out and thin at both ends like the
  approved flame, its top up, as wide as the breath strikes, no two boxes sharing a face's plane where they overlap
  (they would flicker), one body with no gap when steady, still whole blocks when whipped at the full turn (none longer
  than the art's longest) and each riding its own puff's flight, thinning at the tail once it leaves the mouth, and flat
  on a wall a puff struck.
- `gait_checks:garurumon`: walk, panic and run all PASS (the AI's run plays the gallop).
- `speciesTest` (`IceComboRegressionTest`, `FrostBreathRegressionTest`) and `locomotionTest`
  (`CombatPressureRegressionTest`): the data, the puffs' flight, the AI's choice between pounce and breath.
