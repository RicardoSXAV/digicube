# The Digivice item and the recall chip

Read this before touching how the Digivice is handed out, bound, stored, dropped, found, recalled or drawn in
flight.

The Digivice is handed out, never crafted: `StarterFlow.handDigivice` (common) runs first on every join and
gives one to any non-spectator the `digicube:starters` data has not marked yet, so it is one per player per
world and legacy tamers get theirs too. `Digivices` binds that item to an owner and rotating credential in the
world's `DigiviceSavedData`; extras and stale copies are rejected, foreign devices cannot authorize the UI or
be picked up. A player is never left without one: an unbound Digivice (creative tab, command) reaching an
inventory whose device is not with its owner becomes the device (`Digivices.reconcile`, the old credential
revoked by `Digivices.revoke`).

A device neither held nor dropped is found by `DigiviceStorage` around where it was last seen
(`DigiviceSavedData.seen`, updated from hands and open menus): containers in the 5x5 chunks there (the 3x3
loaded for it), item-holding entities within 40 blocks (minecarts, frames, mob hands, pack animals), shulker
boxes and bundles inside them, then the owner's ender chest. The chip takes it from there and it flies out
(the lid opens, `DigiviceStorage.open`; a device inside a block leaves through its open face,
`RecallPath.surface`). Nowhere found, it is summoned from the ledger's stack snapshot, straight down out of
the sky. The old copy is dead: any container a player opens drops revoked copies at once.

Partners live in the Digivice: the tick it leaves its tamer, `PartyManager.checkDevice` sends every deployed
partner to the Digispace (a mount carrying its rider waits until they are down) and nobody is deployed or
given out meanwhile. The creative inventory's cursor is client-only, so the client reports a Digivice held
there (`DigiviceCursorPayload`, believed only in creative) and creative alone waits `CREATIVE_GRACE_TICKS` (3)
for that report.

`DroppedDigivice` replaces ordinary item drops, settles face-up, sinks in liquids, survives damage/despawn and
floats at minimum Y + 1 above the void. Saved drop addresses feed the client golden locator up to 512 blocks
without chunk tickets. The beam waits eight ticks after settling, then fades in over eight; pickup explicitly
withdraws its signal. The owner's drop gets a distinct Digivice icon in the vanilla locator bar, even beyond
beam range in the same dimension. `/give <player> digicube:digivice` replaces that owner's existing
credential/device via `MixinGiveCommand`, never creates an additional device. Headless checks:
`digivice_checks` and `digivice_checks_reload`.

## The recall chip

`RecallChip` recalls the owner's dropped device into the used hand with no distance/dimension limit or chunk
tickets. The packet goes to the owner and, without the credential (`withoutToken`), to every player near
enough to see them; `RecallVisuals` keeps one recall per recipient entity id. The owner's first-person view
plays it as below; any other camera (their own third person, other players) flies it as a plain world motion
(`RecallFlight.View.body`, no squeeze) into the hand the player model draws, read each frame by
`MixinItemInHandLayer` (`thirdPersonDisplay` on the hand anchor), which also hides that hand's item until the
landing; `thirdPersonHeld` is the resting hand until it is drawn. The chip breaks and the catch flashes there,
billboarded; watchers hear the cues from that hand.

The ledger snapshots stack components and revokes the old token before delivery; chip remainders require a
free slot. `RecallMotion` breaks the chip's exact pixels apart; `RecallFlight`/`RecallVisuals` keep the
dropped model's world pose, spin it up to a hover and fly it from where it lies (within the receiver's view
distance, `RecallChip.flyRange`, sent in the packet so the cooldown matches: a device the player can see never
vanishes; a long flight holds about 45 blocks a second), or bring it in from the remote source's bearing after
a distance-based wait capped at 4 seconds.

`RecallPath.Planner` plans the route on the client: open arcs first, then (over 24 blocks) a skyline arc over
the upper hull of the terrain under the line, else an open-air-only cell A* over a wide area (run in 1.5 ms
slices per frame, finished on demand), then a digging A*, smoothed with centripetal Catmull-Rom; only a buried
device (straight up) or a sealed room crosses blocks. A distant device enters 24 blocks out along a line of
open air nearest its bearing (steeply from the sky in a ravine).

`RecallFx` draws the shooting star (per-vertex-coloured streak, glinting head, stateless stardust) with the
beacon shader; the four cues are `DCSounds.RECALL_*`, and the flight rush follows the device. The flight ends exactly on the held pose (no
separate catch lerp); near the eye it moves to the hand pass. Note the 26.2 hand pass's pose stack starts with
the *inverse view rotation* (`GameRenderer.renderItemInHand`), so a view-space matrix must be premultiplied by
the camera orientation, and `RecallFlight.handMatrix`'s view-space squeeze makes both passes project
identically.

`RecallJourney.arriveAt` is when the device lands: the server cooldown ends there, and until then the device
is hidden from every slot drawing and the item-name banner (`MixinGuiGraphicsExtractor`, `MixinHud`). Custody
is already safe in inventory; source chunks are never loaded for visuals.

Verify `recall_checks` (lost and stored devices, creative-tab replacement, partners without the device),
`recall_checks_reload`, and `fabric:recallReview` (flight/packet/projection checks, the view from outside;
`outside_*` frames with evidence).
