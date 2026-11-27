package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.CbScriptPrimer;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.datapack.DatapackStore;

/**
 * The Tier-1 power tool: write a brand-new feature as a CBScript datapack, compile it, and hot-load
 * it into the open world with no restart. This is what lets the agent grant a wish that no existing
 * tool (summon, modify_mob, build_structure, define_creature) can express — a custom item, a thrown
 * projectile with special effects, a per-tick rule, a moving 3D display model.
 *
 * <p><b>The loop is the point.</b> CBScript is niche, so a first draft may not compile. A compile
 * error is returned as plain text; the agent reads it, fixes the source, and calls again. Keep
 * iterating until it compiles and loads — that is expected, not failure.
 */
public final class AuthorDatapackTool implements McpTool {

    /** Said once, when a second build lands after something is already visible. See BuildGate. */
    static final String STOP_BUILDING =
            "\n\n⚠ STOP BUILDING. Something you made is ALREADY IN THE CHILD'S WORLD and he can see "
            + "it. Do not build or fix anything else — check it works, then call finish_building and "
            + "judge it. If it isn't right, that is exactly what 'could_be_better' is for: say so, "
            + "and offer the fix as his next wish so HE decides.";

    /**
     * Which (wish, feature) pairs have already been refused once.
     *
     * <p>Per WISH as well as per feature, so a refusal on one rung cannot silently spend the single
     * refusal a later rung is entitled to — each wish gets its own redirect. Cleared with the rest
     * of a run's state.
     */
    private static final java.util.Set<String> REFUSED_ONCE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String refusalKey(String featureId) {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return (run == null ? "-" : run.wishId().toString()) + "/" + featureId;
    }

    /** Forget the refusals for a finished run, so the next wish starts with its own. */
    public static void clearRefusals() {
        REFUSED_ONCE.clear();
    }

    /** The script this feature is running now, or null if it is new. */
    private static String readCurrentSource(String featureId) {
        try {
            java.nio.file.Path p = DatapackStore.sourceFor(featureId);
            return p != null && java.nio.file.Files.isRegularFile(p)
                    ? java.nio.file.Files.readString(p) : null;
        } catch (java.io.IOException e) {
            return null;   // unreadable is not the same as empty; refuse nothing on a guess
        }
    }

