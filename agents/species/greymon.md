# Greymon (`greymon`)

Species notes for Greymon: its body and seat, its planted walk that turns on its feet and its run lattice, the leap,
Great Antler (a pounce a biped aims with its neck, charged from a run or a leap) and Mega Flame (a burning fireball that
bursts). The mechanics only Greymon uses yet are described here: `pivot_walk` and `run_lattice` on the gait, a pounce's
`tip`, `run_start` and air form on a ground body, a shot's `blast`, the rider attack's `air`, and the catalog's
`attack_effects.follow`, `pounce_pivot` and `pounce_air_pivot`. Shared mechanics: rider attacks in
[mounts.md](../mounts.md), pounces and kinetic shots in [combat.md](../combat.md), gaits in
[locomotion.md](../locomotion.md), shot styles in [effects.md](../effects.md#shot-styles).

## Body and seat

- Native model at `model_scale` 0.45 (1 px = 0.028 blocks); hitbox 2.3 wide, 4.0 tall, eye 3.6. The root stands between
  the feet; the tail (six links) and the horns reach out of the box. Each leg is a thigh, a shin and a foot with three
  toes, every gait clip planted by leg IK.
- The body has no membranes. Its fire and horn light are `greymon_fx`, drawn in the head's frame as the head is drawn:
  the catalog's `attack_effects.follow` names the part path (`root` to `head`) whose drawn pose the effect's top part
  takes (`NativeGroundModel` fills `DigimonRenderState.drawnFollow`, `NativeEffectModel` sets it), so the fire stays in
  the jaws whatever the gait, the leap, a pounce's pitch or the aim do to the head.
- The rider sits on the back behind the neck (`rider` on `torso` at `(0, 1.35, 1.85)`, pose `(-1.1, 0.8, 1.1, 1)`, lean
  `(0.6, 0.6)`; sheet seat `[0, 2.52, -0.6356]`). `look` turns the head 35 degrees each way and 22 up and down, handing
  the neck two fifths. `bank`: under a rider at a run it leans into its turns. Voice: the ravager's at 0.9, its roar the
  cry (`voices.json`).

## Gaits

All clips share one phase (`cycle_ticks` 16) and are planted: the lowest point of each sole (the heel as the walk
strikes, the ball as the run lands, the whole sole between) runs back at exactly its column's pace. Each knee bends
toward its foot's heading (splayed 13 to 8 degrees out over the walk, 6 to 4 over the run; the rest pose's knees splay
16), and every leg angle lies within half a turn of the part's rest angles, so the clips agree with one another: the model
mixes the walk's columns, the run's and the walk into the run by adding their weighted angles, and a leg whose angles
wrapped a whole turn in one clip, or whose hip twisted, bent its knee out sideways in the mix. The head is held steady
over the gaits' bob: the neck tilts to carry about half its rise and fall, and the head turns against most of its nod.

- `walk` lattice (`walk_zero`, `walk_25` to `walk`): heel strike and roll, low heavy steps (the toes lifted about a
  quarter block at full amplitude), the feet on a track that narrows with the pace (22 px from the middle at the full
  walk, 25 at rest), the trunk leaning 2 to 9 degrees into its pace with a bob and a sway over the standing foot, the
  tail swinging against the hips (`stride` 5.25: 0.148 blocks a tick at the clip's own rate; `max_playback_rate` 1.65).
- `pivot_left` / `pivot_right` lattices (`walk_zero`, `pivot_*_50`, `pivot_*_100`; `pivot_reach` 0.708, `pivot_stride`
  1.373, 50 degrees a cycle at full amplitude; `pivot_cadence` 1.5, about 4.7 degrees a tick standing). With
  `pivot_walk` on the gait the pivot steps on the walk's own beats (each foot down when the walk puts it down), so a body
  that turns as it walks mixes the walk and the pivot by their shares at any pace of the walk and goes round in an arc on
  planted feet (`DigimonEntity` gives the pivot its whole share; without the key only a body all but standing pivots).
- `run` lattice (`run_45`, `run_70`, `run`) with `run_lattice`: a column per share of the run's pace, each planted on
  its own stride (`run_stride` 9.778 times the share), chosen by `DigimonGait.runShare` (the ground covered against the
  run's pace, `run_cycle_ticks` 11: 0.4 blocks a tick at the full column) and paid by `DigimonGait.advance(..., share)`,
  so a jog and a sprint keep the run's cadence; the model reads the share as `DigimonRenderState.groundRunShare`. The run
  takes over from 0.19 blocks a tick and hands back under 0.16. A dinosaur's run: the trunk level and leaning 11 to 20
  degrees, the tail held out behind, the arms tucked, each foot landing on its ball under the hips (19.5 px from the
  middle at the full column). A swinging foot's toes go from how they left the ground to how they will meet it.
- `jump` (22 ticks, `DigimonEntity.LEAP_END`): a crouch, the push-off, the legs tucked, reaching for the ground and the
  landing absorbed. The mount's `jump` 0.5 and `leap_carry` 0.94 keep a run's pace through the leap (about 5.6 blocks
  long and 3.3 up at a sprint).
- Sheet: `base_speed` 0.32 with `walk_speed` 0.8 and `run_speed` 1.15 (the AI walks near 0.144 blocks a tick and runs
  near 0.3; a panic, 0.44, plays the run at 1.61 of its rate); `sprint` 1.5 built over 35 ticks (about 0.44 under a
  rider), `turn_rate` 11, `turn_to_travel`, `camera_distance` 7.5; tactics `hold_range` 5 to 10, `strafe`,
  `shoot_moving`, `fight_speed` 1.3.
- `stomps` in the catalog: both feet's beats (`down` 0.98 and 0.48) where the full walk lands them (`feet` 0.62 aside,
  0.83 ahead), a ravager's step, a roar at 0.86 as a ridden one breaks into its run.

## Great Antler

A pounce (`pounce_attacks.json`, 18 ticks, `power` 1.2, `cooldown` 50, `gather` 3, `burst` 6 at 1.05 easing to 0.55
blocks a tick, `exit` 0.35, `cone` 20, pitch -25 to 30 on the ground and -70 to 60 in the air, contact from tick 3 to 12,
`snap` 8, `knockback` 1.1, `shake` false). The clip coils, swings the head down until the helmet's horns level ahead,
drives, rams (8.5) and tosses the head (10.5); `great_antler_impact_fx` bursts where the horns strike. The AI pounces from
7 blocks.

- `tip` 0.35: a biped keeps its feet under it. The body tips only that share of the line's pitch, about the catalog's
  `pounce_pivot` (model px from the model's origin, y down: the middle of the body's height, where the server's
  `PounceLines.pitchPivot` tips it), and the aim part (the neck) turns the rest of the way about the body's own axis (a
  part its clip twists still pitches) as the motion's `aim_weight` lets it (`NativeGroundModel.pouncePitch`); the server
  poses its horn the same way (`PounceLines.posed`), so a charge
  aimed up at a flyer or down at small prey strikes where it is drawn.
