# Mojyamon (`mojyamon`) and thrown weapons

Species notes for Mojyamon, the only thrower so far: the returning bone, the charged icicle, the thrower's AI,
its clips, and throwing from the saddle. When a second species throws, move the generic parts to a topic doc.

## The returning bone

Thrown weapons (Mojyamon, `data/digicube/thrown_attacks.json`, hand anchors in `thrown_motion/<species>.json`).
`RETURNING_THROW`: a bone carried on the back (`carried` part in
`ground_models.json`, synced `DATA_BONE_CARRIED`) flies a `BoomerangPath` fixed at the release (out to the
range, back to a catch point `catch_side` blocks aside; in height it dips from the hand to `cruise_height` and
comes home at the catching fist's height), striking each enemy once each way; caught on the last
`catch_window` of the return, otherwise it drops and is picked up or regrows after `drop_ticks`. The catch
clip starts when the bone will be within `catch_radius` of the fist on the clip's contact tick (the hands set
and reach while it flies on; only its last two ticks blend into the fist), so the brain must have the fist in
place that contact lead early. It is drawn spinning about an axis that leans from nearly upright off the hand,
into its turn, to flat coming home (`BoomerangEntity.spinAxis`). The throw is overhand with a stride, hips
before trunk before arm, and the catch is two-handed beside the body.

Its width is 1.95 on purpose; see [locomotion.md](../locomotion.md#wide-bodies-and-pathing).

## The charged icicle and the thrower's state

`CHARGED_THROW`: formed, held (charge over `charge_ticks`) and thrown on a solved ballistic arc
(`Ballistics`); every number is a [tap, full] pair; ground travel is capped at the walk while forming or
holding (`DigimonEntity.capChargingPace`) and a blow breaks a charge past `ThrowerState.BREAKS_ABOVE`.
`ThrowerState` runs the stages (it owns `activeAttack` for them: `tickAttackTimeline` skips them) and plays
the clips on the sustained channel; a clip whose name is a blend (`icicle_hold`, `icicle_throw`) mixes by the
synced `DATA_THROW_CHARGE`.

Thrower clips play on the upper body over the gait (`NativeGroundModel.thrownPerformance`), so `upper_body`
must not carry the legs: Mojyamon's hierarchy is re-rooted (mojyamon > pelvis > waist > body).

## The thrower's AI and checks

The AI is `ThrowerBrain` (throw planning over headings/sides/ranges against predicted enemies and a reachable
catch, catch interception, fetch, charge by expected damage per tick, openings), walking facing its enemy
through `FacingWalk` and `DigimonMoveControl.walkFacing`; `ThrowerFetchGoal` catches and fetches out of a
fight. The generic chooser never starts thrown attacks (`canAttackFrom` refuses them).

Check with `DIGICUBE_SCENARIO=thrower_checks` (`[thrower-checks] RESULT n of n`) and
`:fabric:nativeMojyamonTest` (drawn fist vs server anchors).

## Clips

The gait is planted. The bone's arms (throw, catch, pickup) are two-bone IK in the body's frame, and no arm
segment sinks into the torso or head. Its arms are 39 px on a 44 px torso: a hand cannot cross the body, so the
throw releases beside the head and the catch is two-handed across the chest's front with the elbows out. The
in-hand bone is rolled 60 degrees about its own axis in the mesh, so the fist meets the bone on the back outside
the shoulder mantle, and the three bone anchors in `thrown_motion` match those clips.

The Icicle Rod's five clips are a javelin throw: the spear held high over the right shoulder clear of the head
and aimed at the target, the glove arm pointing, the charge winding the trunk further back. Light and heavy
share key times and Euler branches so the game's linear charge mix stays between them, and form end = hold
start = release start, since the game switches those clips without blending.

## Throwing from the saddle

Ridden (2026-09-25), thrown weapons go through `ThrowerState` too, never `beginAttack`: `startRiderThrow`
throws the bone at the crosshair (`ThrowerBrain.riderThrow` finds the heading and turn that pass through the
soft target or the crosshair's spot, both ways if it can, re-aimed every tick of the wind-up), curving home on
the side of the strafe key held (left without one); a press beside a lost bone picks it up; the icicle forms
and grows while the button is held (`ThrowerState.riderHold`: no auto-release) and goes on release at the
crosshair, led. The ridden tick runs `thrower.tick` (no AI step under a rider). The rider's client caps its
own pace while the ice is in hand (`chargingPaceCap`; the server's `capChargingPace` would fight the client),
stands for the pickup (`riderAttackLocked`), and squares the body to the crosshair during the wind-up
(`THROW_TURN`). `RiderControls.catchRing` draws the bone's home as a frost ring for the rider; the bone's tile
fills as it flies home and shows the regrow when lost (`BoomerangEntity.regrowIn`, synced `LOST_AT`). The
rider sits on the crown (`rider` path ends at `head`; in every clip the arms, bone and spear stay clear of the
rider), and a look part that carries its rider
does not look around (`NativeGroundModel.ridesLook`).

## Harder throws and leaps

Harder throws (2026-09-25), rider and AI alike. The bone is `input: hold`: tapped it goes as before; held, the
throw stops cocked at `hold_at` (`BONE_HOLD`, clip blend `bone_hold`, the charge on `DATA_THROW_CHARGE`) and
goes on release (`BONE_RELEASE`, blend `bone_release`, the bone leaves at 3); the charge (`charge_ticks`)
stretches the reach from `max_range` to `far_range` (`Returning.reach`), the pace and the power
(`charge_speed`, `charge_power`) and moves the release fist to `bone_release_heavy` in `thrown_motion`. Either
weapon leaves the hand harder for the body's own motion (`ThrownAttacks.impulse`: `air_boost` more from a
leap, plus the pace along the throw as a share of its speed, 40 % at most): pace, reach, power and knockback
go with it; `ThrowerState` measures the body's travel and air time itself (a ridden body's position comes from
its rider's client). A bone thrown from a leap cruises over the floor it left (`BoomerangPath.floor`), and its
far turn climbs or sinks toward the aim (`lift`); `pace`, `floor` and `lift` are synced on the entity.

Thrown weapons fire in the air (`startRiderAttack`). Mojyamon leaps (`body.mount.jump` 0.52): the jump clip
layers over any ground gait (`NativeGroundModel.applyGround`; before only gallopers had one) and keeps the
legs under a thrower's performance. A ridden body walking onto its lost bone picks it up with no press
(`pickUpUnderRider`), and every pickup stands only until the bone is in the fist
(`ThrowerState.PICKUP_STANDS`). While the wind-up is held `RiderControls.turnMark` draws where the bone will
turn.

The AI (`ThrowerBrain`) weighs every bone throw at charge 0, .5 and 1, standing or from a leap, the icicle
standing or from a leap, and takes off `LEAP_LEAD` ticks before the weapon leaves the hand
(`DigimonEntity.leapForThrow`), at most every `LEAP_EVERY`; `thrower_checks` has `far` (a charged throw past a
tap's reach) and `leap` (a throw from a leap past a full charge's), `rider_checks` a held far throw, both
weapons from a leap and the walk-over pickup. The heavy throw is built like the tap one, both cut at the hold,
and the jump clip keeps the feet planted through the landing.
