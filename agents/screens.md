# Screens: the GUI language and the Digivice screen

Read this before building or changing a screen: the shared GUI language and the Digivice screen with its
Analyzer (and its SCAN page) and Digispace tabs. The developer panel is in [dev-panel.md](dev-panel.md).

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

**Analyzer** (`AnalyzerTab`) has three pages under a strip (`DigiviceKit.pageTab`, Tab cycles): DIGIMON lists
every species with search and attribute filter (`AnalyzerIndex`), shows the selected one on an LCD (icon, or the
turning model through `DigimonPreview` on VIEW 3D) with stats, attacks (each with the emblem of the mark it leaves,
a click opening it) and a DIGIVOLUTION block (FROM, INTO, SEE THE TREE); profiles are the lang keys `digimon.digicube.<id>.profile`. MARKS
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

**SCAN** (`ScanPage`) is the scan of [domain.md](domain.md#the-scan-and-the-digitama): four bays, each a family's
Digitama drawn as its bar (`DigitamaArt`: the item sprite `textures/item/digitama/<first form>.png` in its colours from
the bottom up, ink above, data glinting on the line, amber once ready), its percentage and state; below, the selected
family's forms (a click opens their entry), its habitat line (`SpawnHabitat`: regions in the colour of their danger,
with the levels there under the pointer, and the key to the colours), DATA, the defeats left at the recent pace, and
CONVERT, open with a free inventory slot (the sentence says to free one), which pours the data into the egg; once the
Digitama item is in the inventory the egg sinks through its pedestal (SENT). A refusal from the server stops the pour
and shows in red on the sentence; a family whose Digitama waits in the inventory says so. A bar ready and not yet
looked at lights the page's tab and dots ANALYZER (`ScanNews`); the strip counts the fullest bar. A species entry wears
its family's egg and percentage on the DIGIVOLUTION row; a click opens SCAN on it. Gains outside the Digivice show as
toasts (`ScanToast`; a family's data adds up in the toast already showing it).

**Digispace** (`DigispaceTab`) is where the party is managed: the reserve wanders a painted island
(`DigispaceWorld` decides ground and props and paints the terrain, `DigispaceArt` the props, `DigispaceHerd`
who stands where, `DigispaceCamera` zoom and pan), the cursor is a glove, a click selects a Digimon and slides
its card up (DIGIVOLUTION and ANALYZE; a click on a party bay picks a partner the same way), dragging one slides the party
dock up and dropping it on a bay sends `SELECT`; with the dock pinned by the PARTY key a partner can be
carried back to the island (`SELECT` -1, never the last one) or to another bay, where two partners trade
places (`PartyRoster.select`). A Digitama lies still on the island at half a Digimon's size (its texels the island's),
rocks now and then and cracks before it hatches (rebuilt out of data as a Digimon); its time to hatching sits above it
in the pointer's tag (`DigiviceKit.tag`, the name added under the pointer), its card counts it down each second and offers SCAN and
ANALYZE, and it can be carried about the island but not to the party (the dock stays down; a bay says why). One just taken in from the hand (`DigitamaVisuals` opens the Digivice on it,
`PartyClient.openEgg`) comes together where it lies once the display is on (`DigispaceTab.arrive`,
`DigitamaArt.assemble`): its texels fall into place in gold, top rows first, take their colours, flash white with a
ring of data and the recall's catch sound, and its card comes up. Where a Digimon stands is cosmetic and client-side; the server only knows the
reserve. The snapshot page (`PartySnapshotPayload.PAGE_SIZE`) is large enough to show an ordinary reserve at
once. `:fabric:digiviceTest` pins the island, the camera and the herd, and with `-PdigiviceEvidence=<dir>`
writes the painted island and props as PNG.

**The DIGIVOLUTION sheet** (`EvolutionSheet`, drawn over the whole display by `DigiviceScreen`) is a family's tree:
a column per stage, one Rookie's branch open and the other folded (`+2 FORMS`, a click opens it), the level of each
step on its arrow, names under the forms, unseen ones as silhouettes. A Baby II's growth is the same choice, worded
as a growth and with no DigiSoul to spend. It opens from a card (BACK returns to the
island), from the Analyzer for a species, and from the wheel's DIGIVOLVE (BACK puts the Digivice away). For a partner
that has not digivolved yet it is a choice: the forms it can take now breathe amber, the one in focus wears a white
ring two units clear, arrows and Enter walk and take them, and DIGIVOLVE asks ARE YOU SURE? (the Digimon, the form,
THIS CHOICE CANNOT BE UNDONE) before it sends the route and closes. Once bound, the other Champion is dimmed with a
padlock and says why, and DIGIVOLVE goes straight. The readout column shows the form's sentence, two attacks, a
partner's DigiSoul with what a digivolution spends, ANALYZE, and DIGIVOLVE or SET (the Rookie form of a Champion
without one). News (`EvolutionTree.news`: a ready route not yet seen in the tree, before the first digivolution)
puts a white balloon with an amber "!" over the Digimon on the island or its bay, and amber dots on the DIGISPACE tab,
the PARTY key and the card's DIGIVOLUTION key; opening the tree sends `NOTICE` and tags those forms NEW for that
look. The rules (layout, readiness, blocking, news, sentences) are `EvolutionTree`, without drawing, and
`:fabric:digiviceTest` pins them.
