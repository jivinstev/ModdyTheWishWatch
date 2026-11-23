package com.moddingfromamod.agent;

import java.util.List;

import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.mob.EntityHandles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * "Here is what is actually around you, and what it currently looks like."
 *
 * <p>Two gaps closed by one report.
 *
 * <p><b>The agent was blind to nearby mobs.</b> {@code look_around} described the biome, the ground
 * and the time of day, and said nothing at all about what was standing there — so the only way to
 * discover the mobs nearby was to make a <i>failing</i> {@code modify_mob} call and read the list
 * out of the error message. Discovery-by-error is a smell, and it meant an agent asked to change
 * something had to guess first.
 *
 * <p><b>Current values were invisible.</b> Writes are absolute — {@code scale: 4.0}, not "×1.5" —
 * which is deliberate: it keeps the judgement of what "bigger" <i>means</i> with the agent rather
 * than baking a multiplier constant into the mod. But that only works if the agent can see where
 * things currently stand, so sizes and health are reported here. Read, decide, write.
 *
 * <p><b>Buildings appear alongside mobs.</b> They live in a separate registry keyed by id, so
 * without this a child saying "make the castle bigger" left the agent looking at a list of animals
 * with no castle in it, forced to remember an id from earlier in the session.
 */
public final class Surroundings {

    /** Far enough to cover anything the child is looking at, near enough to stay relevant. */
    private static final double RADIUS = 32;

    /** Bounded: a "rain chickens" wish must not produce sixty lines of report. */
    private static final int MAX_MOBS = 12;
    private static final int MAX_BUILDINGS = 10;
    /** Same reason as the two above: a child with a drawer full of wands is not a report. */
    private static final int MAX_ITEMS = 10;
    private static final int MAX_PICTURES = 8;
    /** And the rules running in his world. */
    private static final int MAX_RULES = 10;

    /**
     * How many of the animals his wishes have touched to list. Small on purpose: this is a
     * conversation history, and the useful part of it is the last few things he talked about.
     */
    private static final int MAX_TOUCHED = 6;

    /**
     * The marker on the one he is talking about NOW.
     *
     * <p>A constant because it is a contract with the reader, not decoration:
     * {@code MobIdentityGameTest} looks for exactly this to decide what a worker arriving at rung 3
     * can address, and a silent rename would leave that gate passing while telling the agent
     * nothing.
     */
    public static final String TALKING_ABOUT = "TALKING ABOUT:";

    private Surroundings() {
    }

    /**
     * The "things you can refer to" block. Must be called on the server thread.
     *
     * @param origin where to look from — usually the child
     */
    /**
     * §3: {@link #describe(ServerLevel, Vec3)} and, when the wish has one, what was in his view when he WISHED
     * ({@link #inView}), so "turn this into gold" names his andesite without a round trip. Never re-read from the live
     * player: it is the run's wish-time reading.
     */
    public static String describe(ServerLevel level, Vec3 origin,
                                  com.moddingfromamod.wishbuilder.BuilderSuggest.Seen sawWhenWished) {
        String base = describe(level, origin);
        String seen = inView(sawWhenWished);
        return seen.isEmpty() ? base : base + seen;
    }

    /** At most this many blocks in view, and things carried, are listed. */
    static final int MAX_IN_VIEW = 8;

