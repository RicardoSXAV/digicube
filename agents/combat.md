# Combat: rules, attacks and AI

Read this before changing how any Digimon fights or tuning a matchup's balance (the knobs are here; balance runs
in [testing.md](testing.md#balance-runs) measure them): damage and crits, combat marks, attacks as data and their
readiness checks, tactics and dodging, wraps, ink, projectiles and kinetic shots. How an authored move's
volumes and effects are built is in [authored-attacks.md](authored-attacks.md); riding is in
[mounts.md](mounts.md).

## Damage and the attribute triangle

The attribute triangle is a **critical-hit chance**, not a damage multiplier: `CriticalHits` (base 10 %,
favoured 25 %, countered 5 %, ×1.5) rolls on every Digimon hit through `DigimonEntity.damageAgainst` and the
projectile impacts. Defence is the vanilla `ARMOR` attribute at half `base_defence` (`Progression.armor`).
Keep those numbers there.

## Combat marks

Combat marks live on every `LivingEntity` (`CombatMarkState`, `MixinLivingEntity`): two packed, tracked ints
drive the emblems in `CombatMarkBadges`. **Crack** (`CrackMark`): fists and ground waves fill a 3-charge
gauge, full = `digicube:cracked` for 6 s, +25 % damage taken from every source (a `@ModifyVariable` on
`hurtServer`). Which attacks crack is by `DigimonAttack.Kind`. **Exposed** (`ExposedMark`): a kinetic shot
with `expose_ticks` (Hunting Cannon, 80) leaves its victim `digicube:exposed`: +30 points of crit chance on
every Digimon hit against it (on top of the triangle, in `CriticalHits.chance`) and no dodging
(`DigimonAttackGoal.dodgeChance`); a crit on it blinks the emblem (`mark_exposed_flash`). **Burn**: fire a
Digimon's attack lights (Pepper Breath, Mega Flame, a breath's, an authored blow's or a kinetic shot's `burn`: `DigimonEntity.scorch`,
which also thaws; call `digicube$burn(ticks)` after igniting) is a Burn for
as long as the body keeps burning; vanilla fire does the damage and water puts it out, the emblem's rim drains
with the fire (`mark_burn`), and the body burns in the mark's own look ([effects.md](effects.md#burning-bodies)). **Freeze** (`FreezeMark`): frost pays into a gauge of 100 (a pounce's bite and each
tick of a breath's contact, amounts in their data); left unfed for `HOLD_TICKS` it drains; full, the body is
`digicube:frozen` for `FROZEN_TICKS` (no moving, jumping or acting, vanilla's freezing shake; a Digimon's attack
under way is cut) and then `digicube:frost_resistance` for `RESIST_TICKS`, when the gauge cannot fill. A pounce on a
Frozen body shatters it: `shatter` times the damage, the ice breaks and the resistance starts. Fire thaws it. The
emblem is a round medallion (`mark_freeze`, `_off`, `_spent`, `_flash`): a grey snowflake rising lit as the gauge
fills, the rim draining with the ice, a white flash as the ice closes or breaks; the resistance is drawn small beside
the row (`mark_freeze_resist`) and never counts toward it. The first readout is full (one bit left); new marks go in
the second (`digicube$marks2`: Exposed bits 0-7, Burn 8-14, the Freeze gauge 15-21, the ice 22-28, its flash 29, frost
resistance 30, bit 31 free).

Combat marks are tracked for every living entity in one packed int (`MixinLivingEntity`) and drawn as emblems
above the head by `fabric/.../client/render/CombatMarkBadges`; add a mark there, not as a new synced field, and as
a constant of `CombatMark` (common), the list the Analyzer's guide and a tamer's record use: `CombatMark.of(attack)`
tells which mark a move leaves from the move's own data and kind, `showing(marks, marks2)` which emblems a body
shows, the same rule as the badges. `:common:analyzerTest` pins both.

