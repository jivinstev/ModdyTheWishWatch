package com.moddingfromamod.wish;

import java.util.List;

/**
 * Which method sent a chat line, named from the call stack — pure, so it is unit-tested.
 *
 * <p>Two names, because a duplicate needs both: the {@code WishFeedback} method the caller asked
 * for ({@code planned}, {@code verdictDone} — the outermost one, so a verdict's lines all name it) and the first frame outside it that asked for them
 * ({@code PlanWishTool.call}). "Here's my plan" twice from one caller is a tool called twice; from
 * two callers it is two paths announcing one plan.
 */
public final class SaidSite {
    private static final String HELPER = "WishFeedback";
    private static final List<String> PLUMBING = List.of("tell", "show", "record", "site");

    private SaidSite() {
    }

    /**
     * @param frames the stack, innermost first, as {@code SimpleClassName.method}
     * @return {@code "WishFeedback.planned ← PlanWishTool.call"}, or what of it could be found
     */
    public static String describe(List<String> frames) {
        String said = null;
        for (String f : frames) {
            int dot = f.lastIndexOf('.');
            String cls = dot < 0 ? f : f.substring(0, dot);
            String method = dot < 0 ? "" : f.substring(dot + 1);
            boolean helper = cls.equals(HELPER) || cls.startsWith(HELPER + "$");
            if (helper) {
                // The OUTERMOST feedback method wins: it is the one the caller asked for. verdictDone
                // writes its first line through granted(), and both lines name verdictDone.
                if (!PLUMBING.contains(method) && !method.startsWith("lambda$")) {
                    said = HELPER + "." + method;
                }
                continue;
            }
            if (said != null) {
                return said + " ← " + f;
            }
        }
        return said == null ? "?" : said;
    }
}