    /**
     * The "In view when he wished" section: the block at his crosshair, the blocks his view held (most first, with how
     * many of the fan's 45 rays hit each), the things he carried. Empty for no reading. Pure.
     */
    public static String inView(com.moddingfromamod.wishbuilder.BuilderSuggest.Seen seen) {
        if (seen == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  IN VIEW WHEN HE WISHED (read the moment he wished, not now. When he says \"this\", \"these\n")
          .append("    blocks\", \"that\" or \"the ground\", he means these — prefer them to a guess. find_content\n")
          .append("    with around: true searches only these):\n");
        if (seen.crosshair() == null && seen.inView().isEmpty()) {
            sb.append("    looking at: nothing (the sky, or too far away).\n");
        } else {
            if (seen.crosshair() != null) {
                sb.append("    at his crosshair: ").append(named(com.moddingfromamod.content.ContentIndex.Kind.BLOCK,
                        seen.crosshair())).append('\n');
            }
            List<String> blocks = new java.util.ArrayList<>();
            for (String id : com.moddingfromamod.wishbuilder.BuilderSuggest.lookingAt(
                    new com.moddingfromamod.wishbuilder.BuilderSuggest.Seen(null, seen.inView(), java.util.Map.of()), x -> true)) {
                blocks.add(named(com.moddingfromamod.content.ContentIndex.Kind.BLOCK, id) + " ×" + seen.inView().get(id));
            }
            if (!blocks.isEmpty()) {
                sb.append("    in view: ").append(String.join(", ", blocks.subList(0, Math.min(MAX_IN_VIEW, blocks.size()))))
                  .append('\n');
            }
        }
        List<String> held = new java.util.ArrayList<>();
        for (String id : com.moddingfromamod.wishbuilder.BuilderSuggest.carrying(seen, x -> true)) {
            held.add(named(com.moddingfromamod.content.ContentIndex.Kind.ITEM, id) + " ×" + seen.carrying().get(id));
        }
        sb.append("    he carries: ").append(held.isEmpty() ? "nothing to change" : String.join(", ", held)).append('\n');
        return sb.toString();
    }

    private static String named(com.moddingfromamod.content.ContentIndex.Kind kind, String id) {
        String n = com.moddingfromamod.content.Found.name(kind, id);
        return n == null ? id : id + " (" + n + ")";
    }

    public static String describe(ServerLevel level, Vec3 origin) {
        StringBuilder sb = new StringBuilder();
        sb.append("Things you can refer to right now:\n");

        // The same list modify_mob searches: no hit proxies, which are invisible machinery
        // and were being listed — and handed out — as mobs.
        List<LivingEntity> nearby = com.moddingfromamod.mob.Creatures.nearby(level, origin, RADIUS);

        if (nearby.isEmpty()) {
            sb.append("  MOBS: none nearby.\n");
        } else {
            sb.append("  MOBS (pass the handle as modify_mob's target — that names one EXACT one):\n");
            int shown = 0;
            for (LivingEntity e : nearby) {
                if (shown++ >= MAX_MOBS) {
                    sb.append("    …and ").append(nearby.size() - MAX_MOBS).append(" more.\n");
                    break;
                }
                String handle = EntityHandles.global().handleFor(e.getUUID());
                // A WISHED CREATURE IS LISTED AS ITSELF. Its carrier's type is a fact about
                // the carrier, and printing "minecraft:llama" beside Spikor taught the agent to
                // address him as a llama — which also reaches every real llama. Its size is its
                // design's: the carrier's scale attribute is a stretch to fit the model.
                var species = com.moddingfromamod.mob.Creatures.speciesOf(e);
                boolean creature = com.moddingfromamod.mob.Creatures.isCreature(e);
                String word = com.moddingfromamod.mob.Creatures.word(e);
                sb.append("    ")
                  .append(handle != null ? handle
                          : creature ? "(no handle — target it by its name: " + word + ")"
                          : "(no handle — target it by type word)")
                  .append("  ").append(creature
                          ? word + " (a creature a wish made)"
                          : EntityType.getKey(e.getType()).toString())
                  .append("  size ×").append(trim(species != null ? species.scale()
                          : e.getAttributeValue(Attributes.SCALE)))
                  .append("  health ").append(Math.round(e.getHealth()))
                  .append('/').append(Math.round(e.getMaxHealth()))
                  // EVERY FIELD modify_mob CAN WRITE, not just the two that were easy.
                  // It takes absolute numbers, so an unreadable field makes its refinement a
                  // GUESS — and "make him faster" answered with a guess that lands under the
                  // current speed makes the mob SLOWER while the wish said faster. That is not an
                  // unanswered wish, it is one answered backwards, and nothing anywhere says so.
                  .append("  speed ").append(trim(e.getAttributeValue(Attributes.MOVEMENT_SPEED)))
                  .append("  damage ").append(damageOf(e))
                  .append("  ").append(Math.round(Math.sqrt(e.distanceToSqr(origin))))
                  .append(" blocks away");
                if (e.isInvulnerable()) {
                    sb.append("  INVULNERABLE");
                }
                // THE LAST OF THE SEVEN. Every other field modify_mob can write was added
                // here already; glow was the one left, and it is the one where an absolute write
                // over an unread value is most visible — "make him a different colour" answered
                // without knowing he is already pink can repaint him pink.
                String glow = com.moddingfromamod.mob.MobStates.glowOf(
                        level.getServer().getScoreboard(), e);
                if (glow != null) {
                    sb.append("  glowing ").append(glow.replace('_', ' '));
                }
                if (e.getCustomName() != null) {
                    sb.append("  \"").append(e.getCustomName().getString()).append('"');
                }
                // …AND WHAT IS ALREADY ON IT. The direct counterpart of inspect_player's whole
                // reason for existing: "make him stronger" cannot see a strength effect the
                // mob already carries, so it reaches for breadth and grants something else. Level
                // is shown the way a child counts (I, II, III => 1, 2, 3) rather than as the
                // internal amplifier, which is one lower and would make every "stronger" off by one.
                String effects = effectsOf(e);
                if (!effects.isEmpty()) {
                    sb.append("\n        already has: ").append(effects);
                }
                sb.append('\n');
            }
            sb.append("    (These are the CURRENT values — modify_mob takes absolute numbers, so\n")
              .append("     read them first and decide what bigger/tougher/faster means. To make\n")
              .append("     one STRONGER, pass a HIGHER number than the one above, not a different\n")
              .append("     power.)\n");
        }

        // ── THE ONES HIS WISHES HAVE TOUCHED — which is not the same list as "nearby" ──────
        //
        //. The block above answers "what is standing here"; it cannot answer "which of these
        // two pandas did the last wish mean", and it cannot mention the fox at all once the fox is
        // dead. Both of those are the same question — WHO IS "HIM" — and both were being answered
        // by a distance sort.
        //
        // MEASURED as three failing gates before this existed. A panda he pointed at finished the
        // ladder at x2 while the one that happened to wander nearer finished at x4; a rung asked
        // about a fox that had died answered "Changed 1 minecraft:fox - size x4" about a different
        // fox, cheerfully; and a second worker could not reach the first worker's llama at all.
        //
        // WHY IT IS HERE rather than in a tool the agent could call: availability is not
        // preservation. That lesson has now been paid for three times in this one method — the item
        // numbers (inspect_item existed and was not enough), the rules (list_datapacks was called
        // TWICE and the script still shrank), and the train livery. A read-back tool fixes nothing
        // if nothing makes the agent suspect it needs to read anything back.
        //
        // AND WHY IT IS WORLD-SIDE. A pool of granters serves one child; a refinement routinely
        // lands on a worker that has never seen the animal, and ArtifactAffinity is only a routing
        // preference that expires to "anyone" after three minutes. Any memory of "who I meant" kept
        // on the worker that did it is wrong by construction. This list is read from the world, so
        // every worker sees the same one.
        List<EntityHandles.Handle> touched = EntityHandles.global().targetedRecently(MAX_TOUCHED);
        if (!touched.isEmpty()) {
            sb.append("  MOBS YOUR WISHES HAVE ALREADY CHANGED (this is what \"him\"/\"it\"/\"that\n")
              .append("   one\" means — the top one is the one he is talking about unless his words\n")
              .append("   clearly say otherwise. Pass the HANDLE as modify_mob's target: a handle\n")
              .append("   names one exact animal even after it has wandered off, and a type word\n")
              .append("   only ever means whichever one is nearest right now):\n");
            var server = level.getServer();
            for (EntityHandles.Handle h : touched) {
                LivingEntity live = com.moddingfromamod.mob.EntityCapture.findAnywhere(server, h.id());
                sb.append("    ")
                  .append(h == touched.get(0) ? TALKING_ABOUT + " " : "")
                  // The LIVE creature's kind when it is one: a handle minted before this
                  // recorded a creature's carrier type ("minecraft:llama" beside Spikor).
                  .append(h.name()).append("  ").append(live != null
                          && com.moddingfromamod.mob.Creatures.isCreature(live)
                          ? com.moddingfromamod.mob.Creatures.word(live) + " (a creature a wish made)"
                          : h.typeId());
                if (!h.label().isBlank()) {
                    sb.append("  \"").append(h.label()).append('"');
                }
                if (live == null) {
                    // "He's gone" is a CORRECT answer and the only honest one here. Leaving it off
                    // the list would send the next wish back to the nearest mob of the same kind,
                    // which is the failure this whole section exists to remove — and the child
                    // would be told his dead pet had been made bigger.
                    sb.append("  — GONE. It has died, or it is somewhere nobody is. Do NOT quietly\n")
                      .append("        change a different one: say so, and ask him what he wants\n")
                      .append("        instead.");
                } else {
                    var sp = com.moddingfromamod.mob.Creatures.speciesOf(live);
                    sb.append("  size ×").append(trim(sp != null ? sp.scale()
                              : live.getAttributeValue(Attributes.SCALE)))
                      .append("  health ").append(Math.round(live.getHealth()))
                      .append('/').append(Math.round(live.getMaxHealth()))
                      .append("  ").append(Math.round(Math.sqrt(live.distanceToSqr(origin))))
                      .append(" blocks away");
                }
                sb.append('\n');
                // WHAT EARLIER WISHES ALREADY DID TO IT, oldest first. The current value alone
                // cannot answer "even bigger": ×2 is a starting point, and ×2 that was ×1 last
                // time is a STEP, which is what the child's word means. It is also the difference
                // between reading a size and knowing that somebody already answered this question.
                for (EntityHandles.Touch t : h.history()) {
                    sb.append("        was ").append(t.before().describe())
                      .append(", changed to: ").append(t.summary()).append('\n');
                }
            }
        }

        // MOST RECENT FIRST. BuildRegistry.ids() is insertion-ordered, so taking the first ten
        // showed the child's TEN OLDEST buildings and cut the newest — which is precisely the one
        // they are talking about when they say "make the castle bigger". The truncation was always
        // honest ("...and N more"), but it was truncating from the wrong end, so the agent could
        // not reference the thing it had just built. Surfaced by the stress corpus filling the
        // registry past ten.
        List<String> buildings = new java.util.ArrayList<>(BuildRegistry.ids());
        java.util.Collections.reverse(buildings);
        if (buildings.isEmpty()) {
            sb.append("  BUILDINGS: none saved yet.\n");
        } else {
            // THE INSTRUCTION IS THE FIX HERE, not the list under it. This used to read "to change
            // one, call build_structure with the SAME id — it rebuilds in place where it already
            // stands", which tells the agent to change a building and gives it nothing to change
            // it FROM: the only way to obey was to invent all 22 shapes again from the child's
            // words. Measured across six ladder cells, what the agent did instead was build a
            // SECOND building — 15 companion structures in three arm-B cells. Fifth
            // mechanism in this codebase caught instructing the agent wrongly and being obeyed.
            // and the one EXCEPTION said in the same breath, or "never build a second
            // structure beside it" overrides "build a stable next to my house" — the same wrong
            // instruction obeyed, the other way round. Anything put IN / ON / BY one is an `at`.
            sb.append("  BUILDINGS (to change one: inspect_build to see its shapes, then\n")
              .append("   build_structure with the same id + start_from that id + ONLY the\n")
              .append("   shapes that change. Never build a second structure beside it —\n")
              .append("   UNLESS he asks for a NEW one by it, \"a stable next to my house\": then a\n")
              .append("   new id with at:\"<id>:beside\". Anything put in / on / by / over one\n")
              .append("   takes at:\"<id>:<spot>\" — top, inside, front, beside, behind, around):\n");
            int shown = 0;
            for (String id : buildings) {
                if (shown++ >= MAX_BUILDINGS) {
                    sb.append("    …and ").append(buildings.size() - MAX_BUILDINGS).append(" more.\n");
                    break;
                }
                BuildPlan plan = BuildRegistry.get(id);
                if (plan == null) {
                    continue;
                }
                sb.append("    ").append(id).append("  \"").append(plan.name()).append("\"  ")
                  .append(plan.shapes().size()).append(" shapes, ")
                  .append(plan.volume()).append(" blocks");
                Long anchor = BuildRegistry.anchorOf(id);
                if (anchor != null && BuildRegistry.standing(id)) {   // not one taken down
                    BlockPos at = BlockPos.of(anchor);
                    sb.append("  standing at ").append(at.getX()).append(',')
                      .append(at.getY()).append(',').append(at.getZ());
                }
                sb.append('\n');
            }
        }

        // ── ITEMS: what he is HOLDING, and the numbers inside it ────────────────────────────
        //
        // MEASURED, 2026-08-18. This section did not exist, so a worker was told what mobs and
        // buildings were around and nothing whatever about the thing in the child's hand. On the
        // item-effects ladder that is the whole failure: asked "can it set them on fire too", a
        // worker that had not made the wand did not call inspect_item at all, restated every
        // parameter from memory, and put the freeze back to 5 seconds after an earlier wish had
        // set it to 30. Three cells out of three, identically. The child keeps a wand that looks
        // exactly right and has quietly lost the thing he asked for.
        //
        // Same shape as the bug fixed in items-R2a, where ArtifactIndex enumerated species,
        // builds, contraptions and trains and not items — recurring in a different consumer,
        // which is a reason to look for the third one rather than to feel finished.
        //
        // THE NUMBERS ARE THE POINT. A list of names would have changed nothing here: the agent
        // knew the wand existed and knew what it did. What it could not know was that the freeze
        // was 30, and the instruction under the list is what turns knowing into keeping.
        List<com.moddingfromamod.item.ItemSpecStore.Spec> items =
                new java.util.ArrayList<>(com.moddingfromamod.item.ItemSpecStore.all());
        java.util.Collections.reverse(items);          // newest first, as for buildings
        if (items.isEmpty()) {
            sb.append("  ITEMS: none made yet.\n");
        } else {
            sb.append("  ITEMS he is holding (to change one: make_item with start_from set to\n")
              .append("   its id and ONLY the fields that change. The numbers below are the\n")
              .append("   CURRENT ones — send a parameter to CHANGE it, and leave it out to KEEP\n")
              .append("   it. Sending one again from memory is how a wish he already made gets\n")
              .append("   undone, and he cannot see that by looking at the item):\n");
            int shown = 0;
            for (com.moddingfromamod.item.ItemSpecStore.Spec spec : items) {
                if (shown++ >= MAX_ITEMS) {
                    sb.append("    …and ").append(items.size() - MAX_ITEMS).append(" more.\n");
                    break;
                }
                sb.append("    ").append(spec.id()).append("  \"").append(spec.name()).append("\"  ")
                  .append(spec.colour()).append(' ').append(spec.icon())
                  .append(", ").append(spec.shape())
                  .append(", x").append(spec.count())
                  .append(", does: ").append(String.join(" + ", spec.effects()));
                String numbers = numbersOf(spec);
                if (!numbers.isEmpty()) {
                    sb.append("\n      set to: ").append(numbers);
                }
                sb.append('\n');
            }
        }
        // ── PICTURES ALREADY PAINTED ──────────────────────────────────────────────
        //
        // The same lesson as the item numbers above: a worker that did
        // not paint it cannot know it is there, and "put Spikor on the OTHER side" is a sentence a
        // child says on his second wish. Without this the only honest answer is to paint a second
        // one somewhere arbitrary — which is exactly.
        //
        // The KEY is included on purpose, not just the position: repainting an existing panel and
        // making a new one beside it look identical from a list of coordinates.
        List<String> paintings = new java.util.ArrayList<>(
                com.moddingfromamod.paint.PaintRegistry.ids());
        java.util.Collections.reverse(paintings);      // newest first, as for the others
        if (paintings.isEmpty()) {
            sb.append("  PICTURES: none painted yet.\n");
        } else {
            sb.append("  PICTURES already painted (to CHANGE one, call paint_surface with the SAME\n")
              .append("   x/y/z and facing — that repaints it in place. A new x/y/z paints a\n")
              .append("   second picture somewhere else, which is rarely what he meant):\n");
            int shownPics = 0;
            for (String id : paintings) {
                if (shownPics++ >= MAX_PICTURES) {
                    sb.append("    …and ").append(paintings.size() - MAX_PICTURES)
                      .append(" more.\n");
                    break;
                }
                com.moddingfromamod.paint.PaintRecord rec =
                        com.moddingfromamod.paint.PaintRegistry.get(id);
                if (rec == null) {
                    continue;
                }
                sb.append("    ").append(id).append("  at ").append(rec.x()).append(",")
                  .append(rec.y()).append(",").append(rec.z()).append("\n");
            }
        }

        // ── EVENTS: what a staged event is doing, or has left behind ─────────────────────────
        //
        // MEASURED 2026-09-29 (E7, the events smoke). "Put everything back the way it was"
        // arrived with a gigantic sinkhole still open, and this list named his builds, rules and
        // trains and not the sinkhole — so the agent filled a few holes by hand with run_command,
        // left the pit, and said so. remove_event would have put back every block. The RULES note
        // below says why the fix is a line here rather than a tool: ask first whether it was ever
        // told the thing existed.
        List<com.moddingfromamod.event.StagedEvent> events = new java.util.ArrayList<>();
        for (com.moddingfromamod.event.StagedEvent e
                : com.moddingfromamod.event.EventDirector.all(level.getServer())) {
            if (e.dimension().equals(level.dimension())) {
                events.add(e);
            }
        }
        if (events.isEmpty()) {
            sb.append("  EVENTS: none happening or left behind.\n");
        } else {
            sb.append("  EVENTS happening or left behind (to put one back — a crater filled, a\n")
              .append("   flood drained, what it rained taken away — remove_event with its id.\n")
              .append("   That gives back EVERY block it changed; filling holes by hand does not):\n");
            int shown = 0;
            for (com.moddingfromamod.event.StagedEvent e : events) {
                if (shown++ >= MAX_RULES) {
                    sb.append("    …and ").append(events.size() - MAX_RULES).append(" more.\n");
                    break;
                }
                sb.append("    ").append(e.id()).append("  ")
                  .append(e.phase().name().toLowerCase(java.util.Locale.ROOT))
                  .append(", ").append(e.changed()).append(" block(s) changed");
                if (e.spawned() > 0) {
                    sb.append(", ").append(e.spawned()).append(" thing(s) it made");
                }
                sb.append('\n');
            }
        }

        // ── RULES: the datapacks already running, and how to change one ────────────────────
        //
        // MEASURED 2026-08-19. This section did not exist either, so a worker was told about mobs,
        // buildings and the thing in his hand, and nothing about the RULES already running in his
        // world. On the datapacks ladder a stranger asked to add one thing re-authored the script
        // from scratch and the program SHRANK — 2461 characters to 1378 losing the night condition,
        // 2433 to 974 losing the explosion — while the owner, who still had the conversation, grew
        // it every rung and kept everything.
        //
        // A READ-BACK TOOL WAS TRIED FIRST AND WAS NOT ENOUGH. `list_datapacks <feature_id>` returns
        // the current script, the agent CALLED it twice, and it still dropped a clause each time.
        // Availability is not preservation: the same lesson `inspect_item` taught, where the item's
        // numbers were already readable on request and what actually fixed it was putting them in
        // front of the agent unasked, with the rule beside them. So this is that, for rules.
        //
        // Third section added to this method for the same reason in two days. The pattern is worth
        // more than any of the three: when an agent gets something wrong about a thing that already
        // exists, ask FIRST whether it was ever told the thing existed.
        // HIS rules first, newest first, then the library's. listIds() is the world's ids sorted by
        // NAME with the library's keys appended, so reversing it put every library pack ahead of
        // his own: once the starter library carried nine, MAX_RULES left room for one of
        // his, and which one depended on the alphabet — the agent was no longer told what was
        // running in his world. Newest is by the compiled zip's mtime, the moment it last changed.
        List<String> features = rulesNewestFirst(
                com.moddingfromamod.datapack.DatapackStore.listIds());
        if (features.isEmpty()) {
            sb.append("  RULES: none made yet.\n");
        } else {
            sb.append("  RULES already running (to change one: list_datapacks with its id to read\n")
              .append("   the script it runs NOW, then author_datapack with the SAME id and the\n")
              .append("   WHOLE program — everything it already does, plus the new part. `cbscript`\n")
              .append("   is not a patch. Anything you leave out stops happening, and he will not\n")
              .append("   find out until the night it does not rain. A NEW id does not replace the\n")
              .append("   old rule, it runs alongside it, and nothing in the game shows him that):\n");
            int shown = 0;
            for (String id : features) {
                if (shown++ >= MAX_RULES) {
                    sb.append("    …and ").append(features.size() - MAX_RULES).append(" more.\n");
                    break;
                }
                sb.append("    ").append(id);
                // WHAT IT ACTUALLY DOES, when it is a standing rule about a kind of mob.
                // An id and a character count say a rule exists and nothing about what is true in
                // his world — so the next wish about zombies could not read that zombies are
                // already twice the size, which is the same "availability is not preservation"
                // failure the item numbers and the train livery above were both added for.
                String saysWhat = com.moddingfromamod.datapack.StandingRules.describe(id);
                if (!saysWhat.isEmpty()) {
                    sb.append("  — ").append(saysWhat);
                }
                // ITS SIZE, because the failure is a script getting SMALLER. A number the agent can
                // compare against what it is about to send is worth more than a reassurance.
                try {
                    java.nio.file.Path src =
                            com.moddingfromamod.datapack.DatapackStore.sourceFor(id);
                    if (src != null && java.nio.file.Files.isRegularFile(src)) {
                        sb.append("  (").append(java.nio.file.Files.size(src))
                          .append(" characters of script)");
                    }
                } catch (java.io.IOException ignored) {
                    // a size we cannot read is not worth failing a wish over
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * An item's effect parameters, flattened for reading: {@code freeze SECONDS=30, ignite
     * SECONDS=6}.
     *
     * <p>Deliberately not the raw JSON. The agent has to compare these against what the child just
     * asked for, and {@code {"freeze":{"SECONDS":"30"}}} is a shape to parse where
     * {@code freeze SECONDS=30} is a fact to read.
     */
    static String numbersOf(com.moddingfromamod.item.ItemSpecStore.Spec spec) {
        if (spec == null || spec.params() == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String effect : spec.effects()) {
            if (!spec.params().has(effect) || !spec.params().get(effect).isJsonObject()) {
                continue;
            }
            var one = spec.params().getAsJsonObject(effect);
            for (String key : one.keySet()) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(effect).append(' ').append(key).append('=')
                   .append(one.get(key).getAsString());
            }
        }
        return out.toString();
    }

    /**
     * Attack damage, or {@code "—"} for a mob that has no such attribute.
     *
     * <p>Not every {@code LivingEntity} has {@link Attributes#ATTACK_DAMAGE} — a cow, an armour
     * stand and a bat all lack it — and {@code getAttributeValue} on an absent attribute throws.
     * Printing a dash is the honest answer: the field exists on {@code modify_mob} and writing it
     * to this mob would do nothing, which is worth knowing before spending a call on it.
     */
    private static String damageOf(LivingEntity e) {
        var attr = e.getAttribute(Attributes.ATTACK_DAMAGE);
        return attr == null ? "—" : trim(attr.getValue());
    }

    /**
     * The potion effects a mob is already carrying, as {@code "strength 3 (forever), speed 2"}.
     *
     * <p>Empty when it has none, so the common case adds no noise to a list that can run to
     * {@link #MAX_MOBS} entries.
     */
    private static String effectsOf(LivingEntity e) {
        StringBuilder out = new StringBuilder();
        for (var eff : e.getActiveEffects()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
                            .getKey(eff.getEffect().value()))
               .append(' ').append(eff.getAmplifier() + 1)
               .append(eff.isInfiniteDuration() ? " (forever)"
                       : " (" + (eff.getDuration() / 20) + "s left)");
        }
        return out.toString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** His rules newest first, then the library's — see {@link com.moddingfromamod.datapack.RuleOrder}. */
    private static List<String> rulesNewestFirst(List<String> ids) {
        java.nio.file.Path base = com.moddingfromamod.datapack.DatapackStore.baseDir();
        return com.moddingfromamod.datapack.RuleOrder.order(ids,
                id -> java.nio.file.Files.isRegularFile(base.resolve(id).resolve(id + ".zip")),
                id -> {
                    try {
                        return java.nio.file.Files.getLastModifiedTime(
                                base.resolve(id).resolve(id + ".zip")).toMillis();
                    } catch (java.io.IOException e) {
                        return 0L;
                    }
                });
    }
}
