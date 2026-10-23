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

## Checks

`DIGICUBE_SCENARIO=mode_checks` (`ModeScenario`): the adoption rule (a world made in CLASSIC, one with its mode, an
unset one), the saved data, an empty file and the payload round trip, the lock against every other value in both
modes, the join line once. Prints `[scenario] PASS mode_checks cases=5`.
