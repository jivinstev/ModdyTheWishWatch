package com.moddingfromamod.wish;

import java.util.List;
import java.util.Optional;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.marshal.CancelMode;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Stopping a wish.</b> The whole guess-don't-ask policy is staked on this being one click.
 *
 * <p>The visible stop and the actual stop are deliberately decoupled, and only the visible one has
 * to be instant:
 *
 * <ul>
 *   <li><b>Within a tick</b>, server-side, with no agent involved: the slot and leases are released,
 *       the progress bar comes down, the sparkle beam goes out, any blocks still going up stop, and
 *       the child is told <i>which</i> of the three outcomes he got.</li>
 *   <li><b>Within one model turn</b> (~4s median): the worker discovers it on its next tool call and
 *       is <i>refused</i>, not merely informed — see
 *       {@link com.moddingfromamod.marshal.CancelGate}. Inference cannot be interrupted, so this is
 *       the floor, and it is invisible.</li>
 * </ul>
 *
 * <p>Three outcomes, and we always say which one happened. <b>Redirect matters most</b> for a wrong
 * "make it bigger": the child does not want the work cancelled, he wants it applied to the other
 * thing, so the primary affordance on a wrong guess is not a bare ✕.
 *
 * <p><b>Cancelling an edit never removes the thing being edited.</b> An undo reverses only what
 * <i>this wish</i> recorded making, so stopping "make Spikor bigger" leaves Spikor exactly as he
 * was; only stopping the wish that created something removes it.
 */
public final class WishCancel {

    /**
     * The book's result for a wish stopped only to be re-aimed. Read by the wish bank, which
     * gives the charge back silently there: the re-opened wish is charged at its pickup again.
     */
    public static final String REAIMED = "The child re-aimed this wish.";

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private WishCancel() {
    }

    /** What happened, so a command can report it and a test can assert it. */
    public record Outcome(boolean stopped, CancelMode mode, String wishShortId, int undone) {

        public static final Outcome NOTHING = new Outcome(false, null, "", 0);
    }

    /**
     * Stop one of this child's wishes.
     *
     * @param shortId    which wish, or blank for "whatever you are making for me right now"
     * @param mode       stop, undo or redirect
     * @param redirectTo for a redirect, what he actually meant
     */
    public static Outcome stop(ServerPlayer player, String shortId, CancelMode mode,
                               String redirectTo) {
        if (player == null) {
            return Outcome.NOTHING;
        }
        Marshal marshal = Marshal.global();
        Optional<WishRun> target = pick(marshal, player, shortId);
        if (target.isEmpty()) {
            // A served wish has no run, but while its pillar is still rising nothing has happened
            // yet, so it can be stopped like any other.
            Wish served = ServedFanfare.cancelFor(player);
            if (served != null) {
                WishFeedback.stopped(player, CancelMode.STOP, "");
                return new Outcome(true, CancelMode.STOP, served.shortId(), 0);
            }
            WishFeedback.nothingToStop(player);
            return Outcome.NOTHING;
        }
        WishRun run = target.get();

        // Decide the honest outcome BEFORE stopping: an undo the wish cannot actually deliver is
        // downgraded to a plain stop here rather than promised and then quietly not done.
        CancelMode asked = mode == null ? CancelMode.STOP : mode;

        // ── MODDY IS TIRED — a re-aim is a refinement too. Asked BEFORE the stop, because a
        // redirect stops the wish and then re-opens it: refusing at the re-open would leave the
        // child's wish stopped for asking to change it. So the wish carries on as it was, and he
        // is told Moddy is tired. A STOP or UNDO is not a refinement and is never refused.
        if (asked == CancelMode.REDIRECT) {
            long now = System.currentTimeMillis();
            var tired = WishService.tiredNow(now);
            if (tired.isPresent()) {
                WishFeedback.tired(player, tired.get());
                com.moddingfromamod.agent.ActionLog.wish(redirectTo, "TIRED", "REDIRECT",
                        run.shortId(), player.getStringUUID());
                com.moddingfromamod.health.WishSweeper.announceTiredness(now);
                LOGGER.info("/moddy redirect on {} not taken — Moddy is tired ({})",
                        run.shortId(), tired.get().cause().wire());
                return new Outcome(false, CancelMode.REDIRECT, run.shortId(), 0);
            }
        }
        List<String> used = run.toolsUsed();
        boolean canUndo = com.moddingfromamod.marshal.UndoContract.undoable(used);
        CancelMode actual = (asked == CancelMode.UNDO && !canUndo) ? CancelMode.STOP : asked;

        var result = marshal.cancel(run.wishId(), actual, "", redirectTo);
        if (!result.stopped()) {
            WishFeedback.nothingToStop(player);
            return Outcome.NOTHING;
        }

        // --- within a tick, no agent involved -------------------------------------------------
        Builder.cancelJobsFor(run.wishId());
        int undone = 0;
        int couldNot = 0;
        if (actual == CancelMode.UNDO) {
            boolean handedOver = com.moddingfromamod.item.delivery.WishDelivery.handedOver(
                    run.wishId().toString());
            WishUndo.Result reversal = WishUndo.undo(player.level().getServer(), run);
            undone = reversal.reversed();
            couldNot = reversal.couldNot().size();
            if (undone == 0 && (!run.made().isEmpty() || handedOver)) {
                // It OFFERED to put it back and could not. Say the outcome he actually got — an
                // undo that quietly does nothing is the lying button in its worst form, because
                // nothing on screen contradicts it.
                actual = CancelMode.STOP;
            }
        }
        clearHud(player.getUUID(), run.wishId(), marshal);

        WishBook book = WishBook.get(player.level().getServer());
        // ⚠ THE REDIRECT IS NOT QUOTED HERE. `reaim` below overwrites this result a few lines
        // later on the happy path — but it RETURNS EARLY when the safety floor refuses the
        // re-aim, and this line would then be the surviving record of the wish, holding the exact
        // words the floor just refused (§12.7's derived-text rule: nothing the grade did not pass
        // gets written anywhere an agent reads). The mode is the whole fact worth keeping.
        book.complete(run.wishId(), WishStatus.STOPPED,
                actual == CancelMode.REDIRECT ? REAIMED : "The child stopped this wish.");

        WishFeedback.stopped(player, actual, actual == CancelMode.UNDO && undone > 0
                ? "(" + undone + " thing(s) put back)" : "");
        if (asked == CancelMode.UNDO && actual == CancelMode.STOP) {
            // Say so out loud rather than silently doing less than was asked. He forgives "I
            // stopped"; he does not forgive being told it was put back when it wasn't.
            WishFeedback.howToTrigger(player,
                    "I couldn't put that one back, so I just stopped it.");
        } else if (couldNot > 0) {
            WishFeedback.howToTrigger(player,
                    "I couldn't put " + couldNot + " of them back, sorry!");
        }

        if (actual == CancelMode.REDIRECT) {
            reaim(player, run, redirectTo);
        }

        LOGGER.info("wish {} STOPPED by the child as {} (undone {})",
                run.shortId(), actual, undone);
        com.moddingfromamod.agent.ActionLog.wish(run.text(), "STOPPED", actual.name(), "",
                player.getStringUUID());
        return new Outcome(true, actual, run.shortId(), undone);
    }

