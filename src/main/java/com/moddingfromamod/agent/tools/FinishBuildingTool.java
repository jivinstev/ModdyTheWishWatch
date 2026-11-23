package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.WishFeedback;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.server.level.ServerPlayer;

/**
 * The moment the wish becomes real: tell the child, then LOOK at it.
 *
 * <p><b>Why this is one tool and not three.</b> Every part of it was previously optional, and each
 * one got skipped in the same live wish on 2026-08-04:
 *
 * <ul>
 *   <li>Nothing announced the first complete version. Spikor appeared breathing fire, it looked
 *       finished, and Moddy silently kept working for another nine minutes — so a working wish read
 *       as a hung one.</li>
 *   <li>The photo was the agent's job to remember, so it came fifth rather than first.</li>
 *   <li>Judging happened whenever the agent felt like it, if at all — that wish never finished.</li>
 * </ul>
 *
 * <p>Folding them into one mandatory call makes the good behaviour the only behaviour: the child is
 * told, the shutter fires at exactly the right moment, and the agent gets the picture back so its
 * verdict is based on what the child can actually see rather than on what it believes it built.
 *
 * <p>It also draws the line the whole redesign turns on. Everything before finish_building is workshop and
 * may loop freely; everything after is a judgement and a handover. There is no third option where
 * Moddy quietly keeps tinkering — in a live world the child interferes, and on that same day he
 * killed the creature twice while it was still "improving" something that no longer existed.
 */
public final class FinishBuildingTool implements McpTool {

    @Override
    public String name() {
        return "finish_building";
    }

    @Override
    public String description() {
        return "Call this the MOMENT everything you planned is built — before checking anything. It "
                + "ENDS the wish for the child: it is shown to him and he is told it is done. Then it "
                + "takes a photo and hands it back to you — that photo is for your verdict and your "
                + "closing sentence only, so look at it properly. After finish_building nothing more "
                + "can be built or changed for this wish (those tools refuse); you call finish_wish. "
                + "If you want to LOOK before you are done, call photograph yourself first. If it "
                + "isn't perfect, that is what the 'could_be_better' verdict is for — "
                + "the child would far rather have it now and wish again than wait while you polish. "
                + "Once finish_wish has ended the wish this is refused — a wish ends once.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // NO DESCRIPTION HERE. A `say` written now is written before the photo exists, and
        // it described the plan — a spiral track nobody laid, "3 MASSIVE loops" that were pillars,
        // a carwash with brushes standing underwater. The reveal now says a short "It's here!" by
        // itself, and the description is finish_wish's `say`, written after the photo is judged.
        // A `say` passed here anyway is held as the fallback (RevealWords), never said at the reveal.
        Schemas.prop(s, "say", "string",
                "LEAVE THIS OUT. At the reveal Moddy says a short \"It's here — have a look!\" by "
                        + "himself (the one celebration, bold, with a sound). You have not seen "
                        + "the photo yet, so do not describe the thing here: describe it in "
                        + "finish_wish's `say`, after you have looked at the photo this hands back "
                        + "— naming only what you PLACED and can see in it.");
        // NOT "the creature id". Every framing path in PhotographTool is gated on this, and an
        // agent that had just built a TRAIN read "creature" and left it blank on all three rungs of
        // a real ladder — so the child's wish card was a plain snapshot from wherever he stood.
        // The default now covers it either way, but the wording should not have been steering the
        // agent wrong in the first place.
        Schemas.prop(s, "subject", "string",
                "OPTIONAL: the id of the thing you made — a creature, a building, a "
                        + "machine or an item — so the camera is pointed at it and frames it "
                        + "properly. Leave it out and it defaults to the newest thing THIS wish made "
                        + "(never an earlier wish's), which is usually right.");
        Schemas.prop(s, "label", "string",
                "OPTIONAL short name for the photo file. Defaults to the subject, or 'reveal'.");
        // A VERDICT ON EVERY THING HE ASKED FOR. See WishClaims: an agent finished one wish five
        // times claiming it worked, having verified only what was easy to measure.
        Schemas.prop(s, "claims", "array",
                "REQUIRED if you listed claims in plan_wish: one entry per claim, "
                        + "{\"claim\":\"<his words>\",\"works\":true|false,\"evidence\":\"what you "
                        + "SAW that proves it\"}. Evidence means an observation — 'dropped a log in "
                        + "and a bottle came out', not 'the datapack loaded'. If something does not "
                        + "work say works:false: he is told that bit isn't working yet, in one "
                        + "sentence, and an honest 'not yet' beats a confident sentence he disproves "
                        + "in ten seconds. He is NOT shown a checklist — a claim that works says "
                        + "nothing extra, so put what you want him to hear in `say`. A claim with "
                        + "works:true counts as checked ONLY if it also carries a "
                        + "\"check\" the server runs before he is told — {\"kind\":\"creature_count\","
                        + "\"what\":\"pickleball_player\",\"min\":2,\"near\":\"wish\",\"radius\":8}. "
                        + "Kinds: " + com.moddingfromamod.wish.ClaimCheck.kinds() + ". `what` is a "
                        + "creature id, mob type or name, a block/item/effect id, or a datapack id. "
                        + "Checks ask about THIS wish's own things by identity: creature_count counts "
                        + "the creatures this wish spawned WHEREVER they are (a follower that walked "
                        + "off with him still counts) plus any others within the radius; "
                        + "item_in_inventory counts what THIS wish handed over that he still has — "
                        + "hotbar, inventory or Wish Bag — named by its wish key (boomerang_plain, "
                        + "exactly as make_item printed it), item id or name, so a thing he already "
                        + "had, or an older wish's, never counts; block_present scans the radius you "
                        + "ask for, up to " + com.moddingfromamod.wish.ClaimCheck.MAX_RADIUS + ". "
                        + "Creature checks are WATCHED for 3 seconds, so a mob that wanders off or a "
                        + "pet that does not come fails. If the world disagrees you are sent back once, "
                        + "and if you still finish he is told that bit isn't working; a works:true "
                        + "claim with no check is recorded as 'tried', not as checked.");
        return s;
    }

