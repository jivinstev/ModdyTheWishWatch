package com.moddingfromamod.mob;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.KidTyping;

/**
 * <b>"Which creature do you mean?" — one answer, for every tool ().</b>
 *
 * <p> deleted {@code WishMobEntity}. A wished creature is now an invisible VANILLA carrier (a
 * llama, a horse, an iron golem…) wearing {@code item_display} parts, plus — for a long creature —
 * a few invisible hit-proxy mobs so a sword reaches its tail. The code that found "the creature"
 * still matched the entity's registry TYPE, which for a carrier is a fact about the carrier. So in
 * production:
 * <ul>
 *   <li>{@code modify_mob {target:"spikor"}} answered <i>"No mob matching spikor … Nearby:
 *       minecraft:llama"</i>;</li>
 *   <li>the only thing that reached him was {@code {target:"llama", scope:"group"}}, which also
 *       reaches every REAL llama the child owns;</li>
 *   <li>hit proxies — real mobs — were offered as candidates and could be minted handles;</li>
 *   <li>and a creature listed under its species AND under its carrier's handle was two referents
 *       for one animal.</li>
 * </ul>
 *
 * <p>This class is the whole decision, and it is Minecraft-free so every rule is a unit test
 * ({@code CreatureMatchTest}). The Minecraft side ({@link Creatures}) only reads the four facts a
 * {@link Seen} carries off an entity and executes the answer. Its rules:
 * <ol>
 *   <li><b>A rig's internals are never anything.</b> A hit proxy is not a creature, not a mob he
 *       can address and not a thing to mint a handle for. (Display parts are not living, so no
 *       living-entity query ever sees them; the tag test covers both anyway.)</li>
 *   <li><b>A wished creature answers to its own names</b> — the species id ({@code spikor}), its
 *       display name ({@code "§6SPIKOR"}), its species tag ({@code wishmob_spikor}, also inside a
 *       selector {@code @e[tag=wishmob_spikor]}), {@code wishmob} for "any of mine", and a name
 *       the child gave it — and <b>never to its carrier's vanilla type</b>.</li>
 *   <li><b>A real mob answers to its type</b>, as before, and never to a creature's tag.</li>
 * </ol>
 * So "llama" means real llamas only, and "spikor" means Spikor only, whatever he is riding on.
 */
public final class CreatureMatch {

    /** Every hit proxy wears this (mirrored by {@code HitProxies.PROXY_TAG}, which reads it). */
    public static final String PROXY_TAG = "mfamrigpx";

    /** Every rig carrier wears this (mirrored by {@code DisplayRig.RIG_TAG}, which reads it). */
    public static final String CARRIER_TAG = "mfamrig";

    /**
     * The namespace a creature's KIND is spelled in, e.g. {@code wishmob:spikor} — what a handle
     * records as its type, so a handle's kind is the creature and never the carrier. Chosen so
     * {@link EntityHandles}'s name for it is {@code @spikor}.
     */
    public static final String KIND_NAMESPACE = "wishmob";

    private CreatureMatch() {
    }

    /** What an entity is, for this question. */
    public enum What {
        /** An ordinary mob: a cow, a zombie, a boss from another mod. Found by its type. */
        REAL,
        /** A creature a wish made. Found by its own names, never by its carrier's type. */
        CREATURE,
        /** Part of a creature's machinery (a hit proxy, a display). Never found at all. */
        INTERNAL
    }

