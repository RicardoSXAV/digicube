# Shellmon (`shellmon`)

Species notes for Shellmon: its shell, body and seat, its crawl and heave on its hands, its swim, Hydro Pressure (a
water jet: a breath of puffs with water's own physics that shoves back what it strikes, puts fires out and wets what it
strikes) and Drill Shell (a spin in the shell: `spin_attacks.json`, `SpinAttacks`, `ShellSpin`, the rider aim `spin`).
Shared mechanics: rider attacks and tiles in [mounts.md](../mounts.md), breaths of puffs in
[effects.md](../effects.md#breaths-and-pounces) and [species/garurumon.md](garurumon.md#howling-blaster), gaits and
pivots in [locomotion.md](../locomotion.md).

## Body, shell and seat

- Greymon's size: native model at `model_scale` 0.43 (the spire's top 4.5 blocks up); hitbox 3.3 wide (narrower than
  the shell, so it gets about), 4.3 tall, eye 2.98, a hit part for the head and hands ahead (`[0, 0, 3.01, 2.41, 3.27]`).
  Parts: `root` / `shell` / `shell_spin`, which carries the shell's whorls and hooks and the soft body (`body`, `neck`,
  `head` with its `crown` of eleven tendrils and the `jaw`, the arms down to five digits a hand, each ending in a
  suction pad). `shell_spin` is what a spin turns; `shell` is what it wobbles.
- Membrane: `aperture_cavity`, the dark plate inside the aperture, hidden unless a clip shows it (only while the body is
  in its shell).
