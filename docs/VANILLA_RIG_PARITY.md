# Vanilla display rig vs the mod renderer — what is and is not at parity

**Read this before working on the vanilla creature path.** It is the running answer to "can this
replace `wish_mob`?", kept as a table of measurements rather than a narrative, so a row can be moved
when a measurement moves it. Every ✔ here is a number from a gate, not a judgement.

The spike's four questions and their full workings are in
the spike results; this is the scoreboard. The
plan for closing the gaps below — and the reason nothing downstream should start until §1 does — is
the rig plan.

**Status: the display rig is behind `VanillaRigFlags`, default OFF. `wish_mob` + `RuntimeGeoModel`
is the shipped path and nothing here changes it.**

---

## 1. Geometry and shading — AT PARITY, on primitives and on a real creature

Arm A is `wish_mob` + `RuntimeGeoModel`; arm B is display entities on a vanilla base, from a
generated pack, on a client with no mod. Same spot, same camera, same frozen background, shot
seconds apart. `src/main/java/com/moddingfromamod/gatec/VanillaCreatureAbClientTest`.

| fixture | what it is the only test of | A-vs-B | floor | |
|---|---|---|---|---|
| `__unitbox` | the primitive | **4 px** | 4 px | ✔ |
| `__bigbox` | a box too wide for the [-16,32] window — the shrink round-trip | **0 px** | 0 | ✔ |
| `__twobox` / `__twosize` | two boxes in ONE group — intra-model layout | **0 px** | 0 | ✔ |
| `__twoy` | two boxes offset in Y — the axis discriminator | **0 px** | 0 | ✔ |
| `__asym` | asymmetric in size AND offset — the only real test of a reflection | **4 px** | 4 px | ✔ |
| `__twogroup` | two real bones, each turning about its own pivot | **0 px** | 0 | ✔ |
| `__chain` | a tilted child of a tilted bone — the ancestor chain | **0 px** | 0 | ✔ |
| `__tiltx` / `__tilty` / `__tiltz` | one tilt per axis | **0 px** | 0 | ✔ |
| `__multi` | one box tilted on all three axes — the only test of compose ORDER | **0 px** | 0 | ✔ |
| `__planbar` | a 4:1 bar in plan — the yaw test | **0 px** | 0 | ✔ |
| `__legs3` | Spikor's first three parts, rebuilt by hand | **0 px** | 0 | ✔ |
| `__legs3!scale12` | the same three at the creature scale that broke them | **6 px** | 10 px | ✔ |
| **spikor-armB**, unposed | 62 boxes, 6 bones, 36 rotated, real sculpt | 4,316 px (6.8%) | 848 px (1.3%) | ⚠ not like-for-like — see below |
| **spikor-armB**, frozen at one frame | the same creature, both arms held at `idle` t=1.5 | **890 px (1.4%)** | **0 px** | ✔ |

Every fixture is at or under its own same-arm floor. **Spikor is 1.4% once both arms are held at the
same frame, against a floor of ZERO** — and the unposed row above is kept because getting it wrong is
the more useful half of the story.

### 🔴 CORRECTION — the residual was NOT z-fighting, and the static comparison was not like-for-like

**This section previously said the 6.8% was z-fighting on coplanar boxes, "proven" by arm A
disagreeing with a second photograph of itself by 848 px. That reading was wrong, and the animation
spike is what exposed it.**

Arm A is a real `wish_mob`, and a `wish_mob` standing still is not standing still — it plays the
**idle clip**, which bobs the body, sways the head and swings both arms. The two "same-arm" shots are
several ticks apart, so they catch different phases of that clip. The 848 px was the creature
BREATHING, and the 6.8% was an animating mod compared against a rig standing at rest. Nothing was
holding them at the same instant.

Freeze both at one frame — `poseClip`/`poseTime`, the same two properties `WishMobRenderer` already
reads — and the numbers change completely:

| | A-vs-B | same-arm floor |
|---|---|---|
| unposed (what §1 used to report) | 4,316 px · 6.8% | 848 px · 1.3% |
| **frozen at `walk` t=0** | **935 px · 1.5%** | **0 px** |
| **frozen at `idle` t=1.5** | **890 px · 1.4%** | **0 px** |

A floor of exactly zero, twice, is the tell: the mod renderer is perfectly repeatable once nothing is
animating it, so every pixel of the old floor was the clip.

**So static parity is better than reported — 1.4% rather than 6.8% — and the explanation given for
the old number was wrong twice over.** The lesson is §9.6's, arriving through a new door: the number
was real, the story fitted to it was not, and the thing that refuted it was not a better analysis of
the same photograph but a *different experiment*.

### What the last 1.4% actually is: one-pixel edges on a one-texel pattern

Measured on the frozen frame, and it is a much smaller claim than the old one. The two arms draw
**exactly the same number of pixels** (30,885 against 30,885), so the silhouette is identical; every
differing pixel is interior; and every one is the same green/white pair — Spikor's two
`scales`-patterned parts, whose dashes are one texel wide.

Sliding one arm against the other says the pattern is not displaced: vertical agreement peaks at
**dy = 0** (92.1%) and falls off hard either side, horizontal at dx = 0/+1 (92.1% / 93.6%). Whole
rows are character-for-character identical and the disagreements are single pixels at the ends of
individual dashes. That is two render paths rounding a sub-texel sample to opposite sides of a texel
boundary — a rasterisation tie on a high-frequency texture, not a placement, shading or UV error.

Three candidate causes were tested and all were **worse or neutral**, which is what leaves the tie:

| candidate | experiment | result |
|---|---|---|
| the UVs need mirroring under the flip | `riguvflip=u` / `v` / `uv` | 890 → **1,167 / 4,151 / 4,117 px**. All worse. |
| the rig's texture is mipmapped (block atlas) and the mod's is not (entity texture) | `rigmipmap=0`, atlas rebuilt | **890 px, unchanged to the pixel** |
| the pack paints from the mirrored sculpt and the mod from the raw one | bake from raw parts | 4,315 → **4,316 px**. Identical. |

### The journey, because two of the four numbers were reached by a wrong theory first

| | Spikor A-vs-B | what changed |
|---|---|---|
| start | 46.2% | four bugs already fixed (yaw, double-written tilt, double mirror conjugation, element X) |
| after §5 | **25.6%** | the model is reflected in **Z** as well as X |
| after §6 | **6.8%** | the creature's scale is folded into the MODEL, so the display's scale is 1 |
| floor | 1.3% | arm A against arm A |

### Bugs fixed to get here, each of which is a class rather than an instance

| # | bug | how it presented | found by |
|---|---|---|---|
| 1 | the display yaw was `bodyRot`, not `bodyRot + 180` | 27–56% on every creature, read as a format limit | a yaw sweep on one box: 8.6 / 8.1 / **0.0** / 5.3 |
| 2 | the anchor part's tilt was written **twice** — once as the display's rotation, again as a snapped element rotation | 31° became 53.5°, which no sign flip can cancel | one tilted box: 45.4% while every untilted fixture was 0 px |
| 3 | `mirrorX` negated the Y and Z tilts, applying the mirror's conjugation **twice** | X exact, Y and Z wrong — the signature, since X is the axis conjugation leaves alone | reading `RuntimeGeoModel`'s matrix rather than sweeping |
| 4 | element X was reversed inside the item model | two boxes at ±x came back with positions swapped | `__twobox`; invisible to any single-box fixture |
| 5 | **only X was reversed — a half turn about Y reverses X AND Z** | a Z-asymmetric group came out 6 px wider and 10 px to the right, height and vertical position exact | `__legs3`; **every one of the ten fixtures that passed was Z-symmetric**, so none could see it |
| 6 | **the creature's SCALE sat on the display, and vanilla shades a scaled display differently** | a uniform ×0.92 on the SIDE faces only, top faces exact; invisible on a single box at any scale | `__legs3` 0 px at scale 1 and **30.8% at scale 1.2** — same boxes, same silhouette to the pixel |

⚠ **A fixture that is symmetric in the axis a bug lives in cannot see that bug**, and this cost two
whole rounds. Three pixel-exact primitives said nothing about #4 because each sits centred on model
8. Then ten fixtures said nothing about #5 because every one of them puts all its boxes at the same
z. Then all eleven said nothing about #6 because a fixture with **one box** is exact at any scale.
Every new fixture here is deliberately asymmetric, and the newest ones carry a scale.

### Why the scale had to move into the model (#6), from vanilla's own code

`PoseStack.mulPose(Matrix4f)` takes one branch when the matrix is orthonormal and another when it is
not: orthonormal multiplies the pose's normal matrix straight through, otherwise it recomputes an
inverse-transpose. A display transform whose scale is exactly 1 is orthonormal; at 1.2 it is not, and
the two paths disagree about what a side face's normal is. Measured on a north face: **0.800 against
0.737**, which is exactly the gap between vanilla's block-shade constant for that direction and its
entity-diffuse term for that normal.

`RigPlan.scaled` therefore divides the group's `shrink` by the creature's scale, so the model is
`s` times bigger and the display `s` times smaller. World positions are unchanged and the display
transform is orthonormal again. The pack writer and the driver must be handed the **same** scale;
both call it, and `RigPackWriter.write` grew the parameter for exactly that reason.

### Four causes measured and refuted — all of them plausible, all of them wrong

Each had an instrument built for it and a number that killed it.

