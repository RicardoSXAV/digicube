# Tentomon (`tentomon`)

Species notes for Tentomon: his body and rig, the waddle and the pivot, his flight (the carapace, the membranes, the
takeoff and the landing; his wings never tire), Twice Arm (both claws, on the ground or on the wing), Petit Thunder (cast
only on the wing) and the fight he flies from takeoff to landing. Shared mechanics: flight and fighting on the wing in [flight.md](../flight.md),
authored moves in [authored-attacks.md](../authored-attacks.md), shots in [combat.md](../combat.md#kinetic-shots).

## Body and rig

- Native model at `model_scale` 0.2 (1 px = 0.0125 blocks); box 1.1 wide, 1.3 tall, eye 0.85. About 100 px from the
  feet to the top of the carapace, 1.25 blocks.
- The root carries `body_root` (thorax, abdomen, pelvis, the dorsal spike and the membranes `wing_l`/`wing_r`),
  `arms_root` (the main arms `main_upper` > `main_forearm` with the claw, and the small arms `middle_upper` >
  `middle_lower` > `middle_palm` with four digits), `legs_root` (`leg_upper` > `leg_foot`: a short thigh and a rigid shin
  and foot whose four radial claws stand on a sole plane 26 px under the knee), `head` (antennae, mandible
  `lower_plate`) and `shell_root` (each carapace half hangs from `shell_carrier` > `shell_hinge`: opening it turns the
  hinge and seats the carrier).
- The membranes and the Petit Thunder coronas (`pt_corona_<side>`, under each wing) are membranes: hidden unless a clip
  shows them. Every flight clip keys the carapace open; the ground clips leave it shut.

## Ground

- `idle` (100 ticks): breathing (the carapace lifts a hair each breath), a weight shift foot to foot, looks to each side,
  the antennae swaying with a twitch, the small hands fidgeting, a mandible click. Planted feet roll a little on their
  claws as the body moves over them; they never slide or sink.
- `walk` lattice (`walk_25` to `walk`, `cycle_ticks` 10, `stride` 3.375, `max_playback_rate` 2.5): a brisk waddle on
  short legs; each foot strikes on its back claw, rolls flat and leaves from its front claw, the body bobbing low at
  double support and swaying and rolling over the standing foot, the hips twisting with the stepping leg, the main claws
  swinging against the legs and the head holding its gaze. 0.0675 blocks a tick at full amplitude; `base_speed` 0.19
  walks him at about 0.078 (1.15 times the clip) and runs him (`run_speed`, `fight_speed` 1.25) at about 0.12 (1.8
  times) and a panic (1.4) at about 0.155 (2.3 times), all inside the playback cap: the feet never slide. He flies to go
  fast.
- `pivot_left` / `pivot_right` (lattices at 0.5 and 1): one foot after the other, the planted foot turned with the ground
  as the body turns over it, 40 degrees a cycle (`pivot_reach` 0.342, `pivot_stride` 1.194, `pivot_cadence` 1.5).
- Paws: the two feet (`toe` on the front claw) with `ground_gait.footfalls`.

## Flight

Flight (`locomotion.flight`), unridden: speed 0.38, and `endless`: his wings never tire, so the reserve is never drawn
on and nothing but the AI brings him down. Its clocks are his own clips': `lift_tick` 8, `takeoff_ticks` 20,
`landing_ticks` 16, `loop_ticks` 1 (he lands at once on touching down).

- `takeoff` (20 ticks): a crouch, the carapace springs open (overshooting its seat), the membranes unfurl from the root
  (their length and breadth grow as they rise), a hop off both feet at tick 8 and the legs drawn up into the hover's hang.
  The catalog's `ground_out` 4 hands the gait over to it; the beat comes in from tick 6 over 3 (`wing_in`).
- Postures (`fly_hover`, `fly`, `fly_dash`, `fly_dive`, `fly_brake`, `fly_roll`, 40 ticks): the legs hang and swing
  after the bob, the claws held up before the chest, the antennae pressed back by the air; leans 0, 18, 30, 52, -14, 8
  about the body's middle (`flight.pivot` [0, -28, 4]).
- Wing layers (3 ticks): `wings_beat` strokes each membrane between 10 degrees over and 14 under its open carriage with a
  pronation twist of 32 degrees, a small fore and aft sweep making a figure of eight; `wings_power` wider and harder;
  `wings_fold` raised and still. The stroke stays inside the open covers and clear of the dorsal spike. On the wing clock
  (`FlightLook`, about 1.16 a tick in a hover, faster as the wings work) a hover beats about 7.7 times a second, an
  insect's buzz.
- `land` (24 ticks, touchdown at 8): the legs reach down through the approach (played on its progress), the knees take
  the weight, the membranes furl and vanish by 6 ticks after the touchdown (the beat fades over 5, `wing_out`), the
  carapace shuts with a small shake and he settles.

## Twice Arm

An authored `BOX_SWEEP` (22 ticks, `wing: true`, `flurry`): the left claw is cocked high over the shoulder as the body
coils back, then cuts down across the face as he steps in with the left foot (window 5.6 to 8.2); the right claw, coiled
meanwhile, cuts the other way as the right foot steps in (10.2 to 12.8): an X across the front. Both feet shuffle home in
the recovery. Each claw's volume (along the forearm to the claw's point, padded) strikes once; `flurry` lets the second
claw land through the hurt immunity the first left. `twice_arm_air` keeps the same root and arms exactly (the legs hang,
the carapace stays open, the wings beat), so one set of volumes and one effect serve both. `tentomon_fx`'s `twice_arm`
clip lays three crescents along each claw's real path, in the plane of the cut and turned a little toward the front, and
bursts a contact star where each claw lands (`contact_parts`). Style `pincer`. Power 1.0, range 2.2, cooldown 36.

