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

## Gait and tail

The clips, with leg IK throughout:

- `walk`: 16 ticks, `stride` 5.551, duty 0.56. Each foot is flat through the stance and moves back at exactly
  the body's pace. The heel lands first and the heel rises about the toe tips at push-off. The pelvis sinks
  where a stance leg would straighten, and it bobs, sways, rolls and yaws with the stride. The torso
  counter-turns, the head holds level with a nod on each footfall, and the arms swing.
- `run`: `run_cycle_ticks` 12, `run_stride` 7.495, duty 0.40, with a flight phase. The body leans 16 degrees,
  the pelvis is lowest at mid-stance, and the arms are tucked. It plays for the ridden sprint and the AI's
  panic. The AI's own run modifier is its walk (`run_speed` 1), so combat pace is unchanged.
- The tail is carried out behind like a theropod's, rising gently, on a wave that runs down it. This applies
  in `idle`, `walk`, `run` and `fire_blast`. The old rest curl stood between the rider and the third-person
  camera.

Its feet stomp (`stomps` in the catalog, landing at phases 0 and 0.5) and the ridden body leans into turns
at a run (`bank`).

## Iron Tail

Iron Tail is a half turn from a crouch, the tail lagging behind the spin. The tail itself sweeps
low and flat, down to about 0.5 blocks, with the lag running down it so the tip whips round last. The old
sweep passed 1.1 to 3.9 blocks up, over anything smaller than a Greymon. The server's seven tail boxes
(`attack_volumes/authored.json`) and the motion's `horn_base` and `horn_tip` (the tail_02 and tail_04 pivots)
are measured from the installed clip, each box fixed in its segment's frame, so they change with the clip.

Under a rider the body spins under them and the rider turns with the seat (`riderYaw`).

## Fire Blast

A rider's breath follows the crosshair while it burns (see [mounts.md](../mounts.md#rider-attacks)). The flame
reaches its prey some ticks after `hit_tick`.

## Checks

- `:fabric:nativeDarkTyrannomonTest`:
  - the walk and run ankles stay planted;
  - the seat sits at the sheet's seat, with the feather hidden only while ridden;
  - the drawn tail matches Iron Tail's contact points, the tail sweeps under 1.1 blocks and the rider turns
    over 100 degrees with the spin.
- `gait_checks:darktyrannomon`: walk, panic and run all PASS.
- `rider_checks` covers both slots and the breath steering cases.
