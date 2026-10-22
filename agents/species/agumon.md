# Agumon (`agumon`)

Species notes for Agumon. Shared mechanics it uses: fireball aiming and projectiles in
[combat.md](../combat.md#projectiles), leaps in
[authored-attacks.md](../authored-attacks.md#travelling-sweeps-and-leaps), `look`, `paws` and `attack_effects` in
[animation.md](../animation.md#generic-catalog-keys), planted gaits and run lattices in
[locomotion.md](../locomotion.md#stride-and-planted-gaits).

Agumon is a native model 56 px tall drawn at 0.375 (1.3 blocks), one texel per model pixel; Agumon's native body at
Agumon's scale is the fallback for a species without a model. The rig: `root/pelvis` carries the belly, the tail
(`tail`, `tail_tip`) and the legs (`<side>_thigh`, `_shin`, `_foot`, `_toes`); `torso` carries the chest, the arms
(`<side>_upper_arm`, `_forearm`, `_hand`, `_finger_<front|middle|back>` and their `_claw_` blades) and `neck/head`, whose
`jaw`, ears and eyes move on their own. Each eye (`<side>_eye`) holds a `_pupil` and an `_eyelid`: the eyelid rests
behind the head's wall, and a blink brings it out in front of the pupil (0.29 px) and closes it from the top (its y
scale about the eye's top edge); a glance moves the pupils inside the iris. Stacked sheets (eyeliner, iris, pupil) keep
0.06 px between layers, and faces that would lie on one plane (thigh and shin sides, the elbow, the wrist, teeth on the
jaw's walls) are parted by 0.08 px, so nothing z-fights in game.

Clips: `idle` (a 10 s loop: breathing, the weight rocking from foot to foot, blinks, glances, ear twitches, a claw
flex, a tail flick, a lip smack), the directional walk lattices (`walk`, `walk_back`, `strafe_left`, `strafe_right`;
the strafe never brings a foot inside its rest place, the feet stand 1.6 px apart), the pivots (two unhurried steps to
a quarter turn, 80 degrees a cycle on the walk's beat: `pivot_stride` 0.5466, `pivot_cadence` 1), the run lattice
(`run`: leaning into it, each foot sweeping a long stance and kicked up behind as it leaves the ground, the arms held up
and forward), `jump`, `claw` and `claw_mirrored`, and `pepper_breath`. The legs are short (the hip 17.6 px up), so the
walk covers only slow paces: the run takes over from 0.075 blocks a tick (`run_from`, about where a biped of its leg
changes gait), its beat rising with the pace (`cadence_rise` 0.4: a jog's stride in about 9 ticks, a full run's in 6), and
`max_playback_rate` 4 lets a fleeing Agumon (0.39 blocks a tick) scamper faster rather than slide. The feet are heard
where they land (`paws`, `footfalls`).

On its own AI it strolls at its walk (`stroll_pace` 0.06), its neck reaches 50 degrees (`head_turn`, as far as the
model's look turns the head and neck) before its body comes round, and as a partner it sets off after its tamer from 6
blocks, keeps to them 3 blocks off and runs after a sprinting tamer (`run_speed` 1.3, 0.34 blocks a tick); standing with
a tamer who stays put it potters about near them ([locomotion.md](../locomotion.md#moving-on-its-own-strolls-following-and-making-way)).

The claw is a leaping burst (`authored_attacks.json` `claw`, with its claw volume, motion markers and streaks):
crouch, pounce with the claw cocked over the head, a chop over the top and down across the front as it lands, either
hand in turn.

Pepper Breath leaves from the middle of the mouth's front, halfway between the upper lip (on `head`) and the lower lip
(on `jaw`), so the muzzle opens with the jaw (`attack_motion/pepper_breath_muzzle.json`); `pepper_breath_fx`'s
`mouth_charge` grows the ember there, between the lips, from tick 6.5 to the release at 12.

Check with `DIGICUBE_SCENARIO=agumon_checks` (`[agumon-checks] RESULT PASS`: balls landed per movement kind,
facing error, leaps landed from 1.2 to 3.6 blocks, no blow before the landing, the Burn a ball leaves and
water putting it out) and `gait_checks:agumon` (every AI pace planted under its cap) and `locomotion_checks` (wild and partner, as a client sees
them: no sidesteps, no stepping on the spot, no small turns, a steady head). `:fabric:nativeAgumonTest` pins
the drawn mouth to the server's table, the ember to the mouth, the claw inside its volume and the gait's numbers.
