# AGENTS.md — DigiCube

Rules and conventions for anyone (human or AI) writing code in this repository.
Read this before touching anything. If a rule here conflicts with a habit from
another Minecraft project, **this file wins**.

---

## 1. What this project is

DigiCube is a Digimon mod for **Minecraft Java Edition**. Players find, tame, raise,
train and digivolve partner Digimon.

| Thing | Value | Why it matters |
|---|---|---|
| Minecraft | `26.2` | Year-based versioning; `26.x` is **not** `1.21.x`. Tutorials written for 1.20/1.21 are often wrong here. |
| Java | **25** | Set by `java_version` in `gradle.properties`. Records, sealed types, pattern matching and `switch` expressions are all fair game. |
| Mappings | **Mojang official (mojmap)** | Class names are `Identifier`, `Item`, `Level`, `Player`, `ItemStack`. Note `Identifier` — Mojang **renamed `ResourceLocation` to `Identifier`** in the 26.x mappings, so pre-26 tutorials and muscle memory are wrong here. `World` and `PlayerEntity` are Yarn names and do not exist. |
| Loader today | **Fabric** | The `fabric/` module. |
| Loader later | **NeoForge** | The `neoforge/` module does not exist yet. Section 13 covers adding it. |
| Build | Gradle 9.5 + MultiLoader layout | No Architectury — its API has no `26.x` release. |

Authoritative version numbers live in `gradle.properties`. Never hardcode a version
in a build script or in Java; read it from there.

---

## 2. Golden rules

1. **Never invent an API.** If you are not certain a Minecraft or Fabric method exists in
   `26.2`, do not guess a plausible name. A wrong guess costs a full Gradle build to
   discover. This is not hypothetical: the first version of this scaffold used
   `ResourceLocation` because that is the name everywhere pre-26, and every file using it
   failed to compile.

   Ctrl-click the symbol in IntelliJ, or check the real jar directly — it is the ground
   truth and it answers in a second:

   ```bash
   jar tf ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar | grep -i identifier
   ```

   ```bash
   javap -cp ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar net.minecraft.world.item.Item
   ```

   Use `jar tf | grep` to find where a class lives, and `javap` to read its exact method
   signatures before calling one.
2. **`common/` must never import a loader.** No `net.fabricmc.*`, no `net.neoforged.*`.
   See section 4.
3. **Client code must never run on a dedicated server.** See section 8. This is the
   single most common way to crash a modded server.
4. **The build must pass before you say you are done.** `./gradlew build`. Not
   "it should compile".
5. **Content is data, code is mechanics.** Adding a new Digimon should not require new
   Java. If it does, the system is wrong — fix the system.
6. **One concern per commit**, with a one-line `type: description` message. "Add Greymon"
   and "refactor the evolution engine" are two commits. See section 12.
7. **Never edit anything under `build/`, `runs/`, or `.gradle/`.** Those are generated.

---

## 3. Repository layout

```
digicube/
├── AGENTS.md               <- you are here
├── README.md               <- human setup guide
├── gradle.properties       <- ALL version numbers and mod identity
├── settings.gradle         <- which modules exist
├── build.gradle            <- Gradle plugin versions only
├── buildSrc/               <- shared build logic ("convention plugins")
│   └── src/main/groovy/
│       ├── multiloader-common.gradle   <- applied to every module
│       └── multiloader-loader.gradle   <- applied to loader modules only
│
├── common/                 <- 90%+ of the mod lives here. No loader imports.
│   └── src/main/
│       ├── java/com/digicube/
│       │   ├── Constants.java          <- MOD_ID, LOG, id() helper
│       │   ├── DigiCube.java           <- shared entry point
│       │   ├── digimon/                <- the domain model (species, stages, evolution)
│       │   ├── registry/               <- DCItems, DCBlocks, DCEntityTypes, ...
│       │   ├── platform/               <- ServiceLoader bridge to loader features
│       │   └── mixin/                  <- cross-loader mixins (last resort)
│       └── resources/
│           ├── digicube.mixins.json
│           ├── pack.mcmeta
│           ├── digicube.png            <- mod icon
│           ├── assets/digicube/        <- client-side: textures, models, lang, sounds
│           └── data/digicube/          <- server-side: recipes, loot, tags, species
│
└── fabric/                 <- thin Fabric adapter. Keep it small.
    └── src/main/
        ├── java/com/digicube/fabric/
        │   ├── DigiCubeFabric.java              <- main entry point
        │   ├── client/DigiCubeFabricClient.java <- client-only entry point
        │   ├── platform/FabricPlatformHelper.java
        │   └── mixin/                           <- Fabric-only mixins
        └── resources/
            ├── fabric.mod.json
            ├── digicube.fabric.mixins.json
            └── META-INF/services/...            <- wires up the platform helper
```

