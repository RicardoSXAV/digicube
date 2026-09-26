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
`fabric/.../render/PixelPlaneParticle` (two quads, since particles cull back faces); sounds are synthesised by
`harness/v2/out/centalmon/cannon_fx_01/make_cannon_audio.py`. The `water` style (Crabmon's Water Shot) is in
[species/ganimon.md](species/ganimon.md).

## Strike particle styles

Authored moves name a `"particles"` style (`StrikeParticles`) for their trail, release, contact and landing
effects and sounds; see [authored-attacks.md](authored-attacks.md#strike-particle-styles).

## Voices

A species' own voice (ambient, hurt, death, pitch) is `data/digicube/voices.json` (`DigimonVoices`); without
an entry a Digimon keeps vanilla's.
