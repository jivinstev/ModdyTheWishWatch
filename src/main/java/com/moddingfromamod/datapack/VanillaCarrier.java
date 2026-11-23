package com.moddingfromamod.datapack;

import com.moddingfromamod.ModdingFromAModMod;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// The switch and the carrier id. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * <b>WHICH vanilla item a wished thing rides on when the client has no mod.</b>
 *
 * <p>The one place that answers "are we serving vanilla clients, and with what". Everything else —
 * the give line, the icon components, the ability bridge — asks here rather than carrying its own
 * copy of the answer, because two copies of "is this a carrier" is exactly how one path would keep
 * tinting while another stopped.
 *
 * <p><b>Off by default, and that is load-bearing.</b> With it off every id, every component and
 * every interaction is byte-for-byte what it was before this existed, so the modded path a child
 * plays today is untouched. See {@code docs/VANILLA_ITEM_SPIKE.md}.
 *
 * <h2>Why the substitution happens HERE and not in the prompt</h2>
 *
 * <p>The agent is told to write {@code moddingfromamod:wish_item} and that instruction is frozen —
 * changing it would recalibrate the wish prompt, which this work is not allowed to do. So the id is
 * swapped <b>downstream</b>, in {@link ItemBase#id}, which every give line and every match predicate
 * already goes through. The agent's output is unchanged; only its resolution moves.
 *
 * <p>Minecraft-free on purpose, like {@link ItemBase}: which id goes into a give line is a string
 * decision, and keeping it here means it can be unit-tested without booting a game. The one
 * question that genuinely needs a live {@code ItemStack} — "is this particular stack a carrier" —
 * lives in {@code VanillaCarrierAbilities}, next to the code that acts on it.
 */
public final class VanillaCarrier {

    /** {@code -Ptestprops="vanillacarrier=on"}, or a server property. Absent = off. */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".vanillacarrier";

    /**
     * The carrier — decision D1, measured rather than picked.
     *
     * <p>Vanilla's own handler is {@code layer > 0 ? -1 : DyedItemColor.getOrDefault(stack,
     * -6265536)}, so it takes an <b>arbitrary</b> RGB from {@code dyed_color} — the component the
     * tool already writes — and tints <b>layer 0 only</b>, which is exactly how the icon library is
     * drawn. And it is <b>inert in a player's hand</b>: no use action, no {@code useOn}, so a child
     * right-clicking a wished cup does not drink it, wear it or throw it. Leather <i>armour</i>
     * fails that last test and a potion fails it twice.
     */
    public static final String ITEM_ID = "minecraft:leather_horse_armor";

    /**
     * <b>Moddy's own reserved {@code custom_model_data} on the carrier, well clear of the icon
     * library.</b>
     *
     * <p>{@code custom_model_data} on {@link #ITEM_ID} is ONE number space, shared by every wished
     * thing and by the watch, so the watch needs a number in it — and it must not be a number the
     * wish icon library could ever hand out. Kept out of {@code WishIcons} on purpose: the library
     * is the set of pictures a WISH may pick, and a wished thing that came back looking like the
     * thing you wish WITH is the same "we substituted this" / "he asked for exactly that" confusion
     * the item spike paid for five times over.
     *
     * <p>The gap is not decoration either. The library grows — 24 → 27 in one issue — and a
     * reserved number a later icon could reach is a number that silently repoints Moddy's picture
     * months later. {@code VanillaWatchTest} fails if the two spaces ever meet.
     */
    public static final int WATCH_MODEL_DATA = 1000;

    /**
     * <b>The wish anvil's "Wish!" button </b> — one above Moddy, in the same reserved gap.
     *
     * <p>Both the anvil's input and its result wear it. That is the fix, not decoration: a stock
     * client builds a plain vanilla {@code AnvilMenu} and PREDICTS the result as a renamed copy of
     * the input, then the server's result replaces it — so if the two are different pictures the
     * button flickers between them on every keystroke. Same carrier, same number, same
     * {@code item_model}: nothing left to flip. The art is {@code tools/gen-wish-wand-icon.py}'s,
     * animated through a vanilla {@code .png.mcmeta}.
     */
    public static final int WISH_WAND_MODEL_DATA = WATCH_MODEL_DATA + 1;

    /** The wand's model and sprite, under {@code models/item/} and {@code textures/item/}. */
    public static final String WISH_WAND_ICON = "wish_wand";

    /**
     * <b>The Wish Bag </b> — two above Moddy, in the same reserved gap. It is pinned in
     * hotbar slot 2 beside the watch and opens the bag's paged chest, so a stock client needs its
     * picture in the pushed pack exactly as it needs Moddy's. Since the art is a 16-frame
     * strip (a star glint and sparkles over a held bag, {@code art/icons/animated-1372/}) animated
     * by its {@code .png.mcmeta}, which rides in the pack beside it.
     */
    public static final int WISH_BAG_MODEL_DATA = WATCH_MODEL_DATA + 2;

    /** The bag's model and sprite, under {@code models/item/} and {@code textures/item/}. */
    public static final String WISH_BAG_ICON = "wish_bag";

    /**
     * <b>The menus' BACK arrow </b> — three above Moddy, in the same reserved gap. Vanilla
     * has no item that points LEFT (an arrow item points up-right), so every "◀ Back" tile wears this one picture, on
     * both editions: the pushed pack on Java, a Bedrock bridge's icon set on Bedrock. Drawn white; a stack wearing
     * it is dyed white so the carrier's default tint does not colour it.
     */
    public static final int BACK_ARROW_MODEL_DATA = WATCH_MODEL_DATA + 3;

    /** The back arrow's model and sprite, under {@code models/item/} and {@code textures/item/}. */
    public static final String BACK_ARROW_ICON = "back_arrow";

    /**
     * <b>Moddy's IDEA, a lit light bulb </b>: four above Moddy, in the same reserved gap. The
     * Bedrock watch page's "💡 his idea" button wears it. Vanilla has no light bulb; a MENU picture rather than an icon
     * library one, so the Builder never offers it as a look. Drawn in its own colours; a stack wearing it is dyed white.
     */
    public static final int LIGHT_BULB_MODEL_DATA = WATCH_MODEL_DATA + 4;

    /** The light bulb's model and sprite, under {@code models/item/} and {@code textures/item/}. */
    public static final String LIGHT_BULB_ICON = "light_bulb";

    /**
     * <b>The ATOM RIG's reserved band on the same carrier</b> — the constant library a creature is
     * drawn from (the only rig). See {@code docs/ATOM_RIG_PLAN.md}.
     *
     * <p>{@code custom_model_data} on {@link #ITEM_ID} is ONE number space: the wish icon library
     * (1..27 and growing), Moddy's watch ({@link #WATCH_MODEL_DATA}), and now this. A band rather
     * than a constant because the library was going to grow by exactly one known amount — Phase 1b's
     * eight pattern cubes, one per {@code SculptPattern} name, which have since LANDED at
     * {@link #ATOM_PATTERN_MODEL_DATA_FIRST}. Reserving room cost nothing, where discovering
     * later that the next free number is inside the icon library costs a renumber of things
     * already standing in a child's world. Six of the sixteen are still spare.
     *
     * <p>The gap above {@link #WATCH_MODEL_DATA} is the same reasoning that put the watch above the
     * icons and is not decoration: {@code custom_model_data} is an {@code int} and the predicate is
     * an exact match, so a gap costs literally zero, while a reserved number a growing library could
     * one day reach is a creature that silently starts rendering as a wish icon.
     * {@code AtomLibraryTest} fails if the three spaces ever meet.
     *
     * <p>The deleted pack rig's per-creature numbering used to overlap this band — it
     * started at 1 and strode 256 for up to 64 creatures, straight through both the watch and this.
     * It was survivable only because the two lived in different packs; with the pack rig gone there
     * is nothing left to overlap.
     */
    public static final int ATOM_MODEL_DATA_FIRST = 2000;

    /** The last number the atom library may ever use. Sixteen is room for 1b and then some. */
    public static final int ATOM_MODEL_DATA_LAST = 2015;

    /**
     * The plain cube, with vanilla's per-direction shading left ON.
     *
     * <p>The default arm, because {@code RigModelPack.SHADE} measured shading ON as the better
     * match for the mod renderer on the GROUPED rig (17,283 px against 18,338 with it off). Whether
     * that survives the atom rig's mandatory non-uniform scale is Phase 1's photograph, not an
     * inference — see {@link #ATOM_MODEL_DATA_FLAT}.
     */
    public static final int ATOM_MODEL_DATA = ATOM_MODEL_DATA_FIRST;

    /**
     * The same cube with {@code "shade": false} — the other half of Phase 1's A/B.
     *
     * <p>It is a second MODEL rather than a flag inside one because the mod ships these files for a
     * modded client, which is never pushed the pack ({@code ItemPackService.offerTo} refuses one),
     * so a property that only reached the generated pack would switch the arm for a stock client
     * and not for a Gate C dev client. Putting the choice in the number puts it in entity data,
     * which is where this whole design already lives.
     */
    public static final int ATOM_MODEL_DATA_FLAT = ATOM_MODEL_DATA_FIRST + 1;

    /** The atom cube's sprite, under {@code textures/item/}. Written by tools/gen-atom-library.py. */
    public static final String ATOM_SPRITE = "rig_atom";

    /** The shaded cube's model, under {@code models/item/}. */
    public static final String ATOM_MODEL = "rig_atom";

    /** The unshaded cube's model, under {@code models/item/}. */
    public static final String ATOM_MODEL_FLAT = "rig_atom_flat";

    /**
     * <b>Where Phase 1b's eight PATTERN cubes start</b> — 2002, immediately after the two plain
     * ones. {@code AtomLibrary.PATTERNS} is the order, alphabetical and pinned, so the number a
     * pattern gets is a fact about the vocabulary rather than about the order somebody typed it in.
     *
     * <p>A patterned box is drawn as TWO displays: the plain cube tinted the part's own colour, and
     * one of these — the pattern painted opaque-over-transparent — inflated by a hair and tinted the
     * part's {@code patternColour}. {@code docs/ATOM_RIG_PLAN.md} §2 B.
     *
     * <p><b>There are no FLAT twins, and the band is why.</b> {@link #ATOM_MODEL_DATA_FLAT} exists
     * because {@code shade: false} was Phase 1's A/B; eight more of those would need 2010..2017 and
     * the band ends at {@link #ATOM_MODEL_DATA_LAST} — sixteen numbers reserved when 1b was
     * understood as eight cubes, which is exactly what it turned out to be. Widening the band to fit
     * them would buy nothing measurable: §5.4.2 photographed the two shade arms on nine fixtures and
     * they agree <i>to the pixel on every one</i>, because the ITEM path never reads
     * {@code BakedQuad.isShade()} at all. So a pattern cube is always the shaded spelling, and
     * {@code AtomLibrary.patternModelData} ignores {@code rigatomshade} on purpose.
     */
    public static final int ATOM_PATTERN_MODEL_DATA_FIRST = ATOM_MODEL_DATA_FIRST + 2;

    /**
     * The prefix every pattern cube's model and sprite share: {@code rig_atom_scales}, and so on.
     *
     * <p>Derived rather than eight constants, because the vocabulary lives in
     * {@code SculptPattern.NAMES} and a second hand-written list of it is a name that can drift into
     * a magenta missing cube for one pattern and nothing else.
     */
    public static final String ATOM_PATTERN_PREFIX = "rig_atom_";

    /**
     * The mod's own watch artwork: {@code models/item/wish_watch.json} over
     * {@code textures/item/wish_watch.png}.
     *
     * <p>This is <b>the file the modded client already renders</b> — the same one
     * {@code WishWatchItem} resolves — rather than a nearest-miss from the wish icon library. It
     * lives outside {@code item/wish/}, which is why the pack's sprite addressing had to stop
     * hardwiring that folder. Since it is a 16-frame strip — the face swirls, the case is held
     * still — animated by its {@code .png.mcmeta}, which rides in the pack beside it.
     */
    public static final String WATCH_ICON = "wish_watch";

    /** A test's override, or null to read the property. Always restore it in a {@code finally}. */
    private static volatile Boolean forced;

    private VanillaCarrier() {
    }

    /** Whether wished items are being handed out on a vanilla carrier at all. */
    /**
     * <b>ON by default since the items spike classified as PATH 2</b> — see
     * {@code docs/VANILLA_COMPAT_METHODOLOGY.md}. The vanilla carrier does everything a child can
     * see and do at full measured quality; the one thing vanilla structurally cannot supply
     * (finding a wished thing again in the creative menu) is restored by a thin mod-side shim that
     * sits on top rather than inside. Path 2 obliges the vanilla path to be the default, with the
     * mod item kept for back-compat only ({@code }).
     *
     * <p><b>Turn it off with {@code -Dmoddingfromamod.vanillacarrier=off}</b>, which is the escape
     * hatch for an old world whose items were minted on the mod path and for bisecting a
     * regression. Note that is genuinely lossy in the other direction — a stack minted as a carrier
     * goes inert when the route is off — which is why {@code } also asks for a world-level
     * mark before any beta.
     *
     * <p><b>What had to be true before this could flip</b>, because it nearly shipped wrong: the
     * icon on this route comes from a {@code custom_model_data} override, and during the spike that
     * override existed only inside a generated resource pack the gates wrote. The mod now ships
     * the carrier's bindings itself — as {@code assets/minecraft/items/leather_horse_armor.json}
     * and the native definitions only, no {@code overrides} model ({@code CarrierModelDriftTest}),
     * so a client WITH the mod resolves the artwork locally.
     * Flipping without that would have rendered every wished thing as a plain brown horse armour,
     * on the path the child actually plays, with nothing logged.
     *
     * <p>⚠ <b>The other half of that sentence — "and a client without one gets it from the pushed
     * pack" — was designed and not built, and it stayed false for the whole life of this route
     *.</b> {@code WishPackWriter} produced a correct pack and was reachable only from a probe
     * and a {@code Dist.CLIENT} spike, so nothing on any shipped server path ever wrote one: every
     * wished thing, and Moddy, rendered as a leather horse armour for the only audience the
     * vanilla-lite path exists for, with every gate green because every gate asked about the mod's
     * own copy. It is true now — {@code vanilla/ItemPackService} publishes at server start and
     * offers on join — and {@code StockClientIconTest} is what keeps it true.
     */
    public static boolean enabled() {
        Boolean override = forced;
        if (override != null) {
            return override;
        }
        String m = System.getProperty(PROPERTY, "").trim();
        return m.isEmpty() || (!"off".equalsIgnoreCase(m) && !"false".equalsIgnoreCase(m));
    }

    /** Test seam. */
    public static void force(Boolean on) {
        forced = on;
    }

    /** The id a wished thing is actually handed out as, right now. */
    public static String itemId() {
        return enabled() ? ITEM_ID : ItemBase.WISH_ITEM;
    }

    /** Whether an id names the carrier. */
    public static boolean isCarrierId(String id) {
        return ITEM_ID.equals(id);
    }
}
