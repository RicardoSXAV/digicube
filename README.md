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

Garurumon's approved 32×32 party icon appears in the Digivice and party HUD. Its
editable source and approved face-v8 snapshot live in `../harness/art/pixel_sprites/`.

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
collision box. His approved 32×32 party icon appears in the Digivice and party HUD;
its source is `../harness/art/pixel_sprites/gabumon/sprite.json` (face v3).
Evolution branches are not authored yet. Dedicated-server partner behavior should be checked too.

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

#### Gabumon's attacks

Gabumon prioritizes **Blue Blaster**: a half-second inhale followed by up to four
seconds of continuous icy-blue flame, with an eight-block range. A narrow mouth jet
opens into overlapping blue flame tongues that travel outward, expand, slow and
break apart. Gabumon plants a staggered stance and aims his body and head at the
target; yaw and pitch have turn limits. Living-entity yaw is used consistently by
the model, flame renderer and damage geometry, including east/west headings.
Individual tongues stop at terrain without stretching the whole plume. The damage
volume widens from the mouth with the flame. Damage pulses every half second at 0.4× attack power;
there is no burning, freezing status, terrain damage or hit knockback.

Blue Blaster uses an individual fuel tank instead of a cooldown. Fuel drains only
while emitting and refills while resting or using the horn. An exhausted tank needs
six seconds to refill completely before firing again. An interrupted breath can
resume with its remaining fuel; death, lost targets, obstructed shots and targets
leaving range stop the stream. Fuel and the exhausted-tank lock survive saving,
recalling and redeploying. The long attack timeline is synced for players who begin
tracking Gabumon halfway through a breath.

**Horn Attack** is the short-range fallback: brace, lower the horn, drive up to
0.95 blocks, then recover. Its starting range is 0.65–2.3 blocks, cooldown is
26 ticks (1.3 seconds), and animation lasts 22 ticks. The exported horn segment
checks contact on ticks 7–12 and hits once per use at 0.7× attack power. Its damage
type suppresses vanilla hurt knockback as well as the extra impulse Great Antler
uses. Movement respects walls and unsupported drops.

Use `/digicube give gabumon`, deploy him, and hit a nearby hostile mob. Watch the
continuous blue breath, then horn strikes during recharge. Fight targets on every
side of Gabumon, including east/west and above/below him. Also try a moving target,
cover, allies in the stream, and recalling/redeploying during recharge. Existing
Gabumon partners gain the attacks automatically. Dedicated-server combat is a manual
check; the dev server's EULA must be accepted before it can open a world.

The attack source is `../harness/digimon/gabumon_attacks.py`; flame geometry, pixel
paint and flow animation are in `../harness/digimon/blue_blaster.py`. Through Blender
MCP, run `../harness/blender/launch_gabumon_attacks.py`. It launches the reproducible
`build_gabumon_attacks.py` export in a background Blender process, preserving the
interactive scene and the approved idle/walk/run files. Copy `GabumonModel.java`,
`GabumonAnimations.java` and both attack-motion JSON files from `out/gabumon_attacks/`,
plus `BlueBlasterModel.java`, `BlueBlasterAnimations.java` and `blue_blaster.png` from
`out/blue_blaster/`, to their corresponding mod model, motion and projectile-texture
folders. Use this combined exporter for future Gabumon releases so attacks remain
included alongside the approved locomotion.

`build_blue_blaster.py` exports the flame alone; `preview_blue_blaster_v2.py` renders
the current flame and aimed animation against a moving practice target. The older
`preview_gabumon_attacks.py` retains the Horn Attack preview. Set `GABUMON_SCRIPT` to
the desired script before running the launcher. Source references and the limits
of the art-directed flow approximation are in `../harness/digimon/blue_blaster_research.md`.
`tools/verify_gabumon_attacks.init.gradle` adds the
`:fabric:verifyGabumonAttacks` task: compiled-model mouth/horn alignment at fractional
ticks and varied aim, planted soles, usable horn range, idle reset and valid flame
poses. It also verifies the actual flame renderer transform across 150 headings/pitches,
target alignment at varied heights/ranges, and clipping individual tongues. Standard
`build` includes common-side cardinal aim, plume volume, transport and fuel regressions.