| hypothesis | experiment | result |
|---|---|---|
| the rig's displays are lit at their own positions, not the mob's | force every display's `brightness` to 15/15 | 16,340 → **16,258 px**. Dead. |
| the reflected element needs its UVs mirrored | flip u on the sides, u and v on top and bottom | 4,315 → **4,439 px**. Worse. |
| the reflected element needs its face NAMES swapped | `rigfaceswap=off` | 4,316 → **4,338 px**. Neutral — west/east carry the same shade constant, so no fixture could ever have seen it either way. |
| the pack paints its atlas from the mirrored sculpt, the mod from the raw one | bake from the raw parts | 4,315 → **4,316 px**. Identical. |

The light one is worth keeping in mind: it was a good hypothesis (a uniform 0.92 across a whole
creature really does look like one light level), it took one run to kill, and killing it is what
sent the search to the display's scale.

## 2. Animation — the maths is at parity, the picture has never been checked

| | status |
|---|---|
| rotation channel | ✔ composed as quaternions in the mod's own order (`R(anim)·R(rest)`) |
| position channel | ✔ applied, mirrored into rig space |
| scale channel | ✔ applied |
| idle cost | ✔ **0 updates** — asserted |
| locomotion | ✔ position only, **0 transform updates** |
| cost | ✔ 53 bytes/update, 47 µs/bone, ~1.3–4.0 KB/s per creature per viewer |
| **head-follow** | ✔ **implemented.** `RigDriver.lookAt` turns the head group only, composed as `R(headYaw)·R(headPitch)·R(anim)·R(rest)` with the Y turn conjugated. Measured: 1 head group of 6, turning rewrites 1 display, straight-ahead rewrites 0, **holding a turn rewrites 0** |
| **visual verification** | ✘ **none.** Q2 measured what animation COSTS, never what it LOOKS like. No Gate C test animates a creature and compares |

⚠ **"Head-follow doesn't work" never meant the rig could not tell which way the creature was
looking.** `getYHeadRot()` and `getXRot()` are live and synced, and `WishMobRenderer` builds its own
angles from those same two fields. It was a missing write, not missing information — worth
distinguishing, because the two have completely different costs.

⚠ **`articulate` used to add Euler angles per axis** (`rest[i] + pose[i]`). Euler angles are not a
vector space, so that is only correct when the rest tilt and the keyframe share one axis — right on
a creature whose bones all turn about Z, silently wrong on the next one. Fixed; and the two dropped
channels above were found in the same reading.

---

### 📌 NOTE TO SELF, before any animation work starts

