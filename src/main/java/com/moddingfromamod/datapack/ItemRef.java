package com.moddingfromamod.datapack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>An item reference is a TYPE, not a string.</b>
 *
 * <p>The engine has eight places that take an item id, every one written separately, and they
 * disagree about what a valid one even is. Measured against the real {@code MakeItemTool.derive}
 * before this class existed:
 *
 * <pre>
 * rich  {ITEM=moddingfromamod:hot_coffee_plain} -&gt; ACCEPTED    &lt;- /give fails, silently
 * rich  {ITEM=moddingfromamod:wish_item}        -&gt; ACCEPTED    &lt;- gives N BLANK items
 * rich  {ITEM=minecraft:cobblestne}             -&gt; ACCEPTED    &lt;- typo, silent
 * place {BLOCK=not_an_id}                       -&gt; ACCEPTED    &lt;- no check at all
 * </pre>
 *
 * <p>Every one of those is silent to the child and green to every gate: {@code /give} and
 * {@code /setblock} do not report a miss, and an effect's particles and sounds are separate lines
 * that fire regardless. So <i>"a bag that gives me 10 Hot Coffees"</i> hands him the sparkles, the
 * level-up fanfare, and an empty bag. Full plan: <b>docs/ITEM_REFERENCES.md</b>.
 *
 * <h2>The vocabulary, and why the WISHED spelling is the one the agent invents</h2>
 * Given <i>"a coffee machine that turns coffee beans into coffee"</i> the agent made a Hot Coffee
 * and then wrote {@code moddingfromamod:hot_coffee_plain} — an id that does not exist, because
 * every item made from scratch <b>is</b> {@code moddingfromamod:wish_item} and its name, icon and
 * colour are components on it. The instinct is right, so this accepts that spelling rather than
 * fighting it, alongside the bare feature id {@code make_item} hands back and the name a child
 * actually says.
 *
 * <h2>{@link Kind#BLANK} is the dangerous one</h2>
 * {@code moddingfromamod:wish_item} <b>resolves</b>, so "does this exist" answers <b>yes</b> and a
 * registry check waves it through — and it means <i>every wish item at once</i>. Named as a
 * {@code convert} INPUT it eats whatever wish item {@code /clear} reaches first; named to
 * {@code rich} it hands over N nameless, colourless blanks that {@code WishItemCatalogue} then
 * refuses to record. It has to be its own case, decided here, not left to the registry.
 *
 * <h2>Parsing is pure; existence is not</h2>
 * This class answers only what the SHAPE of the string says. Whether
 * {@code minecraft:cobblestne} is a real item needs the live registry, which — as
 * {@link ItemBase}'s own doc already says — belongs in a GameTest. Gate A catches the malformed and
 * the blank; Gate B catches the typo. Neither substitutes for the other.
 */
public final class ItemRef {

    /** A registry id and nothing else. Spliced in front of a component list, so it stays tight. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** A bare {@code make_item} feature id — no namespace, and never a real item. */
    private static final Pattern FEATURE = Pattern.compile("[a-z][a-z0-9_]*");

    private static final String OURS = "moddingfromamod:";

    public enum Kind {
        /** A real registered item — vanilla or any mod's. Existence still to be checked. */
        REGISTERED,
        /** An item this engine made for him. {@link #feature} names which. */
        WISHED,
        /** {@code moddingfromamod:wish_item} — real, and means every wish item at once. */
        BLANK,
        /** Not an id and not a feature id: nothing can be done with it. */
        MALFORMED
    }

    private final Kind kind;
    private final String text;
    private final String feature;

    private ItemRef(Kind kind, String text, String feature) {
        this.kind = kind;
        this.text = text;
        this.feature = feature;
    }

    public Kind kind() {
        return kind;
    }

    /** The string as given, trimmed and lowercased — the id, for {@link Kind#REGISTERED}. */
    public String text() {
        return text;
    }

    /** The {@code make_item} feature id, for {@link Kind#WISHED} only; null otherwise. */
    public String feature() {
        return feature;
    }

    public boolean isWished() {
        return kind == Kind.WISHED;
    }

    /**
     * What the SHAPE of this string says, with no registry and no spec store consulted.
     *
     * <p>Deliberately does not decide whether a {@link Kind#WISHED} feature was ever made — that
     * needs the store, and a parser that reached for it could not be unit-tested without one. The
     * caller resolves.
     *
     * @param raw whatever the agent wrote; null and blank are {@link Kind#MALFORMED}
     */
    public static ItemRef parse(String raw) {
        if (raw == null) {
            return new ItemRef(Kind.MALFORMED, "", null);
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return new ItemRef(Kind.MALFORMED, "", null);
        }
        if (ItemBase.WISH_ITEM.equals(s)) {
            return new ItemRef(Kind.BLANK, s, null);
        }
        if (s.startsWith(OURS)) {
            // OUR NAMESPACE HOLDS EXACTLY ONE REAL ITEM, and it is the one above. Anything else
            // written here is the agent naming a feature -- which is the right instinct and the
            // spelling it reaches for unprompted -- so it is read as one rather than refused.
            String rest = s.substring(OURS.length());
            return FEATURE.matcher(rest).matches()
                    ? new ItemRef(Kind.WISHED, s, rest)
                    : new ItemRef(Kind.MALFORMED, s, null);
        }
        if (s.indexOf(':') >= 0) {
            return ID.matcher(s).matches()
                    ? new ItemRef(Kind.REGISTERED, s, null)
                    : new ItemRef(Kind.MALFORMED, s, null);
        }
        // NO COLON, SO IT CANNOT BE A REAL ITEM -- an id without a namespace does not resolve
        // anywhere in the game. It is therefore an attempt to name something we made, in one of the
        // two spellings that happen: the feature id make_item handed back (hot_coffee_plain) or the
        // name a child says ("Hot Coffee"). Both are read as WISHED and looked up the same way
        // start_from looks one up, because the id and the name drift apart the moment a refinement
        // renames the thing.
        //
        // Junk with no colon lands here too, deliberately. "nothing was made under that name" is a
        // better answer for a typo'd feature id than "that is not an item id", and it is the only
        // one of the two that names what to do next.
        return new ItemRef(Kind.WISHED, s, s);
    }

    /**
     * The design behind this reference, or null when it does not name a made item.
     *
     * <p>Goes through {@code ItemSpecStore.find}, not {@code get}, so the name a child says
     * (<i>"Hot Coffee"</i>) resolves as readily as the id {@code make_item} handed back — the same
     * rule {@code start_from} already uses, and for the same reason: the id and the name drift
     * apart the moment a refinement renames the thing.
     */
    public com.moddingfromamod.item.ItemSpecStore.Spec spec() {
        return kind == Kind.WISHED
                ? com.moddingfromamod.item.ItemSpecStore.find(feature) : null;
    }

    /**
     * Everything wrong with this reference, store consulted — the one a CALL SITE asks.
     *
     * <p>Separate from {@link #refusal} on purpose. That one answers about the SHAPE of the string
     * and is a pure Gate A question; this one also asks whether a made item by that name exists,
     * which needs the store. Merging them would make {@link #refusal}'s unit tests need a populated
     * store to say anything, and would hide which of the two questions actually failed.
     *
     * <p>Still says nothing about whether a {@link Kind#REGISTERED} id is really in the registry.
     * That needs the live one, and lives at Gate B — see {@link ItemBase}'s own note.
     */
    public String whyUnusable(String field) {
        return whyUnusable(field, RegistryExists.ALWAYS);
    }

    /**
     * The same, with the live registry consulted for a {@link Kind#REGISTERED} id.
     *
     * <p>The typo case: {@code minecraft:cobblestne} is a perfectly well-formed id, so no amount of
     * parsing catches it, and {@code /give} does not report a miss — the effect's particles and
     * sound play and nothing arrives. Only the registry knows, so only a caller holding one can
     * ask, which is why this is a parameter rather than a lookup.
     */
    public String whyUnusable(String field, RegistryExists registry) {
        String shape = refusal(field);
        if (shape != null) {
            return shape;
        }
        if (kind == Kind.REGISTERED && !registry.has(text)) {
            return field + " names '" + text + "', and there is no such item or block in the game. "
                    + "Check the spelling, and check the namespace — an item from a mod is only "
                    + "there if that mod is loaded. Nothing reports a miss at wish time: the "
                    + "command simply does nothing, and he gets the sparkles and an empty bag.";
        }
        if (kind == Kind.WISHED && spec() == null) {
            return field + " names '" + text + "', and nothing was made under that name. Only "
                    + ItemBase.WISH_ITEM + " is registered in this mod — an id like "
                    + OURS + "something is not a real item, it is a make_item feature id, and "
                    + "there is no feature called '" + feature + "'.\n"
                    + "Make it first with make_item and name it here by the id make_item hands "
                    + "back, or name a real game item instead.";
        }
        return null;
    }

    /**
     * Why this cannot be used at all, in the agent's terms — or null when the shape is usable.
     *
     * <p>Shape only. A {@link Kind#WISHED} ref that names nothing, and a {@link Kind#REGISTERED} id
     * that is not in the registry, are both usable SHAPES and are refused by the caller, which has
     * the store and the registry this does not.
     *
     * @param field what to call the parameter when explaining, e.g. {@code "convert's INPUT"}
     */
    public String refusal(String field) {
        return switch (kind) {
            case BLANK -> field + " cannot be " + ItemBase.WISH_ITEM + ". EVERY item made from "
                    + "scratch has that id — the name, the icon and the colour are components on it "
                    + "— so this would mean any wish item at all rather than the one you mean.\n"
                    + "Name the thing you actually want: a real game item, or the id make_item "
                    + "handed back for the one you made (hot_coffee_plain).";
            case MALFORMED -> field + " must name an item: a full id like minecraft:cocoa_beans, or "
                    + "the id make_item handed back for something you made (hot_coffee_plain). '"
                    + text + "' is neither.";
            default -> null;
        };
    }
}
