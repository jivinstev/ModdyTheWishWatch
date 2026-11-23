package com.moddingfromamod.wish;

import com.moddingfromamod.marshal.WorldCatalogue;
import java.util.Optional;

import com.moddingfromamod.ModdingFromAModMod;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The front door for every wish, from any source (watch preset, typed text, or {@code /wish}).
 *
 * <p>One place decides the whole policy, in this order: sanitise → throttle → try to fulfil it
 * locally → otherwise record it for escalation. Keeping that sequence in a single method is what
 * makes the rules easy to reason about (and hard to accidentally bypass by adding a new entry point).
 *
 * <p>Today "escalation" just means the wish sits {@code PENDING} and the kid is told honestly that
 * the watch doesn't know that one yet. In Phase 2 an AI agent picks those up — and the fix it writes
 * is normally a <em>new template</em>, so the same wish becomes instant next time.
 */
public final class WishService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private static final WishRateLimiter RATE_LIMITER = WishRateLimiter.defaults();

    private WishService() {
    }

    /** What happened to a submitted wish (returned so commands/tests can assert on it). */
    public enum Outcome {
        /** Matched a template and is being carried out. */
        FULFILLING,
        /** Recorded, but nothing local can do it yet. */
        PENDING_ESCALATION,
        /** Nothing usable was said. */
        EMPTY,
        /** Throttled or a duplicate. */
        THROTTLED,
        /**
         * Refused by the child-safety floor before anything else happened
         * (docs/CHILD_SAFETY_PLAN.md §3 L0, enforcing arm only).
         *
         * <p>Deliberately distinct from {@link #THROTTLED}: a blocked wish must not cost a
         * rate-limit token, must not start a web search, and must not be re-offered as a duplicate.
         * Every caller that reads this enum treats anything-but-FULFILLING as "not granted", which
         * is already the right answer here — the child has been told by the time this returns.
         */
        BLOCKED,
        /**
         * The server is draining for maintenance, so nothing new was started.
         *
         * <p>Distinct from {@link #BLOCKED} and from {@link #THROTTLED} because it is a fact about
         * the SERVER rather than about the wish or the wisher: pooling it with a safety refusal
         * would make "how often is a child refused for what he said" unreadable the first time an
         * operator deployed a jar during play, and pooling it with the rate limiter would make the
         * mod look like it throttles children it did not.
         */
        DRAINING,
        /**
         * Moddy is tired (D8): the grader cannot be reached, the agent is not answering, or
         * the spend ceiling is reached — so this NEW wish was not started. Cached wishes and
         * re-wishes still go through. A fact about the SERVICE, kept apart from {@link #BLOCKED}
         * for {@link #DRAINING}'s reason: pooled with a safety refusal it would make "how often is
         * a child refused for what he said" unreadable on the first evening the network went down.
         */
        TIRED,
        /**
         * The world's wish bank is empty: refused kindly at submit, with when the next wish
         * arrives — no rate-limit token, no grader call, no book entry, no worker.
         */
        BANK_EMPTY,
        /**
         * Answered by the APPRENTICE: a Wish Crafter's typed words, or a Magician's when he chose it. A
         * local matcher offered Builder makes on a pick page (or said nothing in Moddy's Parts fits). Nothing was
         * spent and no model ran; a make he then presses is the Builder's own wish.
         */
        APPRENTICE,
        /**
         * {@link #reject} (👎 / {@code /moddy no}): the wish was taken away and settled as
         * STOPPED, and nothing was sent back to the agent. Also the answer to a second press on a
         * wish already undone — which changes nothing and pays nothing.
         */
        UNDONE
    }

    /**
     * Submit a wish on behalf of a player. Must be called on the server thread.
     *
     * @return what happened, so callers can react without re-deriving the policy
     */
    public static Outcome submit(ServerPlayer player, String rawText, WishSource source) {
        return submit(player, rawText, source, "");
    }

    /**
     * @param namedTemplateId the recipe a BUTTON named, or empty for anything typed
     */
    public static Outcome submit(ServerPlayer player, String rawText, WishSource source,
                                 String namedTemplateId) {
        try {
            return submitScoped(player, rawText, source, namedTemplateId, null, WishRoute.Hint.AUTO);
        } finally {
            WishFeedback.aboutNone();   //: the scope opened below never outlives this call
        }
    }

    /**
     * A typed wish that names who should answer it: {@code /moddy apprentice <words>} asks for the
     * Apprentice, and the Apprentice page's "Ask Moddy instead" sends a Magician's words on to the agent. Everything
     * else about the wish is {@link #submit}'s.
     */
    public static Outcome submit(ServerPlayer player, String rawText, WishSource source, WishRoute.Hint hint) {
        try {
            return submitScoped(player, rawText, source, "", null, hint);
        } finally {
            WishFeedback.aboutNone();   //: the scope opened below never outlives this call
        }
    }

    /**
     * <b>Grant {@code plan} as this player's own wish, in {@code rawText}'s words</b> — the path
     * {@code /moddy try <wish id>} takes ({@link WishTry}).
     *
     * <p>NOT a second grant path: it is {@link #submit} with the recipe handed in rather than
     * looked up, so every gate above the grant — the drain, the safety floor, the rate limiter —
     * and everything below it — a book entry owned by HIM, the acknowledgement, the executor at
     * the replay's permission, the log line — is the same code a watch press runs. What it skips
     * is only what does not apply: no template is tagged (a try is not a press of a recipe, and
     * must not count as one in the library's numbers), and no routing decision is recorded
     * (nothing was routed).
     */
    public static Outcome submitReplay(ServerPlayer player, String rawText, CommandPlan plan) {
        try {
            return submitScoped(player, rawText, WishSource.COMMAND, "",
                    new WishTemplates.Match("", plan), WishRoute.Hint.AUTO);
        } finally {
            WishFeedback.aboutNone();
        }
    }

    private static Outcome submitScoped(ServerPlayer player, String rawText, WishSource source,
                                        String namedTemplateId, WishTemplates.Match replay, WishRoute.Hint hint) {
        // WHO REALLY SENT IT: decided once, here, with the player's NAME as well as his
        // id (a stand-in may be configured by either), and held for every record this submit
        // writes — the EMPTY/DRAINING/BLOCKED/THROTTLED ones included, which carry no wish id.
        try (WishOrigin.Scope ignored = WishOrigin.scope(WishOrigin.of(
                player.getStringUUID(), player.getGameProfile().getName()))) {
            return submitOrigined(player, rawText, source, namedTemplateId, replay, hint);
        }
    }

    private static Outcome submitOrigined(ServerPlayer player, String rawText, WishSource source,
                                          String namedTemplateId, WishTemplates.Match replay, WishRoute.Hint hint) {
        String text = WishText.sanitize(rawText);
        if (!WishText.isUsable(text)) {
            WishFeedback.didntCatchThat(player);
            com.moddingfromamod.agent.ActionLog.wish(rawText, "EMPTY", source.name(), "",
                    player.getStringUUID());
            return Outcome.EMPTY;
        }

        // ── DRAINING ─────────────────────────────────────────────────────────────────────────────
        //
        // A grown-up is about to restart the server, so nothing NEW starts; wishes already in
        // flight are untouched and the launcher waits for them. High up for the same reason the
        // safety floor is: a wish that is going to be refused must not cost the child a rate-limit
        // token and must not set a web search running, both of which happen below.
        //
        // ABOVE the safety floor too, and that is deliberate rather than convenient. The two
        // refusals are different facts about different subjects — "the world is busy" against
        // "that is not a wish I can make" — and during a maintenance window the first one is the
        // true one. Grading here would also spend money on a wish nothing is going to grant.
        //
        // What that costs, said out loud because the safety layer's numbers are read from this
        // log: a wish typed during a drain produces NO `safety` record, so the minutes of a
        // maintenance window are absent from the grader's denominator rather than counted as
        // passes (docs/CHILD_SAFETY.md §6). That is the honest direction — the wish was never
        // graded, never queued and never granted, so recording it as "allowed" would be a lie
        // about a wish nothing ever looked at.
        if (com.moddingfromamod.health.MaintenanceState.global().refusesNewWishes()) {
            WishFeedback.tidyingUp(player);
            com.moddingfromamod.agent.ActionLog.wish(text, "DRAINING", source.name());
            return Outcome.DRAINING;
        }

        // ── CHILD SAFETY, L0 + L1 (docs/CHILD_SAFETY_PLAN.md §3), BEFORE the rate limiter and
        // ── before the reference prefetch. ENFORCING since CS-S2 — but only in the `on` arm, and
        // ── only for L0: the model's grade is awaited at the dequeue point, never here.
        //
        // Position is the whole point and is §3's, not a convenience: a wish that is going to be
        // refused must not cost the child a rate-limit token (WishRateLimiter's own rule) and must
        // not set a web search running. Both of those happen below, so the check has to be here.
        //
        // NOTHING HERE BLOCKS. The lexicon is a regex sweep over one short string (microseconds);
        // the grade is fire-and-forget on its own daemon thread and is awaited, if at all, on the
        // MCP handler thread in AwaitWishTool. docs/PATTERNS.md' second lesson is that nothing on
        // the wish-submission path may block, "even a cheap model is a network round trip" — and
        // this method runs on the server thread.
        //
        // The LOG for a lexicon hit that does NOT refuse is deliberately not written here: the wish
        // does not exist yet, so there is no wishId to join the record to, and a safety record that
        // cannot be joined to the wish it graded cannot answer "what did the agent build instead"
        // (§6). It is written just after WishBook.add below, where the id exists. A hit that DOES
        // refuse has no wish to join to at all and never will, so it is logged unjoined right here
        // — the alternative being the one thing worse than an unjoinable record, which is none.
        java.util.Optional<com.moddingfromamod.safety.SafetyVerdict> lexicon =
                com.moddingfromamod.safety.ChildSafetyConfig.grading()
                        ? com.moddingfromamod.safety.SafetyLexicon.shared().check(text)
                        : java.util.Optional.empty();
        // ── L0 ENFORCEMENT (CS-S2). The ONE thing this layer refuses synchronously.
        //
        // A lexicon CLEARLY is a term so unambiguous a seven-year-old could not have meant anything
        // else, so it is answered here rather than ten seconds later at the dequeue point — §12.3's
        // acknowledged-then-blocked gap is 8–25 s and this is what keeps the unambiguous half of it
        // at zero. WELLBEING is NOT a CLEARLY and never reaches this branch: it sets no band, so
        // overall() stays FINE and the wish goes on to be answered kindly with a grown-up note
        // (§1 — blocking a child in distress is the worst answer available).
        //
        // ⚠ NO WISH ID EXISTS YET, so neither record below carries one — the same shape as the
        // EMPTY and THROTTLED records above and for the same reason: the book has not been written
        // to, because nothing that could cost the child anything has happened yet. That position is
        // the whole point (§3): no rate-limit token, no web prefetch, no book entry, no bar.
        if (com.moddingfromamod.safety.ChildSafetyConfig.enforcing() && lexicon.isPresent()
                && lexicon.get().overall() == com.moddingfromamod.safety.SafetyVerdict.Band.CLEARLY) {
            // THE ACTION BAR, not chat: this lands before the acknowledgement (§5's table), so the
            // child has not yet been told "Moddy's on it" (WishFeedback.notYet) and there is nothing in chat
            // for this message to contradict.
            WishFeedback.blockedBeforeThinking(player);
            // ⚠ NO wishId AND THEREFORE THE ONE RECORD THAT NEEDS `playerId` MOST: this refusal
            // happens before the book is written to, so nothing will ever join it to a wish. The
            // player's UUID is the only thing that can attribute it to a child afterwards.
            com.moddingfromamod.agent.ActionLog.safety("", "input", lexicon.get(),
                    com.moddingfromamod.safety.SafetyInputGate.Act.BLOCK.name(), 0L, 0.0,
                    "lexicon", "", "", 0, false, false, player.getStringUUID());
            if (lexicon.get().wellbeing()) {
                // THE NOTE, AND NOT THE WARM LINE (§12.1 decision 1B, scoped here deliberately).
                //
                // Reaching this branch takes a wellbeing phrasing AND a banded term in one wish —
                // a wellbeing term sets no band, so overall() could not be CLEARLY on its own. The
                // wish is therefore over, and SafetyWords.WELLBEING_NOTICED ends "I'm still making
                // your wish!", which would be false. A kind sentence containing one lie is worse
                // than the grown-up note on its own, and the note is what §1 actually promises; a
                // second wording for a rare compound case would be a fifth string in SafetyWords
                // for the sake of never leaving one unsaid. Recorded in the CS-D2 ledger row as a
                // judgement rather than left for somebody to find.
                com.moddingfromamod.agent.ActionLog.grownUpNote("", text);
            }
            com.moddingfromamod.agent.ActionLog.wish(text, "BLOCKED", source.name(), "",
                    player.getStringUUID());
            return Outcome.BLOCKED;
        }
        // ── THE WISH BANK (D18): nothing to pay with → refused kindly, with when the next
        // wish arrives. HERE, below the safety floor (a CLEARLY is answered as one, never as "you
        // have no wishes") and ABOVE the rate limiter, both graders and the book, for the floor's
        // own reason: a wish that is going to be refused must cost no token, no model call and no
        // worker. A no-op on a world whose rules declare no wish_bank. A child in
        // distress is never refused (CHILD_SAFETY.md §1): his wish goes on, waived, below.
        final boolean distress = lexicon.map(v -> v.wellbeing()).orElse(false);

        // ── A PRESSED RECIPE STARTS NO MODEL (step 1, docs/OPEN_DOOR_PLAN.md §8.1). Resolved
        // HERE, once, because three model calls below are only worth starting for a wish the agent
        // may read. A button or `/moddy again` names a recipe that exists (verified against the
        // live set, so a client cannot invent one), and such a wish ALWAYS ends in the recipe lane
        // below — tiredness and an earlier issue's hide both decline word matches only. On that lane:
        //   · the L1 input grade is never ENFORCED (AwaitWishTool enforces it at agent pickup, which
        //     a recipe grant never reaches), so on this path it was only ever a record — a model call and
        //     the child's words sent to the grader, to grade a phrase that is our own recipe's;
        //   · the world-rules grade likewise is enforced only at pickup;
        //   · the reference prefetch warms a lookup for an agent that will never run.
        // The L0 lexicon above still reads every press (local, free), so a CLEARLY still refuses
        // and a wellbeing phrasing still gets its grown-up note. A TYPED wish that happens to
        // match a recipe by its words keeps every call: those are a child's own words.
        final Optional<WishTemplates.Match> named = replay != null ? Optional.of(replay)
                : WishCatalog.templates().byId(namedTemplateId, text);
        final boolean models = PreAgentModels.wanted(named.isPresent());

        // ── WHO ANSWERS: THE AGENT, OR THE APPRENTICE (step 5, docs/OPEN_DOOR_PLAN.md §2). EVERY door
        // comes through here (the watch's wish box, /wish, a question, the goal help's wish), so this one check
        // is the route, and the doors cannot drift apart. A Wish Crafter's typed words always go to the
        // Apprentice: a local, deterministic matcher that offers Builder makes, and never the agent. A Magician's go
        // there when his watch's toggle says so, or when he asked for it by name. BELOW the lexicon (a CLEARLY is
        // answered as one) and ABOVE the bank, the graders and the book: looking at the Apprentice's page spends
        // nothing, and nothing of a Crafter's words is kept. His words are not in this record either.
        // a typed RE-WISH the local reader is sure of — change one of his things the Builder's way, another
        // of one, undo / stop — is answered here, at once, by the paths that already exist; anything else goes on as
        // typed. After the lexicon (L0) above, before the route and the bank (each path it takes is charged as it is).
        if (named.isEmpty() && Rewish.tryAnswer(player, text, source, hint)) {
            com.moddingfromamod.agent.ActionLog.wish(CrafterService.isCrafter(player) ? "" : text, "REWISH", source.name(), "",
                    player.getStringUUID());
            return Outcome.APPRENTICE;
        }
        if (named.isEmpty() && ApprenticeService.route(player, hint, WishIntent.isQuestion(text))
                == WishRoute.Route.APPRENTICE) {
            boolean crafter = CrafterService.isCrafter(player);
            ApprenticeService.offer(player, text, source);
            com.moddingfromamod.agent.ActionLog.wish(crafter ? "" : text, "APPRENTICE", source.name(), "",
                    player.getStringUUID());
            return Outcome.APPRENTICE;
        }
        // A QUESTION is never refused here — it is answered, and costs nothing at pickup.
        if (WishCharge.get().refusesAtSubmit(player, distress, text, named.isPresent())) {
            com.moddingfromamod.agent.ActionLog.wish(text, "BANK_EMPTY", source.name(), "",
                    player.getStringUUID());
            return Outcome.BANK_EMPTY;
        }

        // Memoised on the sanitised text, so the three re-wish paths that skip this method join
        // the grade already in flight rather than starting a second one.
        //
        // BELOW the L0 refusal on purpose: a blocked wish must not pay for a model call either.
        if (models) {
            com.moddingfromamod.safety.SafetyGrader.gradeInput(text);
        }

        long now = System.currentTimeMillis();
        WishRateLimiter.Decision decision =
                RATE_LIMITER.check(player.getStringUUID(), WishText.dedupeKey(text), now);
        if (decision != WishRateLimiter.Decision.ALLOW) {
            // A duplicate is silent on purpose — a double-click shouldn't nag.
            if (decision == WishRateLimiter.Decision.RATE_LIMITED) {
                WishFeedback.tooFast(player);
            }
            com.moddingfromamod.agent.ActionLog.wish(text, "THROTTLED", source.name(), "",
                    player.getStringUUID());
            return Outcome.THROTTLED;
        }

        // THE WORLD-RULES GRADE, fired now and never waited on here — in parallel with the
        // safety grade above, and a separate call: it never touches the safety prompt or its floors.
        // On a world whose rules deny nothing it returns at once and costs nothing.
        // Enforced at pickup in AwaitWishTool, beside the safety grade.
        if (models) {
            com.moddingfromamod.world.WorldRulesGrader.gradeInput(text);
        }

        // START ANY REFERENCE LOOKUP NOW, in the background.
        //
        // A live lookup takes 75-109 seconds, measured -- the whole distribution, not a tail. Left
        // until the agent asks, that is a minute and a half of a seven-year-old watching a ticker.
        // Started here it overlaps with the agent's planning, which takes minutes anyway, so by the
        // time look_up_character is called the answer is already cached and the wait is nothing.
        if (models) {
            com.moddingfromamod.reference.ReferenceHint.prefetchFrom(text);
        }

        Wish wish = WishBook.get(player.getServer()).add(Wish.create(text, source, now,
                player.getUUID(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ()));
        // every line from here on is about THIS wish, not the one still running before it.
        WishFeedback.about(wish.shortId());
        // a named character's brief starts now, beside the grades and the lookup, and is
        // enforced at the hand-over (AwaitWishTool). Off unless -Ptestprops wishbrief=… is set.
        if (models) {
            com.moddingfromamod.reference.WishBriefs.atSubmit(wish.id(), text);
        }
        if (distress) {
            WishCharge.get().waiveIfEmpty(player, wish.id());
        }
        // A wish was made on this world — what "went to another world after a refusal" counts.
        com.moddingfromamod.world.WorldRulesLog.submit(wish.shortId(), player.getStringUUID());

        // The lexicon's answer, now that there is a wish id to join it to. SHADOW: the action
        // string says what enforcement WOULD have done and nothing is actually done — CS-S2 owns
        // turning these into a refusal, and it needs a base rate first (§8: "shadow mode is the
        // first thing that ships, so a base rate exists before anything is blocked").
        //
        // ── AND THE ONE WARM LINE THE CHILD GETS (§12.1, decision 1B; chunk CS-D2).
        //
        // A lexicon WELLBEING hit is the only thing that says anything to him, and the wish then
        // carries on exactly as it would have: this is not a refusal, not a redirect, and not a
        // condition on anything below. Decided BEFORE the record is written and written into it as
        // `childTold`, because §6's rule is that the grade is logged before any action is taken and
        // a record that disagreed with what was said would make the one number this decision
        // creates unreadable.
        lexicon.ifPresent(v -> {
            boolean tell = com.moddingfromamod.safety.SafetyInputGate.saysTheWellbeingLine(
                    v, com.moddingfromamod.safety.SafetyVerdict.unavailable());
            com.moddingfromamod.agent.ActionLog.safety(wish.shortId(), "input", v,
                    inputAction(v), 0L, 0.0, "lexicon", "", "", 0, false, tell,
                    player.getStringUUID());
            if (v.wellbeing()) {
                com.moddingfromamod.agent.ActionLog.grownUpNote(wish.shortId(), text);
            }
            if (tell) {
                // SAID ONCE, from here and nowhere else. The dequeue point writes a SECOND
                // grown-up note for the same wish (AwaitWishTool, on the combined verdict) and
                // deliberately says nothing to the child: a note is a record and can be written
                // twice harmlessly, while a line is an event and a child told twice about one
                // sentence he typed would read as being fussed over. There is no line-sending
                // code anywhere but here, which is what makes "once" structural rather than
                // remembered.
                WishFeedback.wellbeingNoticed(player);
            }
        });

        // A wish that points at something already in the world can never be answered from the
        // cache, however well the keywords match: a template is a fixed list of commands and cannot
        // know what "it" is. Serving one would confidently change the wrong thing, instantly.
        // A QUESTION must never be answered by doing something. "How do I use it?" matched against
        // templates would build a second machine and still leave the question unanswered, which
        // reads to a child as being ignored — worse than a slow reply. Questions skip the cache
        // entirely, including the no-agent fallback: there is no offline answer to give.
        // A BUTTON NAMES ITS RECIPE, so serve that exact one — no matching, no gates.
        //
        // This is not a loosening of the matcher; typed text is routed exactly as strictly as
        // before. It is the opposite: a remembered button is not a guess at what the child meant,
        // it IS the thing he already has, so re-deriving it from its own label can only lose
        // information. It did: a taught recipe whose terms happened to include a back-reference
        // ("again", "another", "more") was reported as verified, drawn as a button, and then
        // escalated to the agent on every press — because the contextual gate below fires before
        // the cache is consulted. The child waited a full round-trip for a design he already owned.
        //
        // The id is still verified against the live template set, so a client cannot invent one.
        // ── IS MODDY TIRED? Asked here, and acted on only after the cache has declined:
        // a confident match or a button is "open for cached wishes"; a NEW grant is refused.
        final Optional<com.moddingfromamod.health.ModdyTired.Tired> tired = tiredNow(now);
        Optional<WishTemplates.Match> matched = named;
        boolean question = false;
        boolean contextual = false;
        if (matched.isEmpty()) {
            question = WishIntent.isQuestion(text);
            // A PRONOUN IS NOT THE ONLY WAY TO POINT AT SOMETHING. "Make the stone tower bigger"
            // has none, and used to be handed to the offline matcher -- which matched on the word
            // "tower" and BUILT HIM A SECOND TOWER. Naming a thing he already owns, plus a word
            // that changes it, is exactly as contextual as saying "it", and a fixed list of
            // commands can no more edit his tower than it can edit "it".
            contextual = WishReference.isContextual(text) || namesSomethingHeOwns(player, text);
            matched = (question || contextual) ? Optional.empty() : route(text, source);
            // never quietly reuse a design he took off his watch. A button or press names
            // its recipe and skips this; only WORD matching is refused, and the wish goes on to
            // the agent like any other.
            if (matched.isPresent() && WatchHides.hidesTemplate(player.getStringUUID(),
                    matched.get().templateId())) {
                LOGGER.info("not serving '{}' by word match: he hid it from his watch",
                        matched.get().templateId());
                matched = Optional.empty();
            }
        }
        if (matched.isPresent()) {
            WishTopic.wished(player.getUUID(), text);   //: his previous wish, cached or not
            String templateId = matched.get().templateId();
            // Remember WHICH template granted it, so "that's not right" can un-teach that exact one.
            // A replay (a try) names none, and its wish is left untagged.
            Wish tagged = replay != null ? wish
                    : WishBook.get(player.getServer()).tagTemplate(wish.id(), templateId)
                            .orElse(wish.withTemplate(templateId));
            WishFeedback.accepted(player, text);
            // A recipe's grant is a wish too, and it has no pickup — so it is charged here,
            // where it starts. A failure settles FAILED in the book and is refunded there.
            //
            // this is also where a re-wish of a whole WISH or of one PART is paid for — ONE
            // wish per press, whichever it is (bank/RewishPrice). The bank emptied since the check
            // above (two presses on the last wish) → refused in the bank's words, nothing run.
            // served with no model, so HALF a wish (WishBank.Params.priceHalves).
            var paid = WishCharge.get().charge(player.getUUID(), tagged.id(), false);
            if (paid.refused()) {
                WishBook.get(player.getServer()).complete(wish.id(), WishStatus.REJECTED,
                        "the wish bank emptied before this wish could be paid for");
                WishFeedback.bank(player, paid.line());
                com.moddingfromamod.agent.ActionLog.wish(text, "BANK_EMPTY", source.name(), wish.shortId(),
                        player.getStringUUID());
                return Outcome.BANK_EMPTY;
            }
            WishExecutor.submit(player, tagged, matched.get().plan());
            CrafterService.afterReadyMade(player);   //: a Crafter's 1st and every 5th may carry a nudge
            LOGGER.info("wish {} matched template '{}': {}", wish.shortId(), templateId, text);
            // WHICH recipe, on the wish's own record: the only line in the log that says
            // which recipe served it, typed or pressed.
            com.moddingfromamod.agent.ActionLog.wish(text, "FULFILLING", source.name(), wish.shortId(),
                    player.getStringUUID(), templateId);
            return Outcome.FULFILLING;
        }

        // ── MODDY IS TIRED: CLOSED FOR NEW GRANTS (D8).
        //
        // HERE, after the cache has had its chance and before anything is queued, acknowledged or
        // barred: the child is told at once, in words that say it is Moddy and not him, rather
        // than being told "Moddy's on it" and then — two minutes later, or forty
        // seconds later at the dequeue — "Moddy fell asleep". The lexicon has ALREADY graded this
        // wish above (a CLEARLY never gets this far), so L0 grades every input throughout.
        //
        // ⚠ A WELLBEING-FLAGGED WISH IS NEVER TURNED AWAY FOR TIREDNESS. Two reasons, both from
        // docs/CHILD_SAFETY.md: §1 says a child in distress is never refused, and
        // SafetyInputGate.actionOf already refuses to close on an outage for exactly this row. And
        // the warm line above has just told him "I'm still making your wish!" — refusing now would
        // make that sentence a lie. It goes on to the agent like any other day.
        if (tired.isPresent() && !lexicon.map(v -> v.wellbeing()).orElse(false)) {
            WishBook.get(player.getServer()).complete(wish.id(), WishStatus.REJECTED,
                    "Moddy was tired (" + tired.get().cause().wire() + ") — this new wish was "
                            + "not started. " + tired.get().detail());
            WishFeedback.tired(player, tired.get());
            LOGGER.info("wish {} not started — Moddy is tired ({}): {}", wish.shortId(),
                    tired.get().cause().wire(), text);
            com.moddingfromamod.agent.ActionLog.wish(text, "TIRED", source.name(), wish.shortId(),
                    player.getStringUUID());
            com.moddingfromamod.health.WishSweeper.announceTiredness(now);
            return Outcome.TIRED;
        }


        // No local answer: leave it PENDING for the agent and be honest with the kid.
        //
        // ⚠ NOTHING HERE DECIDES WHAT THE WISH IS ABOUT. A local resolver used to: it ran a
        // ladder of rungs over a contextual wish, told the child "Working on your Pegasus!" before
        // anyone had read his words, and told the agent not to argue with it. Live it was 4
        // picks and 4 wrong, every one a NEW item bound to a creature he owned. The agent decides
        // now (plan_wish `about`), and what the rungs knew rides along as FACTS (WishFacts).
        String note = question ? WishIntent.noteForAgent(text)
                : contextual ? CONTEXTUAL_NOTE
                : hintForAgent(text);
        String facts = WishFacts.forPlayer(player);
        if (facts.isEmpty() && !question && WishReference.pointsBack(text)) {
            facts = com.moddingfromamod.marshal.ReferentFacts.NO_ANTECEDENT;   //
        }
        // AFTER his facts were gathered, so this wish's facts name the wish BEFORE it.
        WishTopic.wished(player.getUUID(), text);
        if (!facts.isEmpty()) {
            note = note.isEmpty() ? facts : note + "\n" + facts;
        }
        // THE WHOLE WORLD, ON EVERY WISH — items 2 and 3.
        //
        // Until now the agent was handed the ladder's PICK and nothing else, on the one branch
        // where the ladder had an opinion. The other three returned "": a wish that did not look
        // contextual, a contextual one the ladder could not resolve, and a question all reached the
        // agent knowing nothing about what the child owns. an earlier issue's own proposal is the opposite —
        // "always hand the agent the candidates, whether or not the ladder had an opinion" — and
        // the cost objection it was written to answer does not survive measurement: his entire
        // world is about a paragraph (48 artifacts = 1110 chars ≈ 277 tokens) against a wish that
        // takes 117 s at p50.
        //
        // NOT ArtifactIndex.forPlayer, which is already narrowed to 512 blocks and six mobs for the
        // ladder's benefit. See ArtifactIndex.everything() — the recorded failure here is a NEW
        // wish read as a modification, and ruling that out needs to know what exists anywhere.
        note = withCatalogue(note);
        if (!note.isEmpty()) {
            // Tell the agent WHY the cache was skipped, so it reaches for handles rather than
            // wondering why an obvious-looking wish arrived unmatched.
            WishBook.get(player.getServer()).complete(wish.id(), WishStatus.PENDING, note);
        }
        if (question) {
            WishFeedback.thinkingAboutQuestion(player);
        } else {
            WishFeedback.notYet(player);
        }
        // WAKE THE POOL for a wish nobody could otherwise hear (step 4). The pool may
        // have been stopped during a quiet spell while he played; the watch wakes it on the panel
        // opening, but a `/wish ...` typed in chat arrives with no earlier signal, so this is the
        // backstop. Only when the wish will really wait for the boot is he told so, once — after
        // the acknowledgement, so the opener is not contradicted.
        boolean bootWait = com.moddingfromamod.agent.AgentSession.wakeForWish();
        // C: how far ahead of this wish the pool was asked to start — the watch,
        // the Wish-now anvil, a keystroke — and whether it will still wait for a boot. The bet that
        // a 5-minute reap costs nobody a wait is measured here, never assumed.
        com.moddingfromamod.agent.ActionLog.intent(wish.shortId(), player.getUUID().toString(),
                com.moddingfromamod.marshal.WishIntent.global()
                        .take(player.getUUID(), System.currentTimeMillis()).orElse(null),
                bootWait);
        if (bootWait) {
            WishFeedback.wakingUp(player);
        }
        // Keep telling him it's happening. Silence while the agent thinks is indistinguishable
        // from a broken mod, and a big build can genuinely take a minute and a half.
        WishProgress.started(player, wish);
        // A BAR WITHIN A TICK, with no agent involved. The design's universal opening state: a wish
        // that is merely getting ready is indistinguishable from one being thought about, and both
        // are better than nothing on screen. Until now the bar only appeared when the agent got as
        // far as plan_wish, so everything before that -- including the whole window where he might
        // want to stop it -- had a stop button and nothing to stop.
        // LOCK where this wish manifests to the spot the watch was aiming at NOW — so the thing
        // appears there instead of following the child around (the mid-air McTrain bug). A default
        // the agent can override with conjure_at; also starts the sparkles gathering there at once.
        // aim, then clamp, decided ONCE here — the one rule in WishAim, with his position,
        // yaw and pitch kept on the run so nothing later re-aims from the live player.
        // Acknowledged within a tick, whether or not a worker is free — the responsiveness
        // contract. The run is where "stop this one" and the per-wish anchor live.
        var run = com.moddingfromamod.marshal.Marshal.global()
                .submit(wish.id(), player.getUUID(), text);
        net.minecraft.core.BlockPos aim = WishAimer.lockAtWishTime(player, run, wish.shortId());
        WishProgress.defaultAnchor(player, aim);
        // ITS OWN BAR, named after it — so three wishes in flight are three rows he can tell apart
        // rather than one that only ever showed the first. Still within this tick, which is the
        // property that matters: a wish merely getting ready must be indistinguishable from one
        // being thought about, and both beat an empty screen. It moved to AFTER the run is
        // submitted only because the run is what knows what the wish is called.
        WishProgress.startThinking(player, wish.id(),
                run == null ? text : run.displayName());
        // A camera is wanted from the moment the wish exists — waking one takes 10–40 s, so the
        // camera starts now and plan_wish's kind releases it if this turns out to be an item.
        CameraNeed.shared().open(wish.id(), System.currentTimeMillis());
        // NO NAME YET: the bar says his words until the agent names the referent in
        // plan_wish, which renames it then. A name shown before the agent has read his words is
        // the guess this used to announce.
        WishFeedback.stopAffordance(player, wish);
        LOGGER.info("wish {} has no template yet: {}", wish.shortId(), text);
        // THE signal for improving the mod: something he wanted that we couldn't do offline.
        com.moddingfromamod.agent.ActionLog.wish(text, "PENDING_ESCALATION", source.name(),
                wish.shortId(), player.getStringUUID());
        // Wake the agent NOW. An agent parked on await_wish gets this in well under a millisecond,
        // where the old file-polling watcher took 20-90 seconds to notice.
        com.moddingfromamod.agent.WishQueue.global().offer(
                WishBook.get(player.getServer()).byId(wish.id()).orElse(wish));
        return Outcome.PENDING_ESCALATION;
    }

    /**
     * Does this wish name one of his own things and ask to change it?
     *
     * <p>Deliberately requires BOTH. Naming a thing alone is not a change — "build me another
     * castle like the sky castle" mentions it and wants a new one — and a change word alone is the
     * pronoun case {@link WishReference#isContextual} already covers.
     *
     * <p>Best-effort: if anything here fails, the wish routes exactly as it did before rather than
     * failing. This decides which lane a wish takes, and no lane is worth a crash.
     */
    private static boolean namesSomethingHeOwns(ServerPlayer player, String text) {
        try {
            // "make THE black hole pull harder" — anything in the whole catalogue, named
            // with the/my, beside a change word. The rule-made things (a datapack, a set_rule rule)
            // are only in the catalogue, never in the nearby candidates below.
            if (WishReference.isContextual(text, ownedNames())) {
                return true;
            }
            if (!WishReference.mentionsAChange(text)) {
                return false;
            }
            var candidates = ArtifactIndex.forPlayer(player);
            if (candidates.isEmpty()) {
                return false;
            }
            var index = new com.moddingfromamod.marshal.AliasIndex();
            for (var c : candidates) {
                index.add(new com.moddingfromamod.marshal.AliasIndex.Artifact(
                        c.id(), c.kind(), c.name(), c.aliases()));
            }
            var named = index.resolve(text);
            return named.resolved()
                    || named.verdict() == com.moddingfromamod.marshal.AliasIndex.Verdict.AMBIGUOUS;
        } catch (RuntimeException e) {
            LOGGER.warn("could not check whether '{}' names something he owns: {}", text, e.toString());
            return false;
        }
    }

    /** What his things are called: every catalogue entry's id, name and aliases. Server thread. */
    private static java.util.List<String> ownedNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (var e : ArtifactIndex.everything()) {
            names.add(e.id());
            names.add(e.name());
            names.addAll(e.aliases());
        }
        return names;
    }

    /**
     * Decide whether this wish can be answered offline — the confidence router.
     *
     * <p>The mod used to be cache-first, with the agent as the exception: any template whose
     * keywords appeared <i>anywhere</i> in the sentence won. That is how "Spikor's airship flying
     * in the sky" became "make me fly". It is now <b>agent-first</b>, and the cache only wins when
     * it is genuinely confident.
     *
     * <p>Three lanes, in order:
     * <ol>
     *   <li><b>Confident</b> → instant. Whole-word match with nothing meaningful left unexplained
     *       (see {@link WishTemplates#matchConfident}).</li>
     *   <li><b>A tapped preset</b> → instant, always. The text came from <i>us</i>, not from a
     *       child typing, so it is exactly a template's own wish text and can never be a
     *       misunderstanding. This lane is a guarantee: a button must never cost a ten-second
     *       round-trip for something the watch already knows.</li>
     *   <li><b>No agent reachable</b> → fall back to the old generous matching. A best guess beats
     *       a dead watch: offline is precisely when there is nobody else to ask. Logged, so the
     *       report can show how often it happens.</li>
     * </ol>
     *
     * <p>Anything else returns empty and goes to the agent, which is now the default path rather
     * than the exception.
     */
    private static Optional<WishTemplates.Match> route(String text, WishSource source) {
        WishTemplates templates = WishCatalog.templates();
        Optional<WishTemplates.Confident> confident = templates.matchConfident(text);
        if (confident.isPresent()) {
            return Optional.of(new WishTemplates.Match(
                    confident.get().templateId(), confident.get().plan()));
        }
        if (source == WishSource.PRESET) {
            return templates.matchDetailed(text);
        }
        // ⚠ DELIBERATELY NOT widened for a TIRED Moddy. "Open for cached wishes" means a
        // confident match or a button — the child's own cached wish. A LOOSE match is a guess,
        // which is how "Spikor's airship flying in the sky" once became "make me fly"; a tired
        // Moddy says so rather than improvising, and the child keeps the buttons that are his.
        // "No agent" means none can be WOKEN, not none parked right now: a stopped pool is
        // started by this very wish, and a loose match served in the meantime is a wrong wish.
        if (!com.moddingfromamod.agent.AgentSession.canAnswer()) {
            Optional<WishTemplates.Match> loose = templates.matchDetailed(text);
            if (loose.isPresent()) {
                LOGGER.info("no agent available; falling back to a loose match '{}' for: {}",
                        loose.get().templateId(), text);
            }
            return loose;
        }
        return Optional.empty();
    }

    /**
     * The band-to-action map for the INPUT layer, as the log's {@code action} string.
     *
     * <p><b>One map, two sites, and the {@code SHADOW_} prefix applied in one place.</b> The rule
     * itself lives in {@link com.moddingfromamod.safety.SafetyInputGate#actionOf} — this is the
     * lexicon's call site and {@code AwaitWishTool} is the model's, and a band-to-action map
     * written twice is the shape CS-S1a's ledger flags as this repo's most reliable source of
     * confident wrong answers (the arm already implements it a second time in Python).
     *
     * <p>The prefix says <b>what was done</b> and {@code mode} says what the operator asked for.
     * Since CS-S2 the two agree: {@code on} enforces, so it writes the bare word, and
     * {@code shadow} acts on nothing, so it writes the prefixed one. A report that has to join two
     * fields to know whether a child was refused will one day forget to, which is why the prefix
     * exists at all — see {@code tools/safety-report.py}, which keeps the two under separate
     * headings.
     *
     * <p>WELLBEING is not a refusal. A distress flag on an otherwise FINE wish is a pass with a
     * grown-up note beside it (§1), so it only appears when nothing else earned a band.
     */
    public static String inputAction(com.moddingfromamod.safety.SafetyVerdict v) {
        return (com.moddingfromamod.safety.ChildSafetyConfig.enforcing() ? "" : "SHADOW_")
                + com.moddingfromamod.safety.SafetyInputGate.actionOf(v).name();
    }

    /**
     * What to tell the agent about a wish the cache declined.
     *
     * <p>The loose matcher's guess is worth passing on: when it happens to be right the agent can
     * just run that template instead of inventing a duplicate, and when it is wrong the words it
     * could not explain say exactly what the child actually asked for.
     */
    private static String hintForAgent(String text) {
        return WishCatalog.templates().hint(text)
                .map(h -> "The offline templates guessed '" + h.templateId() + "' but were NOT "
                        + "confident, because these words are unaccounted for: " + h.unexplained()
                        + ". Treat that guess as a suggestion only — if it is genuinely what they "
                        + "asked for you may run it, otherwise ignore it and grant the real wish.")
                .orElse("");
    }

    /**
     * <b>Is Moddy tired right now?</b> (D8) The ONE definition, asked by {@link #submit} for a
     * new wish and by the refinement path that still needs an agent,
     * {@code WishCancel.stop} ({@code /moddy redirect}): Moddy is too tired
     * whether it's a new wish or a refinement. Once a refinement skipped this entirely,
     * so while Moddy was over quota — grader up, so the dequeue's own check never fires — a
     * refinement still reached an agent that could not work.
     *
     * <p>{@link #reject} ({@code /moddy no}, the watch's 👎) is deliberately NOT a caller any more
     *: it is an undo + refund done here, with no agent,
     * so a tired Moddy still takes the thing away and gives the wish back.
     */
    public static Optional<com.moddingfromamod.health.ModdyTired.Tired> tiredNow(long now) {
        return com.moddingfromamod.health.ModdyTired.armed()
                ? com.moddingfromamod.health.ModdyTired.global().current(now,
                        com.moddingfromamod.safety.ChildSafetyConfig.enforcing())
                : Optional.empty();
    }

    /**
     * <b>"That wasn't right!"</b> — the watch's 👎 and {@code /moddy no}. <b>It is JUST UNDO</b>
     *:
     *
     * <ol>
     *   <li>what the wish made is taken away ({@link WishUndo}, the same reversal {@code /moddy
     *       stop undo} runs — a run still going is stopped first), its datapack is switched off and
     *       the learned template that produced it is forgotten;</li>
     *   <li>it is <b>not</b> tried again: no new run, no queue, no bar. The wish settles as
     *       {@link WishStatus#STOPPED} — the child ended it — with a {@link WishRejection#MARKER}
     *       result so the improvement loop can tell an undo from a stop;</li>
     *   <li>the wish comes back to his bank, once ({@link
     *       WishCharge#refundUndone}), on a world that has one.</li>
     * </ol>
     *
     * <p>Until this re-opened the wish for the agent to retry. Live, a child pressed 👎 twice
     * on a stomp power that ran three times, the third hanging until it timed out, and got no wish
     * back for either press — he had no way to say "stop, it isn't working".
     *
     * <p>A second press on the wish he has just undone lands on the SAME wish
     * ({@link WishRejection#pick}) and changes nothing: it never walks back to an earlier wish he
     * was happy with, and never pays twice. It works while Moddy is tired too: nothing
     * here needs an agent. Must be called on the server thread.
     *
     * @param complaint what the child said was wrong; may be blank
     */
    public static Outcome reject(ServerPlayer player, String complaint) {
        WishBook book = WishBook.get(player.getServer());
        Optional<Wish> target = WishRejection.pick(
                book.recent(40), player.getUUID(), System.currentTimeMillis());
        if (target.isEmpty()) {
            WishFeedback.nothingToUndo(player);
            return Outcome.EMPTY;
        }

        Wish wish = target.get();
        if (WishRejection.alreadyRejected(wish)) {
            WishFeedback.alreadyUndone(player);
            LOGGER.info("/moddy no on {} — already undone, nothing more to do", wish.shortId());
            return Outcome.UNDONE;
        }
        // NOT asked whether Moddy is tired. It once refused a tired 👎
        // because it used to re-queue the wish to an agent; it is a local undo + refund
        // that needs no agent, and refusing it would leave him stuck with the thing he rejected.
        // Only LEARNED templates can be forgotten; a library one is the server's, changed by a push.
        boolean forgot = LearnedTemplates.forget(player.getServer(), wish.templateId());

        // ── TAKE IT AWAY. A run still going is stopped first (its slot and leases released, the
        // worker refused on its next tool call), or it would keep building into the hole the undo
        // makes. Then the same reversal `/moddy stop undo` runs, from what the wish recorded making.
        com.moddingfromamod.marshal.Marshal marshal = com.moddingfromamod.marshal.Marshal.global();
        com.moddingfromamod.marshal.WishRun run = marshal.run(wish.id());
        if (run != null && run.state().isLive()) {
            marshal.cancel(wish.id(), com.moddingfromamod.marshal.CancelMode.UNDO, "", "");
        }
        // A served wish whose pillar is still rising has no run; its climax must not land later.
        ServedFanfare.cancelBeforeClimax(wish.id());
        WishUndo.Result undone = WishUndo.undo(player.getServer(), wish.id(), run);
        WishCancel.clearHud(player.getUUID(), wish.id(), marshal);
        // If that wish's effect came from a datapack, turn the pack OFF (kept on disk, so it's
        // reversible) — un-teaching the template alone leaves a live, wrong datapack running.
        boolean packOff = maybeDisableCulpritDatapack(player, wish);

        // ── GIVE THE WISH BACK, once — BEFORE the book moves, so the settle's own refund finds
        // nothing left to pay and he hears one line, not two.
        String back = WishCharge.get().refundUndone(player, wish);

        // ── AND SETTLE IT. Never PENDING again: nothing is queued, submitted or put on screen.
        book.complete(wish.id(), WishStatus.STOPPED,
                WishRejection.undoneNote(WishText.sanitize(complaint), forgot ? wish.templateId() : null));
        WishFeedback.rejected(player, undone.anything() || packOff,
                back);
        LOGGER.info("wish {} UNDONE by the child (reversed {}, couldNot {}, template '{}' forgotten: {},"
                        + " refunded: {})", wish.shortId(), undone.reversed(), undone.couldNot().size(),
                wish.templateId(), forgot, back != null);

        // The learning signal stays: the rejection record.
        com.moddingfromamod.agent.ActionLog.rejection(wish.text(), wish.templateId(), forgot,
                WishText.sanitize(complaint));
        com.moddingfromamod.agent.ActionLog.wish(wish.text(), "STOPPED", "REJECTION", "",
                player.getStringUUID());
        return Outcome.UNDONE;
    }

    /**
     * If the rejected wish looks like it produced a datapack, turn that pack OFF (kept on disk, so it
     * can be brought back). Time-correlated to the wish, so a template-only rejection — whose newest
     * pack is older than the wish — can never disable an innocent, unrelated pack. Best-effort: a
     * failure here must never stop the rejection itself from going through.
     *
     * @return true when a pack was switched off (it counts as "taken away" in what he is told)
     */
    private static boolean maybeDisableCulpritDatapack(ServerPlayer player, Wish wish) {
        try {
            var server = player.getServer();
            var newest = com.moddingfromamod.datapack.DatapackReloader.newestInstalledPack(server);
            if (newest.isEmpty()) {
                return false;
            }
            if (!com.moddingfromamod.datapack.DatapackReloader.packMatchesRejectedWish(
                    newest.get().modifiedMs(), wish.createdMs(), System.currentTimeMillis(),
                    WishRejection.MAX_AGE_MS, 60_000L)) {
                return false;
            }
            if (com.moddingfromamod.datapack.DatapackReloader.disableAndReload(
                    server, newest.get().featureId())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "🔌 I turned off that last wish for now — it wasn't right. "
                        + "(Don't worry, I kept it, so I can bring it back if you change your mind!)"));
                LOGGER.info("reject: disabled culprit datapack '{}' for wish {}",
                        newest.get().featureId(), wish.shortId());
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            LOGGER.warn("could not disable culprit datapack on reject: {}", e.toString());
            return false;
        }
    }

    /** Reset throttle state for a player (logout). */
    public static void forget(ServerPlayer player) {
        RATE_LIMITER.forget(player.getStringUUID());
    }

    /** Tokens left, for {@code /moddy status}. */
    public static int wishesLeft(ServerPlayer player) {
        return RATE_LIMITER.tokensFor(player.getStringUUID(), System.currentTimeMillis());
    }

    /**
     * Why a contextual wish skipped the offline templates — and nothing about what it means
     *. This replaced {@code WishReference.noteForAgent}, which told the agent to "use
     * modify_mob with one of [the handles]": a default that pushed every such wish towards
     * changing a creature, the exact misreading the live resolver made four times in four.
     */
    public static final String CONTEXTUAL_NOTE = "The offline templates were skipped: these words may "
            + "point at something he already has, and a fixed list of commands cannot know what. "
            + "Read his words and decide — one of his things, or something NEW — and say which in "
            + "plan_wish's `about`.";

    /**
     * Add the world to whatever the local side already had to say (items 2 and 3).
     *
     * <p>Package-private and separate from its one call site on purpose: the composition is the
     * thing at risk, so a gate has to be able to drive <b>this</b> rather than re-implement it. A
     * rule tested only where it is defined is not tested where it runs, and the failure this guards
     * is not the rendering — {@link WorldCatalogue} has its own unit tests — it is the catalogue
     * silently not being attached at all, which is exactly the state every branch was in before.
     *
     * <p>Server thread only: {@link ArtifactIndex#everything()} reads the live stores.
     */
    static String withCatalogue(String note) {
        String base = note == null ? "" : note;
        // each creature and building marked with whether it is OUT in the world now.
        String catalogue = WorldCatalogue.render(ArtifactIndex.everything(),
                InWorldCounts.snapshot(com.moddingfromamod.agent.AgentBridge.serverOrNull())).text();
        if (catalogue.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? catalogue : base + "\n" + catalogue;
    }
}