Frost that chills (a breath of puffs marked `cold`, Seadramon's Ice Blast, or a straight frost stream: `chills`) never
freezes a creature: a second of landed contact charges **Cold** on the victim (`CombatMarkState`, slowed movement for
`IceCombo.COLD_TICKS`, topped up by further contact, melted by fire), and its wrap may take any prey, Cold or not; on prey
already Cold the AI keeps a charge's worth of fuel back (`IceCombo.chillFuelTicks`). A straight stream breathed on the
move (`move` on its rider attack) leaves along the attack yaw, within `STREAM_TWIST` (70 degrees) of the body at
`STREAM_TURN` a tick (`streamYaw`), and freezes a floe where it ends on the sea (`frostSurface`); a serpent swimming
breathes from its lower head (`swim_head_drop`).

## Attacks as data

Attacks are data on the species too: `DigimonSpecies.attacks` is a list of `DigimonAttack` in **fallback
priority order** (first ready + in range wins for ordinary move sets; a move with forms casts the form that suits
the target, see [authored-attacks.md](authored-attacks.md#forms)). Timing, power and cooldown live there;
`DigimonEntity` runs the timeline and `DigimonAttackGoal` picks the move. Each attack plays the clip named
after its id; see [animation.md](animation.md#attack-clips).

Two kinds carry their own data file (Garurumon's; see [species/garurumon.md](species/garurumon.md)):

- A **pounce** (`pounce_attacks.json`, `PounceAttacks`, kind `POUNCE`): a gather, then a burst along a line that eases
  from its opening speed to its closing one, the jaws open through it and the body stopping at the first one they
  bite (`PounceSession`, server). The AI's line leads its prey and turns after it by `home` degrees a tick; a rider's
  follows the crosshair (`PounceLines`), from the ground or mid-leap (the gather skipped, the fall held), flown by the
  rider's client from the press while the server bites along the path the body takes. Uses stack (`charges`).
- A **breath of puffs** (`breath_attacks.json`, `BreathAttacks`, a fueled `FROST_STREAM`): instead of a straight jet,
  each tick the mouth sheds puffs with the aim's speed plus the body's motion, which slow, sink, slide along what they
  strike (met head-on, part of their push splashes out over the surface) and die (`FrostBreath`, the same on the server,
  which strikes with them, and on every client, which draws them); a swept aim bends the stream like a hose. The AI
  aims at its prey's chest led by its pace, through an alpha-beta filter (`breathLead`), so prey stepping about never
  jerks the body round. A puff's radius follows the breath's `radius` profile, `[age, radius]` points from age 0
  (straight between them, held past the last), and the drawn flame is as wide as it, so what the flame covers is what
  it strikes. Optional `sounds` names its
  `start`, `loop` and `end` sound events, which every client plays itself (without, the server bubbles on every damage
  pulse). Contact pays into the Freeze gauge (`mark` `freeze`, the default) or charges Cold (`mark` `cold`) and pulses
  damage every `fuel` interval per victim; `mark` `burn` sets it alight for `burn` ticks instead (a fire breath, with `melt`
  melting snow and ice: [species/meramon.md](species/meramon.md)); `art` picks how it is drawn (`flame` or `shards`,
  [effects.md](effects.md#breaths-and-pounces)). A puff under water flies on through it. Still water it strikes turns to
  frosted ice in a floe that grows round the spot as it plays there (`frostTheWorld`: two neighbours a block, six blocks a
  tick, never within two blocks of the mouth or where the body lies) and fire it crosses goes out (with `mobGriefing`).

A pouncer's AI (`choosePounce`) breathes on prey from `BREATH_FROM` blocks out while its gauge can fill and the tank
holds a share, pounces up close, on Frozen prey (the shatter) and on prey that resists frost, and leaps at prey on a
ledge above or just out of reach to pounce on it from the air (`leapToPounce`). The AI's breath from a body that steps
round on its paws ([locomotion.md](locomotion.md#steady-turning)) never snaps it round: the body comes round after the
aim at its hurried turning rate, the neck turns the rest of the way (the aim stays within the breath's `twist`), and the
legs play the gait under it, the pivot as it turns (`breathesOnItsLegs`).

Readiness also requires a viable attack path: `AttackGeometry` checks authored contact and launch clearance;
`DigimonCombatPosition` finds reachable attack spots when elevation or cover makes the current position
unusable. Preserve these checks when adding moves, and keep client/server mouth geometry identical.

## Tactics, dodging, wraps and ink

How a species fights *between* attacks is data too: the optional `tactics` block on the species sheet
(`DigimonTactics`: `hold_range`, `dodge_chance`, `reaction_ticks`, `strafe`, `lead_ticks`, `press_impaired`,
`prefer_close`, `charge_distance`, `charge_speed`, `fight_speed`, and the skirmisher's `gallop`,
`shoot_moving`, `dash_dodge`, `dash_engage`, `dash_escape`), read by `DigimonAttackGoal` and `BlindGuardGoal`.
A species without one closes in, never dodges and keeps list order. The skirmisher knobs are Centarumon's:
`gallop` circles and closes at the fight pace, `shoot_moving`
looses a shot that has `move` in its rider data on the run (the goal keeps the legs, `KineticSession.twists`,
the client twists the upper body to `DATA_ATTACK_YAW`), and the jet of the species' `aim: charge` move is the
AI's too (`DigimonEntity.startJetBurst`): a charge that bucks an Exposed or impaired target within
`dash_engage`, a short burst aside from a wind-up or a shot, a burst out of a stream's reach (a sidestep never
escapes a stream, so only a jet answers one), and a getaway from a brawler inside `hold_min` or while blinded.
The charge is a fight-only pace; a species' travel gait stays on its `locomotion` sheet (Golemon's walk is
pinned by `LocomotionRegressionTest`).

Dodging reads the opponent's wind-up (`activeAttack`/`attackTick`/`hitTick`; an aimed ground wave is
sidestepped late, just before its aim locks) and inbound projectiles server-side; a wrap against a Digimon
that fights us is timed (`DigimonEntity.wrapPunished`): a range-holding caster never walks into a brawler for
it but wraps one that has caught it (within wrap range + 1), and it waits out a heavy move (power >= 1.0) that
is under way or ready within `LOOMING_TICKS` = 10 (a wider window makes wraps rare and only open when the
fight is already won: rounds with a catch must stay under 80 % wins); the move itself is under [Wraps](#wraps). An inked mob
cannot take or keep a target more than three blocks from its body (`DCEffects.blindTo`, body to body: from the
centres two big Digimon were blind unless touching) and acts on `lastSeenThreat` instead. Ink stops aiming,
not blows: an attack already under way plays out and lands (`canStrike` on the hit paths), only a move that
keeps aiming itself at its prey breaks off (a homing jet charge, a drawn kinetic shot: `attackTracksTarget`),
and a body a whip has just touched feels where it came from for 5 s (`feels`).

`DIGICUBE_TACTICS=<species>:key=value,...;<species>:...` overrides knobs per process for sweeps.

## Wraps

A serpent with a `coil` on its sheet (`body.serpent.coil`: `girth`, `neck`, `tail`, `loops`; Seadramon) wraps with the
constriction move: `ConstrictionSession` runs it, `ConstrictionCoil` holds its shape and clock.

- **Strike.** After two ticks drawing back, the body flies at the spot beside its prey where its head will loom (0.7
  blocks a tick, 0.85 swimming, turning after the prey 30 degrees a tick) and takes it there; past `STRIKE_TICKS` (14),
  or stalled, it has missed and costs `RETRY_TICKS` (40) instead of the cooldown. The AI strikes from the move's range
  (6.5 blocks), a rider from the tile's `reach`. The prey may be a step up or down (1.5 blocks), in mid-hop within
  `SNATCH` (1.6) of the floor (it is held down there) or swimming at any depth within reach.
- **Size.** Only what the body goes all the way round once with its `neck` and `tail` free, and no more than a block
  taller than the serpent (`fit`): Seadramon takes prey up to about 1.4 blocks wide (rookies, players, most animals,
  spiders, horses), never Garurumon, Golemon or Gesomon.
- **Coil.** The loops press on the prey's box (`HUG` 1.05 of its half-width plus the body's half-girth), each a girth under
  the one it goes round, as many as cover four fifths of its height (1.15 at least, `loops` at most, fewer where the body
  runs short), round its middle and never under the floor. The server checks the loops' line for blocks (`ring`): a mob
  with one in the way is drawn out up to `DRAW_OUT` (1.2 blocks) toward the caster, a player never is (refused), and the
  caster's feet stand beside the loops on the side it came from (`headDistance`).
- **Hold.** From the capture the prey is held (`CONSTRICTED`; a mob kept at the coil's middle, a player that slips
  `SLIPPED` 0.75 from it is let go) and squeezed at 14, 24, 34 and 44 ticks (`digicube:crush_attack`, through armour:
  power .08 plus `CRUSH_SHARE` 6 % of its full health each), let go at 48; the move ends at 60. Only a push of
  `BREAKING_PUSH` (1.0) breaks it off. Release leaves Digimon prey winded (no attack for 20 ticks) and any prey resistant
  to holds and frost for 160.
- **Drawn.** The coil is synced (`DATA_WRAP_*`: the prey's feet, its width and height, the winding, the capture tick) and
  laid by `SerpentCoil` ([animation.md](animation.md#serpent-spines)); the move's clip keys only head, jaw and fins, on
  `clipTime` (its lunge held while the strike flies).
- **AI** (`wrapWanted`): chill first; a fighter is wrapped on its timing (`wrapPunished`); a ready shot beats walking to a
  wrap on another level. Otherwise it closes in to strike reach from 40 ticks before the move is back, gives a chase that
  has not got there in 80 ticks up for 40, and strikes when `ConstrictionSession.whyNotFrom` its feet allows; the
  development trace names that gate (`[wrap-trace] ... strikeFromHere=`).

Checked by `ConstrictionRegressionTest` (`:common:speciesTest`), `wrap_checks` ([testing.md](testing.md#index-of-checks))
and `:fabric:nativeSeadramonWrapTest`.

## Projectiles

Slow projectiles must earn their hits: vanilla `ThrowableProjectile` collides as a thin ray
(`ProjectileUtil.computeMargin`: 0 for two ticks, at most 0.3 blocks after), so a big fireball drawn one block
wide would miss like a needle. `PepperBreathEntity` is the pattern: the shooter aims at where the target will
be (`TargetMotion.intercept`) and faces it, and the projectile sweeps its own box for hits before
`super.tick()`. It does not steer: the design wants a straight shot a player can dodge, and an AI that is good
at leading. Tune those before touching speed or hitbox size.

A FIREBALL move charges and fires from `attack_motion/<attack>_muzzle.json` (`FireballMuzzles`: mouth and head
per sub-tick), kept apart from `motion()` so the fireball keeps its own positioning rules; the ball's box
centre, where its core is drawn, leaves the snout. A hit holds the ball still for
`PepperBreathEntity.IMPACT_TICKS` to play `fireball_impact`, with no further collision.

Pepper Breath flies dead straight (no homing, 25 September 2026): hitting a moving body is the shooter's
skill. `TargetMotion` reads the target's last second (pace and rate of turn, falls under gravity, walls) and
measures how well that reading has foretold the last few ticks; through the wind-up the body turns onto the
meeting point by `FIREBALL_TURN` degrees a tick and commits it through `DATA_ATTACK_YAW` (the renderer draws
that facing), and a shot is only started while the reading can be trusted over the flight (`fireballWorthIt`:
a juker is shot from closer).

## Kinetic shots

A kinetic shot reloads from the moment it leaves (`cooldownUntil` is set again at the hit tick, the tile's
clock in `noticeShot`), so a rider may hold a drawn shot on the aim as long as they like, its tile lit until
the shot; the Hunting Cannon's is 6 s. Shot styles (the report, trail and burst of a shot) are in
[effects.md](effects.md#shot-styles).
