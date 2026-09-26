# Porting: NeoForge and new Minecraft versions

Read this only when adding the NeoForge loader or moving to a new Minecraft drop.

## Adding NeoForge later

The layout already anticipates this, so no rewrite is required:

1. Create `neoforge/` mirroring `fabric/`.
2. `neoforge/build.gradle` applies `multiloader-loader` plus `net.neoforged.moddev`, with
   `neoForge { version = neoforge_version }`.
3. Uncomment `include('neoforge')` in `settings.gradle`.
4. Add `neoforge/src/main/resources/META-INF/neoforge.mods.toml`.
5. Implement `NeoForgePlatformHelper` and add the matching `META-INF/services/` file.
6. Port only the entry point and the event subscriptions. **If you find yourself copying
   business logic into `neoforge/`, that logic was in the wrong module — move it to
   `common/`.**

`neoforge_version` is already pinned in `gradle.properties`.

## Porting to a new Minecraft version

Minecraft now ships a drop every few months (`26.1`, `26.2`, `26.3`, ...). To port:

1. Update `minecraft_version`, `minecraft_version_range`, `neo_form_version`,
   `fabric_version` and `fabric_loader_version` in `gradle.properties`.
2. Update the Loom and ModDevGradle versions in `build.gradle` if needed.
3. Read the Fabric porting notes at <https://docs.fabricmc.net/develop/porting/> and
   NeoForge's migration primer for that version.
4. Expect mixins to break first — they bind to exact vanilla method signatures.

Do this on a branch, never on `main`.
