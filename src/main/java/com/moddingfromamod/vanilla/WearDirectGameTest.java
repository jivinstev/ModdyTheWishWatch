package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.EntityNbt;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * <b>: setting a display's item directly leaves it exactly as the old whole-entity round trip
 * did</b> — the oracle for {@link DisplayRig#wear}.
 *
 * <p>Real rig displays (with their transformation and a light override written by the rig) are
 * cloned twice; one clone is recoloured by {@link DisplayRig#wearByRoundTrip} (the pre-#1869 path),
 * the other by {@link DisplayRig#wear}, over a matrix of models and tints including none, a hurt
 * flash, white and black. The two clones' WHOLE saves must be equal — not just {@code item} — so a
 * side effect the old path had by accident shows up here as a difference instead of on a child's
 * screen. Own batch: {@code wearWrites} is static.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class WearDirectGameTest {

    private static final BlockPos SPOT = new BlockPos(4, 0, 4);

    private static DisplayRig rig(GameTestHelper helper, String key) {
        BlockPos p = helper.absolutePos(SPOT);
        for (int i = 0; i < 4 && !helper.getLevel().getBlockState(p).isAir(); i++) {
            p = p.above();
        }
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), Vec3.atBottomCenterOf(p), 0f,
                RigFitGameTest.whale(), 1.0, key, CarrierPool.Move.WALK);
        rig.base().setNoAi(true);
        return rig;
    }

    /** A detached copy of a display, as it is saved: never added to the world. */
    private static Entity cloneOf(ServerLevel level, Entity d) {
        Entity c = EntityType.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        CompoundTag t = EntityNbt.save(d);
        EntityNbt.load(c, t);
        return c;
    }

    private static CompoundTag comparable(Entity e) {
        CompoundTag t = EntityNbt.save(e);
        t.remove("UUID");
        return t;
    }

    @GameTest(template = "empty_test", batch = "wear_direct", timeoutTicks = 100)
    public static void theDirectItemEqualsTheRoundTripOverModelsAndTints(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DisplayRig rig = rig(helper, "gt_wear_direct");
        DisplayRig.writeLight(rig.displays().get(0), new RigLight(7, 3));   // a field the round trip re-read
        List<Integer> models = new ArrayList<>();
        for (int i = 0; i < Math.min(3, rig.groups().size()); i++) {
            models.add(AtomLibrary.modelDataFor(rig.groups().get(i)));
        }
        int own = AtomLibrary.colourFor(rig.groups().get(0));
        int[] tints = {-1, RigTint.NONE, RigTint.multiply(own, 0xFF6060), 0xFFFFFF, 0x000000, own};
        int compared = 0;
        for (int i = 0; i < models.size(); i++) {
            Entity source = rig.displays().get(i);
            for (int tint : tints) {
                Entity viaTrip = cloneOf(level, source);
                Entity direct = cloneOf(level, source);
                DisplayRig.wearByRoundTrip(viaTrip, DisplayRig.carrierItem(models.get(i), tint));
                DisplayRig.wear(direct, models.get(i), tint);
                CompoundTag a = comparable(viaTrip);
                CompoundTag b = comparable(direct);
                helper.assertTrue(a.equals(b), Component.literal("model " + models.get(i) + " tint "
                        + Integer.toHexString(tint) + ": the direct item differs from the round trip\n"
                        + " round trip: " + a + "\n direct:     " + b));
                viaTrip.discard();
                direct.discard();
                compared++;
            }
        }
        helper.assertTrue(compared >= tints.length, Component.literal("compared only " + compared));
        // What one recolour costs each way (logged, not asserted: a GameTest box is not a quiet box).
        Entity a = cloneOf(level, rig.displays().get(0));
        Entity b = cloneOf(level, rig.displays().get(0));
        int m = models.get(0);
        for (int warm = 0; warm < 200; warm++) {
            DisplayRig.wearByRoundTrip(a, DisplayRig.carrierItem(m, warm));
            DisplayRig.wear(b, m, warm);
        }
        long t0 = System.nanoTime();
        for (int k = 0; k < 2000; k++) {
            DisplayRig.wearByRoundTrip(a, DisplayRig.carrierItem(m, k));
        }
        long t1 = System.nanoTime();
        for (int k = 0; k < 2000; k++) {
            DisplayRig.wear(b, m, k);
        }
        long t2 = System.nanoTime();
        org.slf4j.LoggerFactory.getLogger("mfam-wear").info(
                "wear cost per display: round trip {} us, direct {} us",
                String.format(java.util.Locale.ROOT, "%.1f", (t1 - t0) / 2000e3),
                String.format(java.util.Locale.ROOT, "%.1f", (t2 - t1) / 2000e3));
        a.discard();
        b.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "wear_direct", timeoutTicks = 100)
    public static void smoothMotionByItsFieldEqualsTheRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DisplayRig rig = rig(helper, "gt_smooth_direct");
        Entity source = rig.displays().get(0);
        for (int start : new int[] {0, 1, DisplayRig.MOTION_TICKS, 10}) {
            Entity viaTrip = cloneOf(level, source);
            Entity direct = cloneOf(level, source);
            for (Entity e : List.of(viaTrip, direct)) {
                CompoundTag t = EntityNbt.save(e);
                t.putInt("teleport_duration", start);
                EntityNbt.load(e, t);
            }
            DisplayRig.smoothMotionByRoundTrip(viaTrip);
            DisplayRig.smoothMotion(direct);
            CompoundTag a = comparable(viaTrip);
            CompoundTag b = comparable(direct);
            helper.assertTrue(a.equals(b), Component.literal("teleport_duration from " + start
                    + ": the direct write differs from the round trip\n round trip: " + a + "\n direct:     " + b));
            helper.assertTrue(b.getIntOr("teleport_duration", -1) == DisplayRig.MOTION_TICKS,
                    Component.literal("from " + start + " it is " + b.getIntOr("teleport_duration", -1)));
            viaTrip.discard();
            direct.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "wear_direct", timeoutTicks = 100)
    public static void aDisplayAlreadyWearingTheItemIsNotRewritten(GameTestHelper helper) {
        DisplayRig rig = rig(helper, "gt_wear_skip");
        Entity d = rig.displays().get(0);
        int model = AtomLibrary.modelDataFor(rig.groups().get(0));
        DisplayRig.wear(d, model, 0x123456);
        long before = DisplayRig.wearWrites;
        DisplayRig.wear(d, model, 0x123456);
        helper.assertTrue(DisplayRig.wearWrites == before,
                Component.literal("an unchanged item was written again"));
        DisplayRig.wear(d, model, 0x654321);
        helper.assertTrue(DisplayRig.wearWrites == before + 1,
                Component.literal("a changed colour was not written"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "wear_direct", timeoutTicks = 100)
    public static void aHurtFlashAndBackRestoresEveryDisplaysOwnColour(GameTestHelper helper) {
        DisplayRig rig = rig(helper, "gt_wear_flash");
        List<CompoundTag> rest = new ArrayList<>();
        for (Entity d : rig.displays()) {
            rest.add(EntityNbt.save(d).getCompoundOrEmpty("item"));
        }
        int written = rig.tint(RigTint.multiply(0xFFFFFF, 0xFF6060));
        helper.assertTrue(written == rig.displays().size(), Component.literal("the flash wrote " + written
                + " of " + rig.displays().size()));
        boolean changed = !EntityNbt.save(rig.displays().get(0)).getCompoundOrEmpty("item").equals(rest.get(0));
        helper.assertTrue(changed, Component.literal("the flash did not change the display's item"));
        rig.tint(RigTint.NONE);
        for (int i = 0; i < rig.displays().size(); i++) {
            CompoundTag now = EntityNbt.save(rig.displays().get(i)).getCompoundOrEmpty("item");
            helper.assertTrue(now.equals(rest.get(i)), Component.literal("display " + i
                    + " did not come back to its own colour: " + now + " vs " + rest.get(i)));
        }
        helper.succeed();
    }
}
