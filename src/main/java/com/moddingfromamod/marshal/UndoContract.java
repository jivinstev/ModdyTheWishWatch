package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Whether a wish can honestly be <b>put back</b>, from the list of tools it actually used.
 *
 * <p><b>Stop is universal. Undo is best-effort, and we say which one happened.</b> A child forgives
 * "I stopped!"; he does not forgive a button that said it would put his castle back and then did
 * not. So this answers one question — <i>did every tool this wish used have an inverse?</i> — and
 * the answer decides whether an {@code [undo]} affordance is drawn at all.
 *
 * <p>The hole is {@code run_command}, which is about 35% of all tool calls and generally not
 * reversible: an arbitrary command can do anything. It is deliberately NOT special-cased into
 * "probably fine" — one wish that runs a `/kill` and offers undo is worse than a hundred that
 * honestly say "I stopped".
 *
 * <p><b>Unknown tools count as irreversible.</b> That is the important default: a tool added next
 * month, forgotten here, must make undo disappear rather than silently make it lie. The build fails
 * loudly instead — see {@code UndoContractTest}, which asserts every registered tool has a decision.
 *
 * <p>Pure — no Minecraft — so the whole table is a table-driven unit test.
 */
public final class UndoContract {

    /**
     * Tools that <b>change the world and can be undone</b>. Each has a real inverse: the removes are
     * their own inverse, a creature edit is a revision (the previous one is kept), and a build is
     * replayed from its plan and anchor in {@code BuildRegistry} rather than from a region snapshot.
     */
    private static final Set<String> REVERSIBLE = Set.of(
            "build_structure",
            "author_datapack",
            // The drops pack likewise: `remove: true` takes a rule out, and the whole pack
            // is a datapack remove_datapack can take away.
            "change_drops",
            // A world rule: `remove: true` takes it out, and nothing else it did is kept.
            "set_rule",
            // A staged event: every block it touched is in its journal, so remove_event
            // puts the world back exactly and forgets the design.
            "stage_event",
            "remove_event",
            "apply_effect",
            "define_creature",
            "sculpt_creature",
            "paint_creature",
            // A picture on a surface. Reversible because PaintRegistry records what was
            // there BEFORE — the block entity holds only the current picture and has no history,
            // so without that record a repaint could be stopped but never put back, and the button
            // would be lying about the commonest case ("no, the other one").
            "paint_surface",
            "remove_painting",
            // It DOES keep an undo record now: the state the mob was in before this wish
            // first touched it is written down, so MadeKind.MOB can be reversed by putting the
            // numbers back rather than by deleting an animal that was already standing there.
            "modify_mob",
            // NOT the same as modify_mob any more, and the difference is worth keeping visible: it
            // changes the child rather than making an artifact, so it does not BLOCK undo but it
            // has no undo record of its own. Every power it grants takes `remove: true`, and
            // sizes reset to 1, so the inverse exists and the agent can run it — a child who
            // undoes a wish is not left giant with no way back. Worth revisiting alongside
            // (grants that leave no way back) rather than diverging from modify_mob here.
            "modify_player",
            // Navigation. Same shape as modify_player above it: it moves the child rather
            // than making an artifact, so it does not BLOCK undo and keeps no undo record of its
            // own. The inverse is real and the agent can run it — the tool reports the coordinates
            // he was standing on before it moved him, precisely so "take me back" is answerable.
            // Classified here rather than as HARMLESS deliberately: HARMLESS means "changes
            // nothing a child could see afterwards", and being somewhere else is the one thing he
            // definitely notices.
            "take_me_to",
            "make_item",
            "add_template",
            "conjure_at",
            "remove_build",
            "remove_creature",
            "remove_datapack",
            // The inverse of make_item, and the reason MadeKind.ITEM can exist at all — a kind with
            // no reversal makes the [undo] button lie.
            "remove_item",
            "reload_datapacks",
            // A hand-over: every item it gives goes through WishDelivery, which
            // records it against the wish, and an undo takes exactly those back — from his hands,
            // anywhere in his inventory, and his Wish Bag. Until then it had no inverse at all.
            "give_items");

