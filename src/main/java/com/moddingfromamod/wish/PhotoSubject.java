package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.moddingfromamod.marshal.MadeKind;
import com.moddingfromamod.marshal.WishRun;

/**
 * <b>What a photograph with no subject is OF</b>. Minecraft-free.
 *
 * <p>{@code finish_building}'s {@code subject} is optional and the agent usually leaves it out, so
 * {@code PhotographTool.capture} defaults it. The default it had was {@code Marshal.lastArtifactFor},
 * which walks this child's FINISHED runs — and the wish being photographed is not finished yet. So
 * on the archived BlockyBob grant (an 8 × 16 picture, {@code finish_building} called with
 * {@code say} and {@code claims} only) the default was empty, the board framing in
 * {@code PictureFraming} was never reached, and the shutter fired from where the child stood at
 * pitch 0: everything below the chest, the head above the frame.
 *
 * <p>On a child's second wish it was worse, and it happened (live 2026-10-03): an oasis
 * BUILD was photographed as the previous wish's dragon shark ({@code orbit: framing
 * 'dragon_shark'}), and a teleport as the Pegasus. The agent judges its own result from that
 * picture, and the oasis agent's verdict note placed its pool "behind the shark in photo".
 *
 * <p>So, while a run exists, the subject is ONLY ever something THIS wish made, and the last
 * finished artifact is never consulted:
 * <ol>
 *   <li>a picture this wish hung, newest first — its board frames it;</li>
 *   <li>an ITEM this wish made — its picture is its icon, drawn from the stack with no
 *       camera at all (a test server's wand and sword went to a camera for a snapshot of a
 *       field);</li>
 *   <li>otherwise the NEWEST framable thing it made — a build, a creature, a train, a machine, a
 *       changed mob ({@link #FRAMABLE}). {@code BuildRegistry}/{@code BuildFraming} already frame a
 *       build by its bounds; it was simply never given the id;</li>
 *   <li>otherwise "" — and the camera aims at the wish's own spot ({@code WishSpotShot}), never at
 *       anything an earlier wish left lying about. A teleport makes nothing; it is shot from where
 *       he now stands.</li>
 * </ol>
 * {@code lastFinished} answers only when there is no run at all (a test harness, a tool called
 * outside a wish).
 */
public final class PhotoSubject {

    /** Kinds a camera can find in the world by id. A datapack or an event is not one of them. */
    public static final Set<String> FRAMABLE = Set.of(
            MadeKind.BUILD, MadeKind.CREATURE, MadeKind.MOB,
            MadeKind.PAINTING, MadeKind.ITEM);

    private PhotoSubject() {
    }

    /**
     * @param thisWishNewestFirst what the wish being photographed has made, newest first
     *                            ({@code WishRun.made()}), or null when there is no run
     * @param lastFinished        the no-run default, {@code Marshal.lastArtifactFor}; may be null
     * @return the subject to aim at; "" when there is nothing of this wish's to aim at
     */
    public static String choose(List<WishRun.Made> thisWishNewestFirst, String lastFinished) {
        if (thisWishNewestFirst == null) {
            return lastFinished == null ? "" : lastFinished;
        }
        String picture = newest(thisWishNewestFirst, Set.of(MadeKind.PAINTING));
        if (picture != null) {
            return picture;
        }
        String item = newest(thisWishNewestFirst, Set.of(MadeKind.ITEM));
        if (item != null) {
            return item;
        }
        String any = newest(thisWishNewestFirst, FRAMABLE);
        return any == null ? "" : any;
    }

    private static String newest(List<WishRun.Made> made, Set<String> kinds) {
        for (WishRun.Made m : made) {
            if (m != null && kinds.contains(m.kind()) && m.id() != null && !m.id().isBlank()) {
                return m.id().trim();
            }
        }
        return null;
    }

    /**
     * <b>The detector (E): is this photograph of something this wish did not make?</b>
     *
     * <p>True only when there is a run, it made (or went to) something a camera could have
     * framed, it is not a follow-up about an earlier thing ({@code plan_wish} said so), and NONE
     * of {@code subjects} — the id as asked and as resolved, e.g. a handle and its species — is
     * one of those things. A wish that made nothing framable (a rule, a chat line) is never
     * flagged: there was nothing of its own to point at.
     *
     * @param thisWishNewestFirst {@code WishRun.made()}, or null when there is no run
     * @param also                more ids that count as this wish's (where a teleport went, the
     *                            species a changed mob's handle names); nulls ignored
     */
    public static boolean notThisWish(List<WishRun.Made> thisWishNewestFirst, List<String> also,
                                      boolean followup, String... subjects) {
        if (thisWishNewestFirst == null || followup) {
            return false;
        }
        List<String> mine = new ArrayList<>();
        for (WishRun.Made m : thisWishNewestFirst) {
            if (m != null && FRAMABLE.contains(m.kind()) && m.id() != null && !m.id().isBlank()) {
                mine.add(norm(m.id()));
            }
        }
        if (also != null) {
            for (String a : also) {
                if (a != null && !a.isBlank()) {
                    mine.add(norm(a));
                }
            }
        }
        if (mine.isEmpty()) {
            return false;
        }
        boolean asked = false;
        for (String s : subjects) {
            if (s == null || s.isBlank()) {
                continue;
            }
            asked = true;
            if (mine.contains(norm(s))) {
                return false;
            }
        }
        return asked;
    }

    private static String norm(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        // "moddingfromamod:dragon" and "dragon" are one species
        return colon >= 0 && !t.startsWith("@") ? t.substring(colon + 1) : t;
    }
}
