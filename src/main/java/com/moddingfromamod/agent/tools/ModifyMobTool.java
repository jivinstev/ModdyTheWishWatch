package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.mob.ContentSearch;
import com.moddingfromamod.mob.GlowColour;
import com.moddingfromamod.mob.MobTweak;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Change a mob that is <b>already in the world</b> — any mob, from any mod.
 *
 * <p>This is the tool that makes "summon the real thing" a complete strategy rather than a rule the
 * agent has to abandon the moment a child asks a follow-up question. Before it existed, an agent
 * that had correctly summoned a genuine {@code witherstormmod:wither_storm} had exactly one answer
 * to "make it bigger and make it purple!": delete the real boss and rebuild an approximation out of
 * {@code wish_mob} parts — trading the thing the child recognised for a vanilla lookalike.
 *
 * <p>Both headline knobs work on <b>any</b> entity, because both ride vanilla mechanisms rather
 * than the mob's own art:
 * <ul>
 *   <li><b>size</b> — the vanilla {@code SCALE} attribute, which resizes model <i>and</i> hitbox;</li>
 *   <li><b>colour</b> — a glow outline, drawn in the colour of the entity's scoreboard team. Not a
 *       texture tint (a modded texture lives in that mod's jar and we cannot repaint it), but it
 *       does make a mob unmistakably "the pink one" with no art and no restart.</li>
 * </ul>
 *
 * <p>Everything is clamped in the pure {@link MobTweak} before it reaches the game, and players are
 * never a valid target.
 */
public final class ModifyMobTool implements McpTool {

    /**
     * The handle of the mob this call changed, for the dispatch seam to record as the artifact.
     *
     * <p><b>Per calling thread, not a shared field.</b> Tools are shared instances and three
     * granters run at once, so the {@code volatile} field {@code make_item} uses can be overwritten
     * by another wish between this call returning and the seam reading it — narrow, but what it
     * corrupts is precisely "which artifact was this wish about", which is the number the whole
     * experiment turns on.
     *
     * <p><b>And it must be set on the way OUT, not inside the work.</b> The first cut set it inside
     * the {@code AgentBridge.onServer} body, which runs on the SERVER thread — so the value landed
     * in the server thread's copy and {@code lastMadeId()}, read by the caller, saw null every
     * time. The tool worked perfectly and reported making nothing;
     * {@code aChangedMobIsRecordedAsSomethingTheWishWasAbout} is what caught it.
     */
    private static final ThreadLocal<String> LAST_MADE = new ThreadLocal<>();

    @Override
    public String name() {
        return "modify_mob";
    }

    @Override
    public String lastMadeId() {
        return LAST_MADE.get();
    }

    @Override
    public String description() {
        return "Change a mob that is ALREADY in the world — make it bigger, give it a glowing "
                + "colour, rename it, or make it tougher. Works on ANY mob, including bosses from "
                + "other mods. AND MAKE IT A PET: `follow` (it keeps to him), `friendly` (it never "
                + "attacks a player, even a blaze or a warden), `rideable` (he climbs on by using it and steers where he looks) and "
                + "`finds` ('diamonds', 'carrots', 'treasure': it shows him where they are). So 'a "
                + "<mob> pet that follows me' is summon + ONE modify_mob with follow:true. A mob "
                + "you only SUMMON does none of this: no vanilla mob follows him, and a dolphin, a "
                + "cow or a ravager cannot be ridden until modify_mob says rideable. Do NOT write a "
                + "datapack or an apply_effect ability for a pet. THIS is how you answer 'make it bigger!' or 'make it pink!' after "
                + "summoning a real mob: adjust the real one, never replace it with an invented "
                + "lookalike. Say which mob with 'target' (a word like 'wither_storm', or leave it "
                + "out for the nearest one). Note that colour here is a GLOW OUTLINE, not a repaint "
                + "— if the child specifically needs a differently-COLOURED body, that is the one "
                + "case for define_creature instead. You must say `scope`: one animal, the group "
                + "standing there, or the whole kind forever (which goes to author_datapack, not "
                + "here).";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // REQUIRED, and is the reason. Category and group produced byte-identical behaviour
        // and an individual differed only by a count, so nothing decided which he meant. An
        // optional field is not an input to generation — this repo measured a `run_command` list
        // parameter used ZERO times in 752 calls — so the choice is made unskippable and lands in
        // the action log where an arm can be audited.
        Schemas.prop(s, "scope", "string",
                "REQUIRED. How many animals he means.\n"
                + "  'one'      — one animal: \"make HIM bigger\", \"make THAT cow huge\". "
                + "The commonest by far.\n"
                + "  'group'    — the ones standing there: \"all the zombies NEAR ME\". A fixed "
                + "set, decided now.\n"
                + "  'category' — every one of that kind, FOREVER, including ones that do not "
                + "exist yet: \"make zombies bigger\". This tool cannot do that — see below.\n"
                + "WHEN IT COULD HONESTLY BE EITHER, CHOOSE THE NARROW ONE AND ASK HIM. Getting it "
                + "wrong is not symmetric: reading a category wish as one animal just means he asks "
                + "again, but reading \"make him invincible\" about his pet as a category makes "
                + "EVERY zombie invincible forever and nothing in the game shows him why.");
        Schemas.prop(s, "target", "string",
                "Which mob. BEST: a handle you were given when you created it, like '@wither_storm', "
                        + "or '@last' for the most recent — those name one EXACT entity. A creature "
                        + "a wish made answers to its OWN name or id ('turtle_king', or its tag "
                        + "'wishmob_turtle_king') — never to the vanilla mob it rides on. Otherwise a "
                        + "type word like 'zombie', which picks the nearest match and is a guess. "
                        + "Leave out for the nearest mob of any kind. '@event:<id>' (stage_event gives "
                        + "it) is EVERY creature that event brought, wherever each has gone.");
        Schemas.prop(s, "radius", "number",
                "How far around the child to look, 1 to " + (int) MobTweak.MAX_RADIUS
                        + " blocks (default " + (int) MobTweak.DEFAULT_RADIUS + ").");
        Schemas.prop(s, "count", "integer",
                "How many matching mobs to change, 1 to " + MobTweak.MAX_COUNT + " (default 1).");
        Schemas.prop(s, "scale", "number",
                "Size multiplier, " + MobTweak.MIN_SCALE + " (tiny) to " + MobTweak.MAX_SCALE
                        + " (enormous). Resizes the body AND the hitbox.");
        Schemas.prop(s, "glow", "string",
                "Make it glow in a colour — a name (" + String.join(", ", GlowColour.names())
                        + "), or any colour like 'pink' or '#FF69B4' which is snapped to the "
                        + "closest one. Use 'none' to switch the glow off.");
        Schemas.prop(s, "name", "string", "Name shown above the mob, e.g. 'Steve's Storm'.");
        Schemas.prop(s, "health", "number",
                "Max health, 1 to " + (int) MobTweak.MAX_HEALTH + ". Raising it also heals the mob.");
        Schemas.prop(s, "damage", "number", "Attack damage, 0 to " + (int) MobTweak.MAX_DAMAGE + ": what its body "
                + "would have, like a vanilla mob its size. A friendly creature never attacks whatever this says, so "
                + "never 0 to make it gentle; 0 lands nothing.");
        Schemas.prop(s, "armor", "number", "Armour points, 0 to " + (int) MobTweak.MAX_ARMOR
                + " (a full iron suit is 15).");
        Schemas.prop(s, "knockback_resistance", "number", "0 to 1: how hard it is to push (1 = never pushed).");
        Schemas.prop(s, "speed", "number",
                "Move speed, " + MobTweak.MIN_SPEED + " to " + MobTweak.MAX_SPEED + ".");
        Schemas.prop(s, "invulnerable", "boolean", "True = nothing can hurt it.");
        Schemas.prop(s, "persistent", "boolean", "True = it will never despawn.");
        Schemas.prop(s, "follow", "boolean",
                "True = it follows him around like a pet (and hops to him if he gets far away). "
                        + "False = it stops. Only when he asked for it to follow him or to be his "
                        + "pet; to make it harmless use 'friendly' instead.");
        Schemas.prop(s, "friendly", "boolean",
                "True = it never attacks a player, whatever it is — a warden, a blaze, a hoglin. "
                        + "False = it can again.");
        Schemas.prop(s, "rideable", "boolean",
                "True = he can ride it: using it with a free hand (or holding his watch) climbs on, it goes where he looks while "
                        + "he holds forward, sneak gets off. Fliers and swimmers climb and dive. Never on a BOSS "
                        + "(ender dragon, wither, warden, elder guardian): its own AI takes it away from him — "
                        + "sculpt a creature with the rideable power instead.");
        Schemas.prop(s, "finds", "string",
                "Something it finds for him nearby: 'diamonds', 'gold', 'iron', 'emeralds', "
                        + "'carrots', 'treasure' (chests), 'netherite', a block id, or a block tag "
                        + "like '#minecraft:logs'. It walks to the nearest one, sparkles over it and "
                        + "glows. 'none' stops it.");
        Schemas.prop(s, "stay", "number",
                "Keep it AT the spot it is standing on now, for good — 'a boss on his throne', 'a guard "
                        + "dog by the door', 'a knight at the gate', 'a cow in each pen'. The "
                        + "number is how far it may potter (0 = right on the spot, 3 = round its stall; "
                        + "up to " + com.moddingfromamod.mob.PetBehaviour.MAX_STAY + "); true = 0. Pushed or "
                        + "lured away it walks back, even after the world reloads. -1 or false lets it "
                        + "wander again. Not with follow: a pet cannot follow him AND stay. A creature "
                        + "that stays SITS DOWN while it stays (\"sit, puppy!\" is stay: 0), and "
                        + "lies down after a long sit.");
        // the SAME temperament, powers and moves a sculpted creature takes, with the same
        // words — on a real mob (a vanilla enderman, a modded mob) they are run by the code a
        // sculpted creature's are; on a sculpted creature they are saved to its design.
        Schemas.prop(s, "base", "string", SculptCreatureTool.INSTINCTS
                + " Works the same on a REAL mob (an army of real endermen that fights for him is "
                + "base 'guardian' + friendly); on a real mob 'wild' leaves it its own nature.");
        Schemas.prop(s, "powers", "array",
                "Added to what it has, with sculpt_creature's meanings. On a REAL mob: leaps, "
                        + "skittish, friendly, rideable, fireproof, blastproof, poisonproof, "
                        + "witherproof, lightningproof, frostproof, fallproof. A sculpted creature's "
                        + "BODY powers (flies, hops, naps, boss, explodes) need a sculpted creature — "
                        + "a real mob keeps its own body.");
        Schemas.prop(s, "abilities", "array", SculptCreatureTool.ABILITIES
                + " Added to what it has; a real mob's summon calls two more of its own kind.");
        Schemas.prop(s, com.moddingfromamod.species.BehavesLike.ARG, "string",
                com.moddingfromamod.species.BehavesLike.DESCRIBE + " A sculpted creature only: a real mob "
                        + "already lives its own mob's life.");
        Schemas.required(s, "scope");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        ToolResult r = change(args);
        // What a learned button must replay: the teach draft folds this into the summon
        // it changed, as `wishsummon <mob> {spec}`. Only a change that took is recorded.
        // ONE animal only: a change to a group or a category is not about the mob a summon made.
        if (!r.isError() && args != null && "one".equalsIgnoreCase(str(args, "scope"))) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            if (run != null) {
                JsonObject spec = new JsonObject();
                args.entrySet().forEach(e -> {
                    if (!com.moddingfromamod.wish.ModifyMobFold.NOT_SPEC.contains(e.getKey())) {
                        spec.add(e.getKey(), e.getValue());
                    }
                });
                run.modifiedMob(args.has("target") && args.get("target").isJsonPrimitive()
                        ? args.get("target").getAsString() : "", spec);
            }
        }
        return r;
    }

    private ToolResult change(JsonObject args) {
        // Cleared up front: a call that errors must not leave the PREVIOUS call's handle lying
        // around for the seam to record as this wish's artifact.
        LAST_MADE.remove();
        // behaves_like is checked against the game's mobs before anything reads it.
        String behavesRefused = SculptCreatureTool.resolveBehavesLike(args);
        if (!behavesRefused.isEmpty()) {
            return ToolResult.error("Nothing was changed: " + behavesRefused);
        }
        MobTweak tweak = MobTweak.fromJson(args);
        // a word that means nothing is said back, never dropped — "fights" is not a base.
        List<String> unknown = com.moddingfromamod.mob.BehaviourProfile.unknownWords(args);
        if (!unknown.isEmpty()) {
            return ToolResult.error("Not a known " + String.join(", ", unknown) + ". base is one of: wild, "
                    + "guardian, hunter, coward, companion; abilities: " + SculptCreatureTool.ABILITIES);
        }
        if (tweak.isEmpty()) {
            return ToolResult.error("Nothing to change. Pass at least one of: scale, glow, name, "
                    + "health, damage, speed, invulnerable, persistent, follow, friendly, rideable, "
                    + "finds, stay, base, powers, abilities, behaves_like.");
        }
        if (Boolean.TRUE.equals(tweak.pet().follow()) && tweak.pet().asksToStay()) {
            // the two are opposites. Say so rather than pick one silently.
            return ToolResult.error("follow and stay are opposites: a pet that follows him cannot "
                    + "also stay on its spot. Which did he ask for — to keep it with him (follow), or "
                    + "to keep it where it is, like a dog in its kennel (stay)?");
        }
        String finds = tweak.pet().finds();
        if (finds != null && !com.moddingfromamod.mob.PetBehaviours.knows(finds)) {
            return ToolResult.error("Nothing in this game is called '" + finds + "', so it would "
                    + "look forever and find nothing. Try 'diamonds', 'gold', 'iron', 'emeralds', "
                    + "'carrots', 'treasure', a block id like 'minecraft:chest', or a block tag like "
                    + "'#minecraft:logs'.");
        }
        ToolResult refusal = checkScope(args, tweak);
        if (refusal != null) {
            return refusal;
        }
        final com.moddingfromamod.mob.MobScope.Scope scope =
                com.moddingfromamod.mob.MobScope.Scope.of(str(args, "scope"));
        // Written on the server thread, read back here once onServer() has joined — which is what
        // gives it a happens-before and keeps it out of the other granters' way.
        final String[] made = {null};
        // what persistToSpecies wrote to a creature's design, recorded on the way out.
        final List<com.moddingfromamod.agent.CreatureEdits.Edit> edits =
                java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            // SETTLE FIRST, OFF THE SERVER THREAD -- the same reason look_around does. `origin`
            // below is the child's live body, and a capture session outlives the photograph call
            // that opened it, so without this "the nearest mob" can be measured from a photography
            // seat: a wrong creature edited on the strength of a distance sort taken somewhere the
            // child is not standing. Costs nothing when no session is open, which is nearly always.
            java.util.UUID settleWho = AgentBridge.onServer(() -> {
                ServerPlayer p = com.moddingfromamod.agent.ActingChild.person(
                        AgentBridge.serverOrNull());
                return p == null ? null : p.getUUID();
            });
            if (settleWho != null) {
                com.moddingfromamod.wish.WishCapture.awaitSettled(settleWho);
            }
            // HE HAS LEFT: "the nearest" is measured from the world spawn (below), which 1.21.9+ no
            // longer keeps loaded. The spawn is held while the change is found and made, and for the
            // agent's next call; with him here his own chunk is loaded and nothing is held.
            ToolResult out = AgentBridge.onServer(() -> com.moddingfromamod.place.SpawnHold.during(
                    com.moddingfromamod.agent.ActingChild.person(AgentBridge.serverOrNull()) == null,
                    AgentBridge.serverOrNull().overworld(),
                    AgentBridge.serverOrNull().overworld().getRespawnData().pos(),
                    com.moddingfromamod.place.SpawnHoldPlan.Followup.AGENT,
                    () -> com.moddingfromamod.agent.CreatureEdits.collecting(edits, () -> {
                var server = AgentBridge.serverOrNull();
                // The child whose wish this is — NOT whoever joined first. The old form was
                // getPlayers().stream().findFirst(), which with one child is always right and with
                // two is unrelated to who asked; it also cannot tell a child from a client the mod
                // launched for itself, which is what makes a camera unshippable.
                // PERSON, not world (decision 1): "the nearest mob" is measured from HIM,
                // so if he has left it is measured from spawn, never from another child.
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.person(server);
                // Mirror RunCommandTool: with nobody online, work at world spawn rather than
                // refusing, so the agent isn't sent round a pointless retry loop.
                ServerLevel level = player != null ? player.level() : server.overworld();
                Vec3 origin = player != null
                        ? player.position()
                        : Vec3.atBottomCenterOf(level.getRespawnData().pos());

                // an EVENT's handle names every creature it brought, wherever each has gone
                // — a staged crowd of teleporters scatters, and "everything within 32 blocks" found
                // 8 of 12. Resolved by the tag the event put on each one as it spawned.
                String eventId = eventHandle(tweak.targetQuery());
                if (eventId != null) {
                    String tag = com.moddingfromamod.event.EventDirector.entityTag(eventId);
                    List<LivingEntity> brought = new ArrayList<>();
                    for (ServerLevel l : server.getAllLevels()) {
                        l.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                                x -> x.isAlive() && x.getTags().contains(tag), brought);
                    }
                    if (brought.isEmpty()) {
                        return ToolResult.error("Nothing the event " + eventId + " brought is still about"
                                + " — it was undone, or they died or unloaded.");
                    }
                    List<String> notes = new ArrayList<>();
                    int changed = 0;
                    for (LivingEntity found : brought) {
                        if (changed >= com.moddingfromamod.mob.MobTweak.MAX_COUNT) {
                            break;
                        }
                        String boss = bossCannotBeAPet(tweak, found);
                        if (boss != null) {
                            return ToolResult.error(boss);
                        }
                        apply(server.getScoreboard(), found, tweak, notes);
                        changed++;
                    }
                    return ToolResult.ok("Changed " + changed + " of the " + brought.size() + " creatures the event "
                            + eventId + " brought (" + EVENT_HANDLE + eventId + ") — " + tweak.summary() + "."
                            + String.join(" ", new LinkedHashSet<>(notes)));
                }

                // A handle names ONE entity, so resolve it directly and skip the distance sort
                // entirely. This is the difference between "make it bigger" meaning the boss the
                // child is looking at and meaning whichever cow wandered closest.
                boolean deliberateNearest = NEAREST.equals(tweak.targetQuery());
                // …but a SELECTOR is not a handle: the creature tools hand the agent
                // `@e[tag=wishmob_spikor]`, and that is answered by the matcher below.
                if (!deliberateNearest
                        && com.moddingfromamod.mob.EntityHandles.looksLikeHandle(
                                tweak.targetQuery())
                        && !com.moddingfromamod.mob.CreatureMatch.isSelector(
                                tweak.targetQuery())) {
                    var handle = com.moddingfromamod.mob.EntityHandles.global()
                            .resolve(tweak.targetQuery());
                    if (handle.isEmpty()) {
                        return ToolResult.error("I don't know the handle \"" + tweak.targetQuery()
                                + "\". Known: " + knownHandles()
                                + ". Handles are given out when something is created.");
                    }
                    LivingEntity found = com.moddingfromamod.mob.EntityCapture.find(
                            level, handle.get().id());
                    if (found == null) {
                        return ToolResult.error("The " + handle.get().label() + " ("
                                + tweak.targetQuery() + ") is gone — it died or unloaded.");
                    }
                    List<String> notes = new ArrayList<>();
                    com.moddingfromamod.mob.MobState before =
                            com.moddingfromamod.mob.MobStates.read(server.getScoreboard(), found);
                    String backwards = wouldGoBackwards(tweak, before);
                    if (backwards != null) {
                        return ToolResult.error(backwards);
                    }
                    String boss = bossCannotBeAPet(tweak, found);
                    if (boss != null) {
                        return ToolResult.error(boss);
                    }
                    apply(server.getScoreboard(), found, tweak, notes);
                    made[0] = markAsWhatHeIsTalkingAbout(found, tweak, before);
                    return ToolResult.ok("Changed " + handle.get().label() + " ("
                            + tweak.targetQuery() + ") — " + tweak.summary() + "."
                            + String.join(" ", new LinkedHashSet<>(notes)));
                }

                // ── NEAREST ESTABLISHES A REFERENT; IT NEVER RE-ESTABLISHES ONE ────────────────
                //
                // names "nearest match" as the silent pick the invariant forbids, and
                // modify_mob's own schema calls it "a guess". But it cannot simply be deleted: the
                // FIRST wish about an animal nobody summoned — "make that cow huge" — has no handle
                // to use, and walking to the nearest cow is both the only possible answer and the
                // right one. The invariant is about the Nth wish.
                //
                // So it is legal to establish a referent and refused to re-establish one. Once this
                // wish's conversation has a cow in it, a bare "cow" means THAT cow.
                if (referentWins && scope == com.moddingfromamod.mob.MobScope.Scope.ONE
                        && !deliberateNearest) {
                    ToolResult settled = useTheOneHeIsTalkingAbout(server, tweak, made);
                    if (settled != null) {
                        return settled;
                    }
                }

                String query = deliberateNearest ? "" : tweak.targetQuery();
                // ONE ANSWER TO "WHICH ONE": never a rig's hit proxy, and a wished
                // creature by its OWN names — species, tag, the name above its head — never by its
                // carrier's vanilla type, so "llama" means his real llamas and "spikor" means
                // Spikor. The rules are CreatureMatch's, and they are unit-tested.
                List<LivingEntity> candidates = com.moddingfromamod.mob.Creatures.nearby(
                        level, origin, tweak.radius());
                // on a world that fences a wish to its caster's base, the search keeps to his base — the
                // reach rule let this call run because he stands on it (BaseReach.KEEPS_TO_BASE), not because the
                // whole radius fits inside it, so a creature past its edge is never changed.
                var fence = fenceOfThisWish();
                if (fence != null) {
                    candidates.removeIf(e -> !fence.contains(e.getX(), e.getZ()));
                }

                List<LivingEntity> matching = new ArrayList<>();
                for (LivingEntity e : candidates) {
                    if (com.moddingfromamod.mob.Creatures.matches(e, query)) {
                        matching.add(e);
                    }
                }
                // HIS OWN FIRST, for "the one": "the dragon" and "my dragon" both mean the
                // one spawned for him when he has one here, else the nearest. Not for a group, and
                // not when the agent deliberately asked for the nearest.
                if (scope == com.moddingfromamod.mob.MobScope.Scope.ONE && !deliberateNearest) {
                    matching = com.moddingfromamod.mob.Creatures.hisFirst(matching,
                            com.moddingfromamod.agent.AgentContext.currentPlayerId());
                }
                int wanted = scope == com.moddingfromamod.mob.MobScope.Scope.ONE
                        ? 1 : tweak.count();
                List<LivingEntity> targets = new ArrayList<>(
                        matching.subList(0, Math.min(wanted, matching.size())));
                if (targets.isEmpty()) {
                    return ToolResult.error(noMatchMessage(tweak, candidates));
                }

                Set<String> changed = new LinkedHashSet<>();
                List<String> notes = new ArrayList<>();
                List<String> handles = new ArrayList<>();
                for (LivingEntity e : targets) {
                    com.moddingfromamod.mob.MobState before =
                            com.moddingfromamod.mob.MobStates.read(server.getScoreboard(), e);
                    String backwards = wouldGoBackwards(tweak, before);
                    if (backwards != null) {
                        return ToolResult.error(backwards);
                    }
                    String boss = bossCannotBeAPet(tweak, e);
                    if (boss != null) {
                        return ToolResult.error(boss);
                    }
                    apply(server.getScoreboard(), e, tweak, notes);
                    changed.add(com.moddingfromamod.mob.Creatures.word(e));
                    String handle = markAsWhatHeIsTalkingAbout(e, tweak, before);
                    handles.add(handle);
                    // The FIRST one, when several were changed at once. A wish is about one subject
                    // as far as routing and continuity are concerned, and reporting the last of
                    // twenty chickens would make every rung of a group ladder look like a
                    // different artifact.
                    if (made[0] == null) {
                        made[0] = handle;
                    }
                }
                StringBuilder sb = new StringBuilder("Changed ").append(targets.size()).append(' ')
                        .append(String.join(", ", changed))
                        .append(targets.size() == 1 ? "" : "s")
                        .append(" — ").append(tweak.summary()).append('.');
                for (String note : new LinkedHashSet<>(notes)) {
                    sb.append(' ').append(note);
                }
                // SAY WHICH ONE, so the next wish is not a second guess. A type word picked one of
                // possibly several; now it has a name, and the reply is where the agent learns it.
                if (!handles.isEmpty() && !handles.get(0).isBlank()) {
                    sb.append(handles.size() == 1
                            ? " From now on you can say " + handles.get(0)
                              + " to mean THAT exact one — \"" + tweak.targetQuery()
                              + "\" only ever means whichever is nearest."
                            : " They are now " + String.join(", ", handles)
                              + " — use those to mean these exact ones.");
                }
                return ToolResult.ok(sb.toString());
            })), 10_000L);
            if (made[0] != null && !made[0].isBlank()) {
                LAST_MADE.set(made[0]);
            }
            if (!out.isError()) {
                com.moddingfromamod.agent.CreatureEdits.record(edits);
            }
            return out;
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }



    /**
     * <b>Refuse a change that goes the wrong way.</b> an earlier issue's actual defect.
     *
     * <p>{@code modify_mob} takes ABSOLUTE numbers, so "make him faster" means inventing one — and
     * a low guess makes the mob <i>slower</i> while the wish said faster. The issue's words: the
     * wish "is not merely unanswered; it is answered backwards, and nothing in the world or the log
     * says so." Reading fixed half of it — {@code Surroundings} now reports every field this tool
     * can write — but an agent can read 0.25 and still write 0.2, and nothing would notice.
     *
     * <p>The error carries the current number, so the retry is one step rather than another guess.
     * That is the whole reason this refuses instead of quietly clamping: a clamp would answer the
     * wish with "no change" and tell nobody.
     *
     * @return the refusal, or null to go ahead
     */
    /**
     * <b>A boss is never a pet or a mount</b> (2026-10-11). "A baby ender dragon I can ride" was
     * granted by summoning a real ender dragon and making it rideable, follow and friendly; its own
     * boss AI flew it 92 blocks away before he could get on, and the button it was saved to would
     * have done that every time. A boss's AI is its own (phases, a fight, a charge) and no pet
     * behaviour reaches it, so asking for follow or rideable on one is refused and the agent is
     * sent to the creature that works: a sculpted one, with {@code flies} if it flies.
     */
    static String bossCannotBeAPet(MobTweak tweak, LivingEntity e) {
        return bossCannotBeAPet(tweak,
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
    }

    /** The same, by the type's id — {@code wishsummon} asks before it summons anything. */
    public static String bossCannotBeAPet(MobTweak tweak, String id) {
        boolean asks = Boolean.TRUE.equals(tweak.pet().rideable()) || Boolean.TRUE.equals(tweak.pet().follow());
        if (!asks || !com.moddingfromamod.mob.RideSeat.BOSSES.contains(id)) {
            return null;
        }
        return "A " + id.replace('_', ' ') + " is a BOSS: its own boss AI decides where it goes, so it "
                + "cannot follow him or be ridden, whatever you set — it flies or wanders off. For a "
                + "creature like this that he rides or keeps as a pet, SCULPT one (sculpt_creature) with "
                + "powers [\"friendly\", \"rideable\"], plus \"flies\" if it flies (then it flies with him), "
                + "and take this one away (kill it) so it does not roam his world.";
    }

    private static String wouldGoBackwards(MobTweak tweak,
                                           com.moddingfromamod.mob.MobState before) {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        String said = run == null ? "" : run.text();
        var way = com.moddingfromamod.mob.MobDirection.of(said);
        if (way == com.moddingfromamod.mob.MobDirection.Way.NONE) {
            return null;
        }
        String[][] fields = {
            {"size", "scale"}, {"health", "health"}, {"attack damage", "damage"}, {"speed", "speed"},
        };
        Double[] currents = {before.scale(), before.maxHealth(), before.damage(), before.speed()};
        Double[] wants = {tweak.scale(), tweak.health(), tweak.damage(), tweak.speed()};
        for (int i = 0; i < fields.length; i++) {
            if (com.moddingfromamod.mob.MobDirection.backwards(way, currents[i], wants[i])) {
                boolean more = way == com.moddingfromamod.mob.MobDirection.Way.MORE;
                return "He asked to make it " + (more ? "MORE" : "LESS") + " (\"" + said.strip()
                        + "\"), and " + fields[i][0] + " " + wants[i] + " is "
                        + (more ? "LOWER" : "HIGHER") + " than it already is — it is at "
                        + currents[i] + " right now.\n"
                        + "That would answer his wish backwards, and nothing in the game would tell "
                        + "him. Send a " + (more ? "HIGHER" : "LOWER") + " `" + fields[i][1]
                        + "` than " + currents[i] + ".\n"
                        + "look_around lists the current numbers for every one of these — read them "
                        + "first, because this tool takes absolutes, not \"a bit more\".";
            }
        }
        return null;
    }

    /** Deliberately whichever is nearest — the escape hatch from the referent, see below. */
    private static final String NEAREST = "@nearest";

    /**
     * <b>The A/B seam for the referent, and the reason it is a mutable static.</b>
     *
     * <p>Off restores nearest-wins — the exact behaviour was filed about. A gate that has never
     * been seen to fail proves nothing it claims, and this is what lets one client run measure both
     * arms in one boot rather than costing two fifteen-minute runs on a machine that fits two
     * clients in total.
     *
     * <p>Settable rather than read-once so a test can flip it between arms;
     * {@code -Ptestprops="mobreferent=off"} sets the initial value for a whole run. Never touched in
     * play — nothing but a test calls the setter, and the default is on.
     */
    private static volatile boolean referentWins =
            !"off".equalsIgnoreCase(System.getProperty(
                    com.moddingfromamod.ModdingFromAModMod.MODID + ".mobreferent", "on"));

    /** Test seam only. Returns the previous value so an arm can put it back. */
    public static boolean referentWins(boolean on) {
        boolean was = referentWins;
        referentWins = on;
        return was;
    }

    /**
     * <b>A bare type word, on a conversation that already has one of those in it.</b>
     *
     * <p>Rung 2 said "make that cow huge" and the nearest cow was the right answer. By rung 3 he has
     * walked twenty blocks and another cow is nearer, so the same query resolves to a different
     * animal and reports success — the failure is built on. Once a referent exists, it wins.
     *
     * <p>Three outcomes, and the second two are refusals on purpose:
     * <ul>
     *   <li>one established cow, alive → change it, and say which;</li>
     *   <li>one established cow, dead → <i>"he's gone"</i>, which is the correct answer;</li>
     *   <li>two established cows → ask, because picking one of them would be the guess again.</li>
     * </ul>
     *
     * <p>{@code @nearest} is the way out, and it is deliberately a token he cannot type by accident:
     * a child who genuinely means a DIFFERENT cow is talking about one that has no name yet, so
     * there has to be a way to say "no, the one in front of him" — and it has to be explicit, so it
     * shows up in the log rather than happening by default.
     *
     * @return the finished result, or null to fall through to the distance sort
     */
    private static ToolResult useTheOneHeIsTalkingAbout(net.minecraft.server.MinecraftServer server,
                                                        MobTweak tweak, String[] made) {
        var handles = com.moddingfromamod.mob.EntityHandles.global();
        List<com.moddingfromamod.mob.EntityHandles.Handle> established = new ArrayList<>();
        for (var h : handles.targetedRecently(com.moddingfromamod.mob.EntityHandles.MAX_HANDLES)) {
            // Asked of the LIVE animal when there is one: a handle minted before this
            // existed recorded a creature's CARRIER type, and "llama" must not re-establish Spikor.
            // A dead one keeps its recorded kind, which is all there is left to go on.
            var live = com.moddingfromamod.mob.EntityCapture.findAnywhere(server, h.id());
            boolean same = live != null
                    ? com.moddingfromamod.mob.Creatures.matches(live, tweak.targetQuery())
                    : ContentSearch.matches(h.typeId(), tweak.targetQuery());
            if (same) {
                established.add(h);
            }
        }
        if (established.isEmpty()) {
            return null;      // nothing to re-establish; the nearest one is about to become it
        }
        if (established.size() > 1) {
            StringBuilder sb = new StringBuilder("Which one? This wish has already changed ");
            for (var h : established) {
                sb.append(sb.length() > 40 ? ", " : "").append(h.name())
                  .append(" (").append(h.label()).append(')');
            }
            return ToolResult.error(sb + ". Pass one of those handles, or " + NEAREST
                    + " for whichever is closest to him — and if you cannot tell from what he "
                    + "said, ASK him which one he means rather than picking.");
        }
        var h = established.get(0);
        net.minecraft.world.entity.LivingEntity found =
                com.moddingfromamod.mob.EntityCapture.findAnywhere(server, h.id());
        if (found == null) {
            // NOT a fallback to another one of the same kind. "He's gone" is the correct answer,
            // and quietly growing a different animal while reporting success is the sharpest form
            // of the thing this whole mechanism exists to prevent.
            return ToolResult.error("The " + h.label() + " (" + h.name() + ") he has been talking "
                    + "about is GONE — it died, or it is somewhere nobody is. Tell him that; do "
                    + "not change a different " + tweak.targetQuery() + " instead. If he wants a "
                    + "new one, summon it — or pass " + NEAREST + " if he really means whichever "
                    + tweak.targetQuery() + " is standing there now.");
        }
        List<String> notes = new ArrayList<>();
        com.moddingfromamod.mob.MobState before =
                com.moddingfromamod.mob.MobStates.read(server.getScoreboard(), found);
        String backwards = wouldGoBackwards(tweak, before);
        if (backwards != null) {
            return ToolResult.error(backwards);
        }
        String boss = bossCannotBeAPet(tweak, found);
        if (boss != null) {
            return ToolResult.error(boss);
        }
        apply(server.getScoreboard(), found, tweak, notes);
        made[0] = markAsWhatHeIsTalkingAbout(found, tweak, before);
        return ToolResult.ok("Changed " + h.label() + " (" + h.name() + ") — " + tweak.summary()
                + ". " + String.join(" ", new LinkedHashSet<>(notes))
                + " That is the one this conversation has been about, not whichever "
                + tweak.targetQuery() + " happens to be nearest — pass " + NEAREST
                + " if he means a different one.");
    }

    /**
     * <b>The guard 's expensive mistake.</b>
     *
     * <p>The {@code scope} argument makes the model decide explicitly and puts the decision in the
     * action log. This reads the child's <i>own sentence</i> and refuses the one direction that
     * cannot be taken back: an agent claiming a wider change than anything he said supports.
     *
     * <p>Deliberately one-directional. Reading "make zombies bigger" as one zombie under-delivers,
     * he sees something happen and asks again; reading "make him invincible" about his pet as a
     * category rewrites the world permanently. Refusing both would double the questions and buy no
     * safety. And an <b>ambiguous</b> sentence is never refused — that is the ask rung's business,
     * not a dead end.
     *
     * @return an error to return to the agent, or null to proceed
     */
    private static ToolResult checkScope(JsonObject args, MobTweak tweak) {
        String raw = str(args, "scope");
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        // His actual words. Absent on a direct call or a unit test, and MobScope then reads that as
        // ONE-and-ask — narrow, and never a licence to refuse anything.
        String said = run == null ? "" : run.text();
        var words = com.moddingfromamod.mob.MobScope.of(said);
        var scope = com.moddingfromamod.mob.MobScope.Scope.of(raw);

        if (scope == null) {
            return ToolResult.error("Say `scope` — 'one', 'group' or 'category'"
                    + (raw == null || raw.isBlank() ? "" : " (I could not read \"" + raw + "\")")
                    + ".\nHis words read as '" + words.scope().label() + "' ("
                    + words.why() + ")"
                    + (words.ask()
                        ? ", but they could honestly have been wider — take the narrow reading and "
                          + "ASK him which he meant."
                        : ", so pass that unless you have a reason not to.")
                    + "\nWhy this is required: 'one' and 'category' used to do the same thing, so "
                    + "nothing recorded which he meant.");
        }
        if (!said.startsWith(com.moddingfromamod.wishbuilder.BuilderMake.TEXT_PREFIX)   // the Builder's own call
                && com.moddingfromamod.mob.MobScope.overreaches(scope, words)) {
            return ToolResult.error("I will not do that as '" + scope.label()
                    + "'. He said \"" + said.strip() + "\", which means "
                    + words.scope().label() + " (" + words.why() + ").\n"
                    + "'" + scope.label() + "' would change every one of them from now on, "
                    + "including ones that do not exist yet — he never asked for that, and nothing "
                    + "in the game would show him why every "
                    + (tweak.targetQuery().isBlank() ? "mob" : tweak.targetQuery())
                    + " had changed.\nEither pass scope:'" + words.scope().label()
                    + "', or ASK him whether he means all of them forever.");
        }
        if (scope == com.moddingfromamod.mob.MobScope.Scope.CATEGORY) {
            return ToolResult.error(categoryGoesToADatapack(tweak));
        }
        return null;
    }

    /**
     * Where a whole-species wish actually goes.
     *
     * <p>This tool reaches at most {@link MobTweak#MAX_COUNT} mobs within {@link MobTweak#MAX_RADIUS}
     * blocks that are <b>alive at this moment</b>. Answering "make zombies bigger" here looks
     * right and then decays: those twenty burn at sunrise, by the next evening every zombie is
     * normal again, and the wish is still in his list marked granted. That is worse than a refusal.
     *
     * <p>A standing rule is a datapack — and not a new mechanism, which is the point. A
     * {@code clock tick} is re-evaluated every tick forever, so it covers mobs that do not exist
     * yet with no spawn hook at all; {@code DatapackStore} re-installs it into the world on every
     * load; and {@code remove_datapack} is a real undo. Verified by compiling exactly the rule
     * below.
     */
    private static String categoryGoesToADatapack(MobTweak tweak) {
        String type = tweak.targetQuery().isBlank() ? "zombie" : tweak.targetQuery();
        return "That is a whole-species wish, and this tool cannot keep it. It changes at most "
                + MobTweak.MAX_COUNT + " mobs within " + (int) MobTweak.MAX_RADIUS + " blocks that "
                + "are alive RIGHT NOW — so the ones that spawn tomorrow come out normal, and he is "
                + "never told why the wish stopped working.\n"
                + "Use author_datapack instead. A `clock tick` rule runs forever and catches every "
                + "one that spawns later:\n\n"
                + categoryRuleRecipe(type) + "\n"
                + "GOTCHA: no namespace inside the selector — `type=" + type + "`, never "
                + "`type=minecraft:" + type + "`, which is a syntax error. The compiler writes the "
                + "namespace itself.\n"
                + "THEN CALL verify_datapack. CBScript compiles any command text at all, so a wrong "
                + "id (it is " + CATEGORY_RULE_ATTRIBUTE + " on this version) is "
                + "dropped SILENTLY at pack load: the pack reports enabled and the rule does "
                + "nothing. Compiling proves the syntax and nothing else.\n"
                + "That is a RULE datapack, so omit reuse_action.\n"
                + "If he only meant the ones standing here, call me again with scope:'group'.";
    }

    /**
     * The CBScript a whole-species wish should be written as.
     *
     * <p>Its own method so a gate can check it rather than take it on trust — advice the agent is
     * given is code the agent will paste. <b>Two things went wrong in it, and only one of them was
     * caught by compiling.</b>
     *
     * <ul>
     *   <li>CBScript cannot parse a namespace inside a selector: {@code type=minecraft:zombie} is a
     *       syntax error and {@code type=zombie} compiles, after which the compiler writes the
     *       namespace itself. That one failed loudly, at compile time.</li>
     *   <li>The attribute was written {@code minecraft:scale}, which is 1.21.2's name for it. In
     *       1.21.1 it is {@code minecraft:generic.scale}, and the difference is invisible to
     *       CBScript — <b>it compiles any command text at all</b>. Minecraft rejected the function
     *       at pack load, dropped it, and the rule sat there enabled and doing nothing. The
     *       "it compiles" gate was green the whole time; only running it in a world found it.</li>
     * </ul>
     *
     * <p>Hence {@link #CATEGORY_RULE_ATTRIBUTE}, checked against the live registry by a GameTest:
     * compiling proves the syntax, and only the registry proves the id.
     */
    public static String categoryRuleRecipe(String type) {
        String t = type == null || type.isBlank() ? "zombie" : type.trim();
        return "clock tick\n"
                + "    as @e[type=" + t + ", tag=!my_rule] at @s\n"
                + "        /tag @s add my_rule\n"
                + "        /attribute @s " + CATEGORY_RULE_ATTRIBUTE + " base set 2.0\n"
                + "    end\n"
                + "end\n";
    }

    /**
     * The attribute the recipe above names.
     *
     * <p>A constant so a gate can look it up in {@code BuiltInRegistries.ATTRIBUTE} instead of
     * trusting it. 1.21.1 still uses the {@code generic.} prefix; 1.21.2 flattens it, so this is
     * exactly the kind of id that goes stale on a version bump with no compile error anywhere.
     */
    public static final String CATEGORY_RULE_ATTRIBUTE = com.moddingfromamod.compat.Attrs.generic("scale");

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : null;
    }

    /**
     * <b>Acting on a mob is what makes it addressable</b> — the durable half.
     *
     * <p>Most of the animals a child points at were never created by a wish, so nothing ever gave
     * them a name: <i>"make that cow huge"</i> is resolved by walking to the nearest cow, and by the
     * time he says <i>"make him even huger"</i> he has moved and a different cow is nearest. Rung 3
     * then grows the wrong animal and reports success. Stamping here is what leaves a mark the next
     * worker can read out of the world — see {@code Surroundings.TALKING_ABOUT} — and it has to be
     * world-side rather than remembered by this worker, because the next rung is routinely granted
     * by a different one.
     *
     * @return the handle it can be addressed by from now on
     */
    private static String markAsWhatHeIsTalkingAbout(LivingEntity e, MobTweak tweak,
                                                     com.moddingfromamod.mob.MobState before) {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        String handle = com.moddingfromamod.mob.EntityHandles.global().targeted(
                e.getUUID(), com.moddingfromamod.mob.Creatures.kindId(e),
                com.moddingfromamod.mob.Creatures.label(e),
                run == null ? null : run.wishId().toString(), "modify_mob",
                tweak.summary(), before);
        return handle;
    }

    /**
     * <b>Which of our creatures is this — on EITHER renderer.</b>
     *
     * <p>{@link #persistToSpecies} used to ask {@code e instanceof WishMobEntity} and nothing else.
     * That was complete for exactly as long as a wish creature could only BE a
     * {@code WishMobEntity}, and stopped being complete the day made the vanilla display rig
     * the default: {@code VanillaRigBridge.onJoin} <b>cancels</b> that join and dresses a vanilla
     * base instead. So on the shipping path the test returned false every time, the species record
     * was never edited, and <i>"make it bigger"</i> — the most common second sentence a
     * seven-year-old says — silently did nothing that outlived the entity standing there.
     *
     * <p>The rig carrier says which species it is wearing through the tag {@code DisplayRig} wrote
     * on it, which is the same handle an earlier issue's {@code CaptureSubject} uses for the camera. Same root
     * event, same handle, different tool.
     *
     * <p><b>What this does to the creature already standing there</b> is not decided here and needs
     * no new code: {@code VanillaRigAnimator.rebuildIfRewished} already watches
     * {@code SpeciesRegistry.revision()} and re-manifests a rig whose record changed, carrying its
     * live health, effects and name across. A rig cannot be re-scaled in place — its geometry is
     * baked into a resource pack and into the transform of every display — so rebuild is the answer
     * the rig path had already chosen for a re-sculpt, and a species edit arrives at it the same
     * way. The legacy {@code WishMobEntity} keeps its own route (it compares the revision on tick
     * and calls {@code applySpecies}), so both arms still work: that is back-compat, not dead code
     *.
     */
    private static com.moddingfromamod.species.Species speciesOf(LivingEntity e) {
        if (e instanceof com.moddingfromamod.entity.WishMobEntity wish) {
            return wish.species();
        }
        return com.moddingfromamod.vanilla.DisplayRig.speciesOn(e);
    }

    /** What {@link #persistToSpecies} did with a change. */
    private enum Persisted {
        /** Not a design change, or not one of our creatures: apply it to this one mob. */
        NO,
        /** Written to the creature's design: every one of its kind changes. */
        SHARED,
        /** Written to HIS copy of it, and the one he meant moved onto it; the rest are unchanged. */
        FORKED,
        /** Somebody else's design with nobody to make a copy for: not written anywhere. */
        REFUSED
    }

    /**
     * Persist a change to a WISH CREATURE by editing its species, not just the entity.
     *
     * <p>This is what makes "make it bigger" stick. Setting the attribute alone changes the one
     * creature standing there; the next one the child asks for comes back the original size,
     * because the saved definition never learned anything. Editing the species updates every
     * creature of that kind already in the world AND every one summoned afterwards.
     *
     * <p>Only possible for our own creatures. A modded boss has no species we own, so those stay
     * per-entity — and the agent is told so rather than being left to assume it stuck.
     *
     * @return what became of the change
     */
    /** The solid volume of the body a creature draws, or NaN when it draws none (#101's power line). */
    private static double drawnVolume(com.moddingfromamod.species.Species sp) {
        var g = com.moddingfromamod.runtime.ModelRepository.geometry(sp.modelKey());
        return g == null ? Double.NaN : g.volume();
    }

    private static Persisted persistToSpecies(LivingEntity e, MobTweak tweak) {
        com.moddingfromamod.species.Species current = speciesOf(e);
        if (current == null) {
            return Persisted.NO;
        }
        Boolean glowing = tweak.glow() == null ? null : tweak.glowsInColour();
        // The same ceiling the sculpt path has -- this is the OTHER way "make it bigger"
        // arrives, and a limit on one of two doors is not a limit.
        Double scale = tweak.scale();
        if (scale != null) {
            float units = com.moddingfromamod.runtime.Framing.modelHeightUnits(
                    com.moddingfromamod.runtime.ModelRepository.geometry(current.modelKey()));
            scale = com.moddingfromamod.runtime.CreatureGrowth
                    .decide(units, scale, current.scale()).scale();
        }
        com.moddingfromamod.species.Species next = current.with(
                scale, tweak.health(), tweak.damage(), tweak.speed(),
                tweak.name(), glowing)
                .withDefence(tweak.armor(), tweak.knockbackResistance())
                // Riding and harmlessness ARE the design (2026-10-11): a rideable T-Rex his button
                // brought back could not be ridden, because this was the one animal's alone.
                .withDesignedBehaviour(tweak.pet().rideable(), tweak.pet().friendly())
                // temperament, powers and moves are the design too — the same words a real
                // mob takes (RealMobBehaviours), here written where a sculpted creature keeps them.
                .withBehaviour(tweak.behaviour().base(), tweak.behaviour().powers(),
                        tweak.behaviour().abilities());
        // how it LIVES is the design too.
        if (tweak.behaviour().behaves() != null) {
            next = next.withBehaves(tweak.behaviour().behaves());
        } else {
            // "make it fly" with no life named lifts its life off the ground, as a new sculpt's would be.
            java.util.Set<String> words = new java.util.HashSet<>();
            next.powers().forEach(pw -> words.add(pw.name().toLowerCase(java.util.Locale.ROOT)));
            var was = next.archetype();
            String to = com.moddingfromamod.species.MagicianBehaviour.afterPowers(was == null ? null : was.key(),
                    current.has(com.moddingfromamod.species.Species.Power.FLIES), words,
                    next.base() == null ? null : next.base().name(), next.scale(), false);
            var life = to == null ? null : com.moddingfromamod.species.Archetype.byKey(to);
            if (life != null) {
                next = next.withBehaves(life);
            }
        }
        if (next.equals(current)) {
            // Nothing about the DESIGN changed — "stop following me", "you can't be hurt" are
            // about this one animal. Writing it anyway minted a fork of a library
            // creature for a pet behaviour, and told him it was "saved to the creature's design".
            return Persisted.NO;
        }
        var server = AgentBridge.serverOrNull();
        if (server == null) {
            return Persisted.NO;
        }
        // ── COPY-ON-REFINE ─────────────────────────────────────────────────────────────────
        //
        // "Give my Spikor bigger flames" aimed at the CURATED Spikor, or at his brother's, must
        // not write back over it. The `put` below would not corrupt the library on disk — nothing
        // writes there — it would write a species under the SAME id into this world, and
        // `ModelRepository.readable` prefers the world's copy, so the curated creature would be
        // quietly replaced for every child on the server with nothing in the world looking wrong.
        //
        // The decision is pure ({@code ForkPolicy}); this only carries out what it says, and for
        // EDIT_IN_PLACE / EDIT_SHARED it does exactly what it did before per-child ownership.
        String asker = com.moddingfromamod.agent.AgentContext.currentPlayerId();
        var decision = com.moddingfromamod.species.CreatureFork.decide(current, asker);
        if (decision == com.moddingfromamod.marshal.ForkPolicy.Decision.FORK) {
            var forked = com.moddingfromamod.species.CreatureFork.fork(server, next, asker);
            if (forked != null) {
                // AND POINT THE CREATURE HE IS LOOKING AT AT IT. Without this the fork exists,
                // is correct and is invisible: the mob in front of him still carries the base's
                // species id, so it goes on showing the unchanged base and the wish reads as
                // having done nothing. Only THIS mob moves — his brother's copies of the same
                // creature are still that creature.
                // On the rig that is its tags, and the animator rebuilds it from
                // the fork on its next tick; the WishMobEntity arm was the only one there was.
                com.moddingfromamod.mob.Creatures.wearSpecies(e, forked.id());
                com.moddingfromamod.net.ModdedClient.broadcastSpecies();
                com.moddingfromamod.agent.CreatureEdits.collect(new com.moddingfromamod.agent
                        .CreatureEdits.Edit(forked.id(), current.id(), current.name(), next.name(),
                        true, false));
                return Persisted.FORKED;
            }
            // No owner to fork for. Falling through would shadow the curated creature, so refuse
            // the PERSIST and let the caller apply the change to this one mob instead.
            return Persisted.REFUSED;
        }
        if (decision == com.moddingfromamod.marshal.ForkPolicy.Decision.REFUSE) {
            return Persisted.REFUSED;
        }
        com.moddingfromamod.species.SpeciesRegistry.put(server, next);
        // Clients need the new definition to draw it, exactly as define_creature does.
        com.moddingfromamod.net.ModdedClient.broadcastSpecies();
        com.moddingfromamod.agent.CreatureEdits.collect(new com.moddingfromamod.agent.CreatureEdits
                .Edit(current.id(), current.id(), current.name(), next.name(), false, false));
        return Persisted.SHARED;
    }

    /**
     * The same change, applied to a mob this tool did not find — {@code wishsummon}'s replay,
     * so a taught button runs THIS code rather than a translation of it. Returns the notes.
     */
    /** a handle for every creature one staged event brought: {@code @event:<event id>}. */
    public static final String EVENT_HANDLE = "@event:";

    /** The event id an {@code @event:} handle names, or null when it is not one. */
    static String eventHandle(String target) {
        if (target == null) {
            return null;
        }
        String t = target.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.startsWith(EVENT_HANDLE) || t.length() == EVENT_HANDLE.length()) {
            return null;
        }
        return t.substring(EVENT_HANDLE.length());
    }

    public static List<String> applyTo(Scoreboard scoreboard, LivingEntity e, MobTweak tweak) {
        return applyTo(scoreboard, e, tweak, null);
    }

    /**
     * As {@link #applyTo(Scoreboard, LivingEntity, MobTweak)}, with whose pet it becomes said outright
     * (a staged crowd's creatures arrive seconds after the tool call, when no child is acting).
     * Null = the acting child, as the tool itself does.
     */
    public static List<String> applyTo(Scoreboard scoreboard, LivingEntity e, MobTweak tweak,
                                       java.util.UUID owner) {
        List<String> notes = new java.util.ArrayList<>();
        apply(scoreboard, e, tweak, notes, owner);
        return notes;
    }

    private static void apply(Scoreboard scoreboard, LivingEntity e, MobTweak tweak, List<String> notes) {
        apply(scoreboard, e, tweak, notes, null);
    }

    /** Apply the tweak to one mob. Anything the mob can't support is reported, never fatal. */
    private static void apply(Scoreboard scoreboard, LivingEntity e, MobTweak tweak,
                              List<String> notes, java.util.UUID ownerOverride) {
        // PET BEHAVIOUR FIRST, and on the entity whichever route the rest takes: a wish creature
        // saves its numbers to its species below and returns early, but "follow me" is about THIS
        // animal, not every one of its kind.
        if (!tweak.pet().isEmpty()) {
            var server = e.level() instanceof ServerLevel sl ? sl.getServer() : null;
            ServerPlayer owner = ownerOverride != null || server == null ? null
                    : com.moddingfromamod.agent.ActingChild.person(server);
            notes.addAll(com.moddingfromamod.mob.PetBehaviours.apply(e, tweak.pet(),
                    ownerOverride != null ? ownerOverride : owner == null ? null : owner.getUUID()));
        }
        // Try the persistent route first. A wish creature picks the new numbers up on its next
        // tick via the species revision, so we must not also set them by hand — the species is the
        // single source of truth for those creatures.
        Persisted persisted = persistToSpecies(e, tweak);
        if (persisted == Persisted.SHARED || persisted == Persisted.FORKED) {
            if (tweak.name() != null) {
                e.setCustomName(Component.literal(tweak.name()));
                e.setCustomNameVisible(true);
            }
            if (tweak.invulnerable() != null) {
                e.setInvulnerable(tweak.invulnerable());
            }
            if (tweak.glow() != null) {
                applyGlow(scoreboard, e, tweak);
            }
            notes.add(persisted == Persisted.FORKED
                    // only the one he meant moved onto his copy.
                    ? "(This one is now his own copy of the creature, with the change — it is"
                            + " rebuilt that way in a moment. The others of its kind are unchanged.)"
                    : "(Saved to the creature's design, so every one of these — now and in"
                            + " future — is like this.)");
            // #101: what its numbers now mean, and a guard that cannot hit, caught before he sees it
            var nowSpecies = speciesOf(e);
            if (nowSpecies != null && (tweak.health() != null || tweak.damage() != null || tweak.armor() != null
                    || tweak.knockbackResistance() != null || !tweak.behaviour().isEmpty())) {
                double vol = drawnVolume(nowSpecies);
                notes.add(com.moddingfromamod.species.PowerLine.of(nowSpecies, vol));
                String zeroHit = com.moddingfromamod.species.PowerLine.zeroHitFlag(nowSpecies, vol);
                if (!zeroHit.isEmpty()) {
                    notes.add(zeroHit);
                }
                String farAbove = com.moddingfromamod.species.PowerLine.farAbove(nowSpecies, vol);
                if (!farAbove.isEmpty()) {
                    notes.add(farAbove);
                }
            }
            return;
        }
        boolean creature = speciesOf(e) != null;
        if (!tweak.behaviour().isEmpty() && !creature) {
            // a REAL mob's temperament, powers and moves, run by the code a sculpted
            // creature's are (RealMobBehaviours).
            notes.addAll(com.moddingfromamod.mob.RealMobBehaviours.apply(e, tweak.behaviour()));
        } else if (!tweak.behaviour().isEmpty() && persisted == Persisted.REFUSED) {
            notes.add("(couldn't give this creature a new temperament, powers or moves: it belongs to the"
                    + " library, and no child's wish is asking, so there is nobody to make a copy for.)");
        }
        if (tweak.scale() != null && creature) {
            // A creature's size is its RIG — the carrier's SCALE attribute moves only its hitbox,
            // an invisible change the tool used to report as done.
            notes.add("(couldn't resize this creature: "
                    + (persisted == Persisted.REFUSED
                            ? "it belongs to the library, and no child's wish is asking, so there"
                                    + " is nobody to make a copy for."
                            : "its design could not be changed.")
                    + " Nothing about its size changed.)");
        } else if (tweak.scale() != null) {
            if (set(e, Attributes.SCALE, tweak.scale())) {
                // Without this the hitbox keeps the old size until something else refreshes it,
                // so a giant mob would be punchable only where its small self used to be.
                e.refreshDimensions();
            } else {
                notes.add("(couldn't resize a " + typeId(e) + ".)");
            }
        }
        if (tweak.health() != null) {
            double target = tweak.health();
            float before = e.getHealth();
            if (set(e, Attributes.MAX_HEALTH, target)) {
                // Raising the ceiling should feel like "made it tougher", so heal into the new
                // headroom; lowering it must not leave health above the maximum.
                e.setHealth(target > before ? (float) target : Math.min(before, (float) target));
            } else {
                notes.add("(couldn't change a " + typeId(e) + "'s health.)");
            }
        }
        if (tweak.damage() != null && !set(e, Attributes.ATTACK_DAMAGE, tweak.damage())) {
            // Perfectly normal: a chicken has no attack-damage attribute at all.
            notes.add("(a " + typeId(e) + " has no attack damage to set.)");
        }
        if (tweak.armor() != null && !set(e, Attributes.ARMOR, tweak.armor())) {
            notes.add("(couldn't change a " + typeId(e) + "'s armour.)");
        }
        if (tweak.knockbackResistance() != null && !set(e, Attributes.KNOCKBACK_RESISTANCE, tweak.knockbackResistance())) {
            notes.add("(couldn't change how hard a " + typeId(e) + " is to push.)");
        }
        if (tweak.speed() != null && !set(e, Attributes.MOVEMENT_SPEED, tweak.speed())) {
            notes.add("(couldn't change a " + typeId(e) + "'s speed.)");
        }
        if (tweak.name() != null) {
            e.setCustomName(Component.literal(tweak.name()));
            e.setCustomNameVisible(true);
        }
        if (tweak.invulnerable() != null) {
            e.setInvulnerable(tweak.invulnerable());
        }
        if (tweak.persistent() != null && tweak.persistent() && e instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        if (tweak.glow() != null) {
            applyGlow(scoreboard, e, tweak);
        }
        // #174 C1: a REAL mob's numbers were set by hand above, and nothing fits a body with no species to this world's
        // limits by itself, so a wished wolf stood at attack 100 in a world capped at 5. Hold it to them now (its
        // wisher's limits, or with nobody acting the tightest any player here is held to), and say so.
        if (!creature && (tweak.health() != null || tweak.damage() != null || tweak.armor() != null
                || tweak.knockbackResistance() != null || tweak.speed() != null)) {
            java.util.UUID wisher = ownerOverride;
            if (wisher == null && e.level().getServer() != null) {
                ServerPlayer acting = com.moddingfromamod.agent.ActingChild.person(e.level().getServer());
                wisher = acting == null ? null : acting.getUUID();
            }
            List<String> held = com.moddingfromamod.wish.LiveFit.hold(e, wisher);
            if (!held.isEmpty()) {
                notes.add("(held to this world's limits: " + String.join(", ", held)
                        + ". It stands at the limit here, and at its own numbers in a world that allows them.)");
            }
        }
        // #101 (smoke): a REAL mob whose numbers or instinct were shaped is told its power too, read from the live
        // entity (its attributes and its box), with the same flags a sculpted creature's result carries
        if (!creature && (tweak.health() != null || tweak.damage() != null || tweak.armor() != null
                || tweak.knockbackResistance() != null || tweak.scale() != null || !tweak.behaviour().isEmpty())) {
            notes.addAll(realMobPower(e, tweak));
        }
    }

    /** The power line of a real mob as it now stands, and the zero-hit flag and the far-above advisory where they apply. */
    static List<String> realMobPower(LivingEntity e, MobTweak tweak) {
        List<String> out = new ArrayList<>();
        double health = e.getMaxHealth();
        double damage = e.getAttribute(Attributes.ATTACK_DAMAGE) == null ? 0 : e.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double armor = e.getAttribute(Attributes.ARMOR) == null ? 0 : e.getAttributeValue(Attributes.ARMOR);
        double knockback = e.getAttribute(Attributes.KNOCKBACK_RESISTANCE) == null ? 0
                : e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        boolean boss = com.moddingfromamod.mob.RideSeat.BOSSES.contains(typeId(e))
                || tweak.behaviour().powers().contains(com.moddingfromamod.species.Species.Power.BOSS);
        var band = com.moddingfromamod.species.PowerBand.forVolume(
                com.moddingfromamod.species.PowerLine.boxVolume(e.getBbWidth(), e.getBbHeight()));
        out.add(com.moddingfromamod.species.PowerLine.of(health, damage, armor, knockback, band, boss, false));
        var base = tweak.behaviour().base();
        if (damage <= 0 && com.moddingfromamod.vanilla.RigInstinct.forRealMob(base, tweak.behaviour().powers()).fights()) {
            out.add(com.moddingfromamod.species.PowerLine.zeroHitFlag("base '"
                    + base.name().toLowerCase(java.util.Locale.ROOT) + "', on a " + typeId(e).replace('_', ' ')
                    + " with no attack of its own", band));
        }
        String far = com.moddingfromamod.species.PowerLine.farAbove(health, damage, armor, band, boss);
        if (!far.isEmpty()) {
            out.add(far);
        }
        return out;
    }

    /**
     * Colour the glow outline via the entity's scoreboard team.
     *
     * <p>Vanilla draws a glowing entity's outline in its team colour and offers no other hook, so a
     * team per colour is the whole mechanism. Teams are created on demand and reused, which keeps
     * the scoreboard to at most the 16 team colours no matter how many mobs get painted.
     */
    private static void applyGlow(Scoreboard scoreboard, LivingEntity e, MobTweak tweak) {
        String member = e.getScoreboardName();
        if (!tweak.glowsInColour()) {
            e.setGlowingTag(false);
            PlayerTeam current = scoreboard.getPlayersTeam(member);
            if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
                scoreboard.removePlayerFromTeam(member, current);
            }
            return;
        }
        String teamName = TEAM_PREFIX + tweak.glow();
        PlayerTeam team = scoreboard.getPlayersTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
            ChatFormatting colour = ChatFormatting.getByName(tweak.glow());
            if (colour != null) {
                team.setColor(colour);
            }
        }
        scoreboard.addPlayerToTeam(member, team);
        e.setGlowingTag(true);
    }

    private static final String TEAM_PREFIX = "wish_glow_";

    /** Set an attribute's base value. False when this mob simply doesn't have that attribute. */
    private static boolean set(LivingEntity e, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = e.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.setBaseValue(value);
        return true;
    }

    /** Handles the agent could be using, for a helpful error rather than a bare "unknown". */
    private static String knownHandles() {
        var recent = com.moddingfromamod.mob.EntityHandles.global().recent(10);
        if (recent.isEmpty()) {
            return "(none yet)";
        }
        StringBuilder sb = new StringBuilder();
        for (var h : recent) {
            sb.append(sb.length() == 0 ? "" : ", ").append(h.name());
        }
        return sb.toString();
    }

    /** where this wish's search may act (his base's area), or null when nothing fences it. */
    private static com.moddingfromamod.world.Region fenceOfThisWish() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? null : com.moddingfromamod.world.WorldModuleHost.fenceFor(run.playerId());
    }

    private static String typeId(LivingEntity e) {
        return EntityType.getKey(e.getType()).toString();
    }

    /**
     * A miss is the agent's most likely mistake, so say what IS nearby. Returning a bare "not
     * found" invites a retry loop with the same wrong word.
     */
    private static String noMatchMessage(MobTweak tweak, List<LivingEntity> nearby) {
        if (nearby.isEmpty()) {
            return "No mobs within " + (int) tweak.radius() + " blocks of the child. Summon one "
                    + "first with run_command, then modify it.";
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (LivingEntity e : nearby) {
            // A creature by ITS word: "Nearby: minecraft:llama" taught the agent to reach
            // Spikor through his carrier, which also reaches every real llama.
            kinds.add(com.moddingfromamod.mob.Creatures.word(e));
            if (kinds.size() >= 12) {
                break;
            }
        }
        return "No mob matching \"" + tweak.targetQuery() + "\" within " + (int) tweak.radius()
                + " blocks. Nearby right now: " + String.join(", ", kinds)
                + ". Try one of those words, or a bigger radius.";
    }
}
