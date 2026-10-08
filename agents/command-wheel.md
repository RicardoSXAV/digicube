# Command wheel: orders, attack control and the cursor

Read this before changing the command wheel (`CommandWheelScreen`, `CommandWheelReadout`, `CommandIcons`) or
universal control: which attacks a partner uses on its own (AUTO, `ManualAttacks`) and the attack orders the wheel
gives (`DigimonEntity.orderAttack`, `AttackOrders`, `PartyManager.attack` and `auto`).

## Opening, layout and pointing

The wheel is the Digivice's: `key.digicube.command_wheel` (middle mouse by default; vanilla's pick block moves to
B) opens it only with a Digivice in the inventory, and the server checks the same. It opens on the selected
partner, on the partner under the crosshair (outlined in blue), or from the saddle on the mount. No pause, no
blur: a `VOID` veil at 0x80 keeps the world and the party strip readable. While it is open the cursor is the
system cross (`GuiGraphicsExtractor.requestCursor(CursorTypes.CROSSHAIR)` every frame), not the arrow.

`CommandWheelReadout` holds the layout in GUI units from the wheel's centre: the partner panel, 116 × 120, with
the slot tabs, the 40-unit hub (sprite, data grid, scan line, amber corners), the name, the attack tiles (24 × 24,
6 apart, Q and E caps on their corners), the AUTO switches under them (24 × 10, a lamp and AUTO in the 3 × 5
readout glyphs) and one caption line; the four order keys (68 × 48) eight units off the panel's sides, rows at
-54 and +6; the Digivice key (96 × 20) at +68. The centre is the screen's, moved right when the party strip would
sit under the left keys (`centerX`; at 426 × 240, the 4K, 1440p and 720p size, it clears the strip by 2 units).

- **Pointing** (`pick`): outside the panel the whole quarter of the screen is its key; inside, only the tiles and
  the switches are targets and the rest points at nothing; the Digivice key wins over its quarter.
- **Letting go** gives what is pointed and closes: an order, a cast, a switch flip, the Digivice. A tap (released
  within 6 ticks pointing at nothing) latches the wheel open for clicks; flipping a switch by click keeps it open.
  The key's state is polled every tick: its release event is lost when it comes up before the screen opens.
- **Keys**: Q and E cast the first and second attack, Space steps to the next partner, Esc closes.
- **Orders**: Stand still / Follow, Call off (Ride instead when the wheel was opened by aiming at a partner at peace
  that would carry its owner), Recall, Digivolve / Revert. An unavailable key dims (`Reason`). Before a partner's
  first digivolution, Digivolve (caption CHOOSE A FORM) opens the Digivice on its tree to choose the form
  (`PartyClient.openTree`: `OPEN`, then the screen opens on that sheet); once bound (`PartyMemberView.line`) it
  digivolves straight into its form (caption INTO <FORM>). The Digivolve key (V) does the same.
- **Caption**: a refused order's reason in red (`PartyHud.notice` puts it on the strip card for 40 ticks once the
  wheel has closed); else what letting go would give, or why it cannot; at rest TARGET · its name (the target's
  entity id travels in `PartyMemberView.target`), IN SIGHT · the enemy an order would go at, or NO TARGET.
- **Tiles**: the art in `textures/gui/attack/<id>.png`, its `_off` twin swept round the clock while it cools, the
  seconds on it (`RiderAttacks.wheelTile`); an attack without art wears its kind's 16 × 16 glyph
  (`CommandIcons.glyph`). The clocks are the client's own (`seenCooldown`, and the stream tank the server syncs
  for every owned Digimon); a partner out of sight shows its tiles ready. From the saddle the tiles are the
  mount's rider attacks with no switches, and the line under them reads RIDING · Q E CAST.

## Universal control

Every attack is AUTO by default: nothing changes for a tamer who never opens the wheel.

- **Manual** (AUTO off): the AI never picks the move. `DigimonEntity.aiAttacks()` is what the AI chooses from:
  the fallback order, the pounce and breath combo, the wrap planner and its chill loop, positioning, attack
  spacing, the jet charge (`DigimonAttackGoal.engage`) and the thrower's throws (`ThrowerBrain`). The chase,
  dodging and the band stay. With every move on manual the Digimon follows its target (4 blocks off, or its band)
  and starts nothing.
- **Saved** per Digimon: `ManualAttacks.TAG` in its entity data, a list of attack ids, absent while everything is
  AUTO. It lives through recall, the Digispace and a reload; a new form's attacks start AUTO, the ones it keeps keep
  their setting. The snapshot carries a mask by sheet slot (`PartyMemberView.manual`). `PartyActionPayload.AUTO_ATTACK`
  (value `slot << 1 | auto`) sets it on the live body or in the stored data (`PartyMember.setManual`); the wheel
  shows the flip at once.
- **Orders**: `PartyActionPayload.ATTACK` (value: sheet slot) runs `PartyManager.attack`. The order goes at the
  partner's live target, else at `AttackOrders.sighted`: the enemy on the owner's crosshair within 24 blocks, in
  line of sight, that the partner would fight (hit parts count as the body). With neither it is refused
  (`gui.digicube.wheel.refused.no_target`); a move more than `ORDER_GRACE_TICKS` (40) from ready (`readyIn`: its
  cooldown, a tank's refill, a thrown weapon still away) is refused too (`cooling`), and so is a move behind a gauge
  that is not yet full (`charging`, read out with its share: [compound-attacks.md](compound-attacks.md#the-gauge)); nothing
  is spent. The wheel checks the same before sending (`CommandWheelReadout.attackRefusal`), and its caption reads a
  charging tile's share.
- A taken order stands `ORDER_TICKS` (100): `chooseAttack` returns its move the moment it is ready and in reach
  (`orderStrikes`) and nothing else, positioning goes for that move's reach, a range holder closes in. It works on
  AUTO moves too. It ends when its move starts (`orderCarriedOut`, rushes, whips, throws and the jet charge
  included), when it lapses, on Call off, under a rider, when the target is gone, or with a new form.
- **Riding**: the tiles, Q and E cast the mount's rider attacks (`RIDER_ATTACK`), as on the HUD. AUTO is for the
  Digimon on its own feet.

## Checks

- `:fabric:commandWheelTest` (`CommandWheelRegressionTest`): the keys and their reasons, pointing, the layout and
  the strip offset, attack refusals, the icon and glyph sizes.
- `:common:partyTest`: the manual set saved and edited in stored data, the mask, the snapshot and order codecs.
- `DIGICUBE_SCENARIO=order_checks` (`fabric/.../dev/OrderScenario`): a wild hunter against a still dummy. A manual
  move is never cast (Agumon, Garurumon's pounce, Mojyamon's throws), all manual follows and waits, an order casts,
  walks in from 19 blocks, lapses, is refused cooling and kept near ready, the crosshair picks through the partner
  and not through a wall, the settings survive a save. Verdict: `[order-checks] RESULT 10 of 10 checks passed`.
