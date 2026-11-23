package com.moddingfromamod.item;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>A made item whose NAME is a real item that does something (Q1).</b>
 *
 * <p>"Egg Bow", "Rock Smasher Pickaxe", "Storm Trident" made with no {@code base_item} become a
 * wished carrier that only LOOKS like one: it cannot be drawn, cannot mine, cannot be thrown or
 * blown, and its wish runs on a click instead. That was 6 of the 14 wishes where Muse fell short
 * of Opus in an earlier pass (a pickaxe, a crossbow, a trident, an egg bow, a snowball, a horn) —
 * and it is the mistake the whole lesson exists to prevent, so the check moves into the tool.
 *
 * <p>Pure: which word, which real item, and what the real one does. The tool refuses once and
 * names the {@code base_item}; {@code only_looks_like} is the way past it for a thing that must
 * look like one and not work like one.
 *
 * <p>Deliberately NOT here: "pearl" and "egg" alone (a pearl necklace, an easter egg), a brush (a paint brush is a made thing that paints, and a real brush
 * dusts sand), a compass (pointing somewhere is its own wish), and anything whose real item
 * does nothing a child would miss. Launchers come before ammunition, so an "Egg Bow" is a bow.
 */
public final class RealItemHint {

    /** What the real item keeps doing: the one line that says why the base matters. */
    public record Real(String word, String baseItem, String keeps) {
        /** Food or drink: its own use is FINISHING it, which {@code on_consume} already rides. */
        public boolean eaten() {
            return keeps.startsWith("is eaten") || keeps.startsWith("is drunk");
        }
    }

    private static final Map<String, Real> WORDS = new LinkedHashMap<>();

    static {
        put("crossbow", "minecraft:crossbow", "is loaded and shot");
        put("bow", "minecraft:bow", "is drawn and shoots");
        put("trident", "minecraft:trident", "is thrown and hits");
        put("pickaxe", "minecraft:diamond_pickaxe", "mines");
        put("shovel", "minecraft:diamond_shovel", "digs");
        put("sword", "minecraft:diamond_sword", "hits hard");
        put("shield", "minecraft:shield", "blocks");
        put("fishing rod", "minecraft:fishing_rod", "casts and catches");
        put("horn", "minecraft:goat_horn", "is blown");
        put("snowball", "minecraft:snowball", "is thrown");
        put("ender pearl", "minecraft:ender_pearl", "is thrown");
        put("bucket", "minecraft:bucket", "scoops and pours");
        // Food and drink — a made "Magic Apple" is CLICKED, never eaten (Muse's
        // magic apple and tiny potion). Their own use is on_consume, which builds on a real one.
        put("potion", "minecraft:potion", "is drunk");
        put("apple", "minecraft:apple", "is eaten");
        put("cookie", "minecraft:cookie", "is eaten");
        put("bread", "minecraft:bread", "is eaten");
        put("pie", "minecraft:pumpkin_pie", "is eaten");
        put("stew", "minecraft:mushroom_stew", "is eaten");
        put("soup", "minecraft:mushroom_stew", "is eaten");
    }

    private static void put(String word, String base, String keeps) {
        WORDS.put(word, new Real(word, base, keeps));
    }

    private RealItemHint() {
    }

    /**
     * The real item this call's name points at, when it made no {@code base_item}, inherits
     * nothing, and did not say it only looks like one. Empty when there is nothing to say.
     */
    public static Optional<Real> of(String name, String baseItem, boolean inherits, boolean onlyLooksLike) {
        return of(name, baseItem, inherits, onlyLooksLike, null);
    }

    /**
     * As {@link #of(String, String, boolean, boolean)}, knowing the shape: a food or drink made
     * {@code on_consume} from scratch is built on a real one by make_item itself, so there is
     * nothing to say.
     */
    public static Optional<Real> of(String name, String baseItem, boolean inherits, boolean onlyLooksLike,
                                    String shape) {
        if (onlyLooksLike || inherits || (baseItem != null && !baseItem.isBlank()) || name == null) {
            return Optional.empty();
        }
        return forName(name).filter(r -> !(r.eaten() && "on_consume".equals(shape)));
    }

    /** The real item a NAME points at, whatever it was built on. Empty when none. */
    public static Optional<Real> forName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (Real r : WORDS.values()) {
            if (Pattern.compile("\\b" + Pattern.quote(r.word()) + "s?\\b").matcher(n).find()) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /**
     * The real item an item MODEL id draws ({@code minecraft:ender_pearl} → the ender pearl): a
     * pack that dresses a carrot on a stick as a pearl has said which real item it means.
     */
    public static Optional<Real> forModel(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        String path = model.substring(model.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        for (Real r : WORDS.values()) {
            String base = r.baseItem().substring(r.baseItem().indexOf(':') + 1);
            if (path.equals(base) || path.equals(r.word().replace(' ', '_'))) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** The refusal, in the form that works. */
    public static String refusal(String name, Real r) {
        return "'" + name + "' is a " + r.word() + ". A real " + r.word() + " " + r.keeps()
                + "; a made item that is only CALLED one does not, and its wish would run on a click "
                + "instead. Build it ON the real thing: pass base_item \"" + r.baseItem() + "\" (any "
                + "real " + r.word() + " id works) with the shape the real item's own use triggers, "
                + "and keep the rest. Only if he wants something that must look like a " + r.word()
                + " and must NOT work like one, pass only_looks_like: true. (A datapack `give` of the "
                + "same made item skips none of this — author_datapack says the same thing, #1068.)";
    }
}
