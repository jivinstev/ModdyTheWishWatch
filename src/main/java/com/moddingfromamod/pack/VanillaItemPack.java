package com.moddingfromamod.pack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ── TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
// The generated pack's shape. If the route is abandoned this whole file goes; if it ships, the
// MOD ITEM becomes the legacy path instead. Gated.

/**
 * <b>The resource pack that lets a VANILLA item wear a wished icon.</b>
 *
 * <p>Written for the spike in {@code docs/VANILLA_ITEM_SPIKE.md}: can a wished item look the way it
 * looks today on a client that has installed <i>nothing</i>? Today's item is
 * {@code moddingfromamod:wish_item}, a registered mod item carrying
 * {@code custom_model_data} (which artwork) and {@code dyed_color} (what colour). Neither component
 * needs the mod — what needs the mod is (a) an item id the server can hand out and (b) a client-side
 * {@code ItemColor} handler willing to tint it.
 *
 * <p>A vanilla base item supplies both, and this class supplies the third piece: a pack that ships
 * <b>our own artwork</b> and points the base item's ITEM DEFINITION
 * ({@code assets/minecraft/items/<carrier>.json}, the 1.21.4 dialect) at it. The vanilla item is a carrier, not
 * the picture — a wished cup renders as the cup sprite, not as leather horse armour.
 *
 * <p><b>Deliberately Minecraft-free.</b> Every decision here — the file layout, the JSON shape, the
 * entry ORDER — is decidable without a game, and each one fails silently in the game if it is
 * wrong. So it is a pure class with unit tests, per the repo's standing rule.
 *
 * <h2>The three things that are silently wrong if you get them wrong</h2>
 *
 * <ol>
 *   <li><b>{@code range_dispatch} entries must be sorted ASCENDING by threshold</b> — see
 *       {@link #itemDefinition}: at 16 entries or more 1.21.4 binary-searches them, and a binary
 *       search over an unsorted array hands back a real, valid, <i>wrong</i> model, which renders
 *       as some other wish's icon.</li>
 *   <li><b>The carrier's definition must keep a {@code fallback} to its own model.</b> A pack file
 *       REPLACES the vanilla one, so without it a plain, un-wished carrier renders as nothing. The
 *       un-wished case is a real case: any leather horse armour already in a child's world.</li>
 *   <li><b>Every node carries the dye tint.</b> From 1.21.4 tinting is DATA (see
 *       {@link #LEATHER_DYE_DEFAULT}); a node without {@code tints} draws untinted.</li>
 * </ol>
 *
 * <p><b>The pre-1.21.4 dialect is gone (step 3, 2026-10-16).</b> Until then this class
 * also replaced {@code assets/minecraft/models/item/<carrier>.json} with an {@code overrides} table
 * keyed on the same numbers — the half a ≤1.21.3 client reads — plus a one-tint {@code _flat} twin
 * of every two-colour icon, because 1.21.1 tints layer0 only. The server now refuses any
 * client older than 1.21.4 at join ({@code net/ClientVersionPayload}), so nobody who can read that
 * file can reach a server that pushes this pack. The carrier's MODEL is now vanilla's own, which is
 * what the definition's {@code fallback} names. {@code ItemPackBindingTest} keeps the table out.
 *
 * <p>Emits text only. Textures are copied bytes and are named by {@link #texturePath} rather than
 * produced here, so this class stays pure and the caller owns the I/O.
 */
public final class VanillaItemPack {

    /** Resource-pack format for Minecraft 1.21.1. */
    public static final int PACK_FORMAT = 34;

