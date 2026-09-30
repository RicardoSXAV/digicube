# Betamon (`betamon`)

Species notes for Betamon: its rowing walk and pace, its idle, Headbutt (a dash) and Electric Shock (a discharge:
a bolt that jumps between foes and runs through water). Shared mechanics: travelling sweeps and discharges in
[authored-attacks.md](../authored-attacks.md), the bolts' drawing in [effects.md](../effects.md#discharges), gaits in
[locomotion.md](../locomotion.md).

## Body and pace

Native model at `model_scale` 0.32 (1 px = 0.02 blocks); hitbox 1.1 wide, 1.46 tall. Each leg is two rigid pieces
(a 12 px leg block and a long flat foot), and at rest they stand at their full reach, the forefeet out ahead and the
hind feet out behind. Betamon swims, so its move control feeds its speed in once: on land it goes about 2.2 times its
speed attribute (`base_speed` 0.024: 0.053 blocks a tick wandering, 0.085 following at `walk_speed` 1.6, 0.104 at
`run_speed` 2.0). Set those numbers with that in mind; a pace squared would be far too slow.

## Walk and idle

All clips are planted by leg IK. The walk is a walking trot (diagonal pairs half a cycle apart, duty .66 to .56) on
a `cycle_ticks` 10 lattice (`walk_amount_0` to `_8`, `stride` 1.79 units at full amplitude, 0.057 blocks a tick,
`max_playback_rate` 2). A rigid limb only plants its claws on a straight line where it rows about the point where it
stands straight out sideways, so the stance centres move from the rest pose out to that walking posture by half
amplitude: the limbs row about the shoulders as one piece, the claws held exactly on the ground, the foot pivoting
about them, the leg block sliding along its own bone by at most 1.8 px (its top is inside the body), then rolling up
about the claws before they leave. The body bobs over the standing pair, wiggles its head toward the swinging
forefoot and waves its tail.

The idle (80 ticks) breathes twice, sways the tail and fin, looks to one side and then the other on planted feet and
gulps once between. Mixed with a partial walk while the gait starts or stops, a foot may dip up to half a model
pixel (the sum of two planted poses is not quite planted).

## Headbutt

A dashing BOX_SWEEP (22 ticks, `root_travel`, `impact_tick` 9.6): the body sinks and draws back with a rear waggle,
launches off its hind feet and is carried 3.4 blocks along the ground in four ticks, skimming low with the forelegs
folded back, and strikes with its brow (hit window 7.5-11.5): the body squashes, the head snaps up, it hops back and
shakes its head. The drive stops at the victim's box (`AttackGeometry.boxClearance`, corners included) and once the
blow has landed, so the dash never runs through its victim, and the client jumps the clip to its impact pose when
the blow lands sooner. It dashes through water too. The struck volume is the front of the head, padded 0.12 blocks
ahead of the drawn brow so a corner met on the diagonal is still struck. `headbutt_fx` draws wind streaks through the
dash and, only once it has landed (contact parts), a pixel star burst, a square shock ring, chips and sparkles at the
brow. `"particles": "ram"`: the floor kicked back at the launch (bubbles in water), puffs off the brow, a few
small white sparks.

## Electric Shock

A discharging BOX_BURST (30 ticks, discharge at 12, `range` 9): the fin crackles and shivers faster and faster
through the charge (`electric_shock_fx` arcs along the fin, a spark star swelling at its tip, client sparks), the body
rears with its jaw open and the fin flashes; the bolt leaves the tip of the fin (the motion's mouth marker). Its `arc`:
`reach` 8.5 from the fin, `cone` 75, `burst` 1.9 (foes against the body), `chain` 2 jumps of up to 5 blocks at 0.6
of the damage each, `water_reach` 7 at 0.7, `life` 7 ticks. A ring of short bolts runs out along the ground round the
body at the release (drawn only). The AI starts it only where the bolt reaches (`ArcDischarge.canReach`: a block short
of the reach, in sight of the fin, or through water it shares with the target).

Tactics: `prefer_close` (the dash whenever it is ready and reaches), `fight_speed` 1.9, a sidestep from a seen
wind-up (`dodge_chance` .3). Its sounds are not chosen yet: both moves are quiet, never a monster's growl.

## Checks

- `betamon_checks` ([testing.md](../testing.md)): the dash at small and big bodies 1.8 to 4.2 blocks away at every
  heading and at one stepping aside (struck once, stopped at its body, knocked back), into a wall (no hit), through
  water, at an ally; the bolt at 2.5 to 7.5 blocks at every heading, behind a wall (none), along a line of three foes,
  through water to foes off to the sides, the burst from the body, and a cow and an ally standing by (spared).
- `:fabric:nativeBetamonTest`: the drawn brow inside the struck volume and the bolt's emitter on the drawn fin's edge
  (eight headings, three heights, land and water), no foot under the floor at partial gait amounts.
- `gait_checks:betamon`: walk, panic and run within the walk's cadence cap.
- Duels (`betamon_vs_<prey>@...+duel`, `DIGICUBE_BENCHMARK=true`) use both moves about equally and land nearly every
  cast.