    /**
     * The wish this call is for, or null when there is none.
     *
     * <p>A worker that names itself is answered from its OWN binding and nothing else: a worker
     * whose wish has finished has no run, and falling back to "the single wish in flight" would
     * hand it somebody else's — which is how a stale second {@code finish_building} could
     * announce another child's wish. Only an unnamed caller (not through MCP) gets that fallback.
     */
    static com.moddingfromamod.marshal.WishRun boundRun() {
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        if (worker != null && !worker.isBlank()) {
            return com.moddingfromamod.marshal.Marshal.global().runForWorker(worker);
        }
        return com.moddingfromamod.agent.AgentContext.currentRun();
    }

    /** What the agent is told when it has no wish to finish building. */
    static final String NO_RUN = "You have no wish in progress, so nothing was shown to anyone and "
            + "no photo was taken. If you just called finish_wish, that wish is over and the child "
            + "has been told — a wish ends once. Call await_wish.";

    @Override
    public ToolResult call(JsonObject args) {
        // ── NO RUN, NO ANNOUNCEMENT ────────────────────────────────────────────────────
        //
        // Live, 2026-09-24: after finish_wish had released the run, a second finish_building still
        // told every online player "✨ It's daytime FOREVER!" in bold with a level-up, and re-armed
        // BuildGate so the second finish_wish went through as well. Its owner lookup found nothing
        // and the all-players loop never needed one. Asked FIRST, so a stale call spends none of
        // the one-shot nudge budgets below either.
        final com.moddingfromamod.marshal.WishRun finishRun = boundRun();
        if (finishRun == null || finishRun.state().isFinished()) {
            return ToolResult.error(NO_RUN);
        }
        // ── WILL IT STAND, AND CAN HE CLIMB IT? Asked of every building this wish made
        // and of the subject, once, before he is told. A ladder that hangs on nothing or leads into
        // a ceiling looks finished in every photograph.
        String unsound = com.moddingfromamod.builder.BuildSupport.holdReveal(
                buildsOf(finishRun, str(args, "subject")));
        if (unsound != null) {
            boolean nudge = false;
            try {
                nudge = AgentBridge.onServer(() -> com.moddingfromamod.wish.BuildGate
                        .forWish(finishRun.wishId()).nudgeAboutSupport(), 5_000L);
            } catch (AgentBridge.Unavailable e) {
                nudge = false;   // never let the guard be the reason a wish cannot end
            }
            if (nudge) {
                return ToolResult.error(unsound);
            }
        }

        // ── ANSWER WHAT HE ASKED FOR, before he is told it is ready.
        //
        // Recorded verdicts first, so the nag below can name what is still open.
        if (args != null && args.has("claims") && args.get("claims").isJsonArray()) {
            // ── AND ASK THE WORLD, before a single tick is shown ──────────────────────
            //
            // works:true used to BE the ✔: the child read "✔ 2 mobs playing pickleball" about
            // companions that had already walked off the court, and "✔ he follows me" about a
            // Spikor that did not. Now a claim that says it works carries a typed `check` the
            // server runs (ClaimCheck / ClaimWorld / ClaimJudge); one with no check is told as
            // "I tried", and one the world contradicts sends the agent back ONCE and is then shown
            // as not working. This runs where it RUNS — on the real finish_building path.
            java.util.List<String> texts = new java.util.ArrayList<>();
            java.util.List<Boolean> claimed = new java.util.ArrayList<>();
            java.util.List<String> evidence = new java.util.ArrayList<>();
            java.util.List<com.moddingfromamod.wish.ClaimCheck> checks = new java.util.ArrayList<>();
            for (var el : args.getAsJsonArray("claims")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                boolean works = c.has("works") && c.get("works").isJsonPrimitive()
                        && c.get("works").getAsBoolean();
                com.moddingfromamod.wish.ClaimCheck check;
                try {
                    check = works ? com.moddingfromamod.wish.ClaimCheck.parse(c.get("check")) : null;
                } catch (IllegalArgumentException bad) {
                    return ToolResult.error("Claim \"" + str(c, "claim") + "\": " + bad.getMessage()
                            + ". Nothing was shown to the child. The check kinds are: "
                            + com.moddingfromamod.wish.ClaimCheck.kinds() + ".");
                }
                texts.add(str(c, "claim"));
                claimed.add(works);
                evidence.add(str(c, "evidence"));
                checks.add(check);
            }
            java.util.List<com.moddingfromamod.wish.ClaimCheck> toRun = new java.util.ArrayList<>();
            for (var ch : checks) {
                if (ch != null) {
                    toRun.add(ch);
                }
            }
            long lookStart = System.currentTimeMillis();
            java.util.List<com.moddingfromamod.wish.ClaimJudge.Observed> seen =
                    com.moddingfromamod.wish.ClaimWorld.observe(finishRun, toRun);
            long lookedMs = System.currentTimeMillis() - lookStart;
            if (!toRun.isEmpty()) {
                StringBuilder what = new StringBuilder();
                for (int i = 0; i < toRun.size(); i++) {
                    var o = seen.get(i);
                    what.append(" | ").append(toRun.get(i).describe()).append(" → ")
                        .append(o == null ? "UNASKED" : (o.holds() ? "HOLDS " : "FAILS ") + o.saw());
                }
                org.slf4j.LoggerFactory.getLogger(FinishBuildingTool.class).info(
                        "claim checks for wish {}: {} check(s) in {} ms{}", finishRun.shortId(),
                        toRun.size(), lookedMs, what);
            }
            java.util.List<com.moddingfromamod.wish.ClaimJudge.Outcome> outcomes = new java.util.ArrayList<>();
            java.util.List<com.moddingfromamod.wish.ClaimJudge.Observed> perClaim = new java.util.ArrayList<>();
            java.util.List<com.moddingfromamod.wish.ClaimJudge.Contradiction> wrong = new java.util.ArrayList<>();
            int k = 0;
            for (int i = 0; i < texts.size(); i++) {
                var obs = checks.get(i) == null ? null : seen.get(k++);
                perClaim.add(obs);
                var outcome = com.moddingfromamod.wish.ClaimJudge.judge(claimed.get(i), checks.get(i), obs);
                outcomes.add(outcome);
                if (outcome == com.moddingfromamod.wish.ClaimJudge.Outcome.CONTRADICTED) {
                    wrong.add(new com.moddingfromamod.wish.ClaimJudge.Contradiction(texts.get(i),
                            checks.get(i), obs));
                }
            }
            if (!wrong.isEmpty()) {
                boolean refuse;
                try {
                    refuse = AgentBridge.onServer(() -> com.moddingfromamod.wish.BuildGate
                            .forWish(finishRun.wishId()).refuseFalseClaimsOnce(), 5_000L);
                } catch (AgentBridge.Unavailable e) {
                    refuse = false;   // never let the guard be the reason a wish cannot end
                }
                if (refuse) {
                    return ToolResult.error(com.moddingfromamod.wish.ClaimJudge.refusal(wrong));
                }
            }
            for (int i = 0; i < texts.size(); i++) {
                var obs = perClaim.get(i);
                String ev = evidence.get(i) + (obs == null ? "" : " · the server saw: " + obs.saw());
                com.moddingfromamod.wish.WishClaims.Verdict v = switch (outcomes.get(i)) {
                    case WORKS -> com.moddingfromamod.wish.WishClaims.Verdict.WORKS;
                    case TRIED -> com.moddingfromamod.wish.WishClaims.Verdict.TRIED;
                    case BROKEN, CONTRADICTED -> com.moddingfromamod.wish.WishClaims.Verdict.BROKEN;
                };
                com.moddingfromamod.wish.WishClaims.answer(texts.get(i), v, ev);
                // THE GROWN-UP RECORD: the chat checklist was the only place a verdict
                // was written down, so each one now has its own row (`logs.sh trace` → `check`).
                com.moddingfromamod.agent.ActionLog.claimVerdict(finishRun.wishId() == null ? null : finishRun.wishId().toString(),
                        texts.get(i), v.name(), outcomes.get(i).name(),
                        checks.get(i) == null ? "" : checks.get(i).describe(),
                        evidence.get(i), obs == null ? "" : obs.saw());
            }
        }
        // Refused ONCE, like every other guard here: a wish that ends imperfectly beats a wish that
        // cannot end. What it buys is that the LAST thing before the child is told is somebody
        // asking "did the thing he actually asked for happen?" in those words -- which on the wish
        // that motivated this was never asked at all, five finishes in a row.
        String openClaims = com.moddingfromamod.wish.WishClaims.nagAboutUnanswered();
        if (openClaims != null) {
            boolean nudge = false;
            try {
                nudge = AgentBridge.onServer(() -> {
                    // THIS wish's gate — it read the first online player's, whoever's wish that was.
                    return com.moddingfromamod.wish.BuildGate.forWish(finishRun.wishId()).nudgeAboutClaims();
                }, 5_000L);
            } catch (AgentBridge.Unavailable e) {
                nudge = false;   // never let the guard be the reason a wish cannot end
            }
            if (nudge) {
                return ToolResult.error(openClaims);
            }
        }

        String say = str(args, "say");
        String subject = str(args, "subject");
        String label = str(args, "label");
        // THE FILE IS NAMED AFTER THE CREATURE, not after the agent's caption.
        //
        // The label is the only handle anything downstream has for finding a creature's photograph,
        // and an agent naming a shot for the SCENE loses it: a run photographed Dinko as
        // "dinko_vs_spikor", so there was no dinko photo at all and a human reviewing five
        // creatures could not see one of them. A caption is cosmetic; a filename is an index.
        if (!subject.isBlank()) {
            label = subject;
        } else if (label.isBlank()) {
            label = "finish_building";
        }

        // ── RECORD THAT THE AGENT COMPLIED, ON THE WISH'S OWNER, BEFORE ANYTHING ELSE ──────
        //
        // Everything below is keyed off the ONLINE player list, and `markBuilt` lives inside that
        // loop — so on a server with nobody online the agent calls finish_building, does exactly
        // what it was told, and BuildGate.refuseFinish still turns its finish_wish away with "you
        // haven't called finish_building for this wish". Untrue, and it costs a model round trip at
        // the most expensive moment in the wish.
        //
        // Measured in the first real traffic mined into corpus/real-tool-calls.jsonl: 7 of 7 Gate C½
        // wishes end finish_building(ok) -> finish_wish(REFUSED) -> finish_wish(ok). Mostly an
        // instrument fault -- C½ drives a FakePlayer and has no player list -- which is exactly why
        // it is recorded here: anyone reading a C½ transcript as evidence 's "2x the turns"
        // would be counting the harness.
        //
        // Keyed on the WISH'S OWNER (like FinishWishTool's own lookup) rather than on whoever is
        // online, because that is the gate finish_wish will read. Best-effort: a wish that cannot be
        // resolved falls through to exactly the old behaviour.
        // THE WORKER'S OWN RUN, never "the most recent wish": a blank worker id identifies nobody
        // (its javadoc says so), and picking somebody else's wish would mark the wrong child's gate
        // — the same mis-binding that made an earlier issue's first cut refuse a neighbouring wish's sentence.
        com.moddingfromamod.wish.BuildGate.forWish(finishRun.wishId()).markFinishBuildingCalled();

        // ── THE END FIRST, THEN THE PHOTOGRAPH, FOR GRADING ONLY (2026-10-21) ──────────────
        //
        // photographed first and played the reveal after, because the camera used to take
        // every pillar down for 4–13 s and the pillar popped back after "it's here!". FxVeil has
        // since kept the pillar off the camera's screen entirely, so that reason is gone, and the
        // cost of the old order was measured on a gold-dragon demo: the camera never
        // joined, the photograph waited 90 s, and the pillar and the "done" sound waited with it.
        // Now finish_building ENDS the wish for the child: the shimmer stops (WishEarlyReveal),
        // the reveal or the "all done" plays, and he is told. Only then is the photograph taken,
        // and it is for the agent's verdict, the description line and his account page — never
        // another turn of the crank (AfterFinish refuses building tools from here).
        // no checklist — only a broken bit is told, in one sentence (null when none is).
        final String brokenLine = com.moddingfromamod.wish.WishClaims.brokenSentence();
        final String trigger = com.moddingfromamod.wish.BuiltAbility.describe();
        final boolean celebratedBefore = finishRun.celebrated();
        // the agent's words wait for the photo; the reveal itself says only the short line.
        if (!say.isBlank()) {
            finishRun.holdPlannedSay(say);
        }
        // THE ONE CELEBRATION, claimed NOW: a quick finish_wish must find it taken and not
        // celebrate a second time.
        finishRun.celebrate();
        final boolean[] shownEarly = {false};
        try {
            AgentBridge.onServerRun(() -> {
                var server = AgentBridge.serverOrNull();
                com.moddingfromamod.wish.reveal.WishOrbs.holdForReveal(finishRun.wishId());
                // Stop the shimmer and reap any superseded copies BEFORE the end plays, so the
                // celebration lands on the finished creature in its own colours.
                shownEarly[0] = com.moddingfromamod.wish.reveal.WishEarlyReveal.end(server,
                        finishRun.wishId());
                ServerPlayer owner = server.getPlayerList().getPlayer(finishRun.playerId());
                if (owner != null) {
                    // The plan is finished — fill the bar rather than leaving it stuck mid-way.
                    WishProgress.completePlan(owner, finishRun.wishId());
                    com.moddingfromamod.wish.BuildGate.forWish(finishRun.wishId()).markBuilt();
                }
            });
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage()
                    + " (Skip the photo and finish_wish with your honest verdict anyway.)");
        }
        revealAndAnnounce(finishRun, say, celebratedBefore, brokenLine, trigger, shownEarly[0]);
        // The reveal plays for a couple of seconds; the photograph waits for it rather than
        // cutting it short (a child lending his own eyes to the shot would lose his reveal), and
        // so it shows the finished thing rather than the effects. Off the child's path: he has
        // already been told.
        // NEVER on the server thread: the reveal it waits for is played BY that thread's ticks, so
        // waiting there would stall the world for the whole deadline (measured in Gate B: a batch at
        // 6 ticks/s). PhotographTool.awaitCameraForFirstPhoto keeps the same rule.
        var self = AgentBridge.serverOrNull();
        if (self != null && !self.isSameThread()) {
            com.moddingfromamod.wish.reveal.Reveals.awaitAnnounced(finishRun.wishId(),
                    com.moddingfromamod.wish.reveal.Reveals.FINALE_DEADLINE_MS);
        }