**→ Read [§9](#9--what-the-parity-hunt-taught--read-this-before-writing-a-single-animation) first.**
It is the full set of rules the static-parity hunt paid for, and §9.2 in particular is a hazard this
note predates: a SCALE channel puts a non-unit scale back on the display and re-breaks the shading
of that one bone. Rotation and position channels are safe; scale is not.


**Build the matrix layer first, and lock the rest pose into it.** A sculpt already carries per-part
rotations AND per-part displacements, and those are the model's *identity* — the thing that makes it
that creature. A full-body animation has to compose ON TOP of that, once, as a transform applied to
a bone and inherited by everything under it. It must NOT be re-derived per part.

The failure mode if this is got wrong is specific and it has already appeared once in miniature:
`articulate` used to do `rest[i] + pose[i]` per Euler axis. That is not composition, and it is only
correct when the rest tilt and the keyframe happen to share an axis. Scaled up to a whole body, the
same mistake means every part re-applies the animation to itself against its own rest pose, the
creature comes apart at the joints, and — because the error is per-part — it looks like a modelling
bug rather than a maths bug.

So the shape wanted is: **one transform per BONE, composed down the chain as
`R(anim) · R(rest)` with the ancestor product carried in, and each part rigid within its bone.** That
is exactly `RigTransform.place`'s existing contract and it already takes `poseRotDeg`,
`posePosUnits`, `poseScale` and `headDeg`; what does not exist yet is anything that drives it for a
whole body over time, or any proof that it holds up over a loop.

**Pick a walk cycle for the spike**, and the reason is the oracle rather than the animation: a walk
loops, so it must return to its own first frame. That gives a self-consistency test needing no arm A
at all — photograph frame 0 and frame N of the same rig and they must be pixel-identical. A
composition error accumulates and shows as drift; a single held pose hides it completely. It also
drives several independent bone chains at once (two legs in opposite phase, arms counter-swinging),
which is the case a one-bone head turn cannot reach.

---

## 3. Hitbox and targeting

| | status |
|---|---|
| a player hit lands | ✔ health drops |
| another mob targets it | ✔ |
| `@e` selectors find it | ✔ found exactly once by a real AABB query |
| `modify_mob` adjusts it | ✔ resize and rename reach a vanilla base |
| effects apply | ✔ **36 of 36** non-instant mob effects |
| health and damage | ✔ 53.0 → 50.0 after 3 damage |
| **hitbox SHAPE** | ~ **0 of 9 corpus creatures too narrow** under `Fit.COVER`, worst cost 5.7% height overshoot; 7 of 9 inside 3% on both axes |
| **exact hitbox** | ✘ impossible with a `Mob` — `minecraft:scale` is uniform. An `Interaction` takes Spikor's box at **0.00% error**, so the composite (a Mob for AI/health + an Interaction for the aim surface) is the shape that reaches zero |

Vanilla offers **83 mob types, 44 peaceful-safe, 28 distinct width/height ratios**. The two corpus
creatures still off (12.3% and 17.8%) want ratios in the GAPS — 0.43 between vanilla's 0.37 and
0.48; 2.55 between 1.79 and 3.00 — not off the end.

⚠ **The base may not be a `Monster`.** `Mob.checkDespawn` discards anything whose
`shouldDespawnInPeaceful()` is true, so on Peaceful the hitbox, health and targetability vanish
while the rig floats on. Found in Gate C with a Ravager base that the client never received at all.

---

## 4. Abilities

| | status |
|---|---|
| an agent-built ability finds the creature | ✔ **fixed** — the carrier wears `wishmob` and `wishmob_<species>` |
| it fires ONCE, not once per display | ✔ asserted: exactly one entity in the rig may wear the tag |
| emission point vs the mod path | ✔ **0.000000 blocks** over 10 landmarks |
| fire breath, projectiles, particles | ✔ these are spawned at a position, and the position is right |

⚠ **This was the most dangerous defect found in the whole spike, and every cheap instrument was
green through it.** `apply_effect` targets by selector, and the rig tagged nothing — so every
ability ever built for a vanilla-path creature would install, compile, report `DONE` and match no
entity, forever, with no error anywhere. A photograph, a packet count, an emission point measured
off the base directly and a raycast all pass. Only asking *"would the agent's own selector find
this?"* sees it.

---

## 5. What the split costs that nothing can buy back

The creature's body is display entities and its state lives on an invisible carrier, so anything
vanilla draws ON a mob's model is drawn on something nobody can see.

| | status |
|---|---|
### The rule that decides every row

**Anything vanilla draws in WORLD space at the entity's position still works. Anything it draws ON
the entity's MODEL is gone, because the model belongs to something invisible.** That is the whole
distinction, and it predicts every entry below — which is why each one was checked against the
bytecode rather than reasoned about from the rule.

| what a player sees | how vanilla draws it | on this path |
|---|---|---|
| **potion swirl particles** | world particles at the entity's position | ✔ **works, 3.75x rarer — MEASURED.** `LivingEntity` picks the emission chance as `isInvisible() ? 15 : 4`; Gate C `vanillastate` confirms swirls reach the rig and are always thinner there; the RATIO is not measurable with that instrument (ten runs: 0.17–0.49 at the tier-1 camera, 0.04–0.10 at the tier-2 one, against a predicted 0.267 — a four-fold shift when only the CAMERA moved) |
| **burning flames** | `EntityRenderDispatcher.renderFlame`, gated only on `displayFireAnimation()` | ✔ **works — MEASURED.** No invisibility guard, and the flame is sized off the bounding box, which `COVER` already fits to the creature. Gate C `vanillastate`: arm B 12,322 px changed, 11,164 fire-coloured — and a burning COW in the same frame gives flame/body ratios of 1.51x height and 1.63x width against the creature's 1.61x/1.56x, so the rig gets exactly the shape vanilla gives any mob. It engulfs this creature only because its hitbox is 3.60 x 4.50 blocks |
| **ability particles, projectiles** | spawned at a position | ✔ emission point **0.000000 blocks** from the mod path's |
| **sounds** | played at the entity's position | ✔ |
| **name tag** | `EntityRenderer.render` calls it with no invisibility guard — **but `LivingEntityRenderer` OVERRIDES `shouldShowName` and ends `&& !entity.isInvisibleTo(player)`** | ✘ **lost** — see the correction below |
| **shadow** | `EntityRenderDispatcher`, explicitly `if (isInvisible()) skip` | ✔ **RECOVERED, and BETTER than the mod path — MEASURED.** One display (exactly one: they all share a position, so N discs stack) carries `shadow_radius = bbWidth / 2`, derived from the iron golem — the one vanilla mob big enough to calibrate a creature on. Gate C `vanillastate`: rig **9,928 px** of floor darkened, vanilla cow 820, mod path **672**. Arm A's is invisible: `WishMobRenderer` never updates `shadowRadius` on the custom-model branch, so a 3.6-block creature gets the humanoid default 0.5 × its scale and the whole disc hides under its own body |
| ⚠ **…at more than 16 blocks** | `strength = (1 − d²/256) × shadowStrength`, and the block is SKIPPED at ≤ 0 | ✘ **nothing in Minecraft has a shadow past 16 blocks** — not the rig, not the mod path, not a cow. Measured: the side-by-side pose (~20 blocks) reported 0 px for all three subjects, which is why the shadow row is three single-subject passes |
| **glow outline** | `LevelRenderer` routes the entity into the outline buffer on the shared `Glowing` flag | ✔ **RECOVERED — MEASURED.** The flag goes on **every** display (an outline traces GEOMETRY, unlike the shadow, which is a function of POSITION and goes on exactly one). Gate C: arm A 2,749 px, cow 720, rig 1,994 |
| **glow outline IN THE CREATURE'S OWN COLOUR** | `entity.getTeamColor()`, i.e. a scoreboard team | ✔ **the rig can and the mod path cannot.** `Display.getTeamColor()` is overridden to return `glow_color_override`, so it is one field per creature with no team anywhere. Measured 977 red-dominant outline px against arm A's 100 |
| **emissive parts** (glowing eyes) | a second `RenderType.eyes` pass, which ADDS light | ~ **emulated.** `brightness` 15/15 on the chosen groups is FULLBRIGHT, not additive: right in a cave, invisible at noon. Measured at midnight — 1 group of 41 gained 13.5 luma while the untouched arm moved 0.1. Which bones is not yet something the sculpt can say |
| **darkening at night** | the level's ambient light | ✔ **works — MEASURED, and it was NOT obvious.** `matchLight` writes a sky-light *level* of 15 on every display regardless of the hour, so it was entirely plausible that the rig stayed lit while the mod creature darkened. It does not: 113.9 luma lost by arm A against the rig's 109.7 |
| **the light it stands in** | `EntityRenderer.getPackedLightCoords`: ONE light for the whole model, read every frame at `BlockPos.containing(getLightProbePosition)` — the EYE — with block = `isOnFire() ? 15 : getBrightness(BLOCK)` and sky = `getBrightness(SKY)`, the stored level (nightfall is the client's lightmap, not this number) | ✔ **RECOVERED — MEASURED.** Every display carries a `brightness` override, so none is lit where IT stands; the override is the carrier's eye light by vanilla's rule (`RigLight.of`). ⚠ Until it was written once, at SPAWN, and never again: Gate C `vanillalight`, a villager beside the rig as the control, measured a room lit after the creature appeared at rig ÷ villager **0.23** (black) and a walk in from the sun to a dim room at **4.22** (still in daylight). `DisplayRig.followLight` now re-reads it on every pose write tick (3 ticks, ≤100 ms late) and writes only on a change: **1.24** and **0.83**, and a rig seated on a throne in a lit room was at parity (1.2) before and after. Cost: ATOM_RIG_COST §10 |
| **damage flash** | an `OverlayTexture` lerp on the model: pure red at alpha 178/255, applied as `mix(overlay, color, overlay.a)` | ✔ **EMULATED and MEASURED on a real death.** The multiply `0xFFB2B2` is derived from that arithmetic, not chosen: **green and blue come out exactly right**, red falls short by `0.302 × (1 − r)`. Gate C `vanilladeath` on a creature really killed by real damage: the rig's body red fraction 0.394 → **0.509**, a vanilla cow's 0.371 → 0.607. ⚠ And the mod path's is **0.379 → 0.380** — `renderCustom` passes `OverlayTexture.NO_OVERLAY`, so a sculpted creature has never flashed at all |
| ⚠ **an UNTINTED tintable rig** | n/a | 🔴 **a trap, now closed.** The tintable carrier is dyeable and every face declares `tintindex: 0`, so vanilla asks its dyed handler on every face — and that handler's fallback is `DyedItemColor.getOrDefault(stack, -6265536)`, **undyed leather brown**. A display with no `dyed_color` component is not untinted, it is brown. `wear` now writes `RigTint.NONE` unconditionally under `TINTABLE` |
| **freezing / poison / wither tint** | ⚠ **vanilla draws NOTHING on the model for any of them** — `LivingEntityRenderer.getOverlayCoords` is entirely `u = getWhiteOverlayProgress`, `v = hurtTime > 0 \|\| deathTime > 0`. Poison and wither are swirl particles only; freezing is a shake plus a first-person vignette | ~ the tint channel *can* colour a creature green or pale blue, and "make him green" is a wish a child will type — but a row that draws something vanilla does not is a **DIVERGENCE, not parity**, and `RigTint` labels the three of them as inventions. Nothing switches them on by itself. The shake stays unreachable |
| **death animation** (falling over) | `setupRotations`: `Axis.ZP.rotationDegrees(sqrt(deathTime/20 × 1.6) × 90)` | ✔ **BUILT on M2, and FILMED end to end from a real kill.** `RigTransform.about` post-multiplies every display by one rotation about the display origin — which for this rig is the carrier's feet, so no pivot arithmetic. Gate C `vanilladeath`: real lethal damage → the rig's silhouette goes **0.68 → 1.57** wide-over-tall (a vanilla cow in the same frame 0.43 → 2.33), driven on 19 ticks to a peak of 90°. ⚠ The mod path stays at **0.73 → 0.73** — `renderCustom` returns before `super.render`, so `setupRotations` never runs for a sculpted creature |
| **invisibility as a wish** | — | ✘ no-op: displays have no invisibility flag and the carrier is already invisible |
| **a bucket of milk** | — | ~ **one tick of exposure**, see below |
| **death cleanup** | — | ✔ **fixed** — `DisplayRig.reap` removes 6 of 6 |

### 🔴 CORRECTION — the name tag is NOT free, and the mistake is the one this repo already warns about

This table said the name tag survives because `renderNameTag` has no invisibility guard. That is
true of `EntityRenderer.render`, which is the method I read. It is not the method that runs.
`LivingEntityRenderer` **overrides** `shouldShowName`, and its last line is:

```java
return Minecraft.renderNames() && entity != cameraEntity
        && !entity.isInvisibleTo(localPlayer)      // ← the guard
        && !entity.isVehicle();
```

The carrier is invisible, so a named creature on the vanilla rig shows **no name tag at all**.

A known shape — *"'there is no check' and 'the check is not wired to this
path' look identical from outside and want opposite fixes — grep for a guard's CALLERS, never just
for the guard"* — and this is its close relative: **the guard was in an OVERRIDE, and the base
method genuinely had none.** Reading one level of a class hierarchy is reading the wrong method as
surely as reading the wrong file. When a row's verdict turns on "there is no guard", check which
implementation the entity's own renderer actually uses.

**Recoverable, and cheaply**, which is why this is a correction rather than a regression: a
`text_display` entity parked above the creature draws text in world space with no such gate, and it
is the same family of entity the rig is already made of. It moves from "free" to a small row on the
ladder.

### 🎨 The tint channel — the damage flash, and more, recovered

A vanilla client will multiply a model by a colour it is told at runtime, with no mod, if **two**
things are true at once, and either alone is silently inert:

1. every model face declares `"tintindex": 0`, so vanilla asks for a colour at all;
2. the carrier item is one vanilla has a colour handler for. **`paper` has none** — a tintindex on
   it renders plain white. The `dyed_color` handler is registered only for `#minecraft:dyeable`, so
   the tintable pack carries a leather item instead.

Then `minecraft:dyed_color` on the stack recolours the whole creature, and changing it is one item
rewrite per display — the same 53-byte write the animation path already prices.

**Verified in Gate C, not inferred** (`rigtint=on tintrgb=FF0000` on the one-box fixture): the
silhouette is unchanged and **8,429 px change colour**, with the mean subject dropping from
`(150,137,141)` to `(150,129,133)` — red held, green and blue pushed down.

It is a **multiply**, where vanilla's hurt flash is an additive overlay, so it darkens rather than
brightens: an emulation, not a reproduction. That is enough for a red damage flash, green for
poison, black for wither and pale blue for freezing. Off by default (`rigtint`) until a whole
creature has been photographed through it.

### Why the milk problem has no clean fix

The carrier must be hidden by the *effect* — `updateInvisibilityStatus()` runs every tick and is
literally `setInvisible(hasEffect(INVISIBILITY))` — so anything that clears effects reveals it.

- **A resource pack cannot help.** An entity texture is chosen by type and variant, never per
  entity, so a transparent skin would make every horse in the world invisible.
- **Re-applying works, at one tick.** `DisplayRig.hide`; measured `cleared->visible=true,
  reapplied->hidden=true` — 50 ms of a horse, once, when a child drinks milk.
- **The only thing invisible with nothing to clear is an entity invisible by nature.** An
  `Interaction` is, and has no AI or health — the same argument that leads to the composite in §3.

---

## 6. Delivery

| | status |
|---|---|
| everything the client sees is vanilla | ✔ asserted in Gate B and Gate C — no `moddingfromamod:` entity type in arm B |
| colour | ✔ the pack ships the same atlas `CreatureFiles` paints for the mod renderer, through the same painter. `missing-model pixels = 0` |
| pack push | ~ **1.7–2.6 s** client freeze, measured on the future rather than the call |
| **per-creature packs** | ✘ impossible on 1.21.1 — there is no `item_model` component, only `custom_model_data`, so the carrier-item override table is GLOBAL |

---

## 7. How each row was established

A parity claim is only worth what the instrument behind it is worth, so this is the method per row.
Three of the findings in this document were originally recorded the other way round from what the
evidence says, and all three were fixed by moving DOWN this list rather than by arguing.

| method | rows it settled | why it was the right instrument |
|---|---|---|
| **read the bytecode** | the particle rate (`isInvisible() ? 15 : 4`), flames having no invisibility guard, the shadow having one, the rotation conventions, `updateInvisibilityStatus` | The definitive answer to "does vanilla do X in case Y", and it is minutes. Four of this spike's bugs came from deriving an answer that the class file states outright |
| **a Gate B assertion** | effects landing (36/36), health, tags, the reaper, head-follow's cost, the tint component | Anything server-side and cheap. It cannot see a pixel |
| **a Gate C photograph** | every geometry row, the tint actually tinting, the pack being read at all, the carrier being invisible | The only instrument that can answer "what does a child see". Also the only one that caught a HORSE standing inside Spikor |
| **a fixture bisect** | which of eight geometric paths were wrong | The move that unstuck this twice: shrink the subject until the measurement can tell an intact rig from a broken one |

⚠ **A fixture that is symmetric in the axis a bug lives in cannot see that bug**, and this cost real
time twice. Three pixel-exact primitives said nothing about the intra-model X reversal because each
sits centred on model 8; the `zyx`/`xyz` sweep came back byte-identical because compose order cannot
matter for a single-axis tilt. Every fixture added since is deliberately asymmetric, and `__multi`
exists solely to make order observable.

⚠ **Claims corrected in this document since it was written**, kept because the pattern is the
lesson — each was a plausible story fitted to one observation:

| was recorded as | actually |
|---|---|
| a 27–56% fidelity gap, read as the format's ceiling | one 180° convention error in the rig |
| "tilt fixtures within 3 px" | that was the bounding BOX; the contents were 45% wrong |
| burning shows no flames | `renderFlame` has no invisibility guard — flames DO render |
| the hitbox has 4 of 9 too narrow, worst 21% | a hand-picked candidate list under a policy that could be narrow; the whole registry with `COVER` gives 0 of 9 |
| head-follow means the direction is unknown | the direction was always known; the write was missing |

---

## 8. The gap list, in the order it should be closed

1. ~~**Spikor's 46.2%**~~ — **CLOSED.** 46.2% → 25.6% (the model needed reflecting in Z as well as
   X) → 6.8% (the creature's scale had to move off the display and into the model). The remainder is
   z-fighting on coplanar boxes, which the mod renderer is itself unstable on to 1.3%. §1 has the
   working. **Next: two more corpus shapes**, because one creature is one fixture's worth of
   evidence whatever it is a fixture of.
2. **Head-follow**, then a Gate C test that actually animates a creature. The maths is right and
   unverified, which is the state the whole geometry was in an hour ago.
4. **Glow, shadow and the death fall** — each is a few lines now that the mechanism is known
   (mirror the flag; set one display's `shadow_radius`; rotate the rig before reaping).
5. **A whole creature through the tint channel**, then wire the damage flash to `hurtTime`.
6. **The `Interaction` composite** for the exact hitbox, if the 5.7% `COVER` overshoot is not good
   enough.
7. **An end-to-end wish** (Gate C½, `headlesswish=` against a rig-enabled world). Never run. §4's
   defect is exactly what an e2e wish would have caught on day one.

---

## 9. 🔴 WHAT THE PARITY HUNT TAUGHT — read this before writing a single animation

**📍 Static parity landed at `b738da6` — `🎯 STATIC PARITY ACHIEVED`.** That is the revert point if a
later change ever regresses the look; find it with `git log --grep="STATIC PARITY ACHIEVED"` or by
the full sha `b738da6ad8b747f310d2cb3c2ca98d37c018743c`. It is also tagged `vanilla-rig-static-parity` **locally only** — this
container's git proxy refuses tag pushes (HTTP 403 on `refs/tags/*`, branches are fine), so the sha
written here is the durable record and the tag is a convenience for whoever has the clone.

Everything below is a rule
paid for with a measurement, and every one of them applies to animation as much as it did to
geometry. This section exists because the next person to touch this — probably me — will be adding
motion on top of a rig that is currently pixel-exact, and the ways to break that are now known.

### 9.1 The contract that is proven, and must not be gone around

`RigTransform.place(group, scale, …)` → `RigPlan.scaled` → `RigPlan.localBox` → the model JSON →
the display's `transformation` is, end to end, an exact reproduction of `RuntimeGeoModel`. It is
proven on thirteen fixtures and one 62-box creature. **Any animation must drive it through
`place()`'s pose parameters (`poseRotDeg`, `posePosUnits`, `poseScale`, `headDeg`) rather than
computing a transform beside it.** A second path is a second thing to keep in lockstep, and the
first three bugs on this page were all lockstep failures.

### 9.2 🔴 ROTATION AND TRANSLATION ARE FREE. **SCALE IS NOT.**

This is the single most important line in this document for animation work.

Vanilla's `PoseStack.mulPose(Matrix4f)` branches on whether the matrix is **orthonormal**. A pure
rotation is; a rotation plus a translation is; a rotation with a **scale ≠ 1 is not**, and the
non-orthonormal branch shades side faces differently — measured at **0.800 against 0.737** on a
north face, a uniform ~8% on every side face while the top stays exact.

That is why the creature's own scale had to move into the model (`RigPlan.scaled`). The consequence
for animation is direct and unforgiving:

- a **rotation** channel is safe;
- a **position** channel is safe;
- a **scale** channel — squash and stretch, a growing head, a puffing chest — **will put a non-unit
  scale back on the display and re-break the shading of that bone, and only of that bone**, so it
  will read as "one part of the creature is subtly the wrong colour while it animates".

`place()` already accepts `poseScale` and multiplies it into the display's scale. **Before shipping
any animation with a scale channel, measure it**: one fixture, one keyframe, scale 1.0 vs 1.2, and
compare. The three-box `__legs3` / `__legs3!scale12` pair is exactly that experiment and it runs in
two minutes. If it reproduces, the fix has the same shape — fold the pose scale into the model and
regenerate the pack for that frame, which is only affordable for a *static* scale, so a per-frame
scale channel may simply not be expressible on this path. **Find that out on a fixture, not on a
creature.**

### 9.3 Compose per BONE, as quaternions, inherited down the chain

`articulate` used to do `rest[i] + pose[i]` per Euler axis. Euler angles are not a vector space, so
that is correct only when the rest tilt and the keyframe share one axis — right on a creature whose
bones all turn about Z, silently wrong on the next one. The shape wanted is one transform per bone,
`R(anim) · R(rest)`, with the ancestor product carried in and each part rigid within its bone. A
sculpt's per-part rotations and displacements are the creature's *identity*; an animation composes
on top of them once, and must never be re-derived per part.

### 9.4 🔴 A `wish_mob` STANDING STILL IS ANIMATING — freeze both arms or measure nothing

The single most expensive mistake of the whole feature, and it was invisible for the entire static
phase. `AnimationState.select` falls through to the **idle** clip whenever the creature is not
walking or attacking, driven by `tickCount`. So arm A bobs, sways and swings its arms while arm B
stands at rest, and two photographs of arm A taken seconds apart are two different poses.

That produced a same-arm "floor" of 848 px which was then written up — in this document and in a
published artifact — as z-fighting on coplanar boxes. It was the creature breathing.

**Every animation comparison must pin BOTH arms to one frame**, and the mechanism already exists:
`-Dmoddingfromamod.poseClip=walk -Dmoddingfromamod.poseTime=0.45` is read by
`WishMobRenderer.POSE_OVERRIDE` for the mod and, since the spike, by the A/B harness for the rig.
Deliberately the SAME two properties rather than a second pair — a frame typed twice is a frame that
will disagree, the same class of bug as a tween duration typed separately from the schedule.

With that done the floor is **0 px**, twice over, on two different clips. A floor of exactly zero is
also the check that the freeze took: if arm A is still moving between its two shots, the pose
override did not fire and the run is measuring nothing.

### 9.5 A fixture symmetric in the axis a bug lives in cannot see that bug

It bit **three times in a row** on one feature:

| the bug | what was blind to it | how many green fixtures it hid behind |
|---|---|---|
| element X reversed | every fixture centred on the model origin | 3 |
| element Z also needs reversing | every fixture with all its boxes at one z | 10 |
| a display scale ≠ 1 shades wrong | every fixture with ONE box, at any scale | 11 |

**The animation equivalents, written down now so they are not rediscovered:**

- a pose with **both legs in the same phase** cannot see a left/right channel swap;
- a **single held frame** cannot see composition drift — only a loop can;
- a pose at **t = 0** is usually the rest pose, so it cannot see anything at all;
- a bone whose rest rotation is **zero** cannot see a wrong composition ORDER, because
  `R(anim)·R(rest)` and `R(rest)·R(anim)` agree when one of them is identity. **Give the animation
  fixture a non-zero rest tilt on at least one bone**, or the order bug ships.

### 9.6 Instruments lie, and they lie in the reassuring direction

Four times on this feature, and every one produced a confident, plausible, actionable number:

| instrument | what it said | what was true |
|---|---|---|
| corner probe v1 | 0 of 62 boxes misplaced, worst 0.000 units | it skipped `localBox`, the function where the model file is actually written |
| `#group` filter | narrowed a creature to one bone for a clean comparison | filtering changes what arm A's *entity* computes, so the two arms rendered at different sizes |
| the light hypothesis | a uniform 0.92 across a whole creature — surely one light level | forcing 15/15 moved it by 82 px of 16,340 |
| the UV-mirror fix | reflecting the element must need its UVs mirrored | measured **worse**, 4,315 → 4,439 |

The rule that survived all four: **when a number agrees with what you already suspect, check how it
was produced** — and prefer a photograph over a computed answer, because three of these four
disagreed with a photograph and the photograph was right every time.

### 9.7 The levers that exist, and what each is worth

All of them are `-Dmoddingfromamod.<name>` / `-Ptestprops`.

| lever | default | status |
|---|---|---|
| `rigflipmodelx` | **on** | load-bearing. `__asym` reads 0 px on and 49.6% off. |
| `rigflipmodelz` | follows X | load-bearing. `__legs3` reads 0 px on and 53.9% off. |
| `rigshade` | on | load-bearing. Off measured worse (17,283 → 18,338). |
| `rigfaceswap` | on | measured NEUTRAL (4,316 vs 4,338). On because it is correct, not because a number says so. |
| `riguvflip` | empty | keep empty. Non-empty measured worse. Kept so the experiment is a minute, not an afternoon. |
| `rigtint` | off | the runtime colour channel; verified, 8,429 px recoloured. |

### 9.8 The animation spike, and why a walk cycle

Pick a **walk**, for the oracle rather than the animation: a walk loops, so frame 0 and frame N of
the same rig must be **pixel-identical to each other**. That is a self-consistency test needing no
arm A at all, and a composition error accumulates into visible drift where a single held pose hides
it completely. It also drives several independent bone chains at once — two legs in opposite phase,
arms counter-swinging — which is the case a one-bone head turn cannot reach.

Add one more assertion while you are there: **photograph the same frame twice** and confirm the
loop-closure delta is under the same-arm floor, or the "drift" you measure is just the z-fighting
from §9.4.

---

## 10. 🎬 THE ANIMATION SPIKE — done, and what it measured

The spike §9.8 called for, run on **spikor-armB** with the walk clip every wished creature ships.
Its four bones swing in two opposed pairs, `armL`/`armR` each carry a **non-zero rest tilt** and four
children of their own, and the idle clip drives a **position** channel on `body`, which has 17
descendants. So compose order, the ancestor chain and the translation channel are all live on the
real subject rather than on a fixture built to be kind.

| | result |
|---|---|
| **loop closure**, photographed — one whole cycle on, same rig | **0 px**, on both clips |
| **A/B at `walk` t=0**, both arms frozen at the same frame | **935 px · 1.5%**, floor **0 px** |
| **A/B at `idle` t=1.5**, the body position channel + 25 displays rewritten | **890 px · 1.4%**, floor **0 px** |
| **A/B BETWEEN keyframes**, `walk` t=0.375 | **835 px · 1.3%**, floor **0 px** |
| **the client tween**, 30 ticks, sampled in flight | **monotonic and exact** — see below |
| silhouettes | identical to the pixel (30,885 = 30,885) |
| idle cost | **0** displays rewritten — unchanged |

Loop closure is the number with no second renderer in it: a walk must return to its own first frame,
so 0 px says the pose is being COMPOSED onto the rest pose rather than re-derived from it. That is
the property §9.3 is about and the only one a single held frame cannot test.

### What had to be built

**`RigPose` — the matrix layer.** `RigPlan.Group` carried an ancestor chain baked at plan time from
the ancestors' **rest** rotations. Correct forever for a static creature, wrong on the first
keyframe: when `armL` swings, its four claws are separate displays whose chain still says where the
arm was at rest, so the arm moves and the claws stay behind. `RigPose.compose` recomputes the chain
per frame with each ancestor contributing exactly what `RuntimeGeoModel.drawBone` gives it.

**`RigPlan.exactGroups(parts, alsoSplit)` — the split that was asking the wrong question.**
`exactGroups` splits a bone out when it is *tilted relative to its group*, because a vanilla element
cannot draw an arbitrary angle. An animation asks whether a bone can move **independently**, and the
two answers differ: measured on Spikor, `legL` and `legR` are both unrotated, so both were folded
into the `root` model together with the feet — and a clip that swings them in opposite phase had one
display holding both. Nothing errors. The legs simply do not move.

**`RigDriver.animate`.** `articulate` rewrites a display when that display's own bone has a keyframe,
which is right for a head turn and wrong for a hierarchy. `animate` walks the frame instead, so a
bone whose ANCESTOR moved is rewritten too — 12 displays for the walk, 25 for the idle, and still
**zero** when nothing is animating.

### Nine headless oracles, and the one that was thrown away

`RigPoseTest` runs in seconds and covers what a photograph cannot cheaply reach: loop closure over
one cycle and over ten, an animated bone changing the chain its children inherit, a non-animated
bone keeping its rest chain, an idle creature costing nothing, every animated bone getting its own
display, and a scale channel being NAMED rather than silently dropped.

⚠ **The tenth was a corner probe, and it was wrong — again.** The obvious oracle is "compute where
the mod would put each box under this pose and compare". It was written, and it reported **24 units**
of error at REST, on a static path that photographs at 0 px. Two fixes later it reported 43. The mod
mirrors its whole model at render time while the rig mirrors the parts up front, reflects the element
inside the model, and cancels that with a half turn on the display entity — three conventions, and a
local-coordinate probe has no way to say which one it got wrong.

It was replaced by a **differential** oracle instead: feed the matrix layer an EMPTY pose and it must
reproduce, to 1e-5, the chain `RigPlan.exactGroups` already bakes — the same coordinate system, no
reimplementation of the mod, and a chain that is part of the pixel-exact static path. **The control
ran first and failed first**, which is the only reason an afternoon did not go into the matrix layer
looking for a bug that was in the ruler.

### The client tween — the transport half, and the one a frozen frame cannot reach

Every other number here is a POSE: the server writes a transform and the client snaps to it. That is
not how the rig is meant to run. The cost model rests on the server writing a pose every N ticks and
the CLIENT smoothing between them — 53 bytes an update rather than a transform per bone per tick —
so "does the tween land where it was sent" is a different question from "is the pose right", and it
had never been asked.

Fly the rig half a cycle away, snap, then ask the client to travel back over 30 ticks, photographing
along the way. Pixels from the destination:

<pre>  4987  →  4875  →  4655  →  4131  →  2952  →  0        (monotonic)
  away      t=6      t=12     t=18     t=24     landed</pre>

The curve is steeply non-linear because a pixel diff is not linear in an angle — two silhouettes
collapse onto each other fast once they are close. What matters is that it **moves, moves one way,
and lands on exactly the pose the server sent**.

⚠ **The assertion is deliberately TWO-SIDED, and the first run proved why.** "It landed on the right
pose" is satisfied by a client that ignored the duration and snapped instantly, so the mid-flight
frame has to prove the tween was actually running. The first run reported exactly that —
`mid-flight 0 px from the destination … SNAPPED (the duration did nothing)` — and a one-sided check
would have called it a pass.

⚠ **And then TWO instrument bugs, in the same feature, both reading a true number about the wrong
thing.** The fourth and fifth of the spike:

| | what it said | what was true |
|---|---|---|
| read the interpolation fields off `displays().get(0)` | `interpolation_duration=0` — the write never arrived | that display is a group the walk clip never moves, so nothing was ever sent to it. Read one the driver actually rewrote (`legL`) and it says 30. |
| pass the duration through a static across `server.execute` | the tween reported itself as sent | the client thread reset the static before the server task ran, so the duration arrived as 0. It rides as an argument now. |

The control that separates them is the third shot: **how far did the away pose actually move the
rig?** 4,987 px. Without that number, "mid-flight looks like the destination" is equally consistent
with a rig that never went anywhere, and the two want opposite fixes.

⚠ **The table's first row is the same trap as, found a year of rig-time later on the OTHER half
of the transport.** "A display the clip never moves carries a different interpolation field" was
recorded here as an *instrument* fault — reading the wrong display. Nobody asked whether the same
per-display difference was a *product* fault for `teleport_duration`, which `apply` also wrote as the
tween, so a display the clip never moved also eased into each carrier MOVE differently from one it
did. On a creature standing still that is invisible; on one walking it is the head sliding off the
body. `teleport_duration` is now one rig-wide value (`DisplayRig.MOTION_TICKS`), and
`GateC:vanillawalklook` measures the spread across every display on every frame —
`docs/VANILLA_RIG_ANIMATION_LADDER.md` §5.4.

### What is NOT covered yet

- **Scale channels**, refused by design (§9.2) and named at runtime.
- **Attack**, which is driven by vanilla's swing progress rather than by wall-clock, so its time base
  is a different question from the walk's.
- **Two more creatures.** One is one, whatever it is a fixture of.

---

## 11. 🚶 A CREATURE ACTUALLY WALKING — driven from its own motion, and filmed

`RigAnimator` reads the four fields `WishMobRenderer` reads off the entity — swinging, swing
progress, limb speed, limb distance — and hands them to the same `AnimationState.select`. It has no
walk-detection rule of its own on purpose: two rules that must agree are two rules that will not.

It writes every `DEFAULT_INTERVAL` (3) ticks and hands the client that same number as the tween
duration, so the client covers exactly the gap until the next write. **The interval and the duration
are one number**, for the reason §10 gives.

Measured on Spikor, both arms walking side by side in one shot, 360 ticks:

| | |
|---|---|
| both creatures moved | 3.17 blocks from their lanes, identically |
| `walkAnimation` | peak speed 0.360, phase advanced to 125.79 |
| the walk clip was selected on | **360 of 360 ticks** |
| arm B's transform writes | 2,242 over 360 ticks — ~18 displays per write cycle, one cycle in three ticks |
| arm B's model | resolved to a real model, not the carrier |

### 🔴 A NO-AI MOB DOES NOT ANIMATE ITS OWN WALK, and this cost two filmed runs

`walkAnimation` is updated by `calculateEntityAnimation`, which is called from exactly **one** place:
`LivingEntity.travel()`. And `travel()` runs only when `isControlledByLocalInstance()`, which for a
`Mob` is `!isClientSide && !isNoAi()`.

So `setNoAi(true)` — which every fixture in this repo sets, for good reasons — silently disables the
walk animation. **The creature can be moved any distance by any means and its limb speed stays
0.000.** Both arms then fall through to their idle clip and film beautifully, standing still.

Nor does moving it with `Entity.move` from a queued task help: `calculateEntityAnimation` measures
`getX() - xo` from *inside* the entity's own tick, and a `server.execute` task runs outside it, so by
the time the entity ticks its old position is already the new one. The fix is to leave the AI on and
zero the mob's `MOVEMENT_SPEED` instead, then set a delta and let vanilla's own `travel()` move it.

### The three controls this test carries, each because it caught something

1. **Did they MOVE?** Reported in blocks. A creature that never moved cannot have a walk animation,
   and "limb speed is zero" would then be a true statement about the wrong thing.
2. **Did the walk clip get SELECTED?** 360 of 360. A creature that moved but played its idle is a
   different failure with the same picture.
3. **Is arm B wearing its MODELS?** The first successful run filmed a scatter of loose **paper**
   standing where the creature should be, and reported **PASS** — because every assertion in this
   test is about motion, and a sheet of paper moves exactly as well as a creature does. Asking the
   renderer which model it resolves for the stack is what tells them apart.

⚠ **The paper was a trap this repo had already documented, re-hit because a new harness COPIED the
setup instead of calling it.** Selecting a pack means `repo.setSelected(...)`, not writing
`mc.options.resourcePacks` — the options list is what the options SCREEN writes on its way out, and
`reloadResourcePacks()` opens the repository's own selection. The A/B harness carries a paragraph
about this, ending "the rig rendered as six sheets of paper". It is now
`ClientHarness.selectPack`, so the third harness cannot get it wrong.


### 🔴 THE FOURTH CONTROL, added after a human watched the film: **they were walking BACKWARDS**

The run above passed all three controls, produced eighteen seconds of clean video, and showed two
creatures **moon-walking** for the whole take. Dad spotted it in the artifact in about four seconds.
Nothing in the harness could: distance travelled, clip selection, phase advance and model resolution
are every one of them *true of a creature sliding backwards*.

**It was two separate defects, and they hid each other.**

**(a) The harness aimed the yaw 180° from the travel.** `Entity.calculateViewVector` is
`x = sin(-yaw)`, so **yaw 90 faces −X** — and the test paired it with a `+X` delta. Worse, vanilla
does not correct that, it *commits* to it: `LivingEntity.aiStep` aims `yBodyRot` at the bearing of
travel **unless** the head is 95–265° away from it, in which case it deliberately uses
`bearing − 180` so that a mob looking one way while moving the other renders as walking backwards.
Ours was exactly 180 out, so the body locked onto the head and stayed there. Vanilla was working.

**(b) A real bug in the shipping path — `RigDriver.follow` dropped the half turn.** The offset that
cancels the world-space mirror was applied in `DisplayRig.spawn` and **nowhere else**, so
`follow` rewrote every display with the raw yaw. A rig that stood still was pixel-perfect; a rig
that **walked** was 180° out, permanently, from its first step.

Which is why only *one* arm looked wrong in the film: (a) turned arm A backwards, (b) turned arm B
backwards *again*, and two wrongs made arm B accidentally look right. **Fixing either one alone
would have made the other arm the broken one.**

Same shape as the train-axis literal in the camera notes — a constant that is correct at one call
site and simply absent at the second. The fix is not to remember it: `DisplayRig.displayYaw(bodyYaw)`
is now spelt once and every writer goes through it.

**What now guards it, at three levels:**

| level | check | verified |
|---|---|---|
| Gate B | `aRigThatMovesKeepsTheFacingItWasSpawnedWith` — a rig that MOVES must face the way a rig that STANDS STILL faces, asserted over every display | **verified to fail**: putting the raw yaw back gives *"a display turned by -180.0 degrees just by MOVING"* |
| Gate C | worst angle between each creature's rendered **body** and its **bearing of travel**, over the whole run | fails over 5° — the moon-walk itself |
| Gate C | worst **A-vs-B disagreement**, compared through `displayYaw` so the control cannot encode the bug it exists to catch | fails over 5° |

**The lesson, which is the reusable part:** *a control that is TRUE OF THE BUG is not a control.*
Before adding one, name the failure it is for and ask whether the number would be **different** if
that failure happened. For anything with a direction, an orientation or an ordering, a magnitude
never is — the control has to be an angle or a comparison. All three original controls were
magnitudes.

**And a second-order one:** the film was the instrument that caught it, but only because a person
watched it. The repo's own camera rule — *open the PNG* — extends to video, and it is the one
instrument here with no failure mode of its own.

### The re-filmed run, with both fixes in

`facing: worst body-vs-travel A 0.0 deg, B 0.0 deg | worst A-vs-B disagreement 0.0 deg` —
both creatures face exactly the way they are travelling, and agree with each other exactly.
Alongside: 3.13 blocks each, peak `walkAnimation` speed 0.360, walk clip on **359 of 360** ticks,
2,242 transform writes (~18 displays per 3-tick cycle), arm B's stack resolving to a real model.

**What that run does NOT establish**, stated here so it is not read as more than it is: the two
creatures stand in different lanes, so they are seen from ~29° and ~15° off the camera axis and a
side-by-side pixel diff (22–26%, flat across a ±6-tick sweep) measures viewpoint at least as much as
pose. Live phase alignment is **unmeasured**; the designed experiment for it is in
`docs/VANILLA_RIG_ANIMATION_LADDER.md` §2.4b.

## 13. 🎯 DOES THE CARRIER STAND WHERE THE CREATURE IS?

**a tester's report, from play:** two long sea creatures glowing, and each outline a
short four-legged land mob at ONE END of the body. Everything a child does to a creature that is
not looking at it — hit it, click it, bump into it, read its name, see its glow — answers for the
invisible carrier, so a carrier in the wrong place is a creature you cannot touch where you see it.

### The instrument (Gate A, deterministic, no noise floor)

`vanilla/RigFit` measures the rig **as drawn** — every atom's display transform applied to the
unit cube's corners, rotations and ancestor chains included — against the carrier's real box, and
reports: **ray hit** (rays aimed from 8 bearings, level and 30° down, at what the child SEES: the
fraction that cross the hitbox, which is what vanilla picks with), **phantom** (of the rays the
hitbox catches, the fraction that met no creature — a click on thin air that lands anyway), bbox
and volume IoU, and the horizontal offset of the creature's **mass centre** from the carrier.
`RigFitCorpusTest` runs it over every committed creature: **52 real sculpts** (the starter library,
`corpus/tier-experiment`, the `docs/starter-candidates` sculpts and the Opus 5.5 eval's 39 archived
sculpts, deduplicated, harvested into `src/test/resources/rig-fit/real/`), **6 synthetic shapes the
corpus does not contain** (a whale authored from its tail, as in the video; a centred whale; an eel;
a shark; a snake; a flying bird) and the **5 stand-ins**. The full per-creature table is
the recorded evidence.

### The baseline, and what it said that the video did not

| before an earlier change (`main`) | n | ray hit median / min | phantom median | bbox IoU median | mass-centre offset median / max |
|---|---:|---|---:|---:|---|
| real sculpts | 52 | 1.000 / 0.930 | **0.612** | 0.531 | 0.09 / **1.49** blocks |
| synthetic shapes | 6 | 0.804 / **0.378** | 0.645 | 0.301 | 0.19 / **2.64** blocks |

- **The old box was not too SMALL on a real creature — it was too BIG and in the wrong place.**
  `RigBase.choose` matched width against `CreatureAnatomy.widthBlocks`, which is twice the reach
  from the sculpt's ORIGIN: an off-origin creature asked for a box up to twice its length. Aimed
  clicks landed (median 1.000) because the box swallowed everything, and **61% of the clicks it
  caught were on thin air**.
- **Position** was the video's bug: the carrier stood at the sculpt's origin. A real sculpt in the
  corpus is 1.49 blocks off; the tail-authored whale is 2.64, and a ray aimed at it hits 78%.

### What was compared, and what ships

| variant | ray hit median / mean / min | phantom | bbox IoU | offset max |
|---|---|---:|---:|---:|
| V0 `main` | 1.000 / 0.973 / 0.378 | 0.614 | 0.491 | 2.64 |
| V1 centred on the bbox, `main`'s choice | 1.000 / 0.976 / 0.365 | 0.610 | 0.519 | 1.05 * |
| V3 mass-centred, **box IoU**, old pool | 0.989 / 0.954 / 0.383 | **0.476** | **0.689** | 0.02 |
| V5 mass-centred, **mass F1** (sampled), old pool | 0.768 / 0.751 / 0.185 | 0.184 | 0.355 | 0.02 |
| V8 mass-centred, **box F2**, `CarrierPool` by movement | **1.000 / 0.979 / 0.490** | 0.589 | 0.609 | **0.02** |
| **SHIPS** = V8 through the production calls | identical to V8, pinned per creature | | | |

\* measured from the mass centre; a bbox-centred rig is 0 from its own bbox middle.

- **Centre on MASS, not the bounding box.** Equal on every aggregate (V7 vs V8), and chosen for the
  case the aggregate hides: `hoodie_boy-26e91d5b` has a mouth floating two blocks in front of its
  body, and the box's middle is the empty air between them. Only X/Z move; the feet stay at y=0.
- **F2 on the two boxes, not IoU.** IoU cut the thin-air clicks furthest and paid for it in aimed
  hits (worst creature 0.38); a tester's order was "can I hit it where I see it" first, and F2
  (recall weighted twice) keeps the median at 1.000 and raises the worst to 0.49 while still
  cutting phantom and lifting bbox IoU 0.491 → **0.609**. A sampled mass objective (V5) cuts
  phantom to 0.18 and loses a quarter of aimed clicks — refuted.
- **The pool (`CarrierPool`)** is classified by how each mob moves — WALK, SWIM (amphibians safe
  anywhere; dolphin/squid only when spawned IN water), FLY — and every row says what the mob does
  besides carry. Movement class comes first (`flies` power → FLY; fins and nothing to stand on, or a
  sea creature's name and no legs → SWIM; else WALK), then shape. Removed because each can take the
  creature away from the child: bee (enters hives, dies after stinging), mooshroom (shears DISCARD
  it), sniffer (digs items up); never added: bucketable fish and axolotls, parrots, allays, grass-
  and crop-eaters. Hostile rows stay listed and are never chosen (the Peaceful rule).

**Worse after than before, by more than 0.02 ray hit** (named, as the issue asked): two Dinkos
1.00 → 0.98, the standin spider 1.00 → 0.96, the synthetic flying bird 1.00 → 0.93 (it is now on a
bat, which flies; its box is taller than the bird and narrower than its wings). Each also lost
thin-air clicks.

### What it cannot fix: a long thin body

A vanilla hitbox is a square prism. The eel (L/W ≈ 12) lands **0.49** of aimed clicks and the snake
**0.59**, before and after; covering their length means a box as wide as they are long. That is
(d) in the issue — extra `interaction` entities along the body, forwarding an attack to the carrier
— and it is **not built**: nothing in the committed corpus is shaped like that, and a tester's
whales went 0.78 → **0.97**. On a real client (below) aiming at the whale's very tips still misses;
that is the number to beat if (d) is taken up.

### On a real client, and on stock ones

`-Ptestmode=rigfit` (`src/main/java/com/moddingfromamod/gatec/VanillaFitClientTest`, film with `./tools/record-client.sh
rigfit`) stages the tail-authored whale in water and Spikor on land, glowing, draws each carrier's
REAL box as lime edges (vanilla's hitbox view skips invisible entities), asks the client's own pick
ray whether aiming at each creature's front, middle and back lands on it, then turns both a full
circle. The same file ran on a `main` checkout:

| | whale carrier | whale: front (tail) / middle / back (head) | Spikor carrier | Spikor: front / middle / back |
|---|---|---|---|---|
| before (`main` 5d00ef34) | turtle ×4.22, at the tail | **HIT** / MISS / MISS | horse ×2.81 | HIT / HIT / HIT |
| after | turtle ×5.13, under the middle | MISS / **HIT** / MISS | camel ×1.92 | HIT / HIT / HIT |

Before, the only part of the whale that answered a click was its TAIL, because that is where its
carrier stood; after, its middle does, and neither tip does, because a square box cannot be 8.6
blocks long without being 8.6 wide (that is (d); the ray-grid number, 0.78 → 0.97, is the
quantitative one — three points are a check, not a measurement). The run's verdict is the MIDDLE
column: PASS after, FAIL before.

⚠ **The first version of this table was wrong, and the instrument was the reason.** It read
`mc.hitResult`, which the client refreshes only on a tick it is not PAUSED — and an unfocused Xvfb
window pauses it. Four shots of a run read the pick made from the camera seat before the aim began
(`-0.50, 111.62, 6.50`) while the eye position, the client's copy of the box and a hand clip all
said HIT, so the before arm was first recorded as 2/6 and a re-run of the after arm read the same
2/6. The test now calls the client's own `gameRenderer.pick` before reading, logs the eye, the
box and the clip beside every verdict, and both arms were re-filmed. It is the the camera notes lesson in a
new place: the cached answer and the fresh one look identical until you print where it came from.

In the pre- films (`outline-pre775/`) the whale swings round its tail, outside its own glow
outline, before; after, it turns inside it. Since the carrier draws no outline of its own, so
the current films show the lime edges — the box the game picks with — instead. A **genuinely stock 1.21.4 client** (0 loader entries) and a **stock 1.21.1 client through ViaProxy 3.4.13**
into the 1.21.4 server photograph the same thing: the whale inside its (differently drawn) turtle
outline as it turns.

### Worlds saved before an earlier change

The carrier TYPE is saved with the entity and is left alone: an existing creature keeps its carrier
until something rebuilds it. Its displays' saved transforms carry the OLD offset, and the drivers
rewrite only the bones an animation moves — so `DisplayRig.adopt` now re-seats EVERY display at
rest on load (one write per display, once), or an idle body would stay put while a moving limb
jumped to the centred plan. Nothing reads the old shape and no store changes, so there is no shim
and no `store-migrations.json` row (the census covers stores, and no store changed). Rehearsed: a
world saved by `main` with a whale and a Spikor, reopened on this branch — both adopted with the
**same UUIDs and carriers**, whale 2.609 → **0.008** blocks off-centre; the same world on `main`
stays 2.609. Pinned by `RigFitGameTest.adoptReseatsADisplaySavedBeforeCentring`.

✅ **Found on the way, fixed in an earlier change:** `adoptAll` used to run only on `LevelEvent.Load`, deferred
one task. In this rehearsal it ran before the saved entities had loaded (and before `ServerStarted`
had loaded the species registry) and adopted nothing. A creature whose chunk unloaded and loaded
again was never adopted at all, so it stood as a statue, and after an earlier change it also had no hit proxies. Now a
carrier is queued on its own `EntityJoinLevelEvent(loadedFromDisk)` and adopted on the next ticks
(`VanillaRigAnimator.adoptPending`). It waits up to 40 ticks for the displays that load alongside it
before rebuilding, and gets back exactly the proxies it was spawned with (`HitProxies.planFor`).
`RigReloadGameTest` drives a real chunk unload and reload. It was red on `main` ("a statue") and is
green with the fix.

An ability's aim moved with the carrier: `inspect_creature` reports `forward` from where the
carrier now stands (`RigFit.forwardShiftBlocks`), or `positioned ^0 ^up ^forward` would fire from
the whale's tail.

### 13½. "Which creature do you mean" — the carrier is never the answer

**A carrier's type is a fact about the carrier, so nothing an agent reads or types may use it.**
After the code that FOUND a creature still matched the entity's registry type, which is the
carrier's: `modify_mob {target:"spikor"}` answered *"No mob matching spikor … Nearby:
minecraft:llama"*, the workaround `{target:"llama", scope:"group"}` also reached the child's real
llamas, `look_around` listed Spikor as `minecraft:llama` and listed his hit proxies as mobs, a
`run_command` diff minted handles (`@frog`, `@armadillo`) for the proxies, and `ArtifactIndex` read
every creature as "in another world" (distance −1), so "make it bigger" next to him meant the castle.

**One resolver now answers it for every tool**: `mob/CreatureMatch` (pure, `CreatureMatchTest`)
decides, and `mob/Creatures` runs it — `Creatures.nearby` is the one list `modify_mob`,
`look_around` and handle minting share (never a proxy), a creature answers to its species id, name,
tag (`wishmob_spikor`, also as `@e[tag=…]`) or the name above its head and **never** to its carrier's
type, and a creature's handle records `wishmob:<species>` (so it is `@spikor`, and "llama" never
re-establishes it). `ArtifactIndex` measures a species from its carriers and folds a handle on a
carrier into that species, so one creature is one referent. `photograph`/`finish_building` accept a
creature's handle as its subject. **Writing a new `getEntitiesOfClass(LivingEntity.class, …)` for
something the agent will read or act on? Call `Creatures.nearby` instead.**
`CreatureResolverGameTest` stands a long creature (with proxies) beside a real mob of its carrier's
type; it was red on `main` in all four tests.

## 14. 🎯 HIT PROXIES: clicks AND arrows land where the creature is DRAWN

§13 put one square box under the middle of the creature. For a long or sprawling body the best
single box is still wrong one of two ways: tight, so a click on the head goes through it, or loose,
so a click on the air beside it lands. asked for both clicks and **arrows** (a projectile tests
a box grown by **0.3**, `ProjectileUtil.getEntityHitResult`; a click tests the exact box), measured
against what vanilla's own mobs do.

**The mechanism.** A small CORE carrier plus 0–6 invisible **hit proxy** mobs over the parts it
leaves out, and every hit on a proxy forwarded to the carrier (`vanilla/HitProxies`):
a punch (`AttackEntityEvent` → `player.attack(carrier)`), a lead, name tag or food
(`PlayerInteractEvent.EntityInteract` → `interactOn(carrier)`), and any projectile
(`ProjectileImpactEvent` cancelled, a fresh impact on the carrier posted so other listeners see
it, then applied through `Projectile.hitTargetOrDeflectSelf` via `ProjectileHitInvoker`).
`ProxyProjectileMixin` makes `canHitEntity(proxy)` answer as for the carrier, so a creature's own
fire breath, born inside its head, flies out through the proxy there. An `interaction` entity was
rejected: `canBeHitByProjectile()` is `false`, so arrows go straight through.

A proxy is a hitbox and nothing else: invisible (the effect), silent, no AI, no gravity,
invulnerable, `noPhysics` (it neither pushes nor is pushed, trips no pressure plate, never
suffocates: `Entity.push`, `isAffectedByBlocks` and `isInWall` each test it), never glowing
(`CarrierGlowFlagMixin`), persistent (which also keeps it out of the spawn cap: `NaturalSpawner`
skips `isPersistenceRequired()` mobs). It is **never loaded from a save**: rigs spawn theirs fresh on
every build and adopt, so `EntityJoinLevelEvent(loadedFromDisk)` refuses one. The pool is the
peaceful land walkers without the iron golem (zombies hunt golems). Tags: `mfamrigpx` +
`mfampxof_<carrier>` — deliberately not `mfamrig`.

**How many, decided per creature at wish time** (`vanilla/HitProxyPlan`, Minecraft-free). For
k = 0…6 and three cores (the §13 F2 carrier, then F0.5 and F0.25), the drawn boxes are cut into
cells, the cells the core leaves out are clustered into k parts, and each part gets the pool mob
and scale that fits it (F1 and F2 both tried). Each candidate is scored by parallel rays (8
bearings × level/30° down × 16×16, body turned 0°, 22.5° and 45° and averaged, because a vanilla
box never rotates with the body), clicks on exact boxes, arrows on boxes +0.3. The fewest entities
that meet the bounds win; otherwise the least shortfall. Cached per shape.

**The bounds are vanilla's own**, measured by the same instrument on vanilla's model shapes (body
yaw averaged): click hit 0.81–0.99, click on air 0.13–0.57, arrow hit 1.00, arrow on air 0.56–0.82
(zombie, pig, cow, spider, chicken). Per creature: click hit ≥ 0.95, click air ≤ 0.50, arrow hit
≥ 0.98, arrow air ≤ 0.82.

**Actuals, in the world** (`HitProxyCensusGameTest`, every committed creature spawned by
`DisplayRig.spawn`, turned by `RigDriver.follow`, the creature read back off its display entities'
saved transforms and the targets off the live entities' `getBoundingBox()` — nothing from the
plan):

| 63 creatures | click hit | click on air | arrow hit | arrow on air |
|---|---|---|---|---|
| median | **0.98** | **0.39** | **1.00** | **0.65** |
| worst | 0.90 | 0.58 | 0.97 | 0.82 |
| target (median) | ≥ 0.95 | ≤ 0.50 | ≥ 0.99 | ≤ 0.80 |
| before: §13 single carrier ( options table, body at 0°, 52 real) | 1.00 | 0.58 | 1.00 | 0.72 |

Proxies per creature {0: 31, 1: 21, 2: 4, 3: 2, 5: 1, 6: 4}, mean **1.02**. Half the corpus needs
none. The ones needing 5–6 are the wide-winged dragons, a snake and a Tralalero; seven creatures
end just outside a per-creature bound, every one within vanilla's own worst (spider 0.57 air, chicken
0.82 arrow air). Tables: the recorded evidence (world) and `census-planner.md`
(the planner's own prediction, which agrees).

**Verified as behaviour, not as geometry** (`HitProxyGameTest`): a real arrow through the whale's
head hurts the whale by an ARROW (`DamageTypes.ARROW`), while the control whale with its proxies
removed, shot the same way, is not hit by an arrow, so the head is outside the carrier's box. A
mock player's punch on a proxy hurts the whale, attributed to the player; a name tag on a proxy names
the whale; the whale's own arrow fired from inside its head leaves unharmed; after a second of
driving nothing drifted and every proxy is inert and at its part; killing the whale takes its
proxies; a cube gets none. **The real client** (`client-test.sh rigfit`, the client's own
`gameRenderer.pick`): the whale's fluke, body and head all land (before an earlier change only the middle
did); Spikor, given no proxies, lands middle and nose and misses its thin tail tip. That tip is
reported, not asserted, because a creature inside vanilla's bounds may miss a tip the way a cow's
legs do.

Cost: the atom rig's cost notes

## 15. 🐉 A CREATURE HE RIDES: the seat, the click, the flight (2026-10-11)

A rider sits on his mount's vanilla **passenger point**, which every client computes from the
mount's TYPE and SCALE. For a rig that mount is the invisible carrier, so the seat was a fact about
the carrier (anything read off the carrier is a fact about the carrier, §11) and nothing about the
creature: `RideFitProbeGameTest` over the 8 starters and 3 committed sculpts measured riders from
**0.62 blocks inside a body to 0.85 above a back**.

**The fix has two halves, because vanilla can RAISE a seat and never lower it.**
- The rig records the creature's back (`RigFit.backTop`: the top of the drawn creature in a small
  column over the carrier's middle) on the carrier as `mfam_seat`. For a creature with the
  `rideable` power `HitProxyPlan` fits the carrier to the creature BELOW that back and keeps no core
  taller than it (shrinking the best one when none fits, which is the bat a flier rides); the
  proxies cover whatever stands above (a raised head, a neck, wings).
- Climbing on (`RideSeat.mount`) puts a seat on the carrier, an invisible armadillo with no AI,
  scaled so its own passenger point is exactly the missing lift, and him on it. **A mob scaled by
  `minecraft:scale`, never an `interaction` sized by its height**: an interaction was the first cut
  and a 1.21.1 server or client re-reads only its hitbox, never its size, so it seated him where the
  carrier did. A living entity's size is recomputed on every client when its scale changes.

Result: all 12 within **0.02 blocks**, both targets; the real client (`TESTMODE=ride`) seats him at
1.20 on a 1.20 back for both the walking and the flying dragon.

**Climbing on from anywhere** is an earlier change's proxies: a right-click on one is made on the carrier, so
every proxy of every creature climbs him on (n/n), and the click coverage stays 0.97–1.00. Gate C
aims at the head (a proxy, not the carrier) and presses USE.

**Three things found on the way, each now pinned:**
- riding, a swing past its head landed on its own proxies and **hurt his own mount**: a hit or
  click on the proxies of what he rides is now ignored;
- a stock client cannot see the proxy tags, so climbing on with **Moddy's watch** in hand also
  sent a use-item and opened the wish menu (photographed): the use-item in the two ticks after
  climbing on is dropped;
- **logging off mid-ride** let vanilla save the carrier into the player file and remove it from the
  world, leaving its displays behind: he gets off first (`PlayerLoggedOutEvent` fires before that
  save), proven red without it.

**Flying.** A bat is neither a `FlyingMob` nor on a flying navigation, so a ridden one steered like
a walker. While he rides a flier its gravity is off (`PetBehaviours.steer` then moves it in 3-D
from his look) and its own flutter is skipped (`BatRideMixin`; pausing its whole AI is NOT the
lever, because a mob with no AI does not travel at all). Measured: up 45° climbs ~6 blocks in 2 s,
down dives, letting go hovers with 0.00 drift (0.63 blocks of sinking before the mixin). Getting
off higher than 3 blocks gives slow falling: his own fall is simulated by his client, so the real
client is what proves it, and it landed him with 20 of 20 health from 14 blocks up.

**A carrier bat never roosts.** A bat is BORN resting (`Bat.<init>` → `setResting(true)` on
the server), and `Bat.tick` pins a resting bat with `setPosRaw(x, floor(y) + 1 − bbHeight, z)` —
its top against the block above, as it hangs from a ceiling, with no collision. Only the bat's own
AI step wakes it, and that is the step `BatRideMixin` skips for a carrier its archetype drives (a
`mount` or `vehicle`), or that is held still or ridden. A one-block bat pinned that way barely
moves; a carrier scaled to a creature is taller than a block, so a big flier stood ON the ground by
`wishmob spawn` was pulled into it — measured in the starter studio, the six B4 fliers' carriers at
2 (plane, saucer, helicopter), 3 (rocket) and 6 (balloon) blocks under the grass, eye light 0/0,
every display's brightness override 0/0 (`RigLight` copies the eye's light, correctly), beside a
walker at sky 15: drawn black and sunk. `BatRideMixin` now wakes a rig's carrier before the pin and
refuses to let it rest again; `FlierCarrierLightGameTest` is red without it (feet 1.00 inside the
floor, sky 0 against the walker's 15). an earlier change's `RigTransformation` wake was the same trap met on one
path; this closes it for every path.

## 16. 🧭 HOW FAST A RIG'S BODY TURNS ()

A tester filmed two wished dragons on a phone: one hovering whose body pointed a new way several
times a second, one spinning under him. Both were the rig drawing the CARRIER's body yaw raw, each
tick. A carrier's yaw is not a drawing instruction:

- **A flier rides a bat**, and a bat's own AI rewrites its yaw from its velocity every tick. Hovering,
  that velocity is flapping. Measured (`RigPowersGameTest.aHoveringFliersBodyTurnsAtACreaturesPace`,
  `rigturn=off`): 8,700-11,100 degrees turned in 300 ticks, 180 in one.
- **A player nearly straight overhead**: the direction to him swings right round as he drifts,
  the head chases it, and standing still the body follows the head.

So the drawn yaw is the rig's own state (`RigTurn`, pure), stepped each tick towards what the carrier
wants: a threshold before it starts (15 degrees standing, 4 travelling), then an eased turn of at most
10 degrees a tick (24 travelling), which finishes. A flier's wanted yaw is the way it last travelled 2
blocks (`RigCourse`), never the bat's own. A look within 25 degrees of vertical turns neither the body
nor the head's yaw; the head only pitches. An aimed move, a rider's steering and a teleport still turn
at once. `RigDriver.follow` leaves the carrier its own yaw, so no AI is steered by the damping.

Three things measured on the way, each of which a first version got wrong:

- **A look goal re-aims every other tick**, and between them `LookControl.isLookingAtTarget()` is
  false: a hold keyed on it let go on alternate ticks. The steep look is remembered for 6 ticks.
- **A flown bat is not always flown.** A flight lasts 40 ticks unless asked again, and then the bat
  wanders on its own. A heading taken from the flight's target was steady only while one was active;
  the travelled course works for both.
- **One rate for everything made a walker walk backwards.** At 10 degrees a tick the stock-walk turtle
  spent a second of each leg coming round. A travelling creature has somewhere to go; it gets 24.

The seam is `rigturn=off`. Films, before and after, with the turtle as control:
a stock-client walk film of `stagedpet=pet_dragon_flier,stagedpetview=hover[,rigturn=off]`
(and `pet_dragon_spider` / `overhead`, and `SPECIES=turtle`). `STAGEDPET` now logs the DRAWN heading.

