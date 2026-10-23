# Game modes: TAMER and CLASSIC

Read this when a task touches the DigiCube Mode a world is created in: where the mode lives and how it stays fixed,
the DigiCube Mode section of Create World, what each mode changes, and CLASSIC's raising.

## The world's mode

Every world plays one DigiCube Mode, TAMER or CLASSIC, chosen once by its creator and never changed.

- `mode/DigiCubeModes` is the one place a rule reads it from (`of(server)`, `tamer`, `classic`); server code only. A
  rule never checks the mode in a second place.
- **The carrier**: the world game rule `digicube:mode` (`unset`, `tamer`, `classic`; built with Fabric's
  `GameRuleBuilder.forEnum` in `FabricModes`, reached from common code through `IPlatformHelper.modeRule`). Create
  World's DigiCube Mode section writes it, and vanilla copies the creation screen's game rules into the new world.
- **The authority**: `DigiCubeModeData`, saved data `digicube:mode` in the overworld's storage: the mode, when it was
  set, `adopted`, and the tamers already told. `DigiCubeModes.adopt` runs once the server has started: a world without
  it takes the rule's mode, and a rule still `unset` (a world made before modes, a dedicated server's) plays TAMER.
  After that the rule only mirrors the saved mode.
- **The lock**: any change to the rule (`/gamerule`, the in-world Game Rules screen) reaches
  `DigiCubeModes.ruleChanged` through `GameRuleEvents.changeCallback`, which sets the rule straight back and tells the
  operators online (`chat.digicube.mode.locked`).
- **Players**: on joining, `DigiCubeModePayload` gives the client the mode (`PartyClient.mode()`); the first join in a
  world prints `chat.digicube.mode.joined`. The Digivice's tab strip ends in TAMER MODE (cyan) or CLASSIC MODE (amber).
- Headless scenarios put the dev world in a mode with `DigiCubeModes.stage`, past the lock, and put its own mode back
  before the verdict. Play never calls it.

## What each mode changes

Rule switches live on `DigiCubeMode` (`binds`, `digisoul`) and are asked of the world's mode; nothing else checks it.

- **TAMER** (`binds` and `digisoul` false): each digivolution picks either Champion of the partner's own tree, a new
  pick every time (`EvolutionState.choices(.., binds)` ignores the line), and spends nothing. DigiSoul reads full
  everywhere (`EvolutionController`, the party's reserve tick, a Champion loaded at rest), so no gate waits on it; a
  Champion stays until its tamer sends it back, it is defeated or recalled, or its level drops under 20. The
  10-second cooldown after a return stays. `PartyMemberView.of(.., mode)` sends no line and a full, held `soul`; the
  client hides DigiSoul: the HUD card has no DigiSoul cell and names a Champion's stage instead of its clock, the tree
  has no DigiSoul readout, asks ARE YOU SURE? only for a growth, and words its sentences for TAMER (`*_tamer` keys).
  The growth Baby II to Rookie stays a choice for good.
- **CLASSIC** (`binds`, `digisoul` and `raises` true): digivolution as [domain.md](domain.md#species-and-individuals)
  describes it, on DigiSoul, the first one binding, but the form is the raising's ([below](#classics-raising)).
- **Numbers** (all in `Progression`, by mode): a Digitama costs 100 scan data in TAMER and 1000 in CLASSIC
  (`digitamaData`), a bar holds two (`scanCapacity`), the first sighting is 30 in both; XP is 150 % of the base
  yield in TAMER and 100 % in CLASSIC (`xpYield(.., mode)`). Hatching (10 minutes) and the defeat rest (30 seconds)
  are the same.

## CLASSIC's raising

In CLASSIC the way a partner was raised, not its tamer, picks the Rookie it grows into and the Champion it first
digivolves into. Several signals count together; today two, the foes it beats and where it fights.

- **The record**: `RaisingRecord`, on the individual's `EvolutionState` (saved with it as `raising`, server side, never
  sent): wild defeats it took part in (`ExperienceAward`, the XP split's contributors, CLASSIC only), by the foe's
  attribute and by `RaisingPlace` (`water`, `cold`, `hot`, `forest`, `underground`, `night`; each that applies where
  it stands counts once). A growth starts it over: the Baby II's fights pick the Rookie, the Rookie's the Champion.
- **Routes as data**: each growth and Champion route in a sheet names `"raising": {"foe": <attribute>, "place":
  <place>}` (`RaisingCondition`). The foe is the triangle's prey of the target (Digmon, Free, takes Data); a source's
  routes differ in place, so each is reachable. `EvolutionRules.raised` scores a ready route as wins over its foe plus
  wins in its place and takes the best; a tie or an empty record takes the first in sheet order.
- **Growth by itself**: an owned CLASSIC Baby II at its growth level grows at the first calm moment
  (`DigimonEntity.calm`, checked each second in `EvolutionController.tick`), into the raising's Rookie; one in the
  Digivice grows when it is next out.
- **The Champion**: the tamer still digivolves it (V, the wheel, the tree), on DigiSoul; no form is named
  (`EvolutionController.evolve` refuses any other with `raised`), and the first commit binds the raising's pick.
- **The client**: the V key and the wheel never open the tree for a choice (`PartyClient.picksForm`); the wheel says
  ITS RAISING DECIDES. The tree names no branch ahead of the partner (`EvolutionTree.hidden`: a blank "?" plate, no
  silhouette, attacks or NEW tag), asks nothing, gives a Baby II no key and a Rookie a DIGIVOLVE that names no form,
  and words its sentences for CLASSIC (`*_classic` keys).
- Care (hunger, mistakes, sickness, discipline) joins later as a third signal: a counter in the record and a term in
  the score.

## Checks

`DIGICUBE_SCENARIO=mode_checks` (`ModeScenario`): the adoption rule (a world made in CLASSIC, one with its mode, an
unset one), the saved data, an empty file and the payload round trip, the lock against every other value in both
modes, the join line once. Prints `[scenario] PASS mode_checks cases=5`. `evolution_checks` stages CLASSIC, then
`evolution_tamer` (either Champion every time, nothing spent or drained, the cooldown kept, no bind, a level under 20
sending it back); `:common:evolutionTest`, `:common:partyTest` and `:fabric:digiviceTest` pin `choices`, the TAMER
view and its sentences. `scan_checks` ends with `scan_classic` (1000 a Digitama, 2000 a bar, CONVERT, the base XP);
`:common:progressionTest` and `:common:scanTest` pin the numbers of both modes. `DIGICUBE_SCENARIO=classic_checks`
(`ClassicScenario`, `[scenario] PASS classic_checks cases=3`): a Koromon raised against Data in the water waits out a
fight at level 10 and grows by itself into Betamon, its record starting over; the Betamon, raised against Virus in the
forest, digivolves into Dinohyumon and stays bound whatever its record says next; a defeat records its foe and where,
by day and by night, and nothing in TAMER. `evolution_checks` reaches each Champion route with a record of its own
foe and place, and `:common:evolutionTest` checks all 24 routes' raising, the triangle, ties and the save.
