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

**Run a staged situation without playing** (the fastest way to check gameplay logic, and
what an AI agent uses to verify its own changes; fights are staged today, and the same
runner grows a setup step and a verdict rule for each new kind of bug):

```powershell
$env:DIGICUBE_SCENARIO='seadramon_vs_golemon@steps'; .\gradlew.bat :fabric:runServer --console=plain
```

The dedicated server builds a platform high above the world, spawns the two Digimon
eight blocks apart, lets them fight with both healed every tick, logs each phase as
`[scenario] ...` and stops itself with a `[scenario] PASS ...` or `FAIL ...` line in
`fabric/runs/server/logs/latest.log`. Name it `<caster>_vs_<prey>` with an optional
terrain: `@flat` (default), `@steps` (one-block ledges and bumps), `@ledge` (the prey a
block higher) or `@water` (a pool, both swimming), plus `+duel` if the prey should fight
back instead of standing passive and `+behind` to face it away from the caster.
A caster with a wrap move passes when it freezes, captures and releases
its prey and reports the freeze-to-capture ticks; any other caster passes after three
landed hits. Seadramon also logs a `[wrap-trace]` line every second naming the exact
gate that is holding its wrap back. One run takes about 40 seconds.
[agents/testing.md](agents/testing.md) has the rules for when a change must go through these.

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
lang file is missing — see the item checklist in [agents/conventions.md](agents/conventions.md#checklist-adding-an-item).

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

Garurumon's 32×32 party icon appears in the Digivice and party HUD.

`gradlew.bat build` includes the headless `:common:partyTest` regression suite.
Manually try swapping, recalling, repeated species, a large collection, saving and
reloading, portals, mounting, health preservation and two different players on a
dedicated server. The dedicated server's EULA must be accepted manually before
`:fabric:runServer` can start its world. Existing dev partners are tied to the dev
username/UUID, so keep the same `--username` when testing across client launches.

### Koromon

Models and animations are data, not Java: the mod reads each model's mesh, texture and
clips from `common/src/main/resources/assets/digicube/`, the clips from
`models/entity/<name>.animation.json` through `NativeAnimationSet`. The `assetTest` step
of `gradlew.bat build` rejects dense animation tables (keys that interpolating their
neighbours reproduces) and motion tables with more decimals than they need.

Koromon uses a 128×64 atlas, thin folded ear tips and a looping 16-tick hop with
squash, stretch and delayed ear motion. Movement controls animation speed and weight;
standing still fades the hop out. This is a visual walk cycle; collision and navigation
still use the shared Digimon entity dimensions.
The shaded blowing expression replaces the normal face on ticks 5–18 using explicit
visibility switches. Facial planes have only their front polygon and sit clear of the
body surface, which keeps them from flickering.

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

Use `/digicube spawn agumon` alongside it to check species model selection.

### Tsunomon

Tsunomon has a stepped orange body, a cream heart-shaped face, red-orange eyes,
thin fur planes and a curved slate horn. Its original 128×64 pixel atlas includes
the normal smile and shaded blowing expression.

It shares Koromon's body keyframes for the same 16-tick hopping walk and 24-tick
Bubble Blow, including the face swap on ticks 5–18. It uses the same shared bubble
attack, projectile, aim, damage, range and cooldown. Its horn moves with the body.
It has its own 32×32 party icon.

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

Gabumon's model has a fuller belly with its fitted emblem,
sturdier legs, and yellow arms holding the striped coat through shared shoulder,
elbow and wrist joints. He has a 256×64 pixel atlas, an idle pose, a 32-tick walk
and a 16-tick anime run. The coat follows his hands; both arms sweep behind him when
running. Movement fades back to the idle.

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
collision box. His 32×32 party icon appears in the Digivice and party HUD.
Evolution branches are not authored yet. Dedicated-server partner behavior should be checked too.

Each flat sheet is a single polygon, which keeps it from flickering. When he starts or
stops moving, his hands clear his thighs before the legs step, and his feet stay above
the ground.

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
26 ticks (1.3 seconds), and animation lasts 22 ticks. The horn segment
checks contact on ticks 7–12 and hits once per use at 0.7× attack power. Its damage
type suppresses vanilla hurt knockback as well as the extra impulse Great Antler
uses. Movement respects walls and unsupported drops.

Use `/digicube give gabumon`, deploy him, and hit a nearby hostile mob. Watch the
continuous blue breath, then horn strikes during recharge. Fight targets on every
side of Gabumon, including east/west and above/below him. Also try a moving target,
cover, allies in the stream, and recalling/redeploying during recharge. Existing
Gabumon partners gain the attacks automatically. Dedicated-server combat is a manual
check; the dev server's EULA must be accepted before it can open a world.

`build` includes common-side cardinal aim, plume volume, transport and fuel regressions.

### Garurumon and riding

Garurumon is a native model at scale 0.35: about two blocks at the back, 1.8 × 2.1 blocks of body box, the nose well
ahead of it. Every gait is planted: a four-beat walk that becomes a trot as it speeds up, a slow walk backwards and a
side-step for its footwork in a fight, and a rotary gallop with two suspensions that quickens its cadence the faster it
runs. It breaks from the trot into the gallop at once, as a wolf changes gait, and back under a slightly lower pace; it
gallops along its own length through a bend instead of stepping aside, and turning on the spot it steps round one paw
at a time, its spine bent into the turn and its head leading, each standing paw still on the ground. It turns as a big
wolf does, gathering into the turn and braking out of it: a wild one looks round first and its body follows, and one
sent behind itself steps round before it sets off rather than snapping about or walking sideways. On ice its paws keep part of their grip: it gathers pace more slowly and
runs no faster than on stone, skids a few blocks braced on its legs when it stops, throwing up chips of ice, and drifts
through a hard bend, galloping on along its own length. Its leap gathers, drives off the hind legs, tucks the forelegs
and lands forefeet first. Its long tail swings on its own: it trails a start, swings out of a turn, lifts as the wolf
falls from a leap and whips down on landing, rides the steps of a hillside, and settles into a slight droop when it
stands. Each paw is heard as it lands, a soft swish through grass, moss or leaves and the ground's own step anywhere
else (snow, ice, stone): a walk's four beats, a trot's pairs, a gallop's rhythm, and the leap's push-off and landing,
each step softer the quicker they come, so a sprint is no louder than a trot.

Use `/digicube give garurumon`, then right-click your partner to mount. **W** trots at about 0.32 blocks a tick;
holding **sprint** builds into the gallop over a second and a half, up to about 0.84. **Space** leaps: about 2.4
blocks up from a standstill, and at a full gallop about 4.6 up and 10 blocks long, keeping its momentum through the
air; a leaper lands six blocks of fall unhurt. **A/D** turn it into the way it goes, **S** reins it back, **Shift**
dismounts. Wild Garurumon can be created with `/digicube spawn garurumon`; only an owner can ride.

#### Garurumon's attacks

**Freeze Fang** is a pounce: a short crouch, then a dash of about five blocks with the jaws open and the icy canines
bared, the jaws snapping shut on the first body they meet, where the dash stops. It has two uses, each back two seconds
after it is spent. Mounted, the quick button pounces where you aim, pitch included, bent a little toward an enemy near
the crosshair; pressed mid-leap it skips the crouch and dives onto (or rises to) its prey, and the leap's pose takes
over again after it. A bite fills 45 % of the victim's **Freeze** gauge.

**Howling Blaster** is a breath of frost puffs from a five-second tank that refills in seven: they leave the mouth
with the aim's speed and the body's own, slow down, slide along whatever they hit (splashing over a wall met head-on)
and fade out, so sweeping the aim bends the stream like a hose. The flame is a slender stream of glowing blue blocks,
narrow at the jaws, about a block across partway out and ending in thin tips, and it strikes about as wide as it looks.
Held steady it is one body; swept fast it opens into streaks that each fly their own way, and its blocks never stretch.
It sounds like wind howling through ice, with ice chips cracking in it. Mounted, hold the special button to breathe
while running or in the middle of a leap; the stream follows your mouse at once and the neck turns to it. Contact fills the Freeze gauge a little every tick and hurts every half second;
still water it hits freezes into frosted ice and fire goes out. Snowflakes, ice and cold smoke ride and burst from it.

**Freeze** is a round medallion over the victim: a grey snowflake that rises lit as the gauge fills. Full, the victim
is Frozen for two and a half seconds (it cannot move or act; its rim drains as the ice thaws), then resists frost for
four seconds (a small slashed snowflake beside its other emblems). A Freeze Fang on a Frozen victim shatters the ice
for double damage. Fire thaws it.

Wild, Garurumon circles at a gallop, breathes on its prey from a few blocks out until it freezes, pounces up close and
on Frozen prey, and leaps at prey on a ledge above (or just out of reach) to pounce on it from the air.

Try it in a flat field against a sturdy enemy: sprint and leap, pounce from the ground and from the top of a leap,
breathe across two enemies, freeze one and shatter it, and breathe on a pond or a fire. `DIGICUBE_SCENARIO=garurumon_checks`
runs all of that headless.

All Digimon now check actual attack geometry before committing. Horns and bites
rehearse their contact path, including body clearance and ground support;
shots check the mouth's line of fire. Breath aims at a clear point inside the upper
body and includes the moving mouth and aim blend. When an attack cannot connect,
navigation searches for a reachable firing/striking position, including stepping
down to the target's level. Existing attack timing, hitboxes and turn limits remain
in force, so quick targets can still dodge.

### Gomamon on land and in water

Use `/digicube give gomamon`, deploy him from the party, and walk away to see his seal
shuffle, a step slower than Agumon, driven by his rear paws. Enter deeper water and swim away: he
switches to fast three-dimensional swimming, with gradual dives, turns and stops.
He keeps swimming to catch a distant owner instead of teleporting out of the water.
Use `/digicube spawn gomamon` for a wild one. He is not rideable.

The two-second swim cycle combines a broad forepaw power stroke, feathered recovery,
a streamlined glide and delayed motion through the hips and tail. Stroke intensity
and cadence ease with speed; entering and leaving water blends with the walk.
Aquatic movement is enabled by species data through `locomotion.swim_speed` (blocks
per tick); Gomamon uses `0.46`. On land the aquatic move control applies his base speed
`0.08` and follow multiplier `1.3` once, unlike the squared pace of vanilla walkers, which
puts him just under Agumon's walk. Missing swim speed keeps the existing land behavior.
Vanilla steers bodies wider than a block to a block corner but only counts the block centre
as reached, which left Gomamon spinning at the end of a path; Digimon navigation also
accepts the steering target as arrival.

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

`build` checks wave steering and collision.

### Ikkakumon: a sea mount that walks

Use `/digicube give ikkakumon`, then aim at your partner and choose Ride on the command
wheel. The rider sits astride the mane behind the head, centred, legs down into the fur, and
leans with the body. **Shift** dismounts. `/digicube spawn ikkakumon` creates a wild Ikkakumon.

On land he is a slow, heavy walrus: a four-beat walk with every foot planted. **W** walks and
**S** reins back. **A**/**D** turn him into the way he goes, since he never side-steps.
Holding **sprint** breaks him into a galumph, rumbling as he sets off.

In water:

- At the surface he ferries you: head and mane out, paddling with his foreflippers, his rider
  dry. Glancing down or up, he stays at the surface.
- **W** swims ahead and **A**/**D** steer him, as on land.
- **Sprint** paddles harder along the surface, or under water is the torpedo dash.
- Looking down past 30 degrees, or **C**, dives; under water he swims where you look, and
  **Space** rises.
- A double tap of **Space** is a barrel roll toward the way you are turning.
- Sprinting up through the surface, he breaches.
- Under water, turning hard, he banks into the turn.
- Coming up after a dive, he blows a spray.
- A bow wave, a splash at every paddle, a wake, bubbles and splashes follow him.
- His voice is his own: a quiet, low hum, higher when he is hit, deeper as he attacks.

### Seadramon: a sea serpent

Use `/digicube give seadramon`, then aim at your partner and choose Ride on the command wheel.
`/digicube spawn seadramon` creates a wild one. Its long body lies along the path its head
took: round a turn the whole body curves through it, it slithers without sliding sideways on
land, and it never cuts through the ground or a wall beside the way it went. Its shadow lies
under its body, not under its reared head.

On land it slithers faster than you walk, and faster still sprinting. Its head turns only so
far off its body: looking back standing turns its head, and going on it curls round after it.
It climbs walls as high as half its body (four and a half blocks), its body draping over the
edge after its head, and lowers itself down them; a higher wall stops it. It climbs out of
the water up any shore it is pushed at, a beach behind a shallow shelf too. On steps and
broken ground its body lies over the edges instead of down every riser, and it moves
smoothly, never curling up or shaking.

In water:

- At the surface it holds its neck and you out of the water, its back breaking the surface.
- **W** swims ahead and **A**/**D** steer it; it carves wide turns at speed and tight ones slowly.
- **Sprint** surges; sprinting up through the surface it leaps out and dives back in.
- Looking down past 30 degrees, or **C**, dives; the body follows the head down.
- A double tap of **Space** rolls it round its length.
- Hold the quick attack to breathe Ice Blast while it keeps swimming: its head turns toward
  the crosshair, and where the frost plays on the sea it freezes floes of ice that melt again.

Its attacks, on land and in water:

- **Ice Blast** (hold the quick attack): a jet of ice shards that bends as you sweep it and
  trails behind as you move, for four seconds on a tank; it slows what it touches, works
  under water, and heaps snow where it strikes. Once the tank runs dry its tile refills
  clockwise with the seconds left.
- **Constriction** (press the special attack): prey the wrap can take near the crosshair is
  outlined in magenta and the tile lights. Press and Seadramon strikes at it from up to eight
  blocks and throws its body round it in loops that fit its size, squeezing four times before
  letting go. Its body has to go all the way round: a chicken, a player or a cow, a spider,
  never a Golemon.

`DIGICUBE_SCENARIO=seadramon_checks` checks wild swimming (no spinning on the spot), a
channel's corner, land, its land pace, its neck, climbing (head on, from a standstill, aslant,
a wall of logs), a pit, lowering itself down a face, out of the sea onto beaches and banks, a
ridden turn, Ice Blast on the move and the floes.

### Shellmon: a shell that crawls, swims and spins

`/digicube give shellmon` adds the pink Digimon in its spiral shell, about as big as a Greymon; `/digicube spawn
shellmon` a wild one. Aim at your partner and choose Ride on the command wheel: you sit on the flat top of its spire.
Its mouth opens and shuts in its own time.

On land it drags its shell along on its hands, one after the other (**W**), and turns on the spot by stepping round on
them as you look aside; **A**/**D** turn it into the way it goes. Holding **sprint** heaves with both hands together,
faster. It does not jump.

In water it is a slow, steady swimmer: at the surface it floats like a buoy and paddles, the shell rocking with each
stroke; **sprint** paddles harder. Looking down past 30 degrees, or **C**, dives; under water it swims a breaststroke
where you look, and **Space** rises.

Its attacks (left and right mouse with an empty hand, or R and G):

- **Hydro Pressure** (hold the left): it bows its head and a jet of water blasts from its crown wherever you aim, even
  while it crawls. The water's first blow shoves back whatever it strikes, and while the jet plays on it it is driven
  back along the ground, harder the longer it stays in, but only so far: the jet loses its force with distance, and big
  Digimon budge less. Burning targets and fires go out, and what the jet strikes stays wet for a while (puddled on
  floors, dripping from walls) before it dries.
- **Drill Shell** (hold the right): it withdraws into its shell (a short cast), then spins up while you hold (the tile
  fills); let go and it spins off where you look, striking everything it runs into. A short hold gives a slow spin that
  is easy to steer; a full one is fast and hits far harder, but turns slowly and skids wide. Walls throw it back. While
  it is in its shell it takes only a third of the damage. When it stops it slows to a halt with its opening ahead and
  comes back out, a little dizzy.

`DIGICUBE_SCENARIO=shellmon_checks` rides it headless and checks its paces, the swim and both attacks.

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

`build` runs the fuel, decision and steering regression suite.

Tentomon currently has his idle and locomotion. His attacks come later; he is not yet
in the starter or natural spawn tables.

### Greymon and riding

Greymon is a native model at 0.45 scale (about 4 blocks tall, a 2.3 x 4.0 box; horns and tail reach out of it) with a
skull helmet, a jaw that opens wide and a six-link tail. Every gait is planted: its walk strikes heel first and turns
on its feet as it goes, round a bend or on the spot; its run is a dinosaur's, the trunk level and leaning into its
pace, the tail out behind, the arms tucked, and it keeps its cadence from a jog to a sprint. Heavy footfalls shake the
ground and the rider's view, and a ridden Greymon roars as it breaks into its run.

```
/digicube give greymon
```

Right-click your Greymon to sit on its back behind the neck. **W** walks at about 0.29 blocks a tick; holding
**sprint** builds into the run over almost two seconds, up to about 0.44. **Space** leaps: at a run about 3 blocks up
and 4.6 long, keeping the run's pace through the air. **A/D** turn it into the way it goes (it steps round on its feet,
walking or standing), **S** reins it back, **Shift** dismounts. Use `/digicube spawn greymon` for a wild one.

