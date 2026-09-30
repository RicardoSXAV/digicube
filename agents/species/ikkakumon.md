# Ikkakumon (`ikkakumon`)

Species notes for Ikkakumon, the sea mount that also walks: its seat on the mane, the slow planted walk and the
galumph, how it swims under a rider, and its voice. Shared mechanics: sea mounts, holding the surface, steering
and the barrel roll, and a rider who leans with the body in [mounts.md](../mounts.md#sea-mounts); planted gaits and the
pace on the ground in [locomotion.md](../locomotion.md); the catalog keys in
[animation.md](../animation.md#generic-catalog-keys); voices in [effects.md](../effects.md).

## Seat

The rider sits astride the mane just behind the head, centred (`rider` on `neck` at `(0, 1.275, 1.641)`), legs hanging
down into the mane's sides, a little back and apart (`pose` `(0.15, 0.05, 0.35, 1.5)`: the head is two blocks wide
just ahead of the hips, so legs reaching forward or out went through it or stuck out sideways). The rider tips with the
body as it dives, banks and rolls (`rider.lean` `[0.8, 1]`). The sheet's seat `[-0.3, 2.758, 0.05]` is the
first-person eye, 0.3 blocks to the right of the drawn rider: the horn rises past the eye just in front of it, so
centred it would stand on the crosshair. Swimming carries the seat 0.25 up and 0.86 forward (`water_seat_offset`);
every swimming clip keeps the seat there within 0.2 blocks. No part of the head, horn or forelegs touches the rider in
any pose; most of the legs sink into the fur.

## On land

A walrus is slow and heavy out of the water:

- `walk`, 0.10 blocks a tick (`base_speed` 0.03492 at `walk_speed` 1.3): a four-beat walk (left hind, left fore a
  fifth of a cycle later, then the right pair), 18 ticks, `stride` 3.6. Every foot is flat and still through its
  stance: heel down first, then the heel peels up about the toe tips. The shoulders dip where a straight foreleg would
  have to stretch. The chest rolls off the unsupported shoulder, the hips waddle, the head nods on each forefoot, and
  the fur follows a beat late. `walk_back` (`back_stride` 1.152) and a short side step (`side_stride` 1.08, for a
  shove: the rider never side-steps, `turn_to_travel`) share the phase.
- `run`, 0.1875 blocks a tick (`run_stride` 6 over `run_cycle_ticks` 16, keyed on the walk's 18-tick phase): a
  galumph, half a bound. The forefeet land together, the body rocks forward over them while the hind feet swing up
  under it, then the hind pair heaves the front up and on. The rider sprints into it (`sprint` 1.875, over
  `sprint_build` 20). His fights keep the 0.30 they always had (`tactics.fight_speed` 3.9), the galumph played 1.6
  times as fast.
- The shoulder and haunch coats turn only 40 % of their leg's swing, and the rump's locks lift aside as a hind foot
  passes under them.
- `stomps`: his own flipper footfall (`ikkakumon_step`), on the forefeet walking and on each pair galumphing; breaking
  into the galumph under a rider he bellows (`roar`).

## In water

It is a sea mount (`water_turn_rate` 9, `water_sprint` 1.6) that ferries its rider along the surface. It swims at
0.52 blocks a tick (`swim_speed`), gathering up to 0.83 on the surge over a second (`sprint_build` 20, as on land):
faster than Gomamon (0.46) and Gesomon (0.38), slower than Seadramon (0.7).

- It floats head and mane out, the rider well clear of the water (`float_line` 0.58).
- It holds the surface (`surface_dive` 30): glancing down or up it swims on level; looking down past 30 degrees, or C,
  dives; a surge looking up past it breaches.
- A and D steer it (`turn_to_travel`), as on land.
- A double tap of Space is a barrel roll toward the side it is steering to, thrown on along its way (`water_roll` 0.28).

The clips:

- `swim_surface`, 12 ticks: head up, the foreflippers paddle in turn, reaching ahead and pulling down and back, the
  hind feet scull like a tail under the surface, the body rocks and nods with each paddle.
- `swim_surface_dash`, 9 ticks: the same on the surge, deeper and harder, the chest down into the bow wave.
- `swim`, a 14-tick stroke under water: the foreflippers sweep back together and feather forward, and the hind feet
  scull side to side with the rear body.
- `swim_dash`, 10 ticks, the surge under water: the flippers along the flanks steering, the tail feet beating hard twice
  a cycle with the rear body swinging.
- `swim_leap`, out of the water on a breach: arched, flippers sweeping back, the tail feet fluttering.
- `swim_idle` (wild only): head up, sculling.

The surface clips shift the whole body so the seat stays where the underwater ones keep it. The body pitches about the
rider for dives (`pitch_at_rider`, `ridden_pitch` 55) and banks up to 35 degrees into turns under water, a third of
that afloat (`swim_bank`). `swim_wake` throws a bow wave and a wake, a splash at each paddle, bubbles under water,
splashes, and a spray on surfacing (no `blow`: he makes no sound of his own there).

## Voice

Five sounds in `assets/digicube/sounds/ikkakumon/`, named in `voices.json` and the model catalog:

- three low, rough closed-mouth hums (`hum_1` to `_3`, -16 LUFS) are his whole voice: `ikkakumon_call` plays them at
  half volume (about -22 LUFS, as quiet as a vanilla polar bear's breathing), `ikkakumon_hurt` higher (pitch 1.12),
  `ikkakumon_death` lower (0.82, volume 0.8), and `ikkakumon_bellow` a little deeper (0.9, volume 0.9) as each of his
  moves starts (the voice's `cry`) and as he breaks into the galumph (`roar`);
- two heavy flipper footfalls (`step_1`, `step_2`, about -20 LUFS) are `ikkakumon_step`.

## Checks

- `:fabric:nativeIkkakumonTest`:
  - every ankle is planted through its stance in all four walking directions and the galumph;
  - the rider is drawn 0.3 blocks left of the eye at rest, afloat, stroking, dashing and paddling at the surface;
  - the rider leans with a dive, a bank (a third as far at the surface) and a roll;
  - `swim_wake`'s beats match the swim clips' lengths;
  - an attack's bow carries the rider under a block;
  - plus the horn and harpoon parity it always had.
- `gait_checks:ikkakumon`: walk, panic and run all planted.
- `sea_mount_checks`:
  - cruise, surge, dive, the surface at its float line, the keys and a breach;
  - the surface: it climbs 70 ticks, not 110 (once afloat it swims on level at full pace and would reach the quay);
  - `surface surge`: 45 ticks along the surface looking 70 % of `surface_dive` down, within 0.25 blocks of the float
    line at 80 % of the surge;
  - `surface dive`: 15 degrees past `surface_dive`, it sinks over 1.2 blocks in 30 ticks;
  - `steer`: W and A turn it to -45 degrees and over 2 blocks left;
  - `barrel roll`: two double taps of jump, steered right then left, roll both ways and gain 0.1 blocks a tick each;
  - the haul out, walking and sprinting, and the attacks afloat.
- `rider_checks` and `ikkakumon_checks` cover both attacks.
