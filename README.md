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

Once in game:

```
/give @s digicube:digivice
```

If you get a black-and-purple cube called `item.digicube.digivice`, a texture, model or
lang file is missing — see the item checklist in `AGENTS.md` section 6.

### When something breaks

1. Read `fabric/runs/client/logs/latest.log`. The real error is in the stack trace, usually
   near the phrase `Caused by:`.
2. A crash on startup is almost always a missing resource file or a bad mixin.
3. A missing texture or a wrong name is a resource-path problem, not a code problem.

You can leave the game running and use **Ctrl+F9** in IntelliJ to hot-swap changed method
bodies. Adding new classes, fields or registry entries still needs a restart.

---

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
- One item, `digicube:digivice`, registered end to end with model, texture and translation
- Digimon domain model: species, stages, attributes with a damage triangle, evolution branches
- Three placeholder species (Koromon, Agumon, Greymon) as a fixture
- A working mixin, as proof the pipeline runs
- CI that builds on every push

Not built yet, roughly in the order it should be tackled:

1. **Data-driven species loading** from `data/digicube/species/*.json`, replacing the
   hardcoded bootstrap. Do this before adding Digimon in bulk.
2. **The Digimon entity** — a tameable mob holding level, bond, training and current species.
3. **Rendering** with [GeckoLib](https://github.com/bernie-g/geckolib) (supports 26.2) and
   models made in Blockbench.
4. **The evolution engine** — evaluating `Evolution` branches and swapping species at runtime.
5. **The Digivice UI** — a screen showing your partner's stats.
6. **Spawning, taming, and DigiEggs.**
