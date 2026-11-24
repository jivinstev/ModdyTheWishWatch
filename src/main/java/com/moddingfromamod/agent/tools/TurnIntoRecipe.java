package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.moddingfromamod.datapack.BlockRef;
import com.moddingfromamod.datapack.ChangeBlock;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.datapack.ItemRef;
import com.moddingfromamod.datapack.PickList;
import com.moddingfromamod.datapack.RegistryExists;
import com.moddingfromamod.datapack.TurnInto;

/**
 * <b>What {@code turn_into}'s FROM and TO mean </b> — each name resolved to the form the
 * world matches (a block, a block tag) and the form his inventory matches (an item, an item tag, a
 * thing he made), then handed to {@link TurnInto#plan}, which decides the mode and writes the lines.
 *
 * <p>Here rather than in {@link TurnInto} because a made item is only resolvable through the item
 * store and {@link MakeItemTool}'s give/match arguments — the same two {@code convert} used, so a
 * machine in a chain ("roast then grind") still takes HIS Roasted Beans and not any dried kelp.
 */
final class TurnIntoRecipe {

    private TurnIntoRecipe() {
    }

    /** Fill {@code p} for one turn_into, or say why it cannot be made. */
    static String derive(String shape, Map<String, String> p, RegistryExists registry, TurnInto.Kinds kinds) {
        List<String> fromRaw = TurnInto.list(p.get("FROM"));
        if (fromRaw.isEmpty()) {
            return "turn_into's FROM must name what changes: a block or item id (minecraft:stone), a "
                    + "tag (#minecraft:logs), something he made (the id make_item handed back), any_block, "
                    + "or several of those as a list. NO DEFAULT: a thing whose FROM you guessed is one he "
                    + "cannot work.";
        }
        if (fromRaw.size() > PickList.MAX) {
            return "turn_into's FROM lists " + fromRaw.size() + " things; at most " + PickList.MAX + ".";
        }
        List<TurnInto.From> from = new ArrayList<>();
        for (String f : fromRaw) {
            Object r = from(f, registry, kinds);
            if (r instanceof String why) {
                return why;
            }
            from.add((TurnInto.From) r);
        }

        String toRaw = p.get("TO");
        TurnInto.To to;
        if (toRaw == null || toRaw.isBlank()) {
            return "turn_into's TO must name what it becomes: a block or item (gold, minecraft:glass, "
                    + "the id make_item handed back for something he made), minecraft:air to make blocks "
                    + "vanish, or furnace for whatever a furnace makes of each one.";
        }
        if (TurnInto.isFurnace(toRaw)) {
            to = TurnInto.To.smelter();
        } else if (TurnInto.isVanish(toRaw)) {
            to = new TurnInto.To("nothing", List.of("minecraft:air"), null, null, false);
        } else {
            String mode = PickList.modeOf(toRaw);
            List<String> each = PickList.items(PickList.withoutMode(toRaw));
            if (each.size() > 1 && mode == null) {
                return "turn_into's TO lists " + each.size() + " things but not how to choose: put cycle: "
                        + "in front for the next one each time, in the order listed (a rainbow), or random: "
                        + "for a different one each time — e.g. \"cycle: minecraft:red_wool, "
                        + "minecraft:orange_wool\".";
            }
            if (each.size() > PickList.MAX) {
                return "turn_into's TO lists " + each.size() + " things; at most " + PickList.MAX
                        + " (a rainbow is seven).";
            }
            if (each.isEmpty()) {
                return "turn_into's TO must name what it becomes, not '" + toRaw + "'.";
            }
            List<String> blocks = new ArrayList<>();
            List<String> gives = new ArrayList<>();
            List<String> names = new ArrayList<>();
            String toMatch = null;
            for (String t : each) {
                if (TurnInto.isVanish(t)) {
                    blocks.add("minecraft:air");
                    gives = null;
                    names.add("nothing");
                    continue;
                }
                Object r = to(t, registry, kinds);
                if (r instanceof String why) {
                    return why;
                }
                String[] forms = (String[]) r;
                if (blocks != null) {
                    if (forms[0] == null) {
                        blocks = null;
                    } else {
                        blocks.add(forms[0]);
                    }
                }
                if (gives != null) {
                    if (forms[1] == null) {
                        gives = null;
                    } else {
                        gives.add(forms[1]);
                    }
                }
                names.add(forms[2]);
                toMatch = forms[3];
            }
            to = new TurnInto.To(TurnInto.either(names), blocks, gives,
                    mode == null ? "random" : mode, false);
            if (from.size() == 1 && each.size() == 1) {
                TurnInto.From only = from.get(0);
                boolean sameBag = only.item() != null && gives != null
                        && only.item().equals(toMatch);
                boolean sameBlock = only.block() != null && blocks != null && only.block().equals(blocks.get(0));
                if ((sameBag || only.item() == null) && (sameBlock || only.block() == null)) {
                    return "turn_into's FROM and TO are both '" + fromRaw.get(0) + "', so it would hand "
                            + "back exactly what it took. Name the thing that changes and the different "
                            + "thing it becomes.";
                }
            }
        }

        boolean fromBlock = from.stream().anyMatch(f -> f.block() != null);
        boolean fromItem = from.stream().anyMatch(f -> f.item() != null);
        String radiusRaw = p.get("RADIUS");
        Integer radius = radiusRaw == null ? null : ChangeBlock.radius(radiusRaw);
        TurnInto.Smelting table = TurnInto.Smelting.load();
        TurnInto.Plan plan = TurnInto.plan(shape, from, to, radius == null ? 1 : radius, table);
        if (plan.mode() == null) {
            return TurnInto.sideRefusal(shape, fromBlock, fromItem,
                    to.furnace() ? plan.blockSide() : to.blocks() != null,
                    to.furnace() ? plan.itemSide() : to.gives() != null, to.furnace());
        }
        if (plan.mode() != TurnInto.Mode.INVENTORY) {
            if (radiusRaw == null || radiusRaw.isBlank()) {
                return "turn_into changes BLOCKS here, so it needs RADIUS: 0 = just the block he points "
                        + "at (held only), 1 = a 3x3x3 patch, up to " + ChangeBlock.MAX_RADIUS + ". 0 for a "
                        + "held thing unless he said it changes LOTS of blocks; a thrown or swung thing "
                        + "needs at least 1.";
            }
            if (radius == null) {
                return "turn_into's RADIUS must be a whole number 0 to " + ChangeBlock.MAX_RADIUS + ", not '"
                        + radiusRaw + "'. 0 is just the block he points at.";
            }
            String badRadius = ChangeBlock.radiusRefusal(shape, radius);
            if (badRadius != null) {
                return badRadius;
            }
            p.put("RADIUS", String.valueOf(radius));
        } else {
            p.remove("RADIUS");   // a machine never touches a block; nothing to keep
        }

        p.put("ACTS", plan.acts());
        p.put("HELPERS", plan.helpers());
        p.put("TARGET", ItemCatalogue.targetFor(shape,
                plan.mode() == TurnInto.Mode.INVENTORY ? "self" : "world", MakeItemTool.PLACED_RADIUS));
        p.put(MODE_KEY, plan.mode().name());
        p.put(AIM_KEY, TurnInto.shapeAims(plan, shape) ? "block" : "self");
        String in = MakeItemTool.jsonSafe(TurnInto.either(plan.takes().isEmpty()
                ? from.stream().map(TurnInto.From::name).toList() : plan.takes()));
        String out = MakeItemTool.jsonSafe(to.name());
        p.put(MakeItemTool.SIGN_2, com.moddingfromamod.item.MachineFill.fit(in.toLowerCase(Locale.ROOT)));
        p.put(MakeItemTool.SIGN_3, com.moddingfromamod.item.MachineFill.fit("→ " + out));
        p.put(MakeItemTool.RECIPE, in + " → " + out);
        return null;
    }

