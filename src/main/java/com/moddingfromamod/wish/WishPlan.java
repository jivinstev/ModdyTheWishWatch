package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

/**
 * What Moddy is going to build, decided <b>before</b> it builds any of it.
 *
 * <p><b>Why plan at all.</b> The agent used to work out each piece as it went, which meant the world
 * narrated its construction order: in one build the Create gears appeared several seconds before the
 * housing grew around them. The child was watching the workshop instead of the result. Deciding
 * everything up front — across every kind of thing a wish needs, creature and datapack and
 * contraption together — is what makes the build a single burst rather than a serial.
 *
 * <p>It also buys the child a real progress bar. "Making something amazing…" for eleven minutes is
 * indistinguishable from a hang; <b>"Giving Spikor his fire (2 of 3)"</b> is a promise with an end
 * in sight, and a 7-year-old can read it.
 *
 * <p>Pure and Minecraft-free: the step arithmetic and the label are unit-tested, because getting
 * "0 of 3" or "4 of 3" in front of a child is exactly the kind of small wrongness that reads as
 * broken.
 */
public final class WishPlan {

    /** More steps than this and the bar stops meaning anything to a child. */
    public static final int MAX_STEPS = 8;

    /** A step label longer than this won't fit the bar; it gets trimmed. */
    public static final int MAX_LABEL = 48;

    /**
     * The placeholder's one line while Moddy is still working out WHAT to build. It carries
     * no count: "(1 of 1)" read as a wish on its LAST step during the slowest part of the wish.
     */
    public static final String THINKING_LABEL = "Moddy is planning…";

    private final List<String> steps;

    /** True for the stand-in shown before {@code plan_wish} arrives; it never shows a count. */
    private final boolean thinking;

    /** 1-based; 0 means "planned but nothing started yet". */
    private int current;

    private WishPlan(List<String> steps, boolean thinking) {
        this.steps = steps;
        this.thinking = thinking;
        this.current = 0;
    }

    /**
     * The stand-in bar shown while Moddy is planning: one step, already started, whose label
     * never carries "(1 of 1)". {@code setPlan} replaces it, and the count starts with the real plan.
     */
    public static WishPlan thinking() {
        WishPlan p = new WishPlan(List.of(THINKING_LABEL), true);
        p.startStep(1);
        return p;
    }

    public boolean isThinking() {
        return thinking;
    }

    /**
     * Build a plan from whatever the agent passed, or null if there is nothing usable. Blank entries
     * are dropped and the list is capped rather than rejected — a slightly shortened plan is far
     * better for the child than no bar at all.
     */
    public static WishPlan of(List<String> rawSteps) {
        if (rawSteps == null) {
            return null;
        }
        List<String> clean = new ArrayList<>();
        for (String s : rawSteps) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String t = s.strip();
            clean.add(t.length() > MAX_LABEL ? t.substring(0, MAX_LABEL).strip() : t);
            if (clean.size() >= MAX_STEPS) {
                break;
            }
        }
        return clean.isEmpty() ? null : new WishPlan(clean, false);
    }

    public int size() {
        return steps.size();
    }

    /** 1-based index of the step being worked on; 0 before the first {@link #startStep}. */
    public int current() {
        return current;
    }

    public List<String> steps() {
        return List.copyOf(steps);
    }

    public boolean isComplete() {
        return current > steps.size();
    }

    /**
     * Move to step {@code n} (1-based). Clamped to a REAL step rather than validated: an agent that
     * miscounts must not be able to put "5 of 3" on a child's screen, must not crash the wish, and
     * must not accidentally declare itself finished — which is why finishing has its own method.
     */
    public void startStep(int n) {
        this.current = Math.max(0, Math.min(steps.size(), n));
    }

    /**
     * Everything planned is built. Only {@code finish_building} says this, which is the point: the bar
     * reaching full and the child being told "it's ready" are the same event, so the bar can never
     * sit at 100% while Moddy is still working.
     */
    public void markComplete() {
        this.current = steps.size() + 1;
    }

    /** Advance one step. Used when the agent doesn't say which — the common case. */
    public void advance() {
        startStep(current + 1);
    }

    /** The label of the step being worked on, or "" before the first one. */
    public String currentLabel() {
        if (current < 1) {
            return "";
        }
        return steps.get(Math.min(current, steps.size()) - 1);
    }

    /**
     * The line the child reads, e.g. {@code "Giving Spikor his fire (2 of 3)"}. Falls back to the
     * caller's own text before any step has started, so the bar is never blank. A thinking plan and
     * a one-step plan carry no count: "(1 of 1)" tells a child nothing, and on the planning
     * placeholder it said the wish was on its last step.
     */
    public String progressLabel(String fallback) {
        if (current < 1) {
            return fallback == null ? "" : fallback;
        }
        if (thinking || steps.size() == 1) {
            return currentLabel();
        }
        return currentLabel() + " (" + Math.min(current, steps.size()) + " of " + steps.size() + ")";
    }

    /**
     * How full the bar is, 0..1. Deliberately reports a step as <b>finished</b> only once the next
     * one starts — a bar that hits 100% while Moddy is still working is a lie the child can see.
     */
    public float fraction() {
        if (steps.isEmpty()) {
            return 0f;
        }
        float f = (float) Math.max(0, current - 1) / steps.size();
        return Math.max(0f, Math.min(1f, f));
    }

    /**
     * <b>The plan line the child reads</b>, naming what the AGENT said the wish is about.
     *
     * <p>This is the first time a thing of his is named for a wish: the local resolver used to name
     * one at submit, before anyone had read his words, and was wrong 4 times in 4. Now the name
     * comes from {@code plan_wish}'s {@code about}, so it is the agent's reading, said once.
     *
     * @param aboutName how the thing is said ({@code "your Pegasus"}), or blank for something new
     */
    public static String line(List<String> steps, String aboutName) {
        StringBuilder sb = new StringBuilder("✨ Here's my plan");
        if (aboutName != null && !aboutName.isBlank()) {
            sb.append(" for ").append(aboutName.strip());
        }
        sb.append(": ");
        for (int i = 0; i < (steps == null ? 0 : steps.size()); i++) {
            sb.append(i == 0 ? "" : " → ").append(steps.get(i));
        }
        return sb.toString();
    }
}
