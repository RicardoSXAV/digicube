# Testing: headless scenarios, balance runs and gait checks

Read this before verifying a change whose outcome shows up in world state, entity state or the log (step 2 of
the definition of done in [AGENTS.md](../AGENTS.md)): the scenario runner and its names, balance runs, gait
checks, and the index of every named check.

## Headless scenarios: the AI tests gameplay in the real game

Gameplay logic is verified in the actual game, without a player, on the dedicated dev
server. The principle is general: a scenario is a name, a setup step that builds the
situation, and a verdict rule that reads the outcome from the world or the log. Today
the runner stages fights; add setup steps and verdict rules for spawns, levelling,
timers or anything else server-side as bugs arrive, the same way regression tests
accumulate. Player-driven features (taming, the Digivice, riding, GUIs) need a
server-side stand-in for the player before they can be staged; build that when the
first such bug needs it rather than testing them by hand forever.

`common/.../dev/CombatScenario` (hooked from `DigiCubeFabric`) reads the
`DIGICUBE_SCENARIO` environment variable, builds a platform at y=300, spawns the two
Digimon eight blocks apart, heals both every tick (the caster is invulnerable), sets
mutual targets after two seconds, logs each phase as `[scenario] ...`, and halts the
server with a `[scenario] PASS ...` or `[scenario] FAIL ...` verdict (90-second timeout).
One run takes about 40 seconds:

```powershell
$env:DIGICUBE_SCENARIO='seadramon_vs_golemon@steps'; .\gradlew.bat :fabric:runServer --console=plain
```

- Name: `<caster>_vs_<prey>[@flat|@steps|@ledge|@water][+duel][+behind]`, species by id
  path. `flat` is a bare stone platform, `steps` adds a one-block ledge, a step down and
  scattered single blocks, `ledge` raises the prey's whole half by one block so the
  caster must climb, `water` is a five-deep pool with both Digimon swimming. The prey
  is passive by default so the caster is measured alone; `+duel` lets it fight back,
  `+behind` turns its back (and any long body) toward the caster. The arena is walled,
  evicted of leftovers from earlier runs on start, and purged of natural spawns every
  two seconds, so a run only ever contains the two fighters.
- Verdict: a caster with a wrap move passes when its prey is captured and released,
  reporting when the prey turned Cold or frozen (its opening) and the ticks from that
  opening to the capture; any other caster passes after
  three landed hits, reporting the tick of the first one. Compare the numbers before
  and after a change, not just PASS.
- Read `fabric/runs/server/logs/latest.log`. Chill-loop casters also log a
  `[wrap-trace]` line every second (distance, level, status flags, fuel, the wrap's chase
  or its backoff, and the exact gate refusing a strike from the current position).
  The trace turns "it hesitates sometimes" into the name of a gate; fix the gate, rerun.
- Add a terrain to `CombatScenario.build` when a bug needs new geometry, and add a
  verdict rule when a new kind of move needs its own success criterion. Keep scenarios
  deterministic: fixed positions, healed combatants, no wild spawns nearby.
- The server EULA under `fabric/runs/server/eula.txt` is accepted; it is a run
  directory and stays git-ignored.

## Balance runs

**Balance runs** (`common/.../dev/BalanceScenario`) answer a different question: not "does the move work" but
"who wins, and how fast". `DIGICUBE_SCENARIO=balance:<a>_vs_<b>` fights real rounds to the death, no healing,
both at `DIGICUBE_BALANCE_LEVEL` (20), `DIGICUBE_BALANCE_ROUNDS` (20) of them in one server start with the
game sprinting (100 rounds in about 20 seconds). Sides alternate and each round opens from a random distance
(6–10 blocks), lateral offset and facing, because a duel that always starts from the same spots is decided by
whole hit counts and its "win chance" flips between 0 and 1 on a rounding. `DIGICUBE_NEUTRAL=true` drops the
attribute triangle; `DIGICUBE_TRIANGLE_UP` / `DIGICUBE_TRIANGLE_DOWN` override its multipliers for a sweep.
Read the `[balance] RESULT` line (win shares, duration mean/median/min/max, retargets) and the two per-side
lines (casts by move, crits and dodges per round, damage taken per round, health kept when winning, the
tactics in force). A side whose tactics call for a skill (`dash_*`, `shoot_moving`) also gets a
`[balance] SKILLS PASS|FAIL` line: uses per round from `DigimonEntity.countSkill`, and FAIL when one it should
use never happened. The target for a same-level neutral pair: about 50 % each (55–59 % is fine) and a
15-second mean. Tune from the per-side lines: damage taken per round shows who is short of a kill, casts show
which move carries the fight. Use **300 rounds** (about 30 s) for a decision: 100-round runs of one build have
ranged 38–50 % for the same side. `DIGICUBE_BALANCE_TRACE=true` logs both fighters every five ticks (distance,
attack and tick, moving/still, target, effects); read one traced round before touching a number, it is where
"waits beside a Cold prey for a wrap that is 160 ticks away" was found.

