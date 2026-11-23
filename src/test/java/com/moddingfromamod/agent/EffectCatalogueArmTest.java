package com.moddingfromamod.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * <b>Does the A/B off-switch actually switch anything off?</b>
 *
 * <p>This is a pre-flight check for an experiment that costs about ninety minutes of live client
 * time. If the control arm silently keeps {@code apply_effect}, both arms are identical, the run
 * reports "no measurable effect", and the conclusion is that a good idea did not work.
 *
 * <p>Not hypothetical: the first version of the driver script passed the switch as {@code JVMARGS},
 * a variable {@code client-test.sh} does not read at all. The flag would have been dropped on the
 * floor and the whole run would have been control-versus-control. That was caught by reading the
 * script rather than by any test, which is exactly why this test now exists.
 */
class EffectCatalogueArmTest {

    private static final String PROP = ModdingFromAModMod.MODID + ".effectCatalogue";

    private static boolean hasApplyEffect() {
        return WishAgent.buildTools().all().stream()
                .anyMatch(t -> "apply_effect".equals(t.name()));
    }

    @Test
    void theTreatmentArmOffersTheReadyMadeEffects() {
        System.clearProperty(PROP);
        assertTrue(hasApplyEffect(),
                "apply_effect must be registered by default, or the TREATMENT arm is not the "
                        + "treatment and the experiment measures nothing");
    }

    @Test
    void theControlArmDoesNotOfferThem() {
        System.setProperty(PROP, "off");
        try {
            assertFalse(hasApplyEffect(),
                    "the control arm still offers apply_effect, so both arms are identical — the "
                            + "run would report 'no measurable effect' and that conclusion would "
                            + "be about the switch, not about the catalogue");
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    void theAgentIsTOLDTheCatalogueExists() {
        // THE BUG THIS EXISTS FOR, found two runs into a live A/B. The tool was registered and its
        // description listed every effect -- and the agent never called it once, because nothing
        // in the session prompt pointed at it while the surrounding guidance actively said to try
        // the creature paths first. A treatment arm that the agent cannot find is not a treatment
        // arm, and the run would have reported "no measurable effect" from a perfectly good idea.
        System.clearProperty(PROP);
        assertTrue(hasApplyEffect(), "precondition: the tool should be registered by default");
        assertTrue(AgentSession.prompt().contains("apply_effect"),
                "apply_effect is registered but the session prompt never mentions it, so the "
                        + "agent will keep authoring abilities from scratch and any A/B measures "
                        + "nothing");
    }

    @Test
    void thePromptNamesEveryABILITYCLASSSWordsNotJustTheFirstClasses() {
        // THE BUG THIS EXISTS FOR, found by the impulse A/B. The retrieval paragraph listed only
        // class 1's vocabulary -- "poison you, set you on fire, slow you down, glow" -- which is
        // invisible while class 1 is the only class, and it scored 8/8. Impulse then scored 4/8
        // selection with ZERO wrong triggers: never wrong when it used the catalogue, it just did
        // not reach for it, because "my magnet robot should pull all the zombies to him" does not
        // pattern-match a list about poison and fire. That wish is impulse's OWN advertised
        // example.
        //
        // So this is the enforcement of the playbook rule (§4e): a class the prompt never names is
        // a class the agent never reaches for, and every other gate stays green while it happens.
        String prompt = AgentSession.prompt().toLowerCase(java.util.Locale.ROOT);
        for (String word : new String[]{"poison", "fire", "slow", "glow"}) {
            assertTrue(prompt.contains(word),
                    "the retrieval paragraph lost class 1's word '" + word + "'");
        }
        // Class 2 (impulse). At least one word per DIRECTION the class supports, because a child
        // reaching for "pull" is a different wish from one reaching for "launch", and a paragraph
        // that names only one of them retrieves for only one of them.
        for (String word : new String[]{"knock", "launch", "pull"}) {
            assertTrue(prompt.contains(word),
                    "the retrieval paragraph never says '" + word + "', so a wish phrased that way "
                            + "will not remind the agent the catalogue exists -- this is exactly "
                            + "how impulse scored 4/8 while being right every time it was used");
        }
    }

    @Test
    void thePromptSendsARainbowVanillaMobToColourCycleNotToASculpt() {
        // "a rainbow axolotl" came out a plain axolotl twice, on both rigs, because the
        // agent's only choices were a vanilla mob (fast, rainbow lost) or a whole sculpt.
        String prompt = AgentSession.prompt().toLowerCase(java.util.Locale.ROOT);
        assertTrue(prompt.contains("rainbow") && prompt.contains("colour_cycle"),
                "the prompt no longer routes a rainbow vanilla mob to colour_cycle");
    }

    @Test
    void thePromptSaysToCheckEvenWhenItLooksLikeItNeedsCustomCode() {
        // Three of impulse's four misses went find_content -> cbscript_help -> author_datapack.
        // The likely mechanism is a CORRECT inference with a wrong conclusion: no vanilla command
        // moves an entity, so "I will have to write this myself" is a reasonable thought. A class
        // whose implementation is non-obvious needs the prompt to say "check anyway", or the agent
        // reasons its way past a tool that already does the job.
        //
        // Asserted on "custom code" and not on "even when": the first draft of this test used the
        // latter and PASSED against the pre-fix prompt, because that phrase already occurred twice
        // elsewhere. A guard that cannot fail is worse than no guard -- it reports the protection
        // exists. Caught by mutation-testing this very test, which is the only reason it is right.
        String prompt = AgentSession.prompt().toLowerCase(java.util.Locale.ROOT);
        assertTrue(prompt.contains("custom code") && prompt.contains("no vanilla command"),
                "the prompt no longer tells the agent to check the catalogue even when the ability "
                        + "sounds like it needs custom code -- the reasoning that skipped impulse");
    }

    @Test
    void anyOtherValueLeavesItOn() {
        // Only the exact string "off" disables it. A typo in the driver script should fail SAFE --
        // toward the arm we can detect from the data -- rather than silently disabling the feature
        // in a run labelled "treatment".
        System.setProperty(PROP, "false");
        try {
            assertTrue(hasApplyEffect(),
                    "an unrecognised value disabled the catalogue; a typo in the harness would "
                            + "then produce a mislabelled arm");
        } finally {
            System.clearProperty(PROP);
        }
    }
}
