# Crabmon (`ganimon`)

Species notes for Crabmon. Travel facing, built for it, is in [locomotion.md](../locomotion.md#travel-facing).

Its directional gait walks forward, back and side-on. The lattices never turn the root. They are a wave gait (a
step runs rear to front on each side, sides half a cycle apart, duty .58; two-bone leg IK holds each contact
still, drift under 0.2 px after key reduction): 12 ticks, 52 px forward and side-on (`stride`, `side_stride`
3.25), 44 px back (2.75), `max_playback_rate` 3.5; a side-on stance centres 6.5 px inward (the front legs'
reach). His upper legs and arms rest a few degrees from the ZYX singularity, so the clips turn the
geometry-less `leg_*_root` / `arm_*_root` parts (identity at rest) and leave the uppers at rest: turning the
uppers flipped their angles by half a turn mid-swing.

Scissors Execution is a leaping BOX_BURST (`leap`: launch 7, land 13, lead .65 from the prey's side, apex .85;
range 4.5): a cricket's crouch on the big hind legs, the kick, the pincer cocked open in flight, the small
claw's jab on landing and the pincer's chop snapping shut at 14.5 (window 13.5-16); the cut effect keeps the
same relation to the cutting point until the snap, and the pincer volume and the motion markers follow the
swing. `"particles": "pincer"` adds a springy kick and a shell-rattling landing (`StrikeParticles`
release/landing).

Water Shot is a KINETIC_SHOT from the mouth, `"shot_style": "water"` (a spit, drips, a splash;
`ShotStyle.douses`: it puts out a burning victim), drawn at `projectile_scale` 1.75 with its launch rim as the
caster's `attack_effects`.

Tactics hold 2.5-5 blocks between moves (a backward walk facing the enemy, circling side-on). Check with
`DIGICUBE_SCENARIO=gait_checks:ganimon` (the pace against the stride, and how far the body faces off its
travel).
