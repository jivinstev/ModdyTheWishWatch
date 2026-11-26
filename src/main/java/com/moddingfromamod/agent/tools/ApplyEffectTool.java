package com.moddingfromamod.agent.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.datapack.DatapackVerifier;
import com.moddingfromamod.datapack.EffectCatalogue;
import com.moddingfromamod.entity.WishMobTags;

/**
 * <b>Give a creature a ready-made ability, without writing any code.</b>
 *
 * <p>The treatment arm of the effect-catalogue experiment — see docs/EFFECT_CATALOGUE.md. Today an
 * ability costs the agent a design decision and a CBScript authoring turn; measured over 47
 * completed real-play wishes, a wish takes a <b>112s median</b> while the compile it ends in takes
 * <b>881ms</b>. Almost none of the cost is the toolchain. This tool replaces the authoring turn
 * with a selection.
 *
 * <p>Two things it deliberately does NOT do:
 *
 * <ul>
 *   <li><b>It does not guess the effect.</b> An unknown id lists what IS available rather than
 *       picking something close, because a silently-substituted ability is a wrong answer the child
 *       has to notice.</li>
 *   <li><b>It does not accept unknown parameters.</b> Passing {@code DURATION} where the template
 *       wants {@code SECONDS} would otherwise fall back to a default: ask for thirty seconds, get
 *       five, and no error anywhere.</li>
 * </ul>
 *
 * <p>Reports the timings it can see — render and compile — so the before/after comparison this was
 * built for is measurable from the action log rather than from a stopwatch.
 */
public final class ApplyEffectTool implements McpTool {

