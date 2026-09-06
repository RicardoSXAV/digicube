# DigiCube

A Digimon mod for Minecraft Java Edition. Find, tame, raise, train and digivolve
partner Digimon.

- **Minecraft** 26.2 · **Fabric** loader · **Java** 25
- NeoForge support is planned; the project is already structured for it.

> Unofficial fan project. Digimon is a trademark of Bandai. Do not ship assets you
> did not make.

---

## 1. First-time setup

You need three things. Nothing else.

### a) A JDK (Java Development Kit) 25

Java is the language Minecraft is written in. The JDK is the compiler plus the runtime.

```bash
winget install EclipseAdoptium.Temurin.25.JDK
```

Close and reopen your terminal afterwards, then check it worked:

```bash
java -version
```

You should see version `25`. If the command is not found, the terminal did not pick up
the new PATH — reopen it, or reboot.

### b) An IDE — IntelliJ IDEA Community Edition

Free, and by far the best editor for Minecraft mods. It decompiles Minecraft for you, so
you can ctrl-click any vanilla method and read its real source. That is your single most
useful debugging tool.

```bash
winget install JetBrains.IntelliJIDEA.Community
```

Open IntelliJ, choose **Open**, and select this `digicube` folder. It will detect Gradle
and start importing. **The first import takes 10–30 minutes** — it downloads Minecraft and
decompiles it. This happens once.

### c) Git

Already installed on this machine.

---

## 2. The tools, and what each one does

| Tool | What it is | Why you care |
|---|---|---|
| **Gradle** | The build system. Driven by `gradlew` / `gradlew.bat`. | One command compiles the mod, downloads Minecraft, and launches a test game. You never install it — the wrapper script fetches the right version. |
| **Fabric Loom** | A Gradle plugin for Fabric mods. | Sets up a runnable, deobfuscated Minecraft to develop against. |
| **ModDevGradle** | A Gradle plugin from NeoForge. | Gives the `common/` module a plain Minecraft to compile against, with no loader attached. |
| **Fabric Loader** | The thing that actually loads mods at runtime. | Players install this. |
| **Fabric API** | A shared library of hooks Fabric itself does not provide. | Nearly every Fabric mod depends on it, including this one. |
| **Mixin** | A library for patching vanilla classes at load time. | The escape hatch when no API exists. Use sparingly. |
| **Blockbench** | A free 3D model editor built for Minecraft. | Where Digimon models and animations will be made. |

### Why Minecraft has to be "decompiled"

Minecraft ships as compiled, obfuscated code — classes named `a`, `b`, `c`. To write a mod
you need readable names. The build tools download Minecraft, apply Mojang's official name
mappings, and decompile it into readable Java. That's the slow first build. Afterwards it
is cached.

Because we use **Mojang's official mappings**, the class names you see are `Identifier`,
`Level`, `Player`. Note that 26.x renamed `ResourceLocation` to `Identifier`, and Yarn names
(`World`, `PlayerEntity`) do not exist here at all — neither will compile.

---

## 3. The development loop

This is the cycle you will repeat all day.

```
edit code  ->  ./gradlew :fabric:runClient  ->  test in game  ->  read the log  ->  repeat
```

**Build the mod** (compile and package, no game):

```bash
./gradlew build
```

**Launch Minecraft with the mod already loaded:**

```bash
./gradlew :fabric:runClient
```

This opens a real Minecraft client. Create a world and test. You do **not** need to install
anything into your normal Minecraft — this is a separate, throwaway instance living in
`fabric/runs/client/`.

**Launch a dedicated server** (always test this too — server-side crashes are the most
common bug in mods):

```bash
./gradlew :fabric:runServer
```

In IntelliJ, the same two runs appear as **DigiCube Client (Fabric)** and
**DigiCube Server (Fabric)** in the run dropdown at the top right. Use those instead of the
terminal — you get breakpoints and a debugger.

### Testing what you built

In Creative mode, open the **DigiCube** tab, identified by the **Digivice** icon.
Use the Creative inventory's page arrows if the tab is on another page. The Digivice
is its first item and is also available in **Tools & Utilities**, immediately after
the compass, and through **Search Items**: search for `Digivice`.

Or obtain it with a command:

```
/give @s digicube:digivice
```

If you get a black-and-purple cube called `item.digicube.digivice`, a texture, model or
lang file is missing — see the item checklist in `AGENTS.md` section 6.