#### Greymon's attacks

**Great Antler** (the quick button) is a horn charge: Greymon coils, swings its head down until the helmet's
horns level ahead and drives along the crosshair, bending toward an enemy near it. Aim up to gore a flyer or down at
small prey: the body tips a little and the neck does the rest. Pressed at a run it charges at once, keeping its pace;
pressed in a leap it lances down (or up) from the air. The ram bursts where the horns strike.

**Mega Flame** (the special button) is a fireball: Greymon rears back while the ball forms in its jaws, then spits it
where you look. It can be loosed while walking, running or in a leap (the legs keep going while the neck aims). The
ball bursts where it strikes, catching bodies within 1.8 blocks, and sets them alight (a Burn). Wild, Greymon holds
its range, circles and fires on the move, and charges what comes close.

To check the headless side: `DIGICUBE_SCENARIO=greymon_checks` (see `agents/testing.md`).

### Leomon: a swordsman on foot

Leomon is a native model at 0.32 scale (about 2.9 blocks to the crown, a 1.2 x 2.75 box): a lion-headed fighter with a
mane of locks, a six-link tail, a fang necklace and the Lion Sword sheathed across the back of his belt. He is not a
mount: he fights on his own and on your orders. Every gait is planted and he turns on his feet as he walks; his run is a
long driving stride; he leaps at a run, crouches under blows and, running, dives into a forward roll under a shot.