        // a wish that made more than one thing (a castle and its seahorses) is photographed
        // part by part, unless the agent named the one it wants.
        java.util.List<String> parts = subject.isBlank()
                ? com.moddingfromamod.wish.PhotoParts.parts(finishRun) : java.util.List.of();
        // One brief for both paths: a compound wish may be polished in place exactly as a single one.
        String brief = "The wish is now OVER for the child: it has been shown to him and he has been told "
                + "it is done, so nothing more can be added, removed, spawned, given or rebuilt "
                + "for it. This photograph is for your verdict — judge it, then call "
                + "finish_wish with your honest verdict and one short sentence describing "
                + "what the photo shows. If it shows something SMALL to fix in this wish's "
                + "own thing, you may polish it IN PLACE first: modify_mob scope 'one' on its "
                + "creature (what it does — no scale, no rideable), make_item start_from its "
                + "item (no count, variants or at), or author_datapack on its own feature_id. "
                + "Anything bigger is a suggestion in finish_wish, for him to choose.";
        ToolResult photo = !parts.isEmpty() ? PhotographTool.captureParts(label, parts, brief)
                : PhotographTool.capture(label, subject, brief);
        if (photo.isError()) {
            // The finish itself succeeded; only the picture failed. Ending the wish honestly with
            // no photo beats retrying a camera, which is how eleven minutes disappeared last time.
            return ToolResult.ok("The wish is over for the child (shown and told), but the photo "
                    + "didn't come back (" + photo.text() + "). Don't retry the camera — call "
                    + "finish_wish now with your best honest verdict based on what you built.");
        }
        return photo;
    }

    /**
     * The END, before the photograph (2026-10-21): give him his eyes back if they were
     * borrowed, play the reveal, and when it has played, release a creature to his client and tell him. Never throws.
     */
    private static void revealAndAnnounce(com.moddingfromamod.marshal.WishRun finishRun, String say,
                                          boolean celebratedBefore, String brokenLine,
                                          String trigger, boolean shownEarly) {
        try {
            AgentBridge.onServerRun(() -> {
                var server = AgentBridge.serverOrNull();
                // The finish shot is the LAST shot (nothing is built after finish_building), so his
                // eyes are given back now rather than after the idle wait: the reveal is for him.
                ServerPlayer wisher = server.getPlayerList().getPlayer(finishRun.playerId());
                if (wisher != null && com.moddingfromamod.wish.WishCapture.isCapturing(wisher.getUUID())) {
                    com.moddingfromamod.wish.WishCapture.exit(wisher);
                }
                com.moddingfromamod.wish.WishAimer.logFinal(finishRun.shortId(), finishRun.anchor());
                com.moddingfromamod.wish.reveal.Reveals.finale(finishRun, () -> {
                    // ── THE REVEAL — now PART of the reveal moment ─────────────
                    // While a camera is serving, every interim sculpt is veiled from the child's
                    // stock client (RigVeil); here the veil lifts, as the summoning circle flashes.
                    // Idempotent and free on every other topology.
                    com.moddingfromamod.vanilla.RigReveal.reveal(server);
                    // ── THE WISHER, NOT THE ROOM (D1/D2 of docs/MULTIPLAYER_DECISIONS.md) ──
                    java.util.List<java.util.UUID> online = server.getPlayerList().getPlayers().stream()
                            .map(ServerPlayer::getUUID).toList();
                    for (java.util.UUID id : com.moddingfromamod.marshal.WishAudience.forNarration(
                            finishRun.playerId(), online)) {
                        ServerPlayer p = server.getPlayerList().getPlayer(id);
                        if (p == null) {
                            continue;
                        }
                        // "It's here — have a look!": short, so the moment is not
                        // delayed; WHAT it is comes from finish_wish, after the photo is judged.
                        // A creature he has been watching shimmer is FINISHED, not arriving.
                        WishFeedback.built(p, shownEarly ? WishFeedback.FINISHED_SHORT
                                : WishFeedback.REVEAL_SHORT, celebratedBefore);
                        // ...and, small and grey, what the photograph after it is for. His bar and
                        // its clock come down now: the grading only recommends, and it is told in
                        // chat, so nothing on screen waits for it (2026-10-22).
                        if (p.getUUID().equals(finishRun.playerId())) {
                            WishFeedback.closerLook(p);
                            WishProgress.endForChild(p, finishRun.wishId());
                        }
                        // ...and, only if a check found something broken, ONE sentence saying so
                        //. No checklist: in live play it was all "I tried" and read as noise.
                        WishFeedback.brokenClaims(p, brokenLine);
                        // DERIVED FROM THE ARTIFACT, not from `say`.
                        WishFeedback.howToTrigger(p, trigger);
                        // "✨ Your ice dragon is ready! [take me there]" — only when it landed
                        // somewhere he ISN'T.
                        com.moddingfromamod.wish.WishGo.offerToTakeHim(p, finishRun);
                    }
                });
            });
        } catch (AgentBridge.Unavailable e) {
            org.slf4j.LoggerFactory.getLogger(FinishBuildingTool.class).warn(
                    "finish_building: could not reach the server to reveal wish {}: {}",
                    finishRun.shortId(), e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** The buildings this wish made (and the named subject, if it is one), by id. */
    static java.util.Map<String, com.moddingfromamod.builder.BuildPlan> buildsOf(
            com.moddingfromamod.marshal.WishRun run, String subject) {
        java.util.Map<String, com.moddingfromamod.builder.BuildPlan> out = new java.util.LinkedHashMap<>();
        java.util.List<String> ids = new java.util.ArrayList<>(run.freshBuilds());
        if (subject != null && !subject.isBlank()) {
            ids.add(subject.trim());
        }
        for (String id : ids) {
            var plan = com.moddingfromamod.builder.BuildRegistry.get(id);
            if (plan != null) {
                out.putIfAbsent(id, plan);
            }
        }
        return out;
    }
}
