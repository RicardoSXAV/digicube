# Centarumon (`centalmon`)

Species notes for Centarumon, the skirmisher mount. Mechanics built for it live with their topics: charges,
drawn shots, four-legged mounts, hoof beats and leaps in [mounts.md](../mounts.md); the skirmisher tactics and
the Exposed mark in [combat.md](../combat.md); the cannon's shot style in
[effects.md](../effects.md#shot-styles); the wrist chain in [animation.md](../animation.md#rope-chains).

Centarumon's gallop plays each hoof's stance 1.5 times faster about its middle (same sweep, longer flight,
stride 8 -> 12) with every hoof planted, and the whole run lattice is built from the walk and that gallop; the
`hoof_beats` table and `run_stride` match that clip and change with it. The full gallop is the ridden sprint's
(`run_cycle_ticks` 10); the AI's run plays the lattice between walk and gallop.

The mesh keeps the hind thighs' top-back edge 1 px forward, or the thigh tops show through the rump's back face
in some clips; a replaced mesh needs the same offset.