```
/digicube give leomon
```

An Elecmon digivolves into Leomon at level 20 (choose him on its tree; DarkTyrannomon is the other route).
`/digicube spawn leomon` makes a wild one, and the developer panel's Battle Testing stages him against any species.

#### Leomon's attacks

**Lion Sword** draws the sword from his back with the left hand and holds it for five seconds: up close he steps in with
a three-blow combo (a diagonal cut, a rising backhand, a heavy finisher that cuts twice), leaning each cut down at a small
foe; from four to eight blocks at a run he leaps into a lunge, and from a leap he plunges. Then he sheathes it, and the
move rests for four seconds.

**Beast King Fist** has no cooldown: each sword hit fills its gauge (a slash 25, the stab 35 of 100: the amber bar under
his health). Full, he punches with a flaming lion's head round his right fist and throws the target far (up close, from
a run or from a leap), or, farther off, sends the lion's head flying: whole within 4 blocks, under half its strength at
18. Until the gauge is full an order on it is refused (CHARGING n %).

Order either move from the command wheel (Q and E); turn its AUTO off to keep it for your orders. To check the headless
side: `DIGICUBE_SCENARIO=leomon_checks` and `agility_checks` (see `agents/testing.md`).

### Wild Digimon, levels and XP

Every Digimon has a level (1–50) and XP, and species base stats now reach the
entity: max health is `base_health × (1 + 0.04 × (level − 1))` and attack is
`base_attack × (1 + 0.03 × (level − 1))`, so a Koromon and a Greymon finally differ
in health and damage. Partners saved before this change load at level 1. Every
balance constant lives in `Progression`, and `:common:progressionTest` pins the tables.

