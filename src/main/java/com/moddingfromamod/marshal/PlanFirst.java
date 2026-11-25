package com.moddingfromamod.marshal;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * <b>{@code plan_wish} is mandatory before a wish changes the world</b> (D16) —
 * Minecraft-free on purpose, so the rule is unit-tested here and the one place it runs
 * ({@code McpProtocol}'s {@code tools/call}) only asks it.
 *
 * <p>Guardrail 2 of T16 said a build tool comes after a plan, and it was HOPED for: the worker
 * prompt asked, and nothing checked. Measured over an earlier pass, Opus called {@code plan_wish} in
 * 257 of 351 grants and Muse in 34 of 149 — and a skipped plan is a wish with no plan safety grade,
 * no plan line for the child, nothing for {@code finish_building}'s claims check, and (T17) nothing
 * to price a fresh wish from on a world with a wish bank. So a world-changing tool now REFUSES, naming
 * {@code plan_wish}, until the bound wish has a plan.
 *
 * <p>What is NOT asked for a plan, and why:
 * <ul>
 *   <li><b>a call with no wish bound</b> — there is no wish to price and nobody to tell;</li>
 *   <li><b>templates, starter buttons and library casts</b> — they are served by the router and
 *       {@code WishExecutor}, never through {@code tools/call}, and T17 prices them from the saved
 *       artifact. Structurally exempt: they never reach this rule;</li>
 *   <li><b>{@link #EXEMPT}</b> — taking something away, re-reading packs, marking the spot early,
 *       testing a machine already built, teaching a template after a grant, taking him somewhere
 *       (a teleport moves the child, not the world);</li>
 *   <li><b>{@code run_command}</b> — only in a world with a wish budget (T17). It is the escape hatch
 *       for "make it daytime", and in a world where pricing is off the critical path, a plan
 *       turn in front of every one buys nothing. No such world exists yet, so the predicate
 *       defaults to false: {@link #budgetedWorld()}.</li>
 * </ul>
 *
 * <p>A tool nobody gave a decision is caught by {@code PlanFirstTest}, which walks every
 * world-changing tool {@link UndoContract} knows and requires it to be in {@link #NEEDS_PLAN} or
 * {@link #EXEMPT} — the same "a new tool with no decision" gate UndoContract has.
 */
public final class PlanFirst {

    /** Every tool that makes or changes something he will see, and so needs this wish's plan. */
    public static final Set<String> NEEDS_PLAN = Set.of(
            "build_structure",
            "author_datapack",
            "change_drops",
            "set_rule",
            "apply_effect",
            "define_creature",
            "sculpt_creature",
            "paint_creature",
            "paint_surface",
            "stage_event",
            "build_mechanism",
            "modify_mob",
            "modify_player",
            "make_item",
            "give_items");

    /** World-touching tools that deliberately do NOT need a plan. See the class note. */
    public static final Set<String> EXEMPT = Set.of(
            // taking away what he already has: the inverse of a wish, never a new thing to price
            "remove_item",
            "remove_build",
            "remove_creature",
            "remove_datapack",
            "remove_painting",
            "remove_event",
            "remove_mechanism",
            // maintenance: re-reads what is already there
            "reload_datapacks",
            // the sparkles at the spot, which the prompt says to call EARLY, before the design
            "conjure_at",
            // posts a parcel through a machine this wish already built (and so already planned)
            // teaches the watch a grant that has just happened
            "add_template",
            // moves HIM, not the world: it makes nothing to price and changes only where he
            // stands, like the run_command teleports that never plan. Gated, the agent called it
            // first and was refused in 2 of 34 real wishes — a whole model turn for nothing.
            "take_me_to");

    /** Needs a plan only where wishes are priced ({@link #budgetedWorld()}). */
    public static final String BUDGETED_ONLY = "run_command";

    /**
     * The boot property that turns the budget on, until T17 gives a world its own budget. A
     * property rather than a knob because nothing a grown-up can do yet makes it true on purpose.
     */
    public static final String PROPERTY = "moddingfromamod.wishbudget";

    private static volatile BooleanSupplier budgeted = () -> Boolean.getBoolean(PROPERTY);

    private PlanFirst() {
    }

    /** Whether THIS server's world has a wish budget. False everywhere until T17 wires one. */
    public static boolean budgetedWorld() {
        return budgeted.getAsBoolean();
    }

    /** The seam T17 wires its per-world budget into. {@code null} restores the default. */
    public static void budgetedWorld(BooleanSupplier supplier) {
        budgeted = supplier == null ? () -> Boolean.getBoolean(PROPERTY) : supplier;
    }

    /** True when {@code tool} may not run for a bound wish that has no plan yet. */
    public static boolean needsPlan(String tool, boolean budgetedWorld) {
        if (tool == null) {
            return false;
        }
        return NEEDS_PLAN.contains(tool) || (budgetedWorld && BUDGETED_ONLY.equals(tool));
    }

    /**
     * The refusal for this call, or {@code null} to let it run.
     *
     * @param wishBound     a wish is bound to the calling worker
     * @param planned       that wish has had a successful {@code plan_wish}
     * @param budgetedWorld this world prices wishes ({@link #budgetedWorld()})
     */
    public static String refusal(String tool, boolean wishBound, boolean planned, boolean budgetedWorld) {
        if (!wishBound || planned || !needsPlan(tool, budgetedWorld)) {
            return null;
        }
        return message(tool);
    }

    /** Names {@code plan_wish}, says nothing changed, and says what to do next — in that order. */
    public static String message(String tool) {
        return "REFUSED — PLAN FIRST: " + tool + " did not run and nothing changed. Every wish that "
                + "changes the world starts with plan_wish, and this wish has no plan yet. Call "
                + "plan_wish now with this wish's steps (every piece, in order), then call " + tool
                + " again with the same arguments.";
    }
}
