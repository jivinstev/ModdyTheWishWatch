package com.moddingfromamod.vanilla;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>Who may SEE a rig, and which parts are currently hidden</b> — #515b, Minecraft-free and
 * unit-tested.
 *
 * <h2>The invariant, in one line</h2>
 *
 * <p><b>A client sees the rig exactly when it has the pack that makes the rig make sense.</b>
 *
 * <p> withheld the PACK from the stock child until the reveal, which removed the freezes and
 * left the other half of the problem standing in the world: a vanilla-lite creature is 40-odd
 * {@code item_display} entities wearing {@code paper} with a custom model id, and a client without
 * the pack draws exactly that — a heap of flat white cards where the creature is being built. The
 * child was no longer frozen; he was watching the sculpt fail in public. Measured on a real Spikor
 * wish: three interim sculpts, three heaps.
 *
 * <p>The rule was {@code PackAudience.shouldPush} inverted while the pack rig still pushed packs;
 * there is no push, and the rule lives in {@link #hiddenFrom} alone.
 *
 * <h2>Why a LEDGER of entity ids and not a flag on the rig</h2>
 *
 * <p>Hiding is done by packet (see {@code RigVeilService}), and a packet needs ids. The ids also
 * have to outlive the {@code DisplayRig} object: a wish republishes several times, each sculpt
 * leaves its parts in the world until it is reaped, and the reveal has to un-hide <em>everything
 * still veiled</em> rather than whatever the last sculpt happened to return. A ledger keyed on the
 * world's own entity ids is the only thing that is true across all of that.
 *
 * <p>Ids are ints and nothing here touches Minecraft, so the whole thing is unit-testable — which
 * is the point of splitting it from the service at all.
 *
 * <h2>⚠ "un-hide everything still veiled" is NOT "show everything at once" — ATOM-P3c</h2>
 *
 * <p>That sentence above is the contract and it is right: nothing may stay hidden after the reveal,
 * or the child holds the models and looks at an empty anchor. What it does not say, and what cost
 * a photographed defect, is <em>whether the thing is still there to show</em>. A wish that put five
 * live creatures in the world while the child could not see them released 290 parts at once and
 * gave him four spare winged Spikors for nineteen seconds. The reveal therefore <b>reaps</b> the
 * scaffolding and releases the rest — see {@code RigVeilService.reveal}. Nothing stays veiled; the
 * ledger is still emptied in one step by {@link #unveil()}. What each id carries with it is the
 * TICK, which is the only thing this class needs to know to let the caller tell a wish's last word
 * on a creature from the four attempts before it.
 */
public final class RigVeil {

    private RigVeil() {
    }

    /**
     * <b>Should this client be denied the SIGHT of the rig?</b>
     *
     * <p>Only a STOCK client, and only while a camera is doing the looking and the current sculpt
     * has not been revealed. Each of the three conditions is a way this used to go wrong:
     *
     * <ul>
     *   <li><b>modded → never hidden.</b> The camera is a modded client and is the thing the agent
     *       photographs through; hiding from it would have the self-critique loop judge an empty
     *       patch of ground.</li>
     *   <li><b>no camera serving → never hidden.</b> On {@code Viewpoint}'s {@code CHILDS_EYES} arm
     *       the child IS the camera, so hiding from him is an earlier issue's bug induced deliberately, and
     * says the agent cannot tell that from a bad palette. It must be the LIVE answer: a
     *       camera spends ~13 s joining, can die and can be given up on.</li>
     *   <li><b>revealed → never hidden.</b> After the reveal nobody is hidden from, whatever else
     *       is true.</li>
     * </ul>
     *
     * <p><b>This was {@code PackAudience.shouldPush} inverted </b>, delegating so the push
     * rule and the veil rule could not drift apart (a drift showed as paper, or as a revealed child
     * looking at nothing). The pack rig's push was deleted with it, so the veil is the rule's only
     * reader and the rule lives here.
     *
     * @param modded        does this client have our jar ({@code Send.hasOurJar})?
     * @param cameraServing is a camera photographing right now? Live, never "configured"
     * @param revealed      has the current sculpt been revealed ({@link #alreadyRevealed})?
     */
    public static boolean hiddenFrom(boolean modded, boolean cameraServing, boolean revealed) {
        return !modded && cameraServing && !revealed;
    }

    /**
     * Has the stock route already been cleared for this reveal state?
     *
     * <p>Compared by content hash rather than by a boolean, because "revealed" is a fact about a
     * particular sculpt: a NEW sculpt after a reveal must be hidden again, and a re-publish of the
     * SAME geometry must not re-veil a creature the child is already looking at. The hash is
     * {@code RigGeometryPrint.combine} ({@code RigReveal}).
     */
    public static boolean alreadyRevealed(String current, String revealed) {
        return current != null && !current.isBlank() && current.equalsIgnoreCase(revealed);
    }

    /**
     * <b>Every part currently hidden, in the order it was veiled, each with the game tick it was
     * veiled at.</b> Guarded; the server thread veils.
     *
     * <p>🔴 <b>Why the tick, and why it is still a ledger of ids.</b> ATOM-P3c: the reveal used to
     * un-hide everything at once, so a wish that put five creatures in the world while the child
     * could not see them ended with five appearing on the beat he is told to look (measured: 290
     * parts, four spare winged Spikors, {@code docs/img/atom-rig/veil/atom2-child-reveal-022406.png}).
     * The reveal has to tell the wish's LAST word on a creature from its scaffolding, and the only
     * thing here that can say so is WHEN — one long per id. It is deliberately not a rig id, a
     * sculpt id or a species: this class still knows nothing about any of those, and the reveal
     * reads the carrier and the species off the world, where they already live.
     *
     * <p>Grouping is by tick rather than by veil CALL because one command legitimately spawns a
     * herd: {@code /wishmob spawn spikor 5} is five rigs, five calls, one tick, and all five were
     * asked for. Five separate spawns four seconds apart are not.
     */
    private static final Map<Integer, Long> VEILED = new LinkedHashMap<>();

    /**
     * Record these parts as hidden, as of {@code gameTime}. Repeats are free — a re-veil of the
     * same rig is a no-op, and it keeps the FIRST tick, because a part re-offered by the per-tick
     * sweep has not become newer.
     *
     * @param gameTime {@code ServerLevel.getGameTime()} — the tick this rig appeared
     */
    public static synchronized void veil(Collection<Integer> entityIds, long gameTime) {
        for (int id : entityIds) {
            VEILED.putIfAbsent(id, gameTime);
        }
    }

    /**
     * <b>A rig reshaped IN PLACE is the wish's newest word on its creature</b>: its kept
     * displays are re-stamped with the tick of the reshape, as a respawn's new displays would be.
     * Otherwise they keep the tick they were first veiled at, and {@code RigVeilService.reveal} —
     * which keeps only each creature's newest-ticked rigs — would reap a copy reshaped beside a
     * copy that was respawned or spawned later. Only ids already veiled move; nothing is veiled
     * here that was not.
     */
    public static synchronized void restamp(Collection<Integer> entityIds, long gameTime) {
        for (int id : entityIds) {
            VEILED.computeIfPresent(id, (k, was) -> Math.max(was, gameTime));
        }
    }

    /** The ids to hide, as a snapshot the caller may iterate while the server thread runs on. */
    public static synchronized int[] veiled() {
        int[] out = new int[VEILED.size()];
        int i = 0;
        for (int id : VEILED.keySet()) {
            out[i++] = id;
        }
        return out;
    }

    public static synchronized boolean isVeiling() {
        return !VEILED.isEmpty();
    }

    /**
     * Everything is visible again; returns what was hidden — id to the tick it was veiled at, in
     * the order it was veiled — so the caller can decide what to re-send.
     *
     * <p>Clearing and reporting in one step on purpose: a reveal that cleared first and then asked
     * what to re-send would send nothing, and a reveal that asked first and cleared later could be
     * raced by a sculpt landing in between and leave a part hidden for ever.
     */
    public static synchronized Map<Integer, Long> unveil() {
        Map<Integer, Long> out = new LinkedHashMap<>(VEILED);
        VEILED.clear();
        return out;
    }

    /** A server stopping, or a test starting. */
    public static synchronized void forget() {
        VEILED.clear();
    }
}