The **DigiCube** tab is the consistent home for the mod's growing item collection.
Add future player-facing items to its ordered `displayItems` list in
`fabric/.../registry/DCCreativeTabs.java`, alongside entries in suitable vanilla
categories. It uses Fabric's
[custom tab builder](https://docs.fabricmc.net/develop/items/custom-creative-tabs)
and [category event](https://docs.fabricmc.net/develop/items/first-item#adding-the-item-to-a-creative-tab),
initialized after the shared item registry on both client and dedicated server.
Tab contents include items in Creative search and are built without a tick handler.

### When something breaks

1. Read `fabric/runs/client/logs/latest.log`. The real error is in the stack trace, usually
   near the phrase `Caused by:`.
2. A crash on startup is almost always a missing resource file or a bad mixin.
3. A missing texture or a wrong name is a resource-path problem, not a code problem.

You can leave the game running and use **Ctrl+F9** in IntelliJ to hot-swap changed method
bodies. Adding new classes, fields or registry entries still needs a restart.

---

### Digivice collection and party

Right-click a **Digivice** to open your collection (air, a block or an entity).
Select a partner in the collection, then click one of the **three party slots** to
deploy it. An occupied slot swaps its current partner into reserve. Select an active
partner and press **Recall** to store it. The arrows or mouse wheel change collection
pages; the screen supports keyboard focus and tooltips with name, stage and health.
The world keeps running while the Digivice is open.

```
/give @s digicube:digivice
/digicube give koromon
/digicube give agumon
/digicube give greymon
/digicube give agumon
```

The first three partners fill the party; the fourth goes into reserve. Duplicate
species are separate individuals. Existing owned Digimon join the collection as
their chunks load; the first three fill empty slots and extras go into reserve.
Wild `/digicube spawn` Digimon are unaffected. The HUD shows the same pixel icons
beside the left of the hotbar, with health bars and numbered empty slots. It moves
away from the offhand slot and uses a vertical strip at narrow GUI sizes. F1 hides it.

Health bars update at the end of the server tick when damage, healing or maximum
health changes (normally within 50 ms, plus network latency). Updates are batched
per owner and contain only changed health values. Idle parties send no health
packets; full entity saves keep their once-per-second cadence. The Digivice also
updates its health bars and tooltips without rebuilding buttons for health packets.

Collections and selections belong to the **player UUID in the world save**, survive
death/reconnect, and work across dimensions. Full entity data, including names,
health, effects and attack cooldowns, is retained in reserve; storage does not heal
or reset cooldowns. Active partners recall on logout/chunk unload and deploy beside
their tamer when safe space is available. An amber HUD dot means a selected partner
is waiting for space. Dismount before swapping or recalling a mount. A Digimon that
dies remains marked **Defeated** in the collection and cannot be deployed; revival
is not implemented.

Storage is server-authoritative and scoped to the world (`digicube:parties` saved
data). A deployment generation on each entity prevents old chunk copies from
duplicating recalled partners. Only the owner's current collection page and party
summaries are sent to their client. Future species automatically use
`assets/<namespace>/textures/gui/digimon/<species>.png`, with a neutral fallback
if a resource pack omits an icon.

`gradlew.bat build` includes the headless `:common:partyTest` regression suite.
Manually try swapping, recalling, repeated species, a large collection, saving and
reloading, portals, mounting, health preservation and two different players on a
dedicated server. The dedicated server's EULA must be accepted manually before
`:fabric:runServer` can start its world. Existing dev partners are tied to the dev
username/UUID, so keep the same `--username` when testing across client launches.

### Model authoring with Blender MCP

The local [model harness](../harness/README.md) owns geometry, pixel textures and
keyframe animations. Koromon's source is `../harness/digimon/koromon.py`; its saved
Blender scene and preview renders are under `../harness/out/koromon/`.

Rebuild through Blender MCP:

```python
SPECIES = "koromon"
exec(open(r"C:/Users/Administrador/Desktop/Coding/harness/blender/run.py", encoding="utf-8").read())
```

Copy the generated `KoromonModel.java` and `KoromonAnimations.java` into
`fabric/src/main/java/com/digicube/fabric/client/model/`, and `koromon.png` into
`common/src/main/resources/assets/digicube/textures/entity/digimon/`, then build.
Edit the harness source to change geometry or motion; keep the exports reproducible.

Koromon uses a 128×64 atlas, thin folded ear tips and a looping 16-tick hop with
squash, stretch and delayed ear motion. Movement controls animation speed and weight;
standing still fades the hop out. This is a visual walk cycle; collision and navigation
still use the shared Digimon entity dimensions.
The shaded blowing expression replaces the normal face on ticks 5–18 using explicit
visibility switches. Facial planes export only their front polygon and sit clear of
the body surface; keep this setup when regenerating to avoid depth flicker.

Try `/digicube give koromon`, then walk away to see your partner hop after you.
Use `/digicube give <species> [player]` to give a partner to yourself or a selected
player; `/digicube spawn <species>` spawns a wild Digimon. Both require operator
permissions, and the console must specify a player for `give`.
Koromon's only attack is **Bubble Blow**: a seven-bubble visual volley, 8-block
range, 40-tick (2-second) cooldown, and a 24-tick blowing animation. The shot leaves
on tick 10. It deals one normal attack's damage per volley, does not ignite targets,
protects its tamer and allies, and pops on impact. Pepper Breath keeps its 100-tick
cooldown. Hit a nearby hostile mob to have your partner join the fight.
During the windup, Koromon turns his whole body toward the predicted bubble aim point
and holds that facing through the blow. The projectile uses that same point at release.

The bubble rig and its flight/pop clips live in `../harness/digimon/bubble_blow.py`.
Export it with the same `run.py` command using `SPECIES = "bubble_blow"`; copy its
model and animation Java beside Koromon's, and its PNG to
`common/src/main/resources/assets/digicube/textures/entity/projectile/`.
`../harness/blender/preview_bubble_blow.py`, run after Koromon's export, produces an
editable combined attack scene and frames under `../harness/out/koromon/`.

Use `/digicube spawn agumon` alongside it to check species model selection.
In-game testing is manual; the harness provides front, side, three-quarter and
airborne renders plus animation filmstrips for inspection outside Minecraft.

### Tsunomon

Tsunomon has a stepped orange body, a cream heart-shaped face, red-orange eyes,
thin fur planes and a curved slate horn. Its original 128×64 pixel atlas includes
the normal smile and shaded blowing expression. The harness source is
`../harness/digimon/tsunomon.py`; run `blender/run.py` through Blender MCP with
`SPECIES = "tsunomon"` to rebuild its scene, renders, model and animations.

It imports Koromon's body keyframes for the same 16-tick hopping walk and 24-tick
Bubble Blow, including the face swap on ticks 5–18. It uses the same shared bubble
attack, projectile, aim, damage, range and cooldown. Its horn moves with the body.
The 32×32 party icon source is `../harness/art/pixel_sprites/tsunomon/sprite.json`.

Try `/digicube give tsunomon`, walk away to see the hop, and hit a nearby hostile
mob to see the bubble attack. Open the Digivice to check the collection preview
and party icon. If the party is full, deploy Tsunomon from the collection first.
Also check partner behavior on a dedicated server.

Species now load from the bundled `data/digicube/species.json` catalog and one JSON
sheet per species at startup, on both client and server. Attack lists reference
shared move ids; omitted `body` uses the original dimensions and scale. Existing
species retain their stats, attacks, evolution order and Greymon's mount settings.
Tsunomon has no evolution branch yet. Datapack reload and server catalog sync are
still future work. `:common:speciesTest`, included in `build`, checks the migration
and rejects malformed species content.

### Gabumon

Gabumon uses the approved revised model: a fuller belly with its fitted emblem,
sturdier legs, and yellow arms holding the striped coat through shared shoulder,
elbow and wrist joints. The 256×64 pixel atlas, idle pose, 32-tick walk and 16-tick
anime run come from the approved Blender files. The coat follows his hands; both
arms sweep behind him when running. Movement fades back to the approved idle.

```
/digicube give gabumon
/digicube spawn gabumon
```

The first command adds a partner to your Digivice; deploy him into a party slot if
all three slots are already occupied. Walk away to see him follow, sprint to see him
accelerate and run, then release sprint and stop to check the transitions and idle.
He starts following at four blocks and settles within two. His follow speed changes
from 1.15× to 1.65× while his owner sprints, with a short visual walk/run blend.
The server controls this state; another player's sprint does not trigger it.
Other species retain their existing follow behavior. Gabumon is a [Data Rookie](https://digimon.net/reference_en/detail.php?directory_name=gabumon)
with starter stats matching Agumon. His model scale is 0.6, with a 0.95×1.45-block
collision box. This release adds the model and locomotion; attacks, evolution
branches and a dedicated party icon are not authored yet. The Digivice uses its
existing fallback icon. Dedicated-server partner behavior should be checked too.

The source is `../harness/digimon/gabumon_locomotion.py`. Approved idle, walk and run
files live in `../harness/out/gabumon_locomotion/`; previous revisions are retained.
Export through Blender MCP in a background process (the script opens all three
saved files), or save any interactive Blender edits before running it there:

```python
exec(open(r"C:/Users/Administrador/Desktop/Coding/harness/blender/export_gabumon_locomotion.py",
          encoding="utf-8").read())
```

This exporter verifies geometry, UVs and paint against the approved scenes, samples
their saved native animation curves at sixteenth ticks, and writes
`GabumonModel.java`, `GabumonAnimations.java` and `gabumon.png` under
`../harness/out/gabumon_locomotion_release/`. Copy the Java files to
`fabric/src/main/java/com/digicube/fabric/client/model/` and the atlas to
`common/src/main/resources/assets/digicube/textures/entity/digimon/gabumon.png`.
It exports one polygon per flat sheet to avoid depth flicker. Run
`gradlew.bat -I ../harness/tools/verify_gabumon_locomotion.init.gradle :fabric:verifyGabumonLocomotionExport`
to compare the compiled Minecraft model with all sampled Blender poses and check
walk/run blending and a clean idle reset.
The transition audit (`blender/audit_gabumon_game_blends.py`) supplies its Blender
comparison poses. During the game-only fade, hands clear the thighs before the
legs step, and feet stay above ground. The approved idle and full cycles are unchanged.

The optional species `locomotion` object supplies `follow_start_distance`,
`follow_stop_distance`, `walk_speed` and `run_speed`. Speeds are navigation modifiers;
equal values disable sprint-following. Omitting the object preserves the original
10/3-block distances and 1.15× walking speed.

### Greymon and riding

Greymon uses the approved reference model: a three-horned skull mask, inset red eyes,
painted orange shading, navy tiger stripes, and three-digit hands with flat claws.
The 256×256 atlas includes the fix for helmet flicker. Its 40-tick walk preserves the
approved foot placements, weight shifts and delayed arm/tail motion. Animation speed
tracks distance traveled; a full cycle covers 2.4 blocks at the authored scale.

Source: `../harness/digimon/greymon_reference.py`. Open the approved scene at
`../harness/out/greymon_reference/greymon_reference.blend`
and export through Blender MCP:

```python
exec(compile(open(r"C:/Users/Administrador/Desktop/Coding/harness/blender/export_greymon_reference.py",
                  encoding="utf-8").read(), "export_greymon_reference.py", "exec"))
```

The exporter backs up the open scene, verifies its geometry, UVs and texture against
the approved source, and includes the baked foot corrections. Copy `GreymonModel.java`,
`GreymonAnimations.java`, and `greymon.png` from `../harness/out/greymon_reference_release/`
to the mod locations above. Preview renders remain under `../harness/out/greymon_reference/`.
The species id and model layer remain `greymon`, so existing partners use the new model.

Try this in a large open area:

```
/digicube give greymon
```

Right-click your Greymon to sit on the rear of its skull. Use **WASD** to steer and
**Shift** to dismount. It automatically steps up one-block rises; it has no charged
jump. Only the owner can mount, and there is one passenger seat. Walk away while
unmounted to see it follow with the walk animation. Use `/digicube spawn greymon`
for a wild one, or `/digicube give agumon` to compare scale.

Physical size and riding settings live on `DigimonSpecies.body`: Greymon's model is
scaled by 1.5, with a 2.5×4.6-block collision box. The crown seat is 4.540426 blocks
above its feet and 0.507345 blocks forward, measured through the new neck/head pose.
Horns reach about 5.44 blocks; horns and tail extend beyond the main collision box.
While mounted, the rendered crown stays beneath the fixed rider attachment as the
body walks, and body yaw follows the ridden yaw. Existing ownership, movement speed,
one-block stepping, and safe dismount behavior are retained. Check riding, ownership,
save/reload, and dismounting on a dedicated server too; that startup requires accepting
its EULA manually.

#### Greymon's attacks

Greymon prioritizes **Mega Flame** whenever it is ready and has a clear shot. It
inhales, opens its jaw, fires on tick 16, recoils, then settles over a 40-tick clip.
The flame's mouth flare, flickering sheets, hot core and ten-tick impact breakup
are all authored in Blender. Its cooldown is **160 ticks (8 seconds)** and range is
**3.4–16 blocks**. The shot leads moving targets, collides as a 1.2-block volume,
damages visible opponents within 1.8 blocks of impact and burns them for 6 seconds.
It protects the tamer and allies and does not destroy terrain or place fire blocks.

**Great Antler** fills the shorter gaps: a braced crouch, lowered front horn,
2.4-block drive, impact and recovery. Its cooldown is **50 ticks (2.5 seconds)**,
clip length is 36 ticks, and starting range is **2.4–6.2 blocks**. The actual horn
segment is checked on ticks 11–19, with one damaging hit and knockback per use.
Movement respects walls and stops before an unsupported drop. Greymon backs up if
the target is too close to bring its horn to bear.

The creature clips live in `../harness/digimon/greymon_reference.py`; the fire rig
and charge/flight/burst clips live in `../harness/digimon/mega_flame.py`.
Run `../harness/blender/build_greymon_attacks.py` through Blender MCP to export the
model, all animations, flame assets and contact profiles. Copy the Greymon Java
files from `out/greymon_reference_release/`, flame Java/PNG from `out/mega_flame/`,
and `mega_flame.json` / `great_antler.json` from `out/greymon_attacks/` into the
corresponding model, projectile texture and `data/digicube/attack_motion/` folders.
These contact profiles are bundled data; datapack reload support is not implemented.
`preview_greymon_attacks.py` produces editable combined scenes and preview frames;
`package_greymon_attacks.py` packages the frames as GIFs. The motion design uses
[anticipation and stored energy](https://www.animationmentor.com/blog/anticipation-the-12-basic-principles-of-animation/)
and the [rhino's heavy head and horn](https://animals.sandiegozoo.org/animals/rhinoceros)
as references, adapted to Greymon's bipedal anatomy.

To test, give yourself a Greymon and hit a nearby hostile mob in an open area.
Watch Mega Flame first, followed by Great Antler while the flame is cooling down.
Mounting cancels an attack and reserves control for the rider; dismount before
testing autonomous combat. Also check allies near an impact, walls, moving targets,
and mounting during the windup. Dedicated-server combat still needs a manual test.

The harness's `tools/verify_greymon_attacks.init.gradle` runs the compiled model
against the exported mouth and horn markers at fractional ticks, checks the attack
order/cooldowns/range, flame clips, idle reset and the rider seat during late attack
events. `tools/verify_greymon.init.gradle` retains the approved walk and mount checks.

## 4. How the project is organised

```
common/   the mod itself: domain model, items, registration, assets. No loader code.
fabric/   a thin adapter that boots common/ on Fabric.
buildSrc/ shared build logic.
```

Nearly everything you write goes in `common/`. The `fabric/` module only holds the entry
point and the few things Fabric does differently. That split is what makes adding NeoForge
later a new folder rather than a rewrite.

Full conventions, naming rules and architectural constraints are in
**[AGENTS.md](AGENTS.md)** — that file is also the instruction set for AI coding agents
working in this repo.

---

## 5. Shipping a build

```bash
./gradlew build
```

The player-facing jar is `fabric/build/libs/digicube-fabric-26.2.jar`. Ignore the
`-sources` and `-javadoc` jars.

To try it in your real Minecraft: install Fabric Loader for 26.2, drop that jar plus
[Fabric API](https://modrinth.com/mod/fabric-api) into your `mods/` folder.

---

## 6. Current state

Working:

- Build system for Minecraft 26.2 on Fabric, structured for NeoForge later
- Platform abstraction via `ServiceLoader`
- A dedicated DigiCube Creative tab with the Digivice as its icon and first item
- One item, `digicube:digivice`, with model, texture, translation and Creative access
  through DigiCube, Tools & Utilities and Search Items
- Persistent Digivice collection, three active party slots, and a matching pixel-icon HUD
- Digimon domain model: species, stages, attributes with a damage triangle, evolution branches
- Five species (Koromon, Tsunomon, Agumon, Gabumon, Greymon), loaded from bundled JSON sheets
- Owned partner entities that follow their tamers and join combat
- Harness-authored models and animations rendered with Minecraft's native model API
- A working mixin, as proof the pipeline runs
- CI that builds on every push

Not built yet, roughly in the order it should be tackled:

1. **Datapack species reload and synchronization**, extending the bundled JSON loader.
2. **Raising and training** — individual levels, bond and training progression for partners.
3. **The evolution engine** — evaluating `Evolution` branches and swapping species at runtime.
4. **Taming and DigiEggs**, beyond the existing spawn/give commands.