    @Override
    public String name() {
        return "apply_effect";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(
                "Give a creature a READY-MADE ability. Faster and far more reliable than writing a "
                        + "datapack yourself: the code already exists and is known to compile, so "
                        + "prefer this over author_datapack whenever one of these fits. "
                        + "NOT FOR A PET: following him, being friendly to him, carrying him, or "
                        + "finding things for him are not abilities. Those are modify_mob's `follow`, "
                        + "`friendly`, `rideable` and `finds`, set on the real mob in one call; an "
                        + "ability that teleports a mob to him every few seconds is not following. "
                        + "Available effects:");
        try {
            // with the effect search on, one line an effect; off, the full text as always.
            // WHEN IT FIRES, shown to the agent, was omitted for months and is part of the
            // full text in ReadyMadeSources.effectDetail; WITH ITS ALLOWED VALUES too, because
            // printing only the names sent the agent probing (FEEL="?") to rediscover them.
            boolean slim = com.moddingfromamod.readymade.ReadyMadeConfig.searches(
                    com.moddingfromamod.readymade.ReadyMade.Kind.EFFECT);
            for (EffectCatalogue.Effect e : EffectCatalogue.load().all()) {
                if (slim) {
                    sb.append("\n  • ").append(e.id()).append(" — ").append(
                            com.moddingfromamod.readymade.ReadyMade.oneLine(e.summary()));
                } else {
                    sb.append("\n  • ").append(com.moddingfromamod.readymade.ReadyMadeSources.effectDetail(e));
                }
            }
            if (slim) {
                sb.append("\n  describe_ready_made <effect> shows when it fires, what it lands on and "
                        + "its parameters.");
            }
        } catch (RuntimeException ex) {
            // The catalogue failing to load must not remove the tool silently: an agent that sees
            // no effects simply goes back to authoring, and the experiment would look like a null
            // result rather than a broken fixture.
            sb.append("\n  (the catalogue could not be read: ").append(ex).append(')');
        }
        return sb.toString();
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "effect", "string",
                "Which ready-made ability, e.g. status_effect. See the list in this tool's "
                        + "description.");
        Schemas.prop(s, "tag", "string",
                "The creature's tag — the same id you gave sculpt_creature, e.g. turtle_king. For a REAL "
                        + "mob (a pet bee, a horse), summon it wearing a tag of your own — summon "
                        + "minecraft:bee ~ ~ ~ {Tags:[\"honey_bee\"]} — and pass that tag.");
        Schemas.prop(s, "params", "object",
                "Optional overrides for that effect's parameters, e.g. "
                        + "{\"EFFECT\":\"minecraft:poison\",\"SECONDS\":30}. Anything you leave out "
                        + "uses the effect's default. A parameter name that does not exist is an "
                        + "ERROR, not ignored.");
        return Schemas.required(s, "effect", "tag");
    }

    /**
     * <b>The tag an ability's selectors must actually use, given what the caller passed.</b>
     *
     * <p>A wish creature carries {@code wishmob} and {@code wishmob_<species>} and never a bare
     * {@code spikor}, so the species id the schema asks for is not a selector. But this tool is
     * also called with a LITERAL tag that somebody put on an ordinary entity by hand — the
     * ability-live gate tags a pig — and rewriting that would break it just as silently as
     * leaving the species id alone breaks a wish.
     *
     * <p>So the world decides, and it can answer because the creature already exists by the time
     * an ability is applied to it: prefer the species tag when something is actually wearing it,
     * fall back to the literal when something is wearing that instead. With nothing wearing
     * either — the ability is being written ahead of its creature — prefer the species tag, which
     * is the shape a wish creature will have when it arrives.
     *
     * <p>The oracle is injected rather than reached for, so the decision is unit-testable without
     * a server; {@link #call} passes one backed by the live entity list.
     */
    static String selectorTagFor(String given, java.util.function.Predicate<String> anyEntityWears) {
        if (given == null || given.isBlank()) {
            return WishMobTags.ALL;
        }
        String literal = given.strip();
        // Already a selector tag, or the catch-all: never re-prefix, or `wishmob_spikor` becomes
        // `wishmob_wishmob_spikor` and selects nothing in exactly the way this exists to prevent.
        if (WishMobTags.isSpeciesTag(literal) || WishMobTags.ALL.equals(literal)) {
            return literal;
        }
        String species = WishMobTags.forSpecies(literal);
        if (anyEntityWears.test(species)) {
            return species;
        }
        if (anyEntityWears.test(literal)) {
            return literal;
        }
        return species;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String effect = args.has("effect") ? args.get("effect").getAsString() : null;
        String tag = args.has("tag") ? args.get("tag").getAsString() : null;
        if (effect == null || effect.isBlank() || tag == null || tag.isBlank()) {
            return ToolResult.error("apply_effect needs both 'effect' and 'tag'.");
        }

        EffectCatalogue catalogue;
        try {
            catalogue = EffectCatalogue.load();
        } catch (RuntimeException e) {
            return ToolResult.error("the effect catalogue could not be read: " + e.getMessage());
        }
        if (catalogue.get(effect) == null) {
            return ToolResult.error("there is no ready-made effect called '" + effect
                    + "'. Available: " + catalogue.all().stream()
                    .map(EffectCatalogue.Effect::id).toList()
                    + ". If none of them fit, write it yourself with author_datapack.");
        }

        // ⚠ THE SELECTOR TAG IS NOT THE SPECIES ID, and this tool was the one place that assumed
        // it was. A wish creature carries exactly two tags — `wishmob` and `wishmob_<species>`
        // (WishMobEntity.applySpeciesTag) — and never a bare `spikor`. This tool's schema tells
        // the agent to pass "the same id you gave sculpt_creature, e.g. spikor", which is right,
        // and then substituted that bare id straight into the ability's selectors.
        //
        // The result is the worst-behaved failure available: the CBScript COMPILES, the datapack
        // loads, verify_datapack passes, apply_effect reports "Gave 'spikor' the ability", and the
        // clock it installed selects `@e[tag=spikor]` — which matches nothing in the world, for
        // ever. Measured on a real wish: "make me a Spikor that breathes fire" produced a verified
        // fire-breath datapack and a Spikor who never once breathed, and a 195-second recording of
        // that world contains zero frames with a flame particle in them.
        //
        // WishMobTags exists precisely so "the agent never has to invent a selector"; this is that
        // class being bypassed one call short of the finish line. Resolved here rather than in the
        // schema because the agent is already passing what it was asked for.
        String selectorTag = selectorTagFor(tag, WorldTags::wornSomewhere);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("TAG", selectorTag);
        if (args.has("params") && args.get("params").isJsonObject()) {
            JsonObject given = args.getAsJsonObject("params");
            for (String k : given.keySet()) {
                params.put(k, given.get(k).getAsString());
            }
        }

        // IMPULSE is a pseudo-entry: it takes the words a child uses and works out the physics,
        // because "0.8 blocks per tick for 6 ticks" is not a thing anybody wishes for.
        //
        // The trigger it resolves to is TAKEN OUT of params here rather than left in: the child must
        // be told "when he HITS you" rather than the word "impulse", but render() rejects any
        // parameter a template does not declare, and rightly so.
        // SECONDS -> TICKS. The child says "every 15-30 seconds", never "every 400 ticks", so the
        // parameter offered is seconds and the tick count is derived. Same rule as impulse's words:
        // if a value is a quantity a child would never say, do not ask for it.
        String secondsError = fillSeconds(catalogue.get(effect), params);
        if (secondsError != null) {
            return ToolResult.error(secondsError);
        }

        if ("colour_cycle".equals(effect)) {
            if (params.get("MOB") == null || params.get("MOB").isBlank()) {
                // Left out on the first live call. The mob is in the world wearing the tag, so ask
                // it rather than send the agent round again.
                String worn = WorldTags.typeWearing(selectorTag);
                if (worn != null) {
                    params.put("MOB", worn);
                }
            }
            String bad = fillColourCycle(params);
            if (bad != null) {
                return ToolResult.error(bad);
            }
        }

        if ("trail".equals(effect) || "drops".equals(effect)) {
            String bad = fillLeaveBehind(effect, params, catalogue.get(effect));
            if (bad != null) {
                return ToolResult.error(bad);
            }
        }

        // TARGET on an effect that runs its command AS THE CREATURE (periodic, proximity, on_death…):
        // there is no victim list to narrow, and "has no parameter 'TARGET'" sent Spark round three
        // more times with a command that hurt nothing (chunk 2). Say what does take it.
        if (params.containsKey("TARGET") && !catalogue.get(effect).params().contains("TARGET")) {
            return ToolResult.error("'" + effect + "' runs your COMMAND as the creature itself, so it "
                    + "has no TARGET. To land on others, use one that does — on_hit (whoever gets hurt "
                    + "near it), inflict_nearby or lingering_zone (whoever is near), breath_cone (whoever "
                    + "is in front), impulse (a push) — with TARGET: " + com.moddingfromamod.datapack.EffectVictims.WORDS
                    + ". Or keep '" + effect + "' and name them in COMMAND, e.g. damage @e[type="
                    + com.moddingfromamod.datapack.EffectVictims.MONSTERS_TAG + ",distance=..6] 4.");
        }

        // WHO it lands on, for an effect that picks its own victims: a creature's plain name
        // ("creepers", "the cow") becomes its id here, where the registry can be asked; the words
        // (mobs, monsters, players, everyone) and the default are EffectVictims', in render().
        if (catalogue.get(effect).derived().contains("VICTIMS") && params.containsKey("TARGET")
                && !com.moddingfromamod.datapack.EffectVictims.isWord(params.get("TARGET"))) {
            String id = mobId(params.get("TARGET"));
            if (id == null) {
                return ToolResult.error("I do not know a creature called '" + params.get("TARGET")
                        + "'. Use the name Minecraft uses (rabbit, not bunny), or say "
                        + com.moddingfromamod.datapack.EffectVictims.WORDS + ".");
            }
            params.put("TARGET", id);
        }

        String triggerId = effect;
        if ("impulse".equals(effect)) {
            String bad = fillImpulse(params);
            if (bad != null) {
                return ToolResult.error(bad);
            }
            String resolved = params.remove(RESOLVED_TRIGGER);
            if (resolved != null) {
                triggerId = resolved;
            }
        }

        // Its own selector inside a trigger's COMMAND: @s is ALREADY each one it steps or
        // lands near, so `execute as @e[...] run damage @s 1` runs once per victim on everyone in
        // the selector's range of where it stood, and TARGET is bypassed. Measured on the panel:
        // a push wish built that way pushed nothing, both models, three panels running. Refused,
        // with the two forms that work, so the agent writes the one it meant.
        String selector = selectorInCommand(effect, params);
        if (selector != null) {
            return ToolResult.error(selector);
        }
        // A trigger for "only when it walks / lands" on a creature that ALSO fights by itself is a
        // decision, not a footnote: the warning below was in bh-t02's result and the grant
        // shipped a guardian that bit the monsters while it stood still. ALSO_WHEN_STILL says which.
        String still = stillDecision(effect, params, com.moddingfromamod.species.SpeciesRegistry.get(tag));
        if (still != null) {
            return ToolResult.error(still);
        }
        String hunts = huntsHimDecision(effect, params, com.moddingfromamod.species.SpeciesRegistry.get(tag));
        if (hunts != null) {
            return ToolResult.error(hunts);
        }
        // How far it reaches is counted from its FLANK, not its middle.
        fillReach(effect, params, catalogue.get(effect),
                id -> com.moddingfromamod.vanilla.CreatureSpawn.halfWidth(id), tag);

        long t0 = System.nanoTime();
        String source;
        com.moddingfromamod.datapack.ReadyMadeSkeleton.Skeleton skeleton;
        try {
            source = catalogue.render(effect, params);
            // Its precompiled pack's key: a ready-made the store holds is built without compiling it.
            skeleton = catalogue.skeleton(effect, params);
        } catch (IllegalArgumentException e) {
            // Straight back to the agent, which can correct itself: this is the misspelled- or
            // missing-parameter case, and it is the one that would otherwise be silent.
            return ToolResult.error(e.getMessage());
        }
        long renderedMs = (System.nanoTime() - t0) / 1_000_000;

        if (!CbScriptCompiler.available() && !com.moddingfromamod.datapack.PrecompiledPacks.covers(DatapackForge.dialect(skeleton))) {
            return ToolResult.error("the CBScript compiler is turned off here (a test), and this ready-made "
                    + "effect is not one of the precompiled ones, so it cannot be built here.");
        }
        try {
            String featureId = featureId(effect, tag);
            long t1 = System.nanoTime();
            // THROUGH DatapackForge, exactly as author_datapack does. The first version compiled to
            // a TEMP DIRECTORY and never activated anything: every ready-made ability produced a zip
            // in /tmp, reported "Gave 'spikor' the ability", and changed nothing in the world.
            //
            // Nothing caught it for weeks. The compile matrix compiles; the behaviour tests run the
            // rendered COMMANDS directly rather than loading a pack; tier 3.5 judges the choice; and
            // the A/B judged correctness from the apply_effect ARGUMENTS. Every one of those can be
            // green while the child gets nothing, because not one of them asked the only question
            // that matters: is this ability actually LOADED in the world?
            CompileResult r = DatapackForge.compile(featureId, "ready-made ability: " + effect
                    + " for " + tag, source, skeleton);
            long compiledMs = (System.nanoTime() - t1) / 1_000_000;
            if (!r.ok()) {
                // A catalogue template that stopped compiling is a REGRESSION in the catalogue,
                // not something for the agent to debug, so say which template failed.
                return ToolResult.error("the ready-made '" + effect + "' template failed to "
                        + "compile, which is a bug in the catalogue rather than in your wish: "
                        + r.failureSummary());
            }
            // WHAT WAS BUILT, recorded so the closing sentence can be derived from it rather than
            // from what the agent meant. See BuiltAbility for why that distinction is load-bearing.
            EffectCatalogue.Effect spoken = catalogue.get(triggerId);
            if (spoken == null) {
                spoken = catalogue.get(effect);
            }
            com.moddingfromamod.wish.BuiltAbility.record(
                    triggerId, com.moddingfromamod.wish.ChildSubject.say(spoken.childWhen(), tag,
                            ApplyEffectTool::speciesName), tag);

            // LOAD IT. Compiling is not giving: until the pack is activated and the server reloads,
            // the ability exists only as a file. This runs on the server thread because a datapack
            // reload must.
            long t2 = System.nanoTime();
            String packId;
            // A/B SEAM for AbilityLiveClientTest (see docs/TESTING.md convention). Setting
            // -Ptestprops="skipEffectActivate=true" reproduces the original defect exactly:
            // compile the pack, never load it, report success. The gate must FAIL under it, or it
            // is not proven to detect the bug it was written for.
            if ("true".equals(System.getProperty(
                    com.moddingfromamod.ModdingFromAModMod.MODID + ".skipEffectActivate"))) {
                return ToolResult.ok("Gave '" + tag + "' the ready-made ability '" + effect
                        + "'.\n  [A/B SEAM: activation deliberately skipped]");
            }
            net.minecraft.server.MinecraftServer srv = AgentBridge.serverOrNull();
            if (srv == null) {
                // No world to load into (a test calling the tool directly). Say so plainly rather
                // than reporting success: "compiled" and "the creature HAS the ability" are
                // different claims, and conflating them is the entire bug this code path fixes.
                return ToolResult.ok("Built the ready-made '" + effect + "' for '" + tag
                        + "', but there is no running world to load it into, so it is NOT active."
                        + "\n  rendered in " + renderedMs + "ms, compiled in " + compiledMs + "ms"
                        + "\n  feature id: " + featureId);
            }
            // A datapack reload must happen on the server thread. The agent calls this from its own
            // thread and must hand the work over; a GameTest is ALREADY on the server thread, and
            // handing over from there deadlocks (AgentBridge refuses it outright). Both are real
            // callers, so both are handled rather than one being declared the only legitimate one.
            if (srv.isSameThread()) {
                packId = DatapackForge.activate(srv, featureId);
            } else {
                packId = AgentBridge.onServer(() -> {
                    try {
                        return DatapackForge.activate(srv, featureId);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            // DID THE SERVER TAKE EVERY FUNCTION? Minecraft drops a function that fails to parse,
            // whole, with one log line nobody reads. A template compiles every time, but it carries
            // the agent's own COMMAND, and one bad COMMAND kills the whole ability: "damage @e[…] 12"
            // (damage takes ONE entity) left on_step with no tick function at all, while this tool
            // said "now LIVE in the world" (chunk 3 panel). verify_datapack's own check.
            // TAKEN BACK OFF if not: a dropped tick function stops every other datapack's clock in
            // the world (DatapackReloader.takeBackIfRefused, shared with author_datapack and
            // make_item).
            java.util.function.Supplier<DatapackVerifier.Report> check =
                    () -> com.moddingfromamod.datapack.DatapackReloader.takeBackIfRefused(srv, featureId);
            DatapackVerifier.Report verified = srv.isSameThread() ? check.get() : AgentBridge.onServer(check::get);
            if (verified != null) {
                return ToolResult.error("'" + effect + "' for '" + tag + "' is NOT live: the server "
                        + "refused part of it, so it does nothing, and it has been switched off again."
                        + "\n" + verified.describe()
                        + "\nThe template is fine; what you passed in is not — usually COMMAND. In it, @s "
                        + "is already each one it lands on, so write e.g. 'damage @s 4', never @e[...].");
            }

            // BIND IT TO THE SPECIES —.
            //
            // `tag` is the species id ("the same id you gave sculpt_creature"), so the ability was
            // already ABOUT one creature; it just was not a property OF it. Recording it here means
            // it is listed with the species and, more importantly, torn down with it — the visible
            // bug was `remove_creature` killing the mob and leaving its fire breath firing at
            // nothing.
            com.moddingfromamod.species.Species bound = com.moddingfromamod.species.SpeciesRegistry
                    .get(tag);
            if (bound != null) {
                com.moddingfromamod.species.SpeciesRegistry.put(srv, bound.withAbilityPack(featureId));
            }
            long loadedMs = (System.nanoTime() - t2) / 1_000_000;

            // ⚠ "LIVE IN THE WORLD" HAS TO INCLUDE "AND IT CAN SEE THE CREATURE".
            //
            // Everything above proves the pack exists, compiles and loaded. None of it proves the
            // clock it installed selects anything, and a selector that matches nothing is not an
            // error at any layer: `execute as @e[tag=X] run …` with no matches is a SUCCESSFUL
            // no-op, so the functions run, verify_datapack passes, and the ability is inert for
            // ever. That is precisely how a whole catalogue of abilities aimed at the bare species
            // id went unnoticed.
            //
            // A warning rather than an error, because writing an ability BEFORE its creature
            // arrives is legitimate — but the agent is told plainly, in the one place it is about
            // to tell the child that the wish came true.
            String liveness = WorldTags.wornSomewhere(selectorTag) ? ""
                    : "\n⚠ NOTHING IN THE WORLD IS WEARING THE TAG '" + selectorTag + "', so this "
                      + "ability currently selects nobody and will do nothing. It is not an error "
                      + "if you have not summoned the creature yet — but if it is already out "
                      + "there, check what it is really tagged with (a wish creature wears "
                      + "'wishmob_<species>') before telling the child it worked.";

            String immune = com.moddingfromamod.species.SelfHarmGuard.noteFor(featureId);   //
            return ToolResult.ok("Gave '" + tag + "' the ready-made ability '" + effect
                    + "', and it is now LIVE in the world." + liveness
                    + (immune.isEmpty() ? "" : "\n  " + immune)
                    + onlyWhenNote(effect, bound)
                    + liftsOnlyNote(effect, params)
                    + healsWhomNote(effect, params)
                    + "\n  rendered in " + renderedMs + "ms, compiled in " + compiledMs
                    + "ms, loaded in " + loadedMs + "ms"
                    + "\n  feature id: " + featureId + "  (pack " + packId + ")"
                    + "\nNo code was written: this template was already known to compile.");
        } catch (Exception e) {
            return ToolResult.error("could not build the ready-made effect: " + e);
        }
    }

    /**
     * Turn direction/strength/feel into the movement, selector and telegraph the template needs.
     *
     * <p>Three decisions worth knowing about:
     *
     * <ul>
     *   <li><b>AWAY and TOWARD steer by FACING, not by a vector.</b> {@code facing entity} then
     *       {@code tp @s ^ ^ ^±step} moves along the line between the two, so the direction is right
     *       wherever either of them stands — no arithmetic, and it works on a player, which a Motion
     *       vector does not.</li>
     *   <li><b>UP uses Motion</b>: there is no facing trick for vertical, and the vector is the same
     *       for everyone.</li>
     *   <li><b>RAMP is the default.</b> One big shove lands inside a single tick, so there is nothing
     *       to watch and a child reads it as the game glitching rather than as being hit. Slices over
     *       several ticks are what make it read as a PUNCH — and needing per-victim state to do that
     *       is exactly why impulse is an entry instead of another COMMAND.</li>
     * </ul>
     */
    private static String fillImpulse(java.util.Map<String, String> params) {
        String tag = params.get("TAG");
        // TRIGGER IS REQUIRED. It used to default to on_hit, and the A/B that fixed retrieval
        // showed what that cost: of the three wrong builds, ALL THREE simply omitted TRIGGER --
        // "knocks everyone back every 5 seconds", "pulls all the zombies to him" and "from 15
        // blocks away" were each quietly built as on_hit. The agent never chose wrongly; it never
        // chose at all, and a wish that stated its own timing was converted into a different
        // ability with a green build and no error anywhere.
        //
        // The default was defensible in the abstract (see the playbook: given a coin flip, prefer
        // the hit, because an aura cannot be escaped) and that reasoning is KEPT -- but it belongs
        // where the wish text is still visible, so it now lives in the parameter doc and in the
        // refusal below as guidance for the caller, rather than as a fill-in applied long after
        // the words are gone. A default cannot tell an ambiguous wish from an explicit one.
        String trigger = params.get("TRIGGER");
        if (trigger == null || trigger.isBlank()) {
            return "impulse needs to know WHEN to fire: TRIGGER=on_hit (when it hits something), "
                    + "proximity (when something comes near) or periodic (on a timer, with "
                    + "EVERY_SECONDS). Take it from the child's own words -- a time like 'every 5 "
                    + "seconds' is periodic, a distance like 'from 15 blocks away' is proximity, "
                    + "and 'when it punches me' is on_hit. If the wish truly does not say when, "
                    + "pick on_hit: an always-on aura cannot be escaped and reads as the game "
                    + "being broken, while a hit that guessed wrong sits quietly and can be tested.";
        }
        String direction = params.getOrDefault("DIRECTION", "away").toLowerCase(java.util.Locale.ROOT);
        String strength = params.getOrDefault("STRENGTH", "shove").toLowerCase(java.util.Locale.ROOT);
        String feel = params.getOrDefault("FEEL", "ramp").toLowerCase(java.util.Locale.ROOT);
        String radius = params.getOrDefault("RADIUS", "4");
        String target = params.getOrDefault("TARGET", com.moddingfromamod.datapack.EffectVictims.DEFAULT)
                .toLowerCase(java.util.Locale.ROOT);
        String softLanding = params.getOrDefault("SOFT_LANDING", "yes").toLowerCase(java.util.Locale.ROOT);
        String everySeconds = params.getOrDefault("EVERY_SECONDS", "0");

        // WORDS, deliberately. A child asks for a nudge or to be sent flying, never for 0.55.
        //
        // An unrecognised word is REFUSED, not quietly defaulted. `STRENGTH=999` reads as "the
        // hardest push there is" and silently building a middling one is the same defect as
        // guessing an unknown creature: the ability builds, nothing errors, and it simply is not
        // what was asked for. That failure is invisible from the log -- the only way to notice is
        // to play it and feel that the push is wrong.
        double perTick = switch (strength) {
            case "nudge" -> 0.18;
            case "shove" -> 0.40;
            case "launch" -> 0.75;
            case "huge" -> 1.30;
            default -> -1;
        };
        if (perTick < 0) {
            return "I do not know a push called '" + strength + "'. Use nudge, shove, launch or "
                    + "huge. Guessing here would build a push that is not the one asked for.";
        }
        int ramp = switch (strength) {
            case "nudge" -> 3;
            case "launch" -> 8;
            case "huge" -> 12;
            default -> 6;
        };
        if (!"ramp".equals(feel) && !"snap".equals(feel)) {
            return "I do not know a feel called '" + feel + "'. Use ramp (the push builds over a "
                    + "few ticks, so it reads as a hit) or snap (all of it in one tick).";
        }
        if ("snap".equals(feel)) {
            perTick = perTick * ramp;   // the whole push in one tick, for a wish that means instant
            ramp = 1;
        }

        // WHO gets pushed. Without this, "my dragon should fling the CREEPERS up super high" flings
        // the child too -- tier 3.5 caught it by reading the description and finding no target
        // filter at all. `everyone` keeps the old behaviour, so this only ever narrows.
        // WHO gets pushed is the rule every effect shares (EffectVictims, chunk 2), default
        // everything but players: "fling the CREEPERS" flung the child too until TARGET existed,
        // and with TARGET defaulting to everyone, "knock the monsters back" still did.
        String who = com.moddingfromamod.datapack.EffectVictims.isWord(target) ? target : mobId(target);
        String typeClause = who == null ? null : com.moddingfromamod.datapack.EffectVictims.clause(who);
        if (typeClause == null) {
            return "I do not know a creature called '" + target + "'. Use the name Minecraft uses "
                    + "(rabbit, not bunny), or say " + com.moddingfromamod.datapack.EffectVictims.WORDS
                    + ". Guessing here would build an ability that silently does nothing.";
        }

        String base = ",distance=.." + radius + ",tag=!" + tag + typeClause;
        String victim = switch (trigger) {
            case "proximity", "periodic" -> "@e[" + base.substring(1) + "]";
            case "on_hit" -> "@e[nbt={HurtTime:10s}" + base + "]";
            default -> null;
        };
        if (victim == null) {
            return "impulse TRIGGER must be 'on_hit', 'proximity' or 'periodic', not '"
                    + trigger + "'.";
        }
        // The SOURCE's clock, which restarts only when the source FIRES. on_hit: 1, every
        // hit is its own event. proximity: a RE-ARM — it fires the moment something comes into
        // reach, then waits, so a mob standing beside it is shoved once a second rather than held
        // down twenty times a second. periodic turns the child's seconds into ticks.
        int periodTicks = "proximity".equals(trigger)
                ? com.moddingfromamod.datapack.ImpulseTelegraphFix.PROXIMITY_REARM_TICKS : 1;
        if ("periodic".equals(trigger)) {
            double secs;
            try {
                secs = Double.parseDouble(everySeconds);
            } catch (NumberFormatException e) {
                return "impulse EVERY_SECONDS must be a number of seconds, not '" + everySeconds
                        + "'.";
            }
            if (secs <= 0) {
                return "impulse with TRIGGER=periodic needs EVERY_SECONDS, e.g. 5 for 'every 5 "
                        + "seconds'. Without it there is nothing to time.";
            }
            periodTicks = Math.max(1, (int) Math.round(secs * 20));
        }

        String move = switch (direction) {
            case "away" -> "execute facing entity @e[tag=" + tag + ",limit=1] feet run tp @s ^ ^ ^-"
                    + fmt(perTick);
            case "toward" -> "execute facing entity @e[tag=" + tag + ",limit=1] feet run tp @s ^ ^ ^"
                    + fmt(perTick);
            case "up" -> "data modify entity @s Motion set value [0.0d," + fmt(perTick) + "d,0.0d]";
            // DOWN drags you under -- "he sucks you into the deep water". Tier 3.5 found it
            // missing: away/up/toward cannot express a downward pull, so the wish became a
            // horizontal tug and the "deep" part, which is the whole picture, was silently dropped.
            case "down" -> "data modify entity @s Motion set value [0.0d,-" + fmt(perTick)
                    + "d,0.0d]";
            default -> null;
        };
        if (move == null) {
            return "impulse DIRECTION must be 'away', 'up', 'down' or 'toward', not '"
                    + direction + "'.";
        }

        params.put("VICTIM_SELECTOR", victim);
        params.put("MOVE", move);
        params.put("RAMP_TICKS", String.valueOf(ramp));
        params.put("PERIOD_TICKS", String.valueOf(periodTicks));
        // WHEN a source whose clock is up fires: periodic on the clock alone — a stomp every
        // five seconds stomps with nobody near; proximity and on_hit only when something is caught,
        // so with nothing in reach they are silent instead of telegraphing every tick.
        params.put("FIRE_IF", "periodic".equals(trigger) ? "" : "if entity " + victim + " ");
        // FALL DAMAGE. "make the monster launch me way up in the SKY" is a wish for the launch, not
        // for the landing -- and at STRENGTH=huge the landing is lethal. Tier 3.5 flagged that the
        // description said nothing about it and offered no way to soften it. Slow falling is the
        // cheap fix and it keeps the flight, which is the part that was wished for.
        boolean soften = !"no".equals(softLanding) && "up".equals(direction);
        params.put("LANDING", soften
                ? "effect give @s minecraft:slow_falling 12 0 true"
                : "scoreboard players reset @s mfam_noop");
        // A telegraph, because movement with no cause reads as a bug. See the template.
        params.put("PARTICLE", "toward".equals(direction) ? "minecraft:portal" : "minecraft:cloud");
        params.put("SOUND", switch (direction) {
            case "up" -> "minecraft:entity.breeze.jump";
            case "toward" -> "minecraft:block.beacon.activate";
            default -> "minecraft:entity.player.attack.knockback";
        });
        params.keySet().removeAll(java.util.List.of("TRIGGER", "DIRECTION", "STRENGTH", "FEEL",
                "TARGET", "EVERY_SECONDS", "SOFT_LANDING"));
        // Kept, not discarded: the child must be told the trigger impulse actually resolved to.
        // Stripped again before render() so it never reaches the template as a parameter.
        params.put(RESOLVED_TRIGGER, trigger);
        return null;
    }

    /**
     * impulse's template parameters from the words, exactly as {@link #call} fills them (TAG
     * included, used as given) — for a test that RUNS the rendered template. Throws on
     * words impulse would refuse.
     */
    public static java.util.Map<String, String> impulseTemplateParams(java.util.Map<String, String> words) {
        java.util.Map<String, String> p = new java.util.LinkedHashMap<>(words);
        String bad = fillImpulse(p);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        p.remove(RESOLVED_TRIGGER);
        return p;
    }

    /**
     * Internal key carrying the trigger impulse resolved to, from {@link #fillImpulse} to the
     * announcement. Never a template parameter and never offered to the agent.
     */
    private static final String RESOLVED_TRIGGER = "__resolved_trigger";

    /** Which friendly SECONDS parameter feeds which derived TICKS parameter, per entry. */
    private static final java.util.Map<String, java.util.Map<String, String>> SECONDS_TO_TICKS =
            java.util.Map.of(
                    "lingering_zone", java.util.Map.of(
                            "EVERY_SECONDS", "PERIOD_TICKS", "LASTS_SECONDS", "LASTS_TICKS"),
                    "breath_cone", java.util.Map.of(
                            "EVERY_SECONDS", "PERIOD_TICKS", "BREATH_SECONDS", "BREATH_TICKS"),
                    "erupt_blocks", java.util.Map.of(
                            "EVERY_SECONDS", "PERIOD_TICKS", "LASTS_SECONDS", "LASTS_TICKS"),
                    "colour_cycle", java.util.Map.of("EVERY_SECONDS", "PERIOD_TICKS"),
                    "trail", java.util.Map.of("LASTS_SECONDS", "LASTS_TICKS"),
                    "drops", java.util.Map.of("EVERY_SECONDS", "PERIOD_TICKS"));

    /**
     * {@code colour_cycle}'s MOB into the commands that step it through its colours.
     * Package-private so a unit test can drive it without a server.
     */
    public static String fillColourCycle(java.util.Map<String, String> params) {
        String mob = params.remove("MOB");
        if (mob == null || mob.isBlank()) {
            return "colour_cycle needs MOB — which vanilla mob it is, e.g. {\"MOB\":\"axolotl\"} "
                    + "— and nothing wearing that tag was found to ask. Summon the mob with the "
                    + "tag first.";
        }
        var palette = com.moddingfromamod.datapack.ColourCycle.palette(mob);
        if (palette == null) {
            return "colour_cycle cannot recolour '" + mob + "': it only knows the mobs that have "
                    + "colours of their own — " + com.moddingfromamod.datapack.ColourCycle.mobs()
                    + ". For anything else, sculpt the creature in the colours you want.";
        }
        String tag = params.get("TAG");
        int period = Integer.parseInt(params.get("PERIOD_TICKS"));
        params.put("KEY", com.moddingfromamod.datapack.ColourCycle.keyFor(tag));
        params.put("STEPS", com.moddingfromamod.datapack.ColourCycle.steps(tag, palette, period));
        params.put("COUNT", String.valueOf(palette.values().size()));
        return null;
    }

    /** The fewest ticks between drops: a creature that drops something every tick is a fountain. */
    static final int DROPS_MIN_TICKS = 100;

    /**
     * check what a trail or a drop is made of before it is rendered, in the words the agent
     * can act on. A trail's BLOCK is put round the creature's own feet, so it must be something it
     * walks through (a snow layer, a carpet, a flower) — a full block would wall it in; a drop must
     * be a real item; and both are bounded so neither can run away.
     */
    public static String fillLeaveBehind(String effect, java.util.Map<String, String> params,
                                  EffectCatalogue.Effect entry) {
        java.util.function.BiFunction<String, Integer, Integer> bounded = (key, max) -> {
            String v = params.getOrDefault(key, entry == null ? null : entry.defaults().get(key));
            int n;
            try {
                n = v == null ? max : (int) Math.round(Double.parseDouble(v.trim()));
            } catch (NumberFormatException e) {
                n = max;
            }
            n = Math.max(1, Math.min(max, n));
            params.put(key, String.valueOf(n));
            return n;
        };
        if ("trail".equals(effect)) {
            bounded.apply("MOST", 200);
            params.put("STEP_TICKS", "4");   // a mark every few steps, not one per tick
            String raw = params.getOrDefault("BLOCK", entry == null ? "minecraft:snow" : entry.defaults().get("BLOCK"));
            String id = raw.contains("[") ? raw.substring(0, raw.indexOf('[')) : raw;
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
            var block = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
                return "trail's BLOCK '" + raw + "' is not a block. Use a thin one it can walk over: "
                        + "minecraft:snow, a flower like minecraft:poppy, or a carpet like minecraft:red_carpet.";
            }
            var shape = block.defaultBlockState().getCollisionShape(
                    net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
            if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 0.5) {
                return "trail's BLOCK '" + raw + "' is a full block: it would be put round the creature's "
                        + "own feet. A trail is a THIN thing — minecraft:snow for ice or frost, a flower, or a "
                        + "carpet (minecraft:light_blue_carpet for an icy path).";
            }
            params.put("BLOCK", rl.toString() + (raw.contains("[") ? raw.substring(raw.indexOf('[')) : ""));
            return null;
        }
        bounded.apply("MOST", 32);
        String raw = params.getOrDefault("ITEM", entry == null ? "minecraft:honey_bottle" : entry.defaults().get("ITEM"));
        var rl = net.minecraft.resources.ResourceLocation.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        var item = rl == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return "drops' ITEM '" + raw + "' is not an item id. e.g. minecraft:honey_bottle, minecraft:egg.";
        }
        params.put("ITEM", rl.toString());
        int ticks = Integer.parseInt(params.getOrDefault("PERIOD_TICKS", "400"));
        params.put("PERIOD_TICKS", String.valueOf(Math.max(DROPS_MIN_TICKS, ticks)));
        return null;
    }

    /**
     * Convert each of an entry's seconds parameters into the tick count its template wants.
     *
     * <p>Reads the catalogue DEFAULT when the agent left it out, rather than assuming: an entry
     * whose default said 8 seconds and whose template then got no PERIOD_TICKS would fail to render
     * with an unfilled placeholder — a confusing error about a parameter the agent never saw.
     */
    private static String fillSeconds(EffectCatalogue.Effect entry,
                                      java.util.Map<String, String> params) {
        if (entry == null) {
            return null;
        }
        java.util.Map<String, String> mapping = SECONDS_TO_TICKS.get(entry.id());
        if (mapping == null) {
            return null;
        }
        for (var pair : mapping.entrySet()) {
            String given = params.get(pair.getKey());
            if (given == null) {
                given = entry.defaults().get(pair.getKey());
            }
            if (given == null) {
                return entry.id() + " needs " + pair.getKey() + " (in seconds).";
            }
            double secs;
            try {
                secs = Double.parseDouble(given.trim());
            } catch (NumberFormatException e) {
                return entry.id() + "'s " + pair.getKey() + " must be a number of seconds, not '"
                        + given + "'.";
            }
            if (secs <= 0) {
                return entry.id() + "'s " + pair.getKey() + " must be more than zero seconds.";
            }
            params.put(pair.getValue(), String.valueOf(Math.max(1, (int) Math.round(secs * 20))));
            params.remove(pair.getKey());
        }
        return null;
    }

    /**
     * A child's word for a creature into an entity id: "creepers" -> minecraft:creeper.
     *
     * <p>Plural-stripping is deliberate and shallow. The wishes are "the creepers", "all the
     * zombies", "the skeletons" — regular plurals of vanilla mobs — and anything with a namespace
     * is passed through untouched so a modded creature still works.
     */
    /** Test seam: what a child's word for a creature resolves to. */
    /**
     * A trigger that means "only when it lands / walks" on a creature that ALSO goes for monsters
     * on its own, standing still: a guardian or hunter instinct, or a slam. The chunk 3 panel:
     * agents chose on_step correctly and made the creature a guardian too, which fought the monsters
     * while it was held still, so "only when it walks" was never true. Said, not refused: a guardian
     * that also stomps as it walks is a fair wish too.
     */
    static String onlyWhenNote(String effect, com.moddingfromamod.species.Species sp) {
        if (sp == null || !("on_step".equals(effect) || "on_land".equals(effect))) {
            return "";
        }
        boolean fights = com.moddingfromamod.vanilla.RigInstinct.of(sp).fights();
        boolean slams = sp.abilities() != null
                && sp.abilities().contains(com.moddingfromamod.runtime.Abilities.Kind.SLAM);
        if (!fights && !slams) {
            return "";
        }
        return "\n  ⚠ '" + sp.id() + "' ALSO goes for monsters by itself, standing still ("
                + (fights ? "its " + sp.base().name().toLowerCase(java.util.Locale.ROOT) + " instinct" : "")
                + (fights && slams ? " and " : "") + (slams ? "its slam" : "") + "). If the wish is ONLY when it "
                + ("on_step".equals(effect) ? "walks" : "lands") + ", sculpt it 'wild' with no slam.";
    }

    /**
     * on_land / on_step aimed at the monsters with no COMMAND: the default lifts them (a
     * landing) or only thumps (a step), and hurts nobody. bh-n03 ("it stomps on the monsters") shipped
     * that way. Said rather than refused: "the ground shakes and they go flying" is that default.
     */
    static String liftsOnlyNote(String effect, java.util.Map<String, String> params) {
        if (!REACH_FROM_BODY.contains(effect) || params == null || params.containsKey("COMMAND")) {
            return "";
        }
        String target = params.getOrDefault("TARGET", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (target.isEmpty() || target.equals("players") || target.equals("everyone")) {
            return "";
        }
        return "\n  ⚠ No COMMAND, so this " + ("on_land".equals(effect) ? "only lifts them when it lands"
                : "only makes footsteps") + " and HURTS NOBODY. If the wish says it squashes, stomps on or "
                + "hurts them, apply it again with COMMAND 'damage @s 6'"
                + ("on_land".equals(effect) ? " (and a stomp while he just rides it along is on_step: on_land "
                + "fires only after a jump)." : ".");
    }

    /** The tool's own answer key for {@link #huntsHimDecision}: never a template parameter. */
    public static final String ATTACKS_HIM = "ATTACKS_HIM";

    /**
     * An effect that lands on OTHERS, given to a creature that hunts HIM. Spark built bh-r07
     * that way in every panel since the baseline (4 of 4): a creature sculpted without 'friendly'
     * went for the child, never for a monster, so its on_hit poison landed on nobody it was meant
     * for and its bite killed him. Asked, like ALSO_WHEN_STILL: "yes" is a monster meant to attack
     * him (a fair wish), "no" is re-sculpt it friendly (+ guardian to fight the monsters) first.
     * Null to go ahead. {@code ATTACKS_HIM} is taken out of params either way.
     */
    public static String huntsHimDecision(String effect, java.util.Map<String, String> params,
                                          com.moddingfromamod.species.Species sp) {
        String said = params == null ? null : params.remove(ATTACKS_HIM);
        if (sp == null || effect == null || !ON_VICTIMS.contains(effect)
                || !com.moddingfromamod.vanilla.RigInstinct.of(sp).huntsPlayers()) {
            return null;
        }
        String target = params == null ? "" : params.getOrDefault("TARGET", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (target.equals("players") || target.equals("everyone")) {
            return null;   // aimed at him on purpose
        }
        String a = said == null ? "" : said.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.equals("yes") || a.equals("true")) {
            return null;
        }
        if (a.equals("no") || a.equals("false")) {
            return "Then make '" + sp.id() + "' friendly first (powers: [\"friendly\"], and base 'guardian' if it "
                    + "should fight the monsters), with modify_mob or sculpt_creature, THEN apply '" + effect
                    + "'. As it is, it attacks him and never the monsters.";
        }
        return "'" + sp.id() + "' is not friendly, so it HUNTS HIM: it goes for the child, not the monsters, and '"
                + effect + "' lands on whoever it hurts or stands near while it does. Decide from the wish's words "
                + "and say so: apply it again with params ATTACKS_HIM: \"yes\" if it is a monster meant to attack him, "
                + "or \"no\" if it should fight the monsters and leave him alone (then make it friendly first, "
                + "+ guardian to fight them).";
    }

    /** The tool's own answer key for {@link #stillDecision}: never a template parameter. */
    public static final String ALSO_WHEN_STILL = "ALSO_WHEN_STILL";

    /**
     * An on_step / on_land given to a creature that also goes for monsters by itself (a guardian or
     * hunter instinct, a slam) must say whether that is the wish. Null to go ahead; else the
     * refusal. {@code ALSO_WHEN_STILL} is taken out of params either way.
     *
     * <p>Said, then not refused, chunk 3 ({@link #onlyWhenNote}): Opus read it in bh-t02
     * and r03 and shipped a creature that hurt the monsters while it stood still. Asked, it cannot be
     * read past: "yes" is a guardian that also stomps (a fair wish, bh-n02's), "no" is re-sculpt it
     * wild with no slam first.
     */
    public static String stillDecision(String effect, java.util.Map<String, String> params,
                                       com.moddingfromamod.species.Species sp) {
        String said = params == null ? null : params.remove(ALSO_WHEN_STILL);
        String note = onlyWhenNote(effect, sp);
        if (note.isEmpty()) {
            return null;
        }
        String a = said == null ? "" : said.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.equals("yes") || a.equals("true")) {
            return null;
        }
        String when = "on_step".equals(effect) ? "walks" : "lands";
        if (a.equals("no") || a.equals("false")) {
            return "Then '" + sp.id() + "' must not fight by itself: sculpt it again with base 'wild' and no "
                    + "slam (modify_mob or sculpt_creature), THEN apply '" + effect + "'. As it is, it hurts "
                    + "monsters standing still and the wish says only when it " + when + ".";
        }
        return note.strip().replace("⚠ ", "") + " Decide from the wish's words and say so: apply '" + effect
                + "' again with params ALSO_WHEN_STILL: \"yes\" if it should ALSO fight them standing still "
                + "(a guardian that stomps too), or \"no\" if the wish means ONLY when it " + when
                + " (then re-sculpt it wild with no slam first).";
    }

    /** The triggers whose COMMAND runs as each one it steps or lands near. */
    static final java.util.Set<String> REACH_FROM_BODY = java.util.Set.of("on_step", "on_land");
    private static final java.util.regex.Pattern OTHER_SELECTOR =
            java.util.regex.Pattern.compile("@[eapr](?![a-z0-9_])");
    private static final java.util.regex.Pattern BY_SOURCE =
            java.util.regex.Pattern.compile("\\sby\\s+@[eapr](\\[[^\\]]*\\])?");

    /**
     * A selector other than {@code @s} in an on_step / on_land COMMAND, or null. The lesson
     * has said "never write @e[...] inside it" chunk 3; Spark's t02 and Opus's t05 wrote
     * it anyway, and neither hurt nor pushed what the wish named.
     */
    public static String selectorInCommand(String effect, java.util.Map<String, String> params) {
        if (!REACH_FROM_BODY.contains(effect) || params == null) {
            return null;
        }
        // `damage @s 1 minecraft:mob_attack by @e[tag=<its tag>,...]` names WHO hit them, which is
        // what knocks them away from it (the chunk 3 push that worked); only the source may select.
        String cmd = BY_SOURCE.matcher(params.getOrDefault("COMMAND", "")).replaceAll(" ");
        if (!OTHER_SELECTOR.matcher(cmd).find()) {
            return null;
        }
        return "'" + effect + "' runs COMMAND AS each one it " + ("on_step".equals(effect) ? "steps" : "lands")
                + " near, so @s already IS the monster (or whoever TARGET says). Write it without "
                + "@e/@a/@p: 'damage @s 4' hurts them; 'data modify entity @s Motion set value "
                + "[0.0d,0.5d,0.0d]' knocks them up; 'damage @s 1 minecraft:mob_attack by @e[tag="
                + params.getOrDefault("TAG", "its tag") + ",sort=nearest,limit=1]' knocks them away from "
                + "it; 'effect give @s minecraft:slowness 3 1' "
                + "stuns them. Say WHO with TARGET (" + com.moddingfromamod.datapack.EffectVictims.WORDS
                + ", or a creature like zombies) and how far with RADIUS — never with a selector in "
                + "COMMAND.";
    }

    /**
     * RADIUS counted from the creature's flank: its half width is added before rendering, so
     * "how close to its feet counts" holds at any size. Measured from its middle, a scale-2 trex's
     * default stomp reached 1.3 blocks past its side and a scale-4 creature's barely left its own
     * body: ridden past a line of monsters, two stomps of five on the panel landed on nothing.
     */
    public static void fillReach(String effect, java.util.Map<String, String> params,
                          EffectCatalogue.Effect entry,
                          java.util.function.ToDoubleFunction<String> halfWidth, String species) {
        if (!REACH_FROM_BODY.contains(effect) || params == null || entry == null) {
            return;
        }
        String given = params.getOrDefault("RADIUS", entry.defaults().get("RADIUS"));
        double r;
        try {
            r = Double.parseDouble(given == null ? "" : given.trim());
        } catch (NumberFormatException e) {
            return;   // render() names a bad number; nothing to add to
        }
        double half = species == null ? 0.0 : halfWidth.applyAsDouble(species);
        if (half > 0.0) {
            params.put("RADIUS", String.format(java.util.Locale.ROOT, "%.1f", r + half));
        }
    }

    /** The effects that run their command on VICTIMS near the creature, never on the creature. */
    private static final java.util.Set<String> ON_VICTIMS = java.util.Set.of(
            "on_hit", "on_land", "on_step", "breath_cone", "inflict_nearby", "lingering_zone");
    private static final java.util.regex.Pattern HELPS = java.util.regex.Pattern.compile(
            "(regeneration|instant_health|absorption|health_boost|resistance|saturation)");

    /**
     * A HEAL handed to the victims (routing arm): "heals itself when it gets hurt" was built
     * 8 times of 8, on both models, as {@code on_hit} with {@code effect give @s regeneration} —
     * and on_hit's {@code @s} is whoever ELSE was hurt near it, so the creature healed the monsters
     * it was fighting and never itself. Said, not refused: a pet that heals HIM is TARGET players.
     */
    static String healsWhomNote(String effect, java.util.Map<String, String> params) {
        if (effect == null || !ON_VICTIMS.contains(effect) || params == null) {
            return "";
        }
        String target = params.getOrDefault("TARGET", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (target.equals("players") || target.equals("me") || target.equals("player")) {
            return "";
        }
        String what = (params.getOrDefault("COMMAND", "") + " " + params.getOrDefault("EFFECT", ""))
                .toLowerCase(java.util.Locale.ROOT);
        if (!HELPS.matcher(what).find()) {
            return "";
        }
        return "\n  ⚠ This HELPS whoever it lands on — the monsters (and animals) near it, never '"
                + params.getOrDefault("TAG", "the creature") + "' itself: '" + effect + "' runs on the "
                + "others. For a creature that heals ITSELF when hurt, sculpt it with abilities: "
                + "[\"heal\"] (or give it regeneration directly). Leave this only if healing the "
                + "others is the wish.";
    }

    public static String resolveCreatureId(String word) {
        return mobId(word);
    }

    private static String mobId(String word) {
        String w = word.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (w.contains(":")) {
            return w;
        }
        // ASK THE GAME, do not guess. The guessing version turned "zombies" into minecraft:zomby
        // (the -ies rule fired before the -s rule), "wolves" into minecraft:wolve and "endermen"
        // into minecraft:endermen. An invalid entity type makes the WHOLE command fail to parse, so
        // the ability silently did nothing -- and nothing upstream could see it: tier 3.5 checks
        // which effect was chosen, not what the selector resolved to, and the compile matrix only
        // ever sees the selector as text.
        for (String candidate : candidates(w)) {
            if (entityExists("minecraft:" + candidate)) {
                return "minecraft:" + candidate;
            }
        }
        // NOTHING MATCHED. Returning a best guess here is how "bunnies" becomes minecraft:bunny --
        // a selector that fails to parse, so the ability silently does nothing and the child is
        // told it works. The caller turns this into a real error the agent can act on.
        if (registryAvailable()) {
            return null;
        }
        // Outside a running game there is no registry to ask; the only caller is a template test.
        java.util.List<String> c = candidates(w);
        return "minecraft:" + c.get(c.size() - 1);
    }

    /** Singular forms to try for a child's plural, best first. */
    private static java.util.List<String> candidates(String w) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(w);                                             // already singular: sheep, bat
        if (w.endsWith("men")) {
            out.add(w.substring(0, w.length() - 3) + "man");    // endermen -> enderman
        }
        if (w.endsWith("lves")) {
            out.add(w.substring(0, w.length() - 4) + "lf");     // wolves -> wolf
        }
        if (w.endsWith("ies")) {
            out.add(w.substring(0, w.length() - 3) + "y");      // bunnies -> bunny
        }
        if (w.endsWith("es")) {
            out.add(w.substring(0, w.length() - 2));            // foxes -> fox
        }
        if (w.endsWith("s") && !w.endsWith("ss")) {
            out.add(w.substring(0, w.length() - 1));            // zombies -> zombie, creepers -> creeper
        }
        return out;
    }

    /** Is there a registry to ask at all? Distinguishes "no such creature" from "not in a game". */
    private static boolean registryAvailable() {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .containsKey(net.minecraft.resources.ResourceLocation.parse("minecraft:pig"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is this a real entity type in the running game? False when there is no registry to ask. */
    private static boolean entityExists(String id) {
        try {
            net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.tryParse(id);
            return rl != null
                    && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }


    /**
     * The datapack an effect on a creature is kept as: {@code <effect>_<tag>}. ONE per effect per creature, so a second
     * move of the same effect REPLACES the first — which is why the Builder lets a creature have one of each
     * (wishbuilder/BuilderLimits; BuilderLimitsTest holds the two to each other).
     */
    public static String featureId(String effect, String tag) {
        return effect + "_" + tag.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * A species id → the name the child knows it by, or null. The child-facing sentence
     * names the creature with this; the TAG stays in the selectors, where a command needs it.
     */
    public static String speciesName(String id) {
        com.moddingfromamod.species.Species sp = com.moddingfromamod.species.SpeciesRegistry.get(id);
        return sp == null ? null : sp.name();
    }
}
