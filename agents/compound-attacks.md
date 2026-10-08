# Compound moves: forms across families, weapon stances and gauges

Read this before adding or changing a move that casts other attacks (`compound_attacks.json`, `CompoundAttacks`, kind
`COMPOUND`): its forms of any family, a drawn weapon's stance (`AttackStance`), a gauge that landed hits fill, strikes
chained without the gait between them, and how the AI, orders, the command wheel and the party strip read them. Leomon's
Lion Sword and Beast King Fist are the examples ([species/leomon.md](species/leomon.md)). Attacks as data in general are
in [combat.md](combat.md); an authored move's own combo forms in [authored-attacks.md](authored-attacks.md#forms).

## The catalog

A species sheet names a compound as any move (one slot each; a sheet holds two). `data/digicube/compound_attacks.json`,
by move id:

- `forms`: tried in list order; the first whose conditions hold and that can strike from where the body stands is cast.
  Each names an `attack` of the authored, pounce or kinetic catalogs that no sheet names (an authored move by its first
  form), with optional `air` (true: only in the air; false: only on the ground), `min_reach` and `max_reach` (blocks
  between the two bodies' boxes, level) and `move` (a shot cast on the move: the legs keep their gait or leap under the
  upper body). The same attack may appear twice under other conditions.
- `stance` (optional): `draw` and `sheathe` (ticks, the length of the `<move>_draw` and `<move>_sheathe` clips),
  `draw_swap` and `sheathe_swap` (the ticks the weapon changes hands), `hold` (ticks), `cooldown` (ticks after the
  sheathe), `sounds` (`draw`, `sheathe`: sound ids played at the swaps).
- `gauge` (optional): `capacity` and `fill` (attack id to the amount one landed hit pays in; an authored form pays as its
  move).
- `chain` (0 to 10 ticks), `range` (the move's reach: a stance's draw distance; default its furthest form's) and
  `power` (default its strongest form's), which the AI's other checks read.

`CompoundAttacks.parse` refuses an entry with no forms, a form that is not one of those families (or names an authored
move's later form), a reach that runs backwards, a swap outside its clip, a gauge without capacity or filled by an
unknown attack, a chain past 10. A sheet whose moves and their forms exceed the start events' 16 attacks is refused too.

## Forms across families

`CompoundAttacks.castables` is every attack a body plays: its sheet's moves, then its compounds' forms, each once. Start
events (`DigimonAnimationEvents.start(index, mirrored, form)`), the synced sustained clip of a shot and the client's
`getAnimatingAttack` all index or search this list, so a form starts, plays and is drawn as a move does: an authored
form's combo forms by the event's form, a pounce's ground, air and run starts by its form code (0, 1, 2), a shot by its
synced name. A form's own cooldown never gates the compound; the stance and the gauge are its gates.

A pounce cast from a run plays `<clip>_run` when the model has one, on the move's own clock: it starts at the move's
`gather` tick, as the ground clip does from a run, with the same hit, contact and snap ticks. Its entry's `run_motion` (the
run clip's `attack_motion` table) gives that start its own contact points, so the server strikes where the run clip
draws the weapon and the client bursts the impact there (`PounceAttacks.Spec.forRun`). A model without a move's clip
draws the body in its gait while the move plays.

## The stance

`DigimonEntity.drawStance` (any controller may call it: the AI, an order, a body its tamer drives) runs an
`AttackStance`: the draw, the hold, the sheathe, then the move's `cooldown`. It is no attack under way: the legs stay
the body's own, the AI closes in under the draw (`readying`) and keeps after its prey through the hold. The forms strike
only while it holds; a strike begun before the hold ends plays out and the sheathe follows it. While the weapon is drawn
or sheathed the hands are busy: no attack starts (`stanceBusy`). A new form drops the stance at once.

Every client reads it from one synced int (`DATA_STANCE`: phase, the move's sheet slot, ticks into the phase; 0 none)
and starts the move's cooldown clock as it sees the sheathe end. `NativeGroundModel` draws it over the gait:

- `stances` in `ground_models.json`, by move id: `drawn` and `stowed` (part name prefixes in the mesh). Every frame the
  drawn parts show from the draw's swap to the sheathe's and the stowed ones show otherwise, over any clip's visibility
  keys (model instances are shared between bodies). Name the weapon's root: membranes it carries (a thrust's speed
  lines) still follow their clips.
- `<move>_draw` and `<move>_sheathe` play on the upper body (`upper_body`) over the gait, blended in and out over 4
  ticks; `<move>_hold` (and `<move>_hold_run`, mixed in by the gait's run share) loops on the parts its clips key (the
  weapon arm), on the hold's own clock. The hold takes the arm over through the draw's last 4 ticks and gives it back
  through the sheathe's first 4.
- A strike of the move blends in from, and out to, the gait with the hold on the arm (its clips end in the hold's guard);
  another move's strike while the weapon is out plays without the hold's parts, which keep the guard.

## The gauge

Every hit funnel calls `DigimonEntity.attackLanded(attack, victim)` once for each body a blow hurts (`hitWithAttack`,
which kinetic shots, authored volumes, bones and arcs go through; a pounce's bite; a horn or fist's contact; a spin's
blow; melee; a wrap's squeeze; a breath's or stream's pulse). A gauge it fills rises by its amount, up to its capacity.
It never drains; a cast spends it whole and there is no cooldown. It is saved (`AttackGauges`, by move id) and the sheet's
first gauge move's share is synced (`DATA_GAUGE`). Orders on it are refused until it is full
(`gui.digicube.wheel.refused.charging`, read out with the share: `CommandWheelReadout.Refusal.CHARGING`).

## Chained strikes

In the last `chain` ticks of one of a compound's strikes, the next compound cast cuts in (`chainOpen`, the combat goal's
`chainStrike`): the strike ends where it is and the next starts at once, an authored combo going on to its next form.
On every client a start that cuts into a clip still playing blends in from the pose that clip stopped in, not from the
gait (`chainFrom` on the render state).

## The AI, orders and the tiles

The AI (`chooseCompound`, before a sheet's fallback order): a full gauge's form first, then a held weapon's strike, then
the draw from within the move's range and in sight. Every form is chosen by the rules above from where the body stands,
so a run into reach of a stab casts its run start and a body in the air casts its air form. Prey on a ledge above, out
of every form's reach, is leapt at (with a held weapon or a full gauge whose forms include a pounce that may go in the
air) and struck from the top of the leap (`leapToStrike`, as a pouncer's `leapToPounce`). An order on a stance move
draws and then strikes its target; the order is carried out by the first strike.

A compound's tile (`RiderAttacks`): a gauge fills round the clock as its hits land and burns with the rush's amber rim
once full; a stance's tile burns while its weapon is out and cools round the clock after the sheathe. The party card
draws a gauge's share as an amber bar under the health bar, breathing once full (`PartyHud`).

## Checks

- `leomon_checks` ([testing.md](testing.md#index-of-checks)): the stance's clock and cooldown, strikes only while it holds,
  the slash combo (its forms in order, chained, each hit filling the gauge), the stab from a run and from the air, a
  leap at prey on a ledge, the fist refused until full, the gauge kept through a save, the punch spending the gauge and
  throwing its victim, the shot at range, an order.
- `:common:speciesTest` (`CompoundAttacksRegressionTest`): the catalog, castables, the stance's code, refused entries.
- `:fabric:nativeStanceTest`: every model naming a stance shows and hides its parts by the stance, plays its clips at
  the stance's lengths, and poses through the draw, the hold, the sheathe and strikes.
- `:fabric:commandWheelTest`: the charging refusal and its percentage.
