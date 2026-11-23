package com.moddingfromamod.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.function.Predicate;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.wish.Wish;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JOIN behind "too special to save" —, driven through the real seam.
 *
 * <p>{@code OneOffWish} is pure and has its own tests. What this covers is the part those cannot:
 * that {@code FinishWishTool} asks the question at all, asks it of the RUN's own {@code made()},
 * and only for a wish that came true. A rule tested only where it is defined is not tested where it
 * runs, and the case that prompted the issue was found by reading a world save by hand.
 */
class OneOffWiringTest {

    private final String worker = "test-oneoff-" + UUID.randomUUID();
    private UUID open;

    @org.junit.jupiter.api.io.TempDir
    Path tmp;

    /** Never $HOME: Gate A is hermetic, and a unit test that appends to a real log is not. */
    private Path log() {
        return tmp.resolve("one-off-wishes.jsonl");
    }

    private static final Predicate<String> ON_DISK = id -> true;
    private static final Predicate<String> MISSING = id -> false;

    @AfterEach
    void release() {
        if (open != null) {
            Marshal.global().finish(open);
        }
        com.moddingfromamod.agent.AgentContext.unbind();
    }

    /** A live run with whatever it made recorded on it, exactly as the tool will find it. */
    private com.moddingfromamod.marshal.WishRun runThatMade(String kind, String id) {
        open = UUID.randomUUID();
        com.moddingfromamod.agent.AgentContext.bind(worker);
        Marshal.global().submit(open, UUID.randomUUID(), "make spikor shoot fireballs");
        Marshal.global().assign(worker, open);
        var run = Marshal.global().run(open);
        if (kind != null) {
            run.made(kind, id);
        }
        return run;
    }

    private static Wish wish() {
        return Wish.create("make spikor shoot fireballs", com.moddingfromamod.wish.WishSource.TYPED,
                1L, UUID.randomUUID(), "steve", "minecraft:overworld", 0, 64, 0);
    }

    @Test
    @DisplayName("a granted wish that made nothing is recorded — and the LINE really lands")
    void handBuiltIsRecorded() throws Exception {
        assertTrue(FinishWishTool.recordIfOneOff(wish(), runThatMade(null, null), true, ON_DISK,
                log()));
        String written = Files.readString(log());
        assertTrue(written.contains("make spikor shoot fireballs"), written);
        assertTrue(written.endsWith("\n"), "JSONL lines must be newline-terminated: " + written);
    }

    @Test
    @DisplayName("a granted wish that made a datapack ON DISK is not")
    void aRealDatapackIsNot() {
        assertFalse(FinishWishTool.recordIfOneOff(
                wish(), runThatMade(MadeKind.DATAPACK, "freeze_bomb"), true, ON_DISK, log()));
    }

    @Test
    @DisplayName("THE SPIKOR FIREBALL: a datapack id whose zip was never written IS recorded")
    void theFailedCompileIsRecorded() {
        // The join is the whole point: the run says it made a datapack, and only the store knows
        // the compile failed and nothing was written.
        assertTrue(FinishWishTool.recordIfOneOff(
                wish(), runThatMade(MadeKind.DATAPACK, "spikor_fireball"), true, MISSING, log()));
    }

    @Test
    @DisplayName("a wish that did NOT come true is a failure, not a one-off")
    void notGrantedIsNotOneOff() {
        assertFalse(FinishWishTool.recordIfOneOff(wish(), runThatMade(null, null), false, ON_DISK, log()));
    }

    @Test
    @DisplayName("no run at all does not throw")
    void noRunIsSafe() {
        assertTrue(FinishWishTool.recordIfOneOff(wish(), null, true, ON_DISK, log()));
    }

    // ──: the one-time "teach it" nudge, and silence for a help wish ────────────────────

    @Test
    @DisplayName("#1271: a granted, untaught wish of guard-safe commands gets the draft")
    void untaughtCommandWishIsAskedToTeach() {
        var run = runThatMade(null, null);
        for (int i = 0; i < 20; i++) {
            run.ranCommand("summon creeper ~ ~ ~");
        }
        run.usedTool("run_command");
        String r = FinishWishTool.teachingRefusal(wish(), run, true, ON_DISK);
        assertTrue(r != null && r.contains("\"repeat\":20") && r.contains("summon creeper ~ ~ ~"),
                String.valueOf(r));
        assertFalse(FinishWishTool.isHelp(wish(), run));
    }

    @Test
    @DisplayName("#1271: never asked when taught, not granted, help, a recipe was left, or the guard refuses")
    void theNudgeHasItsLimits() {
        var run = runThatMade(null, null);
        run.ranCommand("effect give @s night_vision 999999 0 true");
        run.usedTool("run_command");
        assertNull(FinishWishTool.teachingRefusal(wish(), run, false, ON_DISK), "not granted");
        run.markFollowup();
        assertNull(FinishWishTool.teachingRefusal(wish(), run, true, ON_DISK), "a follow-up is help");
        release();

        var bad = runThatMade(null, null);
        bad.ranCommand("kill @e[type=creeper]");
        assertNull(FinishWishTool.teachingRefusal(wish(), bad, true, ON_DISK), "guard refuses kill");
        release();

        var built = runThatMade(MadeKind.BUILD, "castle");
        built.ranCommand("give @s diamond");
        assertNull(FinishWishTool.teachingRefusal(wish(), built, true, ON_DISK), "it left a recipe");
        release();

        var taught = runThatMade(null, null);
        taught.ranCommand("give @s diamond");
        taught.usedTool("add_template");
        assertNull(FinishWishTool.teachingRefusal(wish(), taught, true, ON_DISK), "already taught");
    }

    @Test
    @DisplayName("#1271: a run that changed nothing is help — recorded, but not told 'can't save it'")
    void noChangeIsHelpAndRecordedAsSuch() throws Exception {
        var run = runThatMade(null, null);
        assertTrue(FinishWishTool.isHelp(wish(), run));
        assertTrue(FinishWishTool.recordIfOneOff(wish(), run, true, ON_DISK, log()));
        assertTrue(Files.readString(log()).contains("\"help\":true"), Files.readString(log()));
    }
}