    /** Scratch keys (never rendered): the derived mode, and where the shape must stand the effect. */
    static final String MODE_KEY = "_MODE";
    static final String AIM_KEY = "_AIM";

    /** One FROM, or the sentence that says why it is not one. */
    private static Object from(String raw, RegistryExists registry, TurnInto.Kinds kinds) {
        String f = raw.trim();
        String low = f.toLowerCase(Locale.ROOT);
        if (TurnInto.isAnyBlock(f)) {
            return new TurnInto.From("any block", TurnInto.ANY, null, null);
        }
        if (TurnInto.isFurnace(f) || TurnInto.isVanish(f)) {
            return "turn_into's FROM names '" + raw + "', which is something it can turn things INTO "
                    + "(TO), not something it changes.";
        }
        if (low.startsWith("#")) {
            if (!TurnInto.isTag(low)) {
                return "turn_into's FROM names '" + raw + "', which is not a tag: a tag is like #minecraft:logs.";
            }
            String block = kinds.isBlockTag(low) ? low : null;
            String item = kinds.isItemTag(low) ? low : null;
            if (block == null && item == null) {
                return "turn_into's FROM names the tag '" + raw + "', and there is no block or item tag by "
                        + "that name. Check the spelling (#minecraft:logs, #minecraft:wool), or name the "
                        + "blocks one by one.";
            }
            return new TurnInto.From(TurnInto.readable(low), block, item, item);
        }
        TurnInto.Material mat = TurnInto.material(f);
        if (mat != null) {
            if (TurnInto.isMaterialWord(f)) {
                return new TurnInto.From(TurnInto.readable(mat.item()), mat.block(), mat.item(), mat.item());
            }
            String id = low.contains(":") ? low : "minecraft:" + low;
            String block = kinds.isBlock(id) ? id : mat.block();
            String item = kinds.isItem(id) ? id : mat.item();
            return new TurnInto.From(TurnInto.readable(id), block, item, item);
        }
        String creature = creatureRefusal(f, raw, kinds);
        if (creature != null) {
            return creature;
        }
        ItemRef ref = ItemRef.parse(f);
        String bad = ref.whyUnusable("turn_into's FROM", registry);
        if (bad != null) {
            return bad + " (FROM may also be a block tag like #minecraft:logs, or any_block.)";
        }
        if (ref.spec() != null) {
            return new TurnInto.From(MakeItemTool.jsonSafe(MakeItemTool.readableName(ref)), null,
                    MakeItemTool.matchArg(ref), null);
        }
        String id = ref.text();
        String block = kinds.isBlock(id) ? id : null;
        String item = kinds.isItem(id) ? id : null;
        if (block == null && item == null) {
            return "turn_into's FROM names '" + raw + "', which is neither a block nor an item.";
        }
        return new TurnInto.From(MakeItemTool.jsonSafe(MakeItemTool.readableName(ref)), block, item, item);
    }