### Garurumon and riding

Garurumon uses the approved Minecraft-style wolf model, including its fitted eyes,
tapered muzzle and paws, closed mouth and stepped pixel teeth. The native 16-tick
run is the only moving gait; stopping restores the standing pose. Its cadence
follows traveled distance, so it accelerates with movement instead of sliding
through a fixed-speed loop.

Use `/digicube give garurumon`, then right-click your partner to mount. **WASD**
steers and **Shift** dismounts. It runs at the same fast follow pace whether the
owner walks or sprints, and has ridden speed 0.5 with one-block stepping. Wild
Garurumon can be created with `/digicube spawn garurumon`; only an owner can ride.
He uses Freeze Fang and Howling Blaster while unmounted; existing partners gain them automatically.

Model scale is 1.0: its back is about 2.1 blocks high, with a 1.9 × 2.8-block body
box. The rider sits between the shoulder and hip plumes, 2.1875 blocks above the
feet and 0.375 blocks behind the origin. A wider seated leg pose fits the wolf's
back. The rider's visible position follows the animated seat while the physical
attachment retains the existing server-authoritative mounting controls.

Approved model/texture source: `../harness/digimon/garurumon.py`; saved native clips:
`../harness/out/garurumon_visible_teeth/`. Export through Blender MCP with
`../harness/blender/export_garurumon_release.py`; review scenes and videos are in
`../harness/out/garurumon_release/`. That exporter preserves the refined surfaces
as mesh JSON as well as exporting the saved native animation curves. Regenerating
only the old cuboid source loses the approved refinements.

The compiled export check is
`gradlew.bat -I ../harness/tools/verify_garurumon.init.gradle :fabric:verifyGarurumonExport`.
It verifies native geometry/UVs, the full run, partial movement, idle reset and the
moving rider seat. In game, check walking away from Garurumon, mounting, steering,
stopping, one-block rises and dismounting, including armor and another player's
view. Dedicated-server mounting still needs a manual test; the dev server's EULA
must be accepted before it can open a world.

#### Garurumon's attacks

**Freeze Fang** crouches, lunges and snaps the real jaw. A successful hit applies
an **8-second Ice Mark**. Its cooldown is 1.4 seconds, matching the full animation, with no knockback that
would push the marked enemy away from the combo.
Marked living mobs show a crisp snowflake medallion above their heads (above a name
when present). It disappears when the mark expires or is consumed, respects terrain
occlusion and F1, and is synchronized to observing players.

**Howling Blaster** uses Gabumon's fuel mechanics with a larger, brighter blue ice
flame: a four-second tank and eight-second empty-to-full refill. Landing **one second**
of flame contact on a marked target freezes it for up to **3 seconds** and consumes the mark.
Garurumon stops emitting, finishes his exhale, then closes for another bite. That bite
deals **50% bonus damage** and shatters the ice on a successful hit. A seven-second
freeze-resistance timer starts with the freeze and remains after shattering, preventing
repeated stun-locks. Misses and blocked flames spend
fuel without advancing the freeze. Separate targets, casters and casts keep separate
contact counts. Neither attack burns terrain or damages allies.

The move pair follows **bite → mark → breath → freeze → shattering bite**.
Garurumon approaches reachable prey to start the combo, backs up only when needed to
clear his muzzle, and pursues frozen prey for the follow-up. A conversion needs at least
28 fuel ticks available (20 contact plus a travel/miss allowance), so a clean freeze
uses roughly a quarter tank. He keeps biting during freeze resistance and refills
during exhale/bite animations. Unmarked ranged damage remains a fallback for enemies
he cannot approach. Positioning changes with the combo phase, including an immediate
replan when a mark is applied or a target freezes.

All Digimon now check actual attack geometry before committing. Horns and bites
rehearse the exported contact path, including body clearance and ground support;
shots check the mouth's line of fire. Breath aims at a clear point inside the upper
body and includes the moving mouth and aim blend. When an attack cannot connect,
navigation searches for a reachable firing/striking position, including stepping
down to the target's level. Existing attack timing, hitboxes and turn limits remain
in force, so quick targets can still dodge.

