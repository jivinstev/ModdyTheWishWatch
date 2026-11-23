package com.moddingfromamod.wish;

import java.util.List;
import java.util.Locale;

/**
 * <b>A note may not say it was TESTED when nothing in the mod tested anything</b>.
 *
 * <h2>The hole this closes</h2>
 * Every finish-time guard in this repo keys on an artifact the mod knows about: {@code }'s
 * motion check needs a machine with a recorded problem, the unproven-machine guard needs a machine
 * to exist at all, and {@code honestVerdict} downgrades on one of those two. So a wish that builds
 * nothing the mod can name is unreachable by all of them <b>by construction</b>.
 *
 * <p>That is not hypothetical. On <i>"make me a drawbridge that opens up when I pull a lever, out
 * of stone bricks"</i> the agent never called {@code build_contraption} — it hand-placed a vanilla
 * sticky-piston bridge with 35 {@code setblock}s and 42 {@code fill}s, and told the child:
 *
 * <blockquote>Pull the lever and three chunks of stone brick slide out across the moat…
 * <b>I tested it both ways</b></blockquote>
 *
 * <p>No {@code ContraptionPlan}, so no ports and no spin checks. No {@code MachineHealth}, so
 * nothing recorded. No {@code verify_contraption}, so the oracle never ran. The entire verification
 * apparatus was bypassed by one routing decision, and <i>"I tested it both ways"</i> is
 * unfalsifiable: nothing in the system knows whether it did.
 *
 * <h2>What this is NOT</h2>
 * <b>It does not object to the build.</b> A vanilla redstone drawbridge is a perfectly good answer
 * for a child, and refusing it would be the mod overruling a reasonable engineering choice — which
 * is the mistake {@code honestVerdict} is careful not to make about verdicts. The complaint is
 * narrower and it is about honesty: say what you did, and do not claim a test nobody ran.
 *
 * <p>It also does not fire on a wish that simply made nothing. "Give me some diamonds", "make it
 * daytime", a question answered in a sentence — none of those claim a test, so none of them are
 * touched. The check is a <b>conjunction</b>, and that is the whole of its anti-cry-wolf property
 * (WISH_LINTERS P6): it needs an unverifiable wish <i>and</i> a note asserting it was verified.
 *
 * <h2>Why first person, and why past tense</h2>
 * The distinction that matters is between describing the thing and vouching for it. <i>"Pull the
 * lever and the bridge slides out"</i> is a description — it may be wrong, but it is not a claim
 * about work the agent did. <i>"I tested it"</i>, <i>"I checked it"</i>, <i>"I made sure"</i> are
 * claims about the agent's own actions, and those are the ones nothing can support here.
 *
 * <p>So the trigger is a first-person past-tense verification verb, never a bare "it works" — that
 * one is a prediction in the child's own register and firing on it would object to most honest
 * notes ever written.
 *
 * <p>Following the remedy costs the agent one sentence (P8): deleting the claim leaves a note that
 * is already correct, so the guard cannot push it into over-claiming somewhere else to compensate.
 */
public final class TestedClaim {

    private TestedClaim() {
    }

    /**
     * First-person past-tense claims of verification. Each is matched with the pronoun attached,
     * so a note saying <i>"pull it and you'll see it works"</i> — a prediction — is untouched.
     */
    private static final List<String> CLAIMS = List.of(
            "i tested", "i've tested", "i have tested",
            "i checked", "i've checked", "i have checked",
            "i verified", "i've verified", "i have verified",
            "i tried it", "i've tried it", "i have tried it",
            "i made sure", "i double-checked", "i double checked",
            "i watched it", "i ran it", "i confirmed",
            "tested it both ways", "checked it both ways");

    /** What the note claimed, or {@code null} if it claimed nothing it cannot support. */
    public static String claim(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String lower = note.toLowerCase(Locale.ROOT);
        for (String c : CLAIMS) {
            int at = lower.indexOf(c);
            if (at >= 0) {
                return clauseAround(note, at);
            }
        }
        return null;
    }

