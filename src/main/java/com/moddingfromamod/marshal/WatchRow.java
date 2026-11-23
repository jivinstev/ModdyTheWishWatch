package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One row of the watch, as it travels from server to client.
 *
 * <p>The screen is drawn on the CLIENT and everything it needs to draw lives on the SERVER — the
 * artifacts, what is in flight, the numbers on a card. So it all has to cross, and this is the
 * shape it crosses in.
 *
 * <p><b>Encoded as a delimited string rather than a wide codec</b>, deliberately. A card carries a
 * dozen fields, and {@code StreamCodec.composite} is both capped in arity and unreadable at that
 * width; a record whose encoding is a pure function of itself can instead be round-tripped in a
 * unit test, which is where a field silently dropped in transit would otherwise first be noticed —
 * on a screen, as a blank.
 *
 * <p>The separator is a control character no child can type and no name can contain, so a creature
 * called "Spikor | King" cannot break the row it rides in.
 */
public final class WatchRow {

    /** Unit Separator. Not typeable, not in any name, so it can never appear in a field. */
    public static final String SEP = "\u001f";

    /** Record Separator, for lists within a field (aliases, powers). */
    public static final String SUB = "\u001e";

    private WatchRow() {
    }

    /** A wish being granted right now. */
    public record Happening(String shortId, String subject, int percent, String kind) {

        public Happening {
            shortId = clean(shortId);
            subject = clean(subject);
            kind = clean(kind);
            percent = Math.max(0, Math.min(100, percent));
        }

        public String encode() {
            return String.join(SEP, shortId, subject, String.valueOf(percent), kind);
        }

        public static Happening decode(String row) {
            String[] f = split(row, 4);
            return new Happening(f[0], f[1], intOr(f[2]), f[3]);
        }
    }

