# Screens: the GUI language and the Digivice screen

Read this before building or changing a screen: the shared GUI language and the Digivice screen with its
Analyzer and Digispace tabs. The developer panel is in [dev-panel.md](dev-panel.md).

## The GUI language

Screens follow the DigiCube GUI language in `fabric/.../client/gui/`: `DigiTheme` holds every colour and knob,
`DigiPanels` draws chamfered frames, brackets, the data grid, data squares, platforms and buttons with `fill`
only, and `DigimonPreview` draws a client-side `DigimonEntity` (never added to the level, never ticked; the
screen bumps its `tickCount`, `markGuiPreview()` hides nameplate and shadow) through
`GuiGraphicsExtractor.entity`. Widgets extend `AbstractButton` for focus and narration; screens do not pause;
layouts are integer GUI units validated at 320 × 240.

## The Digivice screen

The Digivice (`fabric/.../client/digivice/`) is the device itself: `DigiviceScreen` draws the pale blue shell,
its three blue keys (Q previous tab, E next tab, Esc power) and the display, on a fixed 480 × 270 plate that
is centred, doubled on a large screen and shrunk to fit a small one. Controls are immediate: while drawing, a
tab declares what can be clicked (`hit`) and scrolled (`wheel`) and the topmost declaration under the pointer
wins. `DigiviceKit` holds the components (the primary action wears the device's blue key, the rest stay navy),
`DigiviceArt` the palette and the pixel art, painted in code into `DynamicTexture`s on first use.

**Analyzer** (`AnalyzerTab`) has two pages under a strip (`DigiviceKit.pageTab`, Tab switches): DIGIMON lists
every species with search and attribute filter (`AnalyzerIndex`), shows the selected one on an LCD (icon, or the
turning model through `DigimonPreview` on VIEW 3D) with stats, attacks (each with the emblem of the mark it leaves,
a click opening it) and evolution line; profiles are the lang keys `digimon.digicube.<id>.profile`. MARKS
(`MarksPage`) is the guide to the combat marks: the list, the selected emblem living through a run on a stage
(`MarkLife` decides each moment, `MarkEmblems` draws it the way the world does), one sentence with the game's own
numbers in it (`MarkGuide`, lang `mark.digicube.<id>`, `.effect`, `.guide`, `.ends`), how it ends and who leaves
it. An entry is on the tamer's record (`analyzer/AnalyzerRecord`, saved per world) once witnessed
(`AnalyzerWitness`: a species within 24 blocks, in front and in a clear line of sight for a second, the tamer's own
Digimon at once; a mark the moment its emblem shows over such a body, the tamer's own included); the snapshot
carries the record while the Digivice is open (`known`, `marks`). Until then an entry is a silhouette with one
sentence, and search and the filter skip it. A new record sends `AnalyzerDiscoveryPayload`: a toast
(`DiscoveryToast`) and a NEW tag until the entry is opened (`AnalyzerNews`), neither in creative, where every entry
is open and nothing is recorded less for it. The developer panel's ANALYZER RECORD section reveals or forgets the
record. Check with `DIGICUBE_SCENARIO=analyzer_checks` (`[analyzer-checks] RESULT n of n checks passed`);
`:fabric:digiviceTest` pins the guide and the runs.

**Digispace** (`DigispaceTab`) is where the party is managed: the reserve wanders a painted island
(`DigispaceWorld` decides ground and props and paints the terrain, `DigispaceArt` the props, `DigispaceHerd`
who stands where, `DigispaceCamera` zoom and pan), the cursor is a glove, a click selects a Digimon and slides
its card up (Analyzer entry, and the Rookie origin of a Champion that has none), dragging one slides the party
dock up and dropping it on a bay sends `SELECT`; with the dock pinned by the PARTY key a partner can be
carried back to the island (`SELECT` -1, never the last one) or to another bay, where two partners trade
places (`PartyRoster.select`). Where a Digimon stands is cosmetic and client-side; the server only knows the
reserve. The snapshot page (`PartySnapshotPayload.PAGE_SIZE`) is large enough to show an ordinary reserve at
once. `:fabric:digiviceTest` pins the island, the camera and the herd, and with `-PdigiviceEvidence=<dir>`
writes the painted island and props as PNG.
