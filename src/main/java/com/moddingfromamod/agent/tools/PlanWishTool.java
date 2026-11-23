package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.wish.WishPlan;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.server.level.ServerPlayer;

/**
 * Decide the WHOLE wish before building any of it, and show the child a progress bar for it.
 *
 * <p><b>Why this comes first.</b> Working each piece out as you go makes the world narrate your
 * construction order: in one build the Create gears appeared several seconds before the housing grew
 * around them, so the child watched the workshop instead of the result. Planning across every kind
 * of thing the wish needs — creature, datapack, contraption, structure — is what turns a build into
 * one burst instead of a serial.
 *
 * <p>And it gives the child something to read. "Making something amazing…" for eleven minutes is
 * indistinguishable from a hang; "Giving Spikor his fire (2 of 3)" is a promise with an end in
 * sight.
 */
public final class PlanWishTool implements McpTool {

    @Override
    public String name() {
        return "plan_wish";
    }

    @Override
    public String description() {
        return "FIRST THING you do for any wish that needs building. List the steps you are about "
                + "to do, in the order you will do them, across EVERYTHING the wish needs (the "
                + "creature AND its powers AND anything it stands on). Each step is a few kid-words, "
                + "e.g. ['Building the frog knight', 'Giving him fire breath']. The child sees a progress bar "
                + "with these. Then build them all back to back, and call finish_building when the LAST one "
                + "is done. Max " + WishPlan.MAX_STEPS + " steps — if you need more, the wish is too "
                + "big and you should build the best chunky first version instead. ALWAYS say "
                + "what the wish is ABOUT in `about`: the id of ONE of his things it changes, or "
                + "'new' when it makes something new. YOU decide that, from his WORDS — nothing "
                + "has decided it for you. A wish that names a thing none of his things is (a "
                + "shield, a lamp, a hat, another one) is 'new', even if one of his creatures is in "
                + "his view. A wish that ACTS ON one of his things (remove, hit, ride, feed, take "
                + "me to, make it bigger) is about one the catalogue marks OUT IN THE WORLD, never "
                + "a 'design only' one. If a wrong pick would DESTROY or REMOVE something and his "
                + "words fit more than one of his things that are out, call ask_which first. CALL "
                + "plan_wish IN THE SAME "
                + "TURN AS YOUR FIRST LOOKUP: send it beside find_content / conjure_at / a list_* "
                + "or inspect_* call as parallel tool calls, never a lookup turn and then the plan "
                + "on the next — that turn is ~12 s he waits for a plan. If what you find changes "
                + "it, call plan_wish again.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // A STATUS THAT RIDES ALONG WITH THE WORK. Calling set_status separately costs a
        // full model round trip -- ~4s, and 12% of one measured session -- for a tool that
        // executes in under 8ms. Passed here it is shown the moment the call arrives, in the
        // agent's own words, for nothing.
        Schemas.prop(s, "status", "string",
                "A SHORT kid-facing line saying what you are doing, shown the instant this "
                        + "call arrives, e.g. 'Working out how to make this'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "steps", "array",
                "The steps, in build order. A few plain words each, readable by a 7-year-old.");
        // WHAT HE ASKED FOR, as opposed to what you are going to do about it. Deliberately a
        // separate field from `steps`: steps are your activities and claims are his requirements,
        // and an agent that verifies its own steps verifies that it did what it decided to do.
        Schemas.prop(s, "claims", "array",
                "What the CHILD asked for, in HIS words, as separate things — at most "
                        + com.moddingfromamod.wish.WishClaims.MAX + ". Not your build steps: the "
                        + "things he would test. \"make the machine CRUSH up logs into BUTT CHEEK "
                        + "WATER, then bottle it\" is three: ['it grinds up trees', 'it turns them "
                        + "into butt cheek water', 'it puts it in bottles']. You will "
                        + "have to give a verdict on EVERY one of these before finish_building "
                        + "accepts, so keep them to things you can actually go and look at.");
        // (owner decision iii): the spot was decided when he wished, from where he looked;
        // what KIND of thing it is is first known here, and it may slide the spot once along the
        // same line — a dragon aimed at a hill 60 blocks off comes in to 16, a castle aimed at his
        // feet goes out to 12. Never sideways, and never into the air.
        Schemas.prop(s, "kind", "string",
                "What you are making: building, creature, picture, item, event or other. The spot "
                        + "he looked at may slide nearer or farther along the same line to suit it "
                        + "(a creature 4–16 blocks away, a picture 5–16, a building at least 12). "
                        // item 3: "a <made-up name> that makes me float when i touch it" was
                        // built as a magic block on every Opus run, because what it DOES sounded
                        // like an item effect. The noun says what it is; the effect does not.
                        + "THE NOUN DECIDES THE KIND, NOT WHAT IT DOES: an effect on HIM, set off "
                        + "by touching it, fits a creature as well as an item. A name he MADE UP is a "
                        + "creature unless it names an object (a stone, a block, boots, a wand, a "
                        + "potion): children name their pets and monsters, and a creature can do "
                        + "something to him when he touches, pets or stands by it.");
        // a wish ABOUT an earlier one ("he stopped following me", "I can't see it", "I
        // didn't get a map") is help, not a new thing: it is never told "I can't save it" and
        // never asked to become a button.
        Schemas.prop(s, "followup", "boolean",
                "OPTIONAL: true when this wish is ABOUT something already made — fixing it, a "
                        + "complaint, or a question about an earlier wish (\"he doesn't follow me\", "
                        + "\"I can't see the creature\") — rather than a new thing to make.");
        // WHAT THE WISH IS ABOUT, decided by the agent and nobody else. A local resolver
        // used to decide it at submit (4 picks live, 4 wrong) and tell the agent not to argue.
        Schemas.prop(s, "about", "string",
                "What this wish is ABOUT: the id of ONE of his things it changes (from the "
                        + "catalogue in the wish note, e.g. 'pegasus'), or 'new' when it makes "
                        + "something new. His words decide, not what is in his view: \"a shield "
                        + "that…\", \"a lamp\", \"another hat\" are 'new'; \"make him faster\" "
                        + "or \"put a hat on my horse\" are about one of his things. The plan "
                        + "line and his progress bar name it.");
        return Schemas.required(s, "steps", "about");
    }