**How the modules combine:** `fabric/` does not depend on a compiled `common` jar.
`buildSrc/src/main/groovy/multiloader-loader.gradle` feeds `common`'s *source files*
into the Fabric compile task, so `fabric/build/libs/digicube-fabric-26.2.jar` is a
single standalone jar. That is why there is no "common jar" to ship.

---

## 4. The module boundary — the most important rule

`common/` compiles against **plain, un-modded Minecraft**. It physically cannot see
Fabric classes; if you import one, compilation fails.

When common code needs something only a loader can do — registering an event, checking
whether a mod is installed, opening a config screen:

1. Add a method to `common/src/main/java/com/digicube/platform/services/IPlatformHelper.java`.
2. Implement it in `fabric/src/main/java/com/digicube/fabric/platform/FabricPlatformHelper.java`.
3. Call it from common as `Services.PLATFORM.yourMethod()`.

The wiring is plain Java `ServiceLoader`. The file
`fabric/src/main/resources/META-INF/services/com.digicube.platform.services.IPlatformHelper`
contains the implementation's fully-qualified class name. **If you add a new service
interface you must add a matching file there**, or the mod crashes on startup with
"No implementation found for service".

Do not create a service for a one-off. Services are for capabilities that genuinely
differ per loader.

### What goes where

| Put it in `common/` | Put it in `fabric/` |
|---|---|
| Digimon domain model, stats, evolution logic | `ModInitializer` / `ClientModInitializer` |
| Item / block / entity classes and registration | Fabric API event subscriptions |
| Recipes, loot tables, tags, lang, models, textures | Renderer and model-layer registration |
| Anything using only `net.minecraft.*` | Networking channel setup |

---

## 5. Naming conventions

| Kind | Convention | Example |
|---|---|---|
| Mod id | lowercase, no separators | `digicube` |
| Registry holder class | `DC` + plural noun | `DCItems`, `DCBlocks`, `DCEntityTypes`, `DCSounds` |
| Registry field | `SCREAMING_SNAKE_CASE`, matches its id | `DIGIVICE` for `digicube:digivice` |
| `Identifier` path | `snake_case`, English | `training_dummy` |
| Java package | `com.digicube.<feature>` | `com.digicube.digimon` |
| Fabric package | `com.digicube.fabric.<feature>` | `com.digicube.fabric.client` |
| Mixin class | `Mixin` + target class name | `MixinMinecraft` |
| Mixin injected member | prefixed `digicube$` | `digicube$onClientInit` |
| Translation key | `<type>.digicube.<path>` | `item.digicube.digivice` |
| Species translation key | `digimon.digicube.<name>` | `digimon.digicube.agumon` |

**Always build identifiers with `Constants.id("thing")`.** Never write
`Identifier.fromNamespaceAndPath("digicube", ...)` inline, and never a bare string
literal `"digicube:thing"`.

Digimon names use their **Japanese romanisation** as the id (`agumon`, `greymon`,
`wargreymon`). English dub names, where they differ, belong in `en_us.json` only.

---

## 6. Registration

Registration happens in the `DC*` classes under
`common/src/main/java/com/digicube/registry/`. Fields are `static final` and register
themselves in the static initialiser; each class exposes an `init()` that the loader
entry point calls to force class loading.

Since Minecraft 1.21.2 an `Item` must know its own id **before** construction, hence the
factory / `setId` pattern:

```java
private static ResourceKey<Item> key(String path) {
    return ResourceKey.create(Registries.ITEM, Constants.id(path));
}

private static Item register(ResourceKey<Item> key, Function<Item.Properties, Item> factory, Item.Properties properties) {
    Item item = factory.apply(properties.setId(key));
    return Registry.register(BuiltInRegistries.ITEM, key, item);
}
```

Copy this shape for blocks (`Registries.BLOCK` / `BuiltInRegistries.BLOCK`), entity types,
sounds and so on. Do **not** reach for Fabric's registry helpers — they would drag a loader
import into `common/`.

### Checklist: adding an item

Miss a step and it shows up in game as a black-and-purple cube named `item.digicube.foo`.

- [ ] `ResourceKey` + `Item` field in `DCItems`
- [ ] `common/src/main/resources/assets/digicube/items/foo.json` — client item definition
- [ ] `common/src/main/resources/assets/digicube/models/item/foo.json` — the model
- [ ] `common/src/main/resources/assets/digicube/textures/item/foo.png` — 16x16 PNG
- [ ] `item.digicube.foo` in `assets/digicube/lang/en_us.json`
- [ ] Add player-facing items to the DigiCube Creative tab's ordered `displayItems`
      list and an appropriate vanilla category in `fabric/.../registry/DCCreativeTabs.java`

Verify in game with `/give @s digicube:foo`.

