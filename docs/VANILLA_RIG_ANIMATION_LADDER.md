# The animation ladder — everything the vanilla rig still owes, cheapest first

**Read this before writing the next animation.** It is the inventory (what a child can see that the
vanilla display rig does not yet do), the order to close it in, and — growing as each rung lands —
the playbook for doing the *next* one without rediscovering the last one's traps.

Companions: **`docs/VANILLA_RIG_PARITY.md`** §5 is the measured catalogue of what vanilla draws and
where; §9 is what the static-parity hunt taught; §11–12 are the walk cycle. This file is the
forward-looking half — that one records what *is*, this one records what is *left*. And
**the vanilla-creature path notes** is what to read if you are about to *use* this path rather than
extend it: the ten components, how to drive one, and what still cannot be done.

**Status: tiers 1–5 are done, and the ladder is now short.** Rows 4.2, 5.5 and 5.6 — swim
pitch, the jump and the sit/sleep poses — landed as generated layers (–, §5.7). All five mechanisms are built, every
clip a creature can play is playing, and one live driver (`VanillaRigAnimator`) owns idle, walk,
run, attack, head-look and the death flop on a real wished creature. What is left is tier 6 —
accepted regressions we have decided not to chase — plus two rows that are *features* rather than
parity gaps (a one-shot jump clip, pose flags), and the phase-alignment measurement in §2.4b that no
instrument here can take.

---

## 0. There are only five mechanisms. Naming the mechanism IS the estimate.

Every remaining row is one of these, and the cost of a row is almost entirely which one it needs.
A row whose mechanism already exists is an afternoon; a row that needs a new mechanism is a spike.

| | mechanism | what it costs | built? |
|---|---|---|---|
| **M0** | **already free** — vanilla draws it in WORLD space at the carrier's position | nothing | ✔ by construction |
| **M1** | **clip pose** — sample a Bedrock clip, compose per bone, write each display's `transformation` | ~1 entity-data write per *moved* bone per write cycle (3 ticks) | ✔ `RigPose` + `RigAnimator`, proven on walk |
| **M2** | **whole-body modifier** — one extra rotation/translation applied to EVERY display about a shared pivot, on top of whatever M1 wrote | one write per display, but *every* display, so ~6× a bone write | ✔ **built** — `RigTransform.about`, four lines. `T' = q·T`, `L' = q·L`, scale and the right rotation untouched. Proven on the death flop |
| **M3** | **display property** — a synched field on the display entity itself: `glow_color_override`, `shadow_radius`, `shadow_strength`, `brightness_override`, `view_range`, `billboard` | one field write, no model change | ✔ **built** — tier 2: shadow, glow + colour, emissive brightness, culling range |
| **M4** | **what it is wearing** — swap `custom_model_data` (a different model) or `dyed_color` (a multiply tint) | one item rewrite per display, 53 bytes | ✔ **wired** — tier 3: `RigTint` drives it off the carrier's own `hurtTime`/`deathTime`. An unchanged tint costs nothing |

**The rule that predicts M0 for free**, from §5 and re-verified here against 1.21.1 bytecode:
*anything vanilla draws in world space at the entity's position still works; anything it draws ON
the entity's MODEL is gone, because the model belongs to something invisible.*

Two lines of `EntityRenderDispatcher.render` decide most of this list, and they are worth quoting
because they disagree with each other in exactly the way that makes the inventory non-obvious:

```java
if (entity.displayFireAnimation()) { this.renderFlame(...); }        // ← NO invisibility guard
if (... && this.shouldRenderShadow && !entity.isInvisible()) { ... }  // ← explicit guard
```

