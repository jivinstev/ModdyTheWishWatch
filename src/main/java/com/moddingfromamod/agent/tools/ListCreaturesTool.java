package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;

/**
 * The creatures that already exist, so "another dragon" is a lookup rather than a redesign.
 *
 * <p>The same gap that made every McTrain cost a fresh design job: the species were saved and the
 * agent had no way to see them. Without this list, "make me another one of my dragons" reads as a
 * blank sheet, and the child gets <i>a</i> dragon rather than <b>his</b> dragon — which is worse
 * than waiting, because it looks like being ignored.
 */
public final class ListCreaturesTool implements McpTool {

    /**
     * <b>The lessons come FIRST, before the listing — because they must arrive before the sculpt.</b>
     *
     * <p>They were originally attached to {@code sculpt_creature}'s RESULT, which meant that on a
     * first creature the agent read "Spikor is mostly tan, green only on the shell" <i>after it had
     * already built a green Spikor</i>. A live A/B proved it: with the lessons wired that way the
     * agent still produced a 74%-green Spikor, no better than the worst recorded revision.
     *
     * <p>That is the gate ladder's own level-1-versus-level-2 distinction, and I got it wrong one
     * commit after writing the ladder down. A lesson is only an INPUT to generation if the agent
     * reads it before it generates; anywhere later it is feedback, and feedback cannot un-build a
     * creature the child is already looking at.
     */
    /** The lessons, unless this wish has already been handed them (by its plan). */
    private static String lessonsFirst() {
        if (!com.moddingfromamod.wish.BuildGate.current().firstLessons("creature")) {
            return "";
        }
        String lessons = com.moddingfromamod.wish.WishLessons.text("creature");
        return lessons.isEmpty() ? ""
                : "BEFORE YOU SCULPT — rules that cost real wishes:\n\n" + lessons
                        + "\n\n──────────────────────────────────────────────────────\n\n";
    }

    @Override
    public String name() {
        return "list_creatures";
    }

    @Override
    public String description() {
        return "List the creatures already designed and saved in this world. CHECK THIS FIRST when "
                + "the child asks for a creature — especially \"another\" one, or one by name. If it "
                + "already exists, run 'wishmob spawn <id>' with run_command and he gets THAT exact "
                + "creature back instantly, instead of a new one that merely fits the words. Use "
                + "modify_mob to change one; the edit is saved into the design, so every one of that "
                + "kind changes, now and in future.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        var all = SpeciesRegistry.all();
        // The library's ready-made BODIES. A shipped species is read from the library on
        // demand and enters SpeciesRegistry only once somebody spawns it, so until then this list —
        // the one the agent is told to check first — never named it, and "a wolf with wings" was
        // sculpted from nothing beside a wolf body that sculpt_creature start_from could inherit.
        java.util.Set<String> designed = new java.util.HashSet<>();
        for (Species s : all) {
            designed.add(s.id().toLowerCase(java.util.Locale.ROOT));
        }
        String bodies = libraryBodies(com.moddingfromamod.library.WishLibrary.keys("species"), designed);
        if (all.isEmpty()) {
            return ToolResult.ok(lessonsFirst() + "No creatures have been designed in this world yet — this one is "
                    + "new, so make it, then teach it with add_template so the next one is instant."
                    + bodies);
        }
        // WHICH ONE IS HE TALKING ABOUT —.
        //
        // This list opens with every creature he made in previous sessions, and said nothing about
        // which was touched ninety seconds ago. A child refining something says "him", so the agent
        // had three candidates and no reason to prefer the right one; measured, it repeatedly
        // sculpted the wrong creature and the intended one came out of five wishes UNCHANGED —
        // scoring a perfect 1.0 continuity for having been ignored.
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        // what YOUR plan_wish said this wish is about; before you have planned, only the
        // FACT of what he touched last — never "he means this one".
        String current = run == null ? null
                : run.aboutArtifact();
        String last = run == null ? null : com.moddingfromamod.wish.WishTopic.lastOf(run.playerId());
        StringBuilder sb = new StringBuilder("Creatures already designed in this world:\n");
        for (Species s : all) {
            sb.append("- ").append(s.id()).append(" — \"").append(s.name()).append("\": ")
              .append("scale ").append(String.format("%.2f", s.scale()))
              .append(", ").append(String.format("%.0f", s.health())).append(" health");
            if (s.modelKey() != null && !s.modelKey().isBlank()) {
                sb.append(", custom model '").append(s.modelKey()).append('\'');
                appendRealHeight(sb, s);
            }
            if (!s.powers().isEmpty()) {
                sb.append(", powers ").append(s.powers());
            }
            if (current != null && current.equalsIgnoreCase(s.id())) {
                sb.append("   ← THIS WISH IS ABOUT THIS ONE (your plan_wish `about` said so).");
            } else if (current == null && last != null && last.equalsIgnoreCase(s.id())) {
                sb.append("   ← the last thing a wish of his made or changed (a fact — whether "
                        + "THIS wish is about it is for you to decide from his words).");
            }
            sb.append("\n    spawn it again with: wishmob spawn ").append(s.id())
              .append("   |   select it with: ")
              .append(com.moddingfromamod.entity.WishMobTags.selector(s.id())).append('\n');
        }
        // DISCOVERABILITY, not a rule. The repo's most-repeated failure is a capability that was
        // built, wired and never used; the listing is where the agent already looks, so this is
        // where it learns that any of these can be a starting point. Deliberately a statement of
        // fact rather than an instruction — a lesson that is obeyed precisely is also a lesson that
        // does damage when it is wrong.
        sb.append("\nAny of these can be a BASE: pass sculpt_creature start_from '<id>' to inherit "
                + "its whole body and send only what changed (plus 'remove' and 'recolour'). "
                + "A variant — 'a baby version of my dragon', 'the same one but red' — costs a few "
                + "lines that way instead of thirty boxes.\n");
        sb.append("\nCall inspect_creature <id> before aiming anything at one — it gives the real "
                + "position of its mouth, eyes and hands in blocks.");
        sb.append(bodies);
        return ToolResult.ok(lessonsFirst() + sb.toString());
    }

