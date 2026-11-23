package com.moddingfromamod.provenance;

/**
 * The commit message a published wish gets, including WHICH WORLD it happened in —.
 *
 * <p>Pure, so the format is a unit test rather than something you need a git repo to check.
 *
 * <h2>Why the trailer exists</h2>
 *
 * <p> was filed as <i>"Spikor got worse over 6 revisions and nothing noticed"</i> and closed as a
 * misfile: it was <b>four independent creations in four different worlds</b>, quality flat inside
 * every world and changing only at the boundaries.
 *
 * <p>The wrong conclusion was <b>unavoidable rather than careless</b>, and this is the half of that
 * which lives here: the seven versions were recovered from the publisher's <b>git log</b>, where
 * four worlds flatten into one linear history — <i>which looks like iteration by construction</i>.
 * The action log learned to stamp world and sitting; the commits, which are the source those
 * analyses are actually recovered from, did not.
 *
 * <p>Git trailers are the right shape for this: {@code git log --format=%(trailers:key=World)}
 * reads them back without parsing prose, and a reader skimming the log sees the world without
 * being told to look for it.
 */
public final class ProvenanceCommitMessage {

    private ProvenanceCommitMessage() {}

    /**
     * The message for one published wish.
     *
     * <p>A blank world or sitting is <b>omitted rather than written as "null"</b>, the same rule
     * {@code ActionLog} already follows — at the title screen there is no world, and a literal
     * {@code null} in a trailer is a value a later reader has to learn to distrust.
     */
    public static String of(String kind, String id, String world, String session) {
        StringBuilder sb = new StringBuilder("Moddy wish (" + kind + "): " + id);
        String w = world == null ? "" : world.strip();
        String s = session == null ? "" : session.strip();
        if (!w.isEmpty() || !s.isEmpty()) {
            sb.append("\n\n");
            if (!w.isEmpty()) {
                sb.append("World: ").append(w).append('\n');
            }
            if (!s.isEmpty()) {
                sb.append("Session: ").append(s).append('\n');
            }
        }
        return sb.toString();
    }
}
