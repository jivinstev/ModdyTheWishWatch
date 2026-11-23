package com.moddingfromamod.wish;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * EXPLORATORY SWEEP — prints how every player/mob-effect phrasing routes today. Not a gate yet.
 *
 * <p>Step 0 of the mob/player-effects class. The matrix is real wishes mined from the action log,
 * plus synthesized minimal pairs that differ by an article or a pronoun — the cases where the
 * reading flips but the wording barely does.
 */
class PlayerMobRoutingSweep {

    private static final WishTemplates TEMPLATES = WishCatalog.templates();

    /** What the wish SHOULD do, per the reading rule. */
    private enum Want { PLAYER, MOB, BUILD }

    private record Case(Want want, String source, String wish) {}

    private static final List<Case> MATRIX = List.of(
        // ── real, and unambiguously the child himself ──────────────────────────────
        new Case(Want.PLAYER, "REAL", "make me super fast"),
        new Case(Want.PLAYER, "REAL", "make me fly"),
        // ── cited as real, not present in this machine's log ───────────────
        new Case(Want.PLAYER, "CITED", "let me FLY"),
        new Case(Want.PLAYER, "CITED", "let me get hurt but NEVER die"),
        new Case(Want.PLAYER, "CITED", "make me RICH with diamonds and netherite"),
        // ── synthesized player forms ───────────────────────────────────────────────
        new Case(Want.PLAYER, "SYNTH", "make me giant"),
        new Case(Want.PLAYER, "SYNTH", "turn me into a dragon"),
        new Case(Want.PLAYER, "SYNTH", "let me jump super high"),
        new Case(Want.PLAYER, "SYNTH", "make me strong"),
        new Case(Want.PLAYER, "SYNTH", "i want to breathe underwater"),
        new Case(Want.PLAYER, "SYNTH", "make me invincible"),
        new Case(Want.PLAYER, "SYNTH", "let me run really fast"),

        // ── real, and about a creature ─────────────────────────────────────────────
        new Case(Want.MOB, "REAL", "make him faster"),
        new Case(Want.MOB, "REAL", "make him much tougher so he can survive a fight"),
        new Case(Want.MOB, "REAL", "make Chucko McBurger GIANT!"),
        new Case(Want.MOB, "REAL", "make Tails fly way up high and fast"),
        new Case(Want.MOB, "REAL", "give him more hearts"),
        new Case(Want.MOB, "REAL", "make it hurt way more"),
        new Case(Want.MOB, "REAL", "can it set things on fire too"),
        new Case(Want.MOB, "REAL", "make the giant PUNCH me so hard i fly backwards"),
        // ── synthesized mob forms that reuse player vocabulary ─────────────────────
        new Case(Want.MOB, "SYNTH", "make the dragon super fast"),
        new Case(Want.MOB, "SYNTH", "make him fly"),
        new Case(Want.MOB, "SYNTH", "make them all invincible"),
        new Case(Want.MOB, "SYNTH", "give the zombie super speed"),

        // ── "make me a X" is BUILD, per the recorded rule ──────────────────────────
        new Case(Want.BUILD, "REAL", "make me a chocolate river"),
        new Case(Want.BUILD, "REAL", "build me a factory using the create mod"),
        new Case(Want.BUILD, "REAL", "make me a BUTT CHEEK WATER javelin that I can throw"),
        new Case(Want.BUILD, "REAL", "make me Zippy the Hedgehog, he is blue and runs super fast"),
        new Case(Want.BUILD, "SYNTH", "make me a dragon"),
        new Case(Want.BUILD, "SYNTH", "make me a fast dragon"),
        new Case(Want.BUILD, "SYNTH", "make me a giant"),
        new Case(Want.BUILD, "SYNTH", "make me a giant robot"),
        new Case(Want.BUILD, "SYNTH", "build me a really fast rocket"),
        new Case(Want.BUILD, "SYNTH", "make me a sword that makes me fly")
    );

    @Test
    void sweep() {
        System.out.println("\n=== PLAYER / MOB ROUTING SWEEP ===");
        System.out.printf("%-8s %-6s %-14s %s%n", "want", "src", "template", "wish");
        int intercepted = 0;
        for (Case c : MATRIX) {
            String id = TEMPLATES.matchConfident(WishText.sanitize(c.wish()))
                    .map(WishTemplates.Confident::templateId).orElse(null);
            if (id != null) {
                intercepted++;
            }
            System.out.printf("%-8s %-6s %-14s %s%n",
                    c.want(), c.source(), id == null ? "-> agent" : id, c.wish());
        }
        System.out.println("\nintercepted offline by a template: " + intercepted
                + " of " + MATRIX.size());
    }
}
