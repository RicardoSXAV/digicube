# DarkTyrannomon (`darktyrannomon`)

Species notes for DarkTyrannomon: its seat, its dinosaur gait and tail, Iron Tail's low sweep and Fire Blast
from the saddle. Shared mechanics: rider attacks, the rider's pose and footfalls in [mounts.md](../mounts.md),
the catalog keys in [animation.md](../animation.md#generic-catalog-keys).

## Mount

The rider sits on the upper back behind the neck, straddling it with the legs forward over the shoulder pads
(`rider` on `torso` at `(0, 1.087, 0.922)`, pose `(-1.1, 0.8, 1.1, 1)`; sheet seat `[0, 3.24, -0.75]`). The
neck feather `crest_03` stands where the seat is and is hidden while ridden (`rider.hide`). Walking the seat
bobs about 0.2 blocks; running it leans up to 0.6 blocks forward of the sheet seat, so the first-person eye
stays at the resting seat.

Sheet: `turn_rate` 10, `sprint` 1.8 built over 40 ticks (walk 0.2255 blocks a tick, ridden or not, sprint
0.406), `turn_to_travel` (a biped walks along its own length and has no side-step clips),
`camera_distance` 7.5, no leap. Slot 0 is Iron Tail (`sweep`, cone 40), slot 1 Fire Blast (`shot`, cone 10).
Voice: the ravager's at 0.72 (`voices.json`).

Standing, it turns on its feet ([locomotion.md](../locomotion.md#stride-and-planted-gaits)): `pivot_reach` 1.016 (the
ankles' radius round the body's upright axis), `pivot_stride` 1.636 (that radius times the 60 degrees a cycle the pivot
clips turn), `pivot_cadence` 1.5, so a ridden body standing comes round at most 5.6 degrees a tick after the view, eased
in and out (`SteadyBodyControl`), and gets its full 10 back as it walks off; wild, the same rate turns it after its
head and onto its paths.

## Gait and tail

The clips, with leg IK throughout:

- `walk`: 16 ticks, `stride` 5.551, duty 0.56. Each foot is flat through the stance and moves back at exactly
  the body's pace. The heel lands first and the heel rises about the toe tips at push-off. The pelvis sinks
  where a stance leg would straighten, and it bobs, sways, rolls and yaws with the stride. The torso
  counter-turns, the head holds level with a nod on each footfall, and the arms swing.
- `run`: `run_cycle_ticks` 12, `run_stride` 7.495, duty 0.40, with a flight phase. The body leans 16 degrees,
  the pelvis is lowest at mid-stance, and the arms are tucked. It plays for the ridden sprint and the AI's
  panic. The AI's own run modifier is its walk (`run_speed` 1), so combat pace is unchanged.
- `pivot_left` / `pivot_right`: plain 16-tick loops on the walk's phase, 60 degrees a cycle at full amplitude. The
  left foot lands at phase 0 and the right at 0.5 (the walk's beats, so the stomps play), each stands 0.6 of the
  cycle flat and turned with the ground as the body turns over it, then shuffles back round that arc, lifted 0.14
  blocks, to land ahead of the turn. The pelvis sways onto the standing leg and twists with each step, the head
  leads the turn, the tail trails to its outside.
- The tail is carried out behind like a theropod's, rising gently, on a wave that runs down it. This applies
  in `idle`, `walk`, `run`, the pivots and `fire_blast`. The old rest curl stood between the rider and the
  third-person camera. Each tail segment is posed by the least rotation from its rest orientation relative to its
  parent, so no joint rolls.

Its feet stomp (`stomps` in the catalog, landing at phases 0 and 0.5) and the ridden body leans into turns
at a run (`bank`).

## Iron Tail

Iron Tail is a half turn from a crouch, the tail lagging behind the spin. The tail itself sweeps
low and flat, down to about 0.5 blocks, with the lag running down it so the tip whips round last. The old
sweep passed 1.1 to 3.9 blocks up, over anything smaller than a Greymon. The server's seven tail boxes
(`attack_volumes/authored.json`) and the motion's `horn_base` and `horn_tip` (the tail_02 and tail_04 pivots)
are measured from the installed clip, each box fixed in its segment's frame, so they change with the clip.

The clip's keys are Euler deltas the game adds to each part's rest angles and mixes by weight, so a tail segment's
deltas must stay on the branch nearest zero: no joint bends more than 55 degrees off the one before it in the whip,
clear of the ZYX lock (a y rotation of 90 degrees), where the decomposition once jumped to the far branch and the
attack's blend-out mixed the tail's end keys toward the idle's through a ninety-degree swing (a twitch as the move
ended). `nativeDarkTyrannomonTest` pins it: no tail joint's turn from rest changes more than 10 degrees in a quarter
tick, or grows through the blend-out.

Under a rider the body spins under them and the rider turns with the seat (`riderYaw`).

## Fire Blast

A rider's breath follows the crosshair while it burns (see [mounts.md](../mounts.md#rider-attacks)). The flame
reaches its prey some ticks after `hit_tick`.

## Checks

- `:fabric:nativeDarkTyrannomonTest`:
  - the walk and run ankles stay planted;
  - turning on the spot either way, each standing ankle stays put in the world as the body turns over it at the
    sheet's pivot stride, and a turn keeps the pivot's cadence while it gathers;
  - the seat sits at the sheet's seat, with the feather hidden only while ridden;
  - the drawn tail matches Iron Tail's contact points, the tail sweeps under 1.1 blocks, the rider turns
    over 100 degrees with the spin, and no tail joint twitches through the move or its blend-out.
- `darktyrannomon_checks` ([testing.md](../testing.md)): ridden, the view swung 150 degrees standing turns the body no
  faster than its pivot, eased in and out, and all the way; at a walk it comes round faster, within the sheet's rate;
  and the same standing the other way.
- `gait_checks:darktyrannomon`: walk, panic and run all PASS.
- `rider_checks` covers both slots and the breath steering cases.
