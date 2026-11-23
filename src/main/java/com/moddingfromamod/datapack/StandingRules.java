package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Which standing rules this world is already running, and whether a new one would collide.</b>
 *
 * <p>A whole-species wish ("make zombies bigger") is kept as a {@code clock tick} datapack, because
 * that is the only shape that reaches mobs which do not exist yet. Once a world can hold two
 * of them, it can hold two about the same species — and that is the case worth catching.
 *
 * <h2>Why this is an AUTHORING check and not a runtime one</h2>
 *
 * Two packs both selecting every zombie every tick is a <b>race</b>. Which one wins depends on pack
 * order and on which tagged first; the answer can differ between two loads of the same world, and
 * nothing anywhere reports it. A runtime detector could only ever describe the symptom after the
 * fact, inconsistently. At authoring time the question is simple and has one right answer:
 * <b>a second wish about zombies is an edit to the first rule, not a second rule.</b> That is a
 * path the mod already has — {@code author_datapack} with the same {@code feature_id} replaces the
 * program — and one the agent is already told about in {@code look_around}'s RULES section.
 *
 * <p>It applies to any second rule about a species, not only a contradictory one. "Zombies bigger"
 * then "zombies glow" are not in conflict, but they are still two clocks over the same selector, and
 * the child's mental model is one rule about zombies.
 *
 * <h2>Derived, never stored</h2>
 *
 * The index is computed from the saved CBScript sources on demand rather than kept in a file of its
 * own. One source of truth means it cannot drift from what is actually running, it survives a reload
 * for free, and a datapack removed by hand simply stops appearing.
 */
public final class StandingRules {

    private StandingRules() {
    }

    /**
     * Is this saved feature switched OFF in this world (undone, removed, or refused by the server)?
     * Then it is not running, so it is neither listed as a rule nor a collision (a pack the
     * server refused and took back off still blocked the next rule about the same mob as "already
     * running", so the agent was told to edit a rule that does nothing).
     */
    private static boolean switchedOff(String id) {
        return DatapackReloader.disabledIds().contains(DatapackReloader.packId(id));
    }

    /** Every saved feature that carries a standing species rule, and what it says. */
    public static Map<String, List<SpeciesRuleScan.Rule>> all() {
        Map<String, List<SpeciesRuleScan.Rule>> out = new LinkedHashMap<>();
        for (String id : DatapackStore.listIds()) {
            if (switchedOff(id)) {
                continue;
            }
            List<SpeciesRuleScan.Rule> rules = SpeciesRuleScan.rules(sourceOf(id));
            if (!rules.isEmpty()) {
                out.put(id, rules);
            }
        }
        return out;
    }

    /** What one feature says about mobs, in words, or "" if it says nothing. */
    public static String describe(String featureId) {
        List<SpeciesRuleScan.Rule> rules = SpeciesRuleScan.rules(sourceOf(featureId));
        List<String> parts = new ArrayList<>();
        for (SpeciesRuleScan.Rule r : rules) {
            parts.add(r.describe());
        }
        return String.join("; ", parts);
    }

    /**
     * The features, other than this one, that already rule the species this program would rule.
     *
     * @param featureId the id being authored — excluded, because re-authoring the same id is
     *                  exactly the fix and must never be refused as a collision with itself
     */
    public static Map<String, Set<String>> collisions(String featureId, String cbscript) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Set<String> mine = SpeciesRuleScan.standingSpeciesIn(cbscript);
        if (mine.isEmpty()) {
            return out;
        }
        String me = featureId == null ? "" : featureId.trim().toLowerCase(Locale.ROOT);
        for (String id : DatapackStore.listIds()) {
            if (id.trim().toLowerCase(Locale.ROOT).equals(me) || switchedOff(id)) {
                continue;
            }
            Set<String> theirs = SpeciesRuleScan.standingSpeciesIn(sourceOf(id));
            Set<String> shared = new LinkedHashSet<>(mine);
            shared.retainAll(theirs);
            if (!shared.isEmpty()) {
                out.put(id, shared);
            }
        }
        return out;
    }

    /**
     * What to tell the agent, or "" when there is nothing to say.
     *
     * <p>Names the existing feature and the species, and says what to do — a refusal that does not
     * hand back the id of the thing to edit costs a round trip on its own.
     */
    public static String collisionWarning(String featureId, String cbscript) {
        Map<String, Set<String>> clashes = collisions(featureId, cbscript);
        if (clashes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "There is already a rule running about that kind of mob, and two of them is not "
                + "two rules — it is a race. Both would select every one of them every tick, and "
                + "which one wins depends on the order the packs happen to load, so the child would "
                + "see it work, then not work, with nothing to explain either.\n");
        for (var e : clashes.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" already rules: ")
              .append(String.join(", ", e.getValue()));
            String what = describe(e.getKey());
            if (!what.isEmpty()) {
                sb.append("  (").append(what).append(')');
            }
            sb.append('\n');
        }
        String first = clashes.keySet().iterator().next();
        sb.append("Call author_datapack again with feature_id \"").append(first)
          .append("\" and the WHOLE program — everything that rule already does, PLUS the new "
                  + "part. `cbscript` is not a patch: anything you leave out stops happening, and "
                  + "he will not find out until the night it does not.\n")
          .append("Read it back first with list_datapacks ").append(first).append('.');
        return sb.toString();
    }

    private static String sourceOf(String featureId) {
        try {
            Path p = DatapackStore.sourceFor(featureId);
            return p != null && Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException | RuntimeException e) {
            // Unreadable is not the same as empty, but for a COLLISION check the safe direction is
            // to say nothing: refusing a wish because a file could not be opened is worse than
            // letting a second rule through and having it noticed.
            return "";
        }
    }
}
