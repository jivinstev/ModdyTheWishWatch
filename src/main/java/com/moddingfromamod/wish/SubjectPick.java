package com.moddingfromamod.wish;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Which of several matching creatures a photograph frames</b> (follow-up). Minecraft-free.
 *
 * <p>{@code PhotographTool.findSubject} framed the creature of that species NEAREST THE VIEWPOINT,
 * anywhere in the level. A camera VM parks wherever its last take left it, so "nearest the camera"
 * is a fact about the camera's history, not about this wish — the same shape as the fallback that
 * framed the previous wish's dragon shark. So, in order:
 * <ol>
 *   <li>a carrier a LIVE wish spawned ({@code WishRun.creaturesSpawned}) — the one it made;</li>
 *   <li>else the one nearest a live wish's anchor, if within {@link #NEAR_ANCHOR} blocks;</li>
 *   <li>else the nearest to the viewer, as before (a re-sculpt of a creature standing elsewhere).</li>
 * </ol>
 */
public final class SubjectPick {

    /** How close to a wish's spot a creature must stand to count as "at" it. */
    public static final double NEAR_ANCHOR = 64.0;

    /** One matching creature: its entity id, where it stands, and its squared distance to the viewer. */
    public record Candidate(UUID id, double x, double y, double z, double distSqToViewer) {
    }

    private SubjectPick() {
    }

    /**
     * @param spawned carriers live wishes spawned for this subject; may be empty
     * @param anchors live wishes' spots for this subject, as {x, y, z}, in the viewer's dimension
     * @return the index of the chosen candidate, or -1 when there are none
     */
    public static int pick(List<Candidate> cs, Set<UUID> spawned, List<double[]> anchors) {
        if (cs == null || cs.isEmpty()) {
            return -1;
        }
        int best = -1;
        if (spawned != null && !spawned.isEmpty()) {
            for (int i = 0; i < cs.size(); i++) {
                if (spawned.contains(cs.get(i).id())
                        && (best < 0 || cs.get(i).distSqToViewer() < cs.get(best).distSqToViewer())) {
                    best = i;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        if (anchors != null && !anchors.isEmpty()) {
            double bestD = NEAR_ANCHOR * NEAR_ANCHOR;
            for (int i = 0; i < cs.size(); i++) {
                Candidate c = cs.get(i);
                for (double[] a : anchors) {
                    double dx = c.x() - a[0];
                    double dy = c.y() - a[1];
                    double dz = c.z() - a[2];
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d <= bestD) {
                        bestD = d;
                        best = i;
                    }
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        for (int i = 0; i < cs.size(); i++) {
            if (best < 0 || cs.get(i).distSqToViewer() < cs.get(best).distSqToViewer()) {
                best = i;
            }
        }
        return best;
    }
}
