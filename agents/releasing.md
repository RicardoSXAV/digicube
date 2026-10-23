# Releasing: version numbers, tags and the release jar

Read this before cutting a release: which number it takes, what each kind of release may hold, the tag that marks
it and the jar players install.

## Version numbers

The mod's version is `version` in `gradle.properties`, as `MAJOR.MINOR.PATCH` (Fabric Loader reads it as SemVer).
It never repeats the Minecraft version: the jar name already carries it.

| Release | Number | What it holds |
|---|---|---|
| Alpha | `0.1.0` | The first public build: experimental, under heavy development, not stable. Its mechanics may still change a lot. Published on the alpha channel. |
| Update | next `0.N.x` | New Digimon, remakes, mechanics and fixes. No fixed date: the line ships as it goes, so each feature finished on `main` can go out as the next number. |
| Hotfix | next `0.N.x` | Urgent fixes for bugs players reported. None is cut while nothing urgent is pending. |
| Major update | `0.N+1.0` | Complex systems: new maps, bosses with their own structures, progression through quests, NPCs and lore. It ships whole, when its goal is done. |
| Stable | `1.0.0` | The first release considered fully stable. |

- The last number counts every release in a major line, update or hotfix alike: `0.1.0` (alpha), then `0.1.1`,
  `0.1.2` and on. A major update resets it: `0.1.5` is followed by `0.2.0`.
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

A release is cut from `main`, which holds only what can ship. A hotfix while `main` holds work not ready to ship is
cut from a branch off the release's tag, in a worktree of its own
([branches.md](branches.md#a-hotfix-while-main-holds-unreleased-work)).

## What players see

- `mod_name`, `description` and `mod_author` in `gradle.properties` fill the mod's entry in the in-game mod list.
  `fabric/src/main/resources/fabric.mod.json` has no `contact` block: a link there must name no person (golden
  rule 1), so a repository URL under a personal account cannot go in.
- The icon is `common/src/main/resources/digicube.png`: square, a power of two on each side.
- Players need Fabric Loader at `fabric_loader_version` or newer and Fabric API: the `depends` block of
  `fabric.mod.json`, filled from `gradle.properties` at build time.
