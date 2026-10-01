# Meramon (`meramon`)

Species notes for Meramon: his planted gait, his body of fire (self-lit, fireproof), Fire Fist (a burning dash punch)
and Heat Wave (a breath of square fire from both palms). Shared mechanics: breaths and Burn in
[combat.md](../combat.md), authored dashes in [authored-attacks.md](../authored-attacks.md), the flame renderer in
[effects.md](../effects.md#breaths-and-pounces).

## Body and gait

- Native model at `model_scale` 0.26; hitbox 1.0 wide, 2.35 tall. His clips are planted: leg IK with the pelvis height
  solved per frame (his rest legs are straight); stance feet roll heel, flat, ball with the toes kept flat; flame
  sheets flicker in the clips and trail and rise in world space. A strafe's feet close by one side stride, so the side
  gait stands wider as its stride grows. The attack clips re-solve both legs every sample, so a foot that stands stays
  where it stands.
- A body of fire: `glow` in `ground_models.json` draws him full-bright, day or night, and `body.fireproof` on the sheet
  keeps fire and lava from burning him (so he is never Burned). Voice: the blaze's at 0.75, its shot as his move cry
  (`voices.json`).
- `aim_path` is his torso (an attack's aim pitch bends him at the waist), `attack_blend_in` 2, `attack_blend_out` 5.
- Tactics: `prefer_close` (the fist whenever it reaches, the breath further out), `fight_speed` 1.5, dodge 0.3.

## Fire Fist

An authored dash (`authored_attacks.json`, BOX_SWEEP with `root_travel` and `impact_tick` 12, 28 ticks): he drops into a
stance with the right fist chambered at his ribs while it catches fire, the dash carries him 3.6 blocks along the travel
curve (6.5 to 12) leaning in, the left knee driving, and the right arm snaps straight through at the impact with the
shoulders, the left fist pulled back to the hip; a lunge, then back to his stance. The burning fist is part of his body
(`fire_fist` and its `ff_*` pieces under `hand_r`, membranes shown from tick 1 to 25), so it follows the hand through
any blend; the clip flares it, streams its tongues back through the dash and bursts it wide at the impact. The struck
volume is the burning fist, padded, in the window 10.5 to 14; the motion's `mouth` is the fist's centre.

- Effect `fire_fist_fx` (emissive): flame tongues off both feet through the dash, and where the blow lands (contact
  parts) a flash of the fist's flares, a ring of fire, a star of tongues and flung embers.
- `burn` 80: a landed blow sets its victim alight for 4 s, a Burn. `particles` `fire` (`StrikeParticles.FIRE`): a
  fire's roar and the fist igniting as it is drawn back, a burst of flame and the floor kicked back as the dash sets off,
  flames and smoke off the fist through the dash, and a blast of flame, embers and smoke where it lands.

## Heat Wave

A breath of puffs (`breath_attacks.json`, `mark` `burn`, clip `heat_wave`, 96 ticks): he gathers the heat before his
chest with the right foot stepping back into a braced stance, thrusts both hands forward side by side (palms out,
fingers spread) and pours the stream from tick 10 to 79, the arms and body pushed back softly at every damage pulse (no
tremor); the exhale lowers the arms and steps the foot back in. Nothing hangs from the hands: the stream leaves just
before the two palms (the motion's `mouth`), pitched about the torso's pivot (its `head`).

- Puffs leave at 1.35 blocks a tick, keep 0.9 of it a tick, rise a little (hot air) and live 12 ticks: a reach of about
  9.7 blocks. Radius 0.16 at the palms, 0.46 about five blocks out, 0.24 at the tips, fitted to the drawn stream.
- Contact keeps the victim alight 3 s (a Burn, which also ends any ice on it) and pulses damage every 10 ticks; snow it
  crosses or passes low over melts and ice turns to water (`melt`, with `mobGriefing`, never within two blocks of the
  palms). Drawn as `heat_wave_fx`'s square fire (`hw_` sections, ridges as tongues, forks as tips) at `pixel` 0.018,
  each dying puff cooling toward deep red (`cooling`); flames lick off the stream, smoke rises off its end, and where
  it strikes flames spread with smoke and spat embers. Sounds: a fire charge as it starts, a fire's crackle looped.
- The AI turns its whole body with the aim (no pivot clips), at the breath's `turn` 14 degrees a tick, after its
  prey's led chest read through a filter (`breathLead`), so prey stepping about never jerks it round.

## Checks

- `meramon_checks` ([testing.md](../testing.md)): Fire Fist from 4.6 blocks out (a 3.6-block dash that stops at the
  prey's box, one hit, alight) and from right in front (it barely travels); Heat Wave at prey 7.5 blocks out (pulses,
  kept alight), at prey off to its side (the body comes round), at prey strafing, jumping about and a Golemon fighting
  back (the body's turn never twitches), over snow and ice (both melt); and standing in a fire unhurt.
- `:fabric:nativeMeramonTest`: every standing sole point stays on the ground in the installed gait clips, the idle's
  feet stand still; the burning fist shows only through its clip and at the impact is where the motion and the struck
  volume put it; Heat Wave's palms sit side by side with the mouth just before them and its hands move slowly through
  the stream (no tremor); and its flame as the
  breath renderer lays it (its art's own blocks, broadest partway out, as wide as the breath strikes, whole when
  whipped, no two faces on one plane).
- `gait_checks:meramon`: walk, panic and run all PASS.
