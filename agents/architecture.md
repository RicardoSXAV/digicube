# Architecture: layout, modules and sides

Read this before adding a class, a loader hook, a mixin, a service or a resource file: where each kind of code
and file goes, the `common`/`fabric` boundary and its platform services, and what may run on which side.

## Repository layout

```
digicube/
├── AGENTS.md               <- start here: rules for every task and the map of agents/
├── agents/                 <- topic guides for agents; species/ holds per-species notes
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
│       │   ├── digimon/                <- the domain model (species, stages, evolution, progression)
│       │   ├── entity/                 <- DigimonEntity, projectiles, AI goals
│       │   ├── party/                  <- Digivice collection, party slots, sync payloads
│       │   ├── spawn/                  <- wild spawner, spawn tables, wild settings
│       │   ├── starter/                <- first-partner prompt: starter set, saved data, flow, payloads
│       │   ├── dev/                    <- developer panel: server actions, battle testing, payloads, gate
│       │   ├── command/                <- /digicube commands
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
        │   ├── client/gui/                      <- the DigiCube GUI language: DigiTheme, DigiPanels, DigimonPreview
        │   ├── client/starter/                  <- the Partner Link screen and its client gate
        │   ├── client/digivice/                 <- the Digivice screen: shell, Analyzer and Digispace tabs
        │   ├── client/dev/                      <- the developer panel, opened from the wheel gear (dev only)
        │   ├── dev/FabricDevNetworking.java     <- developer panel transport
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

## The module boundary — the most important rule

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

## Assets vs data

| `assets/digicube/` — client | `data/digicube/` — server |
|---|---|
| `textures/`, `models/`, `items/` | `recipe/`, `loot_table/`, `advancement/` |
| `lang/en_us.json` | `tags/` |
| `sounds/`, `sounds.json` | `species/` (DigiCube's own species files) |

A dedicated server never reads `assets/`. A resource pack never reads `data/`.
Putting a file in the wrong tree means it is silently ignored — with no error message.

## Client / server side safety

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
- Test every feature with **`./gradlew :fabric:runServer`**, not just the client. For
  anything a Digimon does in a fight, that means the headless scenarios in [testing.md](testing.md).
