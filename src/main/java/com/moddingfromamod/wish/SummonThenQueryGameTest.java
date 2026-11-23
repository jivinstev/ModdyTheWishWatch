package com.moddingfromamod.wish;

import net.minecraft.network.chat.Component;
import com.moddingfromamod.ModdingFromAModMod;

import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Can a command see an entity the command before it just summoned?</b> —.
 *
 * <p>Observed in a real wish: the agent summoned a tagged zombie and the very next command reported
 * {@code No entity was found}. It retried, failed again, could not {@code kill} its failed attempts
 *, and the child ended up with THREE zombies where he asked for one.
 *
 * <p>Written to REPRODUCE before anything was fixed, because the mechanism was a guess — and the
 * guess was WRONG, which is the useful part. The theory was that {@code run_command} runs a whole
 * batch inside one server-thread hop, so summon-then-query happens within a single tick and the
 * entity is not yet visible. {@link #aCommandCanSeeWhatTheCommandBeforeItSummoned} passed on the
 * day it was written and has passed ever since: a NEARBY entity is queryable by the very next
 * command on the same tick.
 *
 * <p>What actually decides it is whether the target CHUNK is loaded. Measured side by side:
 *
 * <pre>
 *   near (chunk loaded)      summon ok    &#64;e[tag=…] FOUND
 *   far  (chunk NOT loaded)  summon ok    &#64;e[tag=…] "No entity was found"
 * </pre>
 *
 * <p>Nothing repairs the far case. Three treatments (untouched, {@code getChunk},
 * {@code setChunkForced}) and three delays (same tick, +2, +22) all leave it unreachable, and by
 * +2 the chunk has unloaded again. So the far test asserts that the agent is TOLD, which is the
 * only thing that was ever missing.
 *
 * <p>The nearby test is the control that refutes the timing story — delete it and the next person
 * to read "No entity was found" will go back to blaming the tick.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class SummonThenQueryGameTest {

    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "summonquery")
    public void aCommandCanSeeWhatTheCommandBeforeItSummoned(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        Vec3 where = Vec3.atBottomCenterOf(at);
        String tag = "mfam_sq_probe";

        // Exactly the shape the agent wrote: summon with a tag, then address it by that tag —
        // both inside ONE batch, which is one server-thread hop and therefore one tick.
        WishExecutor.Result summoned = WishExecutor.runOne(server, level, where, null,
                "summon minecraft:armor_stand " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " {Tags:[\"" + tag + "\"],NoGravity:1b,Invisible:1b}");
        helper.assertTrue(summoned.success(), Component.literal("the summon itself failed: " + summoned.joined()));

        WishExecutor.Result found = WishExecutor.runOne(server, level, where, null,
                "data get entity @e[tag=" + tag + ",limit=1] Tags");

        if (!found.success()) {
            helper.fail(Component.literal("#173 REPRODUCED — a command cannot see what the one before it summoned: "
                    + found.joined() + ". This is what made the agent summon three zombies."));
            return;
        }
        helper.succeed();
    }

    /**
     * <b>A summon nobody can address SAYS SO.</b>
     *
     * <p>There is no repair to assert here, which is the finding. Measured across three treatments
     * and three delays: untouched, {@code getChunk}, and {@code setChunkForced} all leave the
     * entity unreachable on the same tick, and two and twenty-two ticks later the chunk has
     * unloaded again and it is still unreachable. A chunk ticket is only processed on the next
     * chunk-manager tick, so a chunk cannot be raised to entity-ticking within the tick that
     * summons into it.
     *
     * <p>So the thing that was missing was never a mechanism — it was that nobody said anything.
     * The agent read {@code Ran, OK}, could not address what it had made, and summoned a second
     * zombie and then a third.
     */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "summonquery")
    public void aSummonNobodyCanAddressSaysSo(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2)).offset(15000, 0, 15000);
        Vec3 where = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));

        helper.assertFalse(level.isLoaded(at),
                Component.literal("this fixture only means something while that chunk is UNLOADED; if the world grew "
                        + "to reach it, move the offset further out rather than deleting the test"));

        WishExecutor.Result summoned = WishExecutor.runOne(server, level, where, null,
                "summon minecraft:armor_stand " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " {Tags:[\"mfam_sq_far\"],NoGravity:1b,Invisible:1b}");
        helper.assertTrue(summoned.success(),
                Component.literal("vanilla reports this as a success, which is the whole problem: " + summoned.joined()));
        helper.assertTrue(summoned.joined().contains(WishExecutor.UNREACHABLE_SUMMON),
                Component.literal("#173 — the summon succeeded into a chunk nothing keeps loaded and the agent was "
                        + "told nothing about it: " + summoned.joined()));
        helper.succeed();
    }

    /** And a NEARBY summon must stay quiet, or the warning is noise and gets skipped with the real ones. */
    @GameTest(template = "empty_test", timeoutTicks = 200, batch = "summonquery")
    public void aNearbySummonIsNotWarnedAbout(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        WishExecutor.Result summoned = WishExecutor.runOne(level.getServer(), level,
                Vec3.atBottomCenterOf(at), null,
                "summon minecraft:armor_stand " + at.getX() + " " + at.getY() + " " + at.getZ()
                        + " {Tags:[\"mfam_sq_near_quiet\"],NoGravity:1b,Invisible:1b}");
        helper.assertTrue(summoned.success(), Component.literal(summoned.joined()));
        helper.assertFalse(summoned.joined().contains(WishExecutor.UNREACHABLE_SUMMON),
                Component.literal("a perfectly addressable summon was warned about: " + summoned.joined()));
        helper.succeed();
    }
}
