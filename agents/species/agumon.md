# Agumon (`agumon`)

Species notes for Agumon. Shared mechanics it uses: fireball aiming and projectiles in
[combat.md](../combat.md#projectiles), leaps in
[authored-attacks.md](../authored-attacks.md#travelling-sweeps-and-leaps), `look` and `attack_effects` in
[animation.md](../animation.md#generic-catalog-keys).

Agumon is a native model: the Java `AgumonModel` and the billboard `PepperBreathModel` are gone, and Agumon's
native body at Agumon's scale is the fallback for a species without a model. `:fabric:nativeAgumonTest` pins
the drawn snout to the server's table.

The claw is a leaping burst (`authored_attacks.json` `claw`, with its claw volume, motion markers and streaks):
crouch, pounce, a diagonal chop outside the cheek and across the front as it lands, either hand in turn.

Check with `DIGICUBE_SCENARIO=agumon_checks` (`[agumon-checks] RESULT PASS`: balls landed per movement kind,
facing error, leaps landed from 1.2 to 3.6 blocks, no blow before the landing, the Burn a ball leaves and
water putting it out).