## Gait checks

**Gait checks** (`common/.../dev/GaitScenario`): `DIGICUBE_SCENARIO=gait_checks[:<species>]` walks a wild one
down the flat arena with vanilla navigation at the walk, wild panic and run modifiers and logs
`[gait] PASS|FAIL` per pace: the measured pace, the amount and run share the client will play, and the cadence
travel / stride asks for against `max_playback_rate` (above it the feet slide), and how far the body faces off
its travel (0 walking ahead, about 90 for a side-on scuttle, see `travel_facing`). A gait without a run stride
of its own passes the run pace on its walk clips. So does a species whose AI run modifier is its walk
(`run_speed` <= `walk_speed`, DarkTyrannomon): its run clip belongs to a rider's sprint and a panic. Run it after setting a new species' `base_speed` or strides;
vanilla's ground pace is about k x (base_speed x modifier)^2 blocks a tick with k near 2.1-2.2, so measure
rather than trust k.

## Index of checks

Every named scenario runs the same way: set `DIGICUBE_SCENARIO`, start `:fabric:runServer` and read the
verdict in `fabric/runs/server/logs/latest.log`. The dispatch in `CombatScenario` is the full list of names.

| Scenario | What it stages | Described in |
|---|---|---|
| `<caster>_vs_<prey>[@terrain][+duel][+behind]` | one caster against one prey | [Headless scenarios](#headless-scenarios-the-ai-tests-gameplay-in-the-real-game) |
| `balance:<a>_vs_<b>` | rounds to the death | [Balance runs](#balance-runs) |
| `gait_checks[:<species>]` | paces against strides | [Gait checks](#gait-checks) |
| `rider_checks` | rider attacks cast by a stand-in rider | [mounts.md](mounts.md#rider-attacks) |
| `sea_mount_checks` | sea mounts driven by a stand-in rider | [mounts.md](mounts.md#sea-mounts) |
| `thrower_checks` | the returning bone and the icicle | [species/mojyamon.md](species/mojyamon.md) |
| `agumon_checks` | Pepper Breath and the leaping claw | [species/agumon.md](species/agumon.md) |
| `agility_checks[:<species>]` | a body's own leaps, crouch and roll, the AI's duck, roll and leap clear, a shot's falloff and launch | [agility.md](agility.md#checks) |
| `gesomon_checks` | the AI's whip | [species/gesomon.md](species/gesomon.md#checks) |
| `darktyrannomon_checks` | DarkTyrannomon ridden: turning on the spot no faster than its pivot, eased, and faster at a walk | [species/darktyrannomon.md](species/darktyrannomon.md#checks) |
| `monochromon_checks` | Monochromon ridden (amble, gallop, Guardy Tusk's rush along the view, into a dummy and let go in the brace, Volcano Strike's Burn) and wild (a rush from afar, the ball at prey on a pillar) | [species/monochromon.md](species/monochromon.md#checks) |
| `greymon_checks` | Greymon ridden (walk, run, turning on the spot and as it walks, a leap at the run, Great Antler ahead, aimed up, from the run and from a leap, Mega Flame standing, on the run, from a leap and its burst) and wild (a charge, the fireball) | [species/greymon.md](species/greymon.md#checks) |
| `leomon_checks` | Leomon on his own AI and orders: Lion Sword's stance clock, the slash combo at short and tall prey, the stab from a run, from the air and from a leap at a ledge, Beast King Fist refused until full, the gauge saved, the punch's throw, the shot; `DIGICUBE_LEOMON_ONLY=<prefix>` runs the checks named so | [species/leomon.md](species/leomon.md#checks) |
| `shellmon_checks` | Shellmon ridden (crawl, heave, turning on the spot, the swim, Hydro Pressure's shove and push along the ground and its douse, a Golemon against its face pushed back and not up, Drill Shell tapped and spun up into a dummy, steered weak and full, off a wall) and wild (the jet's aim at range and up a pillar, both moves at prey); `DIGICUBE_SHELLMON_ONLY=<prefix>` runs the steps named so | [species/shellmon.md](species/shellmon.md#checks) |
| `meramon_checks` | Meramon on its own AI: Fire Fist dashed from afar and up close, Heat Wave ahead, aside, at moving prey (no twitching) and over snow and ice, standing in a fire | [species/meramon.md](species/meramon.md#checks) |
| `garurumon_checks` | Garurumon ridden (pace, ice, bends, turning, leaps, pounces, breath, frost on the world) and wild (turning, hunting) | [species/garurumon.md](species/garurumon.md#checks) |
| `seadramon_checks` | Seadramon wild (swimming without spinning, a channel's corner, land and its neck, a ledge, a thin wall, out of the water) and ridden (carving, its land pace, looking back, climbing head on, from a standstill and aslant, a wall of logs from every approach, let go half way and alongside, a thin wall, a high wall, a pit, lowering itself down the face, out of a sea onto a shelf's beach, a beach and a bank, Ice Blast on the move, floes) | [species/seadramon.md](species/seadramon.md#checks) |
| `wrap_checks` | a serpent's wrap: wild, prey of every size it goes round, one too big, a wall, the water; ridden, presses with and without prey (`DIGICUBE_WRAP_ONLY=<words>` runs the checks named so) | [combat.md](combat.md#wraps) |
| `battle_checks` | battle testing teams | [dev-panel.md](dev-panel.md#battle-testing) |
| `analyzer_checks` | the Analyzer record: witnessing, marks, reveal and forget | [screens.md](screens.md#the-digivice-screen) |
| `order_checks` | universal control: moves on manual never cast, orders cast, walk in, lapse and wait out a cooldown, the crosshair's enemy, saving | [command-wheel.md](command-wheel.md#checks) |
| `digivice_checks`, `digivice_checks_reload` | the Digivice item's custody | [digivice-item.md](digivice-item.md) |
| `recall_checks`, `recall_checks_reload` | the recall chip | [digivice-item.md](digivice-item.md#the-recall-chip) |
| `betamon_checks` | Betamon's dash and discharge, case by case | [species/betamon.md](species/betamon.md#checks) |
| `elecmon_checks` | Elecmon's discharge and whirl, case by case | [species/elecmon.md](species/elecmon.md#checks) |
| `kabuterimon_checks` | Kabuterimon flown by a stand-in rider (takeoff, cruise, boost, dive and carry, skim, hover, roll, slide, landing) and his moves (Beet Horn's gore on the ground and ram from the wing, Mega Blaster's shock and hit, stacked shots, the reserve they cost) | [species/kabuterimon.md](species/kabuterimon.md#checks) |
| `evolution_checks` | digivolution: the choice and its binding, news, DigiSoul, recall and reload, every Rookie route long, short and back; a Baby II's growth | [domain.md](domain.md#species-and-individuals) |
| `scan_checks` | the scan with two stand-in tamers: the first sighting, a defeat, one shared by damage, the full bar, CONVERT into the inventory (and refused with no free slot), the Digitama used from the hand (and refused with no Digivice), the hatch | [domain.md](domain.md#the-scan-and-the-digitama) |
| `spawn_checks` | wild spawning in real biomes near the world spawn: species per region, levels in band, room for big bodies | [domain.md](domain.md#wild-spawning) |
| `tamer_checks` | a stand-in tamer's side against wild Digimon: partners fought before the tamer, a fallen partner's share forgotten, its thirty-second rest and return to its slot, mending over time, food and its bite spacing (in a fight, calm again once it ends, kept through a recall), the Digivice's party kept, wild Digimon lingering, the spawner's crowd rule, Digimeat dropped by stage | [domain.md](domain.md#progression-and-healing) |
| `centalmon_checks`, `ikkakumon_checks`, `mochimon_checks` | not described yet | their classes: `KineticScenario`, `IkkakumonScenario`, `MochimonScenario` |

`./gradlew build` runs every regression task hooked into `check` (the `tasks.named('check')` lines in
`common/build.gradle` and `fabric/build.gradle`); `./gradlew :<module>:<task>` runs one alone. The compiled client models
are checked without a window by `:fabric:native<Name>Test` tasks, each described where its subject is: a species' in its
guide (`nativeLeomonTest`: [species/leomon.md](species/leomon.md#checks)), `nativeStanceTest` in
[compound-attacks.md](compound-attacks.md#checks), `nativeGlowPartsTest` in [effects.md](effects.md#bodies-of-fire).
