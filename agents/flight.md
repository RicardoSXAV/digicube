# Flight: flying mounts and fighting on the wing

Read this before changing how a Digimon flies under its rider: the flight phases, agile flight (the view-led dive, the
boost, the slide, the barrel roll), the flight reserve and what flying hard and fighting on the wing cost it, attacks
cast on the wing, how a flyer is posed in the air (the model catalog's `flight`) and how flying feels (camera, sound,
the world stirred under it). Riding in general is in [mounts.md](mounts.md); Kabuterimon's numbers are in
[species/kabuterimon.md](species/kabuterimon.md). Check with `kabuterimon_checks` ([testing.md](testing.md#index-of-checks)).

## The flight lifecycle

`AerialRiding` owns the phases on the server (`FlightPhase`: grounded, takeoff, flying, approach, landing; synced). The
rider's client moves the body (`travel` asks `AerialRiding.velocity` for the step) and sends `AerialInput` with
`AerialInputPayload` (jump, a menu open, the sprint key, the dive key `C`). The jump key takes off from the ground; the
takeoff lifts from its sheet's `lift_tick` and becomes flight at `takeoff_ticks`. Near the ground a flyer settles in to land
by itself (`APPROACH`, the landing clip run on `DigimonEntity.landingProgress`): with the steady handling once it stops
climbing, with agile flight only once it is also slow over the ground (`SETTLE_PACE`, so a skim at speed never lands).
The dive key sinks it onto the ground. A spent reserve (`mustLand`) brings it down. Unridden, a flyer takes off on its
own (`DigimonFlightGoal`) to catch up with its tamer, to escape danger, or on a sortie to fight on the wing (below);
burning, an armed one with prey keeps fighting. An unridden flyer's takeoff and landing run on its sheet's clocks
(`locomotion.flight`: `lift_tick`, `takeoff_ticks`, `landing_ticks`, and `loop_ticks`, the wing loop's seam a touchdown
waits for, 1 for none; `DigimonFlight.Timing`, 13, 32, 32 and 40 without); a flying mount keeps its mount's.

## Fighting on the wing unridden

A move's `wing` key (`WingCasts`, on its entry in `kinetic_attacks.json` or `authored_attacks.json`) says where the AI
casts it: `"only"` only flying (`startAttack` refuses it on the ground, and the ground's choice and stances leave it out),
`true` on the ground and flying too; without it a move stays on the ground. A sheet with `locomotion.flight.sortie`
(`DigimonFlight.Sortie`) takes off for it: with prey within reach, a move cast only on the wing ready and `reserve` of its
reserve, `DigimonFlightGoal` flies a sortie. It flies (paths far off, straight over the last few blocks, facing the prey
there: `DigimonFlightMoveControl.face`) to a perch on the line out from the prey, `height` blocks over the prey's feet and
`range` blocks from it (turned round the prey when that place is shut in or out of sight), hovers there a few ticks and
casts what `DigimonEntity.chooseWingAttack` picks (a move cast only on the wing first, a `true` blow when the prey is in
its reach); a cast brakes it to a hover where it is. It comes down once nothing cast on the wing is ready within `linger`
ticks, the prey is gone or the reserve runs low; badly hurt, it escapes instead.

With `hold` it fights the whole fight on the wing: any prey within `range`'s far end and 12 blocks more draws it up
(something it fights with on the wing is enough), a flight under way or coming down turns into the sortie as prey turns
up, and it comes down only once it has had no prey for `linger` ticks. With `strike` (blocks over the prey's feet) it
swoops in for its `true` blow (`DigimonEntity.wingBlow`): the sortie plans each tick (`Plan`), up to the perch for a ready
shot (and to wait), in beside the prey at the `strike` height for a blow coming ready before the shot (`SWOOP_LEAD`), at
once for a blow that reaches from where it is. The swoop's place is its body's width from the prey (its open wings may
overlap it), where `canAttackFrom` says the blow reaches, along the way it came in (held through the swoop); a cast there
never lets it sink under the `strike` height. `DigimonEntity.wingReadyIn` gives the ticks until each kind comes ready.
Tentomon's is the example ([species/tentomon.md](species/tentomon.md#ai-the-sortie)).

## Agile flight

A mount whose sheet has `body.mount.flight.agility` (`AerialMount.Agility`) flies by `AerialHandling.agile`, one tick:

| Input | What it does |
|---|---|
| forward | the wings push where the rider looks, up and down included |
| back | backs off slowly; nothing held: a flare to a hover (faster the faster it went) |
| strafe keys | a slide aside at `strafe` of cruise; the body banks into it |
| sprint | the boost: the wings beat on full, `boost` times cruise |
| jump / dive key | climb / sink, with or without forward |
| jump twice | a barrel roll (below) |

Speed is energy: going down the path gathers `gravity` a tick (straight down), climbing spends it, and whatever is over the
wings' own speed bleeds off at `drag` of it a tick, more through hard turns (`TURN_LOSS`), up to `max_speed`. So a dive
pulled out level carries its speed on ahead for a few seconds, and a zoom climb trades it for height. The path turns toward
the push no faster than the sheet's `turn_degrees` (times `TURN_SCALE`) allows at cruise, down to `fast_turn` of it at top
speed: a fast body carves wide arcs. Over the ground a dive levels out instead of striking it (`AerialHandling.skim`: the
drop a tick held to `SKIM_DROP` of the height left over `SKIM`, the speed carried on along the ground), so a dive at the
ground becomes a skim; faster than `SKIM_PACE` it holds that height, slower it settles on down to land.
`AerialRiding.steer` turns the body to the view, and at speed along its path. What the server reads of how the body moved
(the costs' rates, the settling) is measured from where it stood at the last tick (`AerialRiding.moved`): the rider's
client moves it between ticks.

A double tap of the jump key on the wing is a barrel roll (the sea mounts' `DigimonEntity.barrelRoll`, `aloft`): a whole
turn round its length over `roll_ticks`, thrown `roll_speed` aside toward the strafe key held (else the way it turns), the
first tap's climb undone; reported by the rider's client as a swim roll is and drawn from `DATA_SWIM_ROLL`.

## The flight reserve

`FlightReserve` is the stamina (`locomotion.flight`: `capacity_ticks` of plain flight, `recharge_ticks` to refill on the
ground; with `endless` it stays full, owes no rest and never calls a landing: Tentomon). Its `costs` (`DigimonFlight.Costs`, steady without) are what flying hard and fighting on the wing take: a tick
of flight costs `boost` on the sprint key, `climb` rising, `glide` diving or gliding (read from how the body moved,
`AerialRiding.rate`); a barrel roll `roll` and a takeoff `takeoff` at once; every attack cast on the wing `attack` of the
whole reserve (`spendAttack` in `beginAttack`), so a flyer cannot pound something on the ground from the sky for long.
In a fight (the body or its rider struck, cast or was struck within `combat_ticks`: `DigimonEntity.inCombat`) the reserve
refills at `combat_recharge` of its rate (`flightRecharge`). The rider's gauge (`AerialMountClient`, on the experience
bar's row) shows the reserve, a notch for each cast it still affords, the stretch a cast or a roll just took fading off its
end, and a violet tint while the fight slows its refill (`DATA_FLIGHT_FIGHTING`).

## Attacks on the wing

An agile flyer casts on the wing what can be cast there (`wingCast`): a pounce and a shot. A pounce may have a wing form
(`air` in `pounce_attacks.json`: its own `motion`, `hit_tick`, `gather`, `burst`, `speed`, `exit`, `home`,
`contact_until`, `snap`, `knockback`; `PounceAttacks.Spec.forAir`): cast from the air it plays its own gather (no skip),
flies the body itself (`travel` leaves the flight's step out while the local pounce drives) and hands its exit on to the
flight. Its line is the one that brings the horn's tip, as it is through the strike and tipped along the line as drawn,
onto the crosshair's point (`PounceLines.wingLine`), and its bite tips the horn about the seat as the model is
(`PounceLines.tipped`), so the horn strikes where it is seen; the rider's own client turns the body onto the line through
the coil. Its start event's form 1 tells the clients; `attackAir` on the client makes the model play the clip's wing form
`<clip>_air` (a shot cast on the wing too). A shot on the wing turns the body to its aim and keeps flying; its upper body
pitches to the aim as standing (`NativeArmAim`).

## How a flyer is posed

`FlightLook` (client, every client) reads how a flyer carries itself from how it moves: cruise, dash, dive and flare
shares, its bank (turn and slide) and the pitch its path gives it, its wings' power and the wing clock. A catalog model
with `flight` in `ground_models.json` is posed off the ground by `FlightPose` (the renderer then never uses the shared
`NativeFlyingMountModel`): `pivot` (model px, the rider's seat), `landing_contact` (the land clip's touchdown tick),
`wings` (the wing parts' prefix) and `leans` (degrees forward of each posture, in the order below). The clips are fixed by
name: postures `fly_hover`, `fly`, `fly_dash`, `fly_dive`, `fly_brake`, `fly_roll` (on the body's clock, mixed by the
look's shares, the roll's tuck through a barrel roll), wing layers `wings_beat`, `wings_power`, `wings_fold` (on the wing
clock, over everything), `takeoff` and `land`. The postures' base lean is not in their clips: the body is turned about the
pivot by their mix of `leans`, with the path's pitch, the bank and a roll, so the rider stays put while the body swings
under them. The rider tips (`rider.lean`, [mounts.md](mounts.md#the-riders-pose)) with the path's pitch and the bank by
its shares and with a barrel roll whole, never with the postures' `leans` (`FlightPose.riderLean`): a dive's lean on a
steep path tips the body past upright, and the rider read off it came out upside down. An attack on the wing plays over the posture and owns the body while it plays (a pounce's line pitches it).
`attack_effects` with `follow_root` draws its effect in the body's root frame as drawn (`NativeEffectState.root`), so a horn
streak or a charging ball stays on the horn or the hands however the flyer turns. Optional `flight` keys time the wings
and the ground: `wing_in` [from, ticks] (the beat comes in on a takeoff, [1, 3] without), `wing_out` (ticks it fades
over on a landing, 6) and `ground_out` (ticks over which a takeoff takes the body over from the gait it was in, 0: at once).
An unridden flyer's approach plays the land clip on its height over the ground as a ridden one's does
(`DigimonEntity.landingProgress`).

## How flying feels

`FlightFeel` (client): every agile flyer and unridden burst flyer in hearing buzzes its wings (a loop following it, deeper for a big body, louder and
higher as the wings work, hushed in a dive and while a pounce plays, its wings swept back), and the local rider of an agile flyer gets a rush of wind with the speed, the view widening
with it, a fine shudder near top speed and its burst, the view tilting into the body's banks (`ROLL_SHARE`, at most
`ROLL_MOST`, `MixinCamera`) and swinging through a roll, and a whoosh as a roll begins. `FlightLook` also stirs the world
round an agile flyer on every client: dust or spray thrown out under a body flying low (the downwash), air streaming past it at speed, a ring
bursting round it as it breaks past `BOOM`, a ring of dust at a takeoff and a landing (heavier the faster it came down).

## The steady handling

A sheet without `agility` (Digmon) keeps the steady handling: `AerialHandling.target`, `step` and, with a `dive` block,
`flightStep`; its model is `NativeFlyingMountModel` with `<species>.presentation.json` ([mounts.md](mounts.md#flying-mounts)).
`AerialRidingRegressionTest` (`:common:locomotionTest`) pins both handlings, the skim and the costs.
