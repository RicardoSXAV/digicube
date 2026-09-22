# AGENTS.md — DigiCube

Rules and conventions for anyone (human or AI) writing code in this repository.
Read this before touching anything. If a rule here conflicts with a habit from
another Minecraft project, **this file wins**.

For Blender/model/animation work, first read
[the shared Blender session instructions](C:/Users/Administrador/Desktop/Coding/harness/BLENDER_SESSIONS.md).
Claim a task-owned session with the canonical harness `tools/blender_slots.py`;
it atomically assigns one of five sessions (`blender_a` through `blender_e`,
ports 9877 through 9881 respectively) using the current
Codex task ID. Use only that connection and its ownership guards, then save and
release it before handing work back. Never use the old shared `blender:9876`
connection or another task's slot. Use the canonical harness paths even from a
Git worktree so all chats coordinate through the same reservation registry.

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

**Design documents live outside the repository.** Mechanics designs (balance tables,
spawn rules, research) sit in `../design/`, a sibling of `digicube/` like `../harness`,
and are never committed. Read the relevant one before implementing a feature and update
it when a decision changes.

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
- Test every feature with **`./gradlew :fabric:runServer`**, not just the client. For
  anything a Digimon does in a fight, that means the headless scenarios in section 11.

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
- The attribute triangle is a **critical-hit chance**, not a damage multiplier:
  `CriticalHits` (base 10 %, favoured 25 %, countered 5 %, ×1.5) rolls on every Digimon hit
  through `DigimonEntity.damageAgainst` and the projectile impacts. Defence is the vanilla
  `ARMOR` attribute at half `base_defence` (`Progression.armor`). Keep those numbers there.
- Combat marks live on every `LivingEntity` (`CombatMarkState`, `MixinLivingEntity`): one packed,
  tracked int drives the emblems in `CombatMarkBadges`. **Crack** (`CrackMark`): fists and ground
  waves fill a 3-charge gauge, full = `digicube:cracked` for 6 s, +25 % damage taken from every
  source (a `@ModifyVariable` on `hurtServer`). Which attacks crack is by `DigimonAttack.Kind`.
  The readout has no spare bits left but one; read `../design/combat-marks.md` before adding a mark.
