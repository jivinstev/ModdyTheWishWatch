package com.moddingfromamod.agent.tools;

import java.util.Optional;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.BuildGate;
import com.moddingfromamod.wish.HandoverStore;
import com.moddingfromamod.wish.Wish;
import com.moddingfromamod.wish.WishBook;
import com.moddingfromamod.wish.WishFeedback;
import com.moddingfromamod.wish.WishProgress;
import com.moddingfromamod.wish.WishStatus;
import com.moddingfromamod.wish.WishText;
import com.moddingfromamod.wish.WishVerdict;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End the wish, with an honest opinion of it. <b>Every</b> wish ends here.
 *
 * <p><b>Why the verdict lives on this tool rather than a new one.</b> finish_wish was already the
 * one mandatory call at the end of a wish, so putting the judgement here makes it impossible to end
 * a wish without judging it — and impossible to judge one without ending it. Those were exactly the
 * two failure modes: on 2026-08-04 a wish ran eleven minutes and finish_wish was never called at
 * all, so the child watched a ticker climb past 700 seconds while Moddy quietly improved a creature
 * he had already killed twice.
 *
 * <p>The four paths differ in what they DO, not just in what they say — one seeds a follow-up wish,
 * one points at the reject button, one expects the mess to have been cleaned up first. See
 * {@link WishVerdict}.
 *
 * <h2>⚠ Why this does NOT absorb the {@code finish_building} it demands </h2>
 * asks whether the turn-away can be removed by having this tool simply do the step it is
 * about to insist on — a full round trip, ~$0.10 and ~6 s, on every wish where the guard fires.
 * Worth asking, and the answer is no, for two reasons that are both about what would be lost
 * rather than about effort:
 *
 * <ol>
 *   <li><b>The verdict is an ARGUMENT of this call.</b> It has already been decided by the time
 *       the guard runs. Absorbing the build step would mean taking the photo and then filing a
 *       judgement formed before it existed — which is precisely the thing the refusal complains
 *       about ("you haven't looked at what you made"), performed by the guard itself.</li>
 *   <li><b>{@code finish_building} carries the agent's own words for the child</b> — its {@code say}
 *       argument, "SPIKOR IS HERE and he BREATHES FIRE! Look!" — and this tool has no such
 *       argument to pass on. Absorbing it would replace that with something generic, and the
 *       personalised line is most of what {@code finish_building} is FOR: it also reports which
 *       claims actually work, how to set the ability off, and the "take me there" offer.</li>
 * </ol>
 *
 * <p><b>What was really costing the round trip</b> was the refusal text, which used to close with
 * <i>"just call finish_wish again and it will go through"</i>. {@code refuseFinish()} is one-shot,
 * so a retry genuinely does go through — meaning the cheapest possible response to the guard
 * satisfied it while the child was never told and the agent never looked. A toll booth with its
 * bypass posted on the sign. an earlier issue's own transcript has {@code finish_wish} called twice
 * <b>six seconds apart</b>, too fast for a photograph to have happened in between.
 *
 * <p>So the give-way behaviour is untouched — a wish that ends imperfectly beats one that cannot
 * end — and only the invitation is gone. The escape is still described, but as what it is: the
 * thing to do when {@code finish_building} <i>errors</i>, not an alternative to calling it.
 */
