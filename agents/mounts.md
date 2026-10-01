# Mounts: riding and mounted combat

Read this before touching anything a rider does: rider attacks and their tiles, controls, getting on, the
rider's pose, water and sea mounts, the wrap as a rider move, mount pace, sprinting and leaps, four-legged
mounts, charges and drawn shots, and flying mounts. Check with `rider_checks` and `sea_mount_checks`
([testing.md](testing.md#index-of-checks)).

## Rider attacks

Mounted combat is opt-in per species: `body.mount.rider_attacks` lists the attacks in slot order with `aim`
(`sweep`/`line`/`shot`/`stream`/`grab`/`charge`/`whip`/`pounce`/`rush`: [monochromon](species/monochromon.md)), `input` (`tap`/`hold`), soft-target `cone`/`reach`
and `move` (`RiderAttack`; Golemon, Garurumon, Greymon, Ikkakumon, Digmon, Seadramon, Centarumon, Mojyamon,
Gesomon, DarkTyrannomon). A rider has no target: `startRiderAttack` shares `beginAttack` with the AI, aims at the soft target
or at `riderAim` (the ray from the rider's eye, which is the crosshair's ray in third person too), and commits
every yaw through `DATA_ATTACK_YAW` because the rider's client owns the facing. A move with forms casts the form
whose `key` is the movement key held ([authored-attacks.md](authored-attacks.md#forms)). Check with
`DIGICUBE_SCENARIO=rider_checks` (`[rider] RESULT n of n casts landed`). The rider keeps their hands and casts
the mount's target-free attacks (`riderAttacks()`, quickest first) with Q/E inside the command wheel
(`PartyActionPayload.RIDER_ATTACK` -> `startRiderAttack`). Vanilla skips a ridden mob's server AI step, so
`tick()` drives a rider's attack through `tickAttackTimeline`; never put attack timing back into
`customServerAiStep` alone.

A rider's `BOX_BURST` that strikes several times, such as Fire Blast, keeps following the crosshair while it
burns, from its `hit_tick` to the motion's `activeUntil`. It turns at `RIDER_BREATH_TURN` (4.5 degrees a tick)
and pitches at `RIDER_BREATH_PITCH` (3), and the soft target is picked again from the view every tick. The
hits read the caster's current yaw and aim pitch, so the fire sweeps across what the rider looks at.
`rider_checks` casts such a burst on a left dummy and then either sweeps the view onto a right one (both must
burn) or holds it (only the left one burns).

A `pounce` ([combat.md](combat.md#attacks-as-data)) is flown by the rider's client from the press
(`predictRiderPounce`): along the crosshair, bent toward the outlined enemy in its `cone`, from the ground or mid-leap;
the server's `PounceSession` only bites along the path the body takes. A breath of puffs aims its newest puffs from the
rider's own mouse at once (`tickClientBreath`); one on the run (`move`) may start mid-leap, its puffs carried by the
flight.

## Attack tiles

`RiderAttacks` replaces vanilla's mount hearts with the attack tiles
(`textures/gui/attack/<attack>[_off].png`),
each in its own 20-unit frame one unit above the experience bar, the 7x9 mouse glyphs together on their left
in tile order (layout approved 20 September 2026).

`RiderAttacks` owns the mount-hearts slot for every Digimon mount, flying ones included (`AerialMountClient`
draws only the flight reserve, on the experience bar's row). A stream's tile shows its tank; an emptied one,
which fires again only full, comes back round the clock with its seconds (`riderRefillTicks`).

## Controls

`RiderControls` is the direct input: with a free hand the mouse casts (attack hook +
`MixinMinecraft.startUseItem`), R/G always, aimed attacks are held and released. The rider's client owns a
ridden mount's position and facing, so turn rate, the swing's lunge and the strike's facing are played in
`tickRidden`; the server owns targets, hits and the input buffer. The middle mouse button is the wheel's:
`PartyClient.movePickBlock` makes B the default of vanilla's pick block (`KeyMappingAccessor`) and rebinds it
once while it still shares the wheel's key.

## Getting on

Getting on is an order, not a click: `mobInteract` no longer rides (the use button is the special attack, so
the click that mounted also cast). `PartyClient.aim` picks the own party Digimon under the crosshair (24
blocks, hit parts included), it is outlined in blue (`AIM_OUTLINE`, set in `MixinEntityRenderer`), the wheel
opens on it, and Ride (`PartyActionPayload.RIDE` -> `PartyManager.ride` -> `DigimonEntity.giveRide`, within
`RIDE_REACH` = 6) takes Cancel target's place while it is not fighting. `RiderControls` ignores a button that
was already down when the rider took the reins or closed a screen.

## The rider's pose

The rider's leg pose is catalog data (`ground_models.json` `rider.pose` = leg pitch, splay, roll; Golemon
sits, no pose = straight legs); only `MixinHumanoidModel` reads it, so the first-person camera is untouched.
`rider.pose` may carry a fourth number, hips: px each leg is set further out. `rider.hide` names parts drawn
only unridden, such as a feather standing where the seat is.

The rider turns with the seat. `NativeGroundModel.riderYaw` measures how far the animated seat part has
turned from the mount's heading, `MixinEntityRenderer` carries it (`RiderVisuals.YAW`), and
`MixinLivingEntityRenderer` adds it to the rider's body rotation. The head keeps looking where the rider looks,
within vanilla's 85 degrees. So a strike that spins the body, such as DarkTyrannomon's Iron Tail, carries its
rider round instead of leaving the rider facing the old heading with their legs through the neck.

A rider can also tip with the body: `rider.lean` (pitch and roll shares, 0 to 1) makes `NativeGroundModel.riderLean`
read how far the pitch part (the `pitch_path`, else the seat part) is turned nose down and right side down;
`MixinEntityRenderer` carries it (`RiderVisuals.LEAN`) and `MixinLivingEntityRenderer.setupRotations` turns the rider
about the seat, 0.6 over the feet. Ikkakumon's rider leans into its dives and banks and goes round with a barrel roll.
The first-person camera is the sheet's seat and never tips. The sheet's seat need not be where the rider is drawn:
Ikkakumon's is 0.3 blocks to the right of the drawn seat, so its horn stands beside the crosshair.

## Water

Water: a land Digimon floats at 55 % of its height (`getFluidJumpThreshold`), keeps every attack that does not
need the ground (`wadingAttack`; the spike wave does), paddles over prey it has no path to
(`DigimonAttackGoal`), and under a rider floats by itself and rises with the jump key (`tickRidden`).

## Sea mounts

A sea mount (`body.mount.water_turn_rate` > 0, Seadramon, Gesomon, Ikkakumon) gets the full water controls
(`seaMount()`): forward follows the view to 70 degrees, jump rises and the dive key (C,
`DigimonEntity.localRiderDives`, client only) sinks, the surface holds the body unless it surges
(`water_sprint`), a surge through the surface is a breach, the rider's air refills. The surface is a float
line (`body.mount.float_line` of the height under water, 90 % by default, `floatLine`; Ikkakumon floats at 58 %, head
and mane out and its rider dry): above it the body settles back and its climb is damped
(`surfaceAndHaul`; before, a swimmer, which has no gravity, coasted up on its momentum and stood on the
water). Pushing into a bank or a quay no higher than `HAUL_ABOVE` (1.6) over the water, or a ledge under it,
it hauls itself up at `HAUL_PACE` until its feet clear the top and walks on (`haulsOut`; a floating body is
never on the ground, so vanilla's step never helped it out); a serpent climbs out as it climbs on land, up any face
over its floating feet, a shelf's lip too ([locomotion.md](locomotion.md#serpents)). A surge streams bubbles and sets off with a
squirt on every client (`seaWake`, read from the body's travel).

A body that holds the surface (`body.mount.surface_dive`, degrees; Ikkakumon 30) stays afloat within `SURFACE_BAND`
(20 % of its height) under its float line (`holdsSurface`): it swims level at its full push whatever the view, and
`surfaceAndHaul` eases it onto the line (`FLOAT_PULL`, at most `FLOAT_RATE` a tick), so a rider's glance down never
dips it and a climb from below levels off instead of bobbing out. Looking down past `surface_dive` with a key held, or
the dive key, dives; a surge looking up past it breaches. Without it, the view steers at the surface too.

A sea mount that turns to travel (`turn_to_travel`; Ikkakumon, Seadramon, which carves its turns as a serpent,
[locomotion.md](locomotion.md#serpents)) steers in water as on land: the strafe keys turn it
toward where they point from the view (`travelTurn`) instead of strafing, and it swims on through the turn at
`SWIM_THE_TURN` of its push at least (`swimPush`; S back-paddles at `BACK_SWIM`). A roller (`body.mount.water_roll`,
a speed in blocks a tick; Ikkakumon, Seadramon) rolls on a double tap of the jump key in the water (`barrelRoll`, taps at most
`ROLL_TAP` 7 ticks apart): a whole barrel roll round its length (11 ticks, at most every 16), thrown on along its way
(`ROLL_AHEAD` of the speed) and aside toward the side it is steering to (`steerSide`: the view and keys past 6
degrees off its heading); steering straight on it rolls the way it last turned, with no sideways throw. The rider's
client reports the roll (`PartyActionPayload.SWIM_ROLL`), and the others follow `DATA_SWIM_ROLL`. Out of the water on
a breach (`leapingFromWater`: 3 ticks clear, not a skim) the body keeps its swimming pose and seat (`waterPose`), and
the model plays `swim_leap`. Past its cruise it plays `swim_dash` over the stroke, and afloat at its float line the
surface clips (`getSwimDash`, `getSwimLeap`, `getSwimSurface`; [animation.md](animation.md#generic-catalog-keys)).

A jet swimmer (`locomotion.jet`, `JetSwim`; Gesomon) moves in pulses instead: `jetStroke` squeezes out the
thrust over the first `squeeze` share of each pulse and glides on the rest (the thrust averages 1, so the mean
pace is the swim speed; cruise pulses 18 ticks, surge 12, a slow breathing pulse with no thrust at rest),
setting off squeezes at once, the swim clip is one pulse played on that clock, and every thrusting pulse puffs
bubbles out behind with a soft squirt (`jetWake`) and surges the rider's view (`RiderControls.cameraKick`).
The side that moves the body owns the clock (`isLocalInstanceAuthoritative`: the rider's client reports each
pulse with `PartyActionPayload.JET_PULSE`, the server counts its own) and every other client follows
`DATA_JET_PULSE`.

A swimmer shares its sight: under water the rider's eyes adjust at a spectator's rate
(`RiderControls.seaSight`, `LocalPlayerAccessor`). A rider's shot under water leaves along the line to the
aim, not held to `max_pitch` (`KineticSession.waterLine`).

Check with `DIGICUBE_SCENARIO=sea_mount_checks` (`[sea] RESULT n of n`, `DIGICUBE_SEA_TRACE=true` traces every
check): the server drives each sea mount with a fake rider's keys and view through the real ridden code
(`DigimonEntity.driveScenarioRider` makes the server simulate the ride, which vanilla leaves to the rider's
client) in a pool with a quay: cruise, surge, dive, the surface, the rise and dive keys, a breach, the
haul-out, walking on land and back in, and the rider's attacks afloat; a jet swimmer's pace is measured over
whole pulses, and `jet pulses` checks that it goes in them (at least 1.6 x between its slowest and fastest
tick). The surface check holds each body to its own float line; the surface holder's, steering and roll checks are
in [ikkakumon.md](species/ikkakumon.md#checks).

## The wrap as a rider move

A wrap is a rider move (`RiderAttack.Aim.GRAB`): the server picks the prey near the crosshair the strike would
take (`grabPick`, synced as `DATA_GRAB_PREY`), the client outlines it in magenta and lights the tile (dull = a
press does nothing), and one press strikes at it and wraps ([combat.md](combat.md#wraps)); through the move
`getControllingPassenger` is null (`serverOwnsBody`), so the server owns the body. `rider()` is the saddle.

## Pace, sprint and leaps

Rule for mounts: the same pace ridden as alone, so new sheets leave `body.mount.speed` out (`ridePace`); a
species that must travel slowly but fight at pace gets `tactics.fight_speed` (Seadramon: `base_speed` 0.07,
fight 3.09; slowing its fights cost 30 points against Golemon). The sprint key gathers its multiplier over
most of a second (`gallopMomentum`); `body.mount.jump` leaps on a tap.

`body.mount.sprint_build` is how many ticks the sprint key takes to reach its full multiplier, on land and for a
sea mount's surge alike (`buildGallop`; Centarumon 45 to 2.8x; the high jump, `LEAP_TOP`, comes late in it).

The leap (`body.mount.jump`) is thrown forward and a little higher by the pace (`LEAP_PUSH`, `LEAP_LIFT`), a
leaper lands 6 blocks of fall free (`LEAP_SAFE_FALL`), `body.mount.leap_carry` is the share of the run the air keeps a
tick of the leap instead of vanilla's 0.91, and its pose is the `jump` clip, driven on every client
by `DigimonEntity.tickLeapPose`
from the body's own motion: takeoff and landing on time, the flight by vertical speed.

## Four-legged mounts

A four-legged body sets `body.mount.turn_to_travel`: the strafe keys turn it into the way it goes and S reins
it back, so it always walks along its own length (a horse has no sidestep clip; sliding sideways is what that
looks like), and `camera_distance` brings the camera in. Its sound: `ground_gait.footfalls` silences vanilla's
step per block, and `HoofBeats` plays one clop where a hoof lands in the clips (`hoof_beats` in
`ground_models.json`, touchdown and lift-off phases per lattice column, measured from the clips offline), a
pair landing within two ticks as one beat; from half the run on, one vanilla gallop sample a stride, started
on the first hoof after the flight and pitched so its four hits (4.6 ticks) span the stride's own.

## Charges and drawn shots

Centarumon (design §7): a `charge` is a server-owned burst (`DATA_RIDER_CHARGE`, `tickJetCharge`) that homes
on the soft target picked at the press, shoves the rest aside unhurt (only the buck strikes) and ends in the
attack's kinetic kick committed at the prey (`KineticSession` buck constructor); a `shot` with `input: hold`
is drawn like a bow (`rider_draw_tick` in `kinetic_attacks.json` holds the clip, `DATA_RIDER_DRAW` the charge)
and with `move` loosed on the run: `upper_body` in `ground_models.json` names the part whose subtree alone
plays the attack over the gait, turned to the rider's aim up to `KineticSession.MAX_TWIST`, and `twistShift`
moves the muzzle to match. `charge_flames` names the clip and parts a charge burns.

The charge fires in the air too (no gather, a small thrust, its fall held while it burns), goes where the
movement keys point (`riderKeysTurn`, the server reads `ServerPlayer.getLastClientInput`), picks up an enemy
crossing its path, and bucks from `BUCK_REACH` out: the buck is the kick on a faster clock (`rider_kick` in
`kinetic_attacks.json`, clip `jet_dash_buck`: `jet_dash_kick` retimed to that clock, so a new clock needs the
clip retimed too) that skids and turns onto
its prey until the hooves swing (`KineticSession.homing`). Use `standing()`, not `onGround()`, inside a
server-owned move: a level `move` clears `onGround`. A charge that reaches its prey in the air drops and bucks
once down.

## Flying mounts

Digmon (24 September 2026) rides on `NativeFlyingMountModel`, whose `<species>.presentation.json` holds the
seat: `rider_point` (model units in the frame of `rider_path`'s last part; Digmon sits on the shell's flat
top), and the rider's legs in radians as `MixinHumanoidModel` sets them (`rider_leg_pitch`, `rider_leg_splay`,
optional `rider_leg_roll`, `rider_leg_hips`); keep `body.mount.seat` at the rest pose's visual seat or the
first-person eye sits apart from the body. The model plays a rider's casts (the seat follows the brace). An
approach draws its landing clip on `DigimonEntity.landingProgress` (rendered height against the height the
approach began at, so it starts from the flight pose and meets the ground with the feet), and `AerialRiding`
settles in at `.035 + .07 x height` a tick on the exact `groundDistance`. Kabuterimon's flight controls are
described in `README.md`.
