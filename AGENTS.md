# AGENTS.md — DigiCube

Rules and conventions for anyone (human or AI) writing code in this repository. Read this before touching
anything. If a rule here conflicts with a habit from another Minecraft project, **this file wins**.

This file holds only what every task needs. The rest lives in topic guides under [`agents/`](agents/): find your
task in the [map](#3-map-read-only-what-the-task-needs) and open those guides, not all of them.

## 1. What this project is

DigiCube is a Digimon mod for **Minecraft Java Edition**. Players find, tame, raise, train and digivolve partner
Digimon.

| Thing | Value | Why it matters |
|---|---|---|
| Minecraft | `26.2` | Year-based versioning; `26.x` is **not** `1.21.x`. Tutorials written for 1.20/1.21 are often wrong here. |
| Java | **25** | Set by `java_version` in `gradle.properties`. Records, sealed types, pattern matching and `switch` expressions are all fair game. |
| Mappings | **Mojang official (mojmap)** | Class names are `Identifier`, `Item`, `Level`, `Player`, `ItemStack`. Note `Identifier` — Mojang **renamed `ResourceLocation` to `Identifier`** in the 26.x mappings, so pre-26 tutorials and muscle memory are wrong here. `World` and `PlayerEntity` are Yarn names and do not exist. |
| Loader today | **Fabric** | The `fabric/` module. |
| Loader later | **NeoForge** | The `neoforge/` module does not exist yet. [agents/porting.md](agents/porting.md) covers adding it. |
| Build | Gradle 9.5 + MultiLoader layout | No Architectury — its API has no `26.x` release. |

Authoritative version numbers live in `gradle.properties`. Never hardcode a version in a build script or in Java;
read it from there.

## 2. Golden rules

1. **Write impersonally.** This repository is public: anyone may clone it and work in it. Code, comments, data,
   docs and commit messages name no individual (not the maintainer, a contributor or a tester) and say nothing
   about who asked for something: state the rule or the reason itself ("the design wants a straight shot a player
   can dodge"), or say "the user" when a person must be meant. Leave `LICENSE` and `mod_author` in
   `gradle.properties` as they are; authorship there is the maintainer's call.
2. **Keep the repository self-contained.** Code, comments, data and docs describe what is in this repository and
   how the game reads it. They never name, link or depend on a folder, repository or path outside it (the
   standard toolchain aside: the JDK, Gradle and its caches), and never name the tool or script that made a file.
3. **Reply in the user's language; think and write everything else in English.** Portuguese is the default; when
   the user writes in another language, reply in that one. The reply is the only text an agent produces in that
   language. Thinking, plans, code, identifiers, comments, log lines, lang and data files, commit messages, docs
   (this file, `agents/`, `README.md`), memory notes, file and branch names, published pages, prompts to other
   agents and their reports are all English, and a decision taken from a message in another language is recorded
   in English. Inside a reply, keep code, paths, commands, quoted log lines and in-game names exactly as written.
   The one exception is a translation the user asks for (a `pt_br.json`, say).
4. **Never invent an API.** If you are not certain a Minecraft or Fabric method exists in `26.2`, do not guess a
   plausible name. A wrong guess costs a full Gradle build to discover. This is not hypothetical: the first version
   of this scaffold used `ResourceLocation` because that is the name everywhere pre-26, and every file using it
   failed to compile.

   Ctrl-click the symbol in IntelliJ, or check the real jar directly — it is the ground truth and it answers in a
   second:

   ```bash
   jar tf ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar | grep -i identifier
   ```

   ```bash
   javap -cp ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar net.minecraft.world.item.Item
   ```

   Use `jar tf | grep` to find where a class lives, and `javap` to read its exact method signatures before calling
   one. When the docs and reality disagree, **the decompiled Minecraft source in the IDE is the truth.**
5. **`common/` must never import a loader.** No `net.fabricmc.*`, no `net.neoforged.*`. See
   [agents/architecture.md](agents/architecture.md).
6. **Client code must never run on a dedicated server.** See
   [agents/architecture.md](agents/architecture.md#client--server-side-safety). This is the single most common way
   to crash a modded server.
7. **The build must pass before you say you are done.** `./gradlew build`. Not "it should compile". On Windows use
   `gradlew.bat`; the rest of the commands are in [agents/build-and-run.md](agents/build-and-run.md).
8. **Content is data, code is mechanics.** Adding a new Digimon should not require new Java. If it does, the system
   is wrong — fix the system.
9. **One concern per commit**, with a one-line `type: description` message. "Add Greymon" and "refactor the
   evolution engine" are two commits. See [section 5](#5-commits).
10. **Never edit anything under `build/`, `runs/`, or `.gradle/`.** Those are generated.
11. **Keep knowledge where it belongs.** A change that alters a rule or a mechanic updates the guide that covers it
    in the same change; see [section 7](#7-keeping-these-docs-useful).

## 3. Map: read only what the task needs

Markdown links are relative to the file they sit in; paths in backticks are relative to the repository root.

| Guide | Read it when the task touches |
|---|---|
| [agents/architecture.md](agents/architecture.md) | a new class, loader hook, mixin, platform service (`IPlatformHelper`) or resource file; the layout; client vs server; `assets/` vs `data/` |
| [agents/conventions.md](agents/conventions.md) | a new identifier (`Constants.id`), registry entry (`DC*`), item (checklist), translation key or Creative tab entry; formatting |
| [agents/build-and-run.md](agents/build-and-run.md) | running the client or the server, the first build, refreshing dependencies, `runs/` folders, crash logs |
| [agents/testing.md](agents/testing.md) | verifying server behaviour: `DIGICUBE_SCENARIO` runs, balance runs, gait checks, the index of every check |
| [agents/domain.md](agents/domain.md) | species sheets (`DigimonSpecies`), evolution and growth, ownership, the first partner (`StarterFlow`), levels and XP (`Progression`), defeat, healing and food, drops (`DigimonDrops`, Digimeat), creative play, the scan and Digitama (`Scan`, `DigitamaItem`), spawning and its regions, whom wild Digimon fight (`WildGrudge`) |
| [agents/combat.md](agents/combat.md) | crits (`CriticalHits`), combat marks (`CombatMarkState`, `FreezeMark`), attacks as data (`DigimonAttack`, `IceCombo`, `PounceAttacks`, `BreathAttacks`), tactics (`DigimonTactics`) and matchup balance, wraps (`ConstrictionCoil`), ink, projectiles |
| [agents/authored-attacks.md](agents/authored-attacks.md) | a move in `authored_attacks.json` (`AuthoredVolumeAttack`): bursts at the target, travelling sweeps and dashes, leaps, discharges (`ArcDischarge`), whole turns in a clip, particle styles, sound cues, stacked uses, volleys |
| [agents/compound-attacks.md](agents/compound-attacks.md) | moves cast as other attacks' forms (`compound_attacks.json`, `CompoundAttacks`): drawn-weapon stances (`AttackStance`), gauges (`attackLanded`), chained strikes |
| [agents/animation.md](agents/animation.md) | models and clips as native JSON (`NativeAnimationSet`); `assetTest`; attack clip names; `ground_models.json` keys (`look`, `attack_effects`, `paws`, `cloth`, `tails`, `ropes`, `spine`); serpent bodies (`SerpentSpine`); rescaling a species |
| [agents/locomotion.md](agents/locomotion.md) | speeds and strides (`DigimonGait`), gait clips, gait changes and pivots, steady turning (`SteadyBodyControl`), serpents (`SerpentTrail`, their necks and climbing), swimming on a path, ice grip and skids (`IceSlip`), stepping down, hovering, `travel_facing`, wide bodies and pathing |
| [agents/agility.md](agents/agility.md) | a body's own leap, crouch, roll and tuck (`body.leap`, `body.crouch`, `_crouch` twins), the AI's duck/roll/leap clear, blows' `launch`, shots' `falloff` |
| [agents/mounts.md](agents/mounts.md) | anything a rider does (`RiderAttack`, `RiderControls`, attack tiles): getting on, pose, water and sea mounts, pace, leaps, charges, flying mounts |
| [agents/flight.md](agents/flight.md) | flying under a rider (`AerialRiding`, `AerialHandling`): agile flight (dive, boost, slide, barrel roll, skim), the flight reserve's costs (`FlightReserve`), attacks on the wing (pounce wing forms), the catalog's `flight` (`FlightPose`, `FlightLook`), `FlightFeel` |
| [agents/effects.md](agents/effects.md) | glows over water (`AfterWaterEffects`), particles (`DCParticles`), shot styles (`ShotStyle`), breaths of puffs (`FrostBreathRenderer`, water jets `WaterJetRenderer`, `WetSurfaces`), bodies of fire (`glow`), burning bodies (`BurnedVisuals`, `BurningFlames`), bolts (`ArcRenderer`), texture expressions (`expressions`), mouths (`mouth`), voices (`voices.json`) |
| [agents/digivice-item.md](agents/digivice-item.md) | the Digivice item (`Digivices`, `DroppedDigivice`): handing out, binding, storage, drops, the locator, the recall chip (`RecallChip`) |
| [agents/screens.md](agents/screens.md) | a screen: the GUI language (`DigiTheme`, `DigiPanels`), the Digivice screen (`DigiviceScreen`: Analyzer with its marks guide, record (`AnalyzerWitness`) and SCAN page (`ScanPage`), Digispace, the DIGIVOLUTION tree and its choice (`EvolutionSheet`, `EvolutionTree`)) |
| [agents/command-wheel.md](agents/command-wheel.md) | the command wheel (`CommandWheelScreen`, `CommandWheelReadout`): its orders, layout, pointing and cursor; universal control: AUTO per attack (`ManualAttacks`) and attack orders (`DigimonEntity.orderAttack`, `AttackOrders`) |
| [agents/dev-panel.md](agents/dev-panel.md) | the developer panel (`DevCatalog`, `DevActions`), battle testing (`BattleTest`) |
| [agents/porting.md](agents/porting.md) | adding NeoForge or moving to a new Minecraft version |

**Species.** Work on one species starts at its guide under `agents/species/`, named by the species id:
[agumon](agents/species/agumon.md), [betamon](agents/species/betamon.md), [centalmon](agents/species/centalmon.md) (Centarumon),
[darktyrannomon](agents/species/darktyrannomon.md), [digmon](agents/species/digmon.md), [dinohyumon](agents/species/dinohyumon.md),
[elecmon](agents/species/elecmon.md), [kabuterimon](agents/species/kabuterimon.md) (and agile flight),
[ganimon](agents/species/ganimon.md) (Crabmon), [garurumon](agents/species/garurumon.md), [gesomon](agents/species/gesomon.md) (and the whip),
[golemon](agents/species/golemon.md), [gotsumon](agents/species/gotsumon.md), [greymon](agents/species/greymon.md) (and run lattices,
walking turns, a biped's aimed pounce), [ikkakumon](agents/species/ikkakumon.md), [leomon](agents/species/leomon.md) (and compound moves),
[meramon](agents/species/meramon.md) (and fire breaths),
[mojyamon](agents/species/mojyamon.md) (and thrown weapons), [monochromon](agents/species/monochromon.md) (and held rushes),
[pukamon](agents/species/pukamon.md) (Bukamon),
[seadramon](agents/species/seadramon.md), [shellmon](agents/species/shellmon.md) (and spins in the shell, water jets).
A species without a guide has nothing beyond its sheet and the topic guides.

## 4. Definition of done

Before reporting a change as complete:

1. `./gradlew build` passes.
2. **Test headless whenever the outcome can be read without eyes.** If the change runs on the server and its result
   shows up in world state, entity state or the log, stage it with a scenario ([agents/testing.md](agents/testing.md))
   and quote the verdict lines in the report. Combat, attack selection, navigation, effects, spawns, levelling,
   timers and server-side rules all qualify. Extend the runner when the situation you need does not exist yet; that
   is part of the change, not optional. A change that makes a scenario slower or fail is not done. Only what needs
   a screen or a real player's hands is left to manual testing.
3. Launch the updated build with `./gradlew :fabric:runClient` so it is ready to try
   (`--args="--quickPlaySingleplayer \"New World\""` opens the dev world directly).
4. Tell the user exactly what to try in game — the command, the item, the recipe — and leave the feel judgments
   (animation, pacing, balance) to them.
5. No new warnings in `latest.log` that this change introduced.

What an agent still must **not** do is drive the Minecraft window: no keystrokes or chat commands typed into the
client, no screenshots of it. Scenario runs are logs, not screens. Launching the client with the fresh build so the
user's own test is one click away remains welcome.

If something could not be built, launched or run through a scenario, **say so explicitly** rather than implying it
was tested.

## 5. Commits

Commit messages are a **single line**, in the form `type: description`. No body, no bullet list, no explanatory
paragraph underneath.

```
feat: add digivice item
fix: correct greymon evolution level
refactor: extract evolution matching into its own class
docs: document the item registration checklist
chore: bump fabric api to 0.159.0
test: cover attribute damage multipliers
```

Types in use: `feat`, `fix`, `refactor`, `docs`, `chore`, `test`, `style`, `perf`.

Rules:

- Lowercase after the colon. No trailing full stop.
- Imperative mood: "add x", not "added x" or "adds x".
- Keep it under ~70 characters. If it does not fit, the commit is doing too much — split it (see golden rule 9).
- **No trailers of any kind.** No `Co-Authored-By`, no "generated with" or other tool-attribution footer. The
  message is the one line and nothing else.

## 6. Things not to do

- Don't add a dependency without a concrete reason. Every one is a compatibility risk and another thing players
  must install.
- Don't write a mixin when an event or an API method exists. Mixins break on every update and conflict with other
  mods.
- Don't use `System.out.println`. Use `Constants.LOG`.
- Don't catch `Exception` to silence a crash. Fix the cause or let it fail loudly.
- Don't store mutable global state outside a registry. Minecraft runs multiple worlds, and both a client and an
  integrated server, in a single JVM.
- Don't commit `runs/`, `build/`, or personal IDE files.
- Don't ship copyrighted Digimon assets you did not make. Sprites, models and audio must be original or properly
  licensed. Digimon is a Bandai trademark and this is unofficial fan work.

## 7. Keeping these docs useful

- This file holds only what every task needs. Knowledge that only some tasks need goes to the guide for its area;
  a new area gets a new guide and a row in the map. Each fact lives in one guide; others link to it.
- A species' own notes go to `agents/species/<id>.md`. When a second species uses a mechanic, move the generic part
  into the topic guide and leave the species examples.
- Every guide opens with a title and a line saying what it covers and when to read it; its map row says the same
  in short, naming the classes and files a task would mention.
- Write how things work now: the rule, the class or file, and the check that pins it. Describe the files the game
  reads (formats, keys, limits), not how they were made. History and rationale belong in the git history; keep a
  "because" only when it prevents a known regression.
- This file and `agents/` are the only instructions both Codex and Claude Code read. A `CLAUDE.md` would switch
  Claude Code off this file unless its first line imports it (an `@` import of `AGENTS.md`); never name a guide
  `AGENTS.md` or `CLAUDE.md`.
- `:common:agentDocsTest`, part of `./gradlew build`, enforces the budgets, the map, the links and anchors, the
  two rules above, and that no link or path in these docs or `README.md` leaves the repository; its failure
  message says what to fix.
