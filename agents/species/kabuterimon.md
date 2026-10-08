# Kabuterimon

Kabuterimon (`kabuterimon`, champion, vaccine): a four-armed, four-winged horned beetle and the agile flying mount.
Read this before changing his model, clips, flight numbers, Beet Horn or Mega Blaster. How flight works for any flyer is
in [flight.md](../flight.md).

## Body

Model scale 0.45, box 2.2 x 3.9 blocks. The rest pose is his standing stance: the four arms at the ready (wrists before
the chest and the waist), the claws a little curled, the four wings folded back along his body, the knees a little bent;
every clip is a delta from it. The rider sits on the helmet's crown behind its hook (`rider` in `ground_models.json`, the
head's frame, leaning with the body at `lean` 0.75 / 0.9), which is also the point the body turns about in the air
(`flight.pivot`). `look` turns his head (a share of it his neck) unridden; ridden, the head carries the rider and holds
still.

## Ground

An idle of 120 ticks (breathing, a weight shift from foot to foot, looks to each side, claws flexing, the folded wings
easing open and a short shiver of all four), a planted walk lattice (`walk`: amplitude columns at .25, .5, .75 and 1 of a 4.5
model-unit stride over 20 ticks, the pelvis dropping after each landing and swaying over the standing foot, the upper
arms swinging against the legs), and `pivot_left`/`pivot_right` (the feet stepping round his middle one at a time, 50
degrees a cycle). Ridden on the ground he walks along his own length (`turn_to_travel`) and his feet are heard where
they land (`paws`, `footfalls`).

## Flight

On the wing his wings stand in an X seen from behind: the fore pair out about level, the hind pair down and out under
it. Every wing layer keeps that spread and only shivers about it, the hind pair against the fore: `wings_beat` swings the
fore pair between about 2 and 24 degrees over level and the hind pair between 40 and 56 under, `wings_power` (a takeoff,
a climb, a boost) 1.4 times as wide, `wings_glide` barely, `wings_fold` lays them back for the dive.

Agile (`agility`): cruise 0.62 blocks a tick, boost 1.6x, slide 0.6 of cruise, dive gravity 0.055 a tick to 1.75, drag
0.045, the fast turn 0.38 of the slow one, a barrel roll of 12 ticks thrown 0.75 aside, the camera 9 blocks back. Takeoff
14 ticks (lifting at 4), landing 13 from the touchdown. Reserve: 2400 ticks of plain flight (two minutes), a refill from
empty in 30 s on the ground; boost 2.4x, climb 1.6x, glide 0.35x; a roll 60 ticks, a takeoff 30; a cast on the wing 32 %
of the reserve (about three drain it to the landing reserve); in a fight the refill runs at 35 % for 7 s after the last blow.

## Beet Horn

A pounce (`pounce_attacks.json`) with a wing form. On the ground (20 ticks): five ticks rearing back (weight back, the
horn raised high, wings flaring), a four-tick lunge of about 1.3 blocks, the lead foot striding out and the rear foot
dragged through, the body dropping into a deep bow (the torso about 50 degrees forward, the head down) that levels the
horn at a Rookie's chest (its tip about 1 block up at tick 7.5), then a toss heaving it up past 4.5 blocks; from tick 6 to
11 the tip sweeps from the ground to over a tall foe's head with a wide contact (0.45 blocks): easy to land on anything.
The line stays near level on the ground (`ground_pitch` -5 to 15: the clip does the reaching down), and the rider tips
with the torso, not the bowed head (`pitch_path` to the thorax).

On the wing (`air`, motion `beet_horn_air`): three ticks coiling back (the wings raised a little and forward) while the
body turns onto its line, then a ram of six ticks at 1.7 to 0.95 blocks a tick (about eight blocks) along the line that brings the horn's tip
onto the crosshair, the body flat as a lance behind the horn and the wings swept back, only the horn's tip striking (0.2
blocks) from tick 4 to 11 (the ram and the start of its glide); its exit (0.75) carries on as flight. Tipped about the
seat the tip never comes lower than about 2.2 blocks over the body's feet, so the ram reaches flyers and foes taller than
about 2 blocks; a small foe on the ground is the gore's or Mega Blaster's. Power 1.15, knockback 0.9 (1.3 from the air),
cooldown 2.5 s. It starts with the lunge's whoosh alone (`shake: false`: no body shake, which on him sounded like a wing
beat), and his wings' buzz hushes through it. The horn's streaks
(`kabuterimon_fx`, `follow_root`) ride the horn's tip; the contact bursts `beet_horn_impact_fx` where it lands; a white
streak and sparks trail the horn's tip (`DigimonEntity.hornTrail`).