## Petit Thunder

A `KINETIC_SHOT` cast only on the wing (`wing: "only"`, 34 ticks, released at 20): he rears back in the hover with the
arms spread and the membranes locked into a wide V, buzzing; the coronas on the membranes flare and crackle; bolts grow
from them to a junction ahead of the brow, an outlet runs on to the shock, which swells (`tentomon_fx`'s `petit_thunder`,
drawn in the root's frame, `follow_root`); at 20 he thrusts both claws forward and the shock leaves as
`petit_thunder_shot` (the shock's star, crackling) at 1.05 blocks a tick for 14 ticks, bursting in the impact star.
`aim_path` is the root: the whole hovering body pitches to the aim (up to 55 degrees), the effect with it. Shot style
`static` (ShockBall's lightning in white and gold), `proximity` 1.5. Power 1.15, range 11, cooldown 110.

## AI: the sortie

He fights the whole fight on the wing (`locomotion.flight.sortie`: height 2, range 4 to 9, `hold`, `strike` 0.35, linger
40). With prey within 21 blocks he takes off (a flight under way, or coming down, turns back to fight as prey turns up),
then goes round: with Petit Thunder ready he flies up to a perch 2 blocks over the prey's feet and 4 to 9 blocks out from
it, in the open and in sight, steadies and casts; with Twice Arm coming ready first he swoops in low, hovering 0.35 blocks
over the prey's feet beside it (his body's width from it, not his open wings'), where the claws reach, facing it, and
cuts as they come ready; a cast brakes him to a hover where he is, never under that height. A fight against a foe that
stands its ground runs Petit Thunder, three Twice Arms 36 ticks apart, Petit Thunder again. He comes down only once he has
had no prey for 40 ticks (or, badly hurt, escapes as any flyer does). Where he cannot take off (a low ceiling) he fights on
foot with Twice Arm (`prefer_close`, `fight_speed` 1.25).

## Checks

- `tentomon_checks` ([testing.md](../testing.md)): Twice Arm's two cuts at a foe in reach at eight headings, nothing out of
  reach, never an ally; Petit Thunder refused on the ground; Twice Arm on the wing at a foe level with him and at a foe
  on the ground from the strike height, at four headings each; and the fight on the wing at three headings (takes off,
  Petit Thunder only on the wing, swoops in low for Twice Arm, twice round both, never down while he has prey, lands
  once it is gone).
- `:fabric:nativeTentomonTest`: the idle's feet planted, no claw under the ground walking or pivoting, every flight
  phase finite, membranes and coronas shown only when they should be, feet on the ground from the touchdown, Twice Arm's
  drawn claws on its motion's markers on the ground and on the wing, Petit Thunder's drawn shock where the shot leaves.
- `speciesTest`: the moves, which of them go on the wing, the sortie (holding the air, the strike height), the endless
  wings, the flight's clocks and the waddle covering his walk and run within its playback cap. `flightTest` tests the
  reserve's rules on a tiring copy of his flight, and that his own never tires.
