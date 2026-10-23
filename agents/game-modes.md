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
- **CLASSIC** (`binds` and `digisoul` true): digivolution as [domain.md](domain.md#species-and-individuals) describes
  it, on DigiSoul, the first one binding.
- **Numbers** (all in `Progression`, by mode): a Digitama costs 100 scan data in TAMER and 1000 in CLASSIC
  (`digitamaData`), a bar holds two (`scanCapacity`), the first sighting is 30 in both; XP is 150 % of the base
  yield in TAMER and 100 % in CLASSIC (`xpYield(.., mode)`). Hatching (10 minutes) and the defeat rest (30 seconds)
  are the same.

## Checks

`DIGICUBE_SCENARIO=mode_checks` (`ModeScenario`): the adoption rule (a world made in CLASSIC, one with its mode, an
unset one), the saved data, an empty file and the payload round trip, the lock against every other value in both
modes, the join line once. Prints `[scenario] PASS mode_checks cases=5`. `evolution_checks` stages CLASSIC, then
`evolution_tamer` (either Champion every time, nothing spent or drained, the cooldown kept, no bind, a level under 20
sending it back); `:common:evolutionTest`, `:common:partyTest` and `:fabric:digiviceTest` pin `choices`, the TAMER
view and its sentences. `scan_checks` ends with `scan_classic` (1000 a Digitama, 2000 a bar, CONVERT, the base XP);
`:common:progressionTest` and `:common:scanTest` pin the numbers of both modes.
