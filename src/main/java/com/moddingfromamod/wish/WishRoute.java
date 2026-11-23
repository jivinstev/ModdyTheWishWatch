package com.moddingfromamod.wish;

/**
 * <b>Who answers a typed wish: the Wish Magician's agent, or the Apprentice</b>. Decided HERE and nowhere
 * else, and asked once, at the submit choke point ({@link WishService}), so the watch's wish box and
 * {@code /wish <words>} cannot drift apart: both are just words arriving at that one place.
 *
 * <table>
 *   <tr><th>who</th><th>default</th><th>can choose</th></tr>
 *   <tr><td>a Wish Crafter (a Visitor with no account included)</td><td>Apprentice</td><td>nothing</td></tr>
 *   <tr><td>a Wish Magician</td><td>Magician</td><td>Apprentice: the Magic dropdown on his wish box, his watch's toggle (the same
 *       setting), or {@code /moddy apprentice <words>}</td></tr>
 * </table>
 *
 * <b>While Magician mode is off</b> (the public default) every typed wish goes to the Apprentice: there is no
 * agent to answer it, and nothing may start one.
 *
 * <p>A Magician's QUESTION ("how do I get sand?") is the agent's to answer even with his toggle on Apprentice: the
 * Apprentice only finds makes in Moddy's Parts. Asking for the Apprentice by name wins over that.
 *
 * <p>Pure: {@code WishRouteTest} walks every combination.
 */
public final class WishRoute {

    public enum Route { MAGICIAN, APPRENTICE }

    /** What the caller asked for: nothing in particular, the Apprentice by name, or the Magician by name. */
    public enum Hint {
        /** A wish box or {@code /wish}: the role and the toggle decide. */
        AUTO,
        /** {@code /moddy apprentice <words>}. */
        APPRENTICE,
        /** The Apprentice page's "Ask Moddy instead" tile: these words go to the agent, whatever the toggle says. */
        MAGICIAN
    }

    private WishRoute() {
    }

    /**
     * @param role             who he is on this world
     * @param prefersApprentice his watch's toggle (only a Magician has one)
     * @param hint             what this one wish asked for
     * @param question         the words read as a question
     */
    public static Route choose(Crafters.Role role, boolean prefersApprentice, Hint hint, boolean question) {
        return choose(true, role, prefersApprentice, hint, question);
    }

    /** @param magicianOn is Magician mode on (or its check running)? Off: the Apprentice answers everything. */
    public static Route choose(boolean magicianOn, Crafters.Role role, boolean prefersApprentice, Hint hint,
                               boolean question) {
        if (!magicianOn) {
            return Route.APPRENTICE;
        }
        if (role == Crafters.Role.CRAFTER) {
            return Route.APPRENTICE;   // whatever was asked: a Crafter has no door to the agent
        }
        if (hint == Hint.APPRENTICE) {
            return Route.APPRENTICE;
        }
        if (hint == Hint.MAGICIAN || question) {
            return Route.MAGICIAN;
        }
        return prefersApprentice ? Route.APPRENTICE : Route.MAGICIAN;
    }
}
