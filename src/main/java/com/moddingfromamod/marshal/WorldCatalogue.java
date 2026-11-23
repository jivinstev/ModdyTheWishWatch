package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Everything the child owns, rendered for the agent — unfiltered (item 3).</b>
 *
 * <p>an earlier issue's finding is that the referent decision belongs to the model rather than to the local
 * ladder, and that the cost objection does not survive measurement: <i>"48 artifacts in his world
 * today; compact catalogue (kind:name + aliases) = 1110 chars ≈ 277 tokens"</i>, against a wish
 * that takes 117 s at p50 and costs $2.21–$5.29. So the model can be handed the whole world for
 * about a paragraph, and this is that paragraph.
 *
 * <h2>Why this is NOT {@code ArtifactIndex.forPlayer}, which already builds a list</h2>
 *
 * <p>That list is <b>already narrowed, twice</b>, and both narrowings are correct for the job it
 * does and wrong for this one:
 *
 * <ul>
 *   <li>{@code add()} drops anything past {@code CONSIDER_RADIUS} (512 blocks) — <i>"not one of the
 *       things he might mean; it is somewhere else entirely"</i>;</li>
 *   <li>changed mobs are capped at {@code MAX_MOB_CANDIDATES} (6) — <i>"a long list of animals makes
 *       every rung below NAMED harder"</i>.</li>
 * </ul>
 *
 * <p>Both exist to make a <b>local ambiguity decision</b>, and says so in as many words:
 * <i>"Those narrowing rules exist only to make a local ambiguity decision."</i> Handing the agent
 * the ladder's list instead of the world would ship that narrowing into the delivery — which is
 * an earlier issue's bug arriving by a second route, and it fails exactly where needs it most: the
 * recorded failure is <b>a new wish read as a modification</b>, and to rule that out the model has
 * to know what exists <i>anywhere</i>, not what happens to be within 512 blocks of where the child
 * is standing when he speaks.
 *
 * <h2>There is NO ceiling, and that is deliberate</h2>
 *
 * <p>The first cut of this carried a 4000-character budget "so a pathological world cannot blow the
 * note", sized at 3.6× the only world anyone has measured. That was the cart before the horse and
 * it is gone. Three reasons, in order of how much they cost:
 *
 * <ul>
 *   <li><b>The problem it guards against has never been observed.</b> The one real measurement is
 *       an earlier issue's 48 artifacts at 1110 chars — about a paragraph. A cap set against a world nobody
 *       has seen is a number invented to feel safe, which is exactly what {@code
 *       docs/WISH_LINTERS.md} P9 forbids: <i>derive the threshold from real archived data, never
 *       choose it.</i></li>
 *   <li><b>Truncating this is worse than the cost it saves.</b> The agent reads the list, sees the
 *       child's thing is not on it, and concludes he is asking for something NEW — which is an earlier issue's
 *       recorded failure, arriving through the fix for it. A disclosure line saying "N more not
 *       shown" mitigates that; it does not remove it, because the agent still has to spend a turn
 *       finding out, and turns are what are about.</li>
 *   <li><b>Token cost is not the constraint anyone measured.</b> 277 tokens against a wish that
 *       costs $2.21–$5.29 and takes 117 s at p50. If a world ever gets big enough for this to
 *       matter, the answer is a measurement and then a decision about WHICH things to leave out
 *       — not a character count that drops whatever happens to sort last.</li>
 * </ul>
 *
 * <p>So {@link #render} lists everything it is given, and {@code Rendered.dropped()} is permanently
 * zero. The field is kept rather than deleted so that a future cap, if one is ever justified by a
 * real world, cannot be added without a caller noticing it is now non-zero.
 *
 * <h2>Order is the store's own, and no recency is claimed</h2>
 *
 * <p>The obvious render puts the most recent last, the way {@code list_items} does. It is not done
 * here because it would be a lie: {@code ArtifactIndex} stamps {@code lastTouchedMs} on changed
 * mobs and passes literal {@code 0L} for buildings, machines, trains and items, so sorting by it
 * would order four of five kinds arbitrarily while looking authoritative. The order is whatever the
 * stores gave, and the text does not claim otherwise.
 *
 * <p>Minecraft-free on purpose, so the rendering rules are unit-testable without booting a server.
 */
public final class WorldCatalogue {

    /**
     * One thing he owns. Aliases are what it also answers to.
     *
     * <p>{@code ownerId} is the stamp and is <b>not rendered</b>. The catalogue's job is an earlier issue's:
     * tell the agent what EXISTS, so that a new wish is not read as a modification — and that
     * question is about the world, not about one child. What the owner is for is
     * {@link #ownFirst}: when the list has to be ordered, his own things come first.
     */
    public record Entry(String id, String kind, String name, List<String> aliases, String ownerId) {
        public Entry {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            ownerId = ArtifactOwner.normalise(ownerId);
        }

        /** An entry nobody owns — the pre-HD-7 shape, and every shared artifact. */
        public Entry(String id, String kind, String name, List<String> aliases) {
            this(id, kind, name, aliases, "");
        }
    }

    /**
     * The same entries, <b>this child's own first</b>, then shared, then everyone else's.
     *
     * <p>A stable partition, not a narrowing: nothing is dropped, because the agent reading this
     * has to know what exists in order to tell a NEW wish from a modification, and hiding a
     * brother's castle is how "build me a castle" becomes a second castle three feet from the
     * first one. Ordering is the whole intervention — the model reads a list top-down, and his own
     * things being at the top is what "my Spikor" should land on.
     */
    public static List<Entry> ownFirst(String askerId, List<Entry> entries) {
        if (entries == null || entries.size() < 2
                || ArtifactOwner.normalise(askerId).isEmpty()) {
            return entries == null ? List.of() : entries;
        }
        List<Entry> out = new ArrayList<>(entries.size());
        for (int rank = 0; rank <= 2; rank++) {
            for (Entry e : entries) {
                if (ArtifactOwner.rank(askerId, e.ownerId()) == rank) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /**
     * A rendered catalogue.
     *
     * @param text    what to hand the agent, or "" when he owns nothing
     * @param listed  how many entries the text names — always every entry it was given
     * @param dropped <b>always 0.</b> Nothing is dropped; see the class note on why there is no
     *                ceiling. Kept as a field so that adding one later is a visible change to
     *                everything that reads this, rather than a quiet edit to a constant
     */
    public record Rendered(String text, int listed, int dropped) {
        public boolean isEmpty() {
            return text.isEmpty();
        }
    }

    /**
     * What the "out in the world" marks mean for a wish, said once under the list. Pinned in
     * {@code AgentKnowsTheTrapsTest}.
     */
    public static final String OUT_RULE = "A wish that ACTS ON one of these — remove it, hit it, ride "
            + "it, feed it, take him to it, make it bigger or better — can only mean one that is OUT IN "
            + "THE WORLD (or standing). A \"design only\" one is not there to act on. A wish for ANOTHER "
            + "one (\"make me another\", \"spawn my X again\") may use any design, out or not.";

    private WorldCatalogue() {
    }

    /**
     * Render the whole world. Nothing is omitted, for any reason — see the class note.
     */
    public static Rendered render(List<Entry> entries) {
        return render(entries, e -> InWorld.NOT_A_THING);
    }

    /**
     * Render the whole world, each creature and building marked with whether it is OUT in the world
     * now — "1 out in the world", "design only", "standing". Still nothing is omitted: a
     * design that is not out is exactly what "make me another" needs to find. The mark is what a
     * wish to hit, ride or remove one needs, because only one that is out there can be what he means.
     */
    public static Rendered render(List<Entry> entries, java.util.function.Function<Entry, InWorld> inWorld) {
        List<Entry> all = entries == null ? List.of() : entries;
        if (all.isEmpty()) {
            return new Rendered("", 0, 0);
        }
        boolean marked = false;
        List<String> lines = new ArrayList<>();
        for (Entry e : all) {
            InWorld w = inWorld == null ? InWorld.NOT_A_THING : inWorld.apply(e);
            String mark = w == null ? "" : w.describe(e.kind());
            marked |= !mark.isEmpty();
            lines.add(mark.isEmpty() ? line(e) : line(e) + " — " + mark);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("EVERYTHING HE ALREADY OWNS (").append(all.size())
          .append(all.size() == 1 ? " thing" : " things")
          .append("). This is the whole list, not the things near him — so if his words point at "
                  + "one of these, he is asking you to CHANGE it, and if they do not, he is asking "
                  + "for something NEW. That call is yours:\n");
        for (String line : lines) {
            sb.append("  ").append(line).append('\n');
        }
        if (marked) {
            sb.append(OUT_RULE).append('\n');
        }
        return new Rendered(sb.toString(), lines.size(), 0);
    }

    private static String line(Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.kind() == null || e.kind().isBlank() ? "thing" : e.kind())
          .append(' ').append(e.id());
        String name = e.name();
        if (name != null && !name.isBlank() && !name.equalsIgnoreCase(e.id())) {
            sb.append(" \"").append(name).append('"');
        }
        List<String> extra = new ArrayList<>();
        for (String a : e.aliases()) {
            if (a != null && !a.isBlank() && !a.equalsIgnoreCase(e.id())
                    && !a.equalsIgnoreCase(name) && !extra.contains(a)) {
                extra.add(a);
            }
        }
        if (!extra.isEmpty()) {
            sb.append(" (also: ").append(String.join(", ", extra)).append(')');
        }
        return sb.toString();
    }
}
