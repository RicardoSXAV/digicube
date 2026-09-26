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

**Analyzer** (`AnalyzerTab`, `AnalyzerIndex`) lists every species with search and attribute filter, shows the
selected one on an LCD (icon, or the turning model through `DigimonPreview` on VIEW 3D) with stats, attacks
and evolution line; profiles are the lang keys `digimon.digicube.<id>.profile`. A species is known when the
tamer owns it, came from it or has reached it (`PartyManager.knownSpecies`, sent in the snapshot while the
Digivice is open); unknown ones are silhouettes, and a development environment knows them all.

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