Try `/digicube give garurumon`, deploy him, then hit a sturdy enemy. Watch the bite,
the snowflake badge on its target, repositioning, sustained breath and brief freeze.
Repeat against cows on level ground and one block below, then compare Gabumon,
Greymon, Agumon and Gomamon. Watch for stepping down or choosing a clear ranged shot.
Also try cover, a moving target, an enemy very close to his chest, and recalling
or mounting during emission. Mounting always cancels combat. Existing partners gain
both moves. Full dedicated-server combat remains a manual check after EULA acceptance.

The reproducible Blender source is `../harness/blender/build_garurumon_attacks.py`.
Claim a task-owned Blender session, run that script and `build_howling_blaster.py`,
then install with `../harness/tools/install_garurumon_attacks.py`. This preserves the
approved native model and gallop and adds the separate generated attack holder.
Saved clips, complete previews and verification reports are in
`../harness/out/garurumon_attacks/`. The compiled attack check is
`gradlew.bat --init-script ../harness/tools/verify_garurumon_attacks.init.gradle :fabric:verifyGarurumonAttacks`.
The badge's editable pixel grid is `../harness/art/pixel_sprites/ice_mark_badge/sprite.json`;
render it with `../harness/tools/render_pixel_sprites.py`, then copy its native PNG to
`assets/digicube/textures/entity/status/`. Source grids and enlarged previews stay in the harness.

### Gomamon on land and in water

Use `/digicube give gomamon`, deploy him from the party, and walk away to see his seal
shuffle, a step slower than Agumon, driven by his rear paws. Enter deeper water and swim away: he
switches to fast three-dimensional swimming, with gradual dives, turns and stops.
He keeps swimming to catch a distant owner instead of teleporting out of the water.
Use `/digicube spawn gomamon` for a wild one. He is not rideable.

The two-second swim cycle combines a broad forepaw power stroke, feathered recovery,
a streamlined glide and delayed motion through the hips and tail. Stroke intensity
and cadence ease with speed; entering and leaving water blends with the approved
walk. The cuboid model, pixel texture and head-surface cleanup are preserved.
Aquatic movement is enabled by species data through `locomotion.swim_speed` (blocks
per tick); Gomamon uses `0.46`. On land the aquatic move control applies his base speed
`0.08` and follow multiplier `1.3` once, unlike the squared pace of vanilla walkers, which
puts him just under Agumon's walk. Missing swim speed keeps the existing land behavior.
Vanilla steers bodies wider than a block to a block corner but only counts the block centre
as reached, which left Gomamon spinning at the end of a path; Digimon navigation also
accepts the steering target as arrival.

Authoring source: `../harness/digimon/gomamon_swim.py`. The approved native model and
walk, glide and swim files are in `../harness/out/gomamon_swim/`, including the review
GIFs. Export the saved Blender files with
`../harness/blender/build_gomamon_attacks.py`; it includes the native locomotion
export and preserves native faces and UVs while adding the attack clips.
Run `gradlew.bat --init-script ../harness/tools/gomamon-verification.gradle build
:fabric:verifyGomamon` to check native-to-compiled poses, transitions and idle reset.
In game, check following on dry ground, diving into deep water, turning, stopping,
surfacing and returning up a bank. Dedicated-server aquatic movement also needs a
manual check after the dev server's EULA is accepted.

#### Gomamon's attacks

**Marching Fishes** sends a curling turquoise wave containing five original red,
yellow, pink, blue and green fish. Their tails and fins swim independently inside
the translucent crest; a broken foam lip, spray and wake follow the wave. On impact
the fish scatter and the water collapses into a fading splash. Gomamon gathers with
both paws, throws on tick 14, and settles over a 32-tick performance. The move has an
11-block starting range and a 90-tick cooldown. It deals 0.55× attack damage with
1.35 knockback, applying damage once to visible nearby opponents.

The full 2.2×1.25-block wave sweeps against enemies and block collision shapes. It
leads moving targets, turns by up to five degrees per tick, and expires after two
seconds of flight. Walls, its tamer and allies are protected; it does not place water,
ignite entities or alter terrain. Terrain contact produces a harmless splash.

