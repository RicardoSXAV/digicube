# Monochromon (`monochromon`)

Species notes for Monochromon: its body and seat, its amble and gallop, Guardy Tusk (a held rush that ends in a horn
blow: `rush_attacks.json`, `BullRush`, the rider aim `rush`) and Volcano Strike (a kinetic shot that Burns). Shared
mechanics: rider attacks and tiles in [mounts.md](../mounts.md), travelling blows in
[authored-attacks.md](../authored-attacks.md#travelling-sweeps-and-leaps), kinetic shots in
[combat.md](../combat.md#kinetic-shots), gaits in [locomotion.md](../locomotion.md).

## Body and seat

- Native model at `model_scale` 0.4 (1 px = 0.025 blocks); hitbox 1.9 wide, 2.25 tall, eye 1.45, with hit parts for the
  head (ahead) and the tail (behind). The model's root stands between the feet. The trunk is one rigid part (`body`); the
  legs are three segments each (upper, lower, a broad foot) and every knee bends backwards.
- Membranes, hidden unless a clip shows them (the idle's first tick hides them for the mesh checks): `gt_*`, the horn's
  ivory pressure streaks, under `nasal_horn` in the horn's own frame; `vm_*`, the magma welling in the jaws, under `head`.
- The rider sits on the back behind the frill (`rider` on `body` at `(0, 0.25, 4.157)`, pose `(-0.9, 0.85, 1.05, 2)`,
  lean `(0.5, 0.7)`: the rider rocks with the trunk's roll; sheet seat `[0, 1.663, -0.1]`). `look` turns the head 30
  degrees each way. Voice: the hoglin's at 0.62 (`voices.json`).

## Gaits

All clips are planted by leg IK on one shared phase (`cycle_ticks` 8):

- `walk` lattice (`walk_2` to `walk`): a lateral-sequence walk whose full amplitude is an amble (fore delay .3 after its
  own side's hind, duty .58: four beats, never off the ground), the trunk rolling onto its standing side and heaving
  over each stance, the head nodding after each forefoot (`stride` 4, 0.2 blocks a tick). `walk_back`, `strafe_*` and
  `pivot_*` (`pivot_reach` 1.116, `pivot_stride` 1.375, `pivot_cadence` 1.6: about 5.6 degrees a tick turning on the spot).
- `run`: a transverse gallop (left hind, right hind, right fore, left fore; one gathered suspension), `run_stride` 6.5,
  taking over from 0.27 blocks a tick and handing back under 0.24; `max_playback_rate` 2.2 (the rush plays it near twice
  as fast).
- Sheet: `base_speed` 0.305 with `walk_speed` = `run_speed` = 1 (the AI walks, follows and runs at the amble, 0.2
  blocks a tick; the ridden cruise is the same), `sprint` 1.9 built over 30 ticks (a gallop near 0.38), `turn_rate` 10,
  `turn_to_travel`, `camera_distance` 7, no leap; tactics `fight_speed` 1.3 (it gallops in a fight).
- `stomps` in the catalog: the hinds' beats on the walk, all four on the gallop, a ravager's step at 0.42, a roar at
  0.6 as a ridden one breaks into its gallop.

## Guardy Tusk

A held rush (`rush_attacks.json`, `RushAttacks`, run on the server by `BullRush`) that ends in the authored blow of the
same id (`authored_attacks.json` `guardy_tusk`: a travelling `BOX_SWEEP`, 26 ticks, impact 7, hit window 5.5-9.5,
horn and muzzle volumes, `guardy_tusk_fx` bursting at the horn's tip on contact, `"particles": "ram"`). From the press
until the blow is over the server moves the body (`DATA_RUSH`, `serverOwnsBody`).

- Brace (`build` 18 ticks under a rider, `ai_build` 12): a body slower than `standing_below` (0.12) stops within `brake`
  ticks, turns slowly onto the aim (`brace_turn` 3) and plays `guardy_tusk_brace` (the head goes down, the right forefoot
  paws the ground: clods and its scrape between ticks 6.8 and 9.6, a snort, a coil on the haunches); a running body lowers
  its head on the run instead. The rider's attack tile fills round the clock over the build (`DigimonEntity.rushBuild`).
- Rush: it bursts off (a roar, the ground thrown back), gathers to `pace` 0.6 over `ramp` 8 ticks and turns after the
  rider's view and keys (or the AI's prey, led by its motion) by at most `turn` 4.5 degrees a tick, the ground torn up
  behind it; the gallop plays under `guardy_tusk_rush` (the head held low, the horn levelled, its streaks shown), the
  tile burns with a pulsing amber rim and the rider's view shudders and widens.
- Blow: when the rider lets go, when an enemy is within 2.1 blocks of the body's front (and 28 degrees of its heading),
  against a wall, or after `max_ticks` 100. Released from the brace it strikes at `power`/`knockback`/`toss` 0.75 / 0.6 /
  0.2; after a full rush 1.25 / 1.4 / 0.5 (blocks a tick of upward toss). The blow drives the body on along its travel
  (about 3 blocks), stopping at its victim's box; then the reins go back.
- AI: the move reaches as its blow's volumes do, and from there out to `reach` 14 blocks (near its own level, a clear
  run) it braces and rushes instead (`countSkill("rush")`).
- Model: the catalog's `rush` names the brace clip, the charge loop and the part it moves (`head`); `NativeGroundModel`
  blends the brace over the gait, adds the loop over the head, and keeps both through the blow's blend-in.

## Volcano Strike

A kinetic shot (`kinetic_attacks.json`, 24 ticks, release 11, range 14): the head rears back as magma wells in the jaws
(`vm_*`), drives forward and spits the ball, which leaves where the drawn magma sits. `volcano_strike_projectile` (its
`effect` clip the flight, `impact` the crust bursting open) flies straight at 1.0 blocks a tick, drawn at 0.75 of its
art. A shot's `burn` (100 ticks) sets the victim alight, a Burn; `"shot_style": "magma"` adds the cough of fire at the
jaws, the trail of flame, smoke, ash and dripping lava, and the eruption where it bursts. The rider fires it on the move
(`move`: only the head's subtree plays it).

## Checks

- `monochromon_checks` ([testing.md](../testing.md)): ridden cruise (an amble) and gallop; Guardy Tusk held along the view
  (braced still, rushed at its pace, the blow carrying it on, the reins back), held into a dummy (struck by itself,
  thrown and tossed) and let go in the brace; Volcano Strike burning a dummy; wild, a rush from afar that strikes, and the
  ball at prey on a pillar.
- `:fabric:nativeMonochromonTest`: the amble's and the gallop's feet planted, the seat, the brace and the charge lowering
  the head (streaks shown only then), the drawn horn on the server's horn markers and inside its struck volume, the ball
  leaving from the drawn jaws, every membrane hidden at rest.
- `gait_checks:monochromon`: walk, panic and run all PASS.