public final class FinishWishTool implements McpTool {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID);

    @Override
    public String name() {
        return "finish_wish";
    }

    @Override
    public String description() {
        return "END the wish, with your honest verdict — ALWAYS the last thing you do, and you must "
                + "always do it. Pick one: "
                + "'done' (it's good — the child is told and invited to wish again); "
                + "'could_be_better' (it works, but you can see an improvement — pass 'suggestion' "
                + "and the child gets it as a ready-made next wish to press); "
                + "'rough' (it works, but you're not proud of it — the child is told honestly and "
                + "shown how to throw it away); "
                + "'failed' (rare — you couldn't do it; clean up whatever you half-made BEFORE "
                + "calling this). Being honest here is the job, not a failure: a child would far "
                + "rather hear 'this bit isn't great, want me to try again?' than be handed "
                + "something broken with a fanfare.";
    }

    /** True when the call named neither a {@code verdict} nor the old {@code granted} flag. */
    static boolean gaveNoVerdict(JsonObject args) {
        return args == null || (!args.has("verdict") && !args.has("granted"));
    }

    /**
     * a defaulted DONE over a wish that changed nothing is FAILED. A verdict the agent
     * actually gave is left alone, and so is any wish that changed something.
     */
    static WishVerdict nothingChanged(WishVerdict verdict, boolean gaveNoVerdict, boolean changedNothing) {
        return gaveNoVerdict && changedNothing && verdict.granted() ? WishVerdict.FAILED : verdict;
    }

    /**
     * The verdict that will actually be recorded, given what the mod knows: a creature this wish
     * SPAWNED that is standing nowhere is not granted, whatever the verdict said —
     * ROUGH would still file the wish as DONE, and there is nothing for him to see, rough or
     * otherwise.
     *
     * <p>Pure and package-visible so this is a unit test rather than a 20-minute client run — and
     * so the test can be about BEHAVIOUR. (It also downgraded `done` over an unproven Create machine
     * or a broken train; both went with Create,.)
     */
    static WishVerdict honestVerdict(WishVerdict claimed, String missingCreature) {
        if (missingCreature != null && claimed.granted()) {
            return WishVerdict.FAILED;
        }
        return claimed;
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "id", "string", "The short wish id from list_wishes.");
        Schemas.prop(s, "verdict", "string", "One of: " + WishVerdict.choices());
        // THE DESCRIPTION, after the photo. finish_building's reveal says only "It's here!";
        // this is where he is told WHAT it is, by an agent that has now seen the picture.
        Schemas.prop(s, "say", "string",
                "WHAT HE IS LOOKING AT, from the photo finish_building handed you — one or two "
                        + "excited sentences in kid words, e.g. 'A blue garage with a red post "
                        + "and a grey ramp — drive your cars in!'. He has already heard \"It's "
                        + "here!\"; this is the description, said straight after. ⚠ NAME ONLY "
                        + "WHAT YOU PLACED AND CAN SEE IN THE PHOTO: never a part of your plan "
                        + "that is not in the picture (a spiral you did not lay, loops that came "
                        + "out as pillars, a creature you cannot see), and say plainly if it ended "
                        + "up somewhere odd (underwater, in a hill). He will walk over and look.");
        Schemas.prop(s, "note", "string",
                "Your verdict, in Moddy's voice — kid words, no jargon. ⚠ HE HAS ALREADY BEEN "
                        + "TOLD what it is: your `say` above. On "
                        + "'done' after finish_building this note is RECORDED in his wish book, not "
                        + "read out; on 'could_be_better', 'rough' or 'failed' it IS what he hears — "
                        + "and so is a 'done' note when nothing was built (a question, a help wish): "
                        + "then it is the one line that ends his wish, so write it TO HIM ('Have "
                        + "fun with your watch!') or leave it out. Never restate the say, add what "
                        + "it does not. ⚠ NEVER NAME A TOOL OR YOUR OWN STEPS (\"nothing built, so "
                        + "no finish_building\"): he reads this, and such a line is not shown. "
                        + "⚠ DESCRIBE "
                        + "WHAT YOU MEASURED, NOT WHAT YOU MEANT TO BUILD. He is going to walk over "
                        + "and look at it, so a sentence like \"the wheels crunch the logs up\" is "
                        + "a promise, and if "
                        + "your last check"
                        + " last told you those wheels were "
                        + "STOPPED it is a promise the machine will break in front of him. Saying "
                        + "what it does AND what it does not do yet is not a lesser ending — it is "
                        + "the one that survives him going to look.");
        // THE HANDOVER RECORD. Written at FINISH on purpose: only now does the agent know what
        // actually happened, as opposed to what it intended -- and `tried` in particular can only
        // be written afterwards. It is the field nothing captures today, and the one a second turn
        // of the crank most needs, because otherwise it re-walks the same dead end.
        Schemas.prop(s, "artifact", "string",
                "The id of the main thing this wish made or changed (e.g. 'turtle_king'). Pass it and "
                        + "the next agent to touch that thing gets your notes on its await_wish "
                        + "wake, instead of working it out again. A wish that made SEVERAL things "
                        + "(a castle and its seahorses) names each, comma-separated, main one first "
                        + "('underwater_sand_castle, seahorse'): every one gets the notes.");
        Schemas.prop(s, "tried", "string",
                "WHAT YOU TRIED THAT DID NOT WORK, and why you abandoned it. This is the one thing "
                        + "nothing else records, and without it the next edit walks the same dead "
                        + "end. Be specific: 'scaled the head to 3 and it clipped through the "
                        + "ceiling', not 'some things did not work'.");
        Schemas.prop(s, "careful", "string",
                "What a future edit of this thing should watch out for.");
        // THE ONE GENUINELY NEW FIELD, and it is new because agents were already smuggling it into
        // `careful` — crushing_plant's reads as a command listing rather than as a warning, which is
        // the form telling us what it needed. Everything else in a turn is free: the wish text, the
        // verdict, what was made, the design note and what was said to the child are all already
        // known to the run. A handover form with eight new fields gets eight shrugs.
        Schemas.prop(s, "state", "string",
                "WHAT IS TRUE ABOUT THIS THING NOW, one fact per line — the numbers and names a "
                        + "future edit would otherwise have to go and look up. e.g. 'scale is 1.76 "
                        + "(the tool caps growth per edit)' / 'speed 0.6, already the maximum' / "
                        + "'datapack tails_sky_flight tps him up 0.7/tick below y=45'. This is "
                        + "state, not history: overwrite it with the current truth each time.");
        Schemas.prop(s, "suggestion", "string",
                "ONLY with 'could_be_better': the improvement, written AS A WISH in the child's own "
                        + "voice so it drops straight into his wish box — e.g. 'make the dragon's fire "
                        + "even bigger'. When the wish made several things, NAME the one that could "
                        + "be better ('make the seahorses darker'), never 'make it better'. Keep it under " + WishText.SUGGESTION_MAX
                        + " characters so it fits on screen.");
        return Schemas.required(s, "id", "verdict");
    }

    /**
     * Which wish should an id that names nothing be taken to mean?
     *
     * <p><b>Measured, a real Muse Gate C½ run.</b> The agent sent {@code id: "unknown"} — a literal
     * placeholder — and then looped {@code await_wish → notify_player → finish_wish} for FIFTEEN
     * rounds until the harness killed the run at 900 seconds. It had already built the creature: 6
     * sculpts, 10 {@code apply_effect}s, 7 commands. It simply could not close the wish, so a child
     * would have been shown a finished Spikor and never told it was his.
     *
     * <p>An empty-string check cannot catch that, because {@code "unknown"} is not empty. What
     * makes it recoverable is that the worker HAS a wish and the marshal knows which: <b>an id that
     * names no wish is worth exactly as much as no id at all.</b>
     *
     * <p>Pure and Minecraft-free on purpose. The first attempt at this lived inside the server hop,
     * where {@code AgentBridge.onServer} throws in a unit test — so both tests written for it
     * passed against the UNFIXED code, measuring the absence of a server rather than the rule. A
     * rule tested only where it is defined is not tested where it runs.
     *
     * @param asked what the agent sent (may be a placeholder, or empty)
     * @param mine  the caller's own wish, from the marshal (may be empty)
     * @param known every wish id the book currently holds
     * @return the id to use, or empty when the caller must genuinely be asked
     */
    static String resolveWishId(String asked, String mine, java.util.List<String> known) {
        java.util.List<String> ids = known == null ? java.util.List.of() : known;
        String a = asked == null ? "" : asked.trim();
        if (!a.isEmpty() && ids.stream().anyMatch(k -> k.equalsIgnoreCase(a))) {
            return a;                    // the agent was right; never second-guess a real id
        }
        String m = mine == null ? "" : mine.trim();
        if (!m.isEmpty() && ids.stream().anyMatch(k -> k.equalsIgnoreCase(m))) {
            return m;                    // it named nothing, and we know what it meant
        }
        return "";                       // genuinely ambiguous — the caller must ask
    }

    @Override
    public ToolResult call(JsonObject args) {
        String askedFor = str(args, "id").trim();
        String note = str(args, "note");
        final String sayAfterPhoto = str(args, "say");
        String artifact = str(args, "artifact").trim();
        String tried = str(args, "tried");
        String careful = str(args, "careful");
        String state = str(args, "state");
        String suggestion = WishText.trimSuggestion(str(args, "suggestion"));
        // A missing or unknown verdict degrades to DONE rather than refusing. A wish that ends
        // mislabelled is a small problem; a wish that CANNOT end is the problem this whole tool
        // exists to fix. The old 'granted' flag is still honoured for the same reason.
        WishVerdict claimed = args != null && args.has("verdict")
                ? WishVerdict.parse(str(args, "verdict"))
                : (args != null && args.has("granted") && args.get("granted").isJsonPrimitive()
                        && !args.get("granted").getAsBoolean()
                                ? WishVerdict.FAILED : WishVerdict.DONE);

        // ── "WHICH WISH?" IS A QUESTION THIS SERVER CAN ANSWER ITSELF ─────────────────────────
        //
        // Measured over a real Muse run: `finish_wish` was refused FIVE times for a missing `id`,
        // and it was the run's dominant failure. The refusal is the same defect this file already
        // argues against three lines above — "a wish that CANNOT end is the problem this whole
        // tool exists to fix" — applied to the verdict but not to the id.
        //
        // And the id was never unknown. `AgentContext.currentRun()` resolves the caller's own wish
        // from the marshal, falling back to the single wish in flight; this method ALREADY calls it
        // further down, and McpProtocol uses the same lookup to attribute every call in the action
        // log. Refusing here asked the agent to go and fetch something the server was holding.
        // Resolved HERE, outside the server hop, on purpose: AgentContext is bound to the MCP
        // request thread by McpProtocol, and AgentBridge.onServer runs the body on the server
        // thread, where the binding is gone. Reading it inside the lambda would silently answer
        // "no wish" forever.
        var mine = com.moddingfromamod.agent.AgentContext.currentRun();
        final String mineId = mine == null ? "" : mine.shortId();
        // finish_building's "it's HERE!" is said when the reveal has played, a second or two
        // after it returns. Wait for it (bounded) so this tool's own words never come first.
        if (mine != null) {
            com.moddingfromamod.wish.reveal.Reveals.awaitAnnounced(mine.wishId(),
                    com.moddingfromamod.wish.reveal.Reveals.FINALE_DEADLINE_MS + 1_000);
        }
        if (askedFor.isEmpty()) {
            askedFor = mineId;
        }
        final String shortId = askedFor;
        if (shortId.isEmpty()) {
            // Still nothing — several wishes in flight and none of them this worker's. Now the
            // agent genuinely has to choose, so say so and name the tool that lists them.
            return ToolResult.error("Which wish? Pass the short id from list_wishes.");
        }
        // ── THE VERDICT IS CHECKED AGAINST WHAT THE MOD KNOWS — COERCED, NOT REFUSED ───────────
        //
        // Every other gate here asks the agent to DO something more, which may be impossible, so
        // each must give way or a wish could never end. These ask it to SAY something different,
        // which is always possible — so refusing would risk a loop for no benefit, while coercion
        // cannot loop and cannot let a false claim through. The wish still ends, on this very call.
        // Computed once into a final, because `verdict` is captured by the server lambda below.
        //
        // ── NOT OVER A CREATURE THAT IS STANDING NOWHERE ─────────────────────────
        //
        // "Your GIANT sea creature is here — it LEAPS right out of the sea!", recorded DONE, and
        // four minutes later: "I can't see the sea creature you made". The spawn had said "0×"
        // and nothing between it and this verdict asked whether anything was there. Coerced, not
        // refused, for the reason above: it asks the agent to SAY something different. Only the
        // creatures THIS wish spawned are asked about (WishRun.creaturesSpawned), so a sculpt for a
        // spawn egg is never a missing creature.
        final String missingCreature = mine != null && mine.shortId().equalsIgnoreCase(shortId)
                ? missingCreatureOf(mine) : null;
        // ── NOR A WISH THAT CHANGED NOTHING AND NAMED NO VERDICT ─────────────────────────
        //
        // A Muse session called every tool with empty arguments, had every one refused, then sent
        // `finish_wish {}` — and the missing verdict degraded to DONE over a world it never touched.
        // Coerced to FAILED, never refused, for the reason above. Only when BOTH hold: a verdict the
        // agent did give is its own judgement ("he said thanks" is a real `done` that changes
        // nothing), and a wish that did change something keeps the old default.
        final boolean changedNothing = mine != null && mine.shortId().equalsIgnoreCase(shortId)
                && !mine.hasChangedWorld() && mine.made().isEmpty() && mine.commandsRan().isEmpty();
        final WishVerdict verdict = nothingChanged(
                honestVerdict(claimed, missingCreature),
                gaveNoVerdict(args), changedNothing);
        boolean downgraded = verdict != claimed;

        if (verdict.seedsFollowUp() && suggestion.isBlank()) {
            return ToolResult.error("'could_be_better' needs a 'suggestion' — the improvement "
                    + "written as a wish the child can press, e.g. 'make his fire even bigger'. "
                    + "If you haven't got one, your verdict is 'done'.");
        }
        // Set from inside the server hop when finish_building guard turns this attempt away, so the
        // refusal comes back as a real error the agent will act on rather than a chatty success.
        final boolean[] refused = {false};
        final boolean[] alreadyEnded = {false};
        try {
            String outcome = AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                WishBook book = WishBook.get(server);
                Optional<Wish> match = book.recent(100).stream()
                        .filter(w -> w.shortId().equalsIgnoreCase(shortId))
                        .reduce((a, b) -> b);
                // A PLACEHOLDER IS NOT AN ID, AND NOT AN OMISSION EITHER — see resolveWishId,
                // which is where the rule and its evidence live so a unit test can reach them.
                if (match.isEmpty()) {
                    String instead = resolveWishId(shortId, mineId,
                            book.recent(100).stream().map(Wish::shortId).toList());
                    if (!instead.isEmpty()) {
                        match = book.recent(100).stream()
                                .filter(w -> w.shortId().equalsIgnoreCase(instead))
                                .reduce((a, b) -> b);
                    }
                }
                if (match.isEmpty()) {
                    // AND IT IS AN ERROR. This returned as a chatty SUCCESS, so `ok:true` — the
                    // 15-round loop above does not appear in any refusal count, and an agent
                    // reading a successful result has little reason to change what it sends.
                    refused[0] = true;
                    return "No wish with id " + shortId + " — call list_wishes first.";
                }
                Wish wish = match.get();

                // ── ONE WISH, ONE ENDING ─────────────────────────────────────────────
                //
                // Live, 2026-09-24: "make it daytime forever" was announced granted TWICE, each
                // time with a bold celebration and "Want to change it?". Nothing here asked whether
                // the wish had already ended, so a second call re-ran book.complete, the handover,
                // the speech and BuildGate.clear — every consequence of an ending, twice.
                //
                // A NO-OP, NOT AN ERROR, and ok:true on purpose. An error invites a fix-and-retry,
                // and there is nothing to fix: the wish ended, the child was told. The result says
                // so and names the one way on. The refused-then-given-way calls the other guards
                // make are untouched, because a refused call never completes the wish.
                if (wish.status().isFinished()) {
                    alreadyEnded[0] = true;
                    return "Wish " + wish.shortId() + " already ended as " + wish.status()
                            + " — this call changed nothing and said nothing to the child. A wish "
                            + "ends once; if it needs changing he will make a new wish. Go straight "
                            + "to await_wish.";
                }

                // The child has to have SEEN it before it can be judged and closed. Refuses once
                // and then gives way — a hard block would trap the agent bouncing between the two
                // if finish_building itself failed, which is how the reload storm worked. See BuildGate.
                if (BuildGate.forWish(wish.id()).refuseFinish()) {
                    refused[0] = true;
                    // ⚠ THE ESCAPE HATCH IS NO LONGER ADVERTISED, AND THAT IS THE FIX.
                    //
                    // refuseFinish() is one-shot by design — a wish that ends imperfectly beats one
                    // that cannot end — but this message used to close with "just call finish_wish
                    // again and it will go through", which is a posted bypass on a toll booth. The
                    // cheapest response to it is a retry, and a retry satisfies the gate: the child
                    // is never told, the agent never looks, and the round trip bought nothing.
                    // Measured 's own transcript, finish_wish was called twice SIX SECONDS
                    // apart — too fast to have taken a photo in between.
                    //
                    // The give-way behaviour is untouched; only the invitation is gone. What the
                    // agent is told instead is the two-call path that is actually cheaper for it,
                    // because the turn it is about to spend is unavoidable either way and this one
                    // ends the wish properly.
                    //
                    // NOT ABSORBED, and that is deliberate — see the class note on why finish_wish
                    // cannot simply do the finish_building it demands.
                    return "Not yet — you haven't called finish_building for this wish, so the child "
                            + "has never been told it's ready and you haven't looked at what you "
                            + "made. Your verdict is an argument of THIS call, so judging now means "
                            + "judging something you have not seen.\n\n"
                            + "Do this, and it is the SHORTEST way to end the wish: call "
                            + "finish_building with your words for him, look at the photo it hands "
                            + "back, then call finish_wish once with the verdict that photo "
                            + "supports. Two calls, and he gets told what you made.\n\n"
                            + "Only if finish_building itself ERRORS: say so in your verdict and "
                            + "call finish_wish again — a wish that ends imperfectly beats one that "
                            + "never ends.";
                }
                var thisRun = com.moddingfromamod.marshal.Marshal.global().run(wish.id());
                // ── NOTHING WAS BUILT, AND THE NOTE SAYS IT WAS TESTED ─────────────
                //
                // Every guard above needs an artifact the mod knows about, so a wish that built
                // none is unreachable by all of them BY CONSTRUCTION — which is exactly how a
                // hand-placed redstone drawbridge reached a child with "I tested it both ways"
                // attached and nothing anywhere having tested it. This is the one complaint whose
                // enabling condition is the ABSENCE of an artifact.
                //
                // Deliberately not an objection to the build: commands-and-blocks is a fine answer
                // for a child. Only the claim is refused.
                String testedClaim = com.moddingfromamod.wish.TestedClaim.claim(note);
                if (testedClaim != null
                        && com.moddingfromamod.wish.TestedClaim.nothingCheckableWasUsed(
                                thisRun == null ? java.util.List.of() : thisRun.made(),
                                thisRun == null ? java.util.List.of() : thisRun.toolsUsed())
                        && BuildGate.forWish(wish.id()).nudgeAboutTestedClaim()) {
                    refused[0] = true;
                    return com.moddingfromamod.wish.TestedClaim.refusal(testedClaim);
                }
                // ── THE LINE PROMISES A PART NO CALL BUILT ────────────────────────
                //
                // TestedClaim's sibling: not "I tested it" but "a glowing red heart", "your
                // underground base", "now pulls WAY harder" — a part he will go and look for and
                // that no call this wish made could have produced. Read from what the calls
                // actually did (WishRun.evidence, written by McpProtocol), never from their
                // names or notes. Once, like every guard here: the same say sent again passes,
                // because a phrase list can be wrong and a wish that cannot end is worse.
                if (thisRun != null && sayAfterPhoto != null && !sayAfterPhoto.isBlank()) {
                    if (!thisRun.existedBefore().isEmpty()) {
                        thisRun.evidence().markEdited();
                    }
                    var unbacked = com.moddingfromamod.wish.SayClaim.check(
                            sayAfterPhoto, thisRun.evidence());
                    if (!unbacked.isEmpty()
                            && BuildGate.forWish(wish.id()).nudgeAboutUnbackedSay()) {
                        refused[0] = true;
                        return com.moddingfromamod.wish.SayClaim.refusal(unbacked);
                    }
                }
                // ── THE CREATURE IT SPAWNED IS STANDING NOWHERE ─────────────
                //
                // Once, so the agent can put him there or say so; if it ends the wish granted
                // anyway, honestVerdict records it as `failed` (above).
                if (missingCreature != null && claimed.granted()
                        && BuildGate.forWish(wish.id()).nudgeAboutMissingCreature()) {
                    refused[0] = true;
                    return "Not yet — no '" + missingCreature + "' is standing anywhere in the "
                            + "world. This wish spawned it, and it is not there, so he cannot see "
                            + "it; telling him \"it's here\" is the one thing he can check. Spawn "
                            + "it again somewhere open (run_command `wishmob spawn "
                            + missingCreature + "`) and read the count it reports, then finish. If "
                            + "it will not stand, finish with verdict 'failed' and tell him "
                            + "honestly that the design is saved and it would not appear.";
                }
                // ── GRANTED WITH COMMANDS AND NEVER TAUGHT ────────────────────────
                //
                // 38% of granted wishes on a live server could not be saved, and about half of those were
                // commands a template accepts (night vision, an army of zombies, 100 creepers): the
                // agent simply never called add_template. Refused ONCE with a draft built from what
                // actually ran; the keywords, icon and `say` stay the agent's (D1 — no
                // auto-teach). A help wish (D3) and a wish already taught are never asked.
                String teach = teachingRefusal(wish, thisRun, claimed.granted(),
                        com.moddingfromamod.datapack.DatapackStore::exists);
                // ── A WISH OF SEVERAL PARTS, UNTAUGHT OR TAUGHT SHORT ─────────────
                //
                // The command nudge above never asks a wish that made a saved thing (it is not a
                // one-off), so a creature and its court, or a compass and a map, ended with no
                // recipe and nothing said. Same one-time refusal, from what the wish made.
                if (teach == null && claimed.granted() && thisRun != null && !isHelp(wish, thisRun)) {
                    java.util.List<String> taughtCommands = new java.util.ArrayList<>();
                    for (String t : thisRun.taughtIds()) {
                        taughtCommands.addAll(com.moddingfromamod.wish.LearnedTemplates.commandsOf(t));
                    }
                    teach = com.moddingfromamod.marshal.WishParts.teachRefusal(
                            com.moddingfromamod.wish.LearnedTemplates.partsOf(thisRun),
                            !thisRun.taughtIds().isEmpty(), taughtCommands);
                }
                if (teach != null && BuildGate.forWish(wish.id()).nudgeAboutTeaching()) {
                    refused[0] = true;
                    return teach;
                }
                // ── THE REVEAL, AS A BACKSTOP ──────────────────────────────────────
                //
                // finish_building is where the reveal BELONGS -- it is the beat the child is told
                // to look, so the reload overlaps the good news. But BuildGate.refuseFinish()
                // refuses only ONCE and then gives way, deliberately, because a wish that ends
                // imperfectly beats one that cannot end. So a wish CAN end having never called
                // finish_building, and on that path nothing else would ever release the pack: the
                // stock child would be left with a permanently invisible creature and no further
                // event to fix it.
                //
                // Idempotent (RigReveal.reveal returns immediately on a sha1 already
                // released), so on the ordinary path this costs nothing and the child freezes once.
                com.moddingfromamod.vanilla.RigReveal.reveal(server);
                book.complete(wish.id(),
                        verdict.granted() ? WishStatus.DONE : WishStatus.FAILED, note);
                // TOO SPECIAL TO SAVE —. Read BEFORE Marshal.finish() below, which releases
                // the run and with it made().
                // ...and never said for a HELP wish (D3): "I can't see the creature" or
                // "he stopped following me" is not a thing to save, and the line reads as failure.
                var oneOffRun = com.moddingfromamod.marshal.Marshal.global().run(wish.id());
                boolean tooSpecialToSave = recordIfOneOff(wish, oneOffRun, verdict.granted(),
                        com.moddingfromamod.datapack.DatapackStore::exists)
                        && !isHelp(wish, oneOffRun);
                // …and the TRUE line when it changed a creature he keeps: the edit is saved.
                String savedEdit = oneOffRun == null ? null
                        : com.moddingfromamod.wish.OneOffWish.savedEditLine(oneOffRun.creaturesEdited());
                // WHAT IT MADE, by the exact key — the Library page's join from a
                // candidate wish to the thing it would promote. Same reason to read it here.
                if (verdict.granted()) {
                    com.moddingfromamod.marshal.WishRun made =
                            com.moddingfromamod.marshal.Marshal.global().run(wish.id());
                    if (made != null) {
                        com.moddingfromamod.agent.ActionLog.made(wish.shortId(), loggedMade(made));
                        // A WISH AND ITS PARTS — what it made, recorded on the recipes it
                        // taught, now that the run knows all of it.
                        // (On the server thread already: this runs inside onServer above.)
                        com.moddingfromamod.wish.LearnedTemplates.recordParts(
                                com.moddingfromamod.agent.AgentBridge.serverOrNull(), made);
                    }
                }
                // The work is over: release the slot, the leases and the worker. Doing it here
                // rather than on the agent's next await_wish means a slot is never held by a wish
                // that has already ended.
                // The handover record, before the run is closed -- it reads the run's own design
                // note and what it made, both of which are per-wish state that finish() releases.
                recordHandover(wish, artifact, tried, careful, state, note, verdict);
                com.moddingfromamod.marshal.Marshal.global().finish(wish.id());
                // ...AND THE AMBIENT INTENT, which is per-wish state exactly like the slot above.
                // await_wish stamps it (ProvenanceContext.begin(wish.text())) so anything persisted
                // while granting is attributed to the right wish; nothing ever took it down again.
                // ProvenanceContext.clear() existed for this from the start -- "forget the current
                // intent + note so nothing is mis-attributed later" -- with ZERO production callers,
                // so it had never once run in a real session (the shape).
                //
                // Left set, a finished wish's intent stays ambient and is inherited by the next
                // artifact published before the next await_wish stamps its own. That is precisely
                // what the Provenance Contract forbids: attributing one wish's reasoning to another
                // wish's artifact. Read AFTER recordHandover, which needs the wish's own note.
                com.moddingfromamod.provenance.ProvenanceContext.clear();

                ServerPlayer player = server.getPlayerList().getPlayer(wish.playerId());
                var spokenRun = com.moddingfromamod.marshal.Marshal.global().run(wish.id());
                // ── WHAT IT IS, NOW THAT THE PHOTO HAS BEEN JUDGED ─────────────────
                // finish_building's reveal said "It's here!" (awaitAnnounced above kept that first);
                // this is the description, from the photo — or, failing that, the plan-time say.
                boolean celebrated = spokenRun != null && spokenRun.celebrated();
                var described = com.moddingfromamod.wish.RevealWords.choose(sayAfterPhoto,
                        spokenRun == null ? "" : spokenRun.plannedSay());
                if (verdict.granted()
                        && described.from() != com.moddingfromamod.wish.RevealWords.From.NONE) {
                    LOGGER.info("[reveal] wish {}: described from the {}", wish.shortId(),
                            described.from().name().toLowerCase(java.util.Locale.ROOT));
                    java.util.List<java.util.UUID> online = server.getPlayerList().getPlayers()
                            .stream().map(ServerPlayer::getUUID).toList();
                    for (java.util.UUID id : com.moddingfromamod.marshal.WishAudience.forNarration(
                            wish.playerId(), online)) {
                        ServerPlayer p = server.getPlayerList().getPlayer(id);
                        if (p == null) {
                            continue;
                        }
                        if (celebrated) {
                            com.moddingfromamod.wish.WishFeedback.described(p, described.text());
                        } else {
                            // No finish_building, so no reveal: these words ARE the one celebration.
                            WishFeedback.built(p, described.text(), false);
                        }
                    }
                    if (!celebrated && spokenRun != null) {
                        spokenRun.celebrate();
                    }
                    celebrated = true;
                }
                if (player != null) {
                    // ONE CELEBRATION PER GRANT: finish_building already gave the bold line,
                    // the level-up and the sparkles if it ran for this wish.
                    speak(player, verdict, note, suggestion, tooSpecialToSave, savedEdit, celebrated,
                            spokenRun != null && spokenRun.toolsUsed().contains("add_template"));
                }
                // However it ended, the work is over — take the progress bar down. A bar left
                // floating after the work stops is the same "is it still going?" ambiguity the
                // bar was added to remove.
                //
                // ⚠ OUTSIDE the `player != null` block. It was inside it, so a wish whose
                // child had logged off before it finished — or any canary, whose wisher is a
                // stand-in no player list holds — kept its bar forever. Nobody saw it (the bar has
                // no viewers), but WishProgress.inFlight() counted it, so the wish cam never
                // believed the server was idle: it drained the full 5-minute cap instead of the
                // 2-minute linger every time, logging "a wish is wedged". Measured on a live server.
                //
                // ⚠ BY `wish.id()`, the wish this call just finished — never AgentContext.currentRun().
                // Marshal.finish (above) has already dropped this worker's run, so currentRun() came
                // back null, or fell through to "the only wish still running" — ANOTHER wish, whose
                // bar it then took down while this one's stayed up. Clearing by player took down every
                // other wish's bar the same way; the id is the only key that names THIS wish.
                WishProgress.clearWishBar(wish.id());
                BuildGate.settle(wish.id());
                // ⚠ TAKE DOWN THE TEST PROPS, AFTER the reveal and before the run is
                // forgotten. The armour stand the agent summoned to aim a fire breath at is an
                // ordinary entity in the child's world and nothing ever removed it -- found by
                // photographing the child's own screen, because no gate has eyes and no prop is
                // wrong enough to trip an assertion.
                //
                // Here rather than at the start of the next wish, because the run's ledger is what
                // makes the rule "what this wish made and did not keep" rather than "it looks like
                // an armour stand", and a run is forgotten on a schedule. See WishProps.
                //
                // ⚠ LOOKED UP BY WISH ID rather than through AgentContext, and the reason is
                // narrower than it first looks: AgentBridge.onServer re-binds the worker across
                // the thread hop, so the thread-local answer here is correct too. What the wish id
                // adds is that it cannot be wrong for a caller that never went through that hop --
                // a gate, a command, a future direct call -- and this tool already holds it.
                String tidied = "";
                var propRun = com.moddingfromamod.marshal.Marshal.global().run(wish.id());
                if (propRun != null) {
                    var swept = com.moddingfromamod.wish.WishProps.sweep(server, propRun);
                    tidied = com.moddingfromamod.wish.PropSweep.report(
                            swept.removed(), swept.alreadyGone(), swept.kept());
                }
                return "Wish " + shortId + " ended as " + verdict + "." + tidied
                        + (verdict.seedsFollowUp()
                                ? " The child has been offered: \"" + suggestion + "\"." : "")
                        + " Now go straight back to await_wish.";
            });
            if (!refused[0] && !alreadyEnded[0] && downgraded) {
                // SAID OUT LOUD. A silent downgrade would leave the agent believing it reported
                // `done`, so it would learn nothing and do the same next time — and the whole point
                // is that the claim did not match what the mod could see.
                // (Until the other branch was the unproven-machine note, so a downgrade
                // was explained as "'null' has never had anything travel through it".)
                outcome = missingCreature != null
                        ? outcome + "\n\n" + com.moddingfromamod.marshal.SpawnTruth.note(
                                missingCreature)
                        : outcome + "\n\nNote: you named no verdict and nothing in the world "
                        + "changed, so this was recorded as '" + verdict + "'.";
            }
            return refused[0] ? ToolResult.error(outcome) : ToolResult.ok(outcome);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** The first creature this run spawned of which none is standing in any world, or null. */
    private static String missingCreatureOf(com.moddingfromamod.marshal.WishRun run) {
        var attempts = run.creaturesSpawned();
        if (attempts.isEmpty()) {
            return null;        // no hop to the server for the wishes that spawned nothing
        }
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                return com.moddingfromamod.marshal.SpawnTruth.missing(attempts, species -> {
                    for (var level : server.getAllLevels()) {
                        if (!com.moddingfromamod.mob.Creatures.carriersOf(level, species).isEmpty()) {
                            return true;
                        }
                    }
                    return false;
                });
            });
        } catch (AgentBridge.Unavailable e) {
            return null;        // cannot look, so do not overrule the agent
        }
    }

    /** Moddy's four voices. Kept out of the tool body so it decides nothing about wording. */
    private static void speak(ServerPlayer player, WishVerdict verdict, String note,
                              String suggestion, boolean tooSpecialToSave, String savedEdit,
                              boolean alreadyCelebrated, boolean taughtAButton) {
        // A wish that came true and cannot be saved should not VANISH without warning on the next
        // reload --. Said in tone and only on a grant: the wish WORKED and he is watching it
        // work, so this must read as "special", never as a failure.
        if (tooSpecialToSave && verdict.granted()) {
            WishFeedback.tooSpecialToSave(player,
                    com.moddingfromamod.wish.OneOffWish.TELL_THE_CHILD);
        } else if (savedEdit != null && verdict.granted()) {
            // an edit of a creature he keeps IS saved — say so, in the same voice.
            WishFeedback.tooSpecialToSave(player, savedEdit);
        }
        switch (verdict) {
            case DONE -> WishFeedback.verdictDone(player, note, alreadyCelebrated,
                    !taughtAButton);
            case COULD_BE_BETTER -> {
                WishFeedback.verdictCouldBeBetter(player, note, suggestion, alreadyCelebrated);
            }
            case ROUGH -> WishFeedback.verdictRough(player, note);
            case FAILED -> WishFeedback.verdictFailed(player, note);
        }
    }

    /**
     * Write down what the next agent needs, from what this wish already knows.
     *
     * <p>Most of it is not asked for: the design note is already on the run
     * ({@code ProvenanceContext.note}), and what was made is already recorded for the undo. Only
     * {@code tried} and {@code careful} are genuinely new, which is why they are the only two new
     * required-ish arguments — a handover form with eight fields would be filled in with eight
     * shrugs.
     */
    private static void recordHandover(Wish wish, String artifact, String tried, String careful,
                                       String state, String saidToChild, WishVerdict verdict) {
        var run = com.moddingfromamod.marshal.Marshal.global().run(wish.id());
        // EVERY part of a compound wish (a castle and its seahorses), not only the first
        // thing made; the first is the primary a pronoun-only next wish is about.
        java.util.List<String> ids = com.moddingfromamod.marshal.HandoverTargets.of(artifact,
                com.moddingfromamod.wish.PhotoParts.noted(run),
                run == null ? java.util.List.of() : run.made());
        for (int i = ids.size() - 1; i >= 0; i--) {   // the primary last, so it is what the run keeps
            recordHandover(wish, run, ids.get(i), tried, careful, state, saidToChild, verdict);
        }
    }

    private static void recordHandover(Wish wish, com.moddingfromamod.marshal.WishRun run, String id,
                                       String tried, String careful, String state, String saidToChild,
                                       WishVerdict verdict) {
        try {
            if (id == null || id.isEmpty()) {
                return;   // a wish that made nothing has nothing to hand over
            }
            String kind = "";
            if (run != null) {
                for (var made : run.made()) {
                    if (made.id().equalsIgnoreCase(id)) {
                        kind = made.kind();
                        break;
                    }
                }
            }
            String where = "";
            if (run != null && run.anchor() != null) {
                var a = run.anchor();
                where = a.x() + "," + a.y() + "," + a.z() + " in " + a.dimension();
            }
            var aliases = com.moddingfromamod.marshal.AliasBackfill.from(
                    id, wish.text(), java.util.List.of());
            var record = new com.moddingfromamod.marshal.HandoverRecord(
                    id, kind, wish.text(), aliases,
                    run == null ? "" : run.note(),
                    tried, where, 1, careful, System.currentTimeMillis());
            // THE TURN JOURNAL — the thing that actually accrues. Almost every field is free, taken
            // from what the run already knows, which is why this adds one argument to the tool and
            // not eight. See ArtifactMemory for what each one is for.
            var turn = new com.moddingfromamod.marshal.ArtifactMemory.Turn(
                    wish.id().toString(), System.currentTimeMillis(),
                    run == null ? "" : String.valueOf(run.workerId()),
                    wish.text(),
                    run == null ? "" : run.subject(),
                    describeMade(run),
                    run == null ? "" : run.note(),
                    // One entry per dead end, never merged into prose: the old design's single
                    // 600-char field for the WHOLE LIFE of an artifact is what compressed out the
                    // reasons the first three shapes were wrong.
                    splitLines(tried),
                    saidToChild,
                    verdict == null ? "" : verdict.name().toLowerCase(java.util.Locale.ROOT),
                    careful);
            // THE JOURNAL FIRST, AND THE ORDER IS LOAD-BEARING.
            //
            // append() reads the journal, finds nothing on a first wish, and MIGRATES whatever
            // handover record exists. Write the legacy record first and it migrates the one this
            // very call just wrote — so every brand-new artifact got a phantom turn 1 reading
            // "carried over from 1 earlier edit(s)", a duplicate of the real turn, and an edit
            // count one too high. Seen in the first real ladder run, on the first wish.
            com.moddingfromamod.wish.ArtifactMemoryStore.append(
                    id, kind, turn, wish.text(), where, splitLines(state), aliases);

            // Still written afterwards, for one release: a world that has only ever seen the old
            // format must not be reset to nothing the first time it opens, and keeping both means
            // the migration can be re-run if the new store is ever found wanting.
            HandoverStore.write(record);
            // THE OTHER HALF OF OWNERSHIP. await_wish records it for a refinement, which already
            // names its artifact; a CREATION names nothing, and only acquires an id here. Recording
            // at one end only loses every ladder's first rung — wish 1 makes Zippy and is forgotten,
            // so wish 2 routes as though Zippy had no history, which is the arm silently not held.
            com.moddingfromamod.marshal.ArtifactAffinity.global()
                    .remember(id, run == null ? "" : run.workerId());
            // …AND ON THE RUN, so "what did he last work on" can answer for a class whose things
            // are not undoable yet. Affinity remembers who owns an artifact; this remembers which
            // artifact the NEXT pronoun-only refinement is about. Without both, an item wish is
            // routed as though nothing had ever been made. See WishRun#finishedArtifact.
            if (run != null) {
                run.finishedArtifact(id);
            }
        } catch (RuntimeException e) {
            // A note must never be able to fail a wish that worked.
            LOGGER.warn("could not record the handover for wish {}: {}", wish.shortId(), e.toString());
        }
    }

    /** What this wish actually made or changed — free, straight off the run's undo record. */
    /**
     * Record a wish that came true and left nothing behind —.
     *
     * <p>A named seam rather than an inline block, for the reason this repo keeps relearning: a
     * rule tested only where it is DEFINED is not tested where it RUNS. {@code OneOffWish} is pure
     * and has its own tests; what this adds is the JOIN — the run's {@code made()}, the datapack
     * store, and the verdict — which is the part that was wrong in the case that prompted the
     * issue.
     *
     * @return whether this grant was recorded as a one-off
     */
    static boolean recordIfOneOff(Wish wish, com.moddingfromamod.marshal.WishRun run,
                                  boolean granted,
                                  java.util.function.Predicate<String> datapackOnDisk) {
        return recordIfOneOff(wish, run, granted, datapackOnDisk,
                com.moddingfromamod.wish.OneOffWishLog.file());
    }

    /** The same, writing to a named file — the seam that keeps Gate A hermetic. */
    static boolean recordIfOneOff(Wish wish, com.moddingfromamod.marshal.WishRun run,
                                  boolean granted,
                                  java.util.function.Predicate<String> datapackOnDisk,
                                  java.nio.file.Path log) {
        if (!granted) {
            return false;      // a wish that did not come true is not a one-off, it is a failure
        }
        java.util.List<com.moddingfromamod.marshal.WishRun.Made> made =
                run == null ? java.util.List.of() : run.made();
        if (!com.moddingfromamod.wish.OneOffWish.isOneOff(made,
                run == null ? java.util.List.of() : run.toolsUsed(),
                run == null ? java.util.List.of() : run.creaturesEdited(), datapackOnDisk)) {
            return false;
        }
        com.moddingfromamod.wish.OneOffWishLog.append(log,
                com.moddingfromamod.wish.OneOffWishLog.record(
                        wish == null ? "" : wish.shortId(), wish == null ? "" : wish.text(), made,
                        com.moddingfromamod.runtime.WorldPaths.worldName(),
                        com.moddingfromamod.runtime.WorldPaths.sessionId(),
                        System.currentTimeMillis(), isHelp(wish, run)));
        return true;
    }

    /**
     * A help wish (owner D3): a question, a follow-up {@code plan_wish} flagged, or a run
     * that changed nothing in the world. It is told nothing about saving.
     */
    static boolean isHelp(Wish wish, com.moddingfromamod.marshal.WishRun run) {
        boolean question = wish != null && com.moddingfromamod.wish.WishIntent.isQuestion(wish.text());
        return com.moddingfromamod.wish.OneOffWish.isHelp(question, run != null && run.followup(),
                changedWorld(run));
    }

    /** Tools that touch nothing he could see change: bookkeeping around a grant. */
    private static final java.util.Set<String> NOT_A_CHANGE = java.util.Set.of(
            "add_template", "conjure_at", "finish_building", "finish_wish", "reload_datapacks");

    /** Did this run change anything in the world: a command that worked, a thing made, a trip? */
    static boolean changedWorld(com.moddingfromamod.marshal.WishRun run) {
        if (run == null) {
            return false;
        }
        if (!run.commandsRan().isEmpty() || !run.made().isEmpty() || run.wentTo() != null
                || !run.creaturesSpawned().isEmpty() || !run.commandSpawned().isEmpty()) {
            return true;
        }
        for (String t : run.toolsUsed()) {
            if (!NOT_A_CHANGE.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one-time "teach it" refusal, or null when this wish should not be asked: not
     * granted, not one-off (it left a recipe or was taught), help, or nothing a template can hold
     * (a successful line the guard refuses, or no command at all).
     */
    static String teachingRefusal(Wish wish, com.moddingfromamod.marshal.WishRun run, boolean granted,
                                  java.util.function.Predicate<String> datapackOnDisk) {
        if (!granted || run == null || run.toolsUsed().contains("add_template")) {
            return null;
        }
        if (!com.moddingfromamod.wish.OneOffWish.isOneOff(run.made(), run.toolsUsed(),
                run.creaturesEdited(), datapackOnDisk)
                || isHelp(wish, run)) {
            return null;
        }
        var draft = com.moddingfromamod.wish.TeachDraft.of(run.commandsRan(), run.wentTo(),
                com.moddingfromamod.wish.CommandGuard::isAllowed, run.mobChanges());
        return draft == null ? null : com.moddingfromamod.wish.TeachDraft.refusal(draft);
    }

    /**
     * What the action log records this wish as having made: {@code made()}, plus every
     * creature whose saved design it changed. The account page groups a child's wishes by these ids
     * ({@code catalog-export.py thing_of}), so without them "make my snake bigger" (a live-mob handle
     * only) and "make it blue" (nothing at all) were entries of their own and the creature's entry
     * kept its first photos and name. Logged only, never put on the run: undo reads {@code made()}.
     */
    static java.util.List<com.moddingfromamod.marshal.WishRun.Made> loggedMade(
            com.moddingfromamod.marshal.WishRun run) {
        java.util.List<com.moddingfromamod.marshal.WishRun.Made> out = new java.util.ArrayList<>(run.made());
        for (var e : run.creaturesEdited()) {
            var m = new com.moddingfromamod.marshal.WishRun.Made(
                    com.moddingfromamod.marshal.MadeKind.CREATURE, e.id());
            if (!out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private static String describeMade(com.moddingfromamod.marshal.WishRun run) {
        if (run == null || run.made().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var m : run.made()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(m.kind()).append(' ').append(m.id());
        }
        return sb.toString();
    }

    /**
     * One agent sentence per line or per bullet, so a list stays a list.
     *
     * <p>The agent writes {@code tried} and {@code state} as prose with newlines or bullets, and
     * keeping them as separate entries is what lets the renderer de-duplicate across turns and drop
     * the oldest rejections individually rather than losing the whole field at once.
     */
    private static java.util.List<String> splitLines(String s) {
        if (s == null || s.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : s.split("\\R|(?<=\\.)\\s+(?=·)|\\s+·\\s+")) {
            String t = line.strip().replaceFirst("^[-·*]\\s*", "").strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
