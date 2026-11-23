package com.moddingfromamod.marshal;

import java.util.Locale;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * <b>Whether a waking worker is handed the history of the thing it is about to change</b> — the
 * treatment switch for the wish-context experiment, and its own control.
 *
 * <ul>
 *   <li>{@link #OFF} — the handover summary and nothing more. Today's behaviour, and <b>the
 *       default</b>: Phase 1 exists to make the arms measurable, not to change what a child sees.</li>
 *   <li>{@link #ON} — the treatment. What it contains is deliberately not decided here; it is
 *       Phase 4's job, sized by what the Phase 2/3 baseline says is actually missing.</li>
 *   <li>{@link #SHUFFLED} — <b>the same volume of context about the wrong artifact.</b></li>
 * </ul>
 *
 * <h2>Why SHUFFLED is not optional</h2>
 * A treatment that injects a thousand tokens of anything may simply make the model more careful in
 * general. Without an arm that pays the same token cost carrying <i>irrelevant</i> history, a
 * positive result cannot be told apart from "more tokens made it try harder" — we would have
 * measured attention and reported memory. It is listed as threat 3 in the plan and as a required
 * gate in Phase 4, so the mode it needs exists from the start rather than being retrofitted once
 * there is a result worth defending.
 *
 * <p>Read per call, never cached, for the same reason as {@link WishAffinity}: one client boot has
 * to be able to measure more than one arm.
 */
public enum WishContextMode {

    /** Handover summary only. Today's behaviour, and the default. */
    OFF,

    /** The treatment: the artifact's own history, injected on the wake. */
    ON,

    /** Threat 3's control: the same volume, about a different artifact. */
    SHUFFLED;

    /** {@code -Dmoddingfromamod.wishcontext=on} (or {@code -Ptestprops="wishcontext=on"}). */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".wishcontext";

    public static final WishContextMode DEFAULT = OFF;

    private static volatile WishContextMode override;

    /** What is being injected <b>right now</b>. Never cached — see the class note. */
    public static WishContextMode current() {
        WishContextMode forced = override;
        if (forced != null) {
            return forced;
        }
        return parse(System.getProperty(PROPERTY));
    }

    /** A name to a mode; anything unreadable is {@link #DEFAULT}, never a treatment. */
    public static WishContextMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** Force a mode for this JVM; {@code null} restores the property/default. */
    public static void override(WishContextMode mode) {
        override = mode;
    }

    /** True when a context block is injected at all — {@link #ON} or {@link #SHUFFLED}. */
    public boolean injects() {
        return this != OFF;
    }

    /** The lower-case name the action log and the scorer use as an arm label. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