    /** One TO: {block form or null, give argument or null, name}, or the sentence why not. */
    private static Object to(String raw, RegistryExists registry, TurnInto.Kinds kinds) {
        String t = raw.trim();
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("#") || TurnInto.isAnyBlock(t)) {
            return "turn_into's TO names '" + raw + "', which is a group of things. TO is ONE thing it "
                    + "becomes (or a cycle:/random: list of blocks).";
        }
        TurnInto.Material mat = TurnInto.material(t);
        if (mat != null) {
            if (TurnInto.isMaterialWord(t)) {
                return new String[]{mat.block(), mat.item(), TurnInto.readable(mat.name()), mat.item()};
            }
            String id = low.contains(":") ? low : "minecraft:" + low;
            String item = kinds.isItem(id) ? id : mat.item();
            return new String[]{kinds.isBlock(id) ? id : mat.block(), item, TurnInto.readable(id), item};
        }
        ItemRef ref = ItemRef.parse(t);
        String bad = ref.whyUnusable("turn_into's TO", registry);
        if (bad != null) {
            return bad;
        }
        if (ref.spec() != null) {
            // HIS made thing: handed over with its name, icon and colour; never set as a block.
            return new String[]{null, MakeItemTool.giveArg(ref),
                    MakeItemTool.jsonSafe(MakeItemTool.readableName(ref)), MakeItemTool.matchArg(ref)};
        }
        String id = ref.text();
        String block = kinds.isBlock(id) && BlockRef.refusal("turn_into's TO", id, registry) == null ? id : null;
        String item = kinds.isItem(id) ? id : null;
        if (block == null && item == null) {
            return "turn_into's TO names '" + raw + "', which is neither a block nor an item.";
        }
        return new String[]{block, item, MakeItemTool.jsonSafe(MakeItemTool.readableName(ref)), item};
    }

    /**
     * a creature is not a block or an item, so turn_into can never change one ("a wand that turns zombies into
     * chickens" routed here 8 times in 8 before this). Said in a sentence the agent can act on, not as "not an item".
     */
    static String creatureRefusal(String f, String raw, TurnInto.Kinds kinds) {
        String id = f.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (id.isEmpty() || id.startsWith("#")) {
            return null;
        }
        id = id.contains(":") ? id : "minecraft:" + id;
        if (kinds.isBlock(id) || kinds.isItem(id) || !isCreature(id)) {
            return null;
        }
        return "turn_into's FROM names '" + raw + "', which is a CREATURE. turn_into changes blocks and the things he "
                + "carries, never a mob: no ready-made effect turns one creature into another. Tell him so in the reply, "
                + "or build exactly that with author_datapack.";
    }

    private static boolean isCreature(String id) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .containsKey(net.minecraft.resources.Identifier.parse(id));
        } catch (RuntimeException | LinkageError e) {
            return false;   // no registry here (a pure caller): nothing to say
        }
    }
}
