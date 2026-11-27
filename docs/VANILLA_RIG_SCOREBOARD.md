# Vanilla-compat creature wishes — the e2e scoreboard

**One ledger for "how far are we".** Every claim about a wished creature rendering, moving and
behaving correctly on a *stock* client sits here with its status, the gate that holds it, and the
number that was measured. Add a row when you start a claim; move it to `VERIFIED` only when a gate
holds it.

**It is enforced, not maintained by memory.** `RigScoreboardTest` (Gate A, milliseconds) parses this
file and fails the build if:

- a row is `VERIFIED` but names no proof, or names a test method / client testmode that does not
  exist in the tree;
- a row's status is not one of the four below;
- **the number of `VERIFIED` rows drops below the floor recorded at the bottom of this file.**

⚠ **What a `GateC:` proof does and does not promise.** The test checks that the named
testmode still EXISTS — not that it passes. A client film is a real client under Xvfb, minutes each,
and nothing runs it on a commit: it is re-verified when **`./tools/gate-c-rig.sh`** runs, which
CI requires of any pull request that touches the rig (`tools/rig-films.py --needed`), and which Port CI runs as
Gate C of every version's build and release, targeted (`.port/<mc>/gate-c.json`: a core set, and every film when
the rig changed since that version's last release; docs/RELEASING.md, "Gate C on every version"). Without that, a
VERIFIED row's film once failed some runs on `main` for days before anyone saw it.

That last one is what makes progress *monotonic*: a change that quietly stops proving something
fails the build, and raising the floor is a visible, deliberate edit in the same commit that earns
it. Same shape as `GuardrailMatrix` and the wish-quality floor.

| status | means |
|---|---|
| `VERIFIED` | a gate holds it. Named in the proof column, and that name must resolve |
| `PARTIAL` | measured, but the measurement does not cover the whole claim. The gap is in the notes |
| `OPEN` | not measured. A prediction, however well argued, is `OPEN` |
| `ACCEPTED` | a known regression we have decided not to chase. Never counts toward the floor |

Proof syntax — `GateA:<TestClass>.<method>` · `GateB:<method>` · `GateC:<testmode>` · `—` for none.

<!-- SCOREBOARD:BEGIN -->

## 0½ · ⚰️ The rows whose evidence compared against the MOD RENDERER are now HISTORICAL

** / (2026-09-22) deleted `WishMobRenderer`, so the arm it was measured against no longer
exists.** Seven rows below named the `vanillacreature` client film as their proof (parked since an earlier change, §0¾), and that test still ran and
still passes — but it is no longer an A/B. Its arm A spawned a `wish_mob`, and with
`VanillaRigBridge` total that mob is cancelled the moment it joins, so arm A was photographing the
empty background (67.3% of the subject region, 58× the floor) until the test was rewritten to score
arm B against an empty stage and its own re-shoot.

**No status is lowered and no number is edited**, because the measurement was real when it was made
and this ledger is monotonic. What changes is what a re-run can tell you:

| row | its claim | can a re-run still check it? |
|---|---|---|
| G1 | *renders identically to the mod renderer* | **no** — there is one renderer. The 1.4% stands as a record of the day it was taken |
| G4 | *25.6% → 6.8% when it moved* | **no** — both numbers are A-vs-B distances |
| G5 | *the last ~1% residual* | **no** — the residual was against arm A |
| A7 | *frames BETWEEN keyframes match the mod renderer* | **no** — same reason |
| G3 | *the pack carries the same atlas* | **yes** — `missing-model px B=0` is measured per run and still is |
| A8 | *the tween really tweens and lands exactly* | **yes** — arm B against itself, never needed two arms |
| D2 | *the pack push does not freeze the client* | **yes** — a timing, not a comparison |

⚠ **The general shape, because it will happen again:** an oracle that transcribes or compares
against a deleted implementation keeps *working* and stops being *checkable*. The same note is on
`RigAtomAnimationTest.modRendererCorners`, which transcribes `RuntimeGeoModel.render` line for line
— still a good oracle (what it encodes is the bedrock convention, a format rather than a class), and
its provenance can no longer be diffed against anything. Treat a disagreement on those rows as a
question about both sides.

## 0¾ · ⚰️ the `vanillacreature` client film is PARKED with the 1.21.1 build (2026-10-15)

`VanillaCreatureAbClientTest` drives the 1.21.1 item-model mechanism, so since T11 it was on the
1.21.4 build's drop list and ran **only** on the 1.21.1 compat build. parked that build (branch
`parked/create-trains-mc1.21.1`), and with it the only place this test could run. The 5 rows that
were `VERIFIED` on it are now `PARTIAL` with no proof (the two that were already `PARTIAL`, G5 and D2, lose theirs too), and the floor fell by 5 in the same commit, because a
`VERIFIED` row is one a gate still holds. **No number is edited**: each was real when it was taken.
Of those rows, G1, G4 and A7 could not be re-checked anyway (§0½), so the real loss is G3 (the pack's
atlas, missing-model px = 0) and A8 (the tween lands exactly). A 1.21.4 port of those two arms is
what would win them back.

**2026-10-16: G3 and A8 are `VERIFIED` again, by a 1.21.4 film, `vanillastage`**
(`VanillaStageClientTest`). It ports exactly those two arms — Spikor alone on the same still stage,
scored in pixels, with no item-model probing (that was the 1.21.1 half). Each was shown red first
through `-Ptestprops` `stagesabotage=`: `atlas` (every display on an item model nothing ships) →
16,266 missing-model px; `snap` (the tween sent with no duration) → mid-flight 0, SNAPPED; `miss`
(flown to another frame) → landed 2,572, MISSED. ⚠ The parked detector (`r,b > 200, g < 80`) saw
only **558** of those 16,266: a display's side faces are shaded to 0.6/0.8, so shaded magenta falls
under 200. The port counts by hue; both count 0 on the intact rig. Floor 90 → 92. G1, G4 and A7 stay
`PARTIAL` — they compared against a deleted renderer.