    /**
     * The facts about one entity that decide it, read off by {@link Creatures#seen}.
     *
     * @param typeId      its registry type, e.g. {@code minecraft:llama}
     * @param speciesId   the species the carrier wears, or null (not a carrier, or unknown species)
     * @param speciesName that species' display name, or null
     * @param tags        its scoreboard tags
     * @param customName  the name above its head, or null
     */
    public record Seen(String typeId, String speciesId, String speciesName,
                       Collection<String> tags, String customName) {

        public Seen {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** Is this tag set a rig's machinery rather than something a child could mean? */
    public static boolean isRigInternal(Collection<String> tags) {
        return tags != null && tags.contains(PROXY_TAG);
    }

    public static What what(Seen s) {
        if (s == null || isRigInternal(s.tags())) {
            return What.INTERNAL;
        }
        if (s.speciesId() != null && !s.speciesId().isBlank()) {
            return What.CREATURE;
        }
        // A carrier whose species this server does not know (a deleted design, a world from
        // another server) is still a creature: matching it by its llama-ness would be again.
        if (s.tags().contains(CARRIER_TAG) || s.tags().contains(WishMobTags.ALL)) {
            return What.CREATURE;
        }
        return What.REAL;
    }

    /**
     * <b>Does this entity answer to what the agent typed?</b> A blank query means "the nearest of
     * anything" and matches every entity that is not a rig's internals.
     */
    public static boolean matches(Seen s, String query) {
        What what = what(s);
        if (what == What.INTERNAL) {
            return false;
        }
        String q = query == null ? "" : unselect(query.trim());
        if (q.isBlank()) {
            return true;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        boolean tagWord = lower.equals(WishMobTags.ALL) || lower.startsWith(WishMobTags.PREFIX);
        if (what == What.REAL) {
            // A creature's tag is never a real mob's name, even where the fuzzy type match would
            // find "wishmob_cow" inside minecraft:cow.
            return !tagWord && ContentSearch.matches(s.typeId(), q);
        }
        if (lower.equals(WishMobTags.ALL)) {
            return true;                                  // "any creature he wished for"
        }
        if (lower.startsWith(WishMobTags.PREFIX) && s.tags().contains(lower)) {
            return true;                                  // the species tag, verbatim
        }
        String asTag = WishMobTags.forSpecies(q);
        if (!WishMobTags.ALL.equals(asTag) && s.tags().contains(asTag)) {
            return true;                                  // "Spikor", "spikor", "SPIKOR!"
        }
        if (tagWord) {
            return false;   // a tag this creature does not wear is somebody else's tag
        }
        return matchesName(s.speciesId(), q)
                || matchesName(s.speciesName(), q)
                || matchesName(s.customName(), q);
        // …and deliberately NOT s.typeId(): that is the carrier, and "llama" is a real llama.
    }

    private static boolean matchesName(String name, String q) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return ContentSearch.matches(KidTyping.plain(name).toLowerCase(Locale.ROOT), q);
    }

    /** A target selector ({@code @e[…]}) rather than a handle ({@code @spikor}). */
    public static boolean isSelector(String q) {
        return q != null && q.trim().startsWith("@") && q.contains("[");
    }

    /**
     * {@code @e[tag=wishmob_spikor,limit=1]} → {@code wishmob_spikor}. The creature tools TELL the
     * agent that selector ({@code WishMobTags.selector}), so a target written that way has to
     * mean what it says rather than fail a fuzzy match on its punctuation.
     */
    static String unselect(String q) {
        if (!isSelector(q)) {
            return q;
        }
        int at = q.indexOf("tag=");
        if (at < 0) {
            return q;
        }
        int end = at + 4;
        while (end < q.length() && q.charAt(end) != ',' && q.charAt(end) != ']') {
            end++;
        }
        return q.substring(at + 4, end).trim();
    }

    /**
     * What KIND of thing this is, in the spelling a handle records — {@code wishmob:spikor} for a
     * creature (so its handle is {@code @spikor} and a later "llama" never reaches it), the
     * registry type for a real mob, and null for a rig's internals, which get no handle.
     */
    public static String kindId(Seen s) {
        switch (what(s)) {
            case INTERNAL:
                return null;
            case CREATURE: {
                String body = speciesWord(s);
                return KIND_NAMESPACE + ":" + (body.isEmpty() ? "creature" : body);
            }
            default:
                return s.typeId();
        }
    }

    /** Is this kind id a wished creature's ({@link #kindId})? */
    public static boolean isCreatureKind(String kindId) {
        return kindId != null && kindId.startsWith(KIND_NAMESPACE + ":");
    }

    /** The species word a creature kind names: {@code wishmob:spikor} → {@code spikor}. */
    public static String speciesOfKind(String kindId) {
        return isCreatureKind(kindId) ? kindId.substring(KIND_NAMESPACE.length() + 1) : null;
    }

    /**
     * What to call it in prose: the name above its head, else a creature's species name. Null for
     * a real mob with no name (the handle then names it after its type, as before).
     */
    public static String label(Seen s) {
        if (s == null) {
            return null;
        }
        if (s.customName() != null && !s.customName().isBlank()) {
            return KidTyping.plain(s.customName());
        }
        if (what(s) == What.CREATURE) {
            String name = s.speciesName() != null && !s.speciesName().isBlank()
                    ? s.speciesName() : s.speciesId();
            return name == null || name.isBlank() ? null : KidTyping.plain(name);
        }
        return null;
    }

    /**
     * How a list the agent READS names this entity: a creature by its species word (the word
     * {@link #matches} accepts), a real mob by its type. Never a creature by its carrier.
     */
    public static String word(Seen s) {
        if (what(s) == What.CREATURE) {
            String body = speciesWord(s);
            return body.isEmpty() ? WishMobTags.ALL : body;
        }
        return s == null ? "" : s.typeId();
    }

    /** {@code spikor} from the species id, or from the species tag the carrier wears. */
    private static String speciesWord(Seen s) {
        if (s.speciesId() != null && !s.speciesId().isBlank()) {
            String tag = WishMobTags.forSpecies(s.speciesId());
            return WishMobTags.ALL.equals(tag) ? "" : tag.substring(WishMobTags.PREFIX.length());
        }
        for (String t : s.tags()) {
            if (WishMobTags.isSpeciesTag(t)) {
                return t.substring(WishMobTags.PREFIX.length());
            }
        }
        return "";
    }

    // ── whose it is ─────────────────────────────────────────────────────────────────────

    /**
     * The tag a creature wears naming the child whose wish (or spawn egg) put it in the world:
     * {@code mfamby_<his short id>}. A tag, so it is saved with the creature and carried across a
     * rig rebuild like every other per-animal fact ({@code CarrierState}). It never changes hands:
     * when another child's change makes the creature his own copy, who SPAWNED it stays true, and
     * the design's owner is the fork's.
     */
    public static final String SPAWNER_PREFIX = "mfamby_";

    /** The tag for {@code playerId}, or {@code ""} when there is nobody to name. */
    public static String spawnerTag(String playerId) {
        String who = com.moddingfromamod.marshal.ArtifactOwner.shortIdOf(playerId);
        return who.isEmpty() ? "" : SPAWNER_PREFIX + who;
    }

    /** Was this creature spawned for {@code playerId}? False for nobody, on either side. */
    public static boolean spawnedBy(Collection<String> tags, String playerId) {
        if (tags == null) {
            return false;
        }
        // Step 8: a creature spawned before his id moved to his key wears the tag of the OLD id.
        for (String id : com.moddingfromamod.net.PlayerIdentity.idsOf(playerId)) {
            String tag = spawnerTag(id);
            if (!tag.isEmpty() && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>His own first</b>: of the creatures a change could mean, nearest
     * first, the ones spawned for {@code playerId} — when there are any — else all of them. So "the
     * dragon" and "my dragon" both find HIS dragon when he has one near, and the nearest when he has
     * none: the answer does not hang on the agent carrying a possessive through. A NARROWING, like
     * {@code ArtifactOwner.preferOwn}, so the order within what is kept is the caller's.
     */
    public static <T> List<T> hisFirst(List<T> nearestFirst,
                                       java.util.function.Function<T, Collection<String>> tagsOf,
                                       String playerId) {
        if (nearestFirst == null || nearestFirst.isEmpty() || spawnerTag(playerId).isEmpty()) {
            return nearestFirst;
        }
        List<T> his = new java.util.ArrayList<>();
        for (T t : nearestFirst) {
            if (spawnedBy(tagsOf.apply(t), playerId)) {
                his.add(t);
            }
        }
        return his.isEmpty() ? nearestFirst : his;
    }
}
