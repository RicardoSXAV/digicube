# Effects: glows, particles and sounds

Read this before adding a glow, a particle, an attack sound or a species voice.

## Glows over water

Glows over water: 26.2 draws translucent entity models and custom geometry *before* translucent terrain, so a
glow that writes no depth (vanilla `entityTranslucentEmissive`, any additive pipeline) gets water and ice
painted over it and looks sunk below the surface. Use `AfterWaterEffects.glow(texture)` instead of
`entityTranslucentEmissive`, and give a custom glow pipeline's `RenderSetup` `AfterWaterEffects.TARGET`
(`EvolutionRenderType` does for its additive types): `MixinSubmitNodeCollection` moves those into vanilla's
after-terrain phase (where translucent particles go), and with improved transparency they draw into the water
layer. Like vanilla particles, a glow under a water surface is then hidden from above it. Depth-writing
`entityTranslucent` effects are left before the water on purpose, so submerged fish and bubbles still show
through it.

## Shot styles

A kinetic shot may have a `shot_style` (`ShotStyle`, `cannon` for the Hunting Cannon): the report and the
burst are server-sent sounds and particles, the trail is strewn by each client along the stretch the bolt
flew. The particles are `DCParticles`, flat pixel planes that tumble in 3D, drawn by
`fabric/.../render/PixelPlaneParticle` (two quads, since particles cull back faces); the sounds are original
cues in `DCSounds`. The `water` style (Crabmon's Water Shot) is in
[species/ganimon.md](species/ganimon.md).

## Breaths and pounces

A breath of puffs ([combat.md](combat.md#attacks-as-data)) is drawn by `fabric/.../render/FrostBreathRenderer` as a
stream of solid glowing blocks: each puff carries boxes of its effect model (`howling_blaster_fx`: cross-sections with
their cores and tongues, tips, edge sheets, embers), whole and rigid at `PIXEL` (0.025 blocks a model pixel). A puff's
section is the one for how far out along the flame it is (narrow throat, broad middle, thin end, now and then a
neighbour's), laid again every 8 pixels back along the puff's own flight for as far as the next puff is behind it
along that flight: a steady stream's trails meet into one body, while a swept one opens into separate streaks, each on
its own puff's way, never a band bent between puffs. Blocks turn along the flow (`FrostBreath.Puff.look*`: the aim
without the scatter, so a steady stream stays straight) with the art's top up, or lie flat on the surface the puff
struck (`Puff.surface*`); puffs toward the end carry the tips, and the oldest all of them ahead of it. A slow wave runs
along the flame and each puff jostles its trail by its own, while each block of a trail keeps its own lane (steps no
multiple of the art's quarter pixel, so no two faces of a trail share a plane and flicker); embers drift off some puffs;
blocks shrink as their puffs die; the head tapers, and so does the tail of a flame that has left the mouth. Nothing is
ever stretched: a stretched piece reads as a bar across the stream. Faces carry a shade by direction in their vertex colour and draw full-bright,
opaque and depth-written (`SolidGlow`, registered at client start so the resource reload compiles it). Each client strews vanilla
particles from the puffs it flies (`breathParticles`: snowflakes and cold smoke on the flame's skin, never inside its
solid blocks, snow and ice where one strikes), and the jaws of a pounce under way stream frost behind them
(`pounceTrail`); a pounce's bite bursts its `impact` effect model where the jaws met. The flame is pinned by
`:fabric:nativeGarurumonTest` ([species/garurumon.md](species/garurumon.md#checks)). A breath's sounds are its sheet's
`sounds` (start, loop, end), played by each client from the breaths it flies (`fabric/.../render/BreathAudio`): the
start as the mouth begins shedding, the loop from the mouth while it sheds (fading in under the start between ticks 2
and 9), and the end as it stops, the loop fading out under it over three ticks.

## Discharges

A discharge's bolts ([authored-attacks.md](authored-attacks.md#discharges)) are drawn by each client from the strike
synced on the caster (`fabric/.../render/ArcRenderer`), with `SolidGlow` like a breath's flame: a zigzag of square rods
from the fin's tip (or a struck body, or the caster's body) to each body struck, following it as it moves, a pale core
with a thinner amber strand winding about it and short forks off the main bolt, a cube at every bend, dealt again about
every two thirds of a tick so it flickers, thinning over its last two ticks; a star of rods bursts where each bolt
lands, the fin's tip flares, and a bolt through water is icy white. The texture is one white texel
(`electric_arc.png`): the colours are the vertices'. `ArcDischarge.clientTick` crackles sparks off the fin through the
charge and off the struck bodies while the bolts live.

## Strike particle styles

Authored moves name a `"particles"` style (`StrikeParticles`) for their trail, release, contact and landing
effects and sounds; see [authored-attacks.md](authored-attacks.md#strike-particle-styles).

## Voices

A species' own voice (ambient, hurt, death, pitch) is `data/digicube/voices.json` (`DigimonVoices`); without
an entry a Digimon keeps vanilla's. Its `cry` is called as each of its moves starts (`DigimonEntity.battleCry`), in
place of the ravager growl an authored move opens with; a move style with a wind-up of its own
(`StrikeParticles.windUp`) keeps that, and a move's own `wind_up` sound cue
([authored-attacks.md](authored-attacks.md#sound-cues)) replaces both. Sounds of the mod's own are registered in `DCSounds` and defined in
`assets/digicube/sounds.json`. An event may reuse another's files at its own `pitch` and `volume`: Ikkakumon's three
hums are `ikkakumon_call` at volume 0.5, and `_hurt` (pitch 1.12), `_death` (0.82) and `_bellow` (0.9) replay them.
The game still varies a voice's pitch by up to 0.2 either way (`LivingEntity.getVoicePitch`) on top of an entry's.