    /**
     * The refusal for an {@code about} that is not one of his things. Public so the routing
     * harness's stub answers exactly as this tool does ({@code RoutingReferentWorldDumpTest}).
     */
    public static String notHisThing(String about) {
        return "'" + about + "' is not one of his things. Use an id from "
                + "the catalogue in the wish note, or about: 'new' if this wish makes "
                + "something new.";
    }

    /**
     * a plan that says {@code about: 'new'} for words that point back at something — "give it
     * wings too", "make the cloud bounce me higher". Live, that is how a follow-up became a brand-new
     * thing called an upgrade, or a change to the CHILD. Said on the reply, never a refusal: "another
     * bar" really is new. Pure, so the routing harness shows the agent these exact words.
     */
    public static String noAntecedentNote(String childWords, String about) {
        if (about == null || !com.moddingfromamod.marshal.WishRun.ABOUT_NEW.equalsIgnoreCase(about.strip())
                || !com.moddingfromamod.wish.WishReference.pointsBack(childWords)) {
            return "";
        }
        return "\n\nCHECK `about`: his words read like they point at something he ALREADY has "
                + "(\"it\", \"him\", \"the …\"). If one of his things fits — the catalogue in the "
                + "wish note, list_datapacks, set_rule list — plan again with its id in `about` and "
                + "change THAT. If his words really ask for something new (\"another\", \"a new\"), "
                + "carry on. If nothing of his fits, do not make a new one and call it his, and do "
                + "not change HIM instead: tell him kindly you couldn't find it and ask whether he "
                + "wants a new one (notify_player, then finish_wish without making anything).";
    }