    /**
     * Re-aim rather than abandon: the wish goes back to the agent carrying what he actually meant.
     *
     * <p>The work already done is not literally handed over — the worker is mid-turn and cannot be
     * reached — but the <b>wish</b> is kept, which is what he asked for. From his side one click
     * turned "you're making the wrong thing" into "now it's making the right thing", with no
     * retyping.
     */
    private static void reaim(ServerPlayer player, WishRun run, String redirectTo) {
        // ── CHILD SAFETY, BOTH LAYERS, ON THE ONE FREE TEXT NOBODY ELSE SAW (CS-S2, then CS-D2).
        //
        // `/moddy redirect <free text>` is the third re-wish path and the only one that puts NEW
        // words into the system without going through WishService.submit. L0 answers HERE, in
        // microseconds, because the unambiguous half of the acknowledged-then-blocked gap is worth
        // closing instantly (§12.3) and this method runs on the server thread, where PATTERNS.md
        // forbids waiting on a model.
        //
        // L1 answers at the DEQUEUE, and the reason it can is §12.7's decision: the re-aim becomes
        // part of the wish TEXT below, so `AwaitWishTool.gradeInput` -- which grades `wish.text()`
        // and is memoised per text -- sees a new string, takes a new grade, and enforces it with
        // the whole INPUT column. Nothing here has to wait, and the note no longer carries a word
        // the grade did not read.
        if (com.moddingfromamod.safety.ChildSafetyConfig.enforcing()) {
            var lexicon = com.moddingfromamod.safety.SafetyLexicon.shared().check(redirectTo);
            if (lexicon.isPresent() && lexicon.get().overall()
                    == com.moddingfromamod.safety.SafetyVerdict.Band.CLEARLY) {
                com.moddingfromamod.agent.ActionLog.safety(run.shortId(), "input", lexicon.get(),
                        com.moddingfromamod.safety.SafetyInputGate.Act.BLOCK.name(), 0L, 0.0,
                        "lexicon", "", "", 0, false, false, player.getStringUUID());
                com.moddingfromamod.agent.ActionLog.wish(redirectTo, "BLOCKED", "REDIRECT",
                        run.shortId(), player.getStringUUID());
                // CHAT, because he has just been told the wish stopped and is watching that line.
                // The wish stays stopped: it already is, and re-opening it to say no would put a
                // bar back up for something that is not going to be built.
                WishFeedback.blockedAfterThinking(player);
                return;
            }
        }
        WishBook book = WishBook.get(player.level().getServer());
        Wish before = book.byId(run.wishId()).orElse(null);
        if (before == null) {
            return;
        }
        // #81: the same route decision a fresh wish gets. A player the Magician no longer answers has the re-aimed
        // words routed as a fresh wish (the Apprentice's, in practice); the stopped wish stays stopped and is never
        // put back in line for a worker.
        if (!MagicianDoor.answersNow(player.getServer(), player.getUUID())) {
            org.slf4j.LoggerFactory.getLogger(WishCancel.class).info(
                    "wish {}: re-aimed, routed as a fresh wish: the Magician no longer answers its player (#81)", run.shortId());
            WishService.submit(player, WishText.reaimed(before.text(), redirectTo), WishSource.TYPED);
            return;
        }
        // THE RE-AIM BECOMES THE WISH (§12.7). Both halves are kept — the original says what to do
        // and the re-aim says which thing — and the whole string is what gets graded, named on the
        // bar, handed to the agent and written to the journal. See WishText.reaimed for why the
        // original is the half that gets cut if they will not both fit.
        String reaimedText = WishText.reaimed(before.text(), redirectTo);
        // START THE GRADE NOW, fire-and-forget, exactly as WishService.submit does and for the same
        // reason: the new text is a new memo key, so without this the dequeue point would be the
        // first thing to ask for it and a child would wait the whole ~10 s with nothing overlapping
        // it. Nothing here blocks — the future is completed on a daemon thread and is only awaited
        // on the MCP handler thread in AwaitWishTool.
        com.moddingfromamod.safety.SafetyGrader.gradeInput(reaimedText);
        // THE NOTE NOW QUOTES NOTHING. It used to repeat the re-aim verbatim, which was the one
        // string in the wake text that no grade had read; pointing at the wish's own words instead
        // means every character the agent is handed is inside the graded text. CS-S2's lesson was
        // that replacing the text is not enough while anything DERIVED from the old text survives
        // beside it — this is the same rule applied in the other direction.
        String note = "The child said this wish was aimed at the WRONG thing. The \""
                + WishText.REAIM_JOINER.strip()
                + "\" part of the wish above is what he actually means. Do the same wish, on that "
                + "instead. Do not re-do the part you already got right, and do not ask him which "
                + "one he meant — he has just told you.";
        Wish reopened = book.reaim(run.wishId(), reaimedText, note).orElse(null);
        if (reopened == null) {
            return;
        }
        // A FRESH RUN, and `submit` is what makes that true rather than this comment (CS-D7). It
        // re-opens a wish whose run has finished, and it has to: the stopped one is STOPPED, so
        // `assign` refuses it, `liveFor` excludes it, and a re-aimed wish still pointing at it is
        // one the child cannot stop -- which is the one control everything below promises him.
        var fresh = Marshal.global().submit(reopened.id(), player.getUUID(), reopened.text());
        if (fresh != null) {
            fresh.subject(redirectTo == null ? "" : redirectTo.strip());
            fresh.defaultAnchor(run.anchor());
        }
        WishProgress.started(player, reopened);
        // GIVE IT ITS BAR BACK. A redirect keeps the same wish id, so clearHud above has just taken
        // that wish's bar down — and nothing here would ever put one back, leaving a wish that is
        // genuinely still being granted with nothing on screen. The child re-aimed it; he did not
        // stop it.
        WishProgress.startThinking(player, reopened.id(),
                fresh == null ? reopened.text() : fresh.displayName());
        com.moddingfromamod.agent.WishQueue.global().offer(reopened);
    }

