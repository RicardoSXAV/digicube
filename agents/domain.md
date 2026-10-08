# Domain: species, partners, progression, the scan and spawning

Read this before touching species sheets, evolution and growth, ownership, the first-partner prompt, levels and XP,
defeat, healing and food, what wild Digimon drop (Digimeat), creative test play, the scan and its Digitama, wild
spawning, or whom wild Digimon fight.

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
- A family is a Baby II and every form its routes reach (`DigimonFamilies`: four in the catalog, Koromon's, Tsunomon's,
  Bukamon's and Motimon's, in catalog order of their earliest member). A Baby II's `evolutions` are its growth into
  one of its family's Rookies, a Rookie's its Champion routes (`EvolutionRules.routes`; an Armor target counts as a
  Champion), in sheet order, and the tamer **chooses** one in the Digimon's tree.
- **Growth** opens at `Progression.GROWTH_LEVEL` (10; the sheets say so too), spends no DigiSoul, waits for no
  cooldown and is for good: at its commit `EvolutionController` leaves the Rookie at rest with no line, no return form
  and no history, its Champion choice still open (the server's refusal below 10 is `grow_level`). It plays the long
  sequence; a recall in the middle leaves the Baby II.
- **Digivolution** opens at `CHAMPION_LEVEL` (20) on DigiSoul, and the first one to commit binds the individual to that
  Champion for good: `EvolutionState.line`, saved with the evolution state. `EvolutionController.evolve(entity, choice)`
  refuses a missing choice while several routes are open (`choose`) and any other Champion once bound (`locked`); the
  one-argument `evolve` (tooling) takes the line, else the first ready route. A Champion with no Rookie form is bound
  to the form it is, and SET (`origin`) keeps that; `EvolutionRules.validOrigin` names Rookies only. Saves from before
  the choice bind to the Champion they reached.
- `EvolutionState.noticed` holds the routes the tamer has seen ready in the Digimon's tree (`PartyActionPayload.NOTICE`);
  a ready route not in it is news. `PartyActionPayload.EVOLVE` carries the route as 1 + its index (0: the line), for a
  growth as for a digivolution. Check with `DIGICUBE_SCENARIO=evolution_checks` (`[scenario] PASS evolution_checks
  cases=29`: every Champion route staged, and `evolution_growth`) and `:common:evolutionTest`.

## Ownership and the first partner

Ownership: `DigimonEntity` implements `OwnableEntity`; `/digicube give <species> [player]` spawns a partner (a
Champion at no less than `Progression.CHAMPION_LEVEL`: below it the party stores it for evolution on its first
tick, so it used to vanish on arrival). Owned Digimon follow their tamer and join their fights.

The first partner is a prompt, not a command: `StarterFlow` (common) decides eligibility (not a spectator, no
`digicube:starters` record, no owned Digimon), writes the record first and then grants through
`PartyManager.give`; the candidates and their level are `data/digicube/starters.json` (the four Baby II at level 1),
loaded by `StarterSet`.
Common code sends payloads through `Services.PLATFORM.sendToPlayer`, so a command can open the prompt without
a loader import. The Fabric adapter only registers payloads and join/leave hooks. `:common:starterTest` covers
the rules; the `/digicube` root has no permission requirement, each operator subcommand carries its own.

## Progression and healing

Progression: every balance number of levels, XP, rest and the scan (the curve, stage yields, the level-gap multiplier
of 10 % a level from 50 % to 150 %, stat scaling, the damage-proportional split, the Digivice regeneration pulse and
the scan's data) lives in `Progression`, next to the attribute triangle, and `:common:progressionTest` pins its
tables. Never put a balance constant anywhere else. `DigimonEntity` holds
`level` and `xp`; a wild Digimon's `DamageLedger` records the health it lost to each partner, and
`ExperienceAward` splits the yield at the end of `hurtServer` on the killing blow (vanilla calls `die` from
inside `hurtServer`, before the last hit could be recorded). Only partners enter the ledger: a tamer's own blows add
nothing to a share. A partner that falls forfeits its entries on every wild ledger (`DigimonEntity.forfeitShares`), so
it earns nothing from a fight it lost. The yield is the base `stageYield × (level + 4) / 2` at `XP_RATE_PERCENT` (150).

Healing in survival: a partner heals on its own, full in `Progression.FULL_HEAL_TICKS` (two minutes). In the Digivice
that is `PartyManager.regenerateReserve`, one pulse per `RESERVE_REGEN_INTERVAL_TICKS` while the tamer is online; out
in the world it is `DigimonEntity.mend`, `fieldHeal` once a second (`FIELD_REGEN_INTERVAL_TICKS`, from the party tick)
once it has been calm for `FIELD_REGEN_DELAY_TICKS` (five seconds: nothing hurt it, no fight of its own or its rider's,
no target). A defeated partner goes into the Digivice and rests `DEFEAT_REST_TICKS` (thirty seconds,
`PartyMember.restTicks`, saved, shown as a countdown in the Digivice), then stands at `REVIVE_HEALTH` (1) and goes back
to the slot it fell from (`PartyMember.returnSlot`, saved; `PartyManager.regroup`) unless the tamer filled it meanwhile.
It comes back without the fire (a Burn), ice or effects it fell with: `PartyMember.defeat` drops them from the snapshot
(`FALL_STATE`), which is taken before vanilla clears a dying body's effects.
Out in the world, a right click with any food heals a hurt partner its tamer owns by `Progression.feedHeal` (5 % of its
maximum per point of nutrition, bread a quarter; `DigimonEntity.feed`); a full partner or a wild one eats nothing.
Bites are spaced (`Progression.feedWait`): one every `FEED_FIGHT_INTERVAL_TICKS` (fifteen seconds) in a fight, one every
`FEED_INTERVAL_TICKS` (1.6 seconds) while the partner is `calm` (the mending's condition), and the calm spacing is back
as soon as the fight is over, however long ago the last bite was. Too soon, the food stays in the hand and the tamer
reads when it eats if nothing new happens (`DigimonEntity.biteWait`, from `calmIn`: ticks to calm, -1 while it fights
on with a live target or an attack under way): `digimon.digicube.feed_wait`, or `feed_wait_fight` while it fights on.
The bite clock is saved with the partner (`FedTicksAgo`), so a recall does not reset it.
`/digicube heal` skips the rest. Check with `DIGICUBE_SCENARIO=tamer_checks` (`[scenario] PASS tamer_checks cases=13`),
`:common:partyTest` and `:common:progressionTest`.

What wild Digimon drop: Digimeat (`digicube:digimeat`, `DCItems.DIGIMEAT`), food with cooked chicken's hunger and
saturation (`Progression.DIGIMEAT_NUTRITION` 6), so 30 % of a partner a piece. A wild Digimon drops whatever killed it
(`DigimonEntity.dropFromLootTable`, `DigimonDrops`): from its species' own table
(`data/<namespace>/loot_table/entities/digimon/species/<path>.json`) when one exists, else its stage's
(`data/digicube/loot_table/entities/digimon/<stage id>.json`: Baby I and II 1, Rookie 1–2, Champion, Armor and Hybrid
1–3, Ultimate 2–4, Mega 3–5, Ultra 4–6; Looting adds 0 to 1 a level). A partner, which falls into the Digivice, and a
Battle Testing fighter drop nothing. `:common:progressionTest` checks that every stage has a table; `tamer_checks`
kills a wild Baby II, Rookie and Champion.

Creative is test play (`PartyManager.creative`, the owner's game mode kept on the party session): a Champion
needs no Rookie return form to be given, deployed or selected, and every partner's DigiSoul stays full with no
evolution cooldown (`EvolutionController.tick`, `PartyEvolution.tick`), so an evolved form lasts as long as
testing does. A Champion with no Rookie behind it offers no Revert. Survival keeps every rule.

## The scan and the Digitama

The scan (`common/.../scan/`) is the only way to more Digimon. Each tamer has a bar per family (`ScanRecord`, saved per
world in `ScanSavedData`):

- The family's first sighting fills `Progression.FIRST_SIGHTING_DATA` (30): the Analyzer recording one of its species
  (`AnalyzerWitness`, the tamer's own Digimon at the first look) or a defeat of one.
- Each defeat of a wild Digimon fills its family's bar by `Progression.scanSplit`, the XP split on the stage's yield
  (Baby II 6, Rookie 10, Champion and Armor 18) with each partner's level-gap multiplier, summed per tamer
  (`ExperienceAward` → `Scan.credit`). The last `SCAN_PACE_DEFEATS` defeats give the pace (`ScanRecord.defeatsLeft`).
- A bar holds `SCAN_CAPACITY` (200); past it data is lost. CONVERT (`PartyActionPayload.CONVERT`, the family's index)
  spends `DIGITAMA_DATA` (100) at any level and puts a Digitama item in the tamer's inventory (`digicube:digitama`,
  `DigitamaItem`, one a slot, its family in the `digicube:digitama` component, `DCDataComponents`); with no free slot it
  is refused (`gui.digicube.scan.no_room`) and spends nothing. The item model picks the family's egg by the component
  (`items/digitama.json`; a stack without one is the blank egg and cannot be used).
- Used from the hand, with the Digivice with the tamer (else `item.digicube.digitama.no_device`), the item goes into
  the Digivice at once (`PartyManager.giveDigitama`): a level-1 member of the family's first form with `hatch_ticks`
  (`PartyMember.egg`). `DigitamaUsePayload` then plays the recall chip's break on the egg's texels in the hand, for the
  tamer and anyone near, and opens the tamer's Digivice on it after `OPEN_TICKS` (the screen side is in
  [screens.md](screens.md)). The Digitama never takes a party slot (`PartyRoster`; `PartyManager.select` answers
  `gui.digicube.scan.egg_party`), is no species the Analyzer knows yet, and hatches after `DIGITAMA_HATCH_TICKS` of its
  tamer's time online (`PartyManager.incubate`, at the reserve pulse).
- The party snapshot always carries the bars (`ScanBar`); `ScanToastPayload` tells of data, sightings, losses, ready
  bars and hatchings. `/digicube scan set <species> <data> [player]` and `/digicube scan hatch [player]` are the
  operator's shortcuts. Check with `DIGICUBE_SCENARIO=scan_checks` (`[scenario] PASS scan_checks cases=7`) and
  `:common:scanTest`.

## Wild spawning

Wild spawning is data too: `data/digicube/spawn_tables.json` lists one
`data/digicube/spawn_table/<dimension>.json` per dimension, loaded and validated at startup by
`BundledSpawnTableLoader` and covered by `:common:spawnTableTest`. `WildSpawner.tick` is loader-neutral and
runs from the loader's end-of-level-tick hook; its settings are the `digicube:wild` saved data edited with
`/digicube wild`. An attempt (every 200 ticks) tries up to `WildSpawner.TRIES` spots around a random player while fewer
than `max_per_player` (8) wild Digimon are within `LOCAL_RADIUS` (96) of that player and fewer than `max_per_level` (48)
in the dimension. Each Digimon of a pack rolls its own level, and every wild Digimon of a species within `CROWD_RADIUS`
of the spot divides that species' weight (`SpawnTable.crowdedWeight`: `weight / (1 + n)`), so the land holds a mix.
A wild Digimon lingers: vanilla's random despawn waits for `DigimonEntity.WILD_LINGER_TICKS` (five minutes with no
player within 32 blocks), and only past `WILD_DESPAWN_DISTANCE` (128) from every player does it go at once.

Wild Digimon are neutral: they only retaliate, and only attack-less species flee. A blow from a tamer or a partner
starts a grudge against that tamer's side (`WildGrudge`, not saved): the wild Digimon fights the partner it is on, else
the nearest partner of that tamer in reach, and the tamer only after the tamer struck it by hand and no partner is left.
New targets are only chosen within `WildGrudge.MEMORY_TICKS` (thirty seconds) of the side's last blow.

A table divides its land into `regions` (`SpawnRegion`: named lists of biome ids, each `calm`, `wild` or `dangerous`,
`SpawnDanger`; a biome in no region is calm) and gives each danger a level band per stage (`bands`: Baby II +2 and
+4, Rookies +4 and +8, Champions none). Entries name regions (or biome ids and tags) and their calm level range (Baby II
1-6, Rookies 3-11, Champions 12-22; weights 24, 16 and 4); `WildSpawner` adds the band of the spot's region, so
dangerous land holds Rookies up to level 19 and Baby II up to 10.
Every species has an entry. `SpawnHabitat` reads where a set of species lives, region by region with its levels (the
SCAN page's habitat line). `WildSpawner.spawnAt` runs one attempt at a chosen column; `DIGICUBE_SCENARIO=spawn_checks`
drives it in real biomes near the world spawn, keeping what it put down until the region is done, so the crowd rule
plays (`[spawn-checks] RESULT`): species per region, levels in band, and how often big bodies find no room.
