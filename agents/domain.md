# Domain: species, partners, progression and spawning

Read this before touching species sheets, evolution, ownership, the first-partner prompt, levels and XP,
healing, creative test play or wild spawning.

The domain lives in `common/src/main/java/com/digicube/digimon/`.

Species are loaded from the bundled `data/digicube/species.json` catalog and `data/digicube/species/*.json`
sheets by `BundledSpeciesLoader`, on both sides at startup. Add species as data; do not add species
constructors to `DigimonSpeciesBootstrap`. Attack ids reference shared moves in the bootstrap, in priority
order. The next architectural step is datapack reload support plus server catalog synchronization; the current
classpath loaders do not process `/reload`.

## Species and individuals

- `DigimonSpecies` is the **immutable shared sheet** for a Digimon: base stats, stage,
  attribute, evolution branches. Exactly one instance exists per species.
- Anything that differs between two individuals — level, bond, nickname, current HP,
  training points, weight — belongs on the **entity**, never on the species.
- `DigimonStage` and `DigimonAttribute` carry stable string ids (`"child"`, `"vaccine"`).
  These get written to JSON and to save data, so **changing one is a breaking data
  migration**, not a rename.
- `Evolution` lists are evaluated **in order, first match wins**. Put the rarest and most
  specific branch first, the plain level-gated fallback last.

## Ownership and the first partner

Ownership: `DigimonEntity` implements `OwnableEntity`; `/digicube give <species> [player]` spawns a partner (a
Champion at no less than `Progression.CHAMPION_LEVEL`: below it the party stores it for evolution on its first
tick, so it used to vanish on arrival). Owned Digimon follow their tamer and join their fights.

The first partner is a prompt, not a command: `StarterFlow` (common) decides eligibility (not a spectator, no
`digicube:starters` record, no owned Digimon), writes the record first and then grants through
`PartyManager.give`; the candidates and their level are `data/digicube/starters.json`, loaded by `StarterSet`.
Common code sends payloads through `Services.PLATFORM.sendToPlayer`, so a command can open the prompt without
a loader import. The Fabric adapter only registers payloads and join/leave hooks. `:common:starterTest` covers
the rules; the `/digicube` root has no permission requirement, each operator subcommand carries its own.

## Progression and healing

Progression: every balance number of levels, XP and rest (the curve, stage yields, the level-gap multiplier,
stat scaling, the damage-proportional split and the Digivice regeneration pulse) lives in `Progression`, next
to the attribute triangle, and `:common:progressionTest` asserts the tables in
`../design/wild-spawns-and-progression.md`. Never put a balance constant anywhere else. `DigimonEntity` holds
`level` and `xp`; a wild Digimon's `DamageLedger` records the health it lost to each partner, and
`ExperienceAward` splits the yield at the end of `hurtServer` on the killing blow (vanilla calls `die` from
inside `hurtServer`, before the last hit could be recorded).

Healing in survival: a partner stored in the Digivice regenerates slowly (`PartyManager.regenerateReserve`,
one pulse per `Progression.RESERVE_REGEN_INTERVAL_TICKS` while the tamer is online, full in
`RESERVE_FULL_HEAL_TICKS`); a defeated partner first rests `DEFEAT_REST_TICKS` (`PartyMember.restTicks`,
saved, shown as a countdown in the Digivice) and then heals from zero; deployed partners heal only through
play and `/digicube heal` skips the rest.

Creative is test play (`PartyManager.creative`, the owner's game mode kept on the party session): a Champion
needs no Rookie return form to be given, deployed or selected, and every partner's DigiSoul stays full with no
evolution cooldown (`EvolutionController.tick`, `PartyEvolution.tick`), so an evolved form lasts as long as
testing does. A Champion with no Rookie behind it offers no Revert. Survival keeps every rule.

## Wild spawning

Wild spawning is data too: `data/digicube/spawn_tables.json` lists one
`data/digicube/spawn_table/<dimension>.json` per dimension, loaded and validated at startup by
`BundledSpawnTableLoader` and covered by `:common:spawnTableTest`. `WildSpawner.tick` is loader-neutral and
runs from the loader's end-of-level-tick hook; its settings are the `digicube:wild` saved data edited with
`/digicube wild`. Wild Digimon are neutral: they only retaliate, and only attack-less species flee.
