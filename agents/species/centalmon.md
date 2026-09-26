# Centarumon (`centalmon`)

Species notes for Centarumon, the skirmisher mount. Mechanics built for it live with their topics: charges,
drawn shots, four-legged mounts, hoof beats and leaps in [mounts.md](../mounts.md); the skirmisher tactics and
the Exposed mark in [combat.md](../combat.md); the cannon's shot style in
[effects.md](../effects.md#shot-styles); the wrist chain in [animation.md](../animation.md#rope-chains).
Design: `../design/centalmon-integration.md` and `../design/mounted-combat.md`.

Centarumon's gallop is `harness/v2/out/centalmon/gallop_02/make_gallop.py`: the approved gallop with each
hoof's stance played 1.5 times faster about its middle (same sweep, longer flight, stride 8 -> 12) and the
legs re-solved so every hoof stays planted, then the whole lattice rebuilt from walk and that gallop; it
prints the `hoof_beats` table. Rerun it (it keeps the approved clip as `backup_animation.json`) and change
`run_stride` with it. The full gallop is the ridden sprint's (`run_cycle_ticks` 10); the AI's run plays the
lattice between walk and gallop.

Centarumon's mesh JSON is edited past its blend: the hind thighs' top-back edge sits 1 px forward
(`harness/v2/out/centalmon/haunch_fix_01/fix_haunch.py`, `measure.py` checks every clip), or the thigh tops
show through the rump's back face; redo it after any re-export.
