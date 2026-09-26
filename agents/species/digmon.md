# Digmon (`digmon`)

Species notes for Digmon. Mechanics built for it live with their topics: the flying mount's seat and landing
in [mounts.md](../mounts.md#flying-mounts), Gold Rush's stacked uses and volley in
[authored-attacks.md](../authored-attacks.md).

Digmon's walk is generated, not keyed: `../harness/v2/out/digmon/mount_01/gen_walk.py` solves both legs (thigh
pitch and roll, shin, foot pitch/yaw/roll) so the stance ankle slides straight back at the body's pace with
the sole flat on the ground (drift 0.000 px on the written keys), 16 ticks, stride 4 units = .125 blocks a
tick, which is his walk, ridden and fighting pace (`run_speed` 2.21 and `tactics.fight_speed` 2.21, no
`body.mount.speed`; measured .124-.130 a tick chasing in a balance trace, where the attack goal's own 1.25 had
left him crawling at .04); `install.py` beside it writes it and the flight feet (the approved takeoff spun
each foot -182 degrees; they now trail at 40) into `digmon.animation.json`, always from its
`backup_animation.json`.

Both moves use `"particles": "drill"` (`StrikeParticles.DRILL`): drill whine and an armadillo call instead of
the growl, a grind where a grounded burst bites the floor (`bite`), one rupture per volume as each opens
(`erupt`, Big Crack's seven sections), and the missiles' ignition, entity-bound whistle, trail and burst. The
sounds are synthesised by `../harness/v2/out/digmon/sound_01/make_digmon_audio.py`.
