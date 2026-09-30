# Developer panel and battle testing

Read this before adding a panel section, a server action or a battle-testing feature.

The developer panel is tooling, not a player feature, and not a command front-end: its one job is calibrating
mechanics in play. It has no key. In a development environment the command wheel shows a gear in the bottom
right corner (`DevGear`); resting the cursor on it opens `DevPanelScreen`, centred and translucent, and Esc
closes it.

The panel draws what `DevTabs` declares: tabs, each tab a scrolling column of section cards with an index on
the left, each card ending in its own action bar, and a search in the title bar over every tab, section and
config (`DevSearch`). `DevCatalog` is the single place that declares content. A plain tuning section is one
chain (`tab(..).section(..).number(..) .toggle(..).choice(..).tuning(..)`) over `DevValue`s and needs no
interface code; a special body implements `DevBody` and registers its controls on the `DevCanvas`
(`BattleTestingBody`). Sections marked `example()` are placeholders whose values live only in the panel; wire
one by giving its rows a `DevValue` that reads and writes the real number. `DevLayout` holds the arithmetic;
`:fabric:devPanelTest` pins declarations, search, cards, the tab row and number rows. Besides Battle Testing, the
PARTY tab's ANALYZER RECORD section is real: REVEAL ALL and FORGET ALL act on the player's Analyzer record
(`AnalyzerWitness`, [screens.md](screens.md#the-digivice-screen)).

Server side: `DevPanel.handle` (common) admits, in a development environment only, an operator or the
singleplayer world owner (a survival world made without cheats gives its host no permission level, and
survival is where the balance testing happens); `DevActions` is the registry of server actions, each a
`(server, player, args) -> reply` lambda. The two payloads (`DevActionPayload`: action id + argument tag,
`DevStatePayload`: state tag + reply) never change when an action is added.

## Battle testing

Battle Testing (`BattleTest`) stages two sides of wild Digimon in front of the player (`BattleRoster`: up to
four kinds a side, each a species, level and count, at most 40 bodies, in ranks), keeps every fighter on the
nearest enemy and lets them fight until a side is down with their real stats; the readout travels in the state
tag every five ticks and `BattleReadout` draws it as a HUD bar. A staged fighter carries its side
(`DigimonEntity.battleSide`, synced): team-mates spare each other, and one that can carry a rider takes any
player on a right click (`mobInteract`) and hands them the reins; this is the only place a right click mounts,
never outside a developer fight. Check with `DIGICUBE_SCENARIO=battle_checks`
(`[battle-checks] RESULT n of n checks passed`).
