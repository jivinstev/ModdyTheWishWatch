package com.moddingfromamod.agent.tools;

import java.util.List;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.item.ItemLineage;
import com.moddingfromamod.item.ItemSpecStore;

/**
 * <b>The items that already exist, so "and one that…" is a lookup rather than a guess.</b>
 *
 * <h2>Items was the only class with a store and no list</h2>
 * Measured across the whole tool surface: {@code list_builds}, {@code list_creatures},
 * {@code list_trains} and {@code list_contraptions} (both parked with Create), {@code list_datapacks}, {@code list_templates}
 * and {@code list_wishes} all exist. There was <b>no {@code list_items}</b> — assumed there
 * was. {@code inspect_item} exists, but it answers <i>"tell me about this id"</i>, which is no use
 * to a wish that never names one.
 *
 * <p>So <i>"and one that shoves the noob in LAVA"</i> arrived at an agent with no way to discover
 * what "one" was. Not a ranking problem or a retrieval problem — there was nothing to retrieve
 * from. {@code ListCreaturesTool} had already solved exactly this for creatures, down to marking
 * the one he is working on; this is that, for the class that needed it most.
 *
 * <h2>Recency was already right, and that is worth recording</h2>
 * {@code ItemSpecStore} keeps a {@code LinkedHashMap} and {@code put} does {@code remove} then
 * {@code put}, so an EDIT moves a design to the end. Least-recently-touched first, most recent
 * last — genuinely "what were we just talking about", and it survives a reload because
 * {@code load} replays the file in order. I went looking for a bug here and there is not one; the
 * store could answer the question all along and nothing asked it.
 */
public final class ListItemsTool implements McpTool {

    // NO CAP, DELIBERATELY. This carried MAX_SHOWN = 30 and a "… N older design(s) not shown"
    // line. Both are gone, and the disclosure line is not what made it acceptable.
    //
    // The number was invented. The only world anyone has measured is an earlier issue's — 48 artifacts across
    // EVERY kind — so a 30-item ceiling on one kind was sized against a collection nobody has ever
    // had, which is what docs/WISH_LINTERS.md P9 forbids: derive a threshold from real archived
    // data, never choose one. And the failure it risks is the one this tool exists to prevent: an
    // agent that reads a short list, does not find the thing the child means, and mints a SECOND
    // one. A disclosure line makes that recoverable, not impossible, and recovering costs a turn.
    //
    // Cost is not the constraint: the whole store renders in about a paragraph against a wish that
    // costs $2.21-$5.29. If a collection ever gets big enough to matter, the answer is a
    // measurement and a decision about WHICH designs to leave out -- not a count that drops
    // whatever happens to be oldest.

    @Override
    public String name() {
        return "list_items";
    }

    @Override
    public String description() {
        return "List the items already designed in this world, oldest first — so the LAST one is "
                + "the one he was most recently working on. CHECK THIS FIRST for any wish that "
                + "does not spell out a whole item: \"and one that…\", \"another one but…\", \"make "
                + "it stronger\", \"the same but blue\". Those sentences carry only what CHANGED, "
                + "and the rest of the design is here. Then decide the one thing that matters: "
                + "start_from alone CHANGES that item, start_from + as_new makes him a SECOND one.";
    }

    @Override
    public JsonObject inputSchema() {
        return Schemas.object();
    }

    @Override
    public ToolResult call(JsonObject args) {
        List<ItemSpecStore.Spec> all = ItemSpecStore.all();
        if (all.isEmpty()) {
            return ToolResult.ok("No items have been designed in this world yet. If this wish says "
                    + "\"another\" or \"and one that…\", it is pointing at something that is NOT "
                    + "here — ask him which item he means rather than inventing what it was, "
                    + "because a wrong guess hands him a stranger wearing his item's name.");
        }
        // WHICH ONE IS HE TALKING ABOUT — the same answer list_creatures gives. Without it
        // the agent reads every item he ever made and has no reason to prefer the right one.
        var run = AgentContext.currentRun();
        // what YOUR plan_wish said this wish is about; before you have planned, only the
        // FACT of what he touched last — never "he means this one".
        String current = run == null ? null
                : run.aboutArtifact();
        String last = run == null ? null : com.moddingfromamod.wish.WishTopic.lastOf(run.playerId());

        StringBuilder sb = new StringBuilder("Items already designed in this world "
                + "(oldest first — the LAST is the most recently touched):\n");
        for (int i = 0; i < all.size(); i++) {
            ItemSpecStore.Spec s = all.get(i);
            sb.append("- ").append(s.id()).append(" — \"").append(s.name()).append("\": ")
              .append(s.shape());
            if (s.colour() != null && !s.colour().isBlank()) {
                sb.append(", ").append(s.colour());
            }
            if (s.icon() != null && !s.icon().isBlank()) {
                sb.append(", icon ").append(s.icon());
            }
            if (!s.effects().isEmpty()) {
                sb.append(", does ").append(s.effects());
            }
            if (s.baseItem() != null && !s.baseItem().isBlank()) {
                sb.append(", built on ").append(s.baseItem());
            }
            if (i == all.size() - 1) {
                sb.append("   ← MOST RECENT");
            }
            if (current != null && current.equalsIgnoreCase(s.id())) {
                sb.append("   ← THIS WISH IS ABOUT THIS ONE (your plan_wish `about` said so).");
            } else if (current == null && last != null && last.equalsIgnoreCase(s.id())) {
                sb.append("   ← the last thing a wish of his made or changed (a fact — whether "
                        + "THIS wish is about it is for you to decide from his words).");
            }
            sb.append('\n');
        }

        // THE TWO INTENTIONS, stated as fact rather than as an instruction — a lesson obeyed
        // precisely is also one that does damage when it is wrong. Both were unreachable before
        // start_from took the base's id unconditionally, so no argument meant "a second one".
        sb.append("\nTwo different things you can do with any of these, and they are ONE WORD "
                + "apart:\n")
          .append("  • start_from '<id>'                  → CHANGES that item. He still has one, "
                  + "and it is the new version. For \"make it green\", \"make it hurt more\".\n")
          .append("  • start_from '<id>' + as_new true    → a SECOND item, inheriting the whole "
                  + "design. He ends up with BOTH. For \"and one that…\", \"another one but…\".\n")
          .append("Say which; it is not guessed from your wording. Getting it wrong either hands "
                  + "him an item he never asked for, or destroys the one he loved.\n");
        sb.append("\nCall inspect_item <id> for one item's full design before changing it.");
        return ToolResult.ok(sb.toString());
    }

    /** Kept so the tool and the rule cannot drift: both name the same three routes. */
    static ItemLineage.Route routeFor(boolean startFromResolved, boolean asNew) {
        return ItemLineage.of(startFromResolved, asNew);
    }
}
