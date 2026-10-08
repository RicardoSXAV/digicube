# Releasing: version numbers, tags and the release jar

Read this before cutting a release: which number it takes, what each kind of release may hold, the tag that marks
it and the jar players install.

## Version numbers

The mod's version is `version` in `gradle.properties`, as `MAJOR.MINOR.PATCH` (Fabric Loader reads it as SemVer).
It never repeats the Minecraft version: the jar name already carries it.

| Release | Number | What it holds |
|---|---|---|
| Beta | `0.1.0` | The first public build: experimental, under heavy development, not stable. |
| Hotfix | next `0.N.x` | Urgent fixes for bugs players reported. None is cut while nothing urgent is pending. |
| Monthly update | next `0.N.x` | New Digimon and a few new mechanics. |
| Major update | `0.N+1.0` | Complex systems: new maps, bosses with their own structures, progression through quests, NPCs and lore. Its cadence follows development. |
| Stable | `1.0.0` | The first release considered fully stable. |

- The last number counts every release in a major line, hotfix or monthly update alike: `0.1.0` (beta), `0.1.1`
  (hotfix), `0.1.2` (monthly update). A major update resets it: `0.1.5` is followed by `0.2.0`.
- A number is never reused. A broken release is replaced by a new one, not rebuilt under the same number.
- No `-beta` or `-alpha` suffix: a `0.` number already says the mod is not stable, and the release channel is
  chosen where the jar is published.

## Cutting a release

1. Commit everything the release ships; the working tree is clean.
2. Set `version` in `gradle.properties` in its own commit: `chore: set version to 0.1.1`.
3. `./gradlew build` passes on that commit.
4. Tag it with an annotated tag named `v` plus the version, then push the branch with its tags:

   ```bash
   git tag -a v0.1.1 -m "v0.1.1"
   git push origin main --follow-tags
   ```

5. Publish `fabric/build/libs/digicube-fabric-<minecraft_version>-<version>.jar`. The `-sources` and `-javadoc`
   jars (built only with `-Pextra_jars=true`) are not for players, and older jars in that folder are leftovers of
   earlier versions.

A tag is never moved once pushed. Before that, a release that changes again gets its tag recreated on the new
commit (`git tag -d v0.1.1`, then tag again).

A hotfix while `main` holds unreleased work starts from the release's tag: `git switch -c release/0.1 v0.1.1`,
fix, set the next number, build and tag there, then merge the branch back into `main`.

## What players see

- `mod_name`, `description` and `mod_author` in `gradle.properties`, and the `contact` links in
  `fabric/src/main/resources/fabric.mod.json`, fill the mod's entry in the in-game mod list.
- The icon is `common/src/main/resources/digicube.png`: square, a power of two on each side.
- Players need Fabric Loader at `fabric_loader_version` or newer and Fabric API: the `depends` block of
  `fabric.mod.json`, filled from `gradle.properties` at build time.
