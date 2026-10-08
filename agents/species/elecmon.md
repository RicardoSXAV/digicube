# Elecmon (`elecmon`)

Species notes for Elecmon: its rig, its gaits and idle, Sparkling Thunder (a discharge from a ball over its fan of
nine tails) and Nine Tails (a whirl). Shared mechanics: discharges and whole turns in
[authored-attacks.md](../authored-attacks.md), the bolts' drawing in [effects.md](../effects.md#discharges), gaits in
[locomotion.md](../locomotion.md).

## Body and rig

Native model at `model_scale` 0.3 (1 px = 0.019 blocks); hitbox 0.9 wide, 1.05 tall. It stands on all fours at rest,
the fan raised behind it. The mesh adds level empties to the model's hierarchy without moving any face: `tail_fan` is
level, and each tail hangs from a `tail_NN_turn` that holds its rest direction, so its base rests at zero turn (x bends
it out of the fan, + toward the head; z within the fan, + toward his left), far from the Euler lock; `neck_turn` and
`head_turn` are level, so the head look (`look`, a share carried by `neck_turn`) turns the head about the vertical
although the spine lies pitched 65 degrees; `shoulder_*` and `hip_*` are level girdles the leg clips swing (x),
spread (z) and turn (y).

Paces (`gait_checks:elecmon`): `base_speed` 0.19; following at `walk_speed` 1.35 it trots at 0.145 blocks a tick
(cadence 1.76 of a 2.4 cap); `run_speed` 1.85 runs at 0.272.

## Gaits and idle

All clips are planted by leg IK. The walk lattice (`cycle_ticks` 10, `stride` 2.75 units at full amplitude) is a
lateral walk at low amplitudes and a quick, low trot at full; the legs are short, so the forepaws stand under the
shoulders at a trot, forward of them at rest. Backing up, stepping aside and turning on the spot have their own
lattices (`pivot_reach` 0.61, `pivot_stride` 1.43, `pivot_cadence` 1.6: about 6 degrees a tick, one paw at a time).
The run is a half-bound (`run_from` 0.17, `run_until` 0.14, `run_stride` 5.25 units, `run_cycle_ticks` 6): the spine
stretches and bunches, the ears lie back and the fan streams behind, narrowed. Through every gait the fan sways with
the hips and bounces a beat after the footfalls, a ripple running out across it. Its paws are heard where they land
(`paws`, `footfalls`).

The idle (160 ticks) breathes on all fours, then sits up into the approved upright stance (the hind paws planted, the
forepaws carried up to the chest, the fan laid down behind), looks left and right and tilts its head, comes down,
wiggles its hips and shakes the fan. It blinks: `expressions` windows on the idle's own clock show `elecmon_blink`
([effects.md](../effects.md#expressions)), which also squeezes its eyes shut through the whirl and the discharge's
flash.

## Sparkling Thunder

A discharging BOX_BURST (30 ticks, discharge at 14, `range` 9.5). He rears up three fifths of the way to the upright
stance with the fan raised behind his head and opened wide; the tips light from the outer pair inward, sparks leap
from tip to tip and a ball swells over the fan; at 13 he throws himself down onto his forepaws, the fan whipping
forward over his back, and the bolt leaves the ball (the motion's mouth marker). Its `arc`: `reach` 9, `cone` 75,
`burst` 1.6, `chain` 3 jumps of up to 4.5 blocks at 0.55, `water_reach` 5 at 0.6, `life` 8. `sparkling_thunder_fx`
draws the tip sparkles, the arcs between the tips, the ball, the release burst, bolts running out along the ground
from where the forepaws land and a shock ring there.

## Nine Tails

A BOX_SWEEP (16 ticks, no travel): a coil, a hop and one whirl to his right (ticks 3.4 to 9.2) with the tails laid
back flat and spread into a disc, landing where he took off. Its volumes are the nine tails, one box along each,
padded 2.5 px, live from 4 to 9: a foe on any side within about 0.96 blocks of his middle is struck once, and
whichever way he faces the lash reaches the front within a third of a second. `range` 2.6 is only the
centre-to-centre gate (a big body's side is within reach from farther out); the volumes decide. The turn is a whole
turn of the root ([authored-attacks.md](../authored-attacks.md#whole-turns)). `nine_tails_fx` draws the tip sparkles,
two crescents of light turning behind the tails at the disc's height, a ring where he lands and sparks thrown off.

Tactics: `prefer_close` (the whirl whenever a foe is close and it is ready), `fight_speed` 1.6, `dodge_chance` 0.4.
Both moves sound with the game's own sounds for now (a charging hum and a crack; a whirl of air and a smack).

## Checks

- `elecmon_checks` ([testing.md](../testing.md)): the bolt at 2.5 to 7.5 blocks at every heading, behind a wall (none),
  along a line of three foes, the burst from the body, a cow and an ally standing by (spared); the whirl at a small
  body ahead, beside and behind at every heading and at a big one, out of reach (none), at foes on both sides, beside
  an ally (spared), the body staying where it stood.
- `gait_checks:elecmon`: walk, panic and run within the walk's cadence cap.
- A duel (`elecmon_vs_agumon@flat+duel`) uses both moves, and every cast lands.