- The rider sits on the spire's flat top (`rider` on `[root, shell]` at `(0, 0, 9.8125)`, pose `(-0.55, 0.42, 0.5,
  2.5)`, lean `(0.5, 0.5)`; sheet seat `[0, 4.219, 0]`); `rider.hide` hides the spire's tip (`shell_apex_frame`,
  `shell_apex_tip_frame`) while ridden. The seat is not under `shell_spin`, so a spin turns the shell under the rider and
  only its wobble rocks them. `pitch_path` `[root, shell]` tips the shell (35 degrees ridden, 40 swimming, banks 14).
- `look` turns the head 30 degrees (the torso carries a third of it). Voice: the turtle's at 0.52, the sniffer's
  scenting as its battle cry (`voices.json`). The idle (120 ticks) breathes in the shell, looks about, drums its fingers
  and sways its tendrils.
- Its mouth rests open; `mouth` ([effects.md](../effects.md#mouths)) opens and shuts it in its own time (`shut` -0.56,
  `open` 0.45 of spells of 36 to 150 ticks, wide or ajar, moving over 8) and hides the cheeks' folds
  (`mouth_membrane_l` / `_r`) while it is shut.
- The arms are posed on every clip so that no joint snaps from one sample to the next (the native check below holds the
  swims to 22 degrees a quarter tick and every other clip to 50): the tendrils and the jaw lag the head as half damped
  springs of about five ticks.

## Crawl, heave and turning

All three are planted by arm IK on one shared phase (`cycle_ticks` 14): through each stance the hand's pads stand still
on the ground, the fingers fanned out in the air before the hand lands and held through the stance, and the heel rolls
up about the front pads before it leaves.

- `walk` lattice (`walk_2` to `walk`): one hand after the other (the right half a cycle after the left, duty .6), the
  torso leaning into each reach, dipping as the hand takes the weight and hauling out of the aperture, the shell dragged
  a beat later with its lip lifting (`stride` 5.25).
- `run`, the heave: both hands slam down together and the front heaves while the shell is yanked forward and slides on
  (duty .48, `run_stride` 7.8125). Its clip is a walk cycle long, so both play on the walk's phase clock (a run clip of
  any other length slides its hands); from 0.21 blocks a tick, back under 0.18; `max_playback_rate` 1.8. A swinging
  hand never comes nearer its shoulder than the folded arm reaches.
- `pivot_left` / `pivot_right` (lattices of `pivot_*_5` and `pivot_*_10`): it turns on its hands about the shell's
  axis, the leading hand first (`pivot_reach` 3.354, `pivot_stride` 4.29, `pivot_cadence` 1.8: 4.05 degrees a tick on
  the spot, after the view under a rider).
- Sheet: `base_speed` 0.071, a swimmer's (a body that can swim goes about 2.2 times its speed attribute on the ground,
  not the attribute squared: [locomotion.md](../locomotion.md#stride-and-planted-gaits)), `walk_speed` = `run_speed` =
  1: about 0.16 blocks a tick crawling, wild or ridden (a bigger body's slower cadence over a longer stride); `sprint` 2
  built over 24 ticks (the heave, about 0.31); `turn_rate` 8, `turn_to_travel`, `camera_distance` 10.5, no leap.
  `paws` sounds each hand's plant (`footfalls`).

## Swimming

`swim_speed` 0.34 (slower than Ikkakumon and Seadramon), `water_turn_rate` 6, `water_sprint` 1.35, `float_line` 0.45,
`surface_dive` 30. Under water (`swim`, 20 ticks; `swim_dash`, 14) the shell tips back about the rider's seat, so the
rider stays where they sit, and the body stretches out of the aperture while both hands sweep a breaststroke. Afloat
(`swim_surface`, 18; `swim_surface_dash`, 12) the shell rides like a buoy and the hands paddle in turn, each pull rocking
it about its waterline a beat late. `swim_idle` (40) treads water. `swim_wake` beats match the clips (stroke 20, paddle
18, surge paddle 12).

## Hydro Pressure

A breath of puffs (`breath_attacks.json`, `art` `water`, clip `hydro_pressure`, 120 ticks, the jet from tick 11 to 100).
The head bows until the crown faces ahead (torso, neck and head share the turn), the tendrils open, the hands grip (the
elbows bending on as the body bows) and the jet leaves the crown (8 px above its joint, the motion table's `mouth`);
each damage pulse kicks the head back, and after the jet the head comes up shaking the water off. The aim pitch turns
`neck` (the last part of `aim_path`), the crown with it, from 60 degrees up to 50 down (`pitch`); `twist` 70.

- Water's own physics: three puffs a tick at 1.8 blocks a tick, 0.22 blocks across at the mouth widening to 0.66, that
  fall (`rise` -0.045), keep 0.985 of their speed a tick (0.78 under water: `under_drag`), bounce off what they strike
  (0.12) and live 24 ticks; `range` 15. A liquid jet strikes along the whole way each puff went that tick
  (`FrostBreath.touches(Puff, AABB)`; its `bounds` take it in), so a body pressed against the mouth is struck too.
  Pulses of `power` 0.4 every 10 ticks.
- Push (`push`, `BreathAttacks.Push`, `DigimonEntity.pushWithJet`), all along the level way the touching water flows:
  the water's first blow throws a body back by `impact` 0.4 blocks a tick (with a splash), then while it stays in the jet
  it is driven to a slide building from 0.08 to 0.22 blocks a tick (`speed`; `1 - e^(-contact / build)` of the way,
  `build` 25 ticks), at most `accel` 0.12 faster a tick. All of it is whole within `near` 4 blocks of the mouth and gone
  at `far` 15, and shrinks with the water's own speed there. A bulky body moves less, by the cube root of its volume
  against 2 cubic blocks (a player 1.25, Golemon about .55, at most 1.25 and at least .45); knockback resistance takes
  up to 80 % off. It never lifts a body off the ground (a jet angled up into a tall one drives it back); one already off
  it goes with the water's rise (a little) or fall. The speed is set from what the body is already doing (a player's
  own movement, as their client last told it), so a fighter walking into the jet is held back rather than flung, and
  the build drains twice as fast out of the jet, forgotten after 40 ticks.
- `douse`: a burning body it strikes is put out (with the extinguish hiss), and fire blocks (with `mobGriefing`).
- `wet`: every client wets the block faces the jet strikes (`fabric/.../render/WetSurfaces`): 4 x 4 cells a face, the
  struck cell's neighbours within the water's spread, drying over about twelve seconds edges first, a puddle's sheen on
  floors, drips under soaked walls and ceilings, at most 1200 faces (the oldest go first).
- Drawn by `fabric/.../render/WaterJetRenderer` from `hydro_pressure_fx`: one translucent tube through the puffs, sorted
  back to front, its streaks flowing with the water, a flat sheet where it runs along a surface, a splash where it
  strikes; under water only a faint swirl and bubbles. Sounds: a high splash, the whirlpool's loop, a splash at the end.
- Rider: the quick button, held, on the move (`move`: only the upper body bows; the hands keep crawling); the rider aims
  it as a hose, its water falling on the way (their own client draws it from their mouse).
- AI (`DigimonEntity.breathAim`, liquid jets only): the aim is taken from the neck the head turns about, then twice more
  from where that aim puts the mouth, and raised by the water's fall over the flight (`jetElevation`: the low arc, or
  the arc that gets highest when out of reach). Cover in the way of the low arc: it lobs the water over on the high arc
  (`jetLob`) if that one is clear (`jetClear`); with neither it stops after 4 ticks, and any jet that stays off its prey
  15 ticks past the water's flight stops too. It starts no jet that cannot get there (`jetCanReach`), and plays on prey
  its push has sent up to 4 blocks past the range (`JET_HOLD`). Tactics `hold_range` 6 to 12 blocks.

## Drill Shell

A spin in the shell (`spin_attacks.json`, `SpinAttacks.Spec`, kind `SPIN`, run on the server by `ShellSpin`; from the
press until the body is out the server moves it, synced as `DATA_SPIN` = phase << 20 | ticks, with its speed and
spin-up).

- Withdraw (`withdraw` 14 ticks, the cast): the body slides back and down into the shell, the hands last, the cavity
  plate showing as it goes in; the shell drops into place with a thud. Past a third of the way in, the soft body squeezes
  to three quarters of its size (the `body` part's scale keys): the shell's cavity is a cone about 80 px across, and a
  whole body pushed in poked its back out through the shell's rear wall. Inside, every vertex of it keeps within the
  shell's inner wall, or behind the cavity plate in the opening.
- Charge: held, it spins in place, grit flying off the rim, spinning up from 0 to 1 over `charge` 40 ticks (the rider's
  tile fills with it) and turning onto the aim at once; let go, it sets off as soon as it is in and on the ground (thrown
  up, it lands first); held past `max_hold` 140 it goes by itself.
- In its shell (`shell_guard` 0.35): from half way through the withdrawal until half way out again a blow does a third of
  its damage (`DigimonEntity.shellGuard`, the shell ringing), and a blow's toss lifts the shell only by that share.
- Spin: it sets off at `speed` 0.6 to 1.45 blocks a tick by its spin-up and keeps `friction` 0.986 of it a tick. It is
  steered after the rider's view (the AI's prey, led by its motion) by `turn` 7 down to 1.6 degrees a tick as it goes
  faster, and its travel follows only `grip` 0.9 down to 0.35 of the gap, so a fast spin skids wide through a turn. A
  wall throws it back off its face keeping `bounce` 0.6 (a clang); every body it runs into (not its rider or an ally;
  the AI only its prey and monsters) is struck for `power_share` 0.45 to 1 of the move by its spin-up, times 0.35 plus
  0.65 of its share of top speed, thrown along its travel (`knockback` 0.7 to 1.9) and up (`toss` 0.18 to 0.45), and the
  shell glances off it keeping `rebound` 0.62 and steps out of the body if it ended up inside it; one body is struck at
  most every `hit_cooldown` 12 ticks. It spins on until it is under `stop_below` 0.2, for `max_ticks` 90, or into deep
  water. `cooldown` 90 ticks; `range` 15.
- Wind-down (`wind_down` 14) slows it; emerge (22 ticks): the hands grip the rim, the head peeks out, dizzy, and the
  body slides back out to its stance; then the reins go back.
- Facing: it never jumps while the body is in. Spinning, it swings round after the travel at `ShellSpin.FACING_TURN` 24
  degrees a tick (a wall's bounce turns the travel at once); winding down and coming out it holds still.
- AI: spins up for `ai_charge` 12 to 34 ticks by how far its prey is, out to its range, and leads it by its motion (at
  most half the way to it); once it has struck its prey it rolls on where the blow sends it.
- Model: the catalog's `spin` names the clips (`withdraw`, the held `hold`, `emerge`), the part the spin turns (`path`)
  and the part that wobbles (`tilt`). The client winds the shell's turn on (`DigimonEntity.tickSpinAngle`), its rate
  easing at most 5 degrees a tick toward what the phase asks (8 + 50 by the spin-up, 40 + 26 by the speed), so neither
  the launch nor a bounce jumps it. Winding down it plans a settle (`ShellSpin.settle`) onto a whole turn, the aperture
  ahead again, over 6 to the wind-down plus 3 ticks (a slow shell up to twice the wind-down): a cubic from the rate it
  had to nothing (`settleShare`), with no jump, reversal or more than a few per cent of speed-up, run on the client's own
  ticks into the emergence's first if it needs them. The wobble (`getSpinLean`, `getSpinWobble`) leans 0.6 + 1.4 by the
  spin-up, 1.5 + 4 by the speed and up to 7 degrees as it slows, easing 0.8 a tick, its way round winding on with the
  spin, gone as it stops. `NativeGroundModel.spinPose` blends the withdrawal over the gait and the emergence back into it
  over 3 ticks. The client's `SpinAudio` plays its whirr.
- Rider: the special button, held (aim `spin`).

## Checks

- `shellmon_checks` ([testing.md](../testing.md#index-of-checks)): the ridden crawl and heave paces, a turn on the spot
  after the view (no faster than the pivot), the swim; Hydro Pressure on a burning dummy (struck, put out, shoved back at
  once, still driven on a second in, pushed 3 to 11 blocks in all, never lifted) and on a Golemon pressed against its
  face (back, not up, under 8 blocks); Drill Shell tapped (withdrawn before it sets off weakly, out again, walking on)
  and spun up into a dummy (struck, thrown); a weak spin following a swung view far sooner than a full one; a spin off a
  wall; the wild jet's aim (Drill Shell on manual) from 5, 10 and 14 blocks and at a dummy 3 up a pillar: its water on
  the dummy through more than 70 % of the jet; wild, both moves at prey.
- `:fabric:nativeShellmonTest`: the rider's slots, the crawl's and the heave's pads planted (within three quarters of a
  model pixel), walk and run a walk cycle long, the seat on land, under water and afloat, the jet leaving the drawn
  crown at any aim through the whole jet, everything of the body inside the shell behind the cavity plate while it is
  in and every vertex of the soft body within the shell's inner wall, the shell spinning under the rider without turning
  them, settles from any turn at any rate, no arm snapping in any clip, the mouth's spells and its folds, the cavity
  plate and the spire's tip hidden and shown as they should be.
