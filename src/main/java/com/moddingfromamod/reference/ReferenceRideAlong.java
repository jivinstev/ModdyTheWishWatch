package com.moddingfromamod.reference;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * <b>The reference rides in with the wish</b> (idea 1).
 *
 * <p>Measured 's A/B (Opus at low effort): every stand-in run decided on "an original
 * character" without calling {@code look_up_character}, so it never saw the facts; when the lookup
 * WAS called, its colours were followed (Plumo red 30 / blue 30 / tan 25 in, 30 / 30 / 20 built).
 * The researcher is already running by then: {@link ReferenceHint#prefetchFrom} starts it at
 * submit. So, from the hand-over, this waits in the background for that same result and posts it
 * as a note that rides the worker's next tool reply. Nothing the child waits on is held, no search
 * is added, and the wish agent still gets no web access: what crosses is the researcher's typed
 * {@link ReferenceSpec}, rendered by {@link ReferenceSpec#describe}.
 *
 * <p>{@code -Ptestprops referenceridealong=off} is the A/B arm.
 */
public final class ReferenceRideAlong {

    public static final String PROPERTY = "moddingfromamod.referenceridealong";
    /** How long the background wait lasts: a cold lookup measured 48–109 s. */
    public static final int WAIT_SECONDS = 120;

    private static final Set<String> AGENT_ASKED = ConcurrentHashMap.newKeySet();

    private ReferenceRideAlong() {
    }

    /** The agent called {@code look_up_character} itself in this wish: the note would repeat it. */
    public static void agentAsked(String wishId) {
        if (wishId != null) {
            AGENT_ASKED.add(wishId);
        }
    }

    /** The note for the agent, or empty. Pure. */
    public static Optional<String> note(String name, ReferenceSpec spec) {
        if (name == null || name.isBlank() || spec == null || spec.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("REFERENCE FOR THIS WISH: " + name + " was looked up the moment the wish arrived "
                + "(you need not call look_up_character for it). A child wishing for a character he loves "
                + "wants it recognisable: his own fan-made copy in his own private world, colours and shape "
                + "kept, the face your own. Build from these facts, even if what you build is your own "
                + "stand-in: keep its colours in these proportions and its signature parts.\n"
                + spec.describe());
    }

    /**
     * What to post for this wish, decided with the lookups handed in, so a test can drive it with no
     * model and no web. Blocks for the lookup: call it off the server thread.
     */
    static Optional<String> decide(String wishId, String wish,
                                   Function<String, Optional<CharacterResolver.Resolved>> resolve,
                                   BiFunction<String, SubjectKind, ReferenceSpec> look) {
        var r = resolve.apply(wish);
        if (r.isEmpty() || r.get().kind() != SubjectKind.CHARACTER) {
            return Optional.empty();
        }
        ReferenceSpec spec = look.apply(r.get().name(), r.get().kind());
        if (AGENT_ASKED.remove(wishId)) {
            return Optional.empty();
        }
        return note(r.get().name(), spec);
    }

    /** At the hand-over: wait for the reference in the background and post it through {@code deliver}. */
    public static void atHandOver(String wishId, String wish, BiConsumer<String, String> deliver) {
        if (wishId == null || wish == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))
                // a briefed wish already carries the look, and this note would name the character
                || briefed(wishId)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                decide(wishId, wish, CharacterResolver::resolveSubject,
                        (name, kind) -> ReferenceResearcher.lookUp(name, WAIT_SECONDS, kind))
                        .ifPresent(n -> deliver.accept(wishId, n));
            } catch (RuntimeException e) {
                // A reference that never arrives costs nothing: the agent can still look it up.
            }
        }, "mfam-reference-ride-along");
        t.setDaemon(true);
        t.start();
    }

    /** Between tests. */
    static void clear() {
        AGENT_ASKED.clear();
    }

    private static boolean briefed(String wishId) {
        try {
            return WishBriefs.veiled(java.util.UUID.fromString(wishId));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
