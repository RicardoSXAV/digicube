# Dinohyumon (`dinohyumon`)

Species notes for Dinohyumon: his two moves and their forms, the model's knife hand, wrists and trousers. Mechanics
built for him live with their topics: forms, travelling sweeps, leaps and the `steel` style in
[authored-attacks.md](../authored-attacks.md#forms), cloth and sleeve bends in [animation.md](../animation.md#cloth),
run blending in [locomotion.md](../locomotion.md).

## Lizard Dance

Three stacked uses (`charges` 3, each back after 80 ticks), each cast one of three forms that the AI plays as a combo
(dash, right, left; up close it opens with a cut):

- `lizard_dance` (key `forward`): the dash. A four-tick crouch with the knife drawn back at the hip, a flying lunge of
  about three blocks and a rising knife thrust at full reach (window 7-10). The drive stops at the victim's body, and
  no knockback keeps the victim in front of the next form.
- `lizard_dance_right` (key `right`): the right forearm blade, a backhand horizontal cut sweeping out to the right side
  (3.5-6.5), no dash.
- `lizard_dance_left` (key `left`): the left blade with the knife run on along the forearm, the mirrored cut and the
  finisher (4.5-7.5, knockback .45).

The forearm blades stand out of the forearm along the elbow's own axis, so they cut edge-first only when the arm is
raised forward and swept about the shoulder; a cut that bends the elbow would strike with the flat. Each form draws its
own clip of `lizard_dance_fx`: the cuts a flipbook arc along the blade's path, the thrust a streak and two dust puffs
that stay where they were raised; a star and sparks only after a hit.

## Akinakes

Three leaps (`form_choice` reach): `akinakes` 2.8-5 blocks, `akinakes_mid` 4.8-7.6, `akinakes_long` 7.2-10.2. A
longer leap crouches longer, flies higher and longer (8, 12 and 16 ticks) and hits harder (power 2.2, 2.8, 3.5), so it
is easier to see coming and to walk away from; the AI leaves one whose landing it cannot trust. The right hand
cross-draws the greatsword from over the left shoulder, the chop buries it about two blocks ahead (`lead` 1.9), the
knife fist plants beside the feet, and the blade goes back on the back. `akinakes_fx`: the chop's arc, one ring on the
floor, a crack, four chunks and a star at the tip; the same sheets for every leap, the ring wider for a longer one.

## Controls

When Dinohyumon can be controlled, a left click with W, D or A casts the form with that `key` (`AttackForms.rider`),
and the three uses make the dash, right and left combo. Holding the right click to choose Akinakes's distance is not
built: the three leaps are its steps.

## Model

The knife hand rests thumb-forward, as the right hand does, so the knife points ahead out of its hammer grip. Both
hands hang from geometry-less `wrist_L` and `wrist_R` (identity at rest): the hands rest at a quarter turn of yaw, the
ZYX singularity, where a small bend has no small angles, so clips turn the wrists.

The trousers are one garment: each leg is three sleeves of one 16 x 18 px section, `trouser_seat_*` on the pelvis (from
under the torso to the hip pivot, closed on top), `trouser_thigh_*` (hip pivot to knee pivot) and `trouser_shin_*` (knee
pivot to the frayed foot), mitred at the hip and the knee by four `bends`, with one fabric strip per face from the belt
to the hem (folds, shadowed back and the outer seam's stitches unbroken). The pelvis ends 1 px under the hip pivot, so
its corners stay inside a swinging leg's sleeves (at 6.5 px they were the bare butt that showed through a stride), and
wears the crotch's cloth between the legs. The thighs have no skin (the bones stay): a lunge twists a thigh 30 degrees
in its sleeve and folds the sleeve's front into the hip's crease, and the skin came through both.
The cloth's colliders are points on the sleeves' fronts, one 5 px past the knee pivot where the mitred knee's corner is.

`:fabric:nativeDinohyumonTest` pins the blades and the sword inside their cuboids at every heading, the dash's travel,
the leaps' order and impact, the effect clips, the forms' events and keys, the knife's direction, the trousers (closed
seams, no face inside out, the pelvis inside the sleeves, through the gait and every attack a quarter tick at a time)
and the cloth (pushed by the thighs, never flying up or swaying aside). `DIGICUBE_SCENARIO=dinohyumon_vs_<prey>` logs the
forms as they are cast (`caster starts lizard_dance_right`).