- `run_start` 0.2: cast at that pace or more (blocks a tick) the charge skips its gather and keeps its pace into the burst
  (the start event's `PounceAttacks.Spec.RUNNING` form, `startTick(form)`).
- `air`: cast in a leap, the air form plays (its own motion and clip `great_antler_air`: coiled in the air, the horns
  lanced along the line, a toss and a falling pose; `burst` 6 at 1.4 easing to 0.8, `exit` 0.6). Its whole body tips
  along the line about `pounce_air_pivot` (the seat, as the server tips an air form); the model plays a move's
  `<name>_air` clip whenever it is cast from a leap and the model has one.
- `greymon_fx` (`great_antler`, `great_antler_air`): glints at the three horn tips as the charge gathers, streaks
  streaming back off them through the drive (their `*_trail` parts counter-turned against the head, so they trail level
  with the body however low the head goes), a flash at the brow at the ram.

## Mega Flame

A kinetic shot (`kinetic_attacks.json`: 30 ticks, release 12, range 16, `power` 2.2, `cooldown` 140, `knockback` 0.6):
Greymon rears back through the torso, neck and head as its jaws gape, thrusts and spits the ball at 12, then recoils.
The ball leaves from the jaws' middle as the standing clip has it.

- The fireball is the `mega_flame` effect model: a stepped flame core, fluttering licks and embers, drawn at 1.35
  (`model_scale` 0.45 times `projectile_scale` 3), a ball 1.2 blocks across flying at 0.85 blocks a tick for 28 ticks.
  Its `effect` clip is the flight from launch (not looping: the licks' trail grows over the first three ticks and the
  embers come out one by one, the flutter repeating after); `impact` is its ten-tick breakup (`impact_ticks` 10). The same
  ball forms in the jaws through the wind-up (`greymon_fx` `mf_*`, the flare's charge, from tick 3 to 18), in the art's
  own corner of the effect's atlas.
- `blast` (`KineticAttacks.Blast`: `radius` 1.8, `falloff` 0.45): where the ball bursts, on a block or a body, every
  other body within the radius and in sight of the burst takes the shot's damage times 1 - 0.45 x distance / radius, and
  its `burn` (120 ticks, a Burn). A ball that flies its range out fizzles without one.
- `"shot_style": "fire"` (`ShotStyle.FIRE`): the roar and spurt of fire at the jaws, a trail of flame and smoke, the
  explosion where it bursts, a fizzle when it runs out.
- Rider: `move` and `air` (`RiderAttack.air`): the legs keep the gait or the leap while the neck's subtree (`upper_body`)
  plays the move and turns to the aim, and the press works in a leap too. The AI looses it on the move as well
  (`tactics.shoot_moving`).

## Checks

- `greymon_checks` ([testing.md](../testing.md#index-of-checks)): ridden walk and run, turning on the spot no faster than
  its pivot, turning as it walks (its travel along its heading), a leap at the run that keeps its pace; Great Antler at a
  small foe ahead, aimed up at one on a pillar, from the run (no gather, its pace kept) and from a leap; Mega Flame
  standing (alight), on the run (the legs running on), from a leap and its burst catching a second foe; wild, a charge
  and the fireball. `DIGICUBE_GREYMON_ONLY=<prefix>` runs the checks named so.
- `:fabric:nativeGreymonTest`: every gait column's feet planted and the standing foot still while it turns on the spot,
  each knee within 30 degrees of ahead in every mix of the gait clips (walk columns, run columns, the walk into the run),
  the seat and its sway, the drawn horn on the server's through the charge level and aimed up and down, the air form's
  clip from a leap, the ball leaving from the drawn jaws, the head's effect on the head as drawn, the clips' lengths.
- `gait_checks:greymon` (walk, panic and run PASS) and `rider_checks` (both slots).