    /**
     * <b>The highest {@code supported_formats} a modern client will accept in that key</b> —
     * MEASURED against 26.2's own codec, not assumed.
     *
     * <p>26.2 reads four optional keys out of the {@code pack} object and then validates them
     * together. Its rules, read off the error strings it produced when given each candidate
     * ({@code PackFormat$IntermediaryFormat.validate}):
     *
     * <ul>
     *   <li>{@code supported_formats} alone, claiming anything above <b>64</b>, is
     *       <i>"Pack declares support for version newer than 64, but is missing mandatory fields
     *       min_format and max_format"</i>;</li>
     *   <li>{@code min_format}/{@code max_format} alone is <i>"…game versions supporting formats
     *       17 to 64 require a supported_formats field"</i>;</li>
     *   <li>both, with {@code supported_formats} claiming past 64, is a <i>"version declaration
     *       mismatch"</i>.</li>
     * </ul>
     *
     * <p>So the one shape that spans the whole range is all four keys with {@code
     * supported_formats} capped here. 1.21.1 and 1.21.4 read {@code (description, pack_format,
     * lenientOptional supported_formats)} and ignore the two they have never heard of — a
     * {@code RecordCodecBuilder} does not reject unknown fields, which is the same union principle
     * this whole class is an instance of.
     */
    public static final int SUPPORTED_FORMATS_MAX = 64;

    /** The oldest resource format the union claims — 1.21.1's own, as {@code [major, minor]}. */
    public static final int[] MIN_FORMAT = {PACK_FORMAT, 0};

    /**
     * <b>The newest format the union claims, and it is deliberately not "the latest".</b>
     *
     * <p>{@code 88.0} is <b>26.2</b>, which is the newest release ViaProxy 3.4.12 translates and
     * the newest arm {@code tools/forward-compat-gate.sh} runs. 26.3 exists (97.1) and is neither
     * translated nor tested, so claiming it would be a promise nothing here can keep — the number
     * is the honest ceiling rather than an optimistic one, and raising it is the one-line change
     * beside adding a gate arm.
     */
    public static final int[] MAX_FORMAT = {88, 0};

    /**
     * <b>Vanilla leather's own default dye colour</b>, copied from the 1.21.4 and 26.2 jars'
     * {@code assets/minecraft/items/leather_horse_armor.json} — the two are byte-identical there.
     *
     * <p>It is load-bearing rather than decorative. From 1.21.4 tinting is DATA: the Java
     * {@code ItemColor} handler that tints leather on 1.21.1 no longer exists, and a definition
     * that omits {@code tints} makes every stack of the carrier draw untinted. The atom rig's only
     * colour channel is {@code minecraft:dyed_color}, so a dropped tint is not a missing detail —
     * it is every wished creature rendering grey.
     */
    public static final int LEATHER_DYE_DEFAULT = -6265536;

    /**
     * The pack's own namespace for the artwork.
     *
     * <p>Deliberately NOT {@code moddingfromamod}. The whole claim under test is that a client with
     * no mod renders this, and a pack whose ids name the mod would still <i>work</i> — resource
     * namespaces are not mod ids — while making the control ("is arm B genuinely vanilla?")
     * unreadable. A separate namespace makes the answer visible in the resolved texture id.
     */
    public static final String NAMESPACE = "wishpack";

    /** One icon: the number a stack carries and the sprites it is drawn from, base layer first. */
    public record Icon(int customModelData, String name, List<String> layers) {

        /** True when layer1 is a second, separately tinted colour ({@code ..._accent}). */
        public boolean hasAccent() {
            return layers.size() >= 3 && layers.get(1).endsWith("_accent");
        }

        public Icon {
            if (customModelData <= 0) {
                throw new IllegalArgumentException(
                        "custom_model_data must be positive, was " + customModelData
                                + " — 0 is what a stack with NO custom_model_data reports, so an "
                                + "entry at 0 claims every plain base item in the world");
            }
            if (layers == null || layers.isEmpty()) {
                throw new IllegalArgumentException("icon " + name + " has no layers");
            }
            layers = List.copyOf(layers);
        }
    }

    /**
     * <b>A model the pack carries VERBATIM, at its own reserved number.</b>
     *
     * <p>An {@link Icon} is always {@code item/generated} over a list of sprites, which is what a
     * flat 2D wish icon is. The atom rig's library is not that: it is a 3D cube with faces, and a
     * generated-parent model cannot express one. So an extra supplies its own JSON and this class
     * only decides where it lives and what number selects it.
     *
     * <p>Deliberately opaque — this class does not know what an atom is, and {@code AtomLibrary}
     * does not know what a resource pack looks like. The alternative, teaching {@link Icon} about
     * elements, would put a creature-rendering concern inside the item route's pack shape.
     *
     * @param name a path under {@code models/item/}, NOT under {@code wish/} — the icon folder is
     *             the set of pictures a WISH may pick, and a rig cube is not one of those
     */
    public record Extra(int customModelData, String name, String json) {

