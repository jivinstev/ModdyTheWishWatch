package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.ModEntities;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.mob.PetBehaviour;
import com.moddingfromamod.mob.PetBehaviours;
import com.moddingfromamod.mob.PetMoves;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import com.moddingfromamod.testing.MockPlayerNetwork;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>: a creature's species reaches its atom-rig carrier.</b>
 *
 * <p>Every test goes through the REAL bridge: a {@link WishMobEntity} is added to the level, the
 * bridge cancels it and hangs the creature on a vanilla carrier, and the assertions are about that
 * carrier. Each was red on {@code main} before {@link SpeciesOnRig}: the companion wandered (
 * measured 29.0 → 22.8 blocks in 45 s), the carrier took a player as its target, fire cost it
 * health, and the glowing tag was false.
 *
 * <p>A companion with no owner follows the NEAREST player, so the one test that stands a child near
 * a companion, {@link #aCompanionRigWalksToThePlayer}, has a batch to itself: in a shared batch
 * every sibling's companion walked to that child too. The mock players the other tests make
 * are never moved from the world spawn, so nothing here is near them.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SpeciesOnRigGameTest {

    private static final String BATCH = "species_on_rig";
    static final BlockPos SPOT = new BlockPos(2, 2, 2);

    /**
     * A species with the starter pet dragon's behaviour (read from its own shipped species.json),
     * on the corpus Spikor sculpt, under {@code id}. {@code extra} overrides fields.
     */
    static String plant(GameTestHelper helper, String id, String extraJson) {
        try {
            com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, plantedSpecies(id, extraJson));
            return id;
        } catch (IllegalStateException e) {
            helper.fail(Component.literal(e.getMessage()));
            return id;
        }
    }

    /**
     * The species {@link #plant} registers, its sculpt written — for the Bedrock batch, which stages abilities on a
     * plot of its own with no GameTest helper and registers it itself ({@code BedrockStage}).
     */
    public static Species plantedSpecies(String id, String extraJson) {
        // "sculpt" picks the BODY — "starter:<name>" (a starter's own sculpt) or "corpus:<case>" (a committed
        // tier-experiment sculpt) — so a case can stage a shape the planner gives a given carrier (a tall, thin
        // body rides a llama). Spikor's when absent, as ever.
        JsonObject asked = JsonParser.parseString(extraJson).getAsJsonObject();
        String body = asked.has("sculpt") ? asked.remove("sculpt").getAsString() : "corpus:" + SpikeFixture.SPIKOR;
        extraJson = asked.toString();
        try {
            String text;
            if (body.startsWith("starter:")) {
                try (var in = SpeciesOnRigGameTest.class.getResourceAsStream(
                        "/starter-library/species/" + body.substring(8) + "/sculpt.json")) {
                    if (in == null) {
                        throw new IllegalStateException("no starter sculpt " + body);
                    }
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } else {
                java.nio.file.Path spec = SpikeFixture.find(SpikeFixture.CORPUS_DIR + "/" + body.substring(7) + "/sculpt.json");
                if (spec == null) {
                    throw new IllegalStateException("the corpus sculpt is missing: " + body);
                }
                text = java.nio.file.Files.readString(spec);
            }
            CreatureFiles.write(id, CreatureSculpt.parseParts(JsonParser.parseString(text).getAsJsonObject()
                    .getAsJsonArray("parts")), text);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not write the sculpt: " + e, e);
        }
        JsonObject o;
        try (var in = SpeciesOnRigGameTest.class.getResourceAsStream(
                "/starter-library/species/pet_dragon/species.json")) {
            o = JsonParser.parseString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("the starter pet dragon is not on the classpath", e);
        }
        o.addProperty("id", id);
        o.addProperty("modelKey", id);
        o.remove("abilityPacks");
        JsonObject extra = JsonParser.parseString(extraJson).getAsJsonObject();
        extra.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return Species.fromJson(o);
    }

    static void wish(GameTestHelper helper, String id) {
        wishAt(helper.getLevel(), helper.absolutePos(SPOT), id);
    }

    /** {@link #wish} at an absolute block. */
    public static void wishAt(ServerLevel level, net.minecraft.core.BlockPos at, String id) {
        WishMobEntity mob = ModEntities.WISH_MOB.get().create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setSpeciesId(id);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(mob);
    }

    /** This species' carrier, found by its species tag anywhere near the plot. */
    static Mob carrier(GameTestHelper helper, String id) {
        String tag = WishMobTags.forSpecies(id);
        AABB near = new AABB(helper.absolutePos(SPOT)).inflate(48);
        for (Entity e : helper.getLevel().getEntities((Entity) null, near,
                e -> e.getTags().contains(DisplayRig.RIG_TAG) && e.getTags().contains(tag))) {
            if (e instanceof Mob m && m.isAlive()) {
                return m;
            }
        }
        return null;
    }

    /**
     * The nearest of THIS carrier's own displays to {@code to} — only displays wearing its owner tag
     * ({@code RIG_OWNER_PREFIX + uuid}), so another creature's rig standing near the child cannot
     * answer for it. {@code MAX_VALUE} when it has none.
     */
    static double nearestOwnDisplay(GameTestHelper helper, Mob carrier, Entity to) {
        String own = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        double best = Double.MAX_VALUE;
        for (Entity d : helper.getLevel().getEntities((Entity) null, to.getBoundingBox().inflate(48),
                e -> e instanceof Display && e.getTags().contains(own))) {
            best = Math.min(best, d.distanceTo(to));
        }
        return best;
    }

    /**
     * what a failing follow assertion needs to say to name its cause — who the carrier is
     * actually going to (the nearest player, which is what {@code PetBehaviours.ownerOf} falls back
     * to), how far away the child is, which mob is doing the walking, and whether it has a path.
     */
    static String whyNotFollowing(Mob c, Entity child) {
        ServerLevel level = (ServerLevel) c.level();
        var nearest = level.getNearestPlayer(c, PetMoves.FOLLOW_TELEPORT * 2);
        var path = c.getNavigation().getPath();
        StringBuilder players = new StringBuilder();
        for (ServerPlayer p : level.players()) {
            players.append(String.format("%s@%.1f ", p.getGameProfile().getName(), p.distanceTo(c)));
        }
        return String.format("[carrier=%s at %s, child at %s; nearest player=%s (%.1f); players: %s"
                        + "path=%s (done=%s, end=%s); follow=%s]",
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(c.getType()).getPath(),
                c.blockPosition().toShortString(), child.blockPosition().toShortString(),
                nearest == null ? "none" : nearest.getGameProfile().getName(),
                nearest == null ? -1.0 : nearest.distanceTo(c), players,
                path == null ? "none" : (path.canReach() ? "reaches" : "partial"),
                c.getNavigation().isDone(), path == null ? "-" : path.getTarget().toShortString(),
                PetBehaviours.of(c).toStored());
    }

    /** where the carrier went, when it can no longer be found near the plot. */
    static String lostCarrier(Mob last, Entity child) {
        if (last == null) {
            return " (never seen)";
        }
        var src = last.getLastDamageSource();
        return String.format(" (last seen: %s at %s, alive=%s, removed=%s, health=%.1f, "
                        + "last damage=%s, child at %s)",
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(last.getType()).getPath(),
                last.blockPosition().toShortString(), last.isAlive(), last.getRemovalReason(),
                last.getHealth(), src == null ? "none" : src.getMsgId(),
                child.blockPosition().toShortString());
    }

    // ── companion: it follows him, displays and all ──────────────────────────────────────────

    /** The child's first spot on {@code follow_walk}: 12 blocks east of {@link #SPOT}. */
    static final BlockPos WALK_TO = new BlockPos(14, 2, 2);

    /** …and where he goes once it has come: back across the floor, about 12 blocks again. */
    static final BlockPos WALK_BACK = new BlockPos(1, 2, 7);

    /** Close enough to count as having come to him. */
    static final double ARRIVED = PetMoves.FOLLOW_CLOSE + 1.5;

    /**
     * Each leg's budget, in ticks. A real follow closes the first leg in 28–43 ticks and the
     * second in 19–35 (measured over 150 runs); the budget is nearly three times the slowest. What it must exclude was measured too: with the follow stubbed
     * out, a carrier that only WANDERS on this floor drifted to within {@link #ARRIVED} of the
     * child in 104, 155, 250, 278, 486 and 1120 ticks (6 of 10 runs inside 1200) — so one leg
     * and a long budget cannot tell following from strolling into him, and two legs can.
     */
    static final int LEG_TICKS = 120;

    /** More than this in one tick is a hop (a teleport), not a step. */
    static final double STEP_MAX = 2.0;

    /**
     * an earlier issue's claim: a companion on the atom rig — its carrier AND its displays — WALKS to the child,
     * and keeps coming to him when he moves.
     *
     * <p><b>Its own batch and its own floor.</b> It used to share {@code species_on_rig}
     * with five other companions and stand the child 20 blocks away on {@code empty_test}, and
     * both were wrong:
     * <ul>
     *   <li>20 blocks is past {@link PetMoves#FOLLOW_TELEPORT}, so the carrier never walked — it
     *       HOPPED, off its 9-block plot and into a sibling's. When that sibling passed,
     *       {@code GameTestInfo.succeed} discarded every non-player entity in its plot, our
     *       carrier included, and this test timed out on "no carrier yet";</li>
     *   <li>the batch's other companions follow the NEAREST player, which was this child, so
     *       they hopped to him too: the fireproof one suffocated beside him and read that as fire
     *       damage, and their displays could answer the displays check for ours.</li>
     * </ul>
     * So: {@code follow_walk} is a 17-block floor and the child stands ON it, 12 blocks away (a
     * walk: well past {@code FOLLOW_CLOSE}, short of the {@code FOLLOW_TELEPORT} hop); nothing else
     * in the batch has a player or a companion; a hop fails the test; and only this carrier's own
     * displays count. And because a wanderer does reach a child 12 blocks away on a floor this
     * narrow, sooner or later, he then moves across the floor and it must come again — each leg
     * inside {@link #LEG_TICKS}.
     */
    @GameTest(template = "follow_walk", batch = "species_on_rig_walk", timeoutTicks = 1200)
    public static void aCompanionRigWalksToThePlayer(GameTestHelper helper) {
        String id = plant(helper, "t6c4_companion", "{}");
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-companion");
        stand(helper, child, WALK_TO);
        wish(helper, id);
        // leg: 0 = no carrier yet, 1 = walking to WALK_TO, 2 = walking to WALK_BACK, 3 = came both times
        int[] leg = {0};
        long[] legStart = {0};
        double[] start = {-1, -1};
        long[] took = {-1, -1};
        double[] maxStep = {0};
        AtomicReference<net.minecraft.world.phys.Vec3> last = new AtomicReference<>();
        AtomicReference<Mob> seen = new AtomicReference<>();
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c == null || leg[0] == 3) {
                return;
            }
            seen.set(c);
            double d = c.distanceTo(child);
            if (leg[0] == 0) {
                leg[0] = 1;
                legStart[0] = helper.getTick();
                start[0] = d;
                requireAWalk(helper, d, "first");
            }
            var now = c.position();
            if (last.get() != null) {
                double step = now.distanceTo(last.get());
                maxStep[0] = Math.max(maxStep[0], step);
                if (step > STEP_MAX) {
                    helper.fail(Component.literal(String.format("the carrier jumped %.1f blocks in one tick: a hop, "
                            + "not a walk %s", step, whyNotFollowing(c, child))));
                }
            }
            last.set(now);
            long elapsed = helper.getTick() - legStart[0];
            if (d < ARRIVED) {
                took[leg[0] - 1] = elapsed;
                if (leg[0] == 1) {
                    stand(helper, child, WALK_BACK);
                    leg[0] = 2;
                    legStart[0] = helper.getTick();
                    start[1] = c.distanceTo(child);
                    requireAWalk(helper, start[1], "second");
                } else {
                    leg[0] = 3;
                }
            } else if (elapsed > LEG_TICKS) {
                helper.fail(Component.literal(String.format("the companion rig did not come to him within %d ticks "
                                + "(leg %d of 2): %.1f blocks, started at %.1f; first leg took %d "
                                + "ticks, largest step %.2f %s", LEG_TICKS, leg[0], d,
                        start[leg[0] - 1], took[0], maxStep[0], whyNotFollowing(c, child))));
            }
        });
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet" + lostCarrier(seen.get(), child)));
            helper.assertTrue(PetBehaviours.of(c).follow() == Boolean.TRUE,
                    Component.literal("the companion's carrier does not read as following (of=" + PetBehaviours.of(c).toStored() + ")"));
            helper.assertTrue(leg[0] == 3, Component.literal("still walking to him (leg " + leg[0] + ")"));
            double shown = nearestOwnDisplay(helper, c, child);
            helper.assertTrue(shown < 6, Component.literal("the carrier came but its displays did not: "
                    + (shown == Double.MAX_VALUE ? "it has none" : String.format("%.1f", shown))
                    + " " + whyNotFollowing(c, child)));
        });
    }

    private static void stand(GameTestHelper helper, ServerPlayer child, BlockPos rel) {
        var at = helper.absolutePos(rel);
        child.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    /** A leg that starts inside the stay distance, or past the hop distance, proves no walk. */
    private static void requireAWalk(GameTestHelper helper, double d, String which) {
        if (d <= PetMoves.FOLLOW_CLOSE * 3 || d >= PetMoves.FOLLOW_TELEPORT) {
            helper.fail(Component.literal(String.format("the %s leg started %.1f blocks from him: not a walk (it must "
                            + "be well past FOLLOW_CLOSE=%.0f and short of the FOLLOW_TELEPORT=%.0f hop)",
                    which, d, PetMoves.FOLLOW_CLOSE, PetMoves.FOLLOW_TELEPORT)));
        }
    }

    // ── The starter pet dragon, as shipped, rides a carrier that can follow ─────────

    /**
     * The first real grant after 4a (Gate D, 2026-09-25) put the starter pet dragon on a TURTLE:
     * a companion, correctly, that closed 12 blocks to 7.4 in ten seconds. The fitter picks by
     * shape, and a turtle is the dragon's shape. Its own sculpt and species, through the bridge.
     */
    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void theStarterPetDragonRidesACarrierThatCanFollow(GameTestHelper helper) {
        String id = "t6c4d_starter_dragon";
        plant(helper, id, "{}");   // the starter's own species.json, under this id...
        try {                      // ...and its own sculpt over the corpus one plant() wrote
            String sculpt;
            try (var in = SpeciesOnRigGameTest.class.getResourceAsStream(
                    "/starter-library/species/pet_dragon/sculpt.json")) {
                sculpt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            List<CreatureSculpt.Part> parts = CreatureSculpt.parseParts(
                    JsonParser.parseString(sculpt).getAsJsonObject().getAsJsonArray("parts"));
            CreatureFiles.write(id, parts, sculpt);
        } catch (Exception e) {
            helper.fail(Component.literal("could not install the starter pet dragon's sculpt: " + e));
            return;
        }
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(c.getType()).getPath();
            helper.assertTrue(!CarrierPool.CANNOT_FOLLOW.contains(type),
                    Component.literal("the starter pet dragon promises it follows you, and rides a " + type
                            + ", which cannot keep up (CarrierPool.CANNOT_FOLLOW)"));
        });
    }

    // ── rideable: every spawn of it can be ridden (2026-10-11) ──────────────────────────────

    /**
     * A species saved {@code rideable} — what his button re-spawns — rides a carrier that never
     * throws him off ({@link CarrierPool#CANNOT_RIDE}), he climbs on with an empty hand through the
     * real interaction event, and holding W carries him. Live: the T-Rex his button brought back
     * could not be ridden at all.
     */
    @GameTest(template = "empty_test", batch = "species_on_rig_ride", timeoutTicks = 600)
    public static void aRideableSpeciesIsRiddenEverySpawn(GameTestHelper helper) {
        String id = plant(helper, "rideable_rig", "{base:'wild', powers:['friendly','rideable'], speed:0.3}");
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-rideable-rig");
        wish(helper, id);
        boolean[] mounted = {false};
        double[] z0 = {0};
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(c.getType()).getPath();
            helper.assertTrue(!CarrierPool.CANNOT_RIDE.contains(type),
                    Component.literal("a rideable creature rides a " + type + ", which throws its rider off"));
            helper.assertTrue(Boolean.TRUE.equals(PetBehaviours.of(c).rideable()),
                    Component.literal("the species says rideable and the carrier is not: " + PetBehaviours.of(c)));
            if (!mounted[0]) {
                child.getInventory().clearContent();
                child.teleportTo(c.getX(), c.getY(), c.getZ() - 1);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player
                        .PlayerInteractEvent.EntityInteract(child, net.minecraft.world.InteractionHand.MAIN_HAND, c));
                helper.assertTrue(com.moddingfromamod.mob.RideSeat.mountOf(child) == c, Component.literal("an empty hand on a rideable creature did not climb on"));
                child.setYRot(0f);
                child.setXRot(0f);
                mounted[0] = true;
                z0[0] = c.getZ();
            }
            com.moddingfromamod.compat.RiderInput.hold(child, 1f, 0f);
            helper.assertTrue(com.moddingfromamod.mob.RideSeat.mountOf(child) == c, Component.literal("he was thrown off"));
            helper.assertTrue(c.getZ() > z0[0] + 3, Component.literal(String.format(
                    "holding W facing south, the creature went from z=%.2f to z=%.2f", z0[0], c.getZ())));
            child.stopRiding();
        });
    }

    // ── friendly: never takes a player as its target ─────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aFriendlyRigNeverTargetsAPlayer(GameTestHelper helper) {
        String id = plant(helper, "t6c4_friendly", "{base:'wild', powers:['friendly']}");
        ServerPlayer child = MockPlayerNetwork.inLevel(helper, "mfam-friendly");
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            c.setTarget(child);
            helper.assertTrue(c.getTarget() == null,
                    Component.literal("a friendly creature's carrier took the child as its target"));
            helper.assertTrue(PetBehaviours.of(c).follow() == null,
                    Component.literal("friendly alone must not make it follow"));
        });
    }

    // ── fireproof: fire costs it nothing ─────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aFireproofRigDoesNotBurn(GameTestHelper helper) {
        String id = plant(helper, "t6c4_fireproof", "{}");
        wish(helper, id);
        AtomicReference<Float> before = new AtomicReference<>();
        long[] lit = {-1};
        StringBuilder hurts = new StringBuilder();
        float[] was = {-1};
        helper.onEachTick(() -> {
            Mob c = carrier(helper, id);
            if (c != null) {
                if (was[0] >= 0 && c.getHealth() < was[0] && hurts.length() < 600) {
                    var src = c.getLastDamageSource();
                    hurts.append(String.format("[t%d -%.1f by %s at %s] ", helper.getTick(),
                            was[0] - c.getHealth(), src == null ? "?" : src.getMsgId(),
                            c.blockPosition().toShortString()));
                }
                was[0] = c.getHealth();
            }
            if (c != null && before.get() == null) {
                before.set(c.getHealth());
                c.igniteForTicks(200);
                lit[0] = helper.getTick();
            } else if (c != null && lit[0] >= 0 && c.getRemainingFireTicks() <= 0) {
                c.igniteForTicks(200);
            }
        });
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null && before.get() != null && lit[0] >= 0, Component.literal("not lit yet"));
            helper.assertTrue(helper.getTick() - lit[0] > 100, Component.literal("still watching it burn"));
            helper.assertTrue(c.hasEffect(MobEffects.FIRE_RESISTANCE),
                    Component.literal("a fireproof creature's carrier has no fire resistance"));
            helper.assertTrue(c.getHealth() >= before.get(), Component.literal("fire cost a fireproof creature "
                    + (before.get() - c.getHealth()) + " health" + lostCarrier(c, c) + " hurts: " + hurts));
        });
    }

    // ── glowing: the tag is on, so has something to mirror ──────────────────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void aGlowingRigGlows(GameTestHelper helper) {
        String id = plant(helper, "t6c4_glowing", "{powers:['friendly','glowing']}");
        wish(helper, id);
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            helper.assertTrue(c.hasGlowingTag(), Component.literal("a glowing creature's carrier is not glowing"));
        });
    }

    // ── the second birth point: a re-wish rebuilds the rig, and the species comes with it ──

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 600)
    public static void aReWishThatAddsGlowAndDropsFireproofReachesTheNewCarrier(GameTestHelper helper) {
        String id = plant(helper, "t6c4_rewish", "{}");
        wish(helper, id);
        AtomicReference<java.util.UUID> first = new AtomicReference<>();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (first.get() == null) {
                helper.assertTrue(c.hasEffect(MobEffects.FIRE_RESISTANCE) && !c.hasGlowingTag(),
                        Component.literal("the first carrier should be fireproof and not glowing"));
                first.set(c.getUUID());
                plant(helper, id, "{powers:['friendly','glowing']}");
            }
            // Rebuilt on a new carrier, or reshaped on this one: built from the new species.
            var built = VanillaRigAnimator.builtFrom(c.getUUID());
            helper.assertTrue(built != null && built.has(com.moddingfromamod.species.Species.Power.GLOWING),
                    Component.literal("the re-wish has not reached the creature yet"));
            helper.assertTrue(c.hasGlowingTag(), Component.literal("the rebuilt carrier lost the new glowing power"));
            helper.assertTrue(!c.hasEffect(MobEffects.FIRE_RESISTANCE),
                    Component.literal("the rebuilt carrier kept a fire resistance its species no longer has"));
        });
    }

    // ── the third: adoption after a reload, and the child's own word winning ─────────────────

    @GameTest(template = "empty_test", batch = BATCH, timeoutTicks = 400)
    public static void anAdoptedCompanionStillFollowsAndTheChildCanStopIt(GameTestHelper helper) {
        String id = plant(helper, "t6c4_adopt", "{}");
        wish(helper, id);
        helper.runAfterDelay(60, () -> {
            Mob c = carrier(helper, id);
            if (c == null) {
                helper.fail(Component.literal("no carrier 60 ticks after the wish"));
                return;
            }
            VanillaRigAnimator.forget(c.getUUID());
            VanillaRigAnimator.adoptAll(helper.getLevel());
            helper.assertTrue(PetBehaviours.of(c).follow() == Boolean.TRUE
                            && PetBehaviours.of(c).friendly() == Boolean.TRUE,
                    Component.literal("after adoption the companion no longer follows and is no longer friendly"));
            // "Stop following me": the child's word is stored on the carrier and wins over the
            // species, and must SURVIVE being stored (an explicit off used to be dropped).
            PetBehaviours.apply(c, new PetBehaviour(false, null, null, null), null);
            helper.assertTrue(PetBehaviours.of(c).follow() == Boolean.FALSE,
                    Component.literal("the child said stop following and the species default overrode him: "
                            + PetBehaviours.of(c).toStored()));
            helper.assertTrue(PetBehaviours.of(c).friendly() == Boolean.TRUE,
                    Component.literal("stopping the following must not make it unfriendly"));
            PetBehaviours.restore(c, PetBehaviours.stored(c));
            helper.assertTrue(PetBehaviours.of(c).follow() == Boolean.FALSE,
                    Component.literal("restoring the same stored state brought the following back"));
            helper.succeed();
        });
    }
}