## Mega Blaster

A kinetic shot (`kinetic_attacks.json`) of two stacked uses (`charges` 2: a shot's charges refill each from its own cast,
`AttackCharges`): the four hands gather a ball before the chest, the wings flaring on the ground (on the wing they keep
beating), and thrust it away at tick 13 of 26. Through the charge (`kabuterimon_fx`'s `mb_socket`) the ball boils, its
three corona sheets jump to a new turn and size every half tick and blink, and a bolt (`mb_arc_0..3`, the discharge
art's sheets crossed) leaps from each palm into it, more of them as it fills. The ball (`mega_blaster_shot`: its `effect`
loop the same boiling and jumping coronas; its burst, `impact` of 10 ticks, a swell and pop, a flash, the burst sheets
crackling out and the charge chips flying) flies straight at 0.75 blocks a tick for 32 ticks, about 24 blocks: quick
enough to read as a blast, slow beside a cannon, so it can be dodged and its sparks reach what it passes. Each client
draws its lightning about it (`ShockBall`): a halo of jagged magenta lightning round its outline that always faces the
camera, arcs crawling over it, bolts leaping off it, one earthing in the ground under it every few ticks when it flies
within 4.2 blocks of it, feelers reaching for every body within its shock's reach (never its caster's side), a wake along
the stretch it just flew and a ring where it left the hands; where it bursts, a ring sweeping out, a ring of lightning
running over the ground under it, a star of long bolts that earth where they reach the ground, and crackling about the
spot. It shocks what it passes (`proximity`, `KineticAttacks.Proximity`: a body within 3 blocks of the
ball, measured to its box, is struck once at its nearest for 0.6 of the shot near to 0.15 at the radius, a bolt drawn
from the ball, `KineticProjectileEntity.passing`), strikes a body it meets for the whole shot, and bursts on a block, a
body or the end of its flight, shocking the rest within the radius (`burst`). The bolts to the bodies it shocks are
`ArcRenderer`'s in the ball's colours (a pale pink core, magenta, mint forks), and every body it shocks, a direct hit's
too, crackles with short arcs for 12 ticks (`ShockedBodies`). Vanilla particles are a few sparks at the release, the burst
and each shock.
Power 1.3, range 16, cooldown 4 s a use, aimed up to 85 degrees (a shot from the sky at the ground).

## AI

On the ground he pounces up close and otherwise shoots (`choosePounce` without a breath: the pounce in its range, else
the first other move that is ready and has its shot); he does not fight on the wing yet. Tactics: dodge 0.35, reaction 4,
lead 6, fight speed 1.6.

## Sounds

The game's own for now: wings (`entity.bee.loop`, pitched down by size), wind (`item.elytra.flying`), a roll's whoosh
(`item.trident.riptide_1`), the burst past top speed (a large firework far off), takeoff (`entity.ender_dragon.flap`),
landing (`entity.ravager.step`). Mega Blaster (`ShotStyle.ELECTRIC`, `ShotAudio`): the charge
`block.respawn_anchor.charge` with a crackle (`entity.firework_rocket.twinkle`), the release `item.trident.thunder` high
with a sizzle (`block.fire.extinguish`), a hum following the ball (`block.beacon.ambient` high), a crackle where it earths,
each shock a sizzle and crackle, the burst the trident's thunder, sizzle and crackle. (`entity.lightning_bolt.impact` is
vanilla's plain explosion: not used.) Real recordings are to be auditioned first.

## Checks

`kabuterimon_checks` (`KabuterimonScenario`, a fake rider flying him through the real ridden code): the takeoff and its
cost, cruise, the boost and its rate, a dive and the speed it carries level, a dive at the ground that skims at its
height, the hover, a barrel roll thrown aside and its cost, the slide, landing on the dive key, Beet Horn's gore at a
dummy a Rookie's height and its ram from the wing at a dummy on a pillar (and that cast's cost), Mega Blaster's shock as it
passes a dummy that sidesteps it (as the ball comes within 4.5 blocks) against its whole shot on one that stands, two stacked shots, three casts draining the
reserve and the slow refill in the fight. `rider_checks` casts both moves on the ground. `:fabric:nativeKabuterimonTest`:
the idle's feet planted, no sole under the ground walking or pivoting (a column within a third of a pixel, a mix within
0.75), every flight phase finite, the seat still through every posture mix and every pitch, bank and roll, both feet on
the ground through the landing, the drawn horn tip on Beet Horn's motion at every contact tick on the ground and on the
wing, and on the wing, tipped along a ram's line, where the server's bite puts it. `AerialRidingRegressionTest` pins
agile flight's physics, the skim and the costs.
