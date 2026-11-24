package com.moddingfromamod.compat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.component.CustomModelData;

/**
 * <b>The one integer we keep in {@code custom_model_data}</b> (1.21.4 half).
 *
 * <p>1.21.4 rebuilt the component as four lists ({@code floats, flags, strings, colors}) read by the
 * client item definition's {@code range_dispatch}/{@code select}. Our int goes in {@code floats[0]},
 * which is exactly where a 1.21.1 client's int lands after a version-translating proxy (ViaProxy) translates it
 * (docs/VERSION_STRATEGY.md section 4.7) -- so one resource pack serves both. The largest id we store is
 * ~2000, far below 2^24, so the float round-trip is exact.
 *
 * <p><b>The component is also SPELLED in text</b> — every give line we render, every datapack a
 * template or the agent writes, every {@code /clear} predicate — and 1.21.4 refuses the 1.21.1
 * spelling outright: {@code custom_model_data=3} is <i>"Malformed 'minecraft:custom_model_data'
 * component: 'Not a map: 3'"</i>. Twenty Gate B tests went red on exactly that the first time
 * 1.21.4 booted. So the spelling lives here too, beside the value, and
 * {@link #correctSpelling} turns the other target's spelling into this one at the choke points
 * ({@code wish.CommandDialect}).
 */
public final class ModelData {
    private ModelData() {
    }

    public static CustomModelData of(int id) {
        return new CustomModelData(List.of((float) id), List.of(), List.of(), List.of());
    }

    /** The integer this component carries, or 0 when it carries none (1.21.1's default). */
    public static int id(CustomModelData data) {
        Float f = data.getFloat(0);
        return f == null ? 0 : Math.round(f);
    }

    /** How a command or an item predicate spells this component's VALUE on this version. */
    public static String snbt(int id) {
        return "{floats:[" + id + "f]}";
    }

    /** Write the component into a raw item {@code components} compound (entity NBT). */
    public static void put(CompoundTag components, int id) {
        CompoundTag cmd = new CompoundTag();
        ListTag floats = new ListTag();
        floats.add(FloatTag.valueOf(id));
        cmd.put("floats", floats);
        components.put("minecraft:custom_model_data", cmd);
    }

    // ── The stack names its own picture ──────────────────────────────────────────────────────

    /** True on 1.21.4, where a stack can carry {@code minecraft:item_model}; false on 1.21.1. */
    public static final boolean NATIVE_ITEM_MODEL = true;

