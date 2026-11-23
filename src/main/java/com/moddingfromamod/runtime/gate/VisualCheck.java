package com.moddingfromamod.runtime.gate;

import java.util.List;

import com.moddingfromamod.runtime.CreatureSculpt;

/** One thing we look at when a creature is sculpted. */
public interface VisualCheck {

    VisualGate gate();

    /** What is wrong, in words the agent can act on. Empty means it passed. */
    List<String> run(Subject subject);

    /**
     * What the check gets to look at.
     *
     * @param parts       the creature as just sculpted
     * @param previous    the same creature's PREVIOUS sculpt, or null on a first build. This is
     *                    what makes a change-check possible at all.
     * @param declared    what the agent said it was going to change this time, lower-cased. A
     *                    change the agent declared is deliberate; one it did not is a side effect,
     *                    and side effects are the bug (criterion 6).
     */
    record Subject(List<CreatureSculpt.Part> parts, List<CreatureSculpt.Part> previous,
                   String declared) {

        public boolean isRevision() {
            return previous != null && !previous.isEmpty();
        }

        /** Did the agent say it was going to touch this? */
        public boolean declaredMentions(String... words) {
            if (declared == null || declared.isBlank()) {
                return false;
            }
            String d = declared.toLowerCase(java.util.Locale.ROOT);
            for (String w : words) {
                if (d.contains(w)) {
                    return true;
                }
            }
            return false;
        }
    }
}