    /**
     * The library's ready-made bodies not already designed here, as a paragraph — empty when there
     * are none. Pure, so {@code ListCreaturesToolTest} pins the wording and the de-duplication.
     */
    static String libraryBodies(java.util.Collection<String> libraryKeys, java.util.Set<String> designed) {
        java.util.List<String> fresh = new java.util.ArrayList<>();
        for (String k : libraryKeys) {
            if (k != null && !k.isBlank() && !designed.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                fresh.add(k);
            }
        }
        if (fresh.isEmpty()) {
            return "";
        }
        java.util.Collections.sort(fresh);
        return "\n\nReady-made BODIES in the library (not yet in this world): " + String.join(", ", fresh)
                + ".\nStart from one instead of sculpting from nothing: sculpt_creature start_from '<key>' "
                + "inherits the whole body, so you send only what is different. Or spawn it exactly as it "
                + "is with run_command 'wishmob spawn <key>'.";
    }

    /**
     * Add the creature's REAL height in blocks.
     *
     * <p>"scale 2.60" alone reads like something waist-high; Spikor is 56 model units tall, so at
     * that scale he stands 9.1 blocks. Reporting only the multiplier is how a fire breath ended up
     * aimed at thin air well above the mouth it was meant to come from.
     */
    private static void appendRealHeight(StringBuilder sb, Species s) {
        try {
            var geometry = com.moddingfromamod.runtime.ModelRepository.geometry(s.modelKey());
            if (geometry == null) {
                return;
            }
            var extents = com.moddingfromamod.runtime.CreatureAnatomy.extents(geometry);
            if (extents.isEmpty()) {
                return;
            }
            double h = com.moddingfromamod.runtime.CreatureAnatomy.heightBlocks(extents) * s.scale();
            sb.append(String.format(java.util.Locale.ROOT, ", %.2f blocks tall", h));
        } catch (RuntimeException ignored) {
            // A listing must never fail because one creature's model is unreadable.
        }
    }
}
