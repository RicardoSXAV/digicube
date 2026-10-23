# Releasing: version numbers, tags and the release jar

Read this before cutting a release: which number it takes, what each kind of release may hold, the tag that marks
it with its changelog, and how the jar players install reaches CurseForge.

## Version numbers

The mod's version is `version` in `gradle.properties`, as `MAJOR.MINOR.PATCH` (Fabric Loader reads it as SemVer).
It never repeats the Minecraft version: the jar name already carries it.

| Release | Number | What it holds |
|---|---|---|
| Alpha | `0.1.0` | The first public build: experimental, under heavy development, not stable. Its mechanics may still change a lot. |
| Update | next `0.N.x` | New Digimon, remakes, mechanics and fixes. No fixed date: the line ships as it goes, so each feature finished on `main` can go out as the next number. |
| Hotfix | next `0.N.x` | Urgent fixes for bugs players reported. None is cut while nothing urgent is pending. |
| Major update | `0.N+1.0` | Complex systems: new maps, bosses with their own structures, progression through quests, NPCs and lore. It ships whole, when its goal is done. |
| Stable | `1.0.0` | The first release considered fully stable. |

- The last number counts every release in a major line, update or hotfix alike: `0.1.0` (alpha), then `0.1.1`,
  `0.1.2` and on. A major update resets it: `0.1.5` is followed by `0.2.0`.
- A number is never reused. A broken release is replaced by a new one, not rebuilt under the same number.
- No `-beta` or `-alpha` suffix: a `0.` number already says the mod is not stable, and the file's name on
  CurseForge says Alpha ([below](#publishing-to-curseforge)).

## Cutting a release

1. Commit everything the release ships; the working tree is clean.
2. Set `version` in `gradle.properties` in its own commit: `chore: set version to 0.1.1`.
3. `./gradlew build` passes on that commit.
4. Write the tag message in a scratch file outside the repository: `v` plus the version, a blank line, then the
   [changelog](#the-changelog). Tag with it as an annotated tag named `v` plus the version, then push the branch
   with its tags:

   ```
   v0.1.2

   **New**
   - Partners can now ...

   **Fixed**
   - Riding Garurumon into water no longer ...
   ```

   ```bash
   git tag -a v0.1.2 -F <message file>
   git push origin main --follow-tags
   ```

5. The pushed tag publishes the jar to CurseForge by itself ([below](#publishing-to-curseforge)). Follow its
   **Release** run in the repository's Actions tab until it is green.

A tag is never moved once pushed. Before that, a release that changes again gets its tag recreated on the new
commit (`git tag -d v0.1.2`, then tag again).

## The changelog

The body of the tag message, below its first line, is the changelog players read on CurseForge, in Markdown:

- For players, in English: what changes in the game, in short plain sentences. No internal tasks, tests or docs.
- Under the headings **New**, **Improved** and **Fixed**, in that order. A heading with nothing under it is left
  out. Headings are bold, never `#`: git drops lines starting with `#` from a tag message as comments.
- No semicolons: a comma, "and", or a new sentence instead.

## Publishing to CurseForge

Pushing a `v*` tag runs `.github/workflows/release.yml`, which:

1. Checks that the tag is `v` plus `version` from `gradle.properties` and is annotated, with a changelog.
2. Checks that the repository secret `CURSEFORGE_TOKEN` works and that CurseForge knows every version tag the
   upload names.
3. Runs `./gradlew build`, then `./gradlew :fabric:publishMods`, which uploads
   `fabric/build/libs/digicube-fabric-<minecraft_version>-<version>.jar` to the CurseForge project
   `curseforge_project_id` in `gradle.properties`: a Release file named `DigiCube <version> Alpha`, for
   Minecraft `minecraft_version`, Fabric, client and server, with Fabric API as a required dependency. Every file
   goes out as a Release, never an Alpha, since CurseForge hides alpha files from players by default.

The upload's settings are the `publishMods` block in `fabric/build.gradle` (the Mod Publish Plugin, its version in
the root `build.gradle`). CurseForge reviews each file before players can download it.

- **Dry run.** Run the Release workflow by hand (Actions, Release, Run workflow) on `main`: the same checks and
  build with a placeholder changelog, and nothing uploaded, since only a tag push hands the token to the upload
  step. Locally, `./gradlew :fabric:publishMods` is a dry run whenever `CURSEFORGE_TOKEN` is unset or blank: set
  `RELEASE_CHANGELOG` to the changelog, and it prints what it would send and copies the jar to
  `fabric/build/publishMods`.
- **A failed run.** A run that fails before its upload sent nothing: fix the cause and use Re-run jobs on the run's
  page. A tag that fails its own checks (no changelog, the wrong version) stays as it is, and the release takes the
  next number. Re-running a run that uploaded uploads the file a second time.
- `v0.1.0` and `v0.1.1` were uploaded by hand: their commits have no release workflow, so their tags never run one.
  Neither would a hotfix branched from them: its jar is uploaded by hand.
- Modrinth is not wired yet. The plugin publishes there too, from a `modrinth {}` block beside `curseforge {}` with
  its own project id and token secret.
- The `-sources` and `-javadoc` jars (built only with `-Pextra_jars=true`) are not for players, and older jars in
  `fabric/build/libs` are leftovers of earlier versions.

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
