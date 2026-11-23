package com.moddingfromamod.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.item.IconSprites;
import com.moddingfromamod.vanilla.AtomLibrary;

/**
 * <b>A stack names its own picture (the native item path).</b>
 *
 * <p>On 1.21.4 an item stack can carry {@code minecraft:item_model}, which points at
 * {@code assets/<ns>/items/<path>.json} directly. So a wished thing no longer needs its artwork looked
 * up by number in the carrier's shared table: the stack says what it looks like. This class is the
 * <b>one rule</b> from a binding to that id, and everything that mints a stack or writes a definition
 * asks here:
 *
 * <pre>  binding name  cup        -&gt;  item_model  wishpack:icon/cup
 *                                  definition  assets/wishpack/items/icon/cup.json</pre>
 *
 * <p><b>One id, two providers, each self-consistent.</b> A stock client gets the definition from the
 * pushed item pack, which points it at the pack's own {@code wishpack:item/...} model. A modded
 * client is never pushed that pack ({@code ItemPackService.offerTo}), and gets the same-named
 * definition out of the mod jar, which points at the jar's {@code moddingfromamod:item/...} model.
 * Neither side knows the other exists, and the stack is identical for both.
 *
 * <p><b>In the pack's own namespace, {@code wishpack}, not the mod's</b> — for two reasons. The
 * pack's standing control ({@code VanillaItemPackTest}: nothing a stock client resolves names the
 * mod) stays answerable. And {@code moddingfromamod} registers items of its own, whose DEFAULT
 * definition 1.21.4 reads from {@code items/<its id>.json}: the watch's binding is named
 * {@code wish_watch}, which is also a mod item, and a white-dyed carrier definition there would tint
 * the mod watch's face undyed-leather brown. The jar ships its copies under {@code wishpack} too.
 *
 * <p><b>The number stays on the stack.</b> {@code custom_model_data} is still stamped beside
 * {@code item_model}: it is what {@code WishItemCatalogue} keys a stack on, what a datapack's
 * predicate matches, and what the 1.21.1 compat build draws with. Only the <i>picture</i> stops
 * depending on it. An OLD stack, carrying only the number, still draws through the union pack's
 * {@code range_dispatch} half — that half is now a {@code ♻️ SHIM} (docs/COMPAT_SHIMS.md), because a
 * child's saved stacks are held by somebody else.
 */
public final class NativeItemModels {

    /** The namespace every native item definition lives in, in the jar and in the pack alike. */
    public static final String NAMESPACE = VanillaItemPack.NAMESPACE;

    /** The folder under {@code items/}, kept apart from the mod's own item ids (see class doc). */
    public static final String DIR = "icon";

    private NativeItemModels() {
    }

    /** One binding: the number an old stack carries, the name its definition is filed under. */
    public record Binding(int customModelData, String name) {
    }

    private static volatile Map<Integer, Binding> byNumber;

    /**
     * <b>Every binding, ascending by number</b>: the wish icons, Moddy, and the atom rig's cubes —
     * the same three populations the carrier's number table binds, so nothing a 1.21.1 jar ever
     * minted is left without a native twin.
     */
    public static List<Binding> all() {
        return List.copyOf(table().values());
    }

    /** The {@code item_model} id for this binding NAME. */
    public static String itemModelId(String name) {
        return NAMESPACE + ":" + DIR + "/" + name;
    }

    /** Where its definition is filed, inside a pack or the jar. */
    public static String definitionPath(String name) {
        return "assets/" + NAMESPACE + "/items/" + DIR + "/" + name + ".json";
    }

    /**
     * The {@code item_model} id a stack carrying this number should name, or null when nothing is
     * bound at it — never a default, because a number we never bound is not ours to draw.
     */
    public static String forNumber(int customModelData) {
        Binding b = table().get(customModelData);
        return b == null ? null : itemModelId(b.name());
    }

    private static Map<Integer, Binding> table() {
        Map<Integer, Binding> t = byNumber;
        if (t == null) {
            t = build();
            byNumber = t;
        }
        return t;
    }

    private static Map<Integer, Binding> build() {
        List<Binding> rows = new ArrayList<>();
        for (IconSprites.Sprite s : IconSprites.bindings()) {
            rows.add(new Binding(s.customModelData(), s.name()));
        }
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            rows.add(new Binding(e.customModelData(), e.modelName()));
        }
        rows.sort(java.util.Comparator.comparingInt(Binding::customModelData));
        Map<Integer, Binding> out = new LinkedHashMap<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        for (Binding b : rows) {
            if (out.put(b.customModelData(), b) != null) {
                throw new IllegalStateException("two bindings share custom_model_data "
                        + b.customModelData());
            }
            Integer was = names.put(b.name(), b.customModelData());
            if (was != null) {
                throw new IllegalStateException("binding name '" + b.name() + "' is used by "
                        + was + " and " + b.customModelData() + " — one item_model id would "
                        + "draw two different things");
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
