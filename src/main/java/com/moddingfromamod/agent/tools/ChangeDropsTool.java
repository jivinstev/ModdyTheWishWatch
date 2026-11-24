package com.moddingfromamod.agent.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.AddDropModifier;
import com.moddingfromamod.datapack.DatapackReloader;
import com.moddingfromamod.datapack.DropRule;

import net.minecraft.server.MinecraftServer;

/**
 * <b>"Zombies drop diamonds." "Trees drop apples way more often." "Every chest has extra
 * snacks."</b> One call, no datapack to write. (the {@code loot_table} part.)
 *
 * <p>Before this, every one of those wishes went to {@code author_datapack}. CBScript has no loot
 * keyword, so the honest datapack was a hand-written loot table, and a hand-written loot table
 * REPLACES the vanilla one: zombies that drop diamonds and have stopped dropping rotten flesh.
 * This writes a NeoForge global loot modifier ({@link AddDropModifier}), which adds on top.
 *
 * <p>Every rule lives in one world datapack, {@value #PACK}. Calling again with a new name adds a
 * rule. The same name replaces it, and {@code remove: true} takes it out. The pack is rebuilt from
 * the rules already in it, because installing a pack REPLACES it; {@code author_recipe} measured
 * that the hard way (11 recipes → 10).
 *
 * <p>After the reload the tool reports whether NeoForge actually LOADED the rule, read back from the
 * loot modifier manager, not assumed from a successful write. A modifier NeoForge rejects is
 * invisible in the world, like a rejected recipe.
 */
public final class ChangeDropsTool implements McpTool {

    /**
     * The part this tool IS in the parts inventory (<code>rule.mechanism:loot_table</code>), read by
     * {@code tools/parts-coverage.py}: delete the tool and {@code --check} names the regressions.
     */
    public static final Set<String> PARTS = Set.of("loot_table");

    /** The one world datapack every drop rule lives in. */
    public static final String PACK = "wish_drops";

    @Override
    public String name() {
        return "change_drops";
    }

