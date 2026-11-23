package com.moddingfromamod.wish;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * What the wisher sees and hears. Deliberately generous: for a 7-year-old the feedback <em>is</em>
 * the feature — a wish that works silently feels broken, and a wish that's still thinking needs to
 * say so or he'll mash the button.
 *
 * <p>Wording is kept short and read-aloud friendly; the watch speaks like a friendly genie rather
 * than a build log.
 */
public final class WishFeedback {

    private WishFeedback() {
    }

    // ── Every line a child is sent here is also WRITTEN DOWN ─────────────────────────────
    // One `said` record per line (ActionLog.said), naming the method that sent it, so a trace can
    // show what the child actually read. Every send in this class goes through these two; a direct
    // player.sendSystemMessage here is a line no trace can see (WishFeedbackSaidTest pins it).

    private static void tell(ServerPlayer player, Component line) {
        if (heldBack(line)) {
            return;
        }
        player.sendSystemMessage(line);
        record(player, line, false);
    }

    private static void show(ServerPlayer player, Component line, boolean overlay) {
        if (heldBack(line)) {
            return;
        }
        player.displayClientMessage(line, overlay);
        record(player, line, overlay);
    }

    // ──: THE AGENT'S MACHINERY IS NEVER SAID TO HIM ─────────────────────────────────────
    // McpProtocol blanks a child-visible field that names a tool or narrates the agent's steps
    // before the tool runs (AgentInternals.scrub). This is the backstop for a line that reaches
    // here another way: the agent's words are swapped for Moddy's own default (agentWords), and any
    // other line that still carries one is not sent at all.

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);

    private static boolean heldBack(Component line) {
        String hit = com.moddingfromamod.safety.AgentInternals.found(line.getString());
        if (hit == null) {
            return false;
        }
        LOGGER.warn("[internals] a line naming \"{}\" was not sent to the child: {}", hit, line.getString());
        return true;
    }

    /** The agent's words for him, or "" when they are its machinery — the caller's default then speaks. */
    static String agentWords(String say) {
        return say == null || com.moddingfromamod.safety.AgentInternals.found(say) != null ? "" : say;
    }

    private static void record(ServerPlayer player, Component line, boolean overlay) {
        try {
            java.util.List<String> frames = StackWalker.getInstance().walk(st -> st.limit(12)
                    .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                            + "." + f.getMethodName())
                    .toList());
            com.moddingfromamod.agent.ActionLog.said(wishFor(player), player.getUUID().toString(),
                    SaidSite.describe(frames), line.getString(), overlay);
        } catch (RuntimeException | LinkageError e) {
            // Logging must never cost the child the line itself, which has already been sent.
        }
    }

    /**
     * The one wish this line is about: the worker's bound run when it is this player's, else this
     * player's single live run. Two live runs for one player and nothing bound is "" — the same
     * refusal to guess {@code AgentContext.currentRun} makes. The server thread (where
     * finish_building announces) has no binding, which is why the player is the second key.
     */
    private static String wishFor(ServerPlayer player) {
        java.util.UUID id = player.getUUID();
        String scoped = ABOUT.get();
        if (scoped != null) {
            return scoped;
        }
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        String bound = run != null && id.equals(run.playerId()) ? run.shortId() : null;
        var marshal = com.moddingfromamod.marshal.Marshal.global();
        java.util.List<String> working = marshal == null ? java.util.List.of()
                : marshal.working().stream().filter(r -> id.equals(r.playerId()))
                        .map(com.moddingfromamod.marshal.WishRun::shortId).toList();
        return choose(null, bound, working);
    }

    // ──: WHICH wish a line is about, when the sender knows ─────────────────────
    // WishService.submit speaks BEFORE its new wish is a run, so the fallback above found the
    // PREVIOUS wish (his single live run) and stamped the new wish's "let me think" with the old
    // wish's id — or none when two were live, and the trace then guessed. A sender that knows its
    // wish says so with about(); it holds for this thread until aboutNone().
    private static final ThreadLocal<String> ABOUT = new ThreadLocal<>();

    /** The lines this thread sends from now on are about {@code wishShortId}. */
    public static void about(String wishShortId) {
        if (wishShortId == null || wishShortId.isBlank()) {
            ABOUT.remove();
        } else {
            ABOUT.set(wishShortId);
        }
    }

    public static void aboutNone() {
        ABOUT.remove();
    }

    /**
     * The id a line is stamped with: the sender's own scope, else the worker's bound run, else the
     * player's single live run — and "" when that is ambiguous, never a guess.
     */
    static String choose(String scoped, String bound, java.util.List<String> working) {
        if (scoped != null && !scoped.isBlank()) {
            return scoped;
        }
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        return working.size() == 1 ? working.get(0) : "";
    }

    /** Wish accepted and being worked on right now. */
    public static void accepted(ServerPlayer player, String wishText) {
        show(player, 
                Component.literal("✨ Making your wish come true...")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)), true);
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7f, 1.4f);
    }

    /** Wish fulfilled. */
    public static void granted(ServerPlayer player, String say) {
        say = agentWords(say);
        String text = say == null || say.isBlank() ? "Your wish came true!" : say;
        tell(player, Component.literal("✨ " + text)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)));
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.6f);
        sparkle(player);
    }

    /**
     * A wish that came true and cannot be saved —.
     *
     * <p>Deliberately NOT the failure styling. The wish worked and he is watching it work; the only
     * thing being said is that it will not be here tomorrow, and it is said as "special", not as
     * "broken". Sent before the grant line so the grant is still the last thing he reads.
     */
    public static void tooSpecialToSave(ServerPlayer player, String say) {
        tell(player, Component.literal("✨ " + say)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * The wand gave a first wave, not the whole ask — said as what happened ("my wand holds
     * 50 at a time … wish 'more'"), never as a failure and never as a crash.
     */
    public static void wave(ServerPlayer player, String line) {
        tell(player, Component.literal("\u2728 " + line)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * A creature of his was moved somewhere safe, far enough that he could lose it — where,
     * and why, in {@code SafeSpot.told}'s words: <i>"Your dragon is just above — the spot you picked
     * was inside the rock!"</i>
     */
    public static void moved(ServerPlayer player, String line) {
        if (player == null || line == null || line.isBlank()) {
            return;
        }
        tell(player, Component.literal("\u2728 " + line)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /** Some of it worked, some didn't — say so honestly rather than claiming success. */
    public static void partial(ServerPlayer player, String say) {
        tell(player, Component.literal("✨ " + (say == null || say.isBlank()
                        ? "I did most of that one!" : say) + " (some bits didn't work)")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.5f, 0.8f);
    }

    /**
     * The watch can't do this one on its own, so it has gone to the agent.
     *
     * <p>This used to say "I don't know how to do that one yet", which was true when escalation
     * meant <i>maybe someday</i>. It now means <i>being worked on this second</i>, and the old
     * wording read as a refusal: a child asked for a giant airplane, got this line, and then sat
     * through 88 seconds of silence while one was actually being built for him. The sound changed
     * too — VILLAGER_NO is literally the "no" noise.
     *
     * <p>And then it said "Ooh, that's a tricky one! Let me think really hard" to every wish
     * (50 of 50 in a week) — a judgement about difficulty nobody had made. Now it is one of
     * {@link WishWaitWords#OPENERS}, in turn per child, and claims nothing about the wish.
     */
    public static void notYet(ServerPlayer player) {
        int n = OPENER_TURN.computeIfAbsent(player.getUUID(),
                k -> new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement();
        tell(player, Component.literal(WishWaitWords.opener(n))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6f, 1.0f);
    }

    /** Whose turn of {@link WishWaitWords#OPENERS} is next, per child — rotated, never random. */
    private static final java.util.Map<java.util.UUID, java.util.concurrent.atomic.AtomicInteger>
            OPENER_TURN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * <b>"See the sparkles? That's where it'll appear!"</b> — once per wish, when
     * {@code conjure_at} has marked the spot and the wish is a thing that appears in the world
     * ({@link WishStage#saySparkles}). The beam was already there; nothing pointed at it.
     */
    public static void sparklesHere(ServerPlayer player) {
        if (player == null) {
            return;
        }
        tell(player, Component.literal(WishWaitWords.SPARKLES)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * Moddy was asleep and is waking up for this wish (step 4).
     *
     * <p>The pool is stopped after twenty quiet minutes even while children play, so the first
     * wish after that waits ~10-13 s for a worker to start. Without a word that wait reads as the
     * ordinary "thinking" and is fine; Moddy says it once, so a longer first pause is
     * explained rather than guessed at. Sent only when the wish really waits
     * ({@code IdlePolicy.wakeSaysSo}) — never on a wish a parked worker takes at once.
     */
    public static void wakingUp(ServerPlayer player) {
        tell(player, Component.literal("😴 Moddy was having a nap — waking up now...")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /**
     * He asked a question rather than for a thing.
     *
     * <p>Worth its own wording. {@link #notYet} promises a <i>build</i> — "Moddy's on it" sets a
     * child watching the world for something to appear. A child who asked "how do I
     * use it?" and is told that will read the answer, when it comes, as the wrong reply to the
     * wrong question. This promises an answer instead.
     */
    public static void thinkingAboutQuestion(ServerPlayer player) {
        tell(player, Component.literal("🤔 Good question! Let me have a look...")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6f, 1.2f);
    }

    /**
     * The child said a wish came out wrong.
     *
     * <p>Tone matters as much as the mechanism here. He has just told us we got it wrong, and the
     * reply has to sound grateful rather than defensive — if being honest feels like being told
     * off, he stops doing it, and his verdict is the best quality signal the project has.
     *
     * <p>Since it is an UNDO line, not "I'll try again": the wish was taken away and, on a
     * world with a bank, given back — {@code 👍 Gone! I took it away and gave you your wish back —
     * ✨ 7/10.}
     *
     * @param tookAway true when something was actually taken away
     * @param refunded the bank after the refund ({@code ✨ 7/10}), or null when nothing came back
     */
    public static void rejected(ServerPlayer player, boolean tookAway, String refunded) {
        tell(player, Component.literal("👍 " + WishRejection.sayToChild(tookAway, refunded))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.5f, 1.0f);
    }

    /** A second 👎 on the wish he has just undone: nothing more happens. */
    public static void alreadyUndone(ServerPlayer player) {
        tell(player, Component.literal("👍 " + WishRejection.alreadyUndone())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /** Nothing recent enough to undo. */
    public static void nothingToUndo(ServerPlayer player) {
        tell(player, Component.literal("🤔 " + WishRejection.nothingToUndo())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /**
     * A grown-up is servicing the server, so this wish was not started.
     *
     * <p>The action bar, like {@link #tooFast} and {@link #blockedBeforeThinking}: nothing has been
     * acknowledged yet, so there is no promise in chat for this to contradict, and an instant
     * "not now" is exactly what the bar is for. The words are {@link com.moddingfromamod.health.OpsWords}'.
     */
    public static void tidyingUp(ServerPlayer player) {
        show(player, 
                Component.literal(com.moddingfromamod.health.OpsWords.sayDraining())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), true);
    }

    /**
     * Nobody ever picked this wish up, or it ran past its ceiling.
     *
     * <p><b>Chat, not the action bar</b>, and for {@link #blockedAfterThinking}'s reason: he was
     * told "Moddy's on it" ({@link #notYet}) two minutes ago and has been watching a bar ever since. A
     * reply that lands only on the action bar leaves that sentence as the last word — a promise
     * nothing ever answers, which is the exact experience this whole timeout exists to end.
     *
     * <p>No sound. A chime over "that one is not coming" is worse than silence, which is the rule
     * {@code SafetyWords} records for every refusal.
     */
    public static void fellAsleep(ServerPlayer player) {
        tell(player, 
                Component.literal(com.moddingfromamod.health.OpsWords.sayTimedOut())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /**
     * Moddy is tired: a NEW wish was not started. <b>The action bar</b>, like
     * {@link #tidyingUp}: it lands before any acknowledgement, so there is no opener in
     * chat for it to contradict.
     */
    public static void tired(ServerPlayer player) {
        tired(player, null);
    }

    /**
     * The same, in the words for THIS tiredness: the daily spend cap says when new wishes
     * come back ({@link com.moddingfromamod.health.OpsWords#sayTired(com.moddingfromamod.health.ModdyTired.Tired, long)});
     * every other cause says the ordinary tired line.
     */
    public static void tired(ServerPlayer player, com.moddingfromamod.health.ModdyTired.Tired why) {
        show(player,
                Component.literal(com.moddingfromamod.health.OpsWords.sayTired(why, System.currentTimeMillis()))
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), true);
    }

    /**
     * The same, found out at the DEQUEUE — the grade for this wish never came back. <b>Chat</b>,
     * for {@link #blockedAfterThinking}'s reason: he has already read {@link #notYet}'s opener.
     */
    public static void tiredAfterThinking(ServerPlayer player) {
        tell(player, 
                Component.literal(com.moddingfromamod.health.OpsWords.sayTired())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /** Too many wishes too fast. */
    public static void tooFast(ServerPlayer player) {
        show(player, Component.literal("😴 Moddy needs a little rest! Try again in a moment.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), true);
    }

    /**
     * The child-safety floor refused this one <b>before</b> the wish was acknowledged.
     *
     * <p>{@code docs/CHILD_SAFETY_PLAN.md} §5: the action bar, because at this point he has NOT yet
     * been told "Moddy's on it" ({@link #notYet}) — there is nothing in chat for this to contradict, and
     * the bar is where every other instant answer already lives ({@link #tooFast},
     * {@link #didntCatchThat}). The words are {@link com.moddingfromamod.safety.SafetyWords}', so
     * all of them are in one reviewable place and under Gate A.
     *
     * <p>No sound. Every other refusal here got its "no" noise removed for the reason that file
     * records, and a refusal is the one case where a cheerful chime would be worse than silence.
     */
    public static void blockedBeforeThinking(ServerPlayer player) {
        show(player, 
                Component.literal(com.moddingfromamod.safety.SafetyWords.sayBlocked())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)), true);
    }

    /**
     * The same refusal, <b>after</b> he has already been told the watch is thinking about it.
     *
     * <p>Chat rather than the action bar, and that is not a style choice: {@link #notYet} has just
     * put "✨ Got your wish! Moddy's on it…" (or another of its openers) in his chat, and a reply
     * that lands only on the action bar would leave that sentence as the last word — a promise
     * nothing ever answers. §12.3 is about how long this gap is (measured 8–25 s); this is about
     * it ending somewhere he will actually look.
     */
    public static void blockedAfterThinking(ServerPlayer player) {
        tell(player, 
                Component.literal(com.moddingfromamod.safety.SafetyWords.sayBlocked())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)));
    }

    /**
     * <b>This world's rules shaped the wish</b>: a refusal, a split or a clamp, in
     * {@code WorldRulesWords}' one pattern — heard you, I know how, the rule and why, where it works,
     * something to do instead. Chat, like the safety block: he has already read the opener.
     */
    /**
     * A wish-bank line: what is left, when the next one comes, a refund, the welcome. Gold,
     * the colour of the ✨ it counts. Every such line is a fixed template in {@code WishBankWords},
     * swept by the lexicon in Gate A ({@code WishBankWordsTest}).
     */
    public static void bank(ServerPlayer player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        tell(player,
                Component.literal(message).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
    }

    public static void worldRule(ServerPlayer player, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        tell(player,
                Component.literal(message).setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * What will be made instead, said <b>before</b> the agent starts.
     *
     * <p>The whole of §2's "nothing is ever quietly substituted": a redirect the child does not read
     * is a substitution, and the difference between the two is this one chat line. It keeps its
     * chime — unlike the block, this IS good news, something is about to be built.
     */
    public static void redirected(ServerPlayer player, String redirect) {
        tell(player, 
                Component.literal(com.moddingfromamod.safety.SafetyWords.sayRedirect(redirect))
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6f, 1.2f);
    }

    /**
     * <b>He said something about himself, and the watch heard it</b> (§12.1, decision 1B).
     *
     * <p>Chat rather than the action bar, and this is the one place in the safety layer where that
     * choice is not about timing. The action bar is for instant, disposable answers — the rate
     * limiter, "I didn't catch that", the pre-acknowledgement block — and it vanishes in a few
     * seconds whether or not anybody read it. This line is the one message here that is worth
     * leaving on screen, because it is the only one aimed at the child rather than at the wish.
     *
     * <p><b>No sound at all.</b> The redirect keeps its chime because it is good news about a
     * build that is starting; the block has none because a cheerful noise over a refusal is worse
     * than silence. This one has none for a third reason: a chime would make a kind word feel like
     * an achievement popping, and it is not an event — it is somebody noticing.
     *
     * <p>Not bold, unlike {@link #blockedAfterThinking}. That one has to compete with a promise
     * already in chat; this one arrives beside an ordinary acknowledgement and shouting it would
     * turn "I heard you" into "LOOK WHAT YOU SAID".
     */
    public static void wellbeingNoticed(ServerPlayer player) {
        tell(player, 
                Component.literal(com.moddingfromamod.safety.SafetyWords.sayWellbeingNoticed())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
    }

    /**
     * The grader could not run and this machine is configured {@code closed} (§8).
     *
     * <p>Deliberately the "Moddy is resting" register {@link #tooFast} already uses: an outage is a
     * temporary state of the world, not a judgement about the wish, and a child who cannot hear the
     * difference learns the wrong lesson from a flaky subprocess.
     */
    public static void genieResting(ServerPlayer player) {
        tell(player, 
                Component.literal(com.moddingfromamod.safety.SafetyWords.sayUnavailable())
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /** Empty / meaningless wish text. */
    public static void didntCatchThat(ServerPlayer player) {
        show(player, Component.literal("👂 I didn't catch that — try saying it again!")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)), true);
    }

    /**
     * Moddy says what it is about to do, all of it, once.
     *
     * <p>The progress bar only ever shows the CURRENT step, so a child watching it cannot tell
     * whether he is one step from finished or five. Saying the whole plan out loud costs one chat
     * line and answers the question he actually has, which is not "what are you doing" but "how much
     * longer".
     */
    public static void planned(ServerPlayer player, java.util.List<String> steps) {
        planned(player, steps, "");
    }

    /** The same, naming what the agent said the wish is about ({@link WishPlan#line}). */
    public static void planned(ServerPlayer player, java.util.List<String> steps, String aboutName) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        tell(player, Component.literal(WishPlan.line(steps, aboutName))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * <b>The one thing worth telling him about his claims: a bit that is broken</b>.
     *
     * <p>Once a checklist ("Here's what works:" and a ✔/✘/"I tried" per claim); in live play it was
     * all "I tried" and read as noise, so it is gone. A broken bit is still told — he would find it
     * in ten seconds anyway, and being told first keeps him trusting the next sentence — as ONE
     * sentence ({@link WishClaims#brokenSentence()}), and nothing at all when everything passed.
     */
    public static void brokenClaims(ServerPlayer player, String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return;
        }
        tell(player, Component.literal(sentence)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * The build is finished: a complete thing now exists in the child's world, and he is told so.
     *
     * <p>This beat did not exist, and its absence was the loudest complaint about the old flow.
     * Spikor appeared breathing fire, it looked finished, and then Moddy silently kept working for
     * another nine minutes. Nothing marked "this is my first version" — so a working wish read as a
     * hung one.
     */
    public static void built(ServerPlayer player, String say) {
        built(player, say, false);
    }

    /**
     * The same, <b>celebrated once per wish</b>. A second {@code finish_building} inside
     * the SAME wish is legitimate — the prompt's one exception is fixing a creature that came out
     * frightening — but it is "look again", not a second "it's done!": plain aqua, no level-up,
     * no sparkles.
     */
    public static void built(ServerPlayer player, String say, boolean alreadyCelebrated) {
        say = agentWords(say);
        String text = say == null || say.isBlank() ? "Here's my first try — have a look!" : say;
        if (alreadyCelebrated) {
            tell(player, Component.literal("✨ " + text)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
            return;
        }
        tell(player, Component.literal("✨ " + text)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)));
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.5f);
        sparkle(player);
    }

    /** What Moddy says at the reveal, before anybody has looked at the photograph. */
    public static final String REVEAL_SHORT = "It's here — have a look!";

    /**
     * What Moddy says when the end comes for a creature he has already been watching shimmer into
     * shape (the early reveal): it is not arriving, it is FINISHED.
     */
    public static final String FINISHED_SHORT = "All done — it's finished! Have a look!";

    /** The line at the early reveal. Said by the mod, so it never depends on the agent remembering. */
    public static final String APPEARING =
            "Look, it's starting to appear! The rainbow shimmer means I'm still wishing it into shape.";

    /** {@link #changing}: one of HIS creatures is being changed, not a new one arriving. */
    public static final String CHANGING = "The rainbow shimmer means I'm still working on it!";

    /** The line {@link #changing} says, for a creature called {@code name} ("" when unnamed). Pure. */
    public static String changingLine(String name) {
        String n = name == null ? "" : name.replaceAll("§.", "").strip();
        return (n.isEmpty() ? "Changing it! " : "Changing your " + n + "! ") + CHANGING;
    }

    /**
     * <b>His creature is being changed, shimmering</b> — the early reveal of an EDIT (owner,
     * 2026-10-22). "It's starting to appear" was said of a dragon he had owned for days.
     */
    public static void changing(ServerPlayer player, String name) {
        tell(player, Component.literal("✨ " + changingLine(name))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    /** Once per wish, when he is watching it and the shape itself is about to change. */
    public static final String RESHAPING = "Trying that bit again, so it might wiggle!";

    /**
     * <b>His creature has just appeared, shimmering</b> — the early reveal. Soft:
     * a chime, not the level-up. The one celebration is still the END, when the shimmer stops.
     */
    public static void appearing(ServerPlayer player) {
        appearing(player, "");
    }

    /**
     * The appearing line for a creature called {@code name}: a wish that makes two things
     * says which one is starting to appear, and a wish that makes one says its name. Pure.
     */
    public static String appearingLine(String name) {
        String n = name == null ? "" : name.replaceAll("§.", "").strip();
        return n.isEmpty() ? APPEARING
                : "Look, your " + n + " is starting to appear! The rainbow shimmer means I'm still "
                        + "wishing it into shape.";
    }

    /** {@link #appearing(ServerPlayer)}, naming the creature. */
    public static void appearing(ServerPlayer player, String name) {
        tell(player, Component.literal("✨ " + appearingLine(name))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)));
        player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    /** The shape he is watching is being redone. Said once per wish ({@code WishEarlyReveal}). */
    public static void reshaping(ServerPlayer player) {
        tell(player, Component.literal("🔧 " + RESHAPING)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /**
     * <b>What it IS, said after the agent has judged the photograph</b>.
     *
     * <p>The reveal used to carry the whole description, written before the photo was taken — and
     * described the plan: a spiral track nobody laid, a carwash with brushes standing underwater.
     * Now the reveal is the short {@link #REVEAL_SHORT} (the one celebration) and this line
     * follows a few seconds later from {@code finish_wish}'s {@code say}, naming what the picture
     * shows. Plain aqua, no sound: it is the description, not a second "it's done!".
     */
    public static void described(ServerPlayer player, String say) {
        say = agentWords(say);
        if (say == null || say.isBlank()) {
            return;
        }
        tell(player, Component.literal("✨ " + say.strip())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        // The third of the wish's three sounds: it appeared (a chime), it is
        // finished (the level-up), and now Moddy's verdict is in chat. The description lands 10-20 s
        // after "All done", when he is already playing with the thing, so a quiet bell turns his
        // eyes to the chat. Softer than both: it is a tap on the shoulder, not a celebration.
        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.35f, 1.5f);
    }

    /**
     * Said just after "All done", small and grey: the photograph that follows is Moddy checking his
     * own work, and it should not look like the wish is still going.
     */
    public static final String CLOSER_LOOK = "🔍 I'm having one last peek to make sure it came out just right…";

    public static void closerLook(ServerPlayer player) {
        tell(player, Component.literal(CLOSER_LOOK)
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true)));
    }

    /** The line {@link #polished} says ("" = no name: "it"). Pure. */
    public static String polishedLine(String name) {
        String n = name == null ? "" : name.replaceAll("§.", "").strip();
        return "✨ I gave " + (n.isEmpty() ? "it" : "your " + n) + " a little polish.";
    }

    /**
     * <b>Moddy changed his thing in place after the second look</b>: quiet,
     * like {@link #closerLook}, and said once per wish.
     */
    public static void polished(ServerPlayer player, String name) {
        tell(player, Component.literal(polishedLine(name))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(true)));
    }

    /**
     * <b>How to set the new ability off</b>, derived from what was actually built.
     *
     * <p>A separate line from the excited one on purpose. The agent's sentence is its voice and
     * should stay that way; this one is a fact about the artifact, and it is the line the child
     * follows when nothing seems to be happening. Silent when nothing was recorded — an invented
     * instruction is worse than none, because a child who follows it and sees nothing concludes the
     * toy is broken rather than the sentence wrong.
     */
    public static void howToTrigger(ServerPlayer player, String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return;
        }
        tell(player, Component.literal("   👉 " + sentence.strip())
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
    }

    /** The one follow-up line a granted wish ends on. */
    static final String WISH_AGAIN = "   Want to change it? Just wish again! 🪄";

    /** Verdict DONE: it's good. Celebrate, and invite the next wish. */
    public static void verdictDone(ServerPlayer player, String say) {
        verdictDone(player, say, false);
    }

    /**
     * Verdict DONE — <b>celebrated once</b>.
     *
     * <p>{@link #built} is the reveal: the bold line, the level-up sound and the sparkles, at the
     * moment he is told to look. This used to follow it with a SECOND bold line, a second level-up
     * and a second burst of sparkles, so every grant said "it's done!" twice by design, and in live
     * play it read as a confirmation sent twice for no reason. When the wish has
     * already been celebrated, a DONE adds only the invitation to change it; the agent's
     * {@code note} is still recorded (the journal, the handover) but not read out a second time.
     *
     * <p>A wish that ended WITHOUT {@code finish_building} ({@code BuildGate} gives way once) has
     * had no celebration, so it gets this one — the bold line is never lost, only never doubled.
     */
    public static void verdictDone(ServerPlayer player, String say, boolean alreadyCelebrated) {
        verdictDone(player, say, alreadyCelebrated, true);
    }

    /**
     * The same, with <b>at most one follow-up line</b>. A wish that taught a button has
     * just told him "Ta-da! I'll remember your … — tap its button" ({@code LearnedTemplates}), which
     * IS the follow-up; "Want to change it? Just wish again!" on top of it is a second one.
     */
    public static void verdictDone(ServerPlayer player, String say, boolean alreadyCelebrated,
                                   boolean invite) {
        if (!alreadyCelebrated) {
            granted(player, say);
        }
        if (invite) {
            tell(player, Component.literal(WISH_AGAIN)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        }
    }

    /**
     * Verdict COULD_BE_BETTER: good, but Moddy can see an improvement. It offers the follow-up as a
     * ready-made wish so the child only has to press, not find the words — which is a lot to ask of
     * a 7-year-old who can see something is off but cannot say why.
     */
    public static void verdictCouldBeBetter(ServerPlayer player, String say, String suggestion) {
        verdictCouldBeBetter(player, say, suggestion, false);
    }

    /**
     * The same, <b>celebrated once</b>: after {@link #built} the note is said in plain aqua
     * with no second level-up and no second sparkle — it is Moddy's opinion, not a second "done!".
     */
    public static void verdictCouldBeBetter(ServerPlayer player, String say, String suggestion,
                                            boolean alreadyCelebrated) {
        say = agentWords(say);
        suggestion = agentWords(suggestion);
        Style voice = alreadyCelebrated
                ? Style.EMPTY.withColor(ChatFormatting.AQUA)
                : Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true);
        tell(player, Component.literal("✨ " + (say == null || say.isBlank()
                        ? "There it is! I think it could be even better though..." : say))
                .setStyle(voice));
        if (suggestion != null && !suggestion.isBlank()) {
            NextWish.rememberAgent(player.getUUID(), suggestion);   // step 5: his next wish box opens with it
            // PLAIN WORDS, not a link (2026-11-10, a Bedrock design decision): a phone turned the
            // old "[Click to wish: …]" into a pop-up over the thing he had just got. He can say it
            // with the watch's "Wish for something new", on every edition.
            tell(player, Component.literal("   💡 You could wish: " + suggestion)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
        }
        if (!alreadyCelebrated) {
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
            sparkle(player);
        }
    }

    /**
     * Verdict ROUGH: it works, sort of, and Moddy says so. Then it tells the child how to throw it
     * away — a child who does not know he is allowed to say no just lives with the bad thing.
     */
    public static void verdictRough(ServerPlayer player, String say) {
        say = agentWords(say);
        tell(player, Component.literal("✨ " + (say == null || say.isBlank()
                        ? "I made it, but I don't think I did a great job on this one." : say))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        tell(player, Component.literal(
                        "   Don't like it? Open me up and press ↩ Undo my last wish and I'll take it away.")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.5f, 0.8f);
    }

    /** Verdict FAILED: rare. Moddy admits it and takes back what it made rather than leaving a mess. */
    public static void verdictFailed(ServerPlayer player, String say) {
        say = agentWords(say);
        tell(player, Component.literal("✨ " + (say == null || say.isBlank()
                        ? "I tried really hard, but I couldn't make that one work." : say))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
        tell(player, Component.literal(
                        "   I've cleared it away so it isn't cluttering up your world. "
                                + "Want to try wishing it a different way? 🪄")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.5f, 0.7f);
    }

    /**
     * <b>The escape hatch, in the sentence that told him</b> — as words, not a link (2026-11-10, a
     * Bedrock design decision, D1 "stop"). The stop itself lives on the watch, in 👎's slot while a wish
     * is being made ({@code WatchPanel.STOP_LABEL}), on every edition. The old "[✋ stop]" chat link
     * popped up on a Bedrock phone as a form that covered the wish arriving (measured 2026-11-09).
     */
    public static void stopAffordance(ServerPlayer player, Wish wish) {
        if (player == null || wish == null) {
            return;
        }
        tell(player, Component.literal(STOP_HINT).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
    }

    /** What Moddy says under "I'm making it", so he knows how to stop it. */
    public static final String STOP_HINT = "   Changed your mind? Open your wish watch and press "
            + WatchHome.STOP + ".";

    /** Say which of the three outcomes he actually got. Never "done" without naming what happened. */
    public static void stopped(ServerPlayer player, com.moddingfromamod.marshal.CancelMode mode,
                               String extra) {
        if (player == null) {
            return;
        }
        String text = (mode == null ? com.moddingfromamod.marshal.CancelMode.STOP : mode)
                .saidToChild();
        tell(player, Component.literal(text + (extra == null || extra.isBlank()
                        ? "" : " " + extra.strip()))
                .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)));
        player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.5f, 1.1f);
    }

    /** He pressed stop and there was nothing being made. */
    public static void nothingToStop(ServerPlayer player) {
        if (player != null) {
            tell(player, Component.literal("🤔 I'm not making anything right now!")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        }
    }

    private static void sparkle(ServerPlayer player) {
        BlockPos at = player.blockPosition();
        player.level().sendParticles(ParticleTypes.HAPPY_VILLAGER,
                at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5, 24, 1.2, 1.0, 1.2, 0.02);
        player.level().sendParticles(ParticleTypes.END_ROD,
                at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 12, 0.8, 0.8, 0.8, 0.03);
    }
}
