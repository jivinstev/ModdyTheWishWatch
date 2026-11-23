package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.carrier;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.plant;
import static com.moddingfromamod.vanilla.SpeciesOnRigGameTest.wish;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.runtime.ModelRepository;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>An edit changes the creature on the carrier it already has</b>. Through the real
 * animator: the creature stands, its sculpt is rewritten as {@code sculpt_creature} does, and the
 * next tick's re-wish check either reshapes it in place or, when the carrier must change, rebuilds
 * it. Three edits, because they take three paths:
 * <ul>
 *   <li>a repaint: the same carrier and the SAME display entities, eased to their new colours;</li>
 *   <li>fewer parts: the same carrier, a new set of displays;</li>
 *   <li>it now flies: a carrier that can fly, which is a rebuild.</li>
 * </ul>
 * The last two are TRANSFORMATIONS — the old body whitens and shrinks away while the
 * new one grows out of white and colours in — and two more tests here prove that through the real
 * path: the white was shown, the old body is gone afterwards, and the carrier is free again.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class ReshapeInPlaceGameTest {

    /** Rewrite this creature's sculpt (its {@code sculpt.json} parts), as sculpt_creature does. */
    private static void resculpt(GameTestHelper helper, String id, UnaryOperator<JsonArray> edit) {
        try {
            var f = ModelRepository.dir(id).resolve("sculpt.json");
            JsonObject spec = JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
            JsonArray parts = edit.apply(spec.getAsJsonArray("parts").deepCopy());
            spec.add("parts", parts);
            CreatureFiles.write(id, CreatureSculpt.parseParts(parts), spec.toString());
            ModelRepository.clearCache();
        } catch (Exception e) {
            helper.fail(Component.literal("could not rewrite the sculpt: " + e));
        }
    }

    private static Set<UUID> displays(GameTestHelper helper, Mob carrier) {
        String own = DisplayRig.RIG_OWNER_PREFIX + carrier.getUUID();
        Set<UUID> out = new HashSet<>();
        for (Entity e : helper.getLevel().getEntities((Entity) null,
                carrier.getBoundingBox().inflate(32), e -> e instanceof Display && e.getTags().contains(own))) {
            out.add(e.getUUID());
        }
        return out;
    }

    private record Before(UUID carrier, Set<UUID> displays, String type) {
    }

    /**
     * Stand the creature, then edit it once it is driven; succeed when {@code check} holds of the
     * creature standing afterwards.
     */
    private static void drive(GameTestHelper helper, String id, String extra,
                              Runnable edit, java.util.function.BiConsumer<Before, Mob> check) {
        plant(helper, id, extra);
        wish(helper, id);
        AtomicReference<Before> before = new AtomicReference<>();
        helper.succeedWhen(() -> {
            Mob c = carrier(helper, id);
            helper.assertTrue(c != null, Component.literal("no carrier yet"));
            if (before.get() == null) {
                helper.assertTrue(VanillaRigAnimator.isDriving(c), Component.literal("not driven yet"));
                before.set(new Before(c.getUUID(), displays(helper, c), c.getType().toString()));
                edit.run();
                helper.fail(Component.literal("edited; waiting for the re-wish check"));
            }
            check.accept(before.get(), c);
        });
    }

    @GameTest(template = "empty_test", batch = "reshape_repaint", timeoutTicks = 400)
    public static void aRepaintKeepsTheCarrierAndItsDisplays(GameTestHelper helper) {
        String id = "gt_reshape_paint_" + helper.getLevel().getGameTime();
        drive(helper, id, "{\"powers\":[]}", () -> {
            plant(helper, id, "{\"powers\":[]}");   // the registry moves, as a sculpt's put does
            resculpt(helper, id, parts -> {
                for (JsonElement p : parts) {
                    p.getAsJsonObject().addProperty("colour", "#AA3355");
                }
                return parts;
            });
        }, (was, c) -> {
            helper.assertTrue(c.getUUID().equals(was.carrier()),
                    Component.literal("a repaint replaced the carrier: it blinked out and back"));
            Set<UUID> now = displays(helper, c);
            helper.assertTrue(now.equals(was.displays()),
                    Component.literal("a repaint replaced the displays rather than recolouring them (" + now.size()
                            + " now, " + was.displays().size() + " before, "
                            + now.stream().filter(was.displays()::contains).count() + " kept)"));
            // EVERY display, never one picked out of a HashSet: which one came first depended on the
            // UUIDs, and a display that does not wear the body colour failed the 2026-10-22 release
            // with this message while the repaint was fine.
            String colour = Integer.toString(0xAA3355);
            long recoloured = now.stream().map(helper.getLevel()::getEntity)
                    .filter(e -> e != null && com.moddingfromamod.compat.EntityNbt.save(e)
                            .toString().contains(colour))
                    .count();
            helper.assertTrue(recoloured * 2 >= now.size(), Component.literal("the displays were kept but not "
                    + "recoloured (" + recoloured + " of " + now.size() + " wear the new colour)"));
        });
    }

    @GameTest(template = "empty_test", batch = "reshape_fewer", timeoutTicks = 400)
    public static void fewerPartsKeepTheCarrierWithNewDisplays(GameTestHelper helper) {
        String id = "gt_reshape_fewer_" + helper.getLevel().getGameTime();
        UnaryOperator<JsonArray> dropLast = parts -> {
            JsonArray out = new JsonArray();
            for (int i = 0; i < parts.size() - 1; i++) {
                out.add(parts.get(i));
            }
            return out;
        };
        drive(helper, id, "{\"powers\":[]}", () -> {
            plant(helper, id, "{\"powers\":[]}");
            resculpt(helper, id, dropLast);
        }, (was, c) -> {
            Set<UUID> now = displays(helper, c);
            helper.assertTrue(!now.isEmpty() && !now.equals(was.displays()),
                    Component.literal("waiting for the new shape's displays"));
            helper.assertTrue(c.getUUID().equals(was.carrier()),
                    Component.literal("a part taken off replaced the carrier: it blinked out and back"));
            helper.assertTrue(now.stream().noneMatch(was.displays()::contains),
                    Component.literal("the old shape's displays are still standing beside the new ones"));
        });
    }

    @GameTest(template = "empty_test", batch = "reshape_flies", timeoutTicks = 400)
    public static void aCreatureThatNowFliesGetsACarrierThatCan(GameTestHelper helper) {
        String id = "gt_reshape_flies_" + helper.getLevel().getGameTime();
        drive(helper, id, "{\"powers\":[]}", () -> plant(helper, id, "{\"powers\":[\"flies\"]}"),
                (was, c) -> {
                    helper.assertTrue(!c.getUUID().equals(was.carrier()),
                            Component.literal("waiting for the flying carrier (still " + was.type() + ")"));
                    helper.assertTrue(CarrierPool.movementOf(
                                    com.moddingfromamod.species.SpeciesRegistry.get(id), java.util.List.of())
                                    == CarrierPool.Move.FLY,
                            Component.literal("the species does not fly, so this tested nothing"));
                    helper.assertTrue(!c.getType().toString().equals(was.type()),
                            Component.literal("it flies now and still rides " + was.type()));
                });
    }

    // ──: the transformation ───────────────────────────────────────────────────────────────

    private static final String WHITE = Integer.toString(0xFFFFFF);

    /** Every carrier of this species near the test, alive or not yet removed. */
    private static java.util.List<Mob> carriers(GameTestHelper helper, String id) {
        String tag = com.moddingfromamod.entity.WishMobTags.forSpecies(id);
        java.util.List<Mob> out = new java.util.ArrayList<>();
        for (Entity e : helper.getLevel().getEntities((Entity) null,
                new net.minecraft.world.phys.AABB(helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2))).inflate(48),
                e -> e.getTags().contains(DisplayRig.RIG_TAG) && e.getTags().contains(tag))) {
            if (e instanceof Mob m && !m.isRemoved()) {
                out.add(m);
            }
        }
        return out;
    }

    private static long wearing(GameTestHelper helper, Set<UUID> ids, String rgb) {
        return ids.stream().map(helper.getLevel()::getEntity)
                .filter(e -> e != null && com.moddingfromamod.compat.EntityNbt.save(e)
                        .toString().contains("\"minecraft:dyed_color\":" + rgb))   // 1.21.5: a bare int, no {rgb:}
                .count();
    }

    private static long leavingNear(GameTestHelper helper, Mob c) {
        return helper.getLevel().getEntities((Entity) null, c.getBoundingBox().inflate(32),
                e -> e.getTags().contains(DisplayRig.LEAVING_TAG)).size();
    }

    @GameTest(template = "empty_test", batch = "reshape_transform_parts", timeoutTicks = 500)
    public static void aPartCountChangeTransformsThroughWhite(GameTestHelper helper) {
        String id = "gt_transform_parts_" + helper.getLevel().getGameTime();
        UnaryOperator<JsonArray> dropLast = parts -> {
            JsonArray out = new JsonArray();
            for (int i = 0; i < parts.size() - 1; i++) {
                out.add(parts.get(i));
            }
            return out;
        };
        java.util.concurrent.atomic.AtomicBoolean sawWhite = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean sawBoth = new java.util.concurrent.atomic.AtomicBoolean();
        drive(helper, id, "{\"powers\":[]}", () -> {
            plant(helper, id, "{\"powers\":[]}");
            resculpt(helper, id, dropLast);
        }, (was, c) -> {
            Set<UUID> now = displays(helper, c);
            if (wearing(helper, now, WHITE) * 2 >= now.size() && !now.equals(was.displays())) {
                sawWhite.set(true);
            }
            if (leavingNear(helper, c) > 0) {
                sawBoth.set(true);   // the old set still fading while the new one grows
            }
            helper.assertTrue(sawWhite.get(), Component.literal("waiting for the white phase"));
            helper.assertTrue(sawBoth.get(), Component.literal("the old set was never kept to fade out -- it popped"));
            helper.assertTrue(!RigTransformation.transforming(c.getUUID()), Component.literal("still transforming"));
            helper.assertTrue(c.getUUID().equals(was.carrier()), Component.literal("a part count change replaced the carrier"));
            helper.assertTrue(leavingNear(helper, c) == 0, Component.literal("the old set is still standing after the transformation"));
            helper.assertTrue(now.stream().noneMatch(was.displays()::contains), Component.literal("an old display survived"));
            helper.assertTrue(wearing(helper, now, WHITE) * 2 < now.size(),
                    Component.literal("it never coloured back in (" + wearing(helper, now, WHITE) + " of " + now.size() + " white)"));
            helper.assertTrue(!c.isNoAi() && !c.isInvulnerable(), Component.literal("the carrier was left frozen"));
            helper.assertTrue(VanillaRigAnimator.isDriving(c), Component.literal("the animator never took the carrier back"));
        });
    }

    @GameTest(template = "empty_test", batch = "reshape_transform_flies", timeoutTicks = 500)
    public static void aNewCarrierTransformsThroughWhite(GameTestHelper helper) {
        String id = "gt_transform_flies_" + helper.getLevel().getGameTime();
        java.util.concurrent.atomic.AtomicBoolean sawWhiteOld = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean sawTwo = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<Before> first = new AtomicReference<>();
        AtomicReference<net.minecraft.core.BlockPos> home = new AtomicReference<>();
        drive(helper, id, "{\"powers\":[]}", () -> {
                    // seated on a spot keeps him there (SpotSeat.remember): the new carrier must keep
                    // that leash, or a boss rebuilt on his throne strolls off it (2026-10-25)
                    Mob seated = carrier(helper, id);
                    home.set(seated.blockPosition());
                    seated.setHomeTo(home.get(), SpotSeat.HOME_RADIUS);
                    plant(helper, id, "{\"powers\":[\"flies\"]}");
                },
                (was, c0) -> {
                    first.set(was);
                    var all = carriers(helper, id);
                    if (all.size() >= 2) {
                        sawTwo.set(true);   // old and new side by side, crossing over
                    }
                    for (Mob m : all) {
                        if (m.getUUID().equals(was.carrier())) {
                            Set<UUID> d = displays(helper, m);
                            if (!d.isEmpty() && wearing(helper, d, WHITE) * 2 >= d.size()) {
                                sawWhiteOld.set(true);
                            }
                        }
                    }
                    helper.assertTrue(sawWhiteOld.get(), Component.literal("waiting for the old body to go white"));
                    helper.assertTrue(sawTwo.get(), Component.literal("the new carrier never stood beside the old one -- it popped"));
                    helper.assertTrue(all.size() == 1, Component.literal("waiting for the old carrier to go (" + all.size() + " now)"));
                    Mob c = all.get(0);
                    helper.assertTrue(!c.getUUID().equals(was.carrier()), Component.literal("still the old carrier"));
                    // a frozen bat carrier is born RESTING and sank through the floor (an earlier issue's film)
                    double floor = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)).getY();
                    helper.assertTrue(Math.abs(c.getY() - floor) < 4,
                            Component.literal("the new carrier is not where the creature stood: y " + c.getY() + " against " + floor));
                    helper.assertTrue(!RigTransformation.transforming(c.getUUID()), Component.literal("still transforming"));
                    Set<UUID> now = displays(helper, c);
                    helper.assertTrue(wearing(helper, now, WHITE) * 2 < now.size(), Component.literal("it never coloured back in"));
                    helper.assertTrue(!c.isNoAi() && !c.isInvulnerable(), Component.literal("the new carrier was left frozen"));
                    helper.assertTrue(VanillaRigAnimator.isDriving(c), Component.literal("the new carrier is not driven"));
                    helper.assertTrue(helper.getLevel().getEntity(was.carrier()) == null
                            || helper.getLevel().getEntity(was.carrier()).isRemoved(), Component.literal("the old carrier is still in the world"));
                    helper.assertTrue(leavingNear(helper, c) == 0, Component.literal("something tagged leaving is still standing"));
                    helper.assertTrue(c.hasHome() && c.getHomePosition().equals(home.get()),
                            Component.literal("the new carrier lost the old one's home (" + (c.hasHome()
                                    ? c.getHomePosition().toShortString() : "none") + ", was "
                                    + home.get().toShortString() + ")"));
                });
    }
}