        public Extra {
            if (customModelData <= 0) {
                throw new IllegalArgumentException(
                        "custom_model_data must be positive, was " + customModelData);
            }
            if (name == null || name.isBlank() || json == null || json.isBlank()) {
                throw new IllegalArgumentException("extra " + customModelData + " has no model");
            }
        }
    }

    /**
     * What to generate: which vanilla item carries the icons, and what it looks like unwished.
     *
     * <p>No base LAYERS any more (step 3): they were the textures of the replaced
     * {@code models/item/<carrier>.json}, and the pack no longer replaces that model — the
     * definition's {@code fallback} names vanilla's own.
     *
     * @param dyeTint the carrier's default dye colour, or {@code null} for a carrier nothing tints.
     *                See {@link #LEATHER_DYE_DEFAULT} for why a null here on a dyeable carrier is
     *                every creature turning grey.
     */
    public record Spec(String baseItemPath, List<Icon> icons, List<Extra> extras, Integer dyeTint) {

        /** The item route alone, with no verbatim models riding along. */
        public Spec(String baseItemPath, List<Icon> icons) {
            this(baseItemPath, icons, List.of());
        }

        /**
         * The carrier plus verbatim models, on a LEATHER carrier.
         *
         * <p>The tint defaults rather than being asked for because every caller in the tree rides
         * {@code minecraft:leather_horse_armor} — {@code WishPackWriter.BASE_ITEM} — and a caller
         * that moved to an untinted carrier would want to say so out loud in the 5-argument form.
         */
        public Spec(String baseItemPath, List<Icon> icons, List<Extra> extras) {
            this(baseItemPath, icons, extras, LEATHER_DYE_DEFAULT);
        }

        public Spec {
            if (baseItemPath == null || baseItemPath.isBlank()) {
                throw new IllegalArgumentException("no base item");
            }
            icons = List.copyOf(icons);
            extras = extras == null ? List.of() : List.copyOf(extras);
        }
    }

    private VanillaItemPack() {
    }

    /**
     * Where a sprite lives inside the pack.
     *
     * <p>The argument is a path <b>relative to {@code textures/item/}</b> — {@code wish/cone}, not
     * {@code cone}. It used to be a bare name with {@code wish/} hardwired here, and that was a
     * quiet ceiling rather than a simplification: the mod's own artwork does not all live in that
     * one folder, and the piece that does not is {@code wish_watch.png}, i.e. <b>Moddy himself</b>.
     * A hardwired folder meant the carrier could dress a wished thing and could not dress the item
     * a child holds to make the wish.
     */
    public static String texturePath(String sprite) {
        return "assets/" + NAMESPACE + "/textures/item/" + sprite + ".png";
    }

    /** The texture id a model refers to — the pack's namespace, never the mod's. */
    public static String textureId(String sprite) {
        return NAMESPACE + ":item/" + sprite;
    }

    /**
     * Where the replaced vanilla ITEM DEFINITION lives — the one binding file the pack writes.
     *
     * <p>{@code items/}, not {@code models/item/}: a different directory, read by a different
     * loader ({@code ClientItemInfoLoader}, new in 1.21.4). Until step 3 the pack also wrote
     * {@code models/item/<carrier>.json} with an {@code overrides} table for ≤1.21.3 clients; those
     * are refused at join now, and the carrier's model is vanilla's own.
     */
    public static String itemDefinitionPath(String baseItemPath) {
        return "assets/minecraft/items/" + baseItemPath + ".json";
    }

    /** Where one icon's model lives. */
    public static String iconModelPath(String iconName) {
        return "assets/" + NAMESPACE + "/models/item/wish/" + iconName + ".json";
    }

    /** Where a verbatim {@link Extra}'s model lives — beside the wish folder, never inside it. */
    public static String extraModelPath(String name) {
        return "assets/" + NAMESPACE + "/models/item/" + name + ".json";
    }

