# Leomon (`leomon`)

Species notes for Leomon: his body (no seat: he fights on his own AI and on orders) and the sword he wears, his planted
walk that turns on its feet, the run lattice, the leap, the crouch, roll and tuck he dodges with, Lion Sword (a drawn
weapon's stance whose strikes are a slash combo and a stab), Beast King Fist (a gauge his sword's hits fill, then a punch
up close or a shot at range), his AI and orders, and the checks. Shared mechanics: compound moves in
[compound-attacks.md](../compound-attacks.md), the body's own leap, crouch and roll in [agility.md](../agility.md),
pounces and kinetic shots in [combat.md](../combat.md), gaits in [locomotion.md](../locomotion.md), a pounce's `tip` and
pivots in [greymon.md](greymon.md#great-antler).

## Body

- A Champion (`adult`, Vaccine), Elecmon's first route at level 20 (DarkTyrannomon the second). Not a mount: every one of
  his moves is an action of the entity any controller may call (the AI today).
- Native model at `model_scale` 0.32 (1 px = 0.02 blocks), about 2.9 blocks to the crown; hitbox 1.2 wide and 2.75 tall,
  eye 2.45. Model axes: +x his left, y down, front -z. Two-bone legs on long feet (`paws`: each foot's toe line 15.5 px
  down and 20 ahead in its frame, heard where it lands, `footfalls`), six tail links with three tufts, a mane of locks and
  a fang necklace; the tail and the hair are keyed in every clip with their own follow-through (no `tails` chain).
- `paws.floor`: clips mixed by weight (a walk and its pivot, a gait and its crouched twin part way) would sink the feet;
  the model raises the body instead ([agility.md](../agility.md#clips)).
- The sword lies across the back of his waist, its hilt past the left hip, so only the left hand reaches it: Lion Sword
  is drawn and wielded left-handed, and Beast King Fist comes from the right fist, free while the sword is out. The
  stowed hilt is its own part (`stowed_sword_hilt`, under `stowed_sword`); the sword in the hand is the left hand's
  membrane `ls_root` and all it carries. Both follow the stance (`stances.lion_sword` in `ground_models.json`: `drawn`
  `ls_root`, `stowed` `stowed_sword_hilt`), whatever the clips' visibility keys say.
- `look`: the head turns 40 degrees each way and 25 up and down, the neck carrying two fifths. `upper_body` and
  `aim_path` are the torso (`root`, `pelvis`, `torso`): the draw and the sheathe play there over the gait, a shot on the
  move too, and a pounce's aim turns it. Attack blends 2.5 ticks in, 4 out.
- A pounce tips him about half his height over the feet (`pounce_pivot` and `pounce_air_pivot` -44.75 px), the ground
  forms only their `tip` share (the stab 0.4, the punch 0.3), the torso turning the rest about the body's axis; the air
  forms tip whole.
- Voice (`voices.json`): the polar bear's at 0.85, its warning roar the cry a stab, a punch and a shot open with; a slash
  opens with the steel's clink.

## Gaits and the leap

Every gait clip is planted by leg IK on one phase (`cycle_ticks` 20):

- `walk` lattice (`walk_zero`, `walk_25` to `walk`): `stride` 5.875, 0.094 blocks a tick at full amplitude; `walk_back`
  (`back_stride` 4) and `strafe_left` / `strafe_right` (`side_stride` 2.75) for the AI's footwork.
- `pivot_left` / `pivot_right` lattices (`walk_zero`, `pivot_*_25` to `pivot_*_100`): `pivot_reach` 0.2955, `pivot_stride`
  0.9669, 60 degrees a cycle at full amplitude; `pivot_cadence` 1.6, about 4.8 degrees a tick standing. With `pivot_walk`
  the pivot steps on the walk's beats, so he turns as he walks on planted feet.
- `run` lattice (`run_55`, `run_75`, `run`) with `run_lattice`: `run_stride` 13.8125 times the share on the walk's phase
  (`run_cycle_ticks` 13: 0.34 blocks a tick at the full column). It takes over from 0.19 blocks a tick and hands back
  under 0.16; `max_playback_rate` 2.5.
- Sheet: `base_speed` 0.22 with `walk_speed` 1.0 and `run_speed` 1.82: the AI walks near 0.107 blocks a tick and runs
  near 0.353 (a panic, 0.21, runs the 0.6 column; `fight_speed` 1.6, near 0.27).
- `jump` (22 ticks on `tickLeapPose`'s clock): crouch, push-off, the knees drawn up, the landing absorbed. `body.leap`
  `jump` 0.62 and `carry` 0.94: a standing leap rises 2.52 blocks; at his run (0.35 blocks a tick) about 5.3 blocks long
  and 3.1 up, keeping most of the run's pace. `step_height` 1.0; a leap's fall lands unhurt.

## Crouch, roll and tuck

- `body.crouch`: 2.0 tall (eye 1.7), walking at 0.45 of the pace. Every gait clip has its `_crouch` twin (`idle_crouch`
  and the walk, back, strafe and pivot lattices); crouched, the head stays under the box (the mane's top locks reach
  past it).
- Crouching at a run he rolls: `roll` (16 ticks) dives forward onto the shoulder and comes up running, at 0.44 blocks a
  tick (no push, kept whole: the clip's own pace), tucked to 1.8 (eye 1.53) on ticks 3 to 8.
- In the air the crouch is a tuck: `jump_crouch` draws the knees to the chest and lands into the crouch.

## Lion Sword

A compound with a stance (`compound_attacks.json`): drawn from within 10 blocks (`lion_sword_draw`, 15 ticks, the sword
out of the sheath at tick 4: the iron equip sound), it holds 100 ticks, then sheathes (`lion_sword_sheathe`, 15 ticks,
home at 10: the chain equip sound); the move cools 80 ticks after the sheathe. Its forms, in order:

1. `lion_sword_stab` in the air (its air form, `lion_sword_stab_air`): a plunging thrust from a leap, tipped whole along
   the line (`air_pitch` -60 to 30), a 6-tick burst easing from 1.5 to 0.8 blocks a tick.
2. `lion_sword_slash` on the ground: authored sweeps (`BOX_SWEEP`) with root travel, the blade's own boxes and the
   `lion_sword_fx` arc traced from it, `steel` sparks, and three combo forms: `lion_sword_slash` (a forehand diagonal,
   15 ticks, hits 3.8 to 6, range 3), `lion_sword_slash_rising` (a backhand rising cut, 15 ticks, 3.2 to 5.2, range 2.9) and
   `lion_sword_slash_cleave` (the finisher, 22 ticks, two cuts at 3.4 and 11, range 3.6, stepping in 1.25 blocks). In
   the last 4 ticks of a strike the next one cuts in (`chain` 4); the travel stops at the victim (no knockback). The cuts
   are `aimed`: the torso leans each swing at its target, down at a short foe (an Agumon), level at a tall one.
3. `lion_sword_stab` on the ground from 1.5 blocks out: a pounce (18 ticks, range 7, a 3-tick gather, a 6-tick burst
   from 1.45 to 0.65 blocks a tick, contact 3 to 11, `tip` 0.4, knockback 0.45, `lion_sword_stab_impact_fx`); cast at 0.2
   blocks a tick or more it skips its gather and keeps its pace (`lion_sword_stab_run`: a leaping lunge out of a run,
   striking with its own contact points, `run_motion`). The blade's speed lines (`ls_streak_*`) show through the thrust.

The strikes end in the hold's guard (`lion_sword_hold`, and `lion_sword_hold_run` mixed in by the run share, on the left
arm). Another move's strike while the sword is out keeps the guard.

## Beast King Fist

A compound with a gauge of 100: each landed hit of a slash form pays 25, of the stab 35. Full, it is cast at once and
spent whole; there is no cooldown and nothing drains. Its forms:

1. `beast_king_fist_punch` within 4 blocks: a pounce from the right fist (18 ticks, a 4-tick burst from 1.0 to 0.45
   blocks a tick, contact 3 to 8, `tip` 0.3), with run (`_run`, its own `run_motion`) and air (`_air`) starts; its blow throws the victim
   (`launch` 1.6 along the blow and 0.55 up) and bursts `beast_king_fist_impact_fx`. The flaming lion's head (`bkf_root`,
   `bkf_flash_*`) forms round the fist through the strike.
2. `beast_king_fist_shot` otherwise: a kinetic shot cast on the move (26 ticks, released at 12 from the knuckles): the
   lion's head (`beast_king_fist_shot`, a flight of 22 ticks at 0.9 blocks a tick and a 10-tick impact) leaves the fist,
   its damage and push whole within 4 blocks of the muzzle and falling to 0.45 and 0.3 at 18 (`falloff`; `launch` 1.1
   and 0.35), `shot_style` fire.

An order on it is refused while it charges (`CHARGING n %` on the wheel and the card). The fist goes before the sword's
strikes when it is full, while the sword is out as well. The aura and the sword's speed lines are `glow_parts`: they
light themselves ([effects.md](../effects.md#bodies-of-fire)).

## The AI and orders

The AI draws as its prey comes within 10 blocks, closes in under the draw at a run, stabs from four to eight blocks while
running, slashes up close, leaps at prey on a ledge to stab from the top of the leap, and keeps after its prey through
the hold; a full gauge's punch comes first up close, its shot farther off. He fights as a fighter
([agility.md](../agility.md#the-ais-answers)): a combo runs three or four strikes and, at `spacing` 0.6, he breaks it off
and gets out of reach (a hop back half the time), circles in his band of 3.5 to 6.5 blocks facing his prey (`footwork`,
`strafe`; crouched, stalking, for `stalk` 0.45 of the stretches) and runs back in for a stab; with the sword sheathed and
cooling he keeps that band too. Tactics besides: `prefer_close`, `press_impaired`, `fight_speed` 1.6, `roll_dodge` 0.35
(he rolls away from blows and shots no duck escapes), `dodge_chance` 0.3, `duck_chance` 0.6 (he ducks under shots and
blows that pass over the crouch, or rolls under them at a run), `leap_dodge` (a leap clear of a ground wave). An order on
Lion Sword draws it and strikes the ordered target; on Beast King Fist it is refused until the gauge is full.

## Checks

- `leomon_checks` ([testing.md](../testing.md#index-of-checks)): the stance's clock (draw 15, hold 100, sheathe 15, the
  sword out from tick 4 to 125, the cooldown after it), the slash combo against short prey (an Agumon) and tall prey (a
  Garurumon): only while the sword holds, its forms in order, chained, +25 a hit; the stab from a run (no gather, +35) and
  from the air, a leap at prey on a ledge, the fist refused until full, the gauge kept through a save, the punch spending
  the gauge and throwing its victim about 9 blocks, the shot from 14 blocks, an order that draws and strikes. Verdict:
  `[leomon] RESULT 11 of 11 checks passed`.
- `agility_checks` (Leomon by default) and `gait_checks:leomon` (walk, panic and run PASS).
- `:fabric:nativeLeomonTest`: every gait planted (the lowest of each foot's heel, pad and claw tips still on the ground
  within 0.008 blocks a quarter tick: the walk's amplitudes, backwards, aside, the run's columns and turning on the spot,
  upright, crouched and part way into the crouch); the feet on the floor part way crouched and turning as he walks (within
  0.005 and 0.01 blocks; without the floor they would sink up to 0.09 and 0.12) and the authored gaits left as they are;
  the head under the crouched box, the body under the roll's box through its tucked ticks, the tuck more compact than the
  leap and landing crouched; the sword and the hilt by the stance and the draw and sheathe as long and swapping where
  `compound_attacks.json` says; the stab's blade on the server's contact segment level and aimed, on the ground, from a
  run and from the air, and the aimed slashes' blade where the server leans their volumes; the shot leaving from the
  drawn knuckles; the aura and the speed lines full-bright; no two faces sharing a plane in any clip's pose; the tail and
  the mane's locks out of the ground.
- `:fabric:nativeStanceTest` and `:fabric:nativeGlowPartsTest` (every model with a stance or glow parts),
  `:common:speciesTest` (`CompoundAttacksRegressionTest`: his two moves as data).
