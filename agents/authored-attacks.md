# Authored attacks: volumes, bursts, leaps and volleys

Read this before adding or changing an authored move (`authored_attacks.json` with its volumes, effects and
motion): bursts summoned at the target, travelling sweeps, leaps, contact parts, strike particle styles,
stacked uses and volleys. Rules shared by every attack are in [combat.md](combat.md); clip naming and the asset
gates are in [animation.md](animation.md).

## Bursts summoned at the target

An authored burst can be **summoned at the target** instead of drawn from its caster: `anchor_lock_tick` in
`authored_attacks.json` (Gotsumon's Comet Hammer). Its effect and volumes are exported relative to the landing
point. `DigimonEntity.strikeAnchor()` (synced block + fraction) follows the floor under the target, led by its
pace up to 1.5 blocks, until the lock tick and then stays: walking out from under the warning is the dodge.

`AuthoredVolumeAttack` holds the rules (`landing`: floor within 3 blocks below the target, a swimmer is struck
where it floats; `canReach`: sight of the target and the last three blocks of the way in open, along
`anchorApproach`, the place the leading volume first hangs; `visible`: nothing falls through a roof; damage
needs a clear line from the stone to the victim) and the renderer offsets the effect by the same point, so
keep `culling_margin` at the attack's range. `contact_parts` lists effect cells a miss never shows
(`DigimonAnimationEvents.CONTACT`), and a mirrored cast plays the effect's `effect_mirrored` clip when it has
one. Victims are thrown away from the landing point with a small pop upward.

A fist may carry margins around the drawn hand (Gotsumon: 0.35 ahead over its smear);
`:fabric:nativeGotsumonTest` pins those, the drawn stone and the fists against the server's cuboids.

## Travelling sweeps and leaps

A sweep may **travel** and a burst may **jump** (Dinohyumon). `root_travel: true` on a `BOX_SWEEP` makes the
server drive the caster along the motion's `travel` curve like a horn charge (the exporter strips that
translation from the root track and exports effects and volumes relative to the moving root), the facing keeps
turning after the victim until the last hit window, and the client chases at the lunge rate through the
travel. `leap: {launch, land, lead, apex}` on a `BOX_BURST`: at `launch` the server plans a parabola
(`AuthoredVolumeAttack.arc`) from the feet to the floor `lead` blocks short of where the target will be (a
victim already inside that reach is struck from a short hop back) and flies it as a closed loop each tick
(`tickLeap`, blocks still stop the body, gravity is cancelled by leaving exactly one tick of it on the
velocity); the facing is settled at launch; `canReach` needs the landing floor within five blocks of the
caster's own and the whole arc free of blocks. Hit windows must start after the landing.

A leap's `edge: true` measures its lead from the target's side, so a short reach lands as close to Golemon as
to a player; every leap now aims at where `TargetMotion` puts the victim at the landing and settles the facing
there.

## Strike particle styles

`"particles"` (`StrikeParticles`, `stone`) adds server-sent trail, release, contact and landing particles and
sounds to any authored volume; the first volume of the move is the one that trails. A style also voices the
start of its move in place of the shared growl (`windUp`), so a small friendly Digimon does not sound like
Golemon.

`"particles": "steel"` is the blade style. The other styles belong to one species each: `drill` in
[species/digmon.md](species/digmon.md) and `pincer` in [species/ganimon.md](species/ganimon.md).

## Stacked uses

An authored attack may stack uses (`charges` in `authored_attacks.json`, Gold Rush 3, `AttackCharges`): each
cast starts its own refill of the cooldown and the body's cooldown clock says "ready now" while a use is left,
so the AI's choice, the wrap's looming check and saves need nothing else; the client mirrors the refills from
the starts it sees (`readyUses`) and `RiderAttacks` draws the count on a plate in the tile's corner, the next
refill only shading a tile that still has a use.

## Volleys

Gold Rush is a volley (`volley` in `authored_attacks.json`, `AttackVolley`): its volumes never strike; at
`launch_tick` each drill leaves as a `VolleyMissileEntity` from where the clip holds it
(`attack_motion/gold_rush_volley.json`, sampled from the body's own forward kinematics), coasts out, lights
after its delay, homes on the
target or the rider's aim at up to `turn` degrees a tick until it passes it, and deals `power` of the attack
per hit (volley damage type, so all five land); `VolleyMissileRenderer` draws that drill's own quads from the
species mesh, spinning. The clip hides the drills at the release and grows them back; the effect keeps only
the socket flashes. `rider_checks` casts it from 3 blocks inside its 16-block range too.