    /**
     * <b>Stamp a wished thing's look onto a stack</b>: the number (kept — see
     * {@code pack.NativeItemModels}) AND, where one is bound, the {@code item_model} that makes the
     * picture independent of the carrier's number table. Every direct mint goes through here, so
     * no new stack rides the carrier.
     */
    public static void stamp(net.minecraft.world.item.ItemStack stack, int id) {
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, of(id));
        String model = com.moddingfromamod.pack.NativeItemModels.forNumber(id);
        if (model != null) {
            stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL,
                    net.minecraft.resources.Identifier.parse(model));
        }
    }

    /**
     * A copy of this stack as a 1.21.1 jar would have minted it: the same components, but no
     * {@code item_model} of its own (the item's default comes back, so it draws by its number).
     * Setting the prototype's own value drops the patch entry rather than writing a duplicate.
     */
    public static net.minecraft.world.item.ItemStack unnamed(net.minecraft.world.item.ItemStack stack) {
        var out = stack.copy();
        out.set(net.minecraft.core.component.DataComponents.ITEM_MODEL,
                out.getItem().components().get(net.minecraft.core.component.DataComponents.ITEM_MODEL));
        return out;
    }

    /** The {@code item_model} this stack names EXPLICITLY (its patch, not the item's default), or null. */
    public static String itemModelOf(net.minecraft.world.item.ItemStack stack) {
        var patch = stack.getComponentsPatch().getPatch(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        return patch == null || patch.isEmpty() ? null : patch.get().toString();
    }

    /**
     * The same stamp as TEXT, for a give line: {@code minecraft:custom_model_data=…} and, where one
     * is bound, {@code ,minecraft:item_model='…'}. No trailing comma. SINGLE-quoted, because a give
     * line is often itself inside a double-quoted string (a JSON command, a CBScript literal) and
     * {@code custom_name='…'} has always travelled that way.
     */
    public static String components(int id) {
        String model = com.moddingfromamod.pack.NativeItemModels.forNumber(id);
        return "minecraft:custom_model_data=" + snbt(id)
                + (model == null ? "" : ",minecraft:item_model='" + model + "'");
    }

    /** True on 1.21.4, where an icon is composed with an overlay; false on 1.21.1. */
    public static final boolean COMPOSED_ICONS = true;

    /**
     * <b>The stamp, with an overlay drawn over the icon</b>: the same number, the same
     * {@code item_model}, and the overlay's name in {@code strings[0]} — which the icon's composed
     * definition ({@code VanillaItemPack.composed}) selects on. A null or unknown overlay is the
     * plain stamp, byte for byte, so an item that does nothing picturable is unchanged. The name is
     * SINGLE-quoted for the reason {@link #components(int)} gives: a give line often travels inside
     * a double-quoted string.
     *
     * <p>The number stays in {@code floats[0]}: the catalogue keys on it, the carrier's
     * {@code range_dispatch} draws by it, and a client translated down to 1.21.1 sees only it.
     */
    public static String components(int id, String overlay) {
        if (!com.moddingfromamod.item.IconOverlays.known(overlay)
                || com.moddingfromamod.pack.NativeItemModels.forNumber(id) == null) {
            return components(id);
        }
        return components(id).replace("minecraft:custom_model_data=" + snbt(id),
                "minecraft:custom_model_data={floats:[" + id + "f],strings:['" + overlay + "']}");
    }

    /**
     * <b>The stamp, with the icon's SECOND colour </b> in {@code colors[0]} — read by the
     * {@code minecraft:custom_model_data} tint on a drawn icon's accent layer
     * ({@code VanillaItemPack.ACCENT_INDEX}). Written only for an icon that HAS an accent, so every
     * other give line is byte for byte what it was; a client translated down to 1.21.1 never sees
     * {@code colors} and draws the flat, one-colour picture instead.
     */
    public static String components(int id, String overlay, int accentRgb) {
        String base = components(id, overlay);
        if (!hasAccent(id)) {
            return base;
        }
        String key = "minecraft:custom_model_data={";
        int at = base.indexOf(key);
        int close = at < 0 ? -1 : base.indexOf('}', at + key.length());
        if (close < 0) {
            return base;
        }
        return base.substring(0, close) + ",colors:[" + (accentRgb & 0xFFFFFF) + "]"
                + base.substring(close);
    }

    /** True when the icon at this number is a drawn library icon with a second colour. */
    public static boolean hasAccent(int id) {
        for (com.moddingfromamod.item.IconSprites.LibraryIcon icon
                : com.moddingfromamod.item.IconSprites.drawn()) {
            if (icon.number() == id) {
                return icon.accent();
            }
        }
        return false;
    }

        /** The icon's second colour ({@code colors[0]}), or null — what an icon picture tints its accent with. */
    public static Integer accentOf(CustomModelData data) {
        return data == null ? null : data.getColor(0);
    }

/** The overlay a stack's icon wears ({@code strings[0]}), or null for none. */
    public static String overlayOf(CustomModelData data) {
        return data == null ? null : data.getString(0);
    }

    /**
     * <b>Put an overlay on a stack minted before there were overlays</b> (the backfill).
     * Only a stack drawn by the icon library is touched: one naming a {@code wishpack:icon/…} model,
     * or an older one naming none but carrying a bound number, which is also given its
     * {@code item_model} (the same picture, and the only way its composite can draw). A
     * worn thing drawn as leather, a real item he built on, or a stack that already has an overlay
     * is left exactly as it is.
     *
     * @return true if the stack changed
     */
    public static boolean withOverlay(net.minecraft.world.item.ItemStack stack, String overlay) {
        if (stack == null || stack.isEmpty() || !com.moddingfromamod.item.IconOverlays.known(overlay)) {
            return false;
        }
        CustomModelData cmd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
        if (cmd == null || overlayOf(cmd) != null) {
            return false;
        }
        int id = id(cmd);
        String bound = com.moddingfromamod.pack.NativeItemModels.forNumber(id);
        String named = itemModelOf(stack);
        String iconDir = com.moddingfromamod.pack.NativeItemModels.NAMESPACE + ":"
                + com.moddingfromamod.pack.NativeItemModels.DIR + "/";
        if (bound == null || !bound.startsWith(iconDir) || (named != null && !named.equals(bound))) {
            return false;
        }
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) id), List.of(), List.of(overlay), List.of()));
        if (named == null) {
            stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL,
                    net.minecraft.resources.Identifier.parse(bound));
        }
        return true;
    }

    /** The same stamp into a raw item {@code components} compound (a display entity's item). */
    public static void putWithModel(CompoundTag components, int id) {
        put(components, id);
        String model = com.moddingfromamod.pack.NativeItemModels.forNumber(id);
        if (model != null) {
            components.putString("minecraft:item_model", model);
        }
    }

    /**
     * <b>Name the model on every carrier stack a command HANDS OUT</b> — the text choke point
     * ({@code wish.CommandDialect}) that catches what no Java mint site sees: a give line an agent
     * or a template wrote, a stored one replayed from a shelf, a datapack function. Only a
     * {@code give} or an {@code item … with} is touched, never a predicate: adding a component to
     * {@code clear @s leather_horse_armor[custom_model_data=…]} would stop it matching every stack
     * minted before this, which is every stack a child already holds.
     */
    public static String nameTheModel(String text, List<String> notes) {
        if (text == null || !text.contains("custom_model_data") || !text.contains("[")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        Matcher m = HANDED_OUT.matcher(text);
        while (m.find()) {
            int open = m.end() - 1;                     // the '[' that opens the components
            int close = closingBracket(text, open);
            if (close < 0 || close < from) {
                continue;
            }
            String inside = text.substring(open + 1, close);
            if (inside.contains("item_model")) {
                continue;                               // it already names one; never second-guess it
            }
            Matcher cmd = OURS.matcher(inside);
            if (!cmd.find()) {
                continue;
            }
            String model = com.moddingfromamod.pack.NativeItemModels.forNumber(
                    Integer.parseInt(cmd.group(2)));
            if (model == null) {
                continue;
            }
            int at = open + 1 + cmd.end();
            out.append(text, from, at).append(",minecraft:item_model='").append(model).append('\'');
            from = at;
            notes.add("custom_model_data " + cmd.group(2) + " + item_model " + model);
        }
        if (from == 0) {
            return text;
        }
        out.append(text.substring(from));
        return out.toString();
    }

    /** A carrier (or the mod's own wish item) being handed out: {@code give <who> <id>[} / {@code with <id>[}. */
    private static final Pattern HANDED_OUT = Pattern.compile(
            "(?:\\bgive\\s+(?:@[a-z](?:\\[[^\\]]*\\])?|[^\\s\\[]+)\\s+|\\bwith\\s+)"
                    + "(?:minecraft:)?(?:leather_horse_armor|moddingfromamod:wish_item)\\[");

    /** This version's spelling of the number, inside a component list. */
    private static final Pattern OURS = Pattern.compile(
            "((?:minecraft:)?custom_model_data\\s*=\\s*\\{\\s*floats\\s*:\\s*\\[\\s*)(\\d+)(?:\\.0+)?[fF]?\\s*]\\s*}");

    /** The index of the ']' that closes the '[' at {@code open}, skipping quoted text; -1 if none. */
    static int closingBracket(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return c == ']' ? i : -1;
                }
            }
        }
        return -1;
    }

    /** 1.21.1's bare-int spelling: {@code custom_model_data=3}, followed by the end of the value. */
    private static final Pattern OTHER =
            Pattern.compile("(custom_model_data\\s*=\\s*)(-?\\d+)(?=\\s*[,\\]}])");

    /**
     * Rewrite the other target's spelling of the component into this one's, noting each rewrite.
     * Only a value that is certainly the other spelling is touched: a bare integer directly after
     * {@code custom_model_data=}, which this version cannot parse at all.
     */
    public static String correctSpelling(String text, List<String> notes) {
        if (text == null || !text.contains("custom_model_data")) {
            return text;
        }
        Matcher m = OTHER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String to = snbt(Integer.parseInt(m.group(2)));
            notes.add("custom_model_data=" + m.group(2) + " -> " + to);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + to));
        }
        m.appendTail(out);
        return out.toString();
    }
}