So **burning is free and the shadow is not**, from adjacent lines of the same method. Do not reason
about a row from the rule; check the call site. (`DisplayRenderer` then takes `shadowRadius` off the
display's own `shadow_radius`, which is why the shadow is recoverable at all.)

---

## 1. THE LADDER — in the order to do it

Ordered easiest-first on purpose: **find the gotchas on the cheap rungs, where a wrong turn costs an
afternoon rather than a week.** Each rung's "proves" column is what the *next* rungs get for free if
it lands, which is the real reason for the order — this is not just risk-aversion, the early rungs
build the machinery the late ones need.

### Tier 1 — ✅ **DONE** (`GateC:vanillastate`). Two of three predictions held; one was wrong.

One Gate C run, both arms side by side, each state measured as a pixel DIFFERENCE against the same
creature seconds earlier. Result:

| row | predicted | measured |
|---|---|---|
| burning | free | ✅ **free** — arm B 27,238 px changed, 20,660 fire-coloured |
| potion swirls | free, 3.75× rarer | ✅ **free** — present on both arms in every run, always thinner on the rig. The RATIO is not measurable with this instrument (see below) |
| name tag | free | 🔴 **WRONG — vanilla's tag is absent** (arm A 240 px above the head, arm B 0). **Now BUILT** as a `text_display`: plate boxes A=(194,149)-(230,158) B=(623,149)-(658,159) — same top row, same size to a pixel |

**The name tag is the finding.** `EntityRenderer.render` calls `renderNameTag` with no invisibility
guard — which is the method I read — but `LivingEntityRenderer` **overrides** `shouldShowName` and
ends `&& !entity.isInvisibleTo(localPlayer)`. Reading one level of a class hierarchy is reading the
wrong method as surely as reading the wrong file. The gate now asserts the tag is **still** absent,
so the day a `text_display` fixes it is a loud failure rather than a quiet pass.

### 🔥 "Is that really vanilla's fire?" — the question a photograph could not answer, and the control that did

A burning creature on this rig is **completely engulfed**: a pillar of flame with no creature
visible in it. Both arms did it identically, which proves the two PATHS agree and says nothing
about whether what they agree on is what Minecraft does. Dad asked exactly that.

**The answer is yes, and it took a vanilla mob in the same frame to say so.** A cow, on fire, one
camera, one tick:

| | body | flame | vs its own height | vs its own width |
|---|---|---|---|---|
| vanilla cow | 16 × 35 px | 26 × 53 px | **1.51×** | **1.63×** |
| arm A — mod renderer | 71 × 96 px | 111 × 155 px | **1.61×** | **1.56×** |
| arm B — vanilla rig | 66 × 95 px | 122 × 171 px | **1.80×** | **1.85×** |

**Same proportions, five times the scale.** `renderFlame` sizes the flame at `bbWidth × 1.4` and
stacks quads 0.45 apart until `bbHeight` is covered, so the flame is always about 1.5–1.8× the
mob — and this creature's hitbox is **3.60 × 4.50 blocks** against a cow's 0.90 × 1.40. 1.6× of
4.5 blocks is a seven-block pillar of fire. It looks alarming because the creature is large, not
because anything is wrong.

⚠ **arm B's flame is consistently ~15% bigger than arm A's**, because the rig's carrier hitbox is
not exactly the wish mob's. That is the hitbox residual scoreboard row B4 already tracks, showing
up somewhere new — a hitbox difference is invisible until something is SIZED off it.

**The reusable rule: two paths agreeing is not the same as either being right.** Every measurement
in tier 1 was arm-A-vs-arm-B, and every one of them would have read identically if vanilla's fire
were twice the size it is. A third party that is unarguably correct costs one entity.

⚠ **The swirl RATIO is not measurable with this instrument, and I quoted it three times before
admitting that.** Six runs: 0.17, 0.27, 0.31, 0.36, 0.49, 0.49, against a predicted 0.267. The
first run landed on the prediction and I wrote it up as confirmation; it was the run that agreed.
What is genuinely established is that swirls REACH the rig and are always thinner there. The count
is a few hundred pixels of scattered particles against a moving mask, and it does not support a
second significant figure. **A number that moves by 3× between runs is not a measurement, whatever
its mean.**

**Four instrument faults on the way**, all in §2.8 below — every one produced a confident number.

| # | row | mechanism | status |
|---|---|---|---|
| 1.1 | **burning** (the flame overlay) | M0 | predicted free from bytecode, **never seen** |
| 1.2 | **potion swirl particles** | M0 | predicted free but **3.75× rarer** (`isInvisible() ? 15 : 4`) — so it is *not* parity, it is a measurable regression, and the number should be photographed |
| 1.3 | ~~**name tag**~~ → **a `text_display` above the head** | M3-ish (a new display) | 🔴 **NOT free after all.** `LivingEntityRenderer` OVERRIDES `shouldShowName` and ends `&& !isInvisibleTo(player)`; the unguarded `renderNameTag` I first read is the base class, which does not run. See the correction in parity §5 |
| 1.4 | **sounds, ability particles, projectiles** | M0 | ✔ already measured — emission point 0.000000 blocks from the mod path |

> ⚠ **"Predicted free" is not free.** Every one of these is a claim about a code path nobody has run
> on this rig. §9.6 of the parity doc is a list of instruments that lied in the reassuring
> direction; a prediction from bytecode is an instrument too.

### Tier 2 — ✅ **DONE** (`GateC:vanillastate`). All four rows land; two are better than the mod path.

One Gate C run, the same difference-based instrument, plus the vanilla cow as the third party.

| row | predicted | measured |
|---|---|---|
| 2.1 shadow | one property on one display | ✅ **works, and is VISIBLY BETTER than arm A** — rig 9,928 px of floor darkened, vanilla cow 820, mod path **672** (its own flicker floor). Three wrong radii were measured before the right one |
| 2.2 glow outline | flag + colour override | ✅ **works** — arm A 2,749 px outlined, cow 720, rig 1,994; and in the creature's **own colour**, which the mod path cannot do (977 red-dominant px against 100) |
| 2.3 view range | already written | ✅ **was a LITERAL, now derived** — `view_range` is the carrier's own `getBoundingBox().getSize()`, which makes `Display.shouldRenderAtSqrDistance` and `Entity`'s identical |
| 2.4 emissive parts | `brightness_override` on some bones | ✅ **works** — at midnight, 1 group of 41 forced to 15/15 gained **13.5 luma** while the untouched arm moved 0.1. And the row it needed first: **the rig darkens at night exactly like the mod path** (113.9 vs 109.7 luma lost), the first time `matchLight` has been exercised in the dark |

### 🕶 The shadow row is the best worked example in this document, because THREE plausible answers were wrong before the fourth

Every one of them produced a shadow. None of the first three was right, and the failure mode was
different each time.

**Wrong answer 0 — the whole side-by-side pose.** The first run measured **zero shadow pixels for
all three subjects, on a stage with 561 blocks of white concrete laid under it.** The cow is what
made that readable in one line: a vanilla cow casting no shadow is not a rig defect. The cause is a
law nobody had written down —

```java
strength = (1 - distanceToSqr / 256.0) * shadowStrength;   if (strength <= 0) { /* no shadow */ }
```

— **nothing in Minecraft casts a shadow more than 16 blocks from the camera**, and two 4.5-block
creatures 20 blocks apart cannot be framed any closer. The side-by-side camera and the shadow are
mutually exclusive, so the row is three single-subject passes at 7.5 blocks. No amount of tuning
the floor, the radius or the light would have helped, and each of those was a plausible next move.

**Wrong answer 1 — the renderer's number, 0.5.** A mob's shadow is not its renderer's field. The
chain is `MobRenderer.getShadowRadius = EntityRenderer.shadowRadius × LivingEntity.getScale() ×
Mob.getAgeScale()`, so the field is a BASE and the entity's scale is applied on top of it every
frame. `DisplayRenderer` copies `shadow_radius` out of the render state and multiplies by nothing.
Measured: 729 px against the mod path's 5,650.

**Wrong answer 2 — 0.5 × the CARRIER's scale.** This reproduces the formula with the wrong input,
and it is the one that would have shipped, because after wrong answer 1 "bigger" reads as "fixed at
last". The carrier is an arbitrary vanilla mob stretched until its box matches, so its scale says
how far a *horse* had to be stretched: **2.813**, against the creature's own **1.200**. ⚠ **Anything
read off the carrier is a fact about the carrier.**

**The answer — half the creature's own width**, calibrated on vanilla's own authored radii:

| mob | radius | width | ratio |
|---|---|---|---|
| creeper, zombie | 0.5 | 0.6 | 0.83 |
| cow, pig | 0.7 | 0.9 | 0.78 |
| spider | 0.8 | 1.4 | 0.57 |
| **iron golem** | **0.7** | **1.4** | **0.50** |

The ratio falls as the mob grows, and a sculpted creature is larger than all of them — so the golem
is the calibration point, not the cow. `bbWidth / 2` reproduces it exactly. The measured disc then
comes out 161×108 px against the cow's 56×37 — **2.9×, against a radius ratio of 2.8.**

**And the finding that fell out of it: the MOD path's shadow is invisible.** Arm A gets
`0.5 × 1.2 = 0.6`, a disc 1.2 blocks across under a creature 3.6 blocks wide, entirely hidden
beneath its own body — because `WishMobRenderer` sets `shadowRadius` from its per-body table only
on the *vanilla-body* branch, and every sculpted creature takes the custom-geometry branch and
returns before that line. So the rig **deliberately does not match arm A here**, which is the first
row in this whole spike where parity would have meant reproducing an invisible feature. The diff
image is the proof: a clean ellipse under the rig, bare floor under the mod creature, and the cow's
shadow plainly visible between them.

### 🔴 The tier-2 instrument faults — two more, and both inflated the number in the direction of "it works"

Adding to the four in §2.8:

**5. A neighbour's state toggles too.** The shadow A/B is one client option, `entityShadows`, which
turns off *every* shadow in the frame — including the subject ten blocks away at the edge of it.
Arm A scored 1,327 px, of which **574 sat in a single 40-pixel window at x=806**: the cow. The
number looked entirely reasonable; the column histogram is what showed it, and the diff image is
what proved it. Fixed by counting only the middle 40% of the frame. **When your A/B is a GLOBAL
switch, everything the switch affects is in your measurement.**

**6. One arm's renderer is not static frame to frame and the other's is.** The floor row —
plain-against-plain, the same creature seconds apart — reads **~900 px on arm A and 0 on arm B**,
every run. So any whole-frame count adds ~900 px to arm A and nothing to arm B, which is a
systematic bias in favour of exactly the arm being compared against. It is also why the first
"shadow disc" bounding box came back 498×213 px for arm A: it was the creature's own flicker, not a
disc. **Quote your own floor per arm, and never assume it is symmetric.**

### Tier 3 — ✅ **the tint channel (M4)** (`GateC:vanilladeath`). One row was parity; three were inventions.

| # | row | predicted | measured |
|---|---|---|---|
| 3.1 | **damage flash** | "an emulation" | ✅ **works, and the emulation gap is a NUMBER** — `0xFFB2B2`, derived below. On a real death the rig's body red fraction goes **0.394 → 0.509 (+29.2%)**; a vanilla cow in the same frame 0.371 → 0.607 |
| 3.1b | …and the MOD path's | — | 🔴 **there isn't one.** 0.379 → 0.380. `renderCustom` passes `OverlayTexture.NO_OVERLAY`, so a sculpted creature has never flashed red |
| 3.1c | an untinted tintable rig | assumed fine | 🔴 **would have shipped BROWN** — see below. Closed before it was ever photographed |
| 3.2 · 3.3 | poison / wither / frost tint | "free once 3.1 is wired" | ⚠ **REFUTED AS PARITY.** Vanilla tints a poisoned, withered or freezing mob's model *not at all*. They stay as capabilities, labelled inventions |
| 3.4 | invisibility as a wish | unresolved | still unresolved — not attempted |

### 🎨 The hurt flash is a DERIVED colour, and the derivation is the whole row

The tempting move is to pick a red that "looks about right". Two files settle it instead:

```java
// OverlayTexture's constructor, for every pixel of the red half (v < 8):
setPixelRGBA(x, y, -1308622593);          // = 0xB20000FF as ABGR -> pure RED at alpha 178/255
// rendertype_entity_cutout_no_cull.fsh:
color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);   // overlay*(1-a) + color*a
```

So vanilla's flash is `r' = 0.302 + 0.698·r`, `g' = 0.698·g`, `b' = 0.698·b` — and our channel is a
multiply on `vertexColor`, one line *earlier* in the same shader. The multiply that best emulates it
is therefore `(1.0, 0.698, 0.698)` = **`0xFFB2B2`**:

- **green and blue come out EXACTLY right** — a multiply is precisely what vanilla does to them;
- **red falls short by `0.302 × (1 − r)`** — zero on an already-red creature, the full 30% on a
  white one. That is the entire emulation gap, and `RigTint.redShortfall` returns it.

"It is an emulation" was the honest thing to say before anyone did the arithmetic. Afterwards, the
honest thing is the number.

### 🔴 The untinted rig would have been BROWN, and the bug is the ABSENCE of a component

`ItemColors.createDefault` registers the dyeable handler as
`tintIndex > 0 ? -1 : DyedItemColor.getOrDefault(stack, -6265536)`. That fallback is **`0xA06540`,
undyed leather**. The tintable pack declares `tintindex: 0` on every face and uses a dyeable
carrier — so a display with **no `dyed_color` component at all** is not untinted, it is multiplied
by brown.

Nothing about that looks wrong anywhere: the pack is right, the model is right, the write path is
right, and the creature is simply muddy. It would have arrived as "the tint channel makes creatures
look dirty" long after the channel was closed. `wear` now writes `RigTint.NONE` unconditionally
under `TINTABLE`, and Gate C prints the three subjects' mean colours so a regression is visible in
the report rather than in someone's memory of what the creature used to look like.

### ⚠ Two of tier 3's rows are INVENTIONS, and calling them parity would have been the same mistake as scoring an instrument against itself

`LivingEntityRenderer.getOverlayCoords` is, in its entirety, `u = getWhiteOverlayProgress` and
`v = hurtTime > 0 || deathTime > 0`. **Vanilla does not tint a poisoned, withered or freezing mob's
model.** Poison and wither are swirl particles — which already reach the rig for free (tier 1) —
and freezing is a shake in `setupRotations` plus a first-person vignette.

They stay in `RigTint` because *"make him green"* is a wish a child will type and this is the
channel that would grant it. But they are labelled inventions in the source, nothing switches them
on by itself, and they earn no parity row. **A ladder that quietly reclassifies "something we could
do" as "something vanilla does" stops being a measurement of parity.**

### Tier 4 — ✅ **M2 is BUILT** (`GateA:RigTransformFlopTest`, `GateC:vanilladeath`). The death flop, from a real kill.

| # | row | mechanism | status |
|---|---|---|---|
| 4.1 | **death flop** | M2 | ✅ **done and filmed** — 0.68 → 1.57 wide-over-tall against a vanilla cow's 0.43 → 2.33, driven only by the carrier's own `deathTime` after real lethal damage |
| 4.1b | …and the MOD path's | — | 🔴 **there isn't one.** 0.73 → 0.73. `renderCustom` returns before `super.render`, so `setupRotations` never runs |
| 4.2 | **swim pitch** | M2 | ✅ **done** — the travel pitch, read off the carrier's velocity, is the whole-body ROOT inside `RigPose.compose`: M2 in the one pose writer, so the look stays exact on a root-parented head. `GateC:vanillalocomotion` (§5.7) |
| 4.3 | **spin attack / whole-body wishes** | M2 | the mechanism is now there; each is a curve and an axis |

**M2 turned out to be four lines**, and the reason it looked bigger is worth keeping. A display
renders model point `m` at `world = T + L·(S ⊙ m)`, so turning the whole creature by `q` about the
display origin is two substitutions — `T' = q·T`, `L' = q·L` — with scale and the right rotation
untouched, which is exactly what makes it stack on a clip pose instead of replacing it. **The pivot
is free**: every display of a rig sits at the carrier's position and carries its offset inside `T`,
so "about the feet" needs no pivot arithmetic at all.

The curve is copied rather than eased (`RigFlop`), and two details of vanilla's are visible if you
get them wrong: the `sqrt` makes the fall **fast then slow** (a body giving way, not a door
closing), and the `× 1.6` puts it **flat at deathTime 14 of 20**, so the body lies still for the
last third instead of still turning as it vanishes.

### 🔴 The instrument fault that PASSED: a death poof is exactly the shape of a fallen body

The first death run reported **the mod-path creature falling over** — aspect 0.72 → 1.54 — for a
renderer that provably has no flop in it. The run was **green**, because the two subjects that were
supposed to flop did.

Opening the frame settled it in one look: the creature was *gone*, and the box had found its
**death poof**, the wide short scatter of particles vanilla emits at removal
(`broadcastEntityEvent(60)`). "The silhouette got wide" is satisfied by a creature that merely
vanished — which is not a hypothetical, it is what happens at the end of every death this test
measures.

That is §2.2 again — **a control that is true of the bug is not a control** — and it was only
visible on the arm that was expected to do *nothing*. **An arm you expect to score zero is the most
sensitive instrument you have; when it scores, believe it and go and look.** Two guards now:
particles are quieted, and the oracle only looks during the fall (`FALL_WINDOW`, 18 ticks — past
flat at 14, before removal at 20).

### Tier 5 — ✅ **DONE for the four rows that are parity** (`GateC:vanillaclips`). One row was not a clip at all, and the one number everybody expects is structurally zero.

The walk was done; what was left was never new machinery, it was a new answer to *what drives the
clock* — and that is exactly where two of the four went wrong.

| # | row | driven by | status |
|---|---|---|---|
| 5.1 | **idle** | `tickCount` — wall time | ✅ `clip=IDLE`, **265 px** changed over 50 ticks with the creature standing perfectly still |
| 5.2 | **attack** | ~~`getAttackAnim(1.0f)`~~ → **our own swing clock** | ✅ `clip=ATTACK`, **4,879 px** across the swing window — but see below: vanilla's own number is 0 and always will be |
| 5.3 | **head look** | `yHeadRot`/`xRot`, not a clip at all | ✅ **865 px** for a 55° turn, `headMoved=true` — and it needed an explicit invalidation to compose (below) |
| 5.4 | **run** | the walk clip, faster | ✅ free, as predicted: `speed=0.240 position=22.26` walking against `speed=1.200 position=134.01` running, same clip |
| 5.5 | **jump / land** | the jump itself: `0.5 − vy/2vy₀` | ✅ **done as a feature** — a one-shot JUMP from the carrier's own take-off, a landing crouch that eases out, and the `hops` power, which replaces the walk with gather → leap → land. Filmed beside a rabbit and a frog (§5.7) |
| 5.6 | **sit / sleep / sit-on-shoulder** | the stay flag, idle time, the clock | ✅ **sit, lie and sleep done** — told to stay it sits; a long sit lies down; a `naps` creature sleeps at night on its side, eyes shut. Filmed beside a sitting wolf and a sleeping fox (§5.7). Sit-on-shoulder is not done |

**One driver owns all of it** — `VanillaRigAnimator`, on the server tick, delegating the *selection*
to the `RigAnimator.tick(…, Motion)` that already existed. That delegation is the point: writing a
second selection rule beside `AnimationState.select` would have been two rules to keep in step, and
the first divergence would have been invisible (both would still animate, just differently). The
`Motion` overload exists precisely so a caller can override **one** input — here `attackProgress` —
while the rule itself stays in one place.

### 🔴 5.2 — `getAttackAnim` is structurally **0.00** for a rig carrier, and it is 0.00 for the MOD path too

The film prints this, deliberately, as a passing line:

```
5.2 vanilla getAttackAnim: peak 0.00  (swinging seen: true)  -- 0 is CORRECT
```

`swinging` latches true. `attackAnim` never moves. Two hypotheses were wrong before the bytecode
settled it — a cross-thread read of a non-volatile field (moved the sampling onto the server thread:
still 0.00), then a missed `swing()` call (the latch proves it fired). What is actually true is that
**`updateSwingTime()` is called from exactly three places in 1.21.1** — `Monster.aiStep`,
`Player.serverAiStep`, and `RemotePlayer.tick`. Nothing else advances the clock.

So the attack progress is dead for **any** `PathfinderMob` carrier, and a hostile carrier is not
available either: `Monster.shouldDespawnInPeaceful()` is true, so a rig on Peaceful would evaporate.
**And it is equally dead on the mod path**, whose `WishMob` is likewise not a `Monster` — which makes
this the fourth row where the vanilla control is the only control and matching arm A would mean
reproducing an absence (§ the shadow, the hurt flash, the death flop).

The rig therefore keeps **its own** swing clock: `SWING_TICKS = 6`, started on the `swinging` latch
and cleared with it. That is not an emulation gap to hedge — it is the only way an attack clip plays
at all, and it is strictly more than the path it replaces.

### 🔴 5.3 — two pose sources, and the second one silently wins forever

The head is the first row where two writers touch the same display, and the failure is quiet: head
look measured **851 px with no clip running and 71 px with one**. Not zero — 71 px is the tween
finishing — so it reads as "mostly working".

The cause is a *cache* meeting an *overwrite*. `DisplayRig.lookAt` skips a write when the requested
angle matches the last one it sent, which is right and cheap. But a clip pose rewrites the head
display's transform wholesale, so the display no longer holds what `lookAt` believes it holds, and
`lookAt` declines to correct it. The head freezes in whatever the clip last said.

The fix is one method and it is deliberately explicit rather than clever:

```java
int posed = RigAnimator.tick(rig, …);
if (posed > 0) { rig.forgetHead(); }   // the clip just overwrote the head display
```

**A cache invalidation is part of the WRITE, not of the reader.** Making `lookAt` compare against
the real display state instead would be a per-tick NBT read of every head display to save one write;
having the clip announce that it clobbered something costs nothing. `GateB:aClipWriteDoesNotSwallowTheHeadTurn`
holds it — writes, no-ops at the same angle, then **writes again after `forgetHead()`** — so the
no-op optimisation cannot be deleted "to be safe" and cannot come back.

⚠ **Superseded on the shipping path by §5.4.** The fix above was right about the cache and
wrong about the design: it kept TWO writers and taught the second one to notice the first. §5.4
removes the second writer.

### 🔴 5.4 — a WALKING creature came apart: one motion smoothing per rig, one pose writer per tick

A tester filmed the starter pet dragon in play (a newer stock client):
while it walked, its head — eyes, horns — slid against its body and wings. No gate had ever filmed a
creature walking **and** looking: `vanillawalk` drives the rig by hand and never turns the head, and
`vanillaclips`' head take turns the head of a creature standing still.

**The oracle, and why it is not a magnitude.** `GateC:vanillawalklook` circles the dragon through
the SHIPPING driver (`VanillaRigAnimator`, fed only by the carrier) with its head on the camera, and
reads, per rendered frame at the frame's own partial tick, what the renderer reads: every display's
interpolated position and yaw, and its transform-tween progress. A rig is rigid exactly when those
are the same for every display, so the numbers are **spreads** — zero for any rigid rig, non-zero
for a trailing head. "The head turned" and "it travelled" are separate controls, not the oracle.

| | head-vs-body position spread | yaw spread | tween-progress spread |
|---|---|---|---|
| `main` (3 runs) | **0.269 / 0.307 / 0.269 blocks** | up to 169° | **0.667** |
| fix 1 only (1 run) | 0 | 0 | 0.667 |
| fix 1 + fix 2 (2 runs) | **0** | **0** | **0** |

Two causes, each attributed by that middle row:

1. **Each display eased into a MOVE by its own write history.** `RigDriver.follow` moves every
   display to the carrier's feet every tick; the client eases each over its `teleport_duration`, and
   the only writer of that was `DisplayRig.apply` — the POSE write — which set it to the transform
   tween: 0 at spawn, 3 once posed. The dragon's back legs are never keyed (0), everything else is (3),
   so posed parts trailed by ~2 ticks of travel. `teleport_duration` is now `DisplayRig.MOTION_TICKS`,
   written by the rig itself (the constructor both `spawn` and `adopt` pass through — an adopted rig
   an older jar saved is a MIX) and by nothing that poses. **The value is a measurement**
   (`rigmotionticks` on a scratch tree, same run each):

   | value | moving ticks drawn smooth / as a snap | drawn rig vs the client's carrier |
   |---|---|---|
   | 0 | 0 / 238 — a 20 Hz stutter at any frame rate | 0.177 blocks |
   | 1 | 239 / 0 | 0.178 blocks |
   | 2 | 239 / 0 | 0.122 blocks |
   | **3** | 239 / 0 | **0.008 blocks** |

   3 is what `ClientPacketListener` lerps a MOB with, so the drawn rig moves as a vanilla mob moves
   and stays over the carrier's hitbox — the thing a player's crosshair actually hits.
2. **Two pose writers, two tween clocks.** `lookAt` ran every tick after the clip: it composed with
   a NULL pose (the head subtree became rest + look, discarding the clip's head keys — the idle sway —
   whenever the look was non-zero, and keeping them when it was exactly 0), and it restarted its
   3-tick tween every tick while the body's restarted every third. Now the look rides the clip's own
   write (`RigAnimator.tick(…, look)` → `RigDriver.animate(…, head, …)`): one frame,
   `R(look)·R(clip)·R(rest)` down the chain, every display it touches starting its tween on the same
   tick. `lookAt` survives for its other callers and composes with the rig's last clip pose.

⚠ **A FIXED rig still showed small spreads on some runs, and the oracle had to learn to tell them
apart from the bug — by measuring their cause, not by loosening a threshold.** Three of the first
seven fixed runs reported a worst frame of 0.03–0.056 blocks, ~1° and a tween spread of 0.333 or
0.667 (one tick, or its echo into the next write), on 2–14% of walking frames, while every display
carried `teleport_duration` 3. It is vanilla's DELIVERY: one server tick's packets for ~40 displays
sometimes reach the client split across a client tick, so a few displays take that tick's move and
tween one tick late. The rig writes everything in one server tick and cannot prevent that; `main`
has it too, underneath its own 0.27.

Two wrong turns first, recorded so they are not re-taken: judging the WORST frame (the split fails
it) and judging the longest stretch against a flat 100 ms (a split's position error outlives that:
`lerpTo` closes 1/T of the gap per tick, so a one-tick offset decays by (1 − 1/T) per tick and at T=3
takes ~11 ticks to fall below 0.001 — measured 449 ms). What the oracle does now:

- **detects splits directly** — every display moves every tick while walking, so a client tick in
  which only SOME take a new move target is one server tick delivered across two;
- **excuses a frame only within a window DERIVED from that decay** (`3 + ln(speed/tol)/ln(T/(T−1))`
  ticks, T read off the rig itself, so `main` and the fix get the same rule);
- **never excuses on transform arrivals** — on `main` the head's per-tick writer produces consecutive
  arrivals by design, so using them would excuse the bug;
- fails on any UNEXPLAINED stretch out of tolerance ≥ 100 ms, and prints the worst frame, the bad
  frames and how many were near a split, so a transient is visible rather than hidden.

`main` has 0 move splits and ~100% of walking frames out of tolerance (583/585) for 11.9 s unbroken;
the sabotaged fix the same. Neither is excusable by construction.

⚠ ** (2026-09-27): the split detector knew one shape of four, and that made the test
intermittent on `main` — not the rig.** Measured first: 5 runs on `main` and 5 with an earlier change's glow
mirror removed failed **3/5 and 2/5**, so is not the cause. A ring of per-client-tick delivery
(`tick:moved/n,arrived,targets,step spread`, now printed beside the longest stretch) then showed every
failing stretch following a split the detector could not see:

- a split **turn while STANDING** — splits were only looked for while walking, and a standing
  creature still turns its body, so the yaw is part of the target now;
- **every display moved, to different targets** (`40/40` moved, 2 targets) — the late half of a split
  arriving with the next tick's move; and **every display moved to one target by different steps**
  (0.09 = one tick of travel), the late ones catching up two server ticks at once;
- **one pose write over two ticks** (1 then 35, 11 then 25, of one 36-display write). These ARE
  excused now, but only when the two ticks' display sets are **disjoint**. an earlier change's bug re-posed the
  SAME head displays every tick on top of the body's write, so its consecutive arrivals OVERLAP; they
  are counted as `tweenOverlaps` and never excuse a frame.

No window or tolerance moved. Fixed: **6/6 green**, worst unexplained stretch 41 ms. The sabotage
arm is still red: a 5,027 ms walk stretch, 0.27 blocks, and 19.8° of yaw while standing. The stock
check agrees: a stock-client walk film on native 1.21.4 measured legs-vs-body **6.97 px** on current
`main`, inside an earlier change's fixed arm (6.89–7.16) and below pre- `main` (8.12–8.49).

⚠ ** (2026-09-27), 1.21.1 only: the POSE WRITE restarted the posed displays' movement easing.**
The first rig-films run then found `vanillawalklook` red on 1.21.1 in the stand phase: ~5° of yaw spread
with zero delivery splits of any kind. A new instrument logs every display whose `Display`
position/rotation interpolation target was REPLACED while its target stayed the same (the object's
identity changes on every `lerpTo`). It found the 36 posed displays of a 40-display rig doing that in
bursts throughout the stand, while the 4 never-posed ones did not. So a body turning while it stood drew
two groups on two easing curves. The cause was `DisplayRig.apply`'s `saveWithoutId` → `load` round trip,
which re-read the whole display on every pose. It now sets the three pose fields directly
(`mixin/DisplayPoseInvoker`, the same private setters on both versions). Result on 1.21.1: 3/3 green with
the stand yaw spread **0.000°**, and only whole-rig (40/40) restarts left, which cannot pull displays
apart. Main without the fix failed 5/5. It also stops re-sending every display's item stack on every
pose.

⚠ ** (2026-11-21): a position-only move froze every turn.** The 1.21.8 client re-targets a display's yaw to its
CURRENT yaw on a move with no rotation, so with a move every tick a turn stopped a third of the way through its 3-tick
ease (Spikor's walk film: parts drawn a median 65.7° off the server's facing). Every rig move now carries its rotation
(`RigMoveRotation`, the atom rig's cost notes): 0.1°.

⚠ ** (2026-11-22): an ease no longer than its gap stops the part; a pose restart costs a frame; a look change jumps
a pose.** Moves now go every 3rd tick on one beat, and every channel eases over its gap plus a tick (move 4, pose 4, the
death flop on the beat, aimed ahead). Two client facts behind it, both in `Display.tick`: a new transform restarts the
ease from the LAST FRAME DRAWN (so a re-pose every tick loses a frame's motion every tick), and a light, item or shadow
change rebuilds the ease with the OLD start tick (so a part mid-ease leaps); the tracker re-arms a running ease for
that. the atom rig's cost notes; `tools/rig-drift.py` judges spin (a turning body drawn still, then moving).

**On a STOCK client — a tester's path.** A stock client cannot be read, so it
is FILMED: a stock-client walk film stages the same dragon from the server
(`lite/StockWalkStage`, the shipping `VanillaRigAnimator`, only the carrier moved) in four flat
marker colours, walking STOP-AND-GO across the view with its head on the child, and
A walk metric scores the film. Two instrument findings shaped it, both measured:
the real palette's head markers are a dozen pixels each (its control wobbled MORE than the bug), and
a steady lap turns a trailing display into a constant OFFSET a film cannot see — stopping and
starting turns it into a transient. The metric was calibrated on `GateC:vanillawalklook` with
`markers=on,motion=stopgo` first, where the render-state oracle already knows the answer (legs-vs-body
wobble main 6.54 / 6.46 px, fix 5.43 / 5.44 px). Then, two runs per cell, alternated:

| legs-vs-body wobble | stock 1.21.4 (native) | stock 1.21.1 via ViaProxy 3.4.13 | stock 26.2 via ViaProxy 3.4.13, JDK 25 |
|---|---|---|---|
| `main` | 8.49 · 8.12 px | 8.06 · 8.70 px | 8.10 · 9.13 px |
| | 6.89 · 7.16 px | 7.05 · 6.75 px | 6.50 · 6.98 px |

Every cell's two runs separate from the other arm's with no overlap (main 8.06–9.13
6.50–7.16) on all three clients, the same direction and size as the calibration — so the fields the fix
relies on reach a translated client intact. Evidence: the recorded evidence. ⚠ It is a small,
INDIRECT pixel signal (the head-vs-body number, ~16 px on both arms, is the head legitimately turning
±104° and discriminates nothing), and **26.3 is still not filmable here** (the forward-compatibility notes: no GLX
visual under Xvfb) — a tester's client version is inferred from 26.2 and 1.21.1, not measured.

**What changed for every OTHER animation, deliberately.** The single writer also writes back, once,
any bone the previous clip frame moved and this one does not (`Frame.posed`, kept on the rig as
`lastPosed`). Before, a clip that let go of a bone left it frozen where it was — the walk's legs
mid-stride once the creature stopped (traced in the code, not filmed) — and the head was only
reset by `lookAt`'s rewrite, by accident. The death flop, tint, scale and every clip are otherwise untouched: `apply` still writes the
same transform with the same tween; it just no longer touches the carrier-motion field.

**Cost** — fewer writes, not more: a turning head was 13 displays EVERY tick on the dragon (26 on
Spikor) and is now once per 3-tick cycle, in the same packet flush as the body. Measured in
the atom rig's cost notes: entity-data −41% per tracking player, rig driver time −38%.

**Not fixed here, and why.** Live head pitch is still 0 (`follow` zeroes the carrier's `xRot` before
it is read) and the head yaw is measured against `yRot` rather than `yBodyRot` and not clamped — the
walk above measured a relative head yaw of −148°, which vanilla would never draw. Those are an earlier change's
reads (the "copy of me" hair), being fixed there; changing them here would have made neither fix
attributable. And the dragon's `head` is a ROOT bone rather than a child of `body`, so the body bob
leaves it behind by ~0.04 blocks — an artifact defect to be fixed in the generator, not in this
creature.

### 🔴 5.5 — the head measured against a body the mob was not using, and the hair left behind

A live "copy of me" turned its face towards the child and left its hair box behind, until the back
of the hair was over the face (the eyes z-fighting through the brown). There were two defects, and
**each one alone looks fine in a film that is shot face-on**:

1. **The body was pinned.** `driveOne` drew the body at `yRot` and turned the head by
   `yHeadRot − yRot`, unclamped, and `RigDriver.follow` wrote `yBodyRot = yRot` on the carrier
   every tick. Vanilla's bytecode says why that reaches 180°: `LookControl.clampHeadRotationToBody`
   clamps only **while pathing**, and a standing mob keeps head and body together from the other
   side, with `BodyRotationControl` turning `yBodyRot` towards the head. Pinning `yBodyRot` to a
   stale `yRot` cancelled that, so the head did a half turn while the body faced away. Now the body
   is `yBodyRot` and the head is `RigHeadYaw.relative` (clamped to `getMaxHeadYRot()`, 75).
2. **The hair was not in the head's subtree**, so head-look (a bone transform, inherited) never
   reached it. Across 18 committed sculpts and 3 fresh real-agent humanoids the agent parented hair
   under `head` every time. The route to a ROOT hair box is an EDIT: `SculptBase.merge` swapped a
   re-sent part in wholesale, so "make his hair brown" (the box and the colour, no `parent`)
   detached it. Every one of those 3 wishes made a `start_from` edit. Now a replacement keeps its
   base part's parent, and `SculptHeadAdoption` hangs any part that sits on the head from it at
   assembly and tells the agent (`ATTACHED TO THE HEAD`).

The oracle is **false of the bug** and was found by being wrong first. A face-pixel count passed
the bug at 462 of 519, because the face is still drawn, INSIDE the back hair box. What
discriminates is the face's own bounding box: face-on it is **419 skin px and 0 hair**, and on the
bug it is **0 skin and 456 hair**. `GateC:vanillahair` films a creature facing away from the
camera, points its carrier's own `LookControl` at the camera, and scores that box and the chest
button (did the body come round?). Gate A pins both rules on the rig's real placement:
`HairFollowsHeadTest` (with a control proving the instrument sees the bug) and `RigHeadYawTest`.

**Pitch: shipped in an earlier change, with a write rule and a sign found on screen.** `driveOne` reads the
carrier's `xRot` BEFORE `follow` zeroes it, and `RigHeadYaw.pitch` NEGATES it: fed `xRot` as it
stands, the head tilted AWAY from a camera above it (the crown to the camera) and back from one
below. No film had seen a non-zero pitch before, because it was always 0, and the Gate A reference
transcribes the old mod renderer's head step, which is right about the chain and silent about which
way a vanilla angle points. The cost is bounded by `RigHeadYaw.worthWriting`: a yaw change writes
the head exactly as before, and the pitch rides along; a pitch change ALONE writes only once it has
moved 2°. Measured on an earlier change's single writer: every pitch change sent was +8% packets / +12% bytes;
the 2° rule is +1% / +3% with the child on the same ground and +3% / +5% with him 3 blocks up.
`GateC:vanillapitch` films a camera rising above and dropping below the creature and scores where
the face sits inside the head (tilt off: +0.199 from above; tilt on: −0.025; wrong sign: +0.339).
A/B seam: `headpitch=off|exact|step`, `headpitchstep`.


### 5.7 — fly, swim, hop, sit, lie and sleep: GENERATED, not keyframed

**No mechanism was new.** Every layer is M1 (bone poses) plus M2 (a whole-body root), and the
work was entirely in *where the numbers come from*. Three classes, all pure:

- **`RigAnatomy`** reads the sculpt: legs (a limb that REACHES THE GROUND, front or hind by
  POSITION: the cat's hind legs are named `armL/armR`), wings, the tail as an ordered chain, fins,
  tentacles, eyes. Each with a **hinge at the body edge**, because the wing kit pivots each panel at
  its own middle (a flap about the pivot spins three plates in place).
- **`RigLocomotion`** draws each layer as rigid moves about those hinges, solved EXACTLY onto bone
  poses (`F∘B`: rotation `q·q_b`, position `q(p+o_b)+t−p`), and the whole-body moves as a ROOT
  chain composed inside `RigPose`. The sit's tip and every drop are SOLVED on the geometry (a
  bisection that lands the front feet once the rump is down), not tuned per creature.
- **`RigGait`** picks the mix from the carrier's real state and eases every weight. Pure, so the
  controls are unit tests too (`RigGaitTest`).

**The playbook rule this rung adds: the agent decides by NAME and POWER, so test the routing, not
only the rendering.** The first routing arm asked for "a bunny that hops", "a kitten that sits and
naps" and "a baby shark that swims", and the agent summoned a vanilla rabbit, cat and dolphin 23 of
24 times — correctly, since those already hop, sit and swim, and never touching the generated path.
Only invented creatures (a purple three-eared bunny, a cotton-candy kitten) reach `hops`/`naps`.
A feature for sculpted creatures is measured on wishes only a sculpt can grant.

**Three instrument faults in the film, none of them the rig's:**
1. **A `NoAI` carrier never sets its own `onGround`.** `travel()` only runs when
   `isControlledByLocalInstance()`, which is `isEffectiveAi()` — false under NoAI. So a staged
   creature never sat (the gait, rightly, sits only something standing on the ground). The stage now
   STATES `onGround`, which is the fact under test.
2. **Looking +Z puts +X on the screen's LEFT**: the lane labelled vanilla was the control.
3. **A stage the server held and the client did not draw**: after the swim tank was drained, every
   later take showed no floor while all 759 floor blocks were counted present server-side. The floor
   is re-laid every take and the swim films last.

**What it does not do ( and honest gaps):** sit-on-shoulder; a squash (scale is refused, A6 —
approximated by a drop and bent legs); a biped lies on its back rather than curling; the flap is 15 ticks a beat at every size, where a bee is far faster and a phantom slower — the write rate (one pose
every 3 ticks) caps what can be drawn without aliasing.

### 5.8 — the WALK, on every leg the rig finds: a generated layer, not the clip

**Before:** the walk was the fixed clip (`CreatureFiles`), which swings four bones BY NAME
(`legL/legR/armL/armR`). A horse whose hind legs are `legBL/legBR` (the pilot's horse, lion,
bear and elephant) stepped on 2 of 4; a spider named `legL1..legR4` on 0 of 8; a `leg_row` spider
or ant on 0 (its legs were `kit_`-named and `RigAnatomy` did not count kit boxes as limbs at all).

**Now** `RigLocomotion.pose(…, Stride)` walks every `RigAnatomy.legs()` leg, on the walk clip's own
clock (`Stride.of(walkSpeed, walkPosition)` — the same two numbers `AnimationState.select` reads,
so the two cannot disagree about whether or where in its stride it is). While it walks, the
clip's keys are DROPPED on every bone that is a leg (`keep = 0`), so nothing is swung twice; the
clip keeps only what is not a leg — a biped's arms, which stay in their old rhythm against the
legs because the right side starts where the clip's `legR` starts.

| legs | gait | phase per leg (`side/2 − row·lag`) |
|---|---|---|
| 2 | alternate | L/R ½ apart |
| 4 | trot | diagonals together |
| 6 | alternating tripod | lag ½ a row |
| 8 | wave front to back, sides in antiphase | lag ¼ a row (`1/rows`) |

A *row* is a cluster, not a rank: the cat's paw stubs `legL2/legR2` stand inside its hind legs
and step with them (`SAME_ROW`), or they walk away from the legs they are the feet of.

**Two kinds of leg.** An UPRIGHT leg (foot under its hip) swings ±25° about its hip and is drawn
up 7% of its length while it comes forward. A SPRAWLED leg (foot well out to the side: an insect's,
a spider's) is jointed where it meets the BODY (`RigAnatomy` — the face of its top box nearest the
middle line, not the middle of the box, which would swing its root out of the body), sweeps about
that root with its foot on the ground and lifts ~14° to come forward. The sweep is capped so a foot
never reaches its neighbour's: a fixed 18° crossed a spider's legs over each other.

**The `leg_row` kit had to change for this to look like anything** (and a real tarantula wish came
out as slabs): each segment was the axis-aligned BOX of its diagonal, i.e. a panel, and thigh and
shin were both children of the body, so a step moved one and left the other. Each segment is now a
ROD tilted onto its line (`SculptKits.rod`, within `clampRotation`'s ±90° — the shin hangs DOWN in
its own frame so its tilt stays small), and the shin is the thigh's child.

**The controls, and why each is false of the bug:** the BODY display must hold one pose while the
legs change (true of a leg that does not move, false of a rig that shakes); and *a foot coming
forward is lifted, a foot pushing back is planted* (`RigWalkTest.aFootIsLiftedToComeForward…`) —
reverse the stride and every count and every phase lag is unchanged; this is not (§2.2). The film
(`GateC:vanillalegs`) walks each subject with vanilla's own `travel()` over a CHECKERBOARD, with
the camera riding beside it, side-on, front and three-quarter: a planted foot stays on its square.

**Instrument faults it hit:** (1) the stage was built on a fixed tick, the world came up a tick
late, and every subject walked on the superflat floor 160 blocks below an empty platform while all
four counts passed — the film now builds on the first READY tick and fails a subject that is off
the floor; (2) the client's copy of an invisible carrier trailed the drawn rig by ~2 blocks, so a
camera tracking it left the subject at the edge of the frame — it tracks the server's.

**What it does not do:** feet skate a little (the stride is the clip's clock, not solved from
ground speed — every vanilla mob skates too); a sprawled leg sweeps as one rigid piece, the knee
does not flex; the write rate (one pose every 3 ticks) caps the cadence, so a fast insect cannot
patter.

### Tier 6 — accepted regressions. Record, do not chase.

| row | why it cannot be reached |
|---|---|
| **freeze shake** | `setupRotations` on a model nobody renders |
| **hurt flash is additive in vanilla** | the tint channel is a multiply; it darkens where vanilla brightens |
| **F3+B hitbox outline** | `EntityRenderDispatcher` skips it for an invisible entity |
| **per-creature packs** | 1.21.1 has no `item_model` component; the override table is global |
| **a bucket of milk** | one tick of a visible carrier — `DisplayRig.hide` re-applies, measured |

---

---

## 3. IMPLEMENTATION NUANCES, per row

What each tier-1 row actually costs once you go to build it. Two of the three cost no code at all
and still have nuances; the third is the only one with an implementation, and its nuances are the
interesting ones.

### 3.0 The single cause behind three separate-looking defects: the carrier's hitbox

**Measured: the rig carrier is `3.93 × 4.50` where the wish mob is `3.60 × 4.50` — `+9.1%` wide,
identical height.** Nothing about that is visible until something is *sized* off the bounding box,
and then it shows up everywhere at once looking like several small unrelated problems:

| row | how the 9.1% surfaces |
|---|---|
| burning | the flame is `bbWidth × 1.4`, so arm B's is 2.03× its body against arm A's 1.67× |
| shadow (tier 2) | `shadow_radius` will want the same number — expect it there next |
| name plate | **not affected** — the plate keys off `bbHeight`, which matches exactly |

The plate row is the useful one: it is the control that proves the flame difference is *width*
rather than something general about the carrier. **Print both hitboxes in any harness that measures
something vanilla draws around a mob**; one line converts three findings into one.

### 3.1 Burning — no code, and the trap is the test rather than the feature

Zero implementation. `EntityRenderDispatcher.renderFlame` is not gated on invisibility and sizes
itself off the carrier's box, which `COVER` already fits. Two things to know:

- **Fire damages.** Both arms must be `setInvulnerable(true)` before anything is lit, or arm A dies
  partway through and the death flop changes its own picture — which reads as the fire row failing.
- **The flame is 1.5–1.8× the mob and that is vanilla.** Verified against a burning cow in the same
  frame (1.51× its height / 1.63× its width). It engulfs a big creature because the creature is big.

### 3.2 Potion swirls — no code, and two ways to poison the measurement

Zero implementation. Two nuances, both about the carrier's own effect:

- **The carrier is hidden by an INVISIBILITY effect that must stay invisible AND ambient** —
  `MobEffectInstance(INVISIBILITY, -1, 0, true, false, false)`. Drop the `visible=false` and every
  creature in the world wears a permanent grey swirl of its own.
- **`areAllEffectsAmbient` divides the emission rate by five.** The carrier already holds one
  ambient effect, so a test effect applied as ambient makes the rate 1-in-75 rather than 1-in-15
  and the ratio measures the wrong thing.
- The rate itself is **not measurable with a pixel count** — see §2.8.

### 3.3 The name plate — the only row with an implementation

A `text_display` parked over the creature's head. Five nuances, in the order they bite:

1. **It is NOT one of `displays()`.** That list is index-matched to `groups()`, so a plate appended
   to it is handed a bone's transform by every loop that walks the two together. It is its own
   field, included in `all()` (so `reap` and `discard` take it) and nothing else.
2. **`follow` has to move it, and differently from everything else.** It is the one piece of a rig
   that does not ride at the carrier's feet and takes no yaw at all. Open-coding that in the
   locomotion loop is the same shape as the half turn `follow` used to drop, so it goes through
   `DisplayRig.followNameplate`.
3. 🔴 **The height is DERIVED, and the naive value is wrong by exactly one text line.** Vanilla's
   tag translates to `head + 0.5` then scales Y **negatively**, so its text hangs *downward* and
   occupies `head + 0.275 .. head + 0.5`. A `text_display` does `translate(1 - k/2, -l, 0)` with
   `l = lines × 10`, so its text hangs *upward* and occupies `y + 0.025 .. y + 0.25`. Placed at
   `head + 0.5` the plate floats `10 × 0.025 = 0.25` blocks too high — measured at ~0.26 blocks
   before the fix, and **0 px after**. The constant is written as `0.5 - TEXT_LINE_BLOCKS` so the
   next reader can check the subtraction rather than trust a number.
4. **One residual, not chased:** the text_display draws at `1 - k/2` where vanilla draws at
   `-font.width/2`, so the text sits **one font unit to the right**. At this test's distance the
   font renders at roughly one screen pixel per font pixel, so that shift changes which strokes
   survive rounding and a 16× crop reads *"Bouser"*. It is a sub-pixel offset at any normal
   viewing distance and there is no screen-space nudge on a billboarded display to correct it with.
5. ⚠ **The tempting shortcut is worse than the gap.** `isInvisibleTo` returns false when the viewer
   shares a team with `canSeeFriendlyInvisibles`, which does bring vanilla's own tag back — and
   `LivingEntityRenderer.render` then computes `flag1 = !bodyVisible && !isInvisibleTo(player)` and
   draws the carrier as a **translucent ghost**. You would get the name tag and a spectral horse.

### 3.4 And the row that is really the harness

Tier 1 is four numbers and it took nine client runs. Every fault was in the instrument (§2.8), and
the general shape is worth the reminder: **a measurement of something vanilla draws AROUND a mob is
a measurement of the bounding box, the camera, the sky and the HUD before it is a measurement of
the feature.**

### 3.5 The shadow — `DisplayRig.setShadow` + `shadowRadiusFor`, and why it is a Display's ONE-PER-RIG property

- **Exactly one display carries it, and the other forty are explicitly CLEARED.** Every display in
  a rig sits at the *same entity position* — the placement lives in the transform, not in the
  position — so N shadow-bearing displays are N alpha-blended discs stacked on one spot, i.e. a
  black hole. `setShadow` writes `0` to every display but the first for the same reason it writes
  the radius to the first: a second call would otherwise move the shadow and leave the old one
  behind. Same "exactly one entity in a rig may wear that" rule as `RIG_OWNER_PREFIX`.
- **The carrier cannot cast it.** `EntityRenderDispatcher`'s shadow block is gated on
  `!entity.isInvisible()`, and the carrier is hidden forever. That is the **third** distinct vanilla
  check the rig has tripped over that keys off invisibility (after `shouldShowName` and the swirl
  rate), and the flame — four lines further up the same method — does *not* check it. **Whether an
  overlay survives an invisible entity is decided one `if` at a time; there is no rule.**
- **The radius is pre-multiplied here because a Display has no renderer.** See the table above:
  vanilla gives a mob a base and applies the entity's scale every frame; a Display is given an
  absolute number. This is the fifth thing the rig has had to pre-multiply, after the flame
  (bounding box), the plate (box height), the culling range (box size) and the swirl rate.
- **The stage needs a FLOOR within half a block of the feet.** `renderShadow` scans blocks from
  `floor(y − min(strength/0.5, radius))` up to `floor(y)`. Everything in this harness has
  `setNoGravity(true)`, so the floor is placed rather than assumed — and it is **white**, because a
  shadow is a subtraction and a dark floor has nothing to subtract.

### 3.6 The glow outline — `setGlowing`, and the rule that is the exact OPPOSITE of the shadow's

- **Every display glows; only one casts a shadow.** The reason is one sentence: **a shadow is a
  function of POSITION, which every display shares, and an outline is a function of GEOMETRY, which
  none of them share.** A rig with the flag on one display is a creature with one glowing limb.
  Getting this backwards in either direction gives a plausible-looking result.
- **Two vanilla facts make it work, both from bytecode.** `LevelRenderer` routes an entity into the
  outline buffer on `Minecraft.shouldEntityAppearGlowing`, which is the shared `Glowing` flag — the
  one the `Glowing` NBT boolean writes, so the same save/load round trip the rest of `DisplayRig`
  uses reaches it. And the outline COLOUR is `entity.getTeamColor()`, which `Display` **overrides**
  to return `glow_color_override` when one is set. No team, no scoreboard, one colour per creature.
- ⚠ **"The carrier is deliberately not glowed" was a PREDICTION, and play refuted it
 .** This line said the carrier "renders nothing, so it would contribute no outline". It
  renders nothing *visible*: `LivingEntityRenderer.getRenderType` draws an invisible mob that
  appears glowing as **`RenderType.outline(texture)`**, so a glowing carrier outlines its own
  model. And every shipped glow source writes the carrier, because it is the real entity:
  `modify_mob`, `MobStates`, the GLOWING effect (a datapack's `effect give … glowing`, a spectral
  arrow), `/team`. Meanwhile `setGlowing` had exactly one caller, this take. So a tester filmed
  two sea serpents with a white **horse-shaped** outline at one end and none on their blocks,
  while S6 read VERIFIED. **A capability proved by calling it by hand is not wired to the paths
  that need it** (the camera notes: grep for a guard's CALLERS).
- **The fix is a MIRROR, and the carrier stays the one source of truth.** `DisplayRig.mirrorGlow`,
  called every tick from `VanillaRigAnimator.driveOne` (before the dying branch, so a creature
  killed while glowing stays outlined as it falls), reads what vanilla's own
  `LivingEntity.isCurrentlyGlowing` is built from on the server (`hasGlowingTag()` OR the GLOWING
  effect) plus the team colour, and writes the displays **only on a change**. A team colour becomes
  `glow_color_override`; the scoreboard is server-global and synced to every client, so a team's
  colour is the same for every viewer and baking it into the override loses nothing. A flag-only
  change takes `Entity.setGlowingTag` on each display rather than the save/load round trip, which
  also re-sends every item stack (`ItemStack` has no `equals`): measured, 5.8 B a display against
  54 B, and a 1.1 ms toggle tick for eight rigs against 5.5 ms.
- **The carrier's own outline is suppressed at its only two writers.** Shared flag 6 is written by
  `Entity.setGlowingTag` and `LivingEntity.updateGlowingStatus` (from `tickEffects`, when effects
  changed) and nowhere else, on both 1.21.4 and 1.21.1, and both go through `setSharedFlag`.
  `mixin/CarrierGlowFlagMixin` holds bit 6 down there for any entity tagged `mfamrig`, so a command
  run between ticks never reaches a client, and the tag, the effect, the save and the server's
  `isCurrentlyGlowing()` stay true. One ordering trap: `Entity.load` reads `Glowing` BEFORE `Tags`,
  so a carrier saved glowing raises the flag before it is known to be a carrier. `mirrorGlow`'s
  first call rewrites the tag, which puts it back through the mixin.
- **A re-wish rebuild carries the glow.** It makes a new carrier with a new scoreboard name, so
  `respawn` copies the glowing tag and the team across, the same way it copies the name.
- **`-1` is the only "no override" value**, so a plain `0xRRGGBB` with a zero alpha byte is a legal
  override that happens to be the colour asked for. The score row therefore counts *red-dominant*
  changed pixels rather than changed pixels: white has r ≈ g ≈ b and scores zero however bright it
  is, which is what makes it a test of the COLOUR rather than of the outline.
- ⚠ **The outline needs a post-processing chain** (`shouldShowEntityOutlines` wants both
  `entityTarget` and `entityEffect`). It works under Xvfb + llvmpipe — measured — but the score
  treats "arm A shows no outline either" as a SKIP with a note, not a failure: that is a renderer
  capability question, not a rig one.

### 3.7 The culling range — the literal that was right once

`view_range` used to be `4.0f`, written inside `apply`, which the animation loop calls sixty times
a second. Two things were wrong with that and only one of them is the number:

- **The value was arbitrary.** `Display.shouldRenderAtSqrDistance` is
  `d < (view_range × 64 × viewScale)²` and `Entity`'s is the same expression with
  `boundingBox.getSize()` in place of `view_range`. So the derivation is a *substitution*, not a
  tuning: pass the carrier's own box size and the two expressions are identical. `4.0f` gave a
  0.5-block critter 256 blocks of range and a 12-block one the same 256.
- **The place it was written could never be fixed.** `apply` is static and has no carrier, so the
  one method that *knows* the creature (`spawn`) could not have made a derived value stick — the
  next animation frame would have overwritten it. It moved to `matchViewRange`, called once at
  spawn, beside `matchLight`. Same shape as the camera notes' train-axis literal: **a value that
  a feature made variable while every caller still typed the old constant.**

### 3.8 Emissive bones — the plumbing lands, the AUTHORING does not

- **It is FULLBRIGHT, not additive.** Vanilla's emissive layers (an enderman's eyes, a spider's)
  draw a second pass in `RenderType.eyes`, which *adds* light and glows past white in the dark. A
  `brightness` override caps at the maximum ordinary light level, so an emissive bone here looks
  like a bone standing in daylight: correct in a cave, invisible at noon. **An emulation, and it
  goes in the ledger as one**, exactly like the tint channel's multiply-vs-overlay.
- **Which bones is not something the sculpt can say.** There is no emissive opt-in in the schema and
  the sculpt tool is frozen for this spike, so `setEmissive` takes a predicate and the harness picks
  by name. What row 2.4 delivers is the plumbing; the authoring is a later change to the tool. The
  gate prints `1 of 41 groups` so nobody can read the row as more than it is.
- **It shares a key with the rig's light.** `matchLight` (at spawn) and, since an earlier change,
  `followLight` (on every pose write tick where the carrier's light CHANGED) write the same
  `brightness` key on every display to light the rig like the carrier. So `setEmissive` records
  which displays it made fullbright and `followLight` leaves exactly those alone, and
  `clearEmissive` is the undo (the harness's `reset` calls it): a reset that re-lit every display
  by hand would leave `followLight` skipping bones that are no longer emissive.
- **The row it depends on had never been measured**: does the rig darken at night at all? A sky
  light *level* of 15 is written by `matchLight` regardless of the time of day, and it was entirely
  plausible that this made the rig immune to nightfall while the mod creature darkened. Measured:
  **113.9 luma lost by arm A, 109.7 by arm B.** It darkens. That is worth its own scoreboard row
  (S12) because it is the kind of thing that would have been discovered by a child at dusk.

### 3.9 The tint channel — `RigTint`, and the one write that has to happen when there is no tint

- **The colour is derived, not chosen** — see the tier-3 section above. `0xFFB2B2` reproduces
  vanilla's green and blue exactly and under-shoots only red, by a quantity the code returns.
- **Write `NONE` when there is no tint.** A dyeable carrier with `tintindex: 0` and no
  `dyed_color` renders *brown*, not plain. The failing state is an absent component, which is
  invisible in the pack, the model and the write path.
- **An unchanged tint costs nothing.** `tint()` keeps `lastTint` and returns 0 for a repeat, the
  same rule `animate` and `lookAt` use — without it, a creature standing still repays 41 item
  rewrites every tick forever.
- **Refuse to run with the channel off.** The Gate C harness exits loudly when `rigtint=off`,
  because the fallback carrier is `paper`, which vanilla has *no* colour handler for: every write
  would be inert and the row would report a real-looking zero. That is the skip-succeed shape this
  repo keeps finding, and it is worth one loud exit.

### 3.10 The death flop — `RigDriver.dying`, and why the flash and the fall are ONE call

- **Vanilla's own condition is `hurtTime > 0 || deathTime > 0`**, so a mob is red for the *whole*
  death animation, not just for the blow. Driving the tint and the rotation from one place is what
  stops a rig flopping in its ordinary colours — and splitting them is the obvious way to build it.
- **Interpolate over one tick.** The driver writes at 20 Hz; `interpolation_duration = 1` lets the
  client tween between poses, which is the smoothing vanilla gets free from `partialTick`. At 0 the
  fall is visibly stepped.
- **Every display, every tick, no skip.** Unlike `animate`, there is no "did it move" shortcut —
  the whole body is turning. That is M2's quoted price, paid for twenty ticks.
- **Read the carrier before it is REMOVED.** A `Mob` is gone at `deathTime` 20 and the displays
  outlive it, so the driver has to hold the last pose rather than snap upright. Whatever reaps the
  rig has to run *after* that, which is a real ordering constraint on a production driver and the
  reason the harness reaps in a later phase.
- **Nothing in this run is invulnerable.** Every other state row pokes a state onto a creature;
  this one hits it with `hurt(damageSources().generic(), maxHealth × 10)` and lets vanilla's death
  path do the rest. `setHealth(0)`, `kill()` and `discard()` each skip a part of what is being
  measured.

## 2. The playbook — how to land a rung without re-learning the last one

Grown as rungs land. Everything here is a rule that cost something.

### 2.1 Ask which mechanism it is FIRST, and check the call site rather than the rule

The rule in §0 predicts every row correctly and predicts nothing reliably: burning and the shadow
are adjacent lines of one method and go opposite ways. Two minutes with the decompiled
`EntityRenderDispatcher` / `LivingEntityRenderer` settles a row that would otherwise be an
afternoon of building the wrong mechanism. `build/neoForm/*/steps/patch/outputs.jar` is the source.

### 2.2 🔴 A control that is TRUE OF THE BUG is not a control

The walk run shipped with three controls — the creatures moved 3.17 blocks, the walk clip was
selected on 360 of 360 ticks, arm B was wearing real models — and filmed eighteen seconds of two
creatures **moon-walking**, and reported PASS. Every number was true of a creature sliding
backwards. It was found by a human watching the film.

Before adding a control, state the failure it is supposed to catch and then ask *"would this number
be different if that happened?"* For anything with a direction, an orientation or an ordering, the
answer is usually no, and the control has to be an *angle* or a *comparison*, not a magnitude.

### 2.3 🔴 A constant that is applied at ONE call site will be dropped at the second

`DisplayRig.spawn` added the half turn; `RigDriver.follow` did not. A rig that stood still was
pixel-perfect and a rig that WALKED was 180° out, permanently, from its first step. Same shape as
the train-axis literal in the camera notes.

The fix is not to remember: it is `DisplayRig.displayYaw(bodyYaw)`, spelt once, with every writer
going through it — and a Gate B test (`aRigThatMovesKeepsTheFacingItWasSpawnedWith`) that asserts a
rig which MOVES faces the way a rig which STANDS STILL faces. **Verified to fail** by putting the
raw yaw back.

**Generalise it for M2 and M3:** the moment a second code path can write a display's yaw, position,
transform, item or property, that write goes through one named helper. There will be several — a
death flop writes the same transform the walk does.

### 2.3b 🔴 …and the way to make that structural: DELETE the overload that defaults it

§2.3's fix is "spell it once and route every writer through it". D5 needed the other half, because
the value it was routing is not a constant — it is **per creature**, and there is no helper that can
derive it.

`RigPackWriter.customModelData(groups, g)` returned `1 + groups.indexOf(g)`: correct for the first
creature in a world, wrong for every one after it. The obvious fix — add a `base` parameter, keep the
old two-argument form delegating to `base = 1` — is the *same bug with a longer fuse*, because the
call site that forgets the base still compiles and still renders somebody else's creature. So the old
signature was **deleted**, and `DisplayRig.spawn`'s widest overload was collapsed to require a
`cmdBase`. That produced exactly four compile errors, in exactly the four harnesses that write a
pack, and none in the three GameTests that legitimately only ever have one creature.

**The rule: when a value stops being global and becomes per-instance, do not add a parameter with a
default — remove the form that can be called without it.** A default is indistinguishable from a
correct value at every call site, and the failure it produces (a creature wearing another creature's
models) is a picture rather than an error.

### 2.3c 🔴 A REAPER hung on the wrong event deletes the animation it was built beside

B9's reaper and tier 4.1's death flop are the same twenty ticks seen from two sides, and nothing
about either one says so. The obvious hook for "clear a dead creature's displays" is
`LivingDeathEvent` — which fires the instant the creature dies, **twenty ticks before vanilla
removes the body**. Reaping there deletes every display while `deathTime` is still counting, so the
creature *vanishes* where it should fall over, and the flop reads as a feature that was never built.

The hook is `EntityLeaveLevelEvent`, and it needs the removal **reason**: that event also fires on a
chunk unload and on a dimension change, and reaping there would delete the displays of a creature
that is merely asleep — which comes back as an invisible carrier with nothing hanging off it. Only
`KILLED` and `DISCARDED` are the end of a creature.

`VanillaRigBridgeGameTest.theRigIsReapedWhenItsCarrierIsREMOVEDRatherThanWhenItDies` asserts the
displays are **still there five ticks after the killing blow** and gone once the carrier is, which
is the only form of the test that can tell the two hooks apart.

**The general shape:** when a cleanup and an animation share a subject, the cleanup's hook is a
statement about the animation's length. Ask what is still supposed to be happening when the cleanup
runs, before choosing the event.

### 2.4 The oracles that transfer between clips, and the one that does not

From the walk spike, reusable as-is:

- **the differential oracle** — compose with an EMPTY pose and assert it reproduces the planned rest
  chain exactly. Catches the whole matrix layer without needing a picture, and it fired first, which
  is why no time went into a wrong composition;
- **loop closure** — a cyclic clip must return to its own first frame at 0 px, and still after ten
  cycles;
- **cost** — an idle creature writes zero transforms; every bone a clip moves has its own display;
- **refusal** — a channel the rig cannot express (scale) is REFUSED by name rather than dropped.

**Loop closure does not apply to a progress-driven clip.** The attack runs 0→1 once per swing and
never wraps, so asserting it returns to its first frame asserts nothing. Its oracle is instead *the
pose at progress 1 is the pose the next idle frame starts from* — i.e. continuity into the following
state, which is a different question and needs writing.

### 2.4b 🔴 OPEN: **live phase alignment has never been measured**, and the side-by-side film cannot measure it

Everything proven about the walk is proven with both arms **frozen**: static pose 1.4%, frames
between keyframes 1.3%, loop closure 0 px, the differential oracle exact. What has never been
measured is whether the two arms are *in step while moving*, and the obvious instrument does not
work:

- the film puts the arms in two **lanes** (x≈14.5 and x≈5.5, camera at 8.5), so they are seen from
  ~29° and ~15° off axis. A boxy creature shows a different silhouette at each. Measured on the
  fixed film: **22–26% silhouette mismatch**, and a ±6-tick time sweep is FLAT (22.0–26.4%), so no
  phase offset explains it — which is what you would expect if viewpoint dominates;
- a leg-spread proxy cross-correlated at r=0.40, at a nonsense lag. That is a bad instrument, not a
  finding, and it is exactly the shape §9.6 warns about — **do not let a noisy instrument invent a
  defect any more than let a quiet one hide one.**

So this is recorded as **unmeasured, with a designed experiment rather than a number**: freeze both
arms at the *same clip time* in the *same lane* (one after the other, as the static A/B already
does), step the clip time through a whole cycle, and diff. That controls viewpoint and isolates
phase. Until then the live claim is behavioural — both walk, both face their travel, both play the
walk clip on 359 of 360 ticks — and not photometric.

There IS a reason to expect a small real lag: arm A samples the clip on the **client** every frame,
while arm B is posed on the **server** every 3 ticks and tweened. Up to ~1.5 ticks of average lag is
by design and is the price the interval buys. Quantify it before deciding it is a problem.


### 2.8 🔴 FOUR WAYS A PIXEL MEASUREMENT LIED IN ONE AFTERNOON

Tier 1 is four numbers. Getting them took five client runs, and **the code under test was never the
problem** — this is the §9.6 tally repeating itself on a new instrument. Each fault produced a
plausible, actionable, wrong answer, and each is cheap to avoid next time.

1. **`NativeImage.getPixelRGBA` returns ABGR, not ARGB.** Every existing pixel helper in this repo
   only ever SUBTRACTS two shots, and a difference does not care which channel is which — so the
   bug sat latent in `grab()` for as long as it existed, and tier 1 was the first test to
   *interpret* a colour. Symptom: a sky filter that reads "blue beats red" called **100% of the
   frame non-sky**, and the creature box came back as the whole half-screen. Same family as the Mod
   Repo's map-art bug, where a red banner rendered blue behind green gates.
   **Rule: the first consumer to interpret a colour inherits every latent channel-order bug in the
   pipeline it borrowed.** Add a self-check that names the suspicion — ours prints what fraction of
   the frame the sky filter matched, and says "if that is ~100%, the channel order is wrong".
2. **`reloadResourcePacks().thenRun(...)` firing is not the client being back on screen.** The
   Mojang loading overlay is still drawn over the world and fades on its own clock, and while it is
   up it **tints the entire frame pink**. Our plain baseline came back as a pink Mojang splash with
   one creature half-drawn behind it, mean rgb (208,122,144) against the real (166,196,246), so
   every difference against it was ~100% of the box. Wait for `mc.getOverlay() == null`, and refuse
   to `grab` while one is up.
3. **One state's leftovers become the next state's baseline.** BURNING sets 600 ticks of fire and
   NAMED cleared it *as part of applying itself*, so the "name tag" number was really the flames
   going out — and arm B scored HIGHER than arm A on a row where arm B shows nothing at all. Every
   state now resets to plain, settles, and takes its own paired baseline.
4. **Hiding the HUD hides name tags.** `Minecraft.renderNames()` is literally
   `!options.hideGui`, and `shouldShowName` is gated on it — so the camera rule this repo carries
   everywhere (hide the HUD before the shutter) silently switches off the thing being measured.
   Arm A, a plainly visible mob with a visible custom name, scored 0.

5. **A `Monster` control is DISCARDED in a peaceful world**, and `setPersistenceRequired()` does
   not save it — `Mob.checkDespawn` tests `shouldDespawnInPeaceful()` *first*. The zombie control
   was created, logged its hitbox, and was gone by the shutter. Use a cow (which is also the better
   control: 0.9 × 1.4 is far nearer this creature's proportions than 0.6 × 1.95).
6. **A body box clipped by its own search range is not a body box.** With the lanes 4.5 blocks
   apart, each creature's edge landed a pixel past the column split, so the control's "body" was
   the two creatures' overflow — 137 px wide, exactly the width of the band it was searching, for
   an animal 16 px across. **A box equal to its own search range is always the range**, and that is
   now an assertion.
7. **A "more principled" instrument can be worse.** Replacing the fixed colour rule with a per-row
   sky reference sampled from the frame edges was strictly more defensible and strictly less
   accurate — Minecraft's sky varies horizontally as well as vertically, so a row's edges do not
   predict its middle, and both creature boxes grew by 60%. What actually worked was deleting the
   colour rule entirely and differencing against an EMPTY STAGE frame taken after the reload with
   the sky pinned: one reference, no thresholds to tune, nothing to be clever about.

**And the one that was not an instrument fault but read like one:** the swirl ratio measured 0.02,
0.08, 0.15 and 0.11 across runs before settling at 0.27. The cause was §9.4 again — *a `wish_mob`
standing still is animating*, so arm A's idle clip kept moving its silhouette outside the frozen
mask and every one of those pixels counted as a particle. Freezing arm A with the pose override
(`poseClip=IDLE poseTime=0.5`) is what produced a number that matches vanilla's own arithmetic.
**A noisy instrument invents defects exactly as readily as a quiet one hides them.**

### 2.5 Film only what MOVES; photograph everything else

A state you can hold — on fire, poisoned, glowing, dead — is a photograph: exactly reproducible,
diffable, and cheap. Video is warranted for a cycle and for a transition, and even there the frames
are the evidence and the video is the exhibit. Tier 1, 2 and 3 above are entirely photographs.

### 2.6 Freeze BOTH arms before comparing, and check what "freeze" means for the state

`wish_mob` standing still is playing its idle clip, so an A/B of a "static" creature is comparing
two moving things (§9.4). For a state effect the same trap has a second door: `hurtTime` counts
down, `deathTime` counts up, and fire has its own two-frame flicker — so a photograph of a state is
a photograph of a *phase* of that state, and the two arms must be at the same phase, not merely both
"on fire".

### 2.7 The client tween is the transport, and it hides a snap

`interpolation_duration` is what buys the write rate down to one in three ticks. Two instrument bugs
found while proving it: reading the interpolation fields off a display the clip never moves (a true
number about the wrong entity), and passing the duration through a static across `server.execute`,
where the client thread had already reset it. Any new mechanism that writes a transform must be
handed the same interval the schedule uses — never a duration typed separately.