## 1 · Geometry — does it look like the creature?

| id | claim | status | proof | measured |
|---|---|---|---|---|
| G1 | a real creature renders identically to the mod renderer, frozen | PARTIAL | — | 1.4% against a 0 px floor — ⚰️ *measured on 1.21.1; the test is parked with that build, see §0¾* |
| G3 | the generated pack carries the same atlas the mod paints | VERIFIED | GateC:vanillastage | missing-model pixels = **0** on Spikor (64 displays, 30,742 px drawn against a 0 px same-frame floor), 1.21.4. `stagesabotage=atlas` (every display on an item model nothing ships) reads **16,266**. Was 0 on 1.21.1 (`vanillacreature`, parked) — §0¾ |
| G4 | the creature's scale is folded into the model, not the display transform | PARTIAL | — | 25.6% → 6.8% when it moved — ⚰️ *measured on 1.21.1; the test is parked with that build, see §0¾* |
| G5 | the last ~1% residual is one-pixel edges on a one-texel pattern | PARTIAL | — | 1.4%; cause identified, not eliminated |

## 2 · Animation — does it move like the creature?

| id | claim | status | proof | measured |
|---|---|---|---|---|
| A1 | the matrix layer reproduces the planned rest pose exactly | VERIFIED | GateA:RigPoseTest.anEmptyPoseReproducesThePlannedRestChainExactly | exact to 1e-5 / 1e-4 |
| A2 | a bone a clip does not touch keeps its rest chain | VERIFIED | GateA:RigPoseTest.aBoneTheClipDoesNotTouchKeepsItsRestChain | — |
| A3 | a bone whose ANCESTOR moved is reported as moved | VERIFIED | GateA:RigPoseTest.aBoneWhoseANCESTORMovedIsReportedAsMoved | — |
| A4 | a walk cycle returns to its own first frame | VERIFIED | GateA:RigPoseTest.aWalkCycleReturnsToItsOwnFirstFrame | 0 px, and after 10 cycles |
| A5 | an idle creature costs zero transform writes | VERIFIED | GateA:RigPoseTest.anIdleCreatureCostsNothing | 0 |
| A6 | a scale channel is REFUSED rather than silently dropped | VERIFIED | GateA:RigPoseTest.aScaleChannelIsREFUSEDRatherThanQuietlyDropped | — |
| A7 | frames BETWEEN keyframes match the mod renderer | PARTIAL | — | 1.3% — ⚰️ *measured on 1.21.1; the test is parked with that build, see §0¾* |
| A8 | the client tween really tweens and lands exactly | VERIFIED | GateC:vanillastage | **4302→3928→3504→2958→2284→0 px**, monotonic, mid-flight 3294, landed 0 against a 0 px floor (again: 4302→3849→3407→2864→2002→0, mid 3184) (1.21.4, Spikor, walk, 30 ticks). `stagesabotage=snap` → mid 0 (SNAPPED), `=miss` → landed 2572 (MISSED). Was 4987→…→0 on 1.21.1 — §0¾ |
| A9 | a creature drives the rig from its own walk | VERIFIED | GateC:vanillawalk | walk clip on 359 of 360 ticks |
| A10 | a rig that MOVES faces the way a rig that STANDS STILL faces | VERIFIED | GateB:aRigThatMovesKeepsTheFacingItWasSpawnedWith | verified to fail on the old code |
| A11 | both arms face their direction of travel | VERIFIED | GateC:vanillawalk | 0.0° / 0.0° / 0.0° disagreement |
| A17 | the rig culls at the same distance the carrier does | VERIFIED | GateB:aDisplayCullsAtTheSameRangeAsItsCarrier | `view_range` = the carrier's `getBoundingBox().getSize()`, because `Display.shouldRenderAtSqrDistance` is `d < (view_range × 64 × viewScale)²` and `Entity`'s is the same with the box size in place of the range. Replaces a hard-coded 4.0 |
| A12 | live phase alignment while moving | OPEN | — | the two-lane film cannot measure it — see the ladder §2.4b |
| A13 | the idle clip, running | VERIFIED | GateC:vanillaclips | `clip=IDLE` with **265 px** changed over 50 ticks while the creature stands perfectly still. Driven by `tickCount/20`, i.e. wall time — the one clip whose clock does not come from the creature's motion |
| A14 | the attack clip, driven by swing progress | VERIFIED | GateC:vanillaclips | `clip=ATTACK`, **4,879 px** across the swing window — driven by the rig's OWN 6-tick clock, because vanilla's is structurally dead (A14b) |
| A14b | …and vanilla's own `getAttackAnim` is **0.00** for both paths | VERIFIED | GateC:vanillaclips | peak **0.00** with `swinging: true`. `updateSwingTime()` is called from `Monster.aiStep`, `Player.serverAiStep` and `RemotePlayer.tick` and nowhere else, so the progress never advances for a `PathfinderMob` carrier — nor for the mod path's `WishMob`. A hostile carrier is not an option either: `Monster.shouldDespawnInPeaceful()` would evaporate the rig on Peaceful |
| A15 | head look, composed with whatever clip is playing | VERIFIED | GateB:aLookKeepsTheClipsHead | **865 px** for a 55° turn with `headMoved=true` (`GateC:vanillaclips`). ⚠ Until the "composed" half was **not true**: `lookAt` composed with a NULL pose, so the head subtree became rest + look and the clip's own head keys (the idle sway) were thrown away whenever the look was non-zero. The 865 px was true of that bug — a head turned at rest moves as many pixels as one turned on top of the sway — so it was a control TRUE OF THE BUG (ladder §2.2). The proof is now a comparison: one write of clip+look against clip-then-`lookAt`, transform for transform, on every head display of the starter dragon ⚠ **2,134 px since an earlier change** (780 px on its base, same run pair): a head HELD at 55° now brings the body round behind it after ~10 ticks, as vanilla's `BodyRotationControl` does. The rig used to pin `yBodyRot` to a stale `yRot` and cancel that |
| A15c | everything that sits ON the head turns with it (hair, face, horns) | VERIFIED | GateA:HairFollowsHeadTest.theHairFollowsTheHead | hair drift against the face **0.0°** at every angle from −75° to +75° on the sculpt as SAVED. The control `theInstrumentSeesTheBug` measures **75° / 120° / 180°** of drift on the sculpt as SENT (hair a root), so the instrument sees the bug. Two generator rules: `SculptHeadAdoption` (a part on the head hangs from it) and `SculptBase.keepingItsPlace` (an edit that re-sends a part keeps its parent). |
| A15d | a creature that looks at a player BEHIND it turns its body, not a half turn of its head | VERIFIED | GateC:vanillahair | on base: `yBodyRot` **0°** with `yHeadRot` **−180°**, and the face's own box holds **0 skin / 456 hair px** (the face inside the back of the hair, a tester's frame). On the fix: the body reaches −180° by t=40 and the face box is skin, with the chest button at **484 px** (face-on reference 484). The generator fix alone passes the hair oracle and still fails the body one, so each rule removes its own half. `RigHeadYaw` measures the head against `yBodyRot`, clamped to `getMaxHeadYRot()` (75). |
| A15e | the head TILTS to look at a child above or below it, the right way up | VERIFIED | GateC:vanillapitch | face position inside the head against face-on, camera 3 blocks above / 1.6 below: tilt **−0.025 / +0.005**; the pre- rig (tilt off) **+0.199 / −0.054**; the first build, sign inverted, **+0.339 / −0.155**. Cost bounded by a 2° pitch-only write rule: +1% packets on flat ground (the atom rig's cost notes). |
| A15b | …and a clip write does not SWALLOW the head turn | VERIFIED | GateB:aClipWriteDoesNotSwallowTheHeadTurn | measured **851 px with no clip → 71 px with one** before the fix. `lookAt` caches the last angle it sent; a clip pose overwrites the head display behind that cache, so `lookAt` declines to correct it. `forgetHead()` is called by the driver when the clip wrote — the invalidation belongs to the WRITER, not the reader. **Since the shipping driver has ONE writer** (the look rides the clip's own write, `RigAnimator.tick(…, look)`), so the interaction this row guards no longer arises on the live path; the test still holds it for `lookAt`'s remaining callers |
| A18 | run is the walk clip on a faster clock, free | VERIFIED | GateC:vanillaclips | same clip, `speed=0.240 position=22.26` walking against `speed=1.200 position=134.01` running. `walkAnimation.position()` is the phase and vanilla already scales it with speed, so no code was needed |
| A19 | ONE selection rule serves both drivers | VERIFIED | GateB:aStandingCreatureIsDrivenAndAMovingOneChangesClip | `VanillaRigAnimator` delegates to `RigAnimator.tick(…, Motion)` rather than carrying a second copy of `AnimationState.select`. The `Motion` overload exists so a caller can override one input (here `attackProgress`) while the rule stays in one place |
| A20 | a WALKING, head-tracking rig stays in one piece: every display drawn at one position, one yaw and one tween clock on every frame | VERIFIED | GateC:vanillawalklook | the starter pet dragon circling through the SHIPPING driver with its head on the camera, read off the client per rendered frame at the frame's partial tick. **main (1.21.4 ×4, 1.21.1 ×2): head-vs-body 0.267–0.308 blocks, yaw spread up to 180°, tween spread 0.667, out of tolerance on ~100% of walking frames for 11.9 s unbroken, every time. (1.21.4 ×4, 1.21.1 ×1, final oracle): no unexplained stretch over 15 ms**; the only spreads left follow a measured delivery split (one server tick's packets reaching the client across two client ticks — vanilla's, present on main too; ladder §5.4), which the oracle detects directly and excuses only within a window derived from the lerp's own decay. Controls green on every run (21.6 blocks, ~495° turned, walk clip 240/241, head −148°…73°). `rigsabotage=teleport` goes red (4.5 s unexplained) |
| A20b | every display eases into a MOVE over ONE rig-wide `teleport_duration` | VERIFIED | GateB:everyDisplayOfARigEasesIntoAMoveTheSameWay | `DisplayRig.MOTION_TICKS` = **3**, measured: 0 draws 238 of 238 moving ticks as a snap (20 Hz stutter); 1, 2 and 3 are all smooth, and the drawn rig sits **0.178 / 0.122 / 0.008 blocks** from the client's own carrier — 3 is the value the client lerps a mob with, so the rig stays over the hitbox the crosshair hits. Written by the rig itself (spawn AND adopt, which levels a rig an older jar saved with a mix), never by a pose |
| A20c | the look is composed INTO the clip frame: `R(look)·R(clip)·R(rest)` down the head chain | VERIFIED | GateA:RigLookWithClipTest.aLookComposesWithTheClipsHeadKeysRatherThanReplacingThem | on the starter dragon's own idle sway. A held look costs nothing and a changed one writes exactly the head subtree (`aHeldLookCostsNothingButStillTurnsWhatTheClipWrites`) |
| A20d | the shipping driver has ONE pose writer per tick | VERIFIED | GateA:RigLookWithClipTest.theShippingDriverHasOnePoseWriterPerTick | before an earlier change the head was re-tweened EVERY tick by `lookAt` while the body was re-tweened every THIRD — two tween clocks for one creature, the 0.667 in A20. The look is now sampled on the pose schedule; a head turn waits at most 2 ticks, as every other bone already does |
| A16 | the death flop, on a REAL death | VERIFIED | GateC:vanilladeath | **filmed end to end**: real lethal damage → `deathTime` → the rig's silhouette goes from **0.68 to 1.57** wide-over-tall (a vanilla cow in the same frame: 0.43 → 2.33), driven on 19 ticks to a peak of 90° |
| A16b | M2, the whole-body modifier, exists and composes | VERIFIED | GateA:RigTransformFlopTest.itComposes | `RigTransform.about` post-multiplies T and L and leaves scale and the right rotation alone, so it stacks on a clip pose rather than replacing it. The pivot is the display origin, which for this rig IS the feet |
| A16c | the fall uses VANILLA's curve, not an ease | VERIFIED | GateA:RigFlopTest.theCurveIsSqrtNotLinear | `sqrt((deathTime−1)/20 × 1.6)`, clamped — fast then slow, and **flat at deathTime 14 of 20**, so the body lies still for the last third |
| A16d | the mod path does NOT flop a sculpted creature | VERIFIED | GateC:vanilladeath | aspect 0.73 → 0.73. `renderCustom` returns before `super.render`, so `setupRotations` never runs |
| A21 | **FLY**: in the air the wings BEAT about a shoulder at the body's edge, and the beat returns to its own first frame | VERIFIED | GateA:RigLocomotionTest.inTheAirTheWingTipsBeatAboutAShoulderThatStaysPut | generated from the sculpt (`RigAnatomy` → `RigLocomotion`), no keyframes: the starter dragon's wing tip sweeps > 8 units while the inner panel's shoulder stays within 3 of its hinge; every kit panel of a side turns about ONE hinge (`theDragonHasThreeWingPanelsASideHingedAtTheBodyAndAFourPartTail`). Loop closure exact after 10 beats (`theBeatReturnsToItsOwnFirstFrame`) |
| A21b | …and on the ground a winged creature twitches, it does not beat (the chicken rule) | VERIFIED | GateA:RigLocomotionTest.aGroundedWingTwitchesAtATenthOfTheBeatAtMost | grounded sweep < a tenth of the airborne one; a winged WALKER spreads its wings only after falling ≥ 6 ticks (`RigGaitTest.aWingedWalkerSpreadsItsWingsOnlyOnceItHasBeenFallingAWhile`) |
| A21c | FLY is chosen from the carrier's real `onGround`, never from the species alone | VERIFIED | GateB:aFlierFlapsInTheAirAndNotOnTheGround | the same flier species, one carrier in the air and one on the floor: fly 1 against 0 |
| A21d | filmed beside a vanilla parrot and bee, with the same creature grounded as the control | VERIFIED | GateC:vanillalocomotion | the agent's own dragon puppy (routing arm, `flies` + wing kit): FLY on **100 of 100** ticks; wing-lane motion **275 px/frame** against the grounded control's **1** (parrot + bee lane **49**). Beat 15 ticks, ±38° about +8° — five samples a beat at the 3-tick write rate, because a bee's 2.1 rad/tick would alias to nothing |
| A21e | an UPRIGHT wing (panels thin front to back: the swept, bat, dragon and butterfly kits) is laid flat in the air, so its beat pushes air down instead of slicing through it | VERIFIED | GateA:RigWingFlightCensusTest.inTheAirEveryUprightWingMeetsItsStrokeFaceOn | a census of every winged sculpt in the repository plus every wing kit at every size: each upright side's panels meet the stroke at **5–15°** through the whole beat (was **80–90°**; the control `controlBeforeTheFixEveryUprightWingSlices` fails on the old path). Laid over 85° about the attachment edge, trailing edge back, then beaten about Z as before. Upright is read per side from the drawn boxes (`RigAnatomy.uprightPanels`): flat wings, hanging slabs, feather rods and feather fans are not, and stay byte-identical; on the ground (fly 0) every pose is byte-identical (`onTheGroundNothingChangesByteForByte`) and no part but an upright wing moves differently in the air (`inTheAirOnlyUprightWingsChange`) |
| A22 | **SWIM**: the tail sweeps as a CHAIN — each segment turns about the joint before it and stays joined | VERIFIED | GateA:RigLocomotionTest.inWaterTheTailSweepsAndEverySegmentStaysJoinedToTheLast | tip sweep > 3 units; every neighbour gap within 0.6 of rest at every tick of the stroke |
| A22b | …and the body pitches toward its travel (ladder row 4.2) | VERIFIED | GateA:RigLocomotionTest.swimmingUpwardsPitchesTheNoseUp | travel pitch read off the carrier's velocity (`atan2(vy, h)`, eased, clamped ±40°), applied as the whole-body ROOT inside `RigPose.compose` — M2 in the one pose writer |
| A22c | SWIM is chosen from the carrier's real `isInWater`, and wading on the bottom is walking | VERIFIED | GateB:aSwimmerSwimsInWaterAndNotOnLand | same species in water and on land: swim 1 against 0; `RigGaitTest.itSwimsOnlyInWaterAndOffTheBottom` |
| A22d | filmed under water beside a vanilla dolphin and cod, with the same swimmer on land as the control | VERIFIED | GateC:vanillalocomotion | the agent's own glitter shark (`tail`, `tailfin`, `finL/R`, `fin_dorsal` by name): SWIM on **140 of 140** ticks, never on land; peak travel pitch > 10°; lane motion **438** against land's **11** (dolphin + cod **43**) |
| A23 | **JUMP**: a one-shot clip whose clock is the jump itself — 0 leaving the ground, ½ at the top, 1 landing | VERIFIED | GateA:RigLocomotionTest.aJumpStartsAndEndsWithTheLegsWhereTheyHangAndReachesInTheMiddle | progress `0.5 − vy/2vy₀`, monotonic (`RigGaitTest.aJumpRunsFromTakeOffToTheTopToTheLandingThenEasesOut`); a landing crouch eases out over 5 ticks — no snap |
| A23b | a walker on flat ground never jumps | VERIFIED | GateA:RigGaitTest.aWalkerOnFlatGroundNeverJumpsOrFlies | 400 ticks walking: jump, fly, swim and sit all 0 |
| A23c | `hops`: a hopper gathers, leaps and lands instead of walking | VERIFIED | GateA:RigGaitTest.aHopperGathersThenLaunchesWhenItWantsToMove | a 4-tick gather (a crouch) then a launch towards the path's next node |
| A23d | filmed side-on beside a vanilla rabbit and frog, with the same sculpt WITHOUT `hops` as the control | VERIFIED | GateC:vanillalocomotion | the agent's own purple bunny (`hops`): JUMP on **95**, CROUCH on **108** of 258 ticks, travelled **6.3** blocks by hopping; the walker covered **5.2** blocks and was in JUMP on **0** |
| A23e | a slime's squash and stretch | ACCEPTED | — | a SCALE channel, which the rig refuses by name (A6). Approximated with a body drop and bent legs, never a scale write |
| A24 | **SIT**: a quadruped sits with its rump AND its front feet on the ground | VERIFIED | GateA:RigLocomotionTest.aSittingQuadrupedHasItsRumpAndItsFrontFeetOnTheGround | the tip is SOLVED on the geometry (bisected so the front feet land once the rump is down), on the cat, wolf, dragon and turtle. The cat's hind legs are its `armL/armR`: front or hind is a position, never a name |
| A24b | sitting down is monotonic, never a snap | VERIFIED | GateA:RigLocomotionTest.sittingDownIsMonotonicNeverASnap | eased by weight, smoothstepped; no step over 2.5 units |
| A24c | lying down puts the belly on the ground | VERIFIED | GateA:RigLocomotionTest.lyingDownPutsTheBellyOnTheGround | front paws forward, hind legs stretched behind (out from under the belly) |
| A24d | asleep it rolls on its side, its eyes close and its head stops following | VERIFIED | GateA:RigLocomotionTest.asleepItRollsOnItsSideShutsItsEyesAndStopsLooking | eyes sunk inside the head, look weight 0, a slow breath |
| A24e | told to stay it sits; a free one keeps standing | VERIFIED | GateB:toldToStayItSitsAndAFreeOneKeepsStanding | sit 1 against 0, the stay read off the carrier's own pet behaviour |
| A24f | filmed beside a sitting wolf and a sleeping fox, through a day, a night and a morning, with a free non-napper as the control | VERIFIED | GateC:vanillalocomotion | the agent's own cotton-candy kitten (`naps`): SIT on **301** ticks, SLEEP on **219** of the night, back to SIT at dawn; the control never sat, lay or slept |
| A24g | a HELD sit is still: a display write that changes nothing sends nothing | VERIFIED | GateC:vanillalocomotion | a tester saw the held sit/lie/sleep TWITCH. Vanilla's interpolation-delay setter forces a resend, and the client restarts the tween from the display's PREVIOUS pose, so re-writing an unchanged transform snapped each display back and eased it forward every 3 ticks. Before: **390** re-sends in 80 held ticks, 184 px/frame on film. After (`DisplayRig.apply` skips an equal transform): **0** re-sends, **0.0** px/frame, the same as the static vanilla wolf (standing control 43.9). All 11 rig films green on the fix |
| A25 | **WALK**: every leg the rig finds STEPS, whatever it is called — `legBL/legBR`, `legL1..legR4`, a `leg_row`'s six or eight | VERIFIED | GateA:RigWalkTest.everyLegStepsWhereTheFixedClipMovedOnlyTheFourItKnowsByName | feet that move over one stride, clip alone → generated: horse (front `legL/legR`, hind `legBL/legBR`) **2/4 → 4/4**, `leg_row` spider **0/8 → 8/8**, hand-named spider **0/8 → 8/8**, `leg_row` ant **0/6 → 6/6**, person 2/2. A layer in `RigLocomotion.pose` on `RigAnatomy.legs()`, on the walk clip's own clock (`Stride.of`) |
| A25b | …in a gait that fits the count: two alternate, four trot, six on the alternating tripod, eight in a wave front to back with the sides in antiphase | VERIFIED | GateA:RigWalkTest.eightLegsWalkInAWaveFrontToBackTheSidesInAntiphase | phase lags read off each FOOT's forward position (first harmonic), ±20°: trot diagonals 0°, pairs 180° (`fourLegsTrotTheDiagonalPairsTogether`); tripod (`sixLegsWalkOnAnAlternatingTripod`); spider each row **90°** behind the one in front, left/right **180°**. The cat's paw stubs `legL2/legR2` step with the hind leg they stand in (`theCatsPawStubStepsWithTheLegItIsTheFootOf`) |
| A25c | …and it is a STEP, not a slide: a foot coming forward is lifted, a foot pushing back is planted | VERIFIED | GateA:RigWalkTest.aFootIsLiftedToComeForwardAndPlantedToPushBack | the control that is false of a moon-walk (ladder §2.2): reversing the stride leaves every count and lag above unchanged and fails this. Upright legs swing ±25° (the clip's) and draw up 7% of their length on the way forward; sprawled legs sweep about their ROOT at the body and lift ~14° |
| A25d | no leg is swung twice; a biped's arms keep the clip, in step with its legs | VERIFIED | GateA:RigWalkTest.noLegIsSwungTwiceAndABipedsArmsKeepTheClip | a leg's pose with the walk clip under it equals its pose without; `armL/armR` equal the clip's; right hand 180° from right foot |
| A25e | a `leg_row` is a jointed THIGH and SHIN that stay jointed while they walk | VERIFIED | GateA:RigWalkTest.aLegRowIsJointedThighToShinAndStaysJointedWhileItWalks | the shin is the thigh's child (it was two slabs, both on the body, and a step left one behind); each a rod tilted onto its line; knee inside the thigh at every phase, hip on the body |
| A25f | the live rig walks on all of them: leg displays that change pose during a real `travel()` walk | VERIFIED | GateB:aSpiderWalksOnAllEightLegs | horse 4/4 (`aHorseWhoseHindLegsAreLegBLAndLegBRWalksOnAllFour`), spider 8/8, ant 6/6 (`anInsectWalksOnAllSixLegs`); control: the BODY display holds one pose throughout |
| A25g | filmed walking, side-on and at three quarters, over a checkerboard: spider, ant, a `legBL/legBR` horse and a biped | VERIFIED | GateC:vanillalegs | see docs/VANILLA_RIG_ANIMATION_LADDER.md §5.8 for the per-take counts and what the film showed |
| A24h | a resting creature does not play its standing idle | VERIFIED | GateA:RigLocomotionTest.aHeldRestPoseDoesNotPlayTheStandingIdle | the idle bobbed the `body` bone against root-bone legs and tail; a held sit or lie now moves no box between writes, while the standing control does. Verified to fail with the fade removed |

## 3 · State — does it show what is happening to it?

| id | claim | status | proof | measured |
|---|---|---|---|---|
| S1 | burning reaches the rig | VERIFIED | GateC:vanillastate | arm B 27,238 px changed, 20,660 of them fire-coloured |
| S2 | the name tag reaches the rig | VERIFIED | GateB:aWishedCreatureCastsAShadowAndShowsTheNameHeGaveIt | **fixed on the shipped path 2026-11-09**. Vanilla's own tag cannot (`LivingEntityRenderer.shouldShowName` ends `&& !isInvisibleTo(player)`, and the carrier is invisible: arm A 240 px, arm B 0, the original refutation). The rig's `text_display` plate (S3b) now follows the carrier's name every tick: a name tag on it or a proxy shows, taking the name away removes the plate. Only a name he GAVE: every carrier is born wearing its species' name, and that is no plate. Red with the wiring removed |
| S3 | potion swirls reach the rig | VERIFIED | GateC:vanillastate | present on both arms in every run |
| S3c | …at vanilla's own 4/15 rate | OPEN | — | **not measurable with this instrument**: ten runs gave 0.17, 0.27, 0.31, 0.36, 0.49, 0.49 at the tier-1 camera and 0.10, 0.09, 0.08, 0.04 at the tier-2 one, against a predicted 0.267. Always thinner on the rig, never a stable number — and the four-fold shift when the CAMERA moved says the instrument dominates the phenomenon |
| S1b | the flame is the SAME SHAPE vanilla gives any mob | VERIFIED | GateC:vanillastate | a burning cow in the same frame: flame 1.51× its height / 1.63× its width, against the creature's 1.61×/1.56× (arm A). Same proportions, 5× the scale |
| S3b | a named creature shows its name (via a `text_display`) | VERIFIED | GateC:vanillastate | plate boxes A=(194,149)-(230,158) B=(623,149)-(658,159) — vanilla's tag and ours on the same top row, same size to a pixel. The lift is derived (`0.5 - 10×0.025`), not tuned |
| S4 | ability particles and projectiles come from the same place | VERIFIED | GateB:theSameAbilityFiresFromTheSamePlace | 0.000000 blocks |
| S5 | the shadow | VERIFIED | GateC:vanillastate | arm B **9,928 px** of floor darkened against the vanilla cow's 820 — and the rig's disc is 161×108 px against the cow's 56×37, a ratio of 2.9 against a radius ratio of 2.8. Its radius is **half its own width** (1.96), derived from the iron golem, the one vanilla mob big enough to calibrate on. **On for every shipped rig since 2026-11-09** — before, only Gate C ever called `setShadow`; `GateB:aWishedCreatureCastsAShadowAndShowsTheNameHeGaveIt` asserts the radius on display 0 and none on the rest |
| S5b | …and the MOD path's is invisible | VERIFIED | GateC:vanillastate | 672 px, i.e. the instrument's own flicker floor. `WishMobRenderer` never updates `shadowRadius` on the custom-model branch, so a 3.6-block creature gets the humanoid default 0.5 × its scale = a 1.2-block disc entirely under its own body. The rig deliberately does NOT match it |
| S5c | nothing casts a shadow past 16 blocks | VERIFIED | GateC:vanillastate | `strength = (1 - d²/256) × shadowStrength`, skipped at ≤ 0. The side-by-side pose is ~20 blocks out and measured **0 px for all three subjects including the vanilla cow** — which is why the shadow row is three single-subject passes |
| S6 | the glow outline | VERIFIED | GateC:vanillastate | arm A 2,749 px outlined, vanilla cow 720, arm B **1,994**. The `Glowing` NBT flag on every display. ⚠ *"the carrier is deliberately not glowed (it draws nothing)"* was wrong: an invisible glowing mob draws `RenderType.outline`, and the take only ever glowed the displays BY HAND, so this proved a capability no shipped path used. The number is the hand-glowed arm; S6c is the shipped path |
| S6b | …in a colour of the creature's own | VERIFIED | GateC:vanillastate | red-dominant outline pixels: arm B **977** against arm A's 100 (its own warm pixels near the edge). `Display.getTeamColor()` returns `glow_color_override`, so this is something the rig can do that the mod path cannot |
| S6c | glowing the CARRIER, the way every shipped path does, outlines the RIG and never the invisible carrier | VERIFIED | GateC:vanillastate | read off the client's own entities (`Minecraft.shouldEntityAppearGlowing`): **carrier outlined = false, displays outlined 64/64**, both plain and on a red team; arm B 2,016 px outlined against arm A's 969. On `main` the same take outlines the carrier and **0/64** displays |
| S6d | each glow source is mirrored: the tag, the GLOWING effect, a team colour, `modify_mob` | VERIFIED | GateB:modifyMobGlowIsMirrored | `RigGlowMirrorGameTest`, four sources through the real animator tick, the carrier's synced flag byte read as a client would be sent it. **All four red on `main`**, green here |
| S6e | a team colour is the outline colour, and a recolour follows | VERIFIED | GateB:aTeamColourBecomesTheOutlineColour | red team → `glow_color_override` 0xFF5555, recoloured blue → 0x5555FF; client: 0 of 64 displays in the wrong colour, 1,017 red-dominant px |
| S6f | turning glow off clears the rig | VERIFIED | GateC:vanillastate | after the carrier's tag and team come off: displays outlined **0/64**, carrier false |
| S12 | the rig darkens at night like the mod path | VERIFIED | GateC:vanillastate | luma lost day→night: arm A **113.9**, arm B **109.7** (cow 68.8). First exercise of `matchLight` in the dark — a sky-light override of 15 does NOT make the rig immune to nightfall |
| S12b | the rig is lit like a vanilla mob in the SAME place when the light changes after it appeared — a room lit later, a walk indoors from the sun | VERIFIED | GateC:vanillalight | a villager beside it is the control, each read as luma ÷ its own noon. Room lit after they appeared: rig ÷ villager **0.23 → 1.24**; in from the sun to a dim room **4.22 → 0.83** (×2 runs each arm, ±0.02). Before, `matchLight` ran once at SPAWN: the room rig stayed black (luma 8 against 32) and the walk-in one kept daylight. `DisplayRig.followLight` now re-samples the carrier's eye on every pose write tick and writes only on a change |
| S12c | seated on a throne in a lit room, a rig is NOT darkened by the blocks it touches | VERIFIED | GateC:vanillalight | rig ÷ villager **1.18–1.20 with the fix on AND off**: every display carries the carrier's override, so no display is lit where it stands. The 1.2 is the carrier's eye (a llama's) one light level above the villager's (9 vs 8), vanilla's rule for the mob that is there. an earlier change's black boss was S12b's bug: the lanterns went in after he was seated |
| S13 | a bone can be made emissive | VERIFIED | GateC:vanillastate | at midnight, 1 of 41 groups forced to `brightness` 15/15: arm B gained **13.5 luma**, arm A (untouched) moved 0.1. FULLBRIGHT, not additive — an emulation of `RenderType.eyes`, and which bones glow is not something the sculpt can say yet |
| S7 | the damage flash, on a REAL death | VERIFIED | GateC:vanilladeath | the rig's body red fraction **0.394 → 0.509 (+29.2%)** against a vanilla cow's 0.371 → 0.607 (+63.4%) in the same frame, driven only by the carrier's own `hurtTime`/`deathTime` after real lethal damage |
| S7b | …and the emulation gap is a NUMBER, not a hedge | VERIFIED | GateA:RigTintTest.greenAndBlueAreExact | the multiply `0xFFB2B2` is derived from vanilla's own overlay (pure red at alpha 178/255) and the entity shader's `mix(overlay, color, overlay.a)`: **green and blue come out exactly right**, and red falls short by `0.302 × (1 − r)` — zero on an already-red creature |
| S7c | an untinted rig is not quietly BROWN | VERIFIED | GateC:vanilladeath | the tintable carrier is dyeable and every face declares `tintindex: 0`, so a display with **no** `dyed_color` is multiplied by `0xA06540`, undyed leather. `wear` writes `RigTint.NONE` unconditionally; measured means arm A (149,160,135) vs rig (148,152,127) |
| S14 | the mod path shows NO damage flash for a sculpted creature | VERIFIED | GateC:vanilladeath | 0.379 → 0.380, i.e. nothing. `WishMobRenderer.renderCustom` passes `OverlayTexture.NO_OVERLAY` |
| S8 | the freeze SHAKE | ACCEPTED | — | `setupRotations` on a model nobody renders |
| S9 | the hurt overlay is additive in vanilla and a multiply here | ACCEPTED | — | an emulation, not a reproduction |
| S10 | F3+B hitbox outline | ACCEPTED | — | vanilla skips it for an invisible carrier |
| S11 | a bucket of milk exposes the carrier for one tick | ACCEPTED | GateB:everyCorpusCreatureCanBeCarriedByAVanillaBase | `DisplayRig.hide` re-applies |

## 4 · Behaviour — can a child play with it?

| id | claim | status | proof | measured |
|---|---|---|---|---|
| B1 | it can be hit and hurt | VERIFIED | GateB:aHitLands | — |
| B2 | another mob can target it | VERIFIED | GateB:anotherMobCanTargetIt | — |
| B3 | selectors find it | VERIFIED | GateB:selectorsFindIt | — |
| B4 | the hitbox is measured, not assumed | VERIFIED | GateB:theHitboxIsMeasuredNotAssumed | reported as a residual |
| B5 | effects apply to it | VERIFIED | GateB:effectApplies | — |
| B5b | …and an agent-written SELECTOR finds it | VERIFIED | GateB:anAgentWrittenSELECTORFindsTheRigAndItsEffectLands | B5 calls `addEffect` directly, which is true of every mob in the game and says nothing about whether an authored ability can FIND a rig. This runs the real `effect give @e[tag=wishmob_x]` through the real dispatcher. It works because `WishMobTags.selector` carries **no `type=` filter** and `DisplayRig` stamps the same two tags the wish mob stamps on itself — so the entity type can be swapped out from under every existing ability for free |
| B6 | `modify_mob` still adjusts it | VERIFIED | GateB:modifyMobStillAdjustsIt | — |
| B11 | the carrier has the CREATURE's statistics, not the base mob's | VERIFIED | GateB:theRigCarriesTheCREATURESNumbersRatherThanTheCarriersOwn | `spawn` overrode one attribute (`minecraft:scale`, for the hitbox) against `applySpecies`' five, so a creature declared at 200 health stood in the world with a horse's. Invisible because B1 asks whether it can be HURT and B6 sets the attribute itself before checking it took — **a control that is true of the bug is not a control**. Fixed by `DisplayRig.applyVitals`; the order matters (MAX_HEALTH before setHealth) |
| B13 | a SECOND wish changes a creature already standing in the world | VERIFIED | GateB:aSECONDWishRebuildsTheCreatureStandingInTheWorld | `sculpt_creature` promises the agent *"every one already in the world updates"*, and the legacy path delivers it by comparing `SpeciesRegistry.revision()` on tick and re-applying. A rig has its geometry BAKED into a pack and into every display transform, so it is REBUILT instead — carrying health, effects and name exactly as the first manifestation does. The halfway state is what made this urgent: `RigPackWriter` reuses the creature's slot, so a re-wish changes the models while leaving the displays at the old proportions |
| B14 | …and ANOTHER creature's edit does not rebuild this one | VERIFIED | GateB:ANOTHERCreaturesEditDoesNotRebuildThisOne | the revision counter is GLOBAL — it says "something changed", never "this creature changed". Two exact discriminators, because neither implies the other: the `Species` record (a record of value types, so `equals` is exact) and the pack's own byte-compare (a re-sculpt changes geometry without touching the record, since the record holds a `modelKey` rather than the parts) |
| B12 | a wounded creature is still wounded after the swap | VERIFIED | GateB:aWoundedCreatureIsSTILLWoundedAfterTheSwap | the bridge CANCELS the wish mob, and the wish mob is the only thing that knows what happened to this creature — it persists the species id and rebuilds the rest. Health carries as a **fraction** (the maximum changes across the swap), effects are copied except the rig's own INVISIBILITY, and a name tag survives |
| B7 | every corpus creature can be carried by a vanilla base | VERIFIED | GateB:everyCorpusCreatureCanBeCarriedByAVanillaBase | — |
| B8 | the rig CAN be reaped when the creature dies | VERIFIED | GateB:armBSpawnsNothingButVanillaEntities | 6 of 6, by tag, with no in-memory state |
| B9 | …and something actually CALLS it in a game | VERIFIED | GateB:theRigIsReapedWhenItsCarrierIsREMOVEDRatherThanWhenItDies | `VanillaRigBridge` on `NeoForge.EVENT_BUS`. Reaps on **removal**, not on death — `LivingDeathEvent` fires 20 ticks early and would delete the death flop tier 4.1 exists to produce; the removal REASON is checked so a chunk unload does not reap a sleeping creature |

| B10 | a rig is re-adopted after a world reload | VERIFIED | GateB:aRigIsRE_ADOPTEDAfterTheDriverLosesItsMemory | the driver's map is in memory only, so a reload leaves live displays with nobody writing to them. `DisplayRig.adopt` recovers the display ORDER from each display's own `custom_model_data` (a `TreeMap` keyed on it) — the pack manifest is the only durable record of which slot is whose |

## 5 · Delivery — does it reach a real player?

| id | claim | status | proof | measured |
|---|---|---|---|---|
| D1 | nothing the client sees is a modded entity type | VERIFIED | GateB:armBSpawnsNothingButVanillaEntities | 0 `moddingfromamod:` types in arm B |
| D2 | the pack push does not freeze the client unacceptably | PARTIAL | — | 1.7–2.6 s, measured on the future rather than the call |
| D2b | …and it does not fire when nothing CHANGED | VERIFIED | GateB:onTheAtomArmNoPackIsWrittenAndARepublishMovesNothing | **Re-proved by an earlier change (2026-09-23).** The original proof was the pack rig's `RigPackTwoCreaturesTest` (`Written.changed`, a byte-compare over every file incl. the PNG, A/B'd on the pre-fix write). The pack rig is deleted and nothing reloads per wish at all, so the claim that survives is its sharpest half: re-publishing IDENTICAL geometry leaves the reveal state where it was (`RigGeometryPrint`), so a creature nobody re-sculpted is never re-veiled |
| D3 | per-creature packs | ACCEPTED | — | impossible on 1.21.1: no `item_model` component |
| D4 | an end-to-end WISH lands on a rig-enabled world | VERIFIED | GateC:vanillawish | a real agent wish typed into the real wish watch → **41 display entities, 0 wish mobs**, carrier `minecraft:horse` 5.94×6.80, photographed at 48,439 px with **0 magenta**. Gate C½ grants the same wish in 125 s and cannot finish it — `sculpt_creature` only spawns when a player is in the player list, and a dedicated server's is a `FakePlayer` |
| D5 | TWO creatures can stand in one world | VERIFIED | GateB:ANOTHERCreaturesEditDoesNotRebuildThisOne | **Re-proved by an earlier change (2026-09-23).** The original failures were the pack writer's (it WIPED the pack root and numbered every creature from 1; both A/B'd, `theSecondWriteKeepsTheFirstCreaturesModels`), and deleted the writer — two atom creatures share one constant cube and have nothing to number. What still has to be true is that a second creature's wish leaves the first one untouched, which this Gate B test holds |
| D5b | …and a real client draws both, with the first one unchanged | VERIFIED | GateC:vanillatwo | **0 of 3747 px** of alpha moved when beta joined the pack; A/B'd with the old numbering → **3724 of 3747 (0.994)** and alpha's mean colour became beta's |

<!-- SCOREBOARD:END -->

<!-- The number of VERIFIED rows must never fall below this. Raise it in the commit that earns it. -->
<!-- 63 -> 62 on 2026-09-23, deliberately and in the commit that did it: deleted the pack rig,
     and row G2 ("one display per rigid GROUP, not per box") was a claim ABOUT the pack rig. On the
     atom rig -- the only rig -- it is false by design (one display per box is the accepted cost,
     the atom rig's cost notes), so it was removed rather than re-pointed at a test that proves the
     opposite. Nothing regressed; a claim retired with the code it described. D2b and D5 were
     re-proved on live tests instead (see their rows). -->
<!-- 62 -> 66 by an earlier change (A20-A20d), then 66 -> 70 on 2026-09-25 by an earlier change (S6c-S6f, the glow mirror), then 70 -> 72 by an earlier change (A15c, A15d), then 72 -> 73 by an earlier change (A15e), then 73 -> 75 by an earlier change (S12b, S12c). -->
<!-- 75 -> 93 by an earlier change/// (A21-A24f): fly, swim, hop, sit, lie and sleep, generated from the sculpt and filmed beside vanilla. -->
<!-- 93 -> 95 by the held-sit fix (A24g, A24h). -->
<!-- 93 -> 100 by an earlier change (A25-A25g): the walk, on every leg the rig finds. -->
<!-- FLOOR: 101 -->