- A ground gait only looks planted when the clip's stride matches the ground covered: the phase advances by
  travel / stride (`DigimonGait`), so a stride far shorter than the species' real pace hits `max_playback_rate`
  and the feet slide (Golemon walked at the player's .216 blocks/tick on a .043 stride). Golemon's gait is
  generated, not keyed: `../harness/v2/tools/make_gait.py golemon` solves leg IK so the stance foot is fixed
  to the ground (drift < .05 model px, verified on the written keys) and writes four lattices on one phase:
  `walk`, `walk_back`, `strafe_left`, `strafe_right`. Forwards the foot rolls (heel edge, flat, front edge with
  the toes still flat; the edge on the ground is the fixed point) and the stance shortens into a bound above half
  amplitude, because his legs (45 px, ankle resting 13 px ahead of the hip) only sweep about 44 px under a
  pelvis at rest height: a flat foot and a dropped pelvis bent the supporting knee 105 degrees. Thigh yaw holds
  the knee's width. The clip is 27 ticks (`cycle_ticks`), keys every half tick. A gait with `side_stride` / `back_stride` is
  directional: the entity splits its movement in the body's frame into shares (`DigimonGait.directions`) and
  `NativeGroundModel` mixes the lattices by them. Change strides in the script and the species sheet together.
- Mounted combat is opt-in per species: `body.mount.rider_attacks` lists the attacks in slot order with `aim`
  (`sweep`/`line`/`shot`/`stream`), `input` (`tap`/`hold`), soft-target `cone`/`reach` and `move` (`RiderAttack`;
  Golemon, Garurumon, Greymon, Ikkakumon, Digmon). A rider has no target: `startRiderAttack` shares `beginAttack`
  with the AI, aims at the soft target or at `riderAim` (the ray from the rider's eye, which is the crosshair's ray
  in third person too), and commits every yaw through `DATA_ATTACK_YAW` because the rider's client owns the facing.
  Check with `DIGICUBE_SCENARIO=rider_checks` (`[rider] RESULT n of n casts landed`). The rider keeps
  their hands and casts the mount's target-free attacks (`riderAttacks()`, quickest first) with Q/E
  inside the command wheel (`PartyActionPayload.RIDER_ATTACK` -> `startRiderAttack`). Vanilla skips a
  ridden mob's server AI step, so `tick()` drives a rider's attack through `tickAttackTimeline`; never
  put attack timing back into `customServerAiStep` alone. `RiderAttacks` replaces vanilla's mount
  hearts with the attack tiles (`textures/gui/attack/<attack>[_off].png`, made by
  `harness/v2/art/pixel_sprites/_attacks/make_attacks.py`), each in its own 20-unit frame one unit above the
  experience bar, the 7x9 mouse glyphs together on their left in tile order (layout approved 20 September 2026).
  `RiderControls` is the direct input: with a
  free hand the mouse casts (attack hook + `MixinMinecraft.startUseItem`), R/G always, aimed attacks are
  held and released. The rider's client owns a ridden mount's position and facing, so turn rate, the
  swing's lunge and the strike's facing are played in `tickRidden`; the server owns targets, hits and
  the input buffer. The middle mouse button is the wheel's: `PartyClient.movePickBlock` makes B the default of
  vanilla's pick block (`KeyMappingAccessor`) and rebinds it once while it still shares the wheel's key. Getting on is an order, not a click: `mobInteract` no longer rides (the use button is the
  special attack, so the click that mounted also cast). `PartyClient.aim` picks the own party Digimon under the
  crosshair (24 blocks, hit parts included), it is outlined in blue (`AIM_OUTLINE`, set in `MixinEntityRenderer`),
  the wheel opens on it, and Ride (`PartyActionPayload.RIDE` -> `PartyManager.ride` -> `DigimonEntity.giveRide`,
  within `RIDE_REACH` = 6) takes Cancel target's place while it is not fighting. `RiderControls` ignores a button
  that was already down when the rider took the reins or closed a screen. The rider's leg pose is catalog data
  (`ground_models.json` `rider.pose` = leg pitch, splay, roll; Golemon sits, no pose = straight legs); only
  `MixinHumanoidModel` reads it, so the first-person camera is untouched. Water: a land Digimon floats at 55 % of
  its height (`getFluidJumpThreshold`), keeps every attack that does not need the ground (`wadingAttack`; the
  spike wave does), paddles over prey it has no path to (`DigimonAttackGoal`), and under a rider floats by itself
  and rises with the jump key (`tickRidden`). A sea mount (`body.mount.water_turn_rate` > 0, Seadramon) gets the
  full water controls (`seaMount()`): forward follows the view to 70 degrees, jump rises and the dive key (C,
  `DigimonEntity.localRiderDives`, client only) sinks, the surface holds the body unless it surges (`water_sprint`),
  a surge through the surface is a breach, the rider's air refills. A wrap is a rider move (`RiderAttack.Aim.GRAB`):
  the server picks the prey near the crosshair (`grabPick`, synced as `DATA_GRAB_PREY`), the client outlines it
  in magenta and lights the tile (dull = a press does nothing), one press lunges at it (`tickGrabLunge`) and wraps;
  through lunge and wrap `getControllingPassenger` is null (`wrapOwnsBody`) so the server owns the body as it does unridden. Use
  `rider()` for "who is in the saddle". Rule for mounts: the same pace ridden as alone, so new sheets leave
  `body.mount.speed` out (`ridePace`); a species that must travel slowly but fight at pace gets `tactics.fight_speed`
  (Seadramon: `base_speed` 0.07, fight 3.09; slowing its fights cost 30 points against Golemon). Design: `../design/mounted-combat.md`.
- How a species fights *between* attacks is data too: the optional `tactics` block on the
  species sheet (`DigimonTactics`: `hold_range`, `dodge_chance`, `reaction_ticks`, `strafe`,
  `lead_ticks`, `press_impaired`, `prefer_close`, `charge_distance`, `charge_speed`), read by
  `DigimonAttackGoal` and `BlindGuardGoal`. A species without one closes in, never dodges and
  keeps list order. The charge is a fight-only pace; a species' travel gait stays on its
  `locomotion` sheet (Golemon's walk is pinned by `LocomotionRegressionTest`).
  Dodging reads the opponent's wind-up (`activeAttack`/`attackTick`/`hitTick`; an aimed ground
  wave is sidestepped late, just before its aim locks) and inbound projectiles server-side; a
  wrap against a Digimon that fights us is timed (`DigimonEntity.wrapPunished`): a range-holding caster never walks
  into a brawler for it but wraps one that has caught it (within wrap range + 1), and it waits out a heavy move
  (power >= 1.0) that is under way or ready within `LOOMING_TICKS` = 10 (a wider window makes wraps rare and only
  open when the fight is already won: rounds with a catch must stay under 80 % wins). The move itself: the coil follows prey up to .6 blocks a tick, ordinary
  knockback does not shake the caster off (only a push of `ConstrictionMotion.BREAKING_PUSH` = 1.0 breaks the wrap
  and frees the prey), squeezes are `digicube:crush_attack` (bypasses armour; each of the four deals power .08 plus
  `ConstrictionMotion.CRUSH_SHARE` = 6 % of the prey's full health, so a hold costs about a third of any
  champion, never most of it), and release leaves Digimon prey winded (no attack for 20 ticks); an inked mob cannot take or keep a target beyond three blocks
  (`DCEffects.blindTo`) and acts on `lastSeenThreat` instead. `DIGICUBE_TACTICS=<species>:
  key=value,...;<species>:...` overrides knobs per process for sweeps. Design and numbers:
  `../design/combat-ai.md`.
- Attacks are data on the species too: `DigimonSpecies.attacks` is a list of
  `DigimonAttack` in **fallback priority order** (first ready + in range wins for ordinary
  move sets). Frost bite/stream pairs use `IceCombo` to choose from target mark,
  resistance, fuel and range; they reposition to clear the muzzle before emission.
  A frost stream with no bite beside it (Seadramon) never freezes: a second of landed
  contact charges **Cold** on the victim (`CombatMarkState`, slowed movement for
  `IceCombo.COLD_TICKS`, topped up by further contact, melted by fire), and its wrap may
  take any prey, Cold or not. Combat marks are tracked for every living entity in one
  packed int (`MixinLivingEntity`) and drawn as emblems above the head by
  `fabric/.../client/render/CombatMarkBadges`; add a mark there, not as a new synced field.
  Readiness also requires a viable attack path: `AttackGeometry` checks authored
  contact and launch clearance; `DigimonCombatPosition` finds reachable attack spots
  when elevation or cover makes the current position unusable. Preserve these checks
  when adding moves, and keep client/server mouth geometry identical.
  Timing, power and
  cooldown live there; `DigimonEntity` runs the timeline and `DigimonAttackGoal` picks
  the move. The client animation is looked up by the attack id path, so an attack named
  `digicube:claw` needs a harness animation called `claw` (plus `claw_mirrored` when it
  alternates sides). Author animations in `../harness` (README §3c), never by hand in Java.
  Animations ship as data: `assets/digicube/models/entity/<name>.animation.json`, read by
  `NativeAnimationSet` (linear keys, or `"interpolation":"catmullrom"` for Minecraft's own
  spline). A harness export that still produces a `*Animations.java` keyframe class is
  converted with `../harness/tools/native_animation.py java-to-native` and the class is
  never committed. Every export goes through that tool's `simplify` (bounded-error key
  reduction) and motion tables through `round-motion`; the `assetTest` build check fails on
  dense or unrounded tables, because they multiply the jar size for no visible gain.
  `assetTest` also fails on z-fighting: a species mesh (one with an `idle` clip) may have no
  same-facing faces on one plane that overlap in the rest pose (`MeshSurfaceCheck`; they
  flicker in game). All nine species are clean since 2026-09-18 and
  `AssetRegressionTest.KNOWN_COPLANAR_PAIRS` stays empty. Find with
  `../harness/v2/tools/coplanar_poses.py <mesh> <animation>`, repair an export with
  `fix_coplanar.py` next to it (rule and Blender-side check: `../harness/v2/docs/surfaces.md`);
  `install_assets.py` refuses such a mesh too.
