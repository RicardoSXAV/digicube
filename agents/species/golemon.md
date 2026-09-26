# Golemon (`golemon`)

Species notes for Golemon, the tank. Mechanics built for it live with their topics: the Crack mark in
[combat.md](../combat.md#combat-marks), the stride rule in
[locomotion.md](../locomotion.md#stride-and-planted-gaits). Design: `../design/golemon-integration.md`.

Golemon's gait is generated, not keyed: `../harness/v2/tools/make_gait.py golemon` solves leg IK so the stance
foot is fixed to the ground (drift < .05 model px, verified on the written keys) and writes four lattices on
one phase: `walk`, `walk_back`, `strafe_left`, `strafe_right`. Forwards the foot rolls (heel edge, flat, front
edge with the toes still flat; the edge on the ground is the fixed point) and the stance shortens into a bound
above half amplitude, because his legs (45 px, ankle resting 13 px ahead of the hip) only sweep about 44 px
under a pelvis at rest height: a flat foot and a dropped pelvis bent the supporting knee 105 degrees. Thigh
yaw holds the knee's width. The clip is 27 ticks (`cycle_ticks`), keys every half tick.
