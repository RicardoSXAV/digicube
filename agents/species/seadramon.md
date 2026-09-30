# Seadramon (`seadramon`)

Species notes for Seadramon, the sea serpent: its body that lies along the path its head took, how it slithers, climbs
and swims, how it carries a rider through the water, and Ice Blast breathed on the move. Shared mechanics: serpents and
swimming on a path in [locomotion.md](../locomotion.md#serpents), the spine, its clips and its shadow in
[animation.md](../animation.md#serpent-spines), sea mounts in [mounts.md](../mounts.md#sea-mounts), the frost stream,
Cold and the wrap in [combat.md](../combat.md#combat-marks) and [combat.md](../combat.md#tactics-dodging-wraps-and-ink),
and its slow travel with a fight pace in [mounts.md](../mounts.md#pace-sprint-and-leaps).

## Body

- A box 0.9 wide and 2.65 tall round the head; nine hit parts reach 9.4 blocks back along its trail.
- `body.serpent`: the hit parts' middles 1.3 blocks up in the water (`swim_height`); it turns no tighter than a circle of
  1.6 blocks on land and 3 in the water at its pace, 4 degrees a tick standing; its head turns at most 70 degrees off its
  body (`neck_turn`); it climbs walls up to half its body high (`climb_share` 0.5 of 9.2 blocks: 4.6); its swimming head
  is 0.42 blocks lower than the land
  pose Ice Blast was measured in (`swim_head_drop`).
- `spine`: `body_00` to `body_25` under `root`/`float`, `tail_leaf` at the tip; the head keeps its own heading over 3
  blocks; a sway 0.55 blocks aside on land and 0.18 / 0.45 / 0.62 swimming at rest / cruising / dashing, 4.5 blocks from
  crest to crest, running back at 1 / 0.7 of the swim.
- Heights above the feet in the clips (the chain straight in plan in all of them):
  - on land (`idle`, `walk`): the neck reared from the ground 2.5 blocks back up to the head at 2.15, the rest on the
    ground; the walk plays the fins and head on the gait's phase (`stride` 24 at its land pace of 0.3 blocks a tick);
  - `swim` (28 ticks) and `swim_dash` (14): level at 1.7, inside the box, the fins sculling;
  - `swim_surface` (28) and `swim_surface_dash` (14), afloat at its float line (0.4 of its height: the surface 1.06
    blocks up): the body at 0.7, its back breaking the surface, the neck rising 3.2 blocks (4.4 surging) to the head at
    1.75, so the seat stays where it is under water;
  - `swim_leap` (20): straight along its arc out of the water, jaws parted;
  - `swim_idle` (100, wild at rest): level at 1.7, the neck reared to the head at 2.5, breathing.
- The rider sits on the neck behind the head at 2.2 blocks on land (`seat`) and 2.05 swimming (`water_seat_offset`
  `[0, -0.15, 0.16]`); the surface clips keep it within 0.1 of that.

## On land

It goes 0.3 blocks a tick (`walk_speed` and `run_speed` 2 on `base_speed` 0.07: faster than a player walks), and ridden
up to 0.46 sprinting (`sprint` 1.5), turning at most 6 degrees a tick (`turn_rate`).

- Standing, its head turns 70 degrees at most off its body: a rider looking back turns it that far, and going on (W, or
  the strafe keys) it curls round after its head. Wild, it never turns on the spot past that either.
- A wall or a ledge up to 4.6 blocks high (half its body) it climbs, and a bank that high over the water: its neck rears
  up the face, its head goes over the top and its body drapes over the edge after it. A higher wall stops it. Off a
  ledge it lowers itself down the face, unhurt. On steps and broken ground its body lies over the edges, never down every
  riser.

## In the water

A sea mount (`water_turn_rate` 9, `water_sprint` 1.5, `sprint_build` by default): 0.7 blocks a tick, 1.05 on the surge.

- It holds the surface (`surface_dive` 30) with its neck and its rider out; looking down past 30 degrees, or C, dives;
  a surge looking up breaches, the whole body arcing out after the head.
- A and D steer it (`turn_to_travel`), and it carves the turn as a serpent does.
- A double tap of Space rolls it round its length (`water_roll` 0.3), the whole body corkscrewing after the head.
- `swim_wake` (28, 28, 14): a bow wave off the head, a wake off its back along the body at the surface and spray from it
  on a dash, bubbles off its length under water.

## Attacks

- Ice Blast, the first rider slot, held: `move`, so the body swims (or slithers) on under the rider's keys while the
  head (`upper_body`) turns to the crosshair, up to 70 degrees off the body. It charges Cold, and on the sea it freezes
  floes of frosted ice where it plays.
- Constriction, the second, a press: the wrap ([mounts.md](../mounts.md#the-wrap-as-a-rider-move)); the body lets go of
  its trail while the coil holds its prey.

## Checks

- `seadramon_checks`:
  - `wild swims`: 30 s in a pool, never more than 450 degrees one way within 5 blocks of one spot in ten seconds, and
    no turn past its circle at its pace;
  - `wild round a corner`: through a channel three blocks wide and round its corner, no hit part's core in the rock;
  - `wild on land`: round to a spot behind it, no faster than its circle, its head never further off its body than its
    neck;
  - `ridden on land`: faster than a player walks (above 0.25 blocks a tick), past 0.4 sprinting;
  - `ridden looks back on land`: standing, its head turns no further than its neck and the body stays put; going on, it
    comes round to the view;
  - `wild climbs a ledge` and `ridden climbs a ledge`: four blocks up and onto the top, no hit part in the rock;
    `ridden over a thin wall`, `ridden over a thin wall aslant` and `wild over a thin wall`: over a wall two blocks high
    and one thick; `ridden climbs out of the water` and `wild climbs out of the water`: onto the pool's rock three blocks
    over the water, and `wild climbs out along the rock` from swimming along under it; `ridden at a high wall`: five blocks stop it at the foot; `ridden lowers itself`: down four blocks no
    faster than it climbs, unhurt;
  - `ridden carve`: the view swung a quarter round, it comes round within its rates, gathering into the turn;
  - `ice blast swimming`: it swims on (20 blocks) while it breathes, its head turned to prey off its line;
  - `frost on the sea`: the stream on the water ahead freezes floes, none where its body lies.
- `:fabric:nativeSeadramonTest`: every link keeps its length round a bend and diving, the body lies along its path within
  its sway, the sway keeps its place on the ground on land and runs back in the water, a dive draws the tail up after
  the head, the seat holds, and a wrap keeps the clips' own pose; a head turned round, a tight turn and a knock aside
  roll no link belly up, bend no joint past 45 degrees and fold nothing; up a ledge and down it no point goes into the
  rock; on a stair, down, off, across and up, it lies along the nosings with no point in a step and no fold.
- `sea_mount_checks`: cruise, surge, dive, the surface at its float line, the keys, a breach, surging along the surface,
  diving from it, steering, the roll, the haul-out, and walking on land and back in.
- `rider_checks`: Ice Blast and the wrap, afloat too; `gait_checks:seadramon`: its land pace on the walk's stride.
