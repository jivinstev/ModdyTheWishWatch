package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>A sure change on an ITEM the Magician made, made at once</b> (the sure, instant
 * "change this thing" answers should speed up Magician wishes too). Creatures ({@link MagicianCreature}) and buildings ({@link MagicianBuilding}) already had it; an item
 * waited for a whole Magician wish.
 *
 * <p>Two of the Builder's item choices work on any item, so they work on a hand-made one: its COLOURS (a scheme's main
 * and accent, a gold scheme on a real tool or armour piece is that tool in gold, every other scheme drawn tinted, as
 * {@link BuilderLowering}'s item does) and its LOOK (a library icon, in the look's own colour). Its power and strength
 * are not: the Magician wrote its effect and parameters by hand, and a Builder power would replace them, so those
 * stay the Magician's and go on to it as typed ({@code null} here).
 *
 * <p>The change is {@code make_item} with {@code start_from} the item's id, which edits the design in place: the same
 * id and name, everything not sent inherited, the stacks he holds changed. Nothing new is made. Pure: no Minecraft.
 */
public final class MagicianItem {

    private MagicianItem() {
    }

    /** A Magician item: its id, name, and the real item it is built on ("" when it is drawn). */
    public record Thing(String id, String name, String baseItem) {
    }

    /** The calls for these ops, or null when one of them is not a change the Builder makes on a hand-made item. */
    public static List<BuilderLowering.Call> calls(Thing t, List<BuilderEdit.Op> ops) {
        if (t == null || ops == null || ops.isEmpty()) {
            return null;
        }
        JsonObject a = new JsonObject();
        a.addProperty("start_from", t.id());
        for (BuilderEdit.Op o : ops) {
            if (o.verb() == BuilderEdit.Verb.REMOVE) {
                return null;
            }
            switch (o.slot()) {
                case "scheme" -> {
                    Schemes.Scheme sc = Schemes.byId(o.value());
                    if (sc == null) {
                        return null;
                    }
                    a.addProperty("colour", sc.main());
                    a.addProperty("accent", sc.accent());
                    String base = t.baseItem() == null ? "" : t.baseItem();
                    String material = base.isEmpty() ? null : BuilderItemPowers.inScheme(base, sc.id());
                    if (material != null) {
                        a.addProperty("base_item", material);
                    } else if (!base.isEmpty()) {
                        a.addProperty("tint", true);
                    }
                }
                case BuilderLooks.SLOT -> {
                    BuilderLooks.Look look = BuilderLooks.look(o.value());
                    if (look == null) {
                        return null;
                    }
                    a.addProperty("icon", look.id());
                    if (!a.has("accent")) {
                        a.addProperty("colour", String.format("#%06X", BuilderLooks.tileColour(look.id())));
                    }
                    if (t.baseItem() != null && !t.baseItem().isEmpty()) {
                        a.addProperty("look", true);
                    }
                }
                default -> {
                    return null;   // its power, strength, name or anything else: the Magician's
                }
            }
        }
        String name = t.name() == null || t.name().isEmpty() ? t.id() : t.name();
        List<BuilderLowering.Call> out = new ArrayList<>();
        JsonObject plan = new JsonObject();
        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        steps.add("change " + name);
        plan.add("steps", steps);
        plan.addProperty("about", t.id());
        plan.addProperty("kind", "item");
        out.add(new BuilderLowering.Call("plan_wish", plan));
        out.add(new BuilderLowering.Call("make_item", a));
        JsonObject fin = new JsonObject();
        fin.addProperty("label", name);
        out.add(new BuilderLowering.Call("finish_building", fin));
        return out;
    }
}
