# Agumon (`agumon`)

Species notes for Agumon. Shared mechanics it uses: fireball aiming and projectiles in
[combat.md](../combat.md#projectiles), leaps in
[authored-attacks.md](../authored-attacks.md#travelling-sweeps-and-leaps), `look` and `attack_effects` in
[animation.md](../animation.md#generic-catalog-keys). Design: `../design/agumon-integration.md`.

Agumon is a native model (harness `out/agumon/motion_02` clips, installed by `integration_01`; the body is
`mouth_quality_01` since `integration_02`, whose `retarget.py` recomposes the hand keys onto a changed hand
rest: rerun it after any rest change); the Java `AgumonModel` and the billboard `PepperBreathModel` are gone,
and Agumon's native body at Agumon's scale is the fallback for a species without a model.
`:fabric:nativeAgumonTest` pins the drawn snout to the server's table.

The claw is a leaping burst (`authored_attacks.json` `claw`, clips by
`../harness/v2/out/agumon/claw_leap_01/author_claw.py`, which also writes the claw volume, the motion markers
and the streaks): crouch, pounce, a diagonal chop outside the cheek and across the front as it lands, either
hand in turn.

Check with `DIGICUBE_SCENARIO=agumon_checks` (`[agumon-checks] RESULT PASS`: balls landed per movement kind,
facing error, leaps landed from 1.2 to 3.6 blocks, no blow before the landing, the Burn a ball leaves and
water putting it out).