XP comes only from defeating wild (unowned) Digimon. A wild Digimon keeps a ledger
of the health it lost to each partner. When it dies, its yield
(`stageYield × (level + 4) × 0.75`, times a level-gap multiplier between 0.5 and 1.5
for each partner) is split in proportion to the damage each partner dealt, never
below 1 XP per contributor. Contributors must be alive, within 64 blocks and have
hit within the last 60 seconds; a partner that fell loses its share. The tamer's own
hits earn no XP.

A defeated partner rests 30 seconds in the Digivice, then comes back to its party
slot with 1 health point. Partners heal on their own, full in two minutes, in the
Digivice or out in the world from five seconds after their last fight; right-click
one with any food to heal it faster (bread heals a quarter of its health). A Digivice left behind, on the ground after a death for
example, takes the party in, and picking it up brings the same party back out. A level-up plays
the vanilla level-up sound and a burst of green particles, heals the health gained
and tells the owner in chat. Wild Digimon also drop a few vanilla orbs for the
tamer whose partner hit them.

Wild Digimon spawn on their own: once every 10 seconds per dimension, up to three
spots 24 to 48 blocks from a random player, from
`data/digicube/spawn_table/overworld.json`. Each entry names a species, a weight, a
level range, a pack size, regions or biomes, `land` or `water` placement and `any`,
`day` or `night`; each Digimon of a pack rolls its own level, and a species already
near the spot is rarer there. Tables are listed in `data/digicube/spawn_tables.json`
and validated at startup. At most 8 wild Digimon within 96 blocks of a player and 48
per dimension exist at once; spawning respects the `spawn_mobs` game rule. A wild
Digimon stays for about five minutes after the last player walked away, and goes at
once past 128 blocks. They are neutral: they never start a fight, a species with
attacks retaliates when hurt (on the tamer's partners first, and on the tamer only
when the tamer struck it and no partner is left), and a species without attacks
flees. Wild Digimon carry a
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
/digicube wild cap 8 48            around each player, per dimension
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
platforms, buttons).

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