    /** Keys shown per kind before the rest are summarised; a key is a word or two, so 30 is one line. */
    static final int PARTS_SHOWN = 30;

    /**
     * One line per kind of ready-made library part and the argument that starts from it, or
     * "" when the library carries none. Pure, so {@code PlanWishLibraryPartsTest} pins the wording.
     */
    static String libraryParts(java.util.Collection<String> bodies, java.util.Collection<String> pictures,
                               java.util.Collection<String> buildings) {
        StringBuilder sb = new StringBuilder();
        partsLine(sb, "bodies", bodies, "sculpt_creature start_from '<key>'", "list_creatures");
        partsLine(sb, "pictures", pictures, "paint_surface start_from '<key>'", "list_paintings");
        partsLine(sb, "buildings", buildings, "build_structure start_from '<key>'", "list_builds");
        if (sb.length() == 0) {
            return "";
        }
        return "\n\nREADY-MADE PARTS in the library. If this wish is one of these — even with "
                + "something changed (a colour, wings, a hat, a bigger one) — START FROM it and send "
                + "only what is different, instead of making it from nothing:" + sb + "\n";
    }

    /**
     * the ready-made body this wish NAMES, with its boxes, so a hat or a new head goes where the
     * base's really is. "a pet witch cat" names {@code cat}; the agent then starts from it knowing the
     * head's top is y 16, not guessing y 30. Only a body the child's words name, whole word, so the
     * reply does not grow by eight creatures' worth of numbers on every wish.
     */
    static String bodyShape(String childWords, java.util.Collection<String> bodies) {
        if (childWords == null || childWords.isBlank() || bodies == null) {
            return "";
        }
        String words = " " + childWords.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        StringBuilder sb = new StringBuilder();
        for (String key : bodies.stream().sorted().toList()) {
            String spoken = " " + key.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " ";
            if (!words.contains(spoken)) {
                continue;
            }
            String shape = com.moddingfromamod.runtime.SculptBase.outline(key,
                    com.moddingfromamod.runtime.ModelRepository.sculptedParts(key));
            if (!shape.isEmpty()) {
                sb.append("\n  ").append(shape);
            }
        }
        return sb.length() == 0 ? "" : "\n\nTHE BODY HIS WORDS NAME, if you start_from it. Put what you add ON "
                + "these boxes, overlapping by a pixel, and re-send a part only at about its own size:" + sb + "\n";
    }