The dedicated **DigiCube** tab is the home for all player-facing mod items, with the
Digivice as its icon and first item. The Digivice also appears in **Tools & Utilities**,
after the compass, and in Creative search. Retain appropriate vanilla-category
entries as the collection grows. The Fabric builder and events live in
`fabric/.../registry/DCCreativeTabs.java`; initialize it after `DCItems` on both sides.
Fabric tab APIs belong in `fabric/`, not `common/`.

---

## 7. Assets vs data

| `assets/digicube/` — client | `data/digicube/` — server |
|---|---|
| `textures/`, `models/`, `items/` | `recipe/`, `loot_table/`, `advancement/` |
| `lang/en_us.json` | `tags/` |
| `sounds/`, `sounds.json` | `species/` (DigiCube's own species files) |

A dedicated server never reads `assets/`. A resource pack never reads `data/`.
Putting a file in the wrong tree means it is silently ignored — with no error message.

JSON files use **2-space** indentation; Java uses **4 spaces**.

---

## 8. Client / server side safety

Minecraft runs as two logical sides. A dedicated server jar does not contain
`net.minecraft.client.*` at all — touching it there is an instant `NoClassDefFoundError`.

Rules:

- **Never** reference `net.minecraft.client.*` from `common/`, except inside a mixin that
  is listed under `"client"` in the mixin config.
- Client-only registration — renderers, screens, key binds, model layers — goes in
  `fabric/src/main/java/com/digicube/fabric/client/DigiCubeFabricClient.java`.
- Game logic — damage, evolution, inventory changes, world edits — runs on the **server**
  side and is synced to clients. Never decide gameplay outcomes on the client.
- Check `level.isClientSide()` before spawning particles or sounds locally, and before
  running server-authoritative logic. Get this backwards and things de-sync.
- Test every feature with **`./gradlew :fabric:runServer`**, not just the client.

---

## 9. Digimon domain conventions

The domain lives in `common/src/main/java/com/digicube/digimon/`.

- `DigimonSpecies` is the **immutable shared sheet** for a Digimon: base stats, stage,
  attribute, evolution branches. Exactly one instance exists per species.
- Anything that differs between two individuals — level, bond, nickname, current HP,
  training points, weight — belongs on the **entity**, never on the species.
- `DigimonStage` and `DigimonAttribute` carry stable string ids (`"child"`, `"vaccine"`).
  These get written to JSON and to save data, so **changing one is a breaking data
  migration**, not a rename.
- `Evolution` lists are evaluated **in order, first match wins**. Put the rarest and most
  specific branch first, the plain level-gated fallback last.
- Attribute damage multipliers live in `DigimonAttribute.damageMultiplierAgainst`. Keep
  balance numbers there rather than scattered through combat code.
- Attacks are data on the species too: `DigimonSpecies.attacks` is a list of
  `DigimonAttack` in **priority order** (first ready + in range wins). Timing, power and
  cooldown live there; `DigimonEntity` runs the timeline and `DigimonAttackGoal` picks
  the move. The client animation is looked up by the attack id path, so an attack named
  `digicube:claw` needs a harness animation called `claw` (plus `claw_mirrored` when it
  alternates sides). Author animations in `../harness` (README §3c), never by hand in Java.
- Ownership: `DigimonEntity` implements `OwnableEntity`; `/digicube give <species> [player]`
  spawns a partner. Owned Digimon follow their tamer and join their fights.
- Slow projectiles must earn their hits: vanilla `ThrowableProjectile` collides as a thin
  ray (`ProjectileUtil.computeMargin`: 0 for two ticks, at most 0.3 blocks after), so a
  big fireball drawn one block wide would miss like a needle. `PepperBreathEntity` is the
  pattern: lead the target (`predictImpactPoint`), sweep the projectile's own box for
  hits before `super.tick()`, and bend a few degrees per tick toward the target while it
  stays ahead. Tune those constants before touching speed or hitbox size.

Species are loaded from the bundled `data/digicube/species.json` catalog and
`data/digicube/species/*.json` sheets by `BundledSpeciesLoader`, on both sides at
startup. Add species as data; do not add species constructors to
`DigimonSpeciesBootstrap`. Attack ids reference shared moves in the bootstrap, in
priority order. The next architectural step is datapack reload support plus server
catalog synchronization; the current classpath loader does not process `/reload`.

---

## 10. Commands

Run from the repository root. On Windows use `gradlew.bat`; the examples below use the
POSIX form.

```bash
./gradlew build
```

```bash
./gradlew :fabric:runClient
```

```bash
./gradlew :fabric:runServer
```

```bash
./gradlew clean
```

```bash
./gradlew --refresh-dependencies
```

`build` compiles and assembles the jars and is the gate before calling anything done.
`runClient` and `runServer` launch Minecraft with the mod already loaded.
`--refresh-dependencies` is needed after changing versions in `gradle.properties`.

The first `build` downloads and decompiles Minecraft and takes **10–30 minutes**.
Later builds take seconds. Shipped jars land in `fabric/build/libs/`; ignore the
`-sources` and `-javadoc` ones.

Dev-run game files (worlds, logs, configs) live in `fabric/runs/client/` and
`fabric/runs/server/` and are git-ignored. Crash logs are at `runs/*/logs/latest.log` —
**read the actual stack trace before theorising about a cause.**

---

## 11. Definition of done

Before reporting a change as complete:

1. `./gradlew build` passes.
2. Launch the updated build with `./gradlew :fabric:runClient` so it is ready to try
   (`--args="--quickPlaySingleplayer \"New World\""` opens the dev world directly).
3. Tell the user exactly what to try in game — the command, the item, the recipe — and
   leave the in-game testing to them.
4. If it touches gameplay logic, mention that `:fabric:runServer` should be checked too.
5. No new warnings in `latest.log` that this change introduced.

### In-game testing is manual

The user tests in game themselves. An AI agent must **not** drive the running game: no
sending keystrokes or chat commands to the Minecraft window, no screenshotting it, no
scripted "spawn it and look" loops. Launching the client with the fresh build so the
test is one click away is welcome; everything after that is the user's.

Model verification **outside** the game is different and encouraged: rendering a
Blender model (the harness in `../harness` produces idle, front, side and action
renders) and looking at the images before handing the model over is expected.

If something could not be built or launched, **say so explicitly** rather than implying
it was tested.

---

## 12. Commits

Commit messages are a **single line**, in the form `type: description`. No body, no
bullet list, no explanatory paragraph underneath.

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
- Keep it under ~70 characters. If it does not fit, the commit is doing too much —
  split it (see golden rule 6).
- **No trailers of any kind.** No `Co-Authored-By`, no "generated with" or other
  tool-attribution footer. The message is the one line and nothing else.

---

## 13. Adding NeoForge later

The layout already anticipates this, so no rewrite is required:

1. Create `neoforge/` mirroring `fabric/`.
2. `neoforge/build.gradle` applies `multiloader-loader` plus `net.neoforged.moddev`, with
   `neoForge { version = neoforge_version }`.
3. Uncomment `include('neoforge')` in `settings.gradle`.
4. Add `neoforge/src/main/resources/META-INF/neoforge.mods.toml`.
5. Implement `NeoForgePlatformHelper` and add the matching `META-INF/services/` file.
6. Port only the entry point and the event subscriptions. **If you find yourself copying
   business logic into `neoforge/`, that logic was in the wrong module — move it to
   `common/`.**

`neoforge_version` is already pinned in `gradle.properties`.

---

## 14. Porting to a new Minecraft version

Minecraft now ships a drop every few months (`26.1`, `26.2`, `26.3`, ...). To port:

1. Update `minecraft_version`, `minecraft_version_range`, `neo_form_version`,
   `fabric_version` and `fabric_loader_version` in `gradle.properties`.
2. Update the Loom and ModDevGradle versions in `build.gradle` if needed.
3. Read the Fabric porting notes at <https://docs.fabricmc.net/develop/porting/> and
   NeoForge's migration primer for that version.
4. Expect mixins to break first — they bind to exact vanilla method signatures.

Do this on a branch, never on `main`.

---

## 15. Things not to do

- Don't add a dependency without a concrete reason. Every one is a compatibility risk and
  another thing players must install.
- Don't write a mixin when an event or an API method exists. Mixins break on every update
  and conflict with other mods.
- Don't use `System.out.println`. Use `Constants.LOG`.
- Don't catch `Exception` to silence a crash. Fix the cause or let it fail loudly.
- Don't store mutable global state outside a registry. Minecraft runs multiple worlds, and
  both a client and an integrated server, in a single JVM.
- Don't commit `runs/`, `build/`, or personal IDE files.
- Don't ship copyrighted Digimon assets you did not make. Sprites, models and audio must be
  original or properly licensed. Digimon is a Bandai trademark and this is unofficial fan
  work.

---

## 16. Where to look things up

- Fabric docs, with the selector set to **26.2**: <https://docs.fabricmc.net/develop/>
- Fabric API source: <https://github.com/FabricMC/fabric>
- NeoForge docs: <https://docs.neoforged.net/>
- The MultiLoader template this layout follows: <https://github.com/jaredlll08/MultiLoader-Template>
- Cobblemon, the reference for a large data-driven creature mod: <https://gitlab.com/cable-mc/cobblemon>
- Blockbench, for models and animations: <https://www.blockbench.net/>

When the docs and reality disagree, **the decompiled Minecraft source in the IDE is the truth.**