- An authored burst can be **summoned at the target** instead of drawn from its caster: `anchor_lock_tick` in
  `authored_attacks.json` (Gotsumon's Comet Hammer). Its effect and volumes are exported relative to the landing
  point. `DigimonEntity.strikeAnchor()` (synced block + fraction) follows the floor under the target, led by its
  pace up to 1.5 blocks, until the lock tick and then stays: walking out from under the warning is the dodge.
  `AuthoredVolumeAttack` holds the rules (`landing`: floor within 3 blocks below the target, a swimmer is struck
  where it floats; `canReach`: sight of the target and the last three blocks of the way in open, along
  `anchorApproach`, the place the leading volume first hangs; `visible`: nothing falls through a roof; damage needs a clear line from the stone to the victim) and the renderer offsets the effect by the
  same point, so keep `culling_margin` at the attack's range. `contact_parts` lists effect cells a miss never shows
  (`DigimonAnimationEvents.CONTACT`), and a mirrored cast plays the effect's `effect_mirrored` clip when it has one.
  Victims are thrown away from the landing point with a small pop upward. `"particles"` (`StrikeParticles`, `stone`)
  adds server-sent trail, release, contact and landing particles and sounds to any authored volume; the first volume
  of the move is the one that trails. A style also voices the start of its move in place of the shared growl
  (`windUp`), so a small friendly Digimon does not sound like Golemon. A fist may carry margins around the drawn hand (Gotsumon: 0.35 ahead over its
  smear); `:fabric:nativeGotsumonTest` pins those, the drawn stone and the fists against the server's cuboids.
- Ownership: `DigimonEntity` implements `OwnableEntity`; `/digicube give <species> [player]`
  spawns a partner. Owned Digimon follow their tamer and join their fights.
- Slow projectiles must earn their hits: vanilla `ThrowableProjectile` collides as a thin
  ray (`ProjectileUtil.computeMargin`: 0 for two ticks, at most 0.3 blocks after), so a
  big fireball drawn one block wide would miss like a needle. `PepperBreathEntity` is the
  pattern: lead the target (`predictImpactPoint`), sweep the projectile's own box for
  hits before `super.tick()`, and bend a few degrees per tick toward the target while it
  stays ahead. Tune those constants before touching speed or hitbox size.

- Progression: every balance number of levels, XP and rest (the curve, stage yields, the
  level-gap multiplier, stat scaling, the damage-proportional split and the Digivice
  regeneration pulse) lives in
  `Progression`, next to the attribute triangle, and `:common:progressionTest` asserts
  the tables in `../design/wild-spawns-and-progression.md`. Never put a balance
  constant anywhere else. `DigimonEntity` holds `level` and `xp`; a wild Digimon's
  `DamageLedger` records the health it lost to each partner, and `ExperienceAward`
  splits the yield at the end of `hurtServer` on the killing blow (vanilla calls `die`
  from inside `hurtServer`, before the last hit could be recorded).
- Healing in survival: a partner stored in the Digivice regenerates slowly
  (`PartyManager.regenerateReserve`, one pulse per `Progression.RESERVE_REGEN_INTERVAL_TICKS`
  while the tamer is online, full in `RESERVE_FULL_HEAL_TICKS`); a defeated partner first
  rests `DEFEAT_REST_TICKS` (`PartyMember.restTicks`, saved, shown as a countdown in the
  Digivice) and then heals from zero; deployed partners heal only through play and
  `/digicube heal` skips the rest.
- Wild spawning is data too: `data/digicube/spawn_tables.json` lists one
  `data/digicube/spawn_table/<dimension>.json` per dimension, loaded and validated at
  startup by `BundledSpawnTableLoader` and covered by `:common:spawnTableTest`.
  `WildSpawner.tick` is loader-neutral and runs from the loader's end-of-level-tick
  hook; its settings are the `digicube:wild` saved data edited with `/digicube wild`.
  Wild Digimon are neutral: they only retaliate, and only attack-less species flee.
- The Digivice is handed out, never crafted: `StarterFlow.handDigivice` (common) runs first
  on every join and gives one to any non-spectator the `digicube:starters` data has not
  marked yet, so it is one per player per world and legacy tamers get theirs too.
- The first partner is a prompt, not a command: `StarterFlow` (common) decides
  eligibility (not a spectator, no `digicube:starters` record, no owned Digimon), writes
  the record first and then grants through `PartyManager.give`; the candidates and their
  level are `data/digicube/starters.json`, loaded by `StarterSet`. Common code sends
  payloads through `Services.PLATFORM.sendToPlayer`, so a command can open the prompt
  without a loader import. The Fabric adapter only registers payloads and join/leave
  hooks. `:common:starterTest` covers the rules; the `/digicube` root has no permission
  requirement, each operator subcommand carries its own.
- Screens follow the DigiCube GUI language in `fabric/.../client/gui/`: `DigiTheme`
  holds every colour and knob, `DigiPanels` draws chamfered frames, brackets, the data
  grid, data squares, platforms and buttons with `fill` only, and `DigimonPreview` draws
  a client-side `DigimonEntity` (never added to the level, never ticked; the screen bumps
  its `tickCount`, `markGuiPreview()` hides nameplate and shadow) through
  `GuiGraphicsExtractor.entity`. Widgets extend `AbstractButton` for focus and narration;
  screens do not pause; layouts are integer GUI units validated at 320 × 240.
- The Digivice (`fabric/.../client/digivice/`) is the device itself: `DigiviceScreen` draws the pale blue shell, its
  three blue keys (Q previous tab, E next tab, Esc power) and the display, on a fixed 480 × 270 plate that is centred,
  doubled on a large screen and shrunk to fit a small one. Controls are immediate: while drawing, a tab declares what
  can be clicked (`hit`) and scrolled (`wheel`) and the topmost declaration under the pointer wins. `DigiviceKit` holds
  the components (the primary action wears the device's blue key, the rest stay navy), `DigiviceArt` the palette and
  the pixel art, painted in code into `DynamicTexture`s on first use. **Analyzer** (`AnalyzerTab`, `AnalyzerIndex`)
  lists every species with search and attribute filter, shows the selected one on an LCD (icon, or the turning model
  through `DigimonPreview` on VIEW 3D) with stats, attacks and evolution line; profiles are the lang keys
  `digimon.digicube.<id>.profile`. A species is known when the tamer owns it, came from it or has reached it
  (`PartyManager.knownSpecies`, sent in the snapshot while the Digivice is open); unknown ones are silhouettes, and a
  development environment knows them all. **Digispace** (`DigispaceTab`) is where the party is managed: the reserve
  wanders a painted island (`DigispaceWorld` decides ground and props and paints the terrain, `DigispaceArt` the
  props, `DigispaceHerd` who stands where, `DigispaceCamera` zoom and pan), the cursor is a glove, a click selects a
  Digimon and slides its card up (Analyzer entry, and the Rookie origin of a Champion that has none), dragging one
  slides the party dock up and dropping it on a bay sends `SELECT`; with the dock pinned by the PARTY key a partner
  can be carried back to the island (`SELECT` -1, never the last one) or to another bay, where two partners trade
  places (`PartyRoster.select`). Where a Digimon stands is cosmetic and client-side; the server only knows the
  reserve. The snapshot page (`PartySnapshotPayload.PAGE_SIZE`) is large enough to show an ordinary reserve at once.
  `:fabric:digiviceTest` pins the island, the camera and the herd, and with `-PdigiviceEvidence=<dir>` writes the
  painted island and props as PNG.
- The developer panel is tooling, not a player feature, and not a command front-end: its one
  job is calibrating mechanics in play. It has no key. In a development environment the
  command wheel shows a gear in the bottom right corner (`DevGear`); resting the cursor on
  it opens `DevPanelScreen`, centred and translucent, and Esc closes it. The panel draws
  what `DevTabs` declares: tabs, each tab a scrolling column of section cards with an index
  on the left, each card ending in its own action bar, and a search in the title bar over
  every tab, section and config (`DevSearch`). `DevCatalog` is the single place that
  declares content. A plain tuning section is one chain (`tab(..).section(..).number(..)
  .toggle(..).choice(..).tuning(..)`) over `DevValue`s and needs no interface code; a special
  body implements `DevBody` and registers its controls on the `DevCanvas`
  (`BattleTestingBody`). Sections marked `example()` are placeholders whose values live only
  in the panel; wire one by giving its rows a `DevValue` that reads and writes the real
  number. `DevLayout` holds the arithmetic; `:fabric:devPanelTest` pins declarations, search,
  cards, the tab row and number rows. Server side: `DevPanel.handle` (common) admits, in a
  development environment only, an operator or the singleplayer world owner (a survival
  world made without cheats gives its host no permission level, and survival is where the
  balance testing happens); `DevActions` is the registry of server actions, each a
  `(server, player, args) -> reply` lambda. The two payloads (`DevActionPayload`: action id
  + argument tag, `DevStatePayload`: state tag + reply) never change when an action is
  added. Battle Testing (`BattleTest`) stages two wild Digimon in front of the player, keeps
  them on each other and lets them fight to a knockout with their real stats; the readout
  travels in the state tag every five ticks and `BattleReadout` draws it as a HUD bar.

Species are loaded from the bundled `data/digicube/species.json` catalog and
`data/digicube/species/*.json` sheets by `BundledSpeciesLoader`, on both sides at
startup. Add species as data; do not add species constructors to
`DigimonSpeciesBootstrap`. Attack ids reference shared moves in the bootstrap, in
priority order. The next architectural step is datapack reload support plus server
catalog synchronization; the current classpath loaders do not process `/reload`.

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
2. **Test headless whenever the outcome can be read without eyes.** If the change runs
   on the server and its result shows up in world state, entity state or the log, stage
   it with a scenario (below) and quote the verdict lines in the report. Combat, attack
   selection, navigation, effects, spawns, levelling, timers and server-side rules all
   qualify. Extend the runner when the situation you need does not exist yet; that is
   part of the change, not optional. A change that makes a scenario slower or fail is not
   done. Only what needs a screen or a real player's hands is left to manual testing.
3. Launch the updated build with `./gradlew :fabric:runClient` so it is ready to try
   (`--args="--quickPlaySingleplayer \"New World\""` opens the dev world directly).
4. Tell the user exactly what to try in game — the command, the item, the recipe — and
   leave the feel judgments (animation, pacing, balance) to them.
5. No new warnings in `latest.log` that this change introduced.

### Headless scenarios: the AI tests gameplay in the real game

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
  `[wrap-trace]` line every second (distance, level, status flags, fuel, planner state
  with its last failure, and the exact gate refusing a cast from the current position).
  The trace turns "it hesitates sometimes" into the name of a gate; fix the gate, rerun.
- Add a terrain to `CombatScenario.build` when a bug needs new geometry, and add a
  verdict rule when a new kind of move needs its own success criterion. Keep scenarios
  deterministic: fixed positions, healed combatants, no wild spawns nearby.
- The server EULA under `fabric/runs/server/eula.txt` is accepted; it is a run
  directory and stays git-ignored.

**Balance runs** (`common/.../dev/BalanceScenario`) answer a different question: not
"does the move work" but "who wins, and how fast". `DIGICUBE_SCENARIO=balance:<a>_vs_<b>`
fights real rounds to the death, no healing, both at `DIGICUBE_BALANCE_LEVEL` (20),
`DIGICUBE_BALANCE_ROUNDS` (20) of them in one server start with the game sprinting
(100 rounds in about 20 seconds). Sides alternate and each round opens from a random
distance (6–10 blocks), lateral offset and facing, because a duel that always starts
from the same spots is decided by whole hit counts and its "win chance" flips between
0 and 1 on a rounding. `DIGICUBE_NEUTRAL=true` drops the attribute triangle;
`DIGICUBE_TRIANGLE_UP` / `DIGICUBE_TRIANGLE_DOWN` override its multipliers for a sweep.
Read the `[balance] RESULT` line (win shares, duration mean/median/min/max, retargets)
and the two per-side lines (casts by move, crits and dodges per round, damage taken per
round, health kept when winning, the tactics in force). Ricardo's target for a same-level
neutral pair: about 50 % each (55–59 % is fine) and a 15-second mean. Tune from the
per-side lines: damage taken per round shows who is short of a kill, casts show which move
carries the fight. Use **300 rounds** (about 30 s) for a decision: 100-round runs of one
build have ranged 38–50 % for the same side. `DIGICUBE_BALANCE_TRACE=true` logs both
fighters every five ticks (distance, attack and tick, moving/still, target, effects); read
one traced round before touching a number, it is where "waits beside a Cold prey for a
wrap that is 160 ticks away" was found.

What an agent still must **not** do is drive the Minecraft window: no keystrokes or
chat commands typed into the client, no screenshots of it. Scenario runs are logs, not
screens. Launching the client with the fresh build so the user's own test is one click
away remains welcome.

Model verification **outside** the game is different and encouraged: rendering a
Blender model (the harness in `../harness` produces idle, front, side and action
renders) and looking at the images before handing the model over is expected.

If something could not be built, launched or run through a scenario, **say so
explicitly** rather than implying it was tested.

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