    private static String childWords() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? "" : run.text();
    }

    private static void partsLine(StringBuilder sb, String label, java.util.Collection<String> keys,
                                  String how, String listing) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> sorted = keys.stream().sorted().toList();
        String shown = String.join(", ", sorted.subList(0, Math.min(PARTS_SHOWN, sorted.size())));
        String more = sorted.size() > PARTS_SHOWN
                ? " and " + (sorted.size() - PARTS_SHOWN) + " more (" + listing + ")" : "";
        sb.append("\n  ").append(label).append(": ").append(shown).append(more).append(" → ").append(how);
    }

    @Override
    public ToolResult call(JsonObject args) {
        List<String> steps = new ArrayList<>();
        if (args != null && args.has("steps") && args.get("steps").isJsonArray()) {
            args.getAsJsonArray("steps").forEach(e -> {
                if (e != null && e.isJsonPrimitive()) {
                    steps.add(e.getAsString());
                }
            });
        }
        WishPlan plan = WishPlan.of(steps);
        if (plan == null) {
            return ToolResult.error("Give me at least one step, e.g. steps: ['Building the frog knight'].");
        }
        String said = args.has("about") && args.get("about").isJsonPrimitive()
                ? args.get("about").getAsString().strip() : "";
        // The owned list writes an item as `id:black_hole_block_pull` and a changed mob as `@bee`;
        // the agent naturally passes the bare `black_hole_block_pull` or `bee`. Refusing that cost a
        // turn and then a wrong `about:new` in 5 of 10 chained follow-ups (2026-10-10). The match
        // ignores the prefix; the run records the catalogue's own id.
        final String about;
        if (!said.isEmpty() && !com.moddingfromamod.marshal.WishRun.ABOUT_NEW.equalsIgnoreCase(said)) {
            String canon;
            try {
                canon = AgentBridge.onServer(() -> canonicalId(said));
            } catch (AgentBridge.Unavailable e) {
                return ToolResult.error(e.getMessage());
            }
            about = canon == null ? said : canon;
        } else {
            about = said;
        }
        // ABOUT ONE OF HIS THINGS: checked against the same catalogue the wish note listed, so a
        // made-up id is refused here rather than naming a thing he does not have in his chat.
        String aboutName = "";
        if (!about.isEmpty() && !com.moddingfromamod.marshal.WishRun.ABOUT_NEW.equalsIgnoreCase(about)) {
            try {
                aboutName = AgentBridge.onServer(() -> nameOf(about));
            } catch (AgentBridge.Unavailable e) {
                return ToolResult.error(e.getMessage());
            }
            if (aboutName == null) {
                return ToolResult.error(notHisThing(about));
            }
        }
        final String aboutSaid = aboutName;
        // WHAT HE ASKED FOR, recorded now so it can be answered later. Stated at PLAN time on
        // purpose: asked at the end, "what did he want?" is answered by looking at what got built.
        List<String> asked = new ArrayList<>();
        if (args.has("claims") && args.get("claims").isJsonArray()) {
            args.getAsJsonArray("claims").forEach(e -> {
                if (e != null && e.isJsonPrimitive()) {
                    asked.add(e.getAsString());
                }
            });
        }
        int kept = com.moddingfromamod.wish.WishClaims.state(asked);
        if (args.has("followup") && args.get("followup").isJsonPrimitive()
                && args.get("followup").getAsBoolean()) {
            var followed = com.moddingfromamod.agent.AgentContext.currentRun();
            if (followed != null) {
                followed.markFollowup();
            }
        }
        String claimNote = asked.isEmpty()
                ? " (You listed no claims. If this wish MAKES something for him, list what he "
                        + "asked for in `claims` — that is what you will be asked to prove.)"
                : " Claims recorded: " + kept + ". You will need a verdict on each before "
                        + "finish_building accepts."
                        + (kept < asked.size()
                                ? " NOTE: " + (asked.size() - kept) + " were dropped — the limit is "
                                        + com.moddingfromamod.wish.WishClaims.MAX
                                        + ", and a wish with more in it should be built as its best "
                                        + "chunky first version instead."
                                : "");
        try {
            return ToolResult.ok(AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
                if (player == null) {
                    return "Nobody is in the world, so there's no bar to show — build anyway.";
                }
                var run = com.moddingfromamod.agent.AgentContext.currentRun();
                if (run == null) {
                    return "I can't tell which wish this plan is for, so there's no bar to show — "
                            + "build anyway.";
                }
                // D2: the plan read out loud is addressed to the child who asked, not to whoever
                // joined first. `player` above is a findFirst() (D9, filed) and is kept only as the
                // fallback for a wish whose owner has logged out mid-plan — the bar itself is now
                // attached by WishProgress from the wish's own owner, so this decides the CHAT only.
                ServerPlayer owner = server.getPlayerList().getPlayer(run.playerId());
                ServerPlayer tell = owner == null ? player : owner;
                String kind = args.has("kind") && args.get("kind").isJsonPrimitive()
                        ? args.get("kind").getAsString() : null;
                run.planKind(kind);
                if (kind != null) {
                    // The first moment the wish says what it is: an item or a question lets its
                    // camera go; a creature or a building prices it (CameraNeed). No kind keeps it.
                    com.moddingfromamod.wish.CameraNeed.shared().declared(run.wishId(), kind,
                            System.currentTimeMillis());
                }
                // what the AGENT says this wish is about names the bar — the first time
                // anything of his is named for this wish.
                // everything below that the CHILD sees — and everything the plan changes on
                // the run (its "about", the spot it slides the wish to) — is shown only once the plan safety
                // grade has passed this plan (PlanDeferral) — at once when it already has, or when
                // the grade runs beside the agent's next turn; never, when it refuses the plan.
                final boolean firstPlan = !run.planAnnounced();
                final ServerPlayer shownTo = tell;
                final String kindNow = kind;
                Runnable showPlan = () -> {
                    // A plan that is never passed moves nothing: not the sparkles (slideByKind), not
                    // the name the run goes by (an earlier issue's about/subject).
                    slideByKind(shownTo, run, kindNow);
                    if (!about.isEmpty()) {
                        run.about(about);
                        run.subject(aboutSaid);
                        WishProgress.nameBar(run.wishId(), run.displayName());
                    }
                    WishProgress.setPlan(shownTo, run.wishId(), plan);
                // Say the whole plan out loud, once. The bar only ever shows the CURRENT step, so
                // without this the child cannot tell whether he is one step from done or five —
                // "what should I expect" was the single most common thing missing from the wait.
                //
                // ONCE PER WISH, and "once" is now enforced rather than hoped for. Live, a
                // "make it daytime forever" read out two plans in two wordings when the agent
                // re-planned after a refused command; a child reading two plans cannot tell which
                // one is being built. A re-plan still moves the bar above — that is where the
                // current step lives — and only the chat line is not repeated.
                    if (run.announcePlanOnce()) {
                        com.moddingfromamod.wish.WishFeedback.planned(shownTo, plan.steps(), aboutSaid);
                    }
                    // "See the sparkles?" — said here when conjure_at marked the spot before
                    // the kind was known; once per wish either way (WishRun.saySparklesOnce).
                    if (run.saySparklesOnce()) {
                        com.moddingfromamod.wish.WishFeedback.sparklesHere(shownTo);
                    }
                };
                Runnable showNow = com.moddingfromamod.marshal.PlanDeferral.global().show(run.wishId(), showPlan);
                if (showNow != null) {
                    showNow.run();
                }
                // ── THE LIBRARY'S READY-MADE PARTS, SAID HERE FOR THE SAME REASON ────────────
                // Measured with tools/routing-arm.py (world=library): a creature wish opened
                // list_creatures and started from the library's wolf, but a picture wish and a
                // building wish went plan_wish → paint_surface / build_structure and never opened
                // list_paintings or list_builds — so a shipped heart or mushroom village was on
                // disk, counted, and never once found. This reply is read by every wish that builds.
                String parts = libraryParts(
                        com.moddingfromamod.library.WishLibrary.keys("species"),
                        com.moddingfromamod.library.WishLibrary.keys("pictures"),
                        com.moddingfromamod.library.WishLibrary.keys("buildings"))
                        + bodyShape(childWords(), com.moddingfromamod.library.WishLibrary.keys("species"));

                return "Plan set, " + plan.size() + " step(s), and the child can see the bar."
                        + (firstPlan ? "" : " (He has already been told your plan, so this one "
                                + "only moved his bar — nothing new was said in his chat.)")
                        + parts
                        + (about.isEmpty() ? " (You did not say what this wish is ABOUT. Next "
                                + "time pass `about`: one of his things' ids, or 'new'.)" : "")
                        + " Now "
                        + "BUILD THEM ALL back to back — call set_status with the step number as you "
                        + "start each one, and do NOT stop to check anything until they are all "
                        + "done. Then call finish_building." + claimNote
                        + planLessons(firstPlan, kind)
                        + noAntecedentNote(run.text(), about);
            }));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * The planned kind's lessons, on the FIRST plan only. An item, event or picture wish
     * goes plan_wish → its authoring tool, and those tools carry their lessons only on a REFUSAL —
     * measured on 787 Muse runs, an item wish opened list_items first in 12 of 108 and was refused
     * in 7, so lessons/item.md (the "a machine he FILLS is load_and_pour" rule among them) reached a
     * normal grant almost never. Buildings are left out: build_structure hands theirs over on the
     * way. CREATURES ARE NOT (routing arm): this said list_creatures and sculpt_creature
     * hand theirs over, and a creature granted as a REAL mob — summoned, then modify_mob and
     * apply_effect, about a quarter of Opus's creature wishes — calls neither, so it read no creature
     * lesson at all ("heals itself" was built 3 of 4 times as a heal for the monsters it fights). A
     * creature plan now carries them, once per wish, and the two tools skip them after.
     * Package-private for the test.
     */
    static String planLessons(boolean first, String kind) {
        if (!first || kind == null) {
            return "";
        }
        if ("creature".equals(kind.trim().toLowerCase(java.util.Locale.ROOT))) {
            return com.moddingfromamod.wish.WishLessons.appendOnce("creature");
        }
        String file = switch (kind.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "item" -> "item";
            case "event" -> "event";
            case "picture" -> "painting";
            default -> null;
        };
        return file == null ? "" : com.moddingfromamod.wish.WishLessons.appendTo(file);
    }

    /**
     * How one of his things is said in the plan line ({@code "your Pegasus"}), or null when the id
     * is not in the catalogue the wish note listed ({@code ArtifactIndex.everything()}). Server
     * thread only.
     */
    /** The catalogue's own spelling of {@code id}, matched without its `id:` or `@` prefix; null if none. */
    public static String canonicalId(String id) {
        for (var e : com.moddingfromamod.wish.ArtifactIndex.everything()) {
            if (com.moddingfromamod.wish.AboutIds.same(e.id(), id)) {
                return e.id();
            }
        }
        return null;
    }

    static String nameOf(String id) {
        for (var e : com.moddingfromamod.wish.ArtifactIndex.everything()) {
            if (com.moddingfromamod.wish.AboutIds.same(e.id(), id)) {
                String name = e.name() == null || e.name().isBlank()
                        ? com.moddingfromamod.wish.ChildSubject.words(e.id()) : e.name();
                return "your " + name;
            }
        }
        return null;
    }

    /**
     * The one slide by kind. Only on the first plan that names a kind, never after the agent
     * lifted the wish with {@code up}, and only along the wish-time gaze line ({@link
     * com.moddingfromamod.wish.WishAim#slide}). The pillar GLIDES to the new spot — it is
     * never torn down and rebuilt.
     */
    static void slideByKind(ServerPlayer player, com.moddingfromamod.marshal.WishRun run, String kind) {
        if (player == null || run == null || kind == null || kind.isBlank() || run.raised()) {
            return;
        }
        var spot = run.anchor();
        var from = run.aimedFrom();
        if (spot == null || from == null || !run.slideOnce()) {
            return;
        }
        var level = com.moddingfromamod.wish.reveal.WishOrbs.levelOf(player.getServer(), spot.dimension());
        net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(spot.x(), spot.y(), spot.z());
        var to = com.moddingfromamod.wish.WishAimer.slide(level, from, at,
                com.moddingfromamod.wish.WishAim.Kind.parse(kind));
        if (to == null) {
            return;
        }
        net.minecraft.core.BlockPos dest = new net.minecraft.core.BlockPos(to.x(), to.y(), to.z());
        run.lockAnchor(new com.moddingfromamod.marshal.Spot(spot.dimension(), to.x(), to.y(), to.z()));
        com.moddingfromamod.wish.WishProgress.focusOn(player, dest);
        com.moddingfromamod.wish.WishAimer.logMove(run.shortId(), at, dest, to.how());
    }
}