**Claw Attack** is the close-range fallback: lift, rake inward and recover, alternating
paws on successive uses. It hits on tick 6 of a 16-tick animation, has a 22-tick
cooldown, and deals 0.65× attack damage. Attack entry and recovery blend with his
existing walk and swim poses. Existing Gomamon partners gain both attacks.

Use `/digicube give gomamon`, deploy him, and hit a nearby hostile mob. Try moving
targets at 4–10 blocks, then close combat during the wave cooldown. Also check water,
walls and corners, small mobs, allies near the wave, and recalling/redeploying during
cooldown. Dedicated-server combat remains a manual check after accepting its EULA.

Attack sources: `../harness/digimon/gomamon_attacks.py` and
`../harness/digimon/marching_fishes.py`. Rebuild with Blender MCP using
`blender/build_gomamon_attacks.py` and `blender/build_marching_fishes.py`; inspect
their saved scenes and full-motion filmstrips under `out/gomamon_attacks/` and
`out/marching_fishes/`. `blender/preview_gomamon_attacks.py` creates the combined scene.
Run `gradlew.bat --init-script ../harness/tools/verify_gomamon_attacks.init.gradle
:fabric:verifyGomamonAttacks` to compare compiled locomotion, attack curves, blends
and reset poses against Blender. `build` also checks wave steering and collision.

### Ikkakumon: standing mount and fast swimming

Use `/digicube give ikkakumon`, then right-click your partner to mount. The rider
stands on the broad back, slightly to one side so the horn leaves the crosshair
clear. **WASD** steers; in water, look up/down while moving to ascend/dive.
**Shift** dismounts. `/digicube spawn ikkakumon` creates a wild Ikkakumon.

The approved cuboid model, painted atlas, paired push/glide walk, water idle, swim
and finished 32×32 party sprite come from the harness. The model retains its
2.75-block fur crown and 3.88-block horn tip. Attacks are deferred until authored.
Walking responds to travel but is capped at one push/glide cycle per second, keeping
the approved ground speed without frantic leg motion. A sampled amplitude table
keeps the feet above the floor during slow movement. Swimming has an independent cruise speed, and
the standing attachment rises gradually with the water posture.

The editable native file, rider-eye views and playback previews are in
`../harness/out/ikkakumon_release`. Reproduce the asset export with
`../harness/blender/export_ikkakumon_release.py` through a reserved Blender MCP
session, then install with `../harness/tools/install_ikkakumon_release.py`.
`gradlew.bat -I ../harness/tools/verify_ikkakumon.init.gradle :fabric:verifyIkkakumonExport`
checks the compiled poses against Blender, including partial walks and water blends.

### Tentomon: biped walking and short flights

Use `/digicube give tentomon` and deploy him from the Digivice. He walks on his two
rear legs, keeping both pairs of arms free. Sprint away or get about 10 blocks ahead
to see him open his shell and fly to catch up. Nearby danger can trigger an escape
flight too. `/digicube spawn tentomon` creates a wild one for testing.

Flight has a separate fuel reserve: up to 12 seconds, with the last 2.4 seconds
reserved for landing. He needs at least 40% fuel and three seconds of rest before
another takeoff. A fully empty tank refills in 20 seconds on dry ground. Recall and
reload preserve fuel. A blue line beneath his party health bar shows the reserve
while he is tracked nearby. The settings live in `species/tentomon.json` under
`locomotion.flight`, and can be reused by other flying species.

He checks space for the open shell, uses flying pathfinding, and looks for a dry,
supported landing. With no fuel he descends; he cannot hover forever. Flight also
ends when leashed or entering water. Try low ceilings, changing direction, an
obstacle between him and his owner, depletion/recovery, and recalling/redeploying
mid-flight. Real-world navigation and dedicated-server behavior need manual testing;
the current dev server requires EULA acceptance before it can open a world.

The approved sources are in `../harness/out/tentomon_biped_locomotion/`. Reproduce
the installed mesh, packed atlas and saved animation curves with Blender MCP using
`../harness/blender/export_tentomon_release.py`. Run the compiled comparison with
`gradlew.bat --init-script ../harness/tools/verify_tentomon.init.gradle :fabric:verifyTentomonExport`.
`build` also runs the fuel, decision and steering regression suite. The full design
is in [../design/rookie-flight.md](../design/rookie-flight.md).

