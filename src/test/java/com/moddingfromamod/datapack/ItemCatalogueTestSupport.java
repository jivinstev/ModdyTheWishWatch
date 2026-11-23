package com.moddingfromamod.datapack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plausible arguments for every catalogue entry, shared by the tests that need them.
 *
 * <p>One copy, deliberately. Two of these would drift the moment one was fixed, and the drifted one
 * would keep passing while testing something else — which is exactly how a duplicated scorer once
 * reported "0 correct" against a maintained one saying 8 of 8.
 */
final class ItemCatalogueTestSupport {

    private ItemCatalogueTestSupport() {
    }

    static Map<String, String> shapeArgs(String feature) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("FEATURE", feature);
        m.put("ICON", "10");
        m.put("COLOUR", "9857238");
        m.put("HEX", "#966F16");
        m.put("NAME", "Test Thing");
        m.put("COUNT", "4");
        m.put("HINT", "Right-click to use it.");
        m.put("GIVE_MESSAGE", "You got a Test Thing!");
        // GIVES COMES FROM THE REAL BUILDER, not a stub. It used to be a literal line inside each
        // shape_*.cbs; when a family became expressible it moved into MakeItemTool.gives, and a
        // hand-written copy here would drift from it the moment one was fixed -- the exact failure
        // this file's header warns about. Calling the real thing means these tests keep checking a
        // give line the tool would actually emit.
        m.put("GIVES", com.moddingfromamod.agent.tools.MakeItemTool.gives(
                "held", feature, 10, 4, "Right-click to use it.",
                java.util.List.of(new com.moddingfromamod.agent.tools.MakeItemTool.Variant(
                        "Test Thing", 9857238))));
        m.put("AIM", "/function $namespace:effect");
        m.put("PERIOD_TICKS", "40");
        // WHAT A MACHINE IS BUILT FROM, through the real deriver rather than three literals here.
        // A rendered template with a placeholder still in it compiles perfectly and does nothing,
        // so these have to be present for every cell of the matrix — and taking them from the tool
        // means the matrix keeps checking the blocks a wish would actually be built out of.
        String[] body = com.moddingfromamod.agent.tools.MakeItemTool.machineBody(9857238);
        m.put("BODY_BASE", body[0]);
        m.put("BODY_MID", body[1]);
        m.put("BODY_TOP", body[2]);
        m.put("SIGN_NAME", "Test Thing");
        m.put("SIGN_COLOUR", "#966F16");
        m.put("SIGN_IN", "ice + sugar");
        m.put("SIGN_OUT", "→ Slushie");
        m.put("TRAIL", "minecraft:crit");
        // The magic block's, through the real generator for the same reason.
        m.putAll(MagicBlock.shapeArgs(MagicBlock.defaults(), feature, "minecraft:gold_block", false));
        // The worn wrapper's three -- the real renderer's own, so they cannot drift.
        m.putAll(com.moddingfromamod.agent.tools.MakeItemTool.wornArgs("worn_feet", feature,
                java.util.Map.of()));
        return m;
    }

    /** Plausible values for every parameter, as MakeItemTool would have derived them. */
    static Map<String, String> argsFor(ItemCatalogue.Entry e, String shape) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("TARGET", ItemCatalogue.targetFor(shape, e.appliesTo(), 6));
        for (String d : e.derived()) {
            switch (d) {
                case "TARGET" -> { }
                // MULTI-LINE, deliberately. These three are the only values that can be more than
                // one line, and a single-line stub is what let a real bug through: summon.cbs named
                // its own placeholder in a COMMENT, so lines 2 and 3 of a three-zombie summon
                // escaped the comment and became bare top-level commands. Every unit test passed;
                // the runtime matrix, which uses a real COUNT, was what caught it.
                case "SUMMONS" -> m.put(d, "    /summon minecraft:zombie ~ ~ ~\n"
                        + "    /summon minecraft:zombie ~ ~ ~\n    /summon minecraft:zombie ~ ~ ~");
                case "BOLTS" -> m.put(d, "    /summon minecraft:lightning_bolt ~ ~ ~\n"
                        + "    /summon minecraft:lightning_bolt ~ ~ ~");
                case "BLAST" -> m.put(d, "    /summon minecraft:tnt ~ ~ ~ {fuse:0s}\n"
                        + "    /summon minecraft:tnt ~ ~ ~ {fuse:0s}");
                case "DAMAGE" -> m.put(d, "12");
                case "FIRE_TICKS", "GROW_TICKS", "FREEZE_TICKS" -> m.put(d, "160");
                case "MOTION" -> m.put(d, "2");
                case "FALL_SECONDS" -> m.put(d, "25");
                case "SAFE_GUARD" -> m.put(d, "if block ~ ~ ~ #minecraft:replaceable ");
                case "GLOW" -> m.put(d, "/scoreboard players reset @s mfam_noop");
                // real multi-line fragments, as the tool derives them.
                case "PULLS" -> m.put(d, "    /execute as @e[distance=..6] if entity @s[distance=1.5..3]"
                        + " facing entity @s feet positioned as @s positioned ^ ^ ^-0.6 if block ~ ~ ~"
                        + " #minecraft:replaceable run tp @s ~ ~ ~\n"
                        + "    /execute as @e[distance=..6] if entity @s[distance=3..6] facing entity @s"
                        + " feet positioned as @s positioned ^ ^ ^-0.3 if block ~ ~ ~"
                        + " #minecraft:replaceable run tp @s ~ ~ ~");
                case "OUTCOMES" -> m.put(d, "    /execute if score @s mfam_luck matches 1..80 run summon"
                        + " minecraft:item ~ ~0.5 ~ {Item:{id:\"minecraft:diamond\",count:3}}\n"
                        + "    /execute if score @s mfam_luck matches 81..100 run summon minecraft:chicken"
                        + " ~ ~ ~");
                case "FILL_RADIUS" -> m.put(d, "2");
                case "FEATURE" -> m.put(d, "test_fill");
                default -> m.put(d, "1");
            }
        }
        for (String p : e.params()) {
            // Only the params the tool passes STRAIGHT THROUGH survive to render; the rest were
            // consumed into a derived value. Anything the entry still needs is filled plausibly.
            m.putIfAbsent(p, switch (p) {
                case "MOB" -> "minecraft:zombie";
                case "BLOCK" -> "minecraft:water";
                case "ITEM" -> "minecraft:emerald";
                case "BONUS" -> "minecraft:speed";
                case "SCALE" -> "4";
                case "RATE" -> "4";
                default -> "6";
            });
        }
        if ("load_and_pour".equals(e.id())) {
            // THE REAL GENERATED FRAGMENTS, not "1" stubs: a stub renders commands that
            // would never compile, so a matrix over them would prove nothing about this effect.
            m.putAll(loadAndPourArgs());
        }
        // Drop anything this entry does not declare — mirrors the tool consuming COUNT into SUMMONS.
        m.keySet().retainAll(e.allParams());
        return m;
    }


    /** load_and_pour's arguments as make_item derives them: two ingredients, an extra, a chest. */
    static Map<String, String> loadAndPourArgs() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("INGREDIENTS", "minecraft:ice, minecraft:sugar");
        m.put("EXTRAS", "minecraft:snowball");
        m.put("OUTPUT", "minecraft:mushroom_stew");
        m.put("MAX", "5");
        m.put("WHERE", "chest");
        m.put("MACHINE", "test_machine");
        String bad = com.moddingfromamod.agent.tools.MakeItemTool.deriveFillForTest(m);
        if (bad != null) {
            throw new IllegalStateException(bad);
        }
        return m;
    }
}