    /**
     * One of his creations, with everything the card shows.
     *
     * @param vitals exactly four, already rendered with their units — "❤️ 300 hearts". The client
     *     does no arithmetic; a number formatted on one side and reformatted on the other is how
     *     the two disagree.
     */
    /**
     * @param recipeId   the learned recipe that can remake this instantly, or "" if there isn't one
     * @param seedPhrase the words guaranteed to match it, for seeding his wish box
     */
    public record Creation(String id, String kind, String name, List<String> aliases,
                           List<String> vitals, String tier, List<String> powers,
                           String intent, int timesWished, boolean inFlight,
                           List<String> photos, String recipeId, String seedPhrase, String icon,
                           long updatedAt, List<String> partOf) {

        /** A creation that is nobody's part. */
        public Creation(String id, String kind, String name, List<String> aliases,
                        List<String> vitals, String tier, List<String> powers,
                        String intent, int timesWished, boolean inFlight,
                        List<String> photos, String recipeId, String seedPhrase, String icon,
                        long updatedAt) {
            this(id, kind, name, aliases, vitals, tier, powers, intent, timesWished, inFlight,
                    photos, recipeId, seedPhrase, icon, updatedAt, List.of());
        }

        /** A creation with no known time: it sorts after everything that has one. */
        public Creation(String id, String kind, String name, List<String> aliases,
                        List<String> vitals, String tier, List<String> powers,
                        String intent, int timesWished, boolean inFlight,
                        List<String> photos, String recipeId, String seedPhrase, String icon) {
            this(id, kind, name, aliases, vitals, tier, powers, intent, timesWished, inFlight,
                    photos, recipeId, seedPhrase, icon, 0L);
        }

        /** A creation with no icon of its own: the panel falls back to its KIND. */
        public Creation(String id, String kind, String name, List<String> aliases,
                        List<String> vitals, String tier, List<String> powers,
                        String intent, int timesWished, boolean inFlight,
                        List<String> photos, String recipeId, String seedPhrase) {
            this(id, kind, name, aliases, vitals, tier, powers, intent, timesWished, inFlight,
                    photos, recipeId, seedPhrase, "");
        }

        /** The same creation, showing this icon (a {@code WatchIcons} spelling). */
        public Creation withIcon(String newIcon) {
            return new Creation(id, kind, name, aliases, vitals, tier, powers, intent, timesWished,
                    inFlight, photos, recipeId, seedPhrase, newIcon, updatedAt, partOf);
        }

        /** The same creation, a PART of these wishes — their names, newest first. */
        public Creation withPartOf(List<String> wishes) {
            return new Creation(id, kind, name, aliases, vitals, tier, powers, intent, timesWished,
                    inFlight, photos, recipeId, seedPhrase, icon, updatedAt, wishes);
        }

        /** The same creation, last changed at {@code ms} — what newest-first sorts on. */
        public Creation withUpdatedAt(long ms) {
            return new Creation(id, kind, name, aliases, vitals, tier, powers, intent, timesWished,
                    inFlight, photos, recipeId, seedPhrase, icon, ms, partOf);
        }

        public Creation {
            icon = clean(icon);
            id = clean(id);
            kind = clean(kind);
            name = clean(name);
            tier = clean(tier);
            intent = clean(intent);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            vitals = vitals == null ? List.of() : List.copyOf(vitals);
            powers = powers == null ? List.of() : List.copyOf(powers);
            photos = photos == null ? List.of() : List.copyOf(photos);
            recipeId = clean(recipeId);
            // Never blank: the box has to be seeded with SOMETHING he can type after, and his own
            // name for the thing is the next best guarantee after the recipe's own terms.
            seedPhrase = clean(seedPhrase).isEmpty() ? clean(name) : clean(seedPhrase);
            timesWished = Math.max(1, timesWished);
            updatedAt = Math.max(0L, updatedAt);
            partOf = partOf == null ? List.of() : List.copyOf(partOf);
        }

        public String encode() {
            return String.join(SEP, id, kind, name, joinList(aliases), joinList(vitals), tier,
                    joinList(powers), intent, String.valueOf(timesWished), inFlight ? "1" : "0",
                    joinList(photos), recipeId, seedPhrase, icon, String.valueOf(updatedAt),
                    joinList(partOf));
        }

        // Appended at the END so a row written by an older build still decodes — split() pads.
        public static Creation decode(String row) {
            String[] f = split(row, 16);
            return new Creation(f[0], f[1], f[2], splitList(f[3]), splitList(f[4]), f[5],
                    splitList(f[6]), f[7], intOr(f[8]), "1".equals(f[9]), splitList(f[10]),
                    f[11], f[12], f[13], longOr(f[14]), splitList(f[15]));
        }

        /** Can he press one button and get another one of these, with no waiting? */
        public boolean canRemake() {
            return !recipeId.isEmpty();
        }

        /**
         * Exactly four, always — a short list is padded rather than drawn short.
         *
         * <p>A hole in a fixed grid reads as a bug, not as an absence, which is why the contract
         * says every class fills all four slots and an absent figure renders as a dash.
         */
        public List<String> fourVitals() {
            List<String> four = new ArrayList<>(vitals);
            while (four.size() < WishCardContract.VITALS) {
                four.add(WishCardContract.ABSENT);
            }
            return four.subList(0, WishCardContract.VITALS);
        }
    }

    // ---- the plumbing --------------------------------------------------------------------

    /**
     * Strip the separators out of anything going in.
     *
     * <p>Belt and braces: they are untypeable, but an agent authoring a name is not a child
     * typing, and one stray character would shift every field after it — which reads as the card
     * showing another creature's numbers.
     */
    private static String clean(String s) {
        return s == null ? "" : s.replace(SEP, " ").replace(SUB, " ").strip();
    }

    private static String joinList(List<String> items) {
        List<String> cleaned = new ArrayList<>();
        for (String s : items) {
            String c = clean(s);
            if (!c.isEmpty()) {
                cleaned.add(c);
            }
        }
        return String.join(SUB, cleaned);
    }

    private static List<String> splitList(String field) {
        if (field == null || field.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : field.split(java.util.regex.Pattern.quote(SUB), -1)) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Split to exactly {@code fields}, padding what is missing.
     *
     * <p>Padding rather than throwing: a row from an older or newer build must draw as much as it
     * can. A screen that refuses to open because one field moved is worse than a card with a blank
     * on it, and the child cannot tell either way what went wrong.
     */
    private static String[] split(String row, int fields) {
        String[] parts = (row == null ? "" : row)
                .split(java.util.regex.Pattern.quote(SEP), -1);
        String[] out = new String[fields];
        Arrays.fill(out, "");
        System.arraycopy(parts, 0, out, 0, Math.min(parts.length, fields));
        return out;
    }

    private static long longOr(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static int intOr(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