Tentomon currently has his approved idle and locomotion. His attacks will be added
after authoring; he is not yet in the starter or natural spawn tables.

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

### Wild Digimon, levels and XP

Every Digimon has a level (1–50) and XP, and species base stats now reach the
entity: max health is `base_health × (1 + 0.04 × (level − 1))` and attack is
`base_attack × (1 + 0.03 × (level − 1))`, so a Koromon and a Greymon finally differ
in health and damage. Partners saved before this change load at level 1. The
design, balance tables and the multiplayer research behind them are in
[../design/wild-spawns-and-progression.md](../design/wild-spawns-and-progression.md),
kept beside the repository like the harness;
every constant lives in `Progression` and `:common:progressionTest` reproduces the tables.

XP comes only from defeating wild (unowned) Digimon. A wild Digimon keeps a ledger
of the health it lost to each partner. When it dies, its yield
(`stageYield × (level + 4) / 2`, times a level-gap multiplier between 0.25 and 1.5
for each partner) is split in proportion to the damage each partner dealt, never
below 1 XP per contributor. Contributors must be alive, within 64 blocks and have
hit within the last 60 seconds. The tamer's own hits earn no XP. A level-up plays
the vanilla level-up sound and a burst of green particles, heals the health gained
and tells the owner in chat. Wild Digimon also drop a few vanilla orbs for the
tamer whose partner hit them.

Wild Digimon spawn on their own: once every 20 seconds per dimension, 24 to 48
blocks from a random player, from `data/digicube/spawn_table/overworld.json`. Each
entry names a species, a weight, a level range, a pack size, biomes (ids or
`#tags`), `land` or `water` placement and `any`, `day` or `night`. Tables are listed
in `data/digicube/spawn_tables.json` and validated at startup. At most 4 wild Digimon
per player and 24 per dimension exist at once; spawning respects the `spawn_mobs`
game rule and wild Digimon despawn like animals. They are neutral: they never start
a fight, a species with attacks retaliates when hurt, and a species without attacks
flees. Garurumon is not in the bundled spawn table yet. Wild Digimon carry a
`Lv 7 Koromon` nameplate; the party HUD shows `Lv7` beside each icon and the
Digivice card and tooltip show level and XP progress.

Operator commands:

```
/digicube spawn koromon 3          wild level-3 Koromon at your feet; it stays put
/digicube give agumon 5            level-5 Agumon partner; or /digicube give agumon <player> <level>
/digicube level @e[type=digicube:digimon,distance=..5] 12
/digicube xp @e[type=digicube:digimon,distance=..5] 100
/digicube wild status              settings, wild count, cap and the last attempt's outcome
/digicube wild on|off              toggle spawning; saved per world
/digicube wild interval 200        one attempt every 200 ticks (minimum 20)
/digicube wild cap 4 24            per player, per dimension
/digicube wild distance 24 48      the spawn ring around the anchor player
/digicube wild try                 force one attempt and report why it did or did not spawn
/digicube wild clear               remove every wild Digimon in this dimension
/digicube wild debug on|off        log every attempt
```

To test: `/digicube give agumon`, then `/digicube spawn koromon 3` and hit the
Koromon once so Agumon joins in. A few Koromon should level Agumon, with the sound,
particles and chat line; the HUD level and the Digivice tooltip follow. Walk through
plains, forest, taiga, savanna and along a coast for natural spawns, and run
`/digicube wild status` when nothing appears: it names the failing step. Levels must
survive a save and reload and a Digivice recall. On a dedicated server, two players'
partners hitting the same wild Digimon should split its XP by damage dealt; that
remains a manual check after the server EULA is accepted. `:common:progressionTest`
and `:common:spawnTableTest` run as part of `build`.

### Choosing your first partner