    /**
     * Take down the bar for the wish he stopped — and only that one.
     *
     * <p>The whole HUD (beams, status line, anchor) is per-player and only comes down when there is
     * nothing left in flight. The progress BAR is per-wish, so stopping one must leave the others
     * alone: a child who stops his dragon and watches his castle's bar vanish has been told, as far
     * as he can tell, that we stopped the castle too.
     *
     * <p>Takes the wish id rather than reaching for "all of his", so clearing the wrong number of
     * bars is not something this method is able to do. It briefly was — {@code clearAllFor} was
     * called here, which is the exact failure the note above describes.
     */
    // PUBLIC so the safety layer's plan-time stop (SafetyPlanGate.endWish) can end a wish
    // the same way this one does. The alternative was a second copy of a two-branch rule
    // that decides how many bars come down, which is precisely the rule the note above
    // records getting wrong once already.
    public static void clearHud(java.util.UUID playerId, java.util.UUID wishId, Marshal marshal) {
        if (marshal.liveFor(playerId).isEmpty()) {
            WishProgress.forget(playerId);   // nothing left: the whole HUD goes
        } else {
            WishProgress.clearWishBar(wishId);  // just this one's bar
        }
    }

    /**
     * Which wish he means. A blank id is "whatever you are making for me right now", because a
     * seven-year-old clicking {@code [stop]} has not read an id and should never have to.
     */
    static Optional<WishRun> pick(Marshal marshal, ServerPlayer player, String shortId) {
        List<WishRun> mine = marshal.liveFor(player.getUUID());
        if (shortId != null && !shortId.isBlank()) {
            String wanted = shortId.trim();
            for (WishRun r : mine) {
                if (r.shortId().equalsIgnoreCase(wanted)) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }
        // Newest first: the one he is most likely looking at is the one he just made.
        return mine.isEmpty() ? Optional.empty() : Optional.of(mine.get(mine.size() - 1));
    }
}
