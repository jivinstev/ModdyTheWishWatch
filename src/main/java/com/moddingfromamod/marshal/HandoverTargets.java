package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Which of a wish's things its handover notes are FOR</b>. Minecraft-free.
 *
 * <p>{@code finish_wish}'s notes ({@code tried}, {@code careful}, {@code state}) are what the next
 * agent to touch a thing is handed. They went to ONE id: the {@code artifact} named, or the first
 * thing the wish made — so a castle-and-seahorses wish left the seahorses with no history, and the
 * next "make the seahorses darker" started from nothing. Now:
 * <ol>
 *   <li>{@code artifact} may name several, comma-separated: each gets the notes;</li>
 *   <li>naming none, every PART of a compound wish gets them ({@code PhotoParts}), else the first
 *       thing made, as before.</li>
 * </ol>
 * The first id is the primary: the one a pronoun-only next wish ("make it bigger") is about.
 */
public final class HandoverTargets {

    private HandoverTargets() {
    }

    /** At most this many ids take the notes: each is a few store writes on the server thread. */
    public static final int MAX = 8;

    /**
     * @param artifact the agent's {@code artifact} argument, possibly "a, b"
     * @param parts    the wish's parts when it made more than one ({@code PhotoParts.noted}: parts and edits, uncapped), else empty
     * @param made     what the wish made, newest first ({@code WishRun.made()})
     * @return the ids to record the handover for, primary first; empty for a wish that made nothing
     */
    public static List<String> of(String artifact, List<String> parts, List<WishRun.Made> made) {
        Set<String> out = new LinkedHashSet<>();
        if (artifact != null) {
            for (String a : artifact.split(",")) {
                if (!a.isBlank()) {
                    out.add(a.trim());
                }
            }
        }
        if (out.isEmpty() && parts != null) {
            out.addAll(parts);
        }
        if (out.isEmpty() && made != null && !made.isEmpty() && made.get(0) != null) {
            out.add(made.get(0).id());
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > MAX ? new ArrayList<>(list.subList(0, MAX)) : list;
    }
}
