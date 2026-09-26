# Digmon (`digmon`)

Species notes for Digmon. Mechanics built for it live with their topics: the flying mount's seat and landing
in [mounts.md](../mounts.md#flying-mounts), Gold Rush's stacked uses and volley in
[authored-attacks.md](../authored-attacks.md).

Digmon's walk is planted: both legs (thigh pitch and roll, shin, foot pitch/yaw/roll) are solved so the stance
ankle slides straight back at the body's pace with the sole flat on the ground (drift 0.000 px on the written
keys), 16 ticks, stride 4 units = .125 blocks a tick, which is his walk, ridden and fighting pace (`run_speed`
2.21 and `tactics.fight_speed` 2.21, no `body.mount.speed`; measured .124-.130 a tick chasing in a balance
trace, where the attack goal's own 1.25 had left him crawling at .04). His flight feet trail at 40 degrees (a
takeoff that spun each foot -182 degrees was replaced).

Both moves use `"particles": "drill"` (`StrikeParticles.DRILL`): drill whine and an armadillo call instead of
the growl, a grind where a grounded burst bites the floor (`bite`), one rupture per volume as each opens
(`erupt`, Big Crack's seven sections), and the missiles' ignition, entity-bound whistle, trail and burst.