    /**
     * The clause the claim sits in, quoted back so the complaint names the actual words.
     *
     * <p>Quoting the whole note would bury the finding in the note; quoting only the matched phrase
     * would strip the context that makes it recognisable to the agent that wrote it.
     */
    static String clauseAround(String note, int at) {
        int start = 0;
        for (int i = at; i > 0; i--) {
            char ch = note.charAt(i - 1);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                start = i;
                break;
            }
        }
        int end = note.length();
        for (int i = at; i < note.length(); i++) {
            char ch = note.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                end = i;
                break;
            }
        }
        return note.substring(start, end).trim();
    }

    /**
     * Tools that leave, change, or inspect something the mod can actually check.
     *
     * <p><b>This list is the correction to the first cut of this guard, and the bug it fixes is
     * exactly the one this class claims to avoid.</b> The first version asked only whether the wish
     * MADE anything, reading {@code WishRun.made()}. But {@code MadeKind.forTool} records a maker
     * and nothing else: the editors (then {@code modify_contraption} and {@code modify_train}, both
     * gone with Create) and every {@code verify_*} are absent from it by design, and {@code Marshal} says so outright
     * — <i>"a rung that MODIFIES records nothing in made()"</i>.
     *
     * <p>So a REFINEMENT — "make my factory taller", "fill up this tank I was working on" — would
     * have been refused for saying it tested something, even after calling {@code
     * verify_contraption} and reading a real answer. That is a false positive on the machines and
     * trains classes specifically, which are the two that have a modify path at all, and it is the
     * cry-wolf failure (P6) landing on the wishes most likely to be honest about verifying.
     *
     * <p>The honest question is not "did it make something" but "did anything the mod can check
     * take part in this wish at all". A verify tool counts even though it makes nothing: if the
     * agent asked the oracle, the claim has something behind it.
     */
    private static final List<String> CHECKABLE = List.of(
            // makers
            "build_structure", "sculpt_creature",
            "define_creature", "define_species", "author_datapack", "change_drops", "set_rule",
            "make_item", "paint_surface", "paint_creature", "add_template", "stage_event",
            // editors — record nothing in made(), which is the whole point of this list
            "modify_mob",
            // oracles — make nothing, and asking one is exactly what a test IS
            "verify_datapack",
            "inspect_build", "inspect_creature", "inspect_item", "inspect_event");

    /**
     * Did anything the mod can check take part in this wish?
     *
     * <p>False means every world change went through {@code run_command} (or nothing happened at
     * all), so no oracle ran and none could have.
     */
    public static boolean nothingCheckableWasUsed(List<?> made, List<String> toolsUsed) {
        if (made != null && !made.isEmpty()) {
            return false;
        }
        if (toolsUsed == null) {
            return true;
        }
        for (Object t : toolsUsed) {
            if (t != null && CHECKABLE.contains(String.valueOf(t))) {
                return false;
            }
        }
        return true;
    }

    /** Every tool that counts as checkable — exposed so a test can assert the list stays complete. */
    public static List<String> checkableTools() {
        return CHECKABLE;
    }

    /** The refusal, naming the remedy rather than only the fault (WISH_LINTERS P2). */
    public static String refusal(String claimed) {
        return "Your note tells him you tested it — \"" + claimed + "\" — but this wish built "
                + "nothing I can check. Every command went straight to the world, so there is no "
                + "build and no datapack for me to look at, and no verifier ever "
                + "ran. What you did may be exactly right, and a redstone-and-blocks answer is a "
                + "fine one — the problem is only the claim. Two ways out: build it with a tool I "
                + "can check (build_structure, author_datapack) and verify it, "
                + "or keep what you made and tell him plainly what it does and that you could not "
                + "test it. Then call finish_wish again.";
    }
}