    /**
     * Tools that change nothing a child could see afterwards — looking, listing, verifying,
     * photographing, narrating. They can never be the reason undo is unavailable.
     */
    private static final Set<String> HARMLESS = Set.of(
            "await_wish",
            "cbscript_help",
            "find_content",
            "find_ready_made",          //: reads the ready-made index
            "describe_ready_made",
            "finish_building",
            "finish_wish",
            "inspect_creature",
            "inspect_event",
            "list_builds",
            "inspect_build",
            // Reading back a thing he already holds changes nothing about it.
            "inspect_item",
            "inspect_painting",
            // Looking at the child is looking, not changing him.
            "inspect_player",
            "list_creatures",
            "list_datapacks",
            "list_events",
            // Listing the designs is reading, exactly like every other list_* above. The gate that
            // caught this omission is the point: a new tool with no undo decision silently costs
            // the wish its [undo] button.
            "list_items",
            "list_paintings",
            "list_templates",
            "list_wishes",
            "look_around",
            "look_up_character",
            "notify_player",
            "photograph",
            "plan_wish",
            "ask_which",
            "set_status",
            "verify_datapack");

    /**
     * Tools with <b>no inverse</b>, named rather than left to the unknown default so the reason can
     * be told to a grown-up reading a log.
     */
    private static final Set<String> IRREVERSIBLE = Set.of(
            "run_command");

    private UndoContract() {
    }

    /** True when a wish that used exactly these tools can honestly offer {@code [undo]}. */
    /**
     * <b>Is this {@code run_command} batch only a hand-over?</b> Every command a plain
     * {@code give} or a made item's {@code wishdatapack <id> give} — which is exactly how
     * {@code make_item} tells the agent to hand a thing over. Such a batch has the same inverse as
     * {@code give_items} (WishDelivery's ledger), so it is recorded as that and does not cost the
     * wish its [undo]. Anything else in the batch — one {@code setblock} — and it is
     * {@code run_command}, irreversible, as before. Pure, so the rule is a unit test.
     */
    public static boolean onlyHandsOver(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return false;
        }
        for (String c : commands) {
            String t = c == null ? "" : c.strip();
            if (t.startsWith("/")) {
                t = t.substring(1);
            }
            String[] w = t.split("\\s+");
            boolean give = w.length >= 3 && w[0].equals("give");
            boolean packGive = w.length == 3 && w[0].equals("wishdatapack") && w[2].equals("give");
            if (!give && !packGive) {
                return false;
            }
        }
        return true;
    }

    public static boolean undoable(List<String> toolsUsed) {
        return blockers(toolsUsed).isEmpty();
    }

    /** The tools that make undo impossible — empty when it is available. */
    public static List<String> blockers(List<String> toolsUsed) {
        List<String> out = new ArrayList<>();
        if (toolsUsed == null) {
            return out;
        }
        for (String tool : toolsUsed) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String t = tool.trim();
            if (HARMLESS.contains(t) || REVERSIBLE.contains(t)) {
                continue;
            }
            if (!out.contains(t)) {
                out.add(t);   // named irreversible, or unknown — both mean no undo
            }
        }
        return out;
    }

    /** True when this tool has been given a decision at all. Used by the gate, not at runtime. */
    public static boolean known(String tool) {
        return tool != null
                && (HARMLESS.contains(tool) || REVERSIBLE.contains(tool) || IRREVERSIBLE.contains(tool));
    }

    /** True when using this tool changes something a child could see. */
    public static boolean touchesTheWorld(String tool) {
        return tool != null && !HARMLESS.contains(tool);
    }

    /**
     * Which outcomes may be offered for a wish.
     *
     * <p>{@link CancelMode#STOP} is always in the list — that is the point of the whole design.
     *
     * <p><b>Undo needs two things, not one.</b> Every tool used must have an inverse AND the wish
     * must actually have made something. Judging on the tools alone offers {@code [↩️ undo]} on a
     * wish that has just been submitted and done nothing at all — technically not a lie, since
     * stopping it does put the world back (there was nothing to put back), but it reads to a child
     * as a promise that something will be taken away. He is looking at a wish that has produced
     * nothing; two buttons where one means anything is worse than one honest button.
     *
     * @param hasMadeSomething whether this wish has produced anything an undo could reverse
     */
    public static List<CancelMode> offerFor(List<String> toolsUsed, boolean hasMadeSomething,
                                            boolean hasBetterCandidate) {
        List<CancelMode> out = new ArrayList<>();
        if (hasBetterCandidate) {
            // First, because a wrong "make it bigger" wants the work re-aimed, not thrown away.
            out.add(CancelMode.REDIRECT);
        }
        if (hasMadeSomething && undoable(toolsUsed)) {
            out.add(CancelMode.UNDO);
        }
        out.add(CancelMode.STOP);
        return out;
    }
}
