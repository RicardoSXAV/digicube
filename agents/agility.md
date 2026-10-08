# Agility: leaps, crouches, rolls, ducks and blows that throw

Read this before giving a body its own leap, crouch or roll (`body.leap`, `body.step_height`, `body.crouch`, `Agility`),
authoring its `_crouch` twins, `jump_crouch` or `roll` clips, tuning how the AI ducks, rolls and leaps clear
(`duck_chance`, `leap_dodge`, `roll_dodge`) or keeps its band as a fighter (`footwork`, `stalk`, `spacing`), or making a
blow throw its victim (`launch`) or a shot weaken with its flight (`falloff`).
Gaits are in [locomotion.md](locomotion.md); a mount's own leap in [mounts.md](mounts.md#pace-sprint-and-leaps).

## The body's actions

Every move here is an action of the body itself, whatever drives it: the AI today, any controller later
(`DigimonEntity.leap()`, `leap(heading, reach)`, `crouch(boolean)`, `roll()`, `roll(heading)`; `isLow`, `isRolling`). They run where the
body moves (the server, for the AI) and reach clients as the vanilla pose and `DATA_ROLL`.

## Leaps

`body.leap {jump, carry}` gives a body without a mount a leap (a mount's own `jump` and `leap_carry` win). `jump` is the
upward speed of a standing leap (0.62 clears two blocks); at a run the leap goes on by 0.3 and up by 0.12 of the share of
the gait's run speed it ran at, and the air keeps `carry` of its ground speed a tick instead of vanilla's 0.91 (the takeoff
tick, which vanilla still counts as ground, keeps it too); no input steers it until it lands. A body that leaps lands 6
blocks of fall unhurt, its AI leaps at prey to pounce (`leapToPounce`), and `tickLeapPose` plays its `jump` clip. `leap(heading, reach)`
lands about `reach` blocks along `heading`. `body.step_height` is the step of a body without a mount (0: vanilla's).

## Crouch, tuck and roll

`body.crouch {height, eye_height, pace}` is a crouch. Held (`crouch(true)`) the box is lowered over the same feet (pose
`CROUCHING`; the eye to `eye_height`, by default the standing eye lowered with the box; hit parts squashed with it), the
crouched walk goes at `pace` of the pace, and in the air it is a tuck. Let go, the body stands as soon as its standing box
fits (`Agility.canStand`): under a low roof it stays down.

Held at a run (from `roll.from`, else the gait's `run_from`, else 0.2 blocks a tick), or on `roll()`, the body rolls:
`roll {ticks, height, eye_height, low_from, low_until, push, keep, speed}` (by default 16 ticks, tucked from 3 to 12, a 0.05
push, 0.97 kept, no speed of its own). No input moves it: it goes on with the run's momentum plus `push`, or at `speed` (the
pace its clip rolls at without slipping) if that is faster, keeping `keep` of it a tick. Only from `low_from` to
`low_until` is its box the tucked one (pose `SPIN_ATTACK`; outside it the standing box, or the crouch's while held); no roll
starts inside another; it ends after `ticks` (or off an edge) up into the run, or into the crouch while held.
A tuck that lands from a running leap rolls on. `roll(heading)` is a dodge roll from any pace, standing or crouched: the
body turns to the heading at once and keeps facing it until the roll ends (the clip rolls forward), at the roll's `speed`
or its pace along the heading plus `push`, whichever is faster; a roll with neither is refused. Leomon crouches to 2.0 and rolls 16 ticks at 0.44 blocks a tick, tucked to
1.8 on ticks 3 to 8.

## Clips

The client eases a crouch weight over about 3.5 ticks (`Agility.crouchWeight`). `NativeGroundModel.applyGround` gives that
share of every gait clip to its twin `<clip>_crouch` (`idle`, `skid`, a plain walk or pivot) and of every lattice to the
lattice `<blend>_crouch` (`walk`, `walk_back`, the strafes, the pivots, a run): the same amplitude and phase, its columns
planted on the same strides, its first point the empty `walk_zero`. A missing twin keeps the standing share. The `jump`
clip's twin `jump_crouch` is the tuck, on the jump's clock. A roll plays the one-shot `roll` clip, `ticks` long, on its own
clock from the roll's start (`DATA_ROLL`), taking over the gait (in over two ticks, out over three or four).

Clips mixed by weight reach lower than either: legs part way into the crouch, or a walk mixed with its pivot as the body
turns walking, sink the feet. With `paws.floor` (`ground_models.json`) the model raises the whole body by as much as the
paws' own faces sink under the ground, never lowering it, on the ground with no leap, roll, swim or attack clip on the
legs (`NativeGroundModel.footFloor`; Leomon's, held by `nativeLeomonTest`).

## The AI's answers

`DigimonAttackGoal.tickDodge` reads the species' tactics. `duck_chance` ducks under, or at a run rolls under, an inbound
shot or an authored, fist or horn blow whose lowest point would pass over the low box (the tucked one when it would roll,
else the crouch's) and strike the standing one. A duck holds until the threat is past (under an aimed burst, from when its
aim locks); a roll starts only when the whole threat falls inside its tucked window with a tick to spare at either end,
waiting for it to come closer if it must; a shot is seen for `reaction_ticks` first. `leap_dodge` leaps 3.5 blocks aside,
out of a ground wave's line, just after its aim locks. A failed roll of the dice, a blow no low box escapes or a threat no
roll fits falls to the sidestep (`dodge_chance`). Before it, `roll_dodge` rolls away from what no duck escapes (a blow's wind-up, a shot):
aside and back out of the reach or across the line, else straight back, else aside and past, where 4 blocks of open
ground allow (`roll(heading)`; a roll with a `speed` of its own). An Exposed body never ducks or rolls away.

Footwork (`footwork`, with a band `hold_min`..`hold_max` and `strafe`): while no move is ready the band is kept facing the
target (`FacingWalk`): backing steps out to it, side steps round it (the `walk_back` and strafe lattices), and anything
that can hurt the body is circled, not only a shooter. `stalk` is the share of those circling stretches (24 to 48 ticks
each) done crouched; anything else that takes the tick lets the crouch go. `spacing`: a chain of strikes runs three or four
strikes, then at that chance it is broken off; as a combo ends (broken off, or at that chance when it ran out) a body
within `hold_min` + 1 of its prey gets out of reach for 16 to 32 ticks, half the time with a hop back
(`leap(heading, 3.2)`, facing the prey) where it leaps, else on backing steps, keeps the band and then goes back in.
Skills counted: `duck`, `roll`, `leap_dodge`, `roll_dodge`, `stalk`, `spacing`, `hop_back`; a balance run's
`SKILLS` line fails a side whose tactics call for `roll_dodge`, `stalk` or `spacing` and never used it.

## Blows that throw, shots that weaken

`launch {speed, lift}` on a pounce (`pounce_attacks.json`; an `air` form may carry its own) or a kinetic entry
(`kinetic_attacks.json`) replaces the blow's knockback (`Launch`): the victim leaves along the blow (the dash, or away from
the shot's owner) at `speed` blocks a tick and up at `lift` (a faster rise of its own kept), less its knockback resistance,
synced at once (`hurtMarked`) so a player flies too. `falloff {near, far, power, knockback}` on a kinetic entry
(`KineticAttacks.Falloff`) weakens a shot by the distance from its muzzle to where it strikes: whole up to `near`, then in a
straight line to `power` of its damage and `knockback` of its push (a launch too) at `far`, held past it.

## Checks

`agility_checks[:<species>]` (`AgilityScenario`, Leomon by default; `DIGICUBE_AGILITY_ONLY=<prefix>` runs the checks named
so): a standing leap as high as vanilla's flight from `jump`, a running leap long and keeping its run, the crouched box
exact, standing up refused under a low roof, a shot over the crouch, the roll (its pace kept, tucked only in its window, no
second roll, running after it), a shot over the roll, a held crouch's roll, run-leap-tuck-roll, the AI ducking and rolling
under shots and leaping clear of a tectonic wave, rolling away from a punch (facing its heading, unhurt), its footwork
(backing off and circling facing its enemy, low while it stalks, up as soon as a move is let loose), and a shot's
falloff and launch near and far. `speciesTest` (`AgilityRegressionTest`) pins the data.