    /**
     * Is this attribute id real on this build?
     *
     * <p>Defensive because the text is a whole CBScript program rather than one command, so the
     * token after {@code attribute} can be anything at all — and {@code ResourceLocation.parse}
     * throws on a malformed one. An unparseable id is simply left alone: it was going to fail
     * either way, and guessing at a rewrite would be worse than leaving the author's own text.
     */
    private static boolean attributeExists(String id) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.containsKey(
                    net.minecraft.resources.ResourceLocation.parse(id));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String childWordsFor() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? "" : run.text();
    }

    @Override
    public String name() {
        return "author_datapack";
    }

    @Override
    public String description() {
        return "Create a NEW feature by writing it in CBScript; it is compiled to a Minecraft "
                + "datapack and loaded LIVE (no restart) into the child's world, and saved so it "
                + "persists. Use this ONLY when no simpler tool fits (a custom item/projectile, a "
                + "per-tick game rule, a moving/deformable 3D model). Args: feature_id (lowercase "
                + "letters/digits/underscores, e.g. 'ice_bomb'), description (one line), and cbscript "
                + "(the source BODY). If it doesn't compile you get the error back — FIX IT AND CALL "
                + "AGAIN; iterating is normal. After it loads, tell the child how to use it with "
                + "notify_player.\n\n" + CbScriptPrimer.SHORT;
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
                        + "call arrives, e.g. 'Writing the fire-breath spell'. Pass it EVERY time: it is "
                        + "free here, whereas a separate set_status costs the child a wait.");
        Schemas.prop(s, "feature_id", "string",
                "Short id: lowercase letters, digits, underscores, starting with a letter (e.g. 'ice_bomb'). "
                + "Becomes the datapack namespace, so functions are <feature_id>:name.");
        Schemas.prop(s, "description", "string", "One-line description of the feature.");
        Schemas.prop(s, "cbscript", "string",
                "The CBScript source BODY (no dir/desc header). See the description + cbscript_help.");
        Schemas.prop(s, "reuse_action", "string",
                "OPTIONAL. Set this ONLY if the child would want to RE-TRIGGER something on demand "
                + "('another fireball', 'spawn it again') — the name of a parameter-free entry "
                + "function to run, e.g. 'give' or 'spawn' (bare name, no slash, no namespace). Then "
                + "the wish becomes an instant button (/wishdatapack <id> <action>). OMIT it for a "
                + "RULE that's just always-on once loaded (a world rule, a passive effect, an "
                + "auto-timer) — a button there would do nothing, and the datapack auto-loads anyway.");
        Schemas.prop(s, "reuse_words", "array",
                "Required WITH reuse_action: plain keywords for the button, e.g. ['fireball']. Use "
                + "nouns the child says, NOT back-references like 'another'/'again'/'more'.");
        Schemas.prop(s, "reuse_say", "string",
                "Optional friendly line shown when the button is pressed, e.g. 'Here are your fireballs!'.");
        Schemas.prop(s, "note", "string",
                com.moddingfromamod.provenance.ProvenanceContext.NOTE_HINT);
        return Schemas.required(s, "feature_id", "cbscript", "note");
    }

    /** the refusal for this compiled pack on a world of bases, or null (no bases, or it stays his). */
    static String scopeRefusal(String id) {
        var rules = com.moddingfromamod.world.WorldRulesService.loadedOrNull();
        var bases = com.moddingfromamod.world.WorldModuleHost.liveBases();
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (bases == null || run == null || rules == null || !rules.baseReach()) {
            return null;
        }
        var base = bases.baseOf(run.playerId());
        if (base == null) {
            return "REFUSED — NOT HIS ISLAND: he has no island yet, so a datapack cannot be scoped to it.";
        }
        java.util.List<com.moddingfromamod.world.DatapackScope.Line> lines = new java.util.ArrayList<>();
        try (var z = new java.util.zip.ZipFile(com.moddingfromamod.datapack.DatapackStore.zipFor(id).toFile())) {
            for (var e : java.util.Collections.list(z.entries())) {
                if (e.isDirectory() || !e.getName().endsWith(".mcfunction")) {
                    continue;
                }
                String fn = e.getName().replaceFirst("^data/([^/]+)/functions?/", "$1:").replaceFirst("\\.mcfunction$", "");
                String[] text = new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .split("\n");
                for (int i = 0; i < text.length; i++) {
                    lines.add(new com.moddingfromamod.world.DatapackScope.Line(fn, i + 1, text[i]));
                }
            }
        } catch (java.io.IOException e) {
            return "REFUSED — NOT HIS ISLAND: the compiled pack could not be read, so it cannot be judged (" + e + ").";
        }
        return com.moddingfromamod.world.DatapackScope.refusal(lines, id, base,
                com.moddingfromamod.world.BaseReach.owned(run.playerId()), rules,
                h -> com.moddingfromamod.wish.CommandGuard.rule(h) != null);
    }

    static void registerLifetime(String id) {
        var rules = com.moddingfromamod.world.WorldRulesService.loadedOrNull();
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        if (rules != null && run != null && rules.baseReach() && com.moddingfromamod.world.WorldModuleHost.liveBases() != null) {
            com.moddingfromamod.world.StandalonePacks.register("datapack", id, run.playerId(),
                    com.moddingfromamod.world.StandalonePacks.of(rules));
        }
    }

    @Override
    public ToolResult call(JsonObject args) {
        String id = str(args, "feature_id");
        String desc = str(args, "description");
        String source = str(args, "cbscript");
        if (id.isBlank() || source.isBlank()) {
            return ToolResult.error("Need both feature_id and cbscript.");
        }
        // Before anything builds a path from it (readCurrentSource, the store): a ':' in the id threw on Windows.
        if (!com.moddingfromamod.datapack.CbScriptCompiler.validId(id)) {
            return ToolResult.error(com.moddingfromamod.datapack.CbScriptCompiler.invalidIdMessage(id));
        }
        // ── A MACHINE HE FILLS, HAND-WRITTEN (smoke, 2026-09-28) ─────────────────────────
        // A machine that makes something from what he puts in was answered by hand-writing a
        // datapack (a cauldron that eats dropped eggs) — it worked, but it is saved nowhere, it
        // cannot become a button, undo cannot find it, and it explains nothing. make_item's
        // `load_and_pour` is that machine, ready-made; and a change to a machine already made is
        // make_item with start_from. Asked ONCE per feature id, only inside a wish that names a
        // machine: sending the same call again writes the datapack.
        String wish = boundWishText();
        if (wish != null && MakeItemTool.machineWord(wish) != null
                && ASKED_FILL.add(id.toLowerCase(java.util.Locale.ROOT))) {
            return ToolResult.error("He asked for a " + MakeItemTool.machineWord(wish) + ". If it is "
                    + "one he puts things IN and gets something OUT of — a food, a drink, a snack, "
                    + "bigger with more of an ingredient, or into a chest — that is make_item with "
                    + "shape `machine` and effect `load_and_pour`, not a datapack: it is saved, it "
                    + "becomes a button, undo takes it away, and it tells him how to use it. If he "
                    + "asked to CHANGE a machine you already made, that is make_item with start_from "
                    + "its id. If this really needs its own datapack, send the same call again.");
        }
        // ── REFUSE ONCE IF THIS RE-AUTHOR DROPS BEHAVIOUR ────────────────────────────────────
        //
        // The BuildGate shape, and earned rather than reached for. Two information fixes were tried
        // first and neither moved this: a read-back tool the agent CALLED and dropped a clause
        // anyway, then the rules listed unasked in the handover with their sizes — identical
        // continuity median. It knows what is there; it re-derives the whole program from the wish
        // in front of it and writes the minimum that satisfies it.
        //
        // ONCE, then give way, for exactly the reason BuildGate states: a hard refusal is a trap. A
        // wish that ends imperfectly beats a wish that never ends, and the child is sitting there.
        // The second attempt goes through whatever it says — the refusal exists to redirect, not to
        // veto.
        String priorSource = readCurrentSource(id);
        String dropped = com.moddingfromamod.runtime.gate.DatapackDrift
                .between(priorSource, source, childWordsFor());
        if (!dropped.isEmpty() && REFUSED_ONCE.add(refusalKey(id))) {
            return ToolResult.error(dropped);
        }

        // ── REPAIR THE RENAMED ATTRIBUTE IDS, THE WAY WishExecutor ALREADY DOES ──────────────
        //
        // AttributeIds says in its own header that it is "one choke point covering both paths, and
        // any future caller". Datapacks are the future caller it never got, and they are the WORSE
        // place to miss it. A bad id in a command comes straight back to the agent as
        // "Can't find element 'minecraft:scale'"; the same id inside a datapack function is dropped
        // by Minecraft AT PACK LOAD, so CBScript compiles it happily, the pack reports enabled, and
        // the rule does nothing at all — with one line in the server log and nothing anywhere the
        // agent or the child can see.
        //
        // Measured here: a "make zombies bigger" standing rule authored with `minecraft:scale`
        // (1.21.2's name for what 1.21.1 calls minecraft:generic.scale) compiled, activated,
        // reported enabled, and left every zombie exactly the size it was.
        var repaired = com.moddingfromamod.wish.CommandDialect.correct(source, AuthorDatapackTool::attributeExists);
        String corrected = "";
        if (!repaired.notes().isEmpty()) {
            source = repaired.command();
            // Said out loud, for AttributeIds' own reason: a correction nobody can see is
            // indistinguishable from the source having been right all along, so the agent never
            // learns the id and writes it again next time.
            corrected = "\n(Corrected " + String.join(", ", repaired.notes())
                    + " — this build spells it that way, and the wrong spelling would have been "
                    + "dropped silently at pack load.)";
        }

        // ── REFUSE ONCE IF A RULE ABOUT THIS SPECIES ALREADY EXISTS ───────────────────
        //
        // Caught HERE rather than at runtime, deliberately. Two clocks over @e[type=zombie] is a
        // race: which wins depends on pack load order, the answer can differ between two loads of
        // the same world, and nothing reports it — so a runtime detector could only ever describe
        // the symptom, inconsistently. At authoring time the question has one right answer: a
        // second wish about zombies is an EDIT to the first rule.
        //
        // Once, then give way, for the same reason the drift refusal above gives way: a hard
        // refusal is a trap, and a wish that ends imperfectly beats one that never ends.
        String clash = com.moddingfromamod.datapack.StandingRules.collisionWarning(id, source);
        if (!clash.isEmpty() && REFUSED_ONCE.add(refusalKey(id) + "/species")) {
            return ToolResult.error(clash);
        }

        // ── REFUSE ONCE IF THE PACK HANDS OUT A MADE CARRIER FOR A REAL ITEM ────
        //
        // The side door: make_item refuses a made "Storm Trident" (it can only be clicked), and a
        // pack could write the same item by hand in a give line and nothing looked. Measured in the
        // an earlier pass: 16 archived packs did exactly that (DatapackItemGivesTest replays them). Once,
        // then give way — a look-alike he really wants is a second call away.
        var madeGives = com.moddingfromamod.datapack.DatapackItemGives.scan(source);
        if (!madeGives.isEmpty() && REFUSED_ONCE.add(refusalKey(id) + "/give")) {
            return ToolResult.error(com.moddingfromamod.datapack.DatapackItemGives.refusal(madeGives));
        }

        // Capture the agent's design reasoning for the manifest (DatapackForge.compile records it).
        com.moddingfromamod.provenance.ProvenanceContext.note(str(args, "note"));

        // Compile off the game thread: the compiler runs in-process and a big script takes a moment.
        CompileResult compiled = DatapackForge.compile(id, desc, source);
        if (!compiled.ok()) {
            return ToolResult.error("CBScript did not compile:\n\n" + compiled.failureSummary()
                    + "\n\nFix the source and call author_datapack again with the same feature_id. "
                    + "Call cbscript_help if you need the syntax reference.");
        }
        // on a shared world of bases a hand-written pack is allowed only when every line stays on HIS
        // base or HIS creatures — judged on the COMPILED functions, what will really run. A refused pack is not kept.
        String outOfScope = scopeRefusal(id);
        if (outOfScope != null) {
            try {
                java.nio.file.Files.deleteIfExists(com.moddingfromamod.datapack.DatapackStore.ownZipFor(id));
            } catch (java.io.IOException ignored) {
                // a stray zip is switched on by nothing: it was never installed
            }
            return ToolResult.error(outOfScope);
        }

        // WHAT WAS BUILT — derived from the program itself, so the child can be told how to set
        // this off. Before this, ONLY apply_effect recorded anything, so every authored pack
        // closed with no trigger sentence at all while the ready-made path had one. The agent is
        // deliberately not asked to declare its trigger: a declaration is a claim, and a right
        // build described wrongly is the one failure BuiltAbility exists to prevent. The program
        // cannot be wrong about what it fires on.
        //
        // Empty is a normal outcome, not a fallback: proximity has no derivable signature and a
        // pack may not be a trigger at all. describe() then returns "" exactly as it does today.
        String derived = com.moddingfromamod.datapack.TriggerShape.sentenceFor(source,
                trigger -> {
                    com.moddingfromamod.datapack.EffectCatalogue.Effect e =
                            com.moddingfromamod.datapack.EffectCatalogue.load().get(trigger);
                    return e == null ? null : e.childWhen();
                }, ApplyEffectTool::speciesName);
        if (!derived.isBlank()) {
            com.moddingfromamod.wish.BuiltAbility.record(
                    com.moddingfromamod.datapack.TriggerShape.of(source), derived,
                    com.moddingfromamod.datapack.TriggerShape.subjectTag(source));
        }

        // Optional reuse: an ACTION datapack (child re-triggers it) becomes a button; a RULE
        // (always-on once loaded) is not taught one — auto-load is its reuse. The agent DECLARES
        // which, by passing reuse_action or not; we never infer it. See ReplayContract / DATAPACKS.md.
        final com.google.gson.JsonObject template;
        try {
            template = reuseTemplate(id, args);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        String action = template == null ? ""
                : lastWord(template.getAsJsonArray("commands").get(0).getAsString());

        // Install + reload (and teach the button, if any) must happen on the server thread.
        try {
            com.moddingfromamod.datapack.DatapackVerifier.Report[] refused = {null};
            String packId = AgentBridge.onServer(() -> {
                try {
                    var srv = AgentBridge.serverOrNull();
                    String pid = DatapackForge.activate(srv, id);
                    // A function the server could not parse is dropped, and a dropped TICK function
                    // stops every datapack's clock in the world, other children's included.
                    // So it comes straight back off, and no button is taught for a dead pack.
                    refused[0] = com.moddingfromamod.datapack.DatapackReloader.takeBackIfRefused(srv, id);
                    if (template != null && refused[0] == null) {
                        com.moddingfromamod.wish.LearnedTemplates.addAndSave(srv, template);
                    }
                    if (refused[0] == null) {
                        registerLifetime(id);   //: a standalone pack on a world with pack lifetimes lives a while, renewed by play
                    }
                    return pid;
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
            if (refused[0] != null) {
                return ToolResult.error("Datapack '" + id + "' is NOT live: the server refused part of "
                        + "it, and it has been switched off again, because one refused tick function "
                        + "stops EVERY datapack in the world.\n" + refused[0].describe()
                        + "\nFix the named function(s) and call author_datapack again with the same id.");
            }
            String reuse = template != null
                    ? " It is now a BUTTON on the watch (keywords " + template.get("all")
                      + ") that replays instantly with /wishdatapack " + id + " " + action
                      + " — no agent next time."
                    : " This is a RULE datapack: it auto-loads into every world, so no button is "
                      + "needed. (If it actually has a 'give me another' action, call author_datapack "
                      + "again with reuse_action + reuse_words.)";
            // A loaded datapack is LIVE — the child can see its effects immediately. That makes
            // this the boundary of the one-attempt rule; see BuildGate.
            String stop = AgentBridge.onServer(() -> {
                var srv = AgentBridge.serverOrNull();
                var p0 = com.moddingfromamod.agent.ActingChild.of(srv);
                if (p0 == null) {
                    return "";
                }
                var gate = com.moddingfromamod.wish.BuildGate.current();   // THIS wish's
                boolean warn = gate.worldTouched() && gate.warnStopBuilding();
                gate.markWorldTouched();
                return warn ? STOP_BUILDING : "";
            });
            String immune = com.moddingfromamod.species.SelfHarmGuard.noteFor(id);   //
            return ToolResult.ok("Compiled and loaded datapack '" + id + "' (" + packId + ")."
                    + (immune.isEmpty() ? "" : "\n" + immune + "\n")
                    + corrected
                    + reuse + " It is LIVE NOW — do NOT call reload_datapacks; that would only "
                    + "freeze the game for tens of seconds and change nothing. Its functions are '"
                    + id + ":<name>'. Tell the child what to do with notify_player, and TEST a key "
                    + "function with run_command (e.g. run 'function " + id + ":"
                    + (action.isBlank() ? "tick" : action) + "') — a bad command compiles but fails "
                    + "to load. Wait a few seconds before that test: the reload is still settling, "
                    + "and a function run mid-reload can fail with 'This function should not run' "
                    + "even though it is fine."
                    // ALWAYS verify. Measured: 55 live datapacks authored, 3 verified -- a 5%
                    // rate against 200%+ for machines and trains, and the only difference between
                    // them is that those two are DEMANDED in the prompt and the lesson while this
                    // one was mentioned nowhere at all. Verification happens exactly where it is
                    // asked for, so it is asked for here, in the result the agent cannot miss.
                    + " THEN CALL verify_datapack '" + id + "' — a pack can compile, load, report "
                    + "success and be completely dead, and that failure is invisible in the world. "
                    + "It also tells you whether any RECIPES you wrote were accepted."
                    + stop
                    + com.moddingfromamod.wish.WishLessons.appendTo("datapack"));
        } catch (AgentBridge.Unavailable e) {
            // It compiled and is saved, but there's no open world to load it into right now.
            return ToolResult.ok(corrected + "Datapack '" + id + "' compiled and was saved, but I couldn't load "
                    + "it because no world is open (" + e.getMessage() + "). It will load when the "
                    + "child opens their world.");
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /**
     * Build the learned-wish template for a datapack's reuse button, or {@code null} for a RULE
     * datapack — the ACTION-vs-RULE decision made concrete.
     *
     * <p>An ACTION datapack (the agent passed {@code reuse_action}) becomes a button whose command
     * replays it via {@code /wishdatapack <id> <action>}; a RULE datapack (no {@code reuse_action})
     * gets no button, because it auto-loads every world. Pure and package-visible so the
     * "give me fireballs" (row 2) vs "the smash also drops a shield" (row 14) behaviour is
     * unit-tested without a running game.
     *
     * @throws IllegalArgumentException if {@code reuse_action} is malformed or lacks {@code reuse_words}
     */
    static com.google.gson.JsonObject reuseTemplate(String featureId, JsonObject args) {
        String action = str(args, "reuse_action").trim();
        if (action.isBlank()) {
            return null;   // RULE datapack — reuse is auto-load; no button
        }
        if (!action.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("reuse_action must be a bare function name like "
                    + "'give' — no slash, no namespace, no spaces. Got: '" + action + "'.");
        }
        java.util.List<String> raw = new java.util.ArrayList<>();
        if (args.has("reuse_words") && args.get("reuse_words").isJsonArray()) {
            args.getAsJsonArray("reuse_words").forEach(e -> raw.add(e.getAsString()));
        }
        // The whole-word matcher (WishWords) only matches single tokens, so a phrase like
        // "sparkle bomb" as one term never matches when the child types "sparkle bomb". Normalise
        // whatever the agent passed into clean single-word keywords, falling back to the feature id.
        java.util.List<String> keywords = cleanKeywords(raw, featureId);
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("reuse_action needs reuse_words (plain keywords for "
                    + "the button, e.g. ['fireball']).");
        }
        String say = str(args, "reuse_say").trim();
        com.google.gson.JsonObject template = new com.google.gson.JsonObject();
        template.addProperty("id", featureId);
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        keywords.forEach(all::add);
        template.add("all", all);
        com.google.gson.JsonArray cmds = new com.google.gson.JsonArray();
        cmds.add("wishdatapack " + featureId + " " + action);
        template.add("commands", cmds);
        template.addProperty("say", say.isBlank() ? "✨ Here you go!" : say);
        return template;
    }

    private static String lastWord(String s) {
        int i = s.lastIndexOf(' ');
        return i < 0 ? s : s.substring(i + 1);
    }

    /**
     * Turn whatever {@code reuse_words} the agent passed — phrases, plurals, concatenations — into a
     * small set of clean single-word keywords the whole-word matcher can actually match, so TYPING
     * the button's words (not just pressing it) serves it offline.
     *
     * <p>Splits on non-letters, keeps tokens of length ≥ 3, then drops any token that <b>contains</b>
     * a shorter kept token — which removes plurals ("bombs"→drop, keep "bomb") and concatenations
     * ("sparklebomb"→drop, keep "sparkle"). Falls back to the feature id's words if nothing usable
     * was passed. {@code ["sparkle bomb","sparkle bombs","sparklebomb"] → [sparkle, bomb]}.
     */
    static java.util.List<String> cleanKeywords(java.util.List<String> raw, String featureId) {
        java.util.LinkedHashSet<String> toks = new java.util.LinkedHashSet<>();
        for (String w : raw == null ? java.util.List.<String>of() : raw) {
            for (String t : w.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
                if (t.length() >= 3) {
                    toks.add(t);
                }
            }
        }
        if (toks.isEmpty() && featureId != null) {
            for (String t : featureId.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
                if (t.length() >= 3) {
                    toks.add(t);
                }
            }
        }
        java.util.List<String> list = new java.util.ArrayList<>(toks);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String t : list) {
            boolean redundant = false;
            for (String u : list) {
                if (!u.equals(t) && u.length() >= 3 && u.length() < t.length() && t.contains(u)) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                out.add(t);
            }
        }
        return out;
    }

    /** Feature ids already asked "is this a machine he fills?" — asked once, then it goes through. */
    private static final java.util.Set<String> ASKED_FILL =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The wish this worker is granting, or null outside one (a direct call, a replay, a test). */
    private static String boundWishText() {
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        if (worker == null || worker.isEmpty()) {
            return null;
        }
        var run = com.moddingfromamod.marshal.Marshal.global().runForWorker(worker);
        return run == null ? null : run.text();
    }
}
