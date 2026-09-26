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
Digimon's attack lights (Pepper Breath, Mega Flame; call `digicube$burn(ticks)` after igniting) is a Burn for
as long as the body keeps burning; vanilla fire does the damage and water puts it out, the emblem's rim drains
with the fire (`mark_burn`). The first readout is full (one bit left); new marks go in the second
(`digicube$marks2`: Exposed bits 0-7, Burn 8-14, bits 15-31 free).

Combat marks are tracked for every living entity in one packed int (`MixinLivingEntity`) and drawn as emblems
above the head by `fabric/.../client/render/CombatMarkBadges`; add a mark there, not as a new synced field.

A frost stream with no bite beside it (Seadramon) never freezes: a second of landed contact charges **Cold**
on the victim (`CombatMarkState`, slowed movement for `IceCombo.COLD_TICKS`, topped up by further contact,
melted by fire), and its wrap may take any prey, Cold or not.

## Attacks as data

Attacks are data on the species too: `DigimonSpecies.attacks` is a list of `DigimonAttack` in **fallback
priority order** (first ready + in range wins for ordinary move sets). Timing, power and cooldown live there;
`DigimonEntity` runs the timeline and `DigimonAttackGoal` picks the move. Each attack plays the clip named
after its id; see [animation.md](animation.md#attack-clips).

Frost bite/stream pairs use `IceCombo` to choose from target mark, resistance, fuel and range; they reposition
to clear the muzzle before emission.

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
fight is already won: rounds with a catch must stay under 80 % wins). The move itself: the coil follows prey
up to .6 blocks a tick, ordinary knockback does not shake the caster off (only a push of
`ConstrictionMotion.BREAKING_PUSH` = 1.0 breaks the wrap and frees the prey), squeezes are
`digicube:crush_attack` (bypasses armour; each of the four deals power .08 plus
`ConstrictionMotion.CRUSH_SHARE` = 6 % of the prey's full health, so a hold costs about a third of any
champion, never most of it), and release leaves Digimon prey winded (no attack for 20 ticks); an inked mob
cannot take or keep a target more than three blocks from its body (`DCEffects.blindTo`, body to body: from the
centres two big Digimon were blind unless touching) and acts on `lastSeenThreat` instead. Ink stops aiming,
not blows: an attack already under way plays out and lands (`canStrike` on the hit paths), only a move that
keeps aiming itself at its prey breaks off (a homing jet charge, a drawn kinetic shot: `attackTracksTarget`),
and a body a whip has just touched feels where it came from for 5 s (`feels`).

`DIGICUBE_TACTICS=<species>:key=value,...;<species>:...` overrides knobs per process for sweeps.

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
