package com.moddingfromamod.vanilla;

import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * <b>A creature's hit plan is saved with it, and a saved plan is used only when it was made from
 * exactly what the creature is now</b> (the join stall, 2026-11-08: planning was ~57% of a load).
 *
 * <p>Each test drives the real {@link DisplayRig#adopt} — the load path — rather than
 * {@link HitProxies#recall} alone. To tell "reused the save" from "planned again" without shared
 * counters (tests in a batch run together), the saved plan is MARKED: its {@code tried} is set to
 * a number no planner produces, and the adopted rig's plan says which path it came from.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class HitPlanSavedGameTest {

    private static final BlockPos SPOT = new BlockPos(4, 0, 4);
    private static final int MARK = 9999;

    private static List<CreatureSculpt.Part> whale() {
        return RigFitGameTest.whale();
    }

    private static DisplayRig spawn(GameTestHelper helper, double dx, String key) {
        BlockPos p = helper.absolutePos(SPOT);
        for (int i = 0; i < 4 && !helper.getLevel().getBlockState(p).isAir(); i++) {
            p = p.above();
        }
        DisplayRig rig = DisplayRig.spawn(helper.getLevel(), Vec3.atBottomCenterOf(p).add(dx, 0, 0), 0f,
                whale(), 1.0, key, CarrierPool.Move.WALK);
        rig.base().setNoAi(true);
        return rig;
    }

    private static CompoundTag saved(Mob carrier) {
        return carrier.getPersistentData().getCompoundOrEmpty(HitProxies.SAVED);
    }

    /** What a restart does: the proxies are gone (never loaded from a save); the carrier stays. */
    private static DisplayRig reload(GameTestHelper helper, DisplayRig rig) {
        rig.discardProxies();
        return DisplayRig.adopt(helper.getLevel(), rig.base(), whale(), 1.0, CarrierPool.Move.WALK);
    }

    @GameTest(template = "empty_test", batch = "hitplan_saved", timeoutTicks = 100)
    public static void aSpawnedRigSavesItsPlanAndComesBackWithIt(GameTestHelper helper) {
        DisplayRig rig = spawn(helper, 0, "gt_hitplan_saved");
        helper.assertTrue(rig.hitPlan() != null, Component.literal("the whale was given no plan"));
        CompoundTag t = saved(rig.base());
        helper.assertFalse(t.isEmpty(), Component.literal("a spawned rig saved no hit plan on its carrier"));
        // the real entity save carries it (NeoForge writes persistent data with the entity)
        CompoundTag whole = com.moddingfromamod.compat.EntityNbt.save(rig.base());
        helper.assertTrue(whole.getCompoundOrEmpty("NeoForgeData").contains(HitProxies.SAVED),
                Component.literal("the carrier's save does not carry the plan: " + whole.keySet()));
        helper.assertTrue(rig.hitPlan().equals(HitProxies.decode(t)),
                Component.literal("the saved plan does not read back as the plan: " + HitProxies.decode(t)
                        + " vs " + rig.hitPlan()));

        t.putInt("tried", MARK);
        DisplayRig back = reload(helper, rig);
        helper.assertTrue(back != null, Component.literal("the rig could not be adopted"));
        helper.assertTrue(back.hitPlan() != null && back.hitPlan().tried() == MARK,
                Component.literal("adoption planned again instead of using the saved plan: " + back.hitPlan()));
        helper.assertTrue(back.proxies().size() == rig.hitPlan().proxies().size(),
                Component.literal("the adopted rig has " + back.proxies().size() + " proxies, the plan "
                        + rig.hitPlan().proxies().size()));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "hitplan_saved", timeoutTicks = 100)
    public static void aPlanSavedFromOtherInputsIsPlannedAgain(GameTestHelper helper) {
        DisplayRig rig = spawn(helper, 3, "gt_hitplan_stale");
        CompoundTag t = saved(rig.base());
        // an older planner, another shape or another carrier all show up as a different key
        String good = t.getStringOr("key", "");
        t.putString("key", "0".repeat(good.length()));
        t.putInt("tried", MARK);
        DisplayRig back = reload(helper, rig);
        helper.assertTrue(back != null && back.hitPlan() != null,
                Component.literal("the rig could not be adopted"));
        helper.assertFalse(back.hitPlan().tried() == MARK,
                Component.literal("a plan saved from other inputs was used"));
        helper.assertTrue(saved(rig.base()).getStringOr("key", "").equals(good),
                Component.literal("the re-planned rig did not save its new plan under the right key"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "hitplan_saved", timeoutTicks = 100)
    public static void aChangedShapeOrScaleDoesNotMatchTheSave(GameTestHelper helper) {
        DisplayRig rig = spawn(helper, -3, "gt_hitplan_shape");
        var cands = DisplayRig.candidates(helper.getLevel());
        helper.assertTrue(HitProxies.recall(rig.base(), RigFit.atoms(rig.groups(), rig.scale()), cands) != null,
                Component.literal("the save does not match the very rig that wrote it"));
        helper.assertTrue(HitProxies.recall(rig.base(), RigFit.atoms(rig.groups(), 2.0), cands) == null,
                Component.literal("a plan saved at scale 1 was offered for scale 2"));
        List<RigFit.Atom> fewer = RigFit.atoms(rig.groups().subList(1, rig.groups().size()), rig.scale());
        helper.assertTrue(HitProxies.recall(rig.base(), fewer, cands) == null,
                Component.literal("a plan saved for one shape was offered for another"));
        helper.succeed();
    }

    @GameTest(template = "empty_test", batch = "hitplan_saved", timeoutTicks = 100)
    public static void anUnreadableSaveIsPlannedAgainNotThrown(GameTestHelper helper) {
        DisplayRig rig = spawn(helper, 6, "gt_hitplan_bad");
        CompoundTag t = saved(rig.base());
        net.minecraft.nbt.ListTag many = new net.minecraft.nbt.ListTag();
        for (int i = 0; i <= HitProxyPlan.MAX_PROXIES; i++) {
            CompoundTag q = new CompoundTag();
            q.putString("id", "cow");
            many.add(q);
        }
        t.put("proxies", many);   // more proxies than any plan may have
        t.putInt("tried", MARK);
        DisplayRig back = reload(helper, rig);
        helper.assertTrue(back != null && back.hitPlan() != null && back.hitPlan().tried() != MARK,
                Component.literal("an impossible saved plan was used: " + (back == null ? null : back.hitPlan())));
        helper.succeed();
    }
}