    @Override
    public String description() {
        return "Make something DROP an extra item, forever: a kind of mob that drops a prize when it "
                + "dies, any mob that sometimes drops a treat, a block that can drop something extra "
                + "when he breaks it, every kind of leaves dropping fruit, structure chests with extra "
                + "food, a special catch when he fishes. ONE CALL. Do NOT write a datapack or a loot "
                + "table for these: a hand-written loot table REPLACES the vanilla one, so the "
                + "zombie stops dropping everything else. This ADDS the new drop on top of what it "
                + "already drops. Say the `source` (mob / block / chest / fishing) and `which` one "
                + "('zombie', 'stone', a tag like '#minecraft:leaves' for every kind of leaves, or "
                + "'any'); `item` is what drops, and a tag ('#minecraft:wool') picks a random one each "
                + "time. 'Sometimes' is a `chance` like 0.1; 'way more often' is a high one. Calling "
                + "again with the same `name` changes that rule; `remove: true` takes it away. "
                + "Chests only change when they are NEWLY generated, so say that to him. A special "
                + "catch for HIS fishing rod (\"my rod catches…\") is make_item's `catches` property "
                + "on a real rod instead: it is only his rod, and it can catch a treasure_chest FULL "
                + "of loot or an enchanted_book, where an extra drop here is an empty chest or a "
                + "plain book, for everybody who fishes.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "source", "string",
                "REQUIRED. Where the drop comes from: 'mob' (a mob dying), 'block' (a block broken), "
                        + "'chest' (a newly generated structure chest), 'fishing' (a catch).");
        Schemas.prop(s, "which", "string",
                "Which one: an id like 'zombie' or 'minecraft:stone', a tag like '#minecraft:leaves' "
                        + "(every kind of leaves) or '#minecraft:logs', or 'any' for every mob / every "
                        + "block / every chest.");
        Schemas.prop(s, "item", "string",
                "REQUIRED. The extra item, e.g. 'minecraft:diamond' or 'cake'; or an item tag like "
                        + "'#minecraft:wool' for a random one of those each time.");
        Schemas.prop(s, "chance", "number",
                "0.01 to 1. 1 (the default) means every time; 'sometimes' is about 0.1.");
        Schemas.prop(s, "count", "integer", "How many drop at once, 1 to " + DropRule.MAX_COUNT
                + " (default 1).");
        Schemas.prop(s, "name", "string",
                "A short name for this rule, e.g. 'zombie_diamonds'. The same name later changes it.");
        Schemas.prop(s, "remove", "boolean", "True = take the rule with this `name` away again.");
        Schemas.required(s, "source", "item");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        boolean remove = args.has("remove") && args.get("remove").isJsonPrimitive()
                && args.get("remove").getAsBoolean();
        final DropRule rule;
        final String name;
        if (remove) {
            name = DropRule.clean(args.has("name") ? args.get("name").getAsString() : "");
            if (name.isEmpty()) {
                return ToolResult.error("Say which rule to remove with its `name`.");
            }
            rule = null;
        } else {
            try {
                rule = DropRule.parse(args);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
            name = rule.name();
        }
        try {
            return AgentBridge.onServer(() -> {
                MinecraftServer server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                Map<String, String> rules;
                try {
                    rules = existingRules(server);
                } catch (Exception e) {
                    return ToolResult.error("Could not read the drops pack: " + e);
                }
                if (remove) {
                    if (rules.remove(name) == null) {
                        return ToolResult.error("There is no drop rule called '" + name + "'. The "
                                + "rules are: " + (rules.isEmpty() ? "(none)" : String.join(", ",
                                rules.keySet())));
                    }
                } else {
                    rules.put(name, rule.toModifierJson(AddDropModifier.TYPE).toString());
                }
                try {
                    DatapackReloader.installAndReload(server, PACK, writePack(rules));
                } catch (Exception e) {
                    return ToolResult.error("Could not install the drops pack: " + e);
                }
                Set<String> live;
                try {
                    live = LiveLootModifiers.ids();
                } catch (RuntimeException e) {
                    return ToolResult.error("The pack installed, but the loaded rules could not be read "
                            + "back, so nobody can say whether it is working: " + e);
                }
                String id = PACK + ":" + name;
                if (remove) {
                    return live.contains(id)
                            ? ToolResult.error("Removed from the pack, but '" + id + "' is STILL "
                                    + "loaded. Try reload_datapacks.")
                            : ToolResult.ok("Removed the drop rule '" + name + "'. It no longer "
                                    + "happens.");
                }
                if (!live.contains(id)) {
                    return ToolResult.error("The rule was written but NeoForge did NOT load it, so "
                            + "nothing new drops. Check that `item` and `which` are real ids.");
                }
                return ToolResult.ok("LOADED. " + rule.describe() + " (rule '" + name + "'; "
                        + rules.size() + " drop rule(s) in this world.) To see it, make it happen: "
                        + "a loaded rule and a drop you watched fall are different claims.");
            }, 30_000L);
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** Every rule already in the world's drops pack, by name → modifier JSON. */
    static Map<String, String> existingRules(MinecraftServer server) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Path existing = DatapackReloader.worldDatapackDir(server).resolve(PACK + ".zip");
        if (!Files.isRegularFile(existing)) {
            return out;
        }
        String dir = "data/" + PACK + "/loot_modifiers/";
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(existing.toFile())) {
            var it = zf.entries();
            while (it.hasMoreElements()) {
                var e = it.nextElement();
                String n = e.getName();
                if (!e.isDirectory() && n.startsWith(dir) && n.endsWith(".json")) {
                    try (var in = zf.getInputStream(e)) {
                        out.put(n.substring(dir.length(), n.length() - ".json".length()),
                                new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return out;
    }

    /** The whole pack, rebuilt from every rule — installing a pack replaces the one before. */
    static Path writePack(Map<String, String> rules) throws Exception {
        Path zip = Files.createTempDirectory("change-drops").resolve(PACK + ".zip");
        JsonObject global = new JsonObject();
        global.addProperty("replace", false);
        JsonArray entries = new JsonArray();
        for (String n : new TreeSet<>(rules.keySet())) {
            entries.add(PACK + ":" + n);
        }
        global.add("entries", entries);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buf)) {
            put(out, "pack.mcmeta", com.moddingfromamod.datapack.DatapackDialect.mcmeta("wish drops"));
            put(out, "data/neoforge/loot_modifiers/global_loot_modifiers.json", global.toString());
            for (var e : rules.entrySet()) {
                put(out, "data/" + PACK + "/loot_modifiers/" + e.getKey() + ".json", e.getValue());
            }
        }
        Files.write(zip, buf.toByteArray());
        return zip;
    }

    private static void put(ZipOutputStream out, String name, String body) throws java.io.IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    /**
     * <b>The loot modifiers NeoForge has LOADED right now</b>, by id.
     *
     * <p>From NeoForge 26.2 the {@code LootModifierManager} is a retained reload listener, reached through the
     * server's resources ({@code getListener(NeoForgeReloadListeners.LOOT_MODIFIERS_KEY)}), the way NeoForge's own
     * {@code CommonHooks.modifyLoot} reaches it; before, it sat behind a package-private accessor read by reflection.
     * It fails LOUDLY: no running server, or no manager, goes red in {@code ChangeDropsGameTest}, never quietly
     * reports every rule as "not loaded".
     */
    public static final class LiveLootModifiers {
        private LiveLootModifiers() {
        }

        public static Set<String> ids() {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                throw new IllegalStateException("no running server, so no loaded loot modifiers to read");
            }
            var manager = server.getServerResources().managers()
                    .getListener(net.neoforged.neoforge.resource.NeoForgeReloadListeners.LOOT_MODIFIERS_KEY);
            if (manager == null) {
                throw new IllegalStateException("NeoForge has no loot modifier manager in the server's resources; "
                        + "ChangeDropsTool.LiveLootModifiers must follow it");
            }
            Set<String> out = new TreeSet<>();
            for (var mod : manager.getSortedModifiers()) {
                out.add(String.valueOf(manager.getId(mod)));
            }
            return out;
        }
    }
}