AI coding agents start at **[AGENTS.md](AGENTS.md)**: the rules every task follows and a map
of the topic guides in [`agents/`](agents/), which hold the full conventions, naming rules and
architectural constraints. People can read them the same way.

---

## 5. Shipping a build

```bash
./gradlew build
```

The player-facing jar is `fabric/build/libs/digicube-fabric-26.2-<version>.jar`, with
`version` from `gradle.properties`. Ignore the `-sources` and `-javadoc` jars. Version
numbers, tags and the release steps are in [agents/releasing.md](agents/releasing.md).

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
- Models and animations as JSON data, rendered with Minecraft's native model API
- A working mixin, as proof the pipeline runs
- CI that builds on every push

Not built yet, roughly in the order it should be tackled:

1. **Datapack reload and synchronization** for species and spawn tables, extending the bundled JSON loaders.
2. **Raising and training** — bond, weight and training progression for partners, on top of levels.
3. **The evolution engine** — evaluating `Evolution` branches (`min_level` is now meaningful) and swapping species at runtime.
4. **Taming and DigiEggs**, beyond the existing spawn/give commands and wild spawns.

### Kabuterimon: the flying mount

`/digicube give kabuterimon` adds the four-armed, four-winged beetle; `/digicube spawn kabuterimon` a wild one. Aim at
your partner and choose Ride on the command wheel: you sit on his helmet behind the horn's hook.

