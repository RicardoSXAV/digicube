# Locomotion: gaits, turning, the head, moving on its own, grip, stepping and facing

Read this before setting a species' speeds or strides, adding a gait or travel clip, or changing how a body
steps, changes gait, turns, looks, strolls, follows its tamer, grips the ground, hovers, faces or paths while it moves.
Measure any pace change with `gait_checks` ([testing.md](testing.md#gait-checks)) and any change to how a body moves on
its own AI with `locomotion_checks` ([testing.md](testing.md#locomotion-checks)). Swimming and everything under a rider
is in [mounts.md](mounts.md); a body's own leaps, crouches and rolls in [agility.md](agility.md); a serpent's body in
[species/seadramon.md](species/seadramon.md#serpent-bodies).

## Stride and planted gaits

A ground gait only looks planted when the clip's stride matches the ground covered: the phase advances by
travel / stride (`DigimonGait`), so a stride far shorter than the species' real pace hits `max_playback_rate`
and the feet slide (Golemon walked at the player's .216 blocks/tick on a .043 stride). Measure the real pace with
`gait_checks`, not from the sheet. On ordinary ground (friction 0.6) a body whose input is fed once goes speed / (1 -
0.6 x 0.91), 2.2 times its speed attribute: a swimmer's move control does that, and so does a swimmer's ridden pace.
Ikkakumon at 0.1 x 1.3 went 0.286 blocks a tick on a 0.4-block stride capped at one cycle in 32 ticks.

A gait with `side_stride` / `back_stride` is directional: the entity splits its movement in the body's frame
into shares (`DigimonGait.directions`) and `NativeGroundModel` mixes the lattices by them. Change strides in
the script and the species sheet together.

The client plays the gait from where it sees the body go (`GaitDriver`, fed each tick by `DigimonEntity` and by
`locomotion_checks` alike), never from its velocity: a body pushing at a wall or nudged by a neighbour has a velocity
and goes nowhere, and its legs once stepped on the spot. The move is smoothed over a couple of ticks (`SMOOTH`); under
`STILL` (12 %) of the walk's pace the body stands; its way plays the walk alone within `AHEAD` (30) degrees of straight
ahead and the walk back within as much of its tail, the sidesteps only beyond (a 14-degree bend once mixed a third of a
sidestep in, the side stride being half the walk's); and a turn slower than `TURN_STILL` (0.6 degrees a tick) is taken up
in the hips, not stepped round. A gait with `cadence_rise` (Agumon 0.4) shares a change of pace between its cadence
(as the pace to that power) and its stride, under the walk's full pace and across a run lattice's columns, as an animal
does: a slow walk takes long slow steps and a jog runs on a slower beat than a sprint (a fixed beat played a jog as
quick short steps, a march).

A species with a `run` clip blends it over `walk` by `groundRunAmount`, both on the shared gait phase
(`run_cycle_ticks` / `run_stride` on the sheet). The phase counts the walk's `cycle_ticks` a stride, run or walk, so a
run clip is that long whatever `run_cycle_ticks` says (one of any other length slides its feet). A run lattice is a blend named `run` whose full-amplitude clip is
itself called `run`; without that clip no run plays. A gait with no back clip plays its walk backwards when the
body backs up (planted too). The run is along the body (`DigimonGait.drive`): all of it while the travel is within
25 degrees of ahead, none from 60, and it takes the whole stride, so a directional galloper drifting through a bend
never mixes its sidesteps in. With `run_from` (and `run_until`, blocks a tick) the gait changes all at once, as an
animal's does: the run takes over from that pace and hands back under the lower one, crossing over in four ticks,
because half a trot over half a gallop leaves paws that never touch the ground. Without them the run blends in over
the paces between the walk's authored speed and the run's. Any pace turns the legs over; only a move past
`GAIT_SNAP` (3 blocks, a teleport) skips the phase (a sprint on ice past a block a tick once froze the gallop).

The model mixes gait clips (a lattice's columns, the walk into the run, the pivot into the walk) by adding their weighted
angles, so a part's angles must lie on one branch in every clip it is mixed from: within half a turn of its rest angles,
never wrapped a whole turn round a loop or written as the other Euler triple of the same turn (the mix of two equal turns
written differently is a turn nobody authored). A leg solved by IK bends its knee toward a pole, so its hip never twists
free ([species/greymon.md](species/greymon.md#gaits)).

A gait with `pivot_reach` (blocks from the body's centre to its farthest standing paws: a quadruped's fore toe line, a
biped's ankles), `pivot_stride` (what that line sweeps round the centre a cycle at full amplitude) and `pivot_left` /
`pivot_right` in the model (lattice blends on a directional gait, or plain looping clips on a plain walk: DarkTyrannomon)
steps round on the spot as the body turns: the turn is paid on the phase at that stride (`DigimonGait.pivotTravel`)
and its share goes to `pivotTurn`, which plays the pivot in the walk's place. The pivot goes round the body's centre one
paw at a time, each standing paw still on the ground and turned with it (it lands turned into the turn and leaves turned
out of it): the forepaw on the side the shoulders swing to, the other forepaw closing up to it (never across), then the
hind paws the same way, the spine bent into the turn, head first; a biped shuffles, one foot after the other. Only a body
all but standing pivots: from half the walk's full pace it turns along its stride (the pivot and the walk land on
different beats), and a run turns in an arc. A gait with `pivot_walk` steps its pivot on the walk's own beats, so it turns
on planted feet as it walks at any pace; one with `run_lattice` plants each column of its run on that column's own
stride (both Greymon's: [species/greymon.md](species/greymon.md#gaits)).

## Steady turning

A body with a pivot turns as an animal does (`SteadyBodyControl`): standing, no faster than its pivot steps round at
`pivot_cadence` times the walk's cadence (`pivotTurnRate`, about 5 degrees a tick for Garurumon), gathering into the turn
over eight ticks and braking out of it (`SteadyBodyControl.ease`; as long as the gait takes to reach its full amplitude,
so the pivot keeps one cadence all through instead of fluttering its paws while its stride grows). Standing, it turns
only to face what it looks at (its look's target, or with none where its head points), once that lies beyond its neck's
reach (`body.head_turn`, vanilla's 75 by default) for `PATIENCE` (12) ticks or past `URGENT` (110) degrees at once; it
then turns all the way to face it, and `SETTLE` (20) ticks pass before another turn. A glance within the reach moves the
head alone, a push never turns it (vanilla's body came round after every glance), and a turn cut short brakes to a stop.
In a fight it squares up to its enemy past `SQUARE_UP` (4 degrees) at once: an aim, a breath and a charge go where the
body faces (Shellmon's jet at prey up a pillar fell from 91 to 59 % on target without it).
The server brings the body round, its facing is that body, and clients draw the facing they are sent (vanilla's clients
worked the body out from the head, and it snapped). Its controls are set on its first tick as well as on a change of
species: a body born the default species (Agumon) never saw its species change, kept vanilla's move control, and walked
crabwise through every bend. On a
path the move control turns it the same way from where its body faces (`DigimonMoveControl.steerSteadily`; vanilla
turned it up to 90 degrees a tick), up to 1.5 times as briskly at a hurried pace (a run, a chase); it keeps its whole
pace within 30 degrees of its way, slows past that and steps round on the spot from 90 degrees off, so it never walks
sideways. Setting off outside a fight it gathers its pace over `START_TICKS` (6) from `START_KEEP` (30 %), as its stride
grows. Ridden, it turns no faster than its pivot standing, eases the same way (in half the time from its walk's full pace)
and gets its full turn rate back as it walks off. `garurumon_checks` pins the rate and the easing ridden, after the head, and onto a path behind it.

Gaits generated by leg IK: Agumon, Golemon, Centarumon, Digmon, Crabmon, Meramon, Mojyamon, DarkTyrannomon, Ikkakumon and
Betamon (rigid limbs rowing about their lateral point: [species/betamon.md](species/betamon.md#walk-and-idle)).

## The head

A Digimon with authored controls (`DigimonLookControl`) turns its head as a head turns, gathering into the turn over
`GATHER` (5) ticks and braking out of it at the rate the look asks (10 degrees a tick at its tamer, 7 for a glance), its
pitch eased too. It looks no further off its body than its neck reaches (`body.head_turn`; past it the body comes round),
walking within 60 % of that, and a look that ends holds 12 ticks before the head comes back to the front. In a fight,
and for a swimmer (its pitch is its body's), it keeps vanilla's look. The client of a body that steps round draws its
head after the one it is sent the same way (`ClientHead`): vanilla's three-tick lerp of 256ths of a turn stopped it dead.
`GlanceGoal` looks round idly, mostly within the neck's reach, one standing glance in seven past it (the body follows).

## Moving on its own: strolls, following and making way

A stroll (`DigimonStrollGoal`) goes at the sheet's `locomotion.stroll_pace` (blocks a tick; Agumon 0.06) through
`DigimonEntity.modifierForPace` (ground pace is about 2.2 x speed squared); without one, at vanilla's modifier 1. A wild
body strolls where it likes; a partner potters about its tamer once they have stood still 60 ticks, 2 to 5.5 blocks
from them (inside its follow start distance), keeping 1.4 blocks off them on the way.

`FollowOwnerGoal` sets off once the tamer is past `follow_start_distance` for a spot at 0.8 x `follow_stop_distance` from
them on its own side, at the tamer's pace plus 0.05 blocks a tick per block past its stop distance, from its walk's pace
up to its follow pace (`walk_speed`, `run_speed` with the tamer sprinting). It lays a new path only once the tamer is
1.25 blocks from the last one's spot, or after 20 ticks, walks on beside a tamer who walks on, stops by a standing one,
and arrived at their feet steps back facing them. A tamer may walk right up to it (it never scurries off); one standing
within 1.3 blocks for 15 ticks gets room (`GiveRoomGoal`: a step back facing them, or a turn and a walk, to 2.4 blocks),
and their brushing against it nudges it at a quarter of vanilla's push (`DigimonEntity.push`; a full one slid it aside).

On a ground path (`DigimonGroundNavigation`) a walker steers at the farthest of the next four nodes on its floor it can
walk to straight (box free, whole width on walkable ground), so a diagonal is one straight walk; with no enemy it slows
over its last strides to 30 % of its pace (`ARRIVE_TICKS` of it, 0.6 to 2.2 blocks).

`locomotion_checks` pins it on Agumon, wild and as a partner of a stand-in tamer who stands, walks, sprints, comes up
beside it and circles: no sidesteps but a shove's, no stepping on the spot, no small turns on the spot, a steady head, a
walking stroll, the partner within 7 blocks on average.

## Swimming on a path

Unridden in the water (`DigimonMoveControl`) a swimmer turns toward its next node at most `SWIM_TURN` (7) degrees a tick
(a serpent at its circle), gathering into the turn. It keeps its whole pace within 30 degrees of the node and none past
90 but a glide (`GLIDE`), and never more than the circle its turn can draw through the node allows (`MEET` of that):
carried on at speed it circled a node inside its turn for ever. A node more above or below it than aside is swum up or
down to on the heading it has (pitched up to `SWIM_PITCH`, 80 degrees, `PITCH_TURN` a tick): chasing the way to a node
overhead spun the body round on the spot. A node up on a bank (out of the water) it still turns to face (`BANK_ASIDE`),
to press into the bank and climb out: swum up along the bank, it waited under it. A node counts as reached within
`SWIM_REACH` (1.5) blocks across (`DigimonAmphibiousNavigation`). `seadramon_checks` pins it
([seadramon.md](species/seadramon.md#checks)).

## Sure footing

`locomotion.ice_grip` (Garurumon 0.4) makes a body sure-footed (`DigimonEntity.sureFooting`): on ice its paws keep
that share of firm ground's grip (`footing`: blue ice a little less, slime in between). Vanilla's friction modifier,
set per tick for the block under it, makes the block take 0.454 times the grip of its speed a tick, and its push is the
grip's share of firm ground's, so on ice it gathers pace more slowly, runs no faster than on stone and skids a few
blocks when it stops (vanilla's ice ran it on for eight). On firm ground its momentum also turns with its drive, up to
30 degrees a tick, where vanilla lets friction bend the old line (a rider swinging the view at a gallop turned the body
well ahead of its travel, and it slid through the bend sideways); on ice friction alone bends it, a drift. A drive
turned further than 100 degrees, a strike that moves the body itself and a body with no drive keep their line.

On the client the gait follows the legs, not the slide (`IceSlip`): the pace comes round to the legs' drive by the
share lost a tick, so two moves give the drive, and the gait plays it (ahead of a slow start, gone as it lets go,
along the body through a drift). The pace the legs do not carry is the skid (`getSkid`), which plays the model's
`skid` clip over stance and gait (braced, every paw flat on the ground) and scrapes (`PawFalls`). `garurumon_checks`
pins it: the ice pace and its slower gathering, a stop that skids 2.5 to 6.5 blocks on braced legs, a bend on stone
within 12 degrees of the heading, and the same bend on ice a drift that comes round, the legs along the heading.

## Stepping down

A walker steps down as it steps up (`DigimonEntity.stepDown`): off a ledge no higher than `maxUpStep` with
nothing lifting it, it is set on the ground below in the same tick, so a hillside never freezes the gait
mid-drop or plays a landing per block; `rider_checks` ends with a Centarumon driven down six one-block steps
at a canter, which must spend no tick in the air (24 without the step down).

## Hovering

A body can **hover** on fins instead of walking (Bukamon, `pukamon`): `locomotion.hover.fall_speed`
(`DigimonLocomotion.hovers`). It is still a ground mob with ground navigation and the whole combat planner;
the model is lifted above the entity's feet (root `24 - lift` px, lift 1.5 native units = 0.3 blocks) and the
hitbox covers the gap. Off a ledge it glides down at `fall_speed` (`glide`), it takes no fall damage and makes
no footsteps, and its travel clip (`fly`, shipped as `walk` on the ground gait) keeps playing off the ground.
This is not burst flight (`locomotion.flight`, Tentomon): it never climbs.

## Travel facing

A crab walks forward, backward and side-on (Crabmon, `ganimon`, revision 2 of 26 September 2026). Which way
the body looks while it walks a path is `locomotion.travel_facing` (`TravelFacing`,
`DigimonMoveControl.faceWhileTravelling`): within `face_target_within` of its enemy it faces the enemy and its
steps go forward, back or aside at it; otherwise at a navigation modifier of `side_on_from` or more (its run,
a panic, the attack goal's pursuit) it turns side-on to its travel, whichever flank is nearer, and scuttles;
slower it walks facing its travel. Vanilla's input is split in the body's frame, so the pace is unchanged and
the directional gait plays the lattices; the control clears the sideways input itself (vanilla never does).

## Wide bodies and pathing

Keep a big body's width off exactly 2.0: vanilla steers to path node + `(int)(width + 1) / 2`, which jumps
half a block there, and the facing walk then circles a spot it never reaches (Mojyamon is 1.95).