A player who enters a world for the first time is asked to choose a partner about a
second after the terrain appears: the **Partner Link** panel opens in the middle of a
still-visible world. One viewport shows a living 3D model on a lit platform, turning
slowly, with its name and kind beside it; a strip of icon tiles underneath lists the
candidates (Agumon, Gabumon, Gomamon), and pages with arrows once there are more than
six. Hover a tile to preview it, click it (or press **1**, **2**, **3**) to select, then
press **Link with …** (or Enter) to confirm. Hovering the viewport makes the Digimon
face you and follow the cursor. The partner joins the party at level 1 through the
normal deployment, the HUD tile fills and chat says who your partner is. **Esc** or the
corner cross puts the choice off: chat shows a clickable `/digicube starter` that
reopens the prompt, and it returns on the next join anyway. Server answers, such as a
refused choice, appear in amber under the panel. The world keeps running behind the
panel; Tab and the arrow keys move between tiles, and the vanilla narrator reads them.

The prompt is shown to survival, adventure and creative players who have no starter
record in this world and own no Digimon at all, so existing worlds where partners came
from `/digicube give` stay quiet. Spectators are skipped. One starter per player per
world; the choice is validated server-side and stored in the `digicube:starters` saved
data. The candidates and their level come from `data/digicube/starters.json`.

The screen is also the pilot of the DigiCube GUI language, drawn entirely with
primitives from `fabric/.../client/gui/DigiTheme` (colours and knobs) and `DigiPanels`
(chamfered frames, corner brackets, the green data grid, breathing blue data squares,
platforms, buttons). The design, the layout rules and what to judge in game are in
[../design/starter-selection-and-gui-language.md](../design/starter-selection-and-gui-language.md),
kept beside the repository. The Digivice screen keeps its old look until this one is
approved.

```
/digicube starter                  reopen the choice while still eligible (everyone)
/digicube starter open [player]    operator: offer it even to a player who already has partners
/digicube starter reset <player>   operator: forget the choice so it can be made again
/digicube starter list             operator: who chose what
```

To test: create a new world and wait a second; the screen should appear once. In an
existing dev world run `/digicube starter open` to force it. Try Esc and the chat
link, keyboard-only selection, a resize while it is open, and choosing while a mob is
nearby. On a dedicated server each player gets their own prompt. `:common:starterTest`
runs as part of `build`.

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
- Eight species (Koromon, Tsunomon, Agumon, Gabumon, Garurumon, Gomamon, Greymon, Tentomon), loaded from bundled JSON sheets
- Owned partner entities that follow their tamers and join combat
- Levels and XP: species stats scale with level, and defeating wild Digimon splits XP by damage dealt
- Neutral wild Digimon spawning from bundled spawn tables, controlled with `/digicube wild`
- A first-partner prompt on entering a world, and the Partner Link screen that pilots the DigiCube GUI language
- Harness-authored models and animations rendered with Minecraft's native model API
- A working mixin, as proof the pipeline runs
- CI that builds on every push

Not built yet, roughly in the order it should be tackled:

1. **Datapack reload and synchronization** for species and spawn tables, extending the bundled JSON loaders.
2. **Raising and training** — bond, weight and training progression for partners, on top of levels.
3. **The evolution engine** — evaluating `Evolution` branches (`min_level` is now meaningful) and swapping species at runtime.
4. **Taming and DigiEggs**, beyond the existing spawn/give commands and wild spawns.

### Kabuterimon: flying partner

`/digicube give kabuterimon` adds the blue adult partner with the approved native
model. Right-click your partner to ride. Space launches/climbs; normal movement
and mouse look steer. Hold forward and look down to dive: sustained steep descents
build speed, which carries through a smooth pull-up. Climbs and hard turns spend
that momentum. Approaching the ground brakes and lands automatically, with more
braking distance at higher descent speeds. Releasing movement hovers. Shift dismounts as usual.
A single stamina bar shows the available flight reserve. There are no additional
flight keys or potion effects.

The rider is centered behind the horn. Flying uses a forward body lean, tucked
legs, swept arms, turn banking and a dive angle that follows actual movement, with continuous native takeoff/flight/landing
clips. The reusable flight profile lives in the species sheet; future aerial
species supply the same native clip/presentation contract without new physics.
Source, packed Blender scenes, multi-angle reviews and real-client previews are in
`../harness/out/kabuterimon_release/README.md`.

The dive revision has passed deterministic handling and compiled pose checks;
its in-game feel awaits manual review. Try gaining altitude, diving with W, then
leveling the view while holding W to carry speed. `body.mount.flight.dive` supplies
the reusable terminal speed, dive acceleration and momentum-loss settings.