    /** The model id an {@link Extra} is referenced by. */
    public static String extraModelId(String name) {
        return NAMESPACE + ":item/" + name;
    }

    /** Every text file in the pack, keyed by its path inside the pack root. */
    public static Map<String, String> textFiles(Spec spec) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("pack.mcmeta", mcmeta());
        out.put(itemDefinitionPath(spec.baseItemPath()), itemDefinition(spec));
        for (Icon icon : spec.icons()) {
            out.put(iconModelPath(icon.name()), iconModel(icon));
        }
        for (Extra extra : spec.extras()) {
            out.put(extraModelPath(extra.name()), extra.json());
        }
        for (String overlay : com.moddingfromamod.item.IconOverlays.NAMES) {
            out.put(extraModelPath(OVERLAY_DIR + "/" + overlay), overlayModel(overlay));
        }
        for (String fx : BACKING) {
            out.put(extraModelPath(FX_DIR + "/" + fx), spriteModel(FX_DIR + "/" + fx));
        }
        for (String badge : HotbarBadge.sprites()) {
            out.put(extraModelPath(badge), HotbarBadge.model(NAMESPACE, badge));
        }
        out.put(HotbarBadge.DEFINITION_PATH, HotbarBadge.definition(NAMESPACE));
        out.putAll(nativeDefinitions(spec));
        return out;
    }

    /** The folder, under {@code textures/item/} and {@code models/item/}, the overlay library lives in. */
    public static final String OVERLAY_DIR = "overlay";

    /** The sprite path ({@link #texturePath}'s argument) of one overlay. */
    public static String overlaySprite(String overlay) {
        return OVERLAY_DIR + "/" + overlay;
    }

    /** One overlay's model: its sprite alone, untinted — flame is orange whatever the sword is. */
    static String overlayModel(String overlay) {
        return spriteModel(overlaySprite(overlay));
    }

    /** A one-layer, untinted model over the pack's own sprite at this path. */
    static String spriteModel(String sprite) {
        return "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n"
                + "    \"layer0\": \"" + textureId(sprite) + "\"\n  }\n}\n";
    }

    /**
     * <b>The native half: one {@code items/icon/<name>.json} per binding</b>, which is what a
     * new stack's {@code minecraft:item_model} names ({@link NativeItemModels}). Each is exactly the
     * model node the {@code range_dispatch} table would have reached for that number — same model,
     * same dye tint — so a stack drawn by name and a stack drawn by number look the same, by
     * construction rather than by a second table. Read by 1.21.4+, the only clients the server
     * admits.
     */
    public static Map<String, String> nativeDefinitions(Spec spec) {
        List<int[]> rows = new ArrayList<>();
        List<String> models = new ArrayList<>();
        List<String> names = new ArrayList<>();
        collectBindings(spec, rows, models, names);
        List<String> nodes = nodes(spec, models, names);
        Map<String, String> out = new LinkedHashMap<>();
        for (int[] row : rows) {
            int i = row[1];
            out.put(NativeItemModels.definitionPath(names.get(i)),
                    "{\n  \"model\": " + nodes.get(i) + "\n}\n");
        }
        return out;
    }

    /**
     * <b>What each binding DRAWS, once, for both the native definitions and the number table</b>,
     * indexed like {@code models}. A WISH'S icon — one drawn from the {@code wish/} folder, the
     * pictures a wish may pick — is composed; Moddy's watch is not (he never does
     * anything picturable), and neither is an atom cube, where a composite would be four models
     * evaluated per cube per frame for nothing. The jar draws the same line
     * ({@code tools/gen-carrier-model.py}: its model is under {@code item/wish/}).
     *
     * <p>One list for both tables is what keeps an OLD stack — drawn through the {@code
     * range_dispatch} shim by its number — looking exactly like a new one drawn by name.
     */
    static List<String> nodes(Spec spec, List<String> models, List<String> names) {
        java.util.Set<String> wishIcons = new java.util.HashSet<>();
        java.util.Set<String> accented = new java.util.HashSet<>();
        for (Icon icon : spec.icons()) {
            if (icon.layers().get(0).startsWith("wish/")) {
                wishIcons.add(icon.name());
            }
            if (icon.hasAccent()) {
                accented.add(icon.name());
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            String name = names.get(i);
            if (!wishIcons.contains(name)) {
                out.add(modelNode(models.get(i), spec.dyeTint()));
                continue;
            }
            // A VANILLA item where one reads better (IconBases), undyed — his colour moves
            // to the backing glow. Otherwise the icon's own sprite, dyed as it always was.
            String vanilla = IconBases.forIcon(name);
            String base = vanilla != null ? modelNode(vanilla, null)
                    : modelNode(models.get(i), spec.dyeTint(), accented.contains(name));
            out.add(composed(base, NAMESPACE, spec.dyeTint()));
        }
        return out;
    }

    /**
     * <b>An icon, with its overlay drawn over it.</b> A {@code composite} of the dyed
     * sprite and a {@code select} on {@code custom_model_data}'s {@code strings[0]} — the overlay's
     * name, stamped on the stack by {@code ModelData.components(id, overlay)}. A stack with no
     * string (every stack minted before this, and every item that does nothing picturable) reads
     * null, matches no case, and draws the {@code empty} fallback: exactly the icon it always drew.
     *
     * <p>Read out of 1.21.4's bytecode rather than assumed: {@code CompositeModel$Unbaked} takes
     * {@code models}, the select property {@code CustomModelDataProperty} reads
     * {@code CustomModelData.getString(index)}, and {@code empty} is a registered model type.
     *
     * @param modelNs the namespace the OVERLAY models live in — the pack's own, or the mod's for
     *                the jar's copy of these definitions ({@code tools/gen-carrier-model.py})
     */
    public static String composed(String baseNode, String modelNs, Integer dyeTint) {
        StringBuilder sb = new StringBuilder();
        // Drawn in order, so first is BEHIND: the glow in his colour (the stack's dyed_color, a
        // warm gold when it has none), the white twinkles, the item, then what it does on top.
        sb.append("{ \"type\": \"minecraft:composite\", \"models\": [ ")
          .append("{ \"type\": \"minecraft:model\", \"model\": \"").append(modelNs)
          .append(":item/").append(FX_DIR).append("/backing_glow\"")
          .append(dyeTint == null ? "" : ", \"tints\": [ { \"type\": \"minecraft:dye\", \"default\": "
                  + BACKING_DEFAULT + " } ]")
          .append(" }, { \"type\": \"minecraft:model\", \"model\": \"").append(modelNs)
          .append(":item/").append(FX_DIR).append("/backing_twinkle\" }, ");
        // the Moddy badge a HOTBAR wish wears, drawn only when the stack sets the flag (HotbarBadge).
        String[] badge = HotbarBadge.iconLayers(modelNs);
        if (badge[0] != null) {
            sb.append(badge[0]).append(", ");
        }
        sb.append(baseNode)
          .append(", { \"type\": \"minecraft:select\", \"property\": \"")
          .append(dispatchProperty()).append("\", \"index\": ").append(OVERLAY_INDEX)
          .append(", \"cases\": [");
        List<String> names = com.moddingfromamod.item.IconOverlays.NAMES;
        for (int i = 0; i < names.size(); i++) {
            sb.append(i == 0 ? " " : ", ")
              .append("{ \"when\": \"").append(names.get(i)).append("\", \"model\": { \"type\": ")
              .append("\"minecraft:model\", \"model\": \"").append(modelNs).append(":item/")
              .append(overlaySprite(names.get(i))).append("\" } }");
        }
        sb.append(" ], \"fallback\": { \"type\": \"minecraft:empty\" } }");
        if (badge[1] != null) {
            sb.append(", ").append(badge[1]);
        }
        sb.append(" ] }");
        return sb.toString();
    }

    /** The folder the sparkle backing's layers live in, under {@code textures/item/} and {@code models/item/}. */
    public static final String FX_DIR = "fx";

    /** The backing layers every wish icon wears, behind the item. */
    public static final List<String> BACKING = List.of("backing_glow", "backing_twinkle");

    /** The glow's colour on a stack with no {@code dyed_color}: a warm gold (0xFFE070). */
    public static final int BACKING_DEFAULT = 0xFFFFE070;

    /**
     * <b>The second colour of a stack that carries none </b> — every stack minted before
     * accents, and every stack a 1.21.1 server mints (its component has no {@code colors}). A warm
     * cream, so a belly or a window reads as a lighter part rather than as the wish's colour again.
     * Mirrors {@code tools/gen-carrier-model.py}'s {@code ACCENT_DEFAULT}.
     */
    public static final int ACCENT_DEFAULT = 0xF4E8CC;

    /** Which entry of {@code custom_model_data}'s {@code colors} list is the accent. */
    public static final int ACCENT_INDEX = 0;

    /** Which entry of {@code custom_model_data}'s {@code strings} list names the overlay. */
    public static final int OVERLAY_INDEX = 0;

    /**
     * <b>The one {@code pack.mcmeta} every generation accepts.</b>
     *
     * <p>Four keys, because no smaller set spans 1.21.1 to 26.2 — see {@link #SUPPORTED_FORMATS_MAX},
     * where each of the three smaller candidates is recorded with the error 26.2 answered it with.
     *
     * <p>⚠ This is <b>not</b> what makes the pack apply. A server-pushed pack is applied whatever
     * its declared range says: today's pack declares a bare {@code pack_format: 34} and was
     * measured applying cleanly to a stock 1.21.4 client ({@code docs/VERSION_STRATEGY.md} §4.1,
     * the second atlas rebuild). What the range buys is the absence of an "incompatible pack"
     * warning, so getting it wrong can only be cosmetic — while getting it MALFORMED could make a
     * client refuse the section outright, which is why the shape was measured against the real
     * codec rather than reasoned to.
     */
    static String mcmeta() {
        return """
                {
                  "pack": {
                    "pack_format": %d,
                    "supported_formats": { "min_inclusive": %d, "max_inclusive": %d },
                    "min_format": [%d, %d],
                    "max_format": [%d, %d],
                    "description": "wished items, drawn on a vanilla client"
                  }
                }
                """.formatted(PACK_FORMAT, PACK_FORMAT, SUPPORTED_FORMATS_MAX,
                        MIN_FORMAT[0], MIN_FORMAT[1], MAX_FORMAT[0], MAX_FORMAT[1]);
    }

    /**
     * <b>Every (number → model id) the pack binds, ascending — the ONE list every table walks.</b>
     *
     * <p>The {@code range_dispatch} table ({@link #itemDefinition}) and the per-name native
     * definitions ({@link #nativeDefinitions}) are both written from these rows, so a stack drawn
     * by number and a stack drawn by name cannot disagree about which picture they mean. (Until
     * step 3 a third, ≤1.21.3 {@code overrides} table walked them too.)
     *
     * <p>⚠ <b>The model ids are not uniform, and a careless emitter gets it wrong in exactly one
     * direction.</b> An icon is {@code &lt;ns&gt;:item/wish/&lt;name&gt;} — the {@code wish/}
     * segment is the folder of pictures a WISH may pick — and an {@link Extra} is
     * {@code &lt;ns&gt;:item/&lt;name&gt;}, beside it and not in it. The first probe pack
     * flattened the two and drew the magenta missing cube; deriving both from here rather than
     * rebuilding either is what stops that recurring.
     *
     * <p>Icons and extras share ONE number space, so the collision check runs over the union.
     * Checking them separately is the version of this that passes while an atom cube quietly
     * shadows a wish icon.
     */
    static void collectBindings(Spec spec, List<int[]> rows, List<String> models) {
        collectBindings(spec, rows, models, new ArrayList<>());
    }

    /**
     * The same walk, also naming each model's binding — {@code names.get(i)} is what
     * {@code models.get(i)} is filed under as a native item definition. One walk, so the
     * number table and the native definitions cannot disagree about what a binding draws.
     */
    static void collectBindings(Spec spec, List<int[]> rows, List<String> models,
                                List<String> names) {
        List<Icon> sorted = new ArrayList<>(spec.icons());
        sorted.sort(Comparator.comparingInt(Icon::customModelData));
        List<String> ids = new ArrayList<>();
        for (Icon icon : sorted) {
            if (ids.contains(icon.name())) {
                throw new IllegalArgumentException("two icons named " + icon.name());
            }
            ids.add(icon.name());
        }
        for (Icon icon : sorted) {
            rows.add(new int[]{icon.customModelData(), models.size()});
            models.add(NAMESPACE + ":item/wish/" + icon.name());
            names.add(icon.name());
        }
        for (Extra extra : spec.extras()) {
            rows.add(new int[]{extra.customModelData(), models.size()});
            models.add(extraModelId(extra.name()));
            names.add(extra.name());
        }
        rows.sort(Comparator.comparingInt(r -> r[0]));
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i)[0] == rows.get(i - 1)[0]) {
                throw new IllegalArgumentException(
                        "two models share custom_model_data " + rows.get(i)[0]
                                + " — one of them can never be reached");
            }
        }
    }

    /**
     * <b>The carrier's item definition: one {@code range_dispatch} over every bound number.</b>
     *
     * <p>♻️ SHIM (a child's saved stacks carry only the number): since item models, no NEW stack
     * reaches this table — every mint site names its own {@code item_model}
     * ({@link NativeItemModels}), drawn by {@link #nativeDefinitions}. What still reaches it is
     * every stack a 1.21.1 jar ever minted, which Minecraft's own data fixer upgrades on load to
     * {@code custom_model_data: {floats:[N]}} and nothing of ours ever rewrites. So it stays,
     * forever, and {@code NativeItemPathGameTest} drives it over the whole id range because no call
     * graph does. docs/COMPAT_SHIMS.md has the row.
     *
     * <p>1.21.4 deleted {@code overrides} and put the binding in {@code assets/&lt;ns&gt;/items/}.
     * Until step 3 the pack carried both dialects of this table (D11's union); the
     * {@code overrides} half went when the server began refusing pre-1.21.4 clients at join.
     *
     * <h2>Why {@code range_dispatch} and not {@code select}</h2>
     *
     * <p>{@code select} switches on a STRING and would be the natural 1.21.4-native spelling, and
     * it cannot see what this table exists for: a stack minted by a 1.21.1 jar carries a single
     * {@code int}, which Minecraft's data fixer (and, behind the proxy, ViaVersion — MEASURED)
     * delivers as the newer component's {@code floats} list at index 0 — so the property
     * that can see it is the numeric one, and the model type that dispatches on a number is this
     * one.
     * If that translation ever moves, <b>this method and {@link #dispatchProperty} are the whole
     * fix</b>; nothing else in the class knows the shape.
     *
     * <h2>Two facts read out of 1.21.4's own bytecode, because both fail silently</h2>
     *
     * <ol>
     *   <li><b>Entries must be sorted ASCENDING</b>, exactly as the old override table had to be —
     *       for a different mechanical reason. {@code RangeSelectItemModel} scans linearly below 16 entries and calls
     *       {@code Arrays.binarySearch} at 16 or more; this pack has 38, so the binary search is
     *       what runs, and a binary search over an unsorted array returns a real, valid, wrong
     *       index. The union has 38 entries today and would silently change algorithm at 15.</li>
     *   <li><b>A stack with NO {@code custom_model_data} reads 0.0f, not NaN.</b> The property's
     *       getter answers the float at its index and falls through to {@code 0.0F} when the
     *       component is absent or the list is short. With every threshold at 1 or above, 0.0 is
     *       below the first and resolves to the {@code fallback} — which is why {@link Icon} and
     *       {@link Extra} both refuse a number at or below zero.</li>
     * </ol>
     */
    static String itemDefinition(Spec spec) {
        List<int[]> rows = new ArrayList<>();
        List<String> models = new ArrayList<>();
        List<String> names = new ArrayList<>();
        collectBindings(spec, rows, models, names);
        List<String> nodes = nodes(spec, models, names);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"model\": {\n");
        sb.append("    \"type\": \"minecraft:range_dispatch\",\n");
        sb.append("    \"property\": \"").append(dispatchProperty()).append("\",\n");
        sb.append("    \"index\": ").append(DISPATCH_INDEX).append(",\n");
        sb.append("    \"entries\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append("      { \"threshold\": ").append(rows.get(i)[0]).append(", \"model\": ")
              .append(nodes.get(rows.get(i)[1]))
              .append(i == rows.size() - 1 ? " }\n" : " },\n");
        }
        sb.append("    ],\n");
        // The un-wished carrier: a pack file REPLACES vanilla's definition, so without this a real
        // leather horse armour from a dungeon draws nothing at all. It names VANILLA's own model —
        // the pack ships no models/item/<carrier>.json step 3. Byte-for-byte what 1.21.4
        // and 26.2 ship for this item themselves.
        sb.append("    \"fallback\": ")
          .append(modelNode("minecraft:item/" + spec.baseItemPath(), spec.dyeTint()))
          .append("\n  }\n}\n");
        return sb.toString();
    }

    /**
     * The numeric property the ≥1.21.4 half dispatches on, and the index into its list.
     *
     * <p>Its own method so that a change to the measured translation is a change HERE and nowhere
     * else — {@code docs/FORWARD_COMPAT.md} is the measurement it encodes.
     */
    public static String dispatchProperty() {
        return "minecraft:custom_model_data";
    }

    /** Which entry of {@code custom_model_data}'s {@code floats} list carries the number. */
    public static final int DISPATCH_INDEX = 0;

    /**
     * One leaf model node, with the carrier's tint.
     *
     * <p>The {@code tints} array is indexed by the model's own tint index — {@code layer0} for a
     * generated icon, {@code tintindex: 0} for an atom cube's faces — so ONE entry tints index 0
     * and nothing else, as the old Java leather handler did. It is also character-for-character what vanilla's own {@code items/leather_horse_armor.json} carries on
     * both 1.21.4 and 26.2.
     */
    private static String modelNode(String modelId, Integer dyeTint) {
        return modelNode(modelId, dyeTint, false);
    }

    /**
     * The same, and for an icon with a second colour a second tint: layer1 from
     * {@code custom_model_data.colors[ACCENT_INDEX]}. Its last layer (the outline, the fixed
     * colours) has no entry, which 1.21.4 draws untinted — {@code ItemRenderer.getLayerColorSafe}
     * answers -1 past the end of the list, read out of the source rather than assumed.
     */
    private static String modelNode(String modelId, Integer dyeTint, boolean accent) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"type\": \"minecraft:model\", \"model\": \"").append(modelId).append("\"");
        if (dyeTint != null) {
            sb.append(", \"tints\": [ { \"type\": \"minecraft:dye\", \"default\": ")
              .append(dyeTint).append(" }");
            if (accent) {
                sb.append(", { \"type\": \"minecraft:custom_model_data\", \"index\": ")
                  .append(ACCENT_INDEX).append(", \"default\": ").append(ACCENT_DEFAULT).append(" }");
            }
            sb.append(" ]");
        }
        sb.append(" }");
        return sb.toString();
    }

    /** One icon's model: {@code item/generated} over the pack's own sprites. */
    static String iconModel(Icon icon) {
        return layersModel(icon.layers());
    }

    /** {@code item/generated} over these sprites, in order. */
    static String layersModel(List<String> sprites) {
        Map<String, String> layers = new LinkedHashMap<>();
        for (int i = 0; i < sprites.size(); i++) {
            layers.put("layer" + i, textureId(sprites.get(i)));
        }
        return "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n"
                + layerBlock(layers, "    ")
                + "\n  }\n}\n";
    }

    private static String layerBlock(Map<String, String> layers, String indent) {
        List<String> rows = new ArrayList<>();
        // Sorted so layer0 precedes layer10; a map's own order is not a promise and a swapped pair
        // silently tints the outline instead of the body.
        layers.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> layerIndex(e.getKey())))
                .forEach(e -> rows.add(indent + "\"" + e.getKey() + "\": \"" + e.getValue() + "\""));
        return String.join(",\n", rows);
    }

    private static int layerIndex(String key) {
        try {
            return Integer.parseInt(key.substring("layer".length()));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