On the ground he walks with planted feet and steps round on the spot as he turns; **W** walks, **A**/**D** turn him.
**Space** takes off. In the air:

- **W** flies where you look: look down to dive (he folds his wings into an arrow and gathers speed), then level out and
  the speed carries you on ahead; looking up trades it for height.
- **Sprint** beats the wings on full (a boost); **A**/**D** slide aside, banking (the view tilts with him).
- **Space** climbs, **C** sinks (down to the ground to land); letting go of everything flares him to a hover.
- A double tap of **Space** is a barrel roll toward the side you hold, a dodge.
- Diving at the ground he levels out into a skim, throwing up dust or spray; past top speed the air bursts round him.

Flying costs stamina (the gauge on the experience bar): about two minutes of plain flight, more boosting and climbing,
less diving. Each attack cast in the air takes a third of it (the notches), so three or so and he must come down; in a
fight it refills slowly.

His attacks, on the ground and in the air (left and right mouse with an empty hand, or R and G):

- **Beet Horn**: on the ground a lunge that gores with the horn (easy to land); in the air a ram along the crosshair, only
  the horn's tip striking, so it takes timing.
- **Mega Blaster** (two in stock): a slow ball of lightning between his four hands; a direct hit deals the whole blow, and
  anything it passes close to is shocked, harder the closer.

`DIGICUBE_SCENARIO=kabuterimon_checks` flies him headless and checks the flight and both attacks on the wing.
