package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.mob.ContentSearch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * Lets the agent discover what is <b>actually installed</b> — every mob, item and block from every
 * mod in this player's game.
 *
 * <p>This exists because of a real, disappointing failure. A child asked for a "wither storm". The
 * mod that adds the genuine Wither Storm boss was installed all along, but the agent had no way to
 * know that, so it improvised: a giant black spider wearing a wither-skeleton texture, with cave
 * spiders for tentacles. The child knows exactly what a Wither Storm looks like, and that was not it.
 *
 * <p>The rule this enables: <b>if the real thing exists, summon the real thing.</b> Building a
 * lookalike out of vanilla parts is the last resort, not the first move.
 */
public final class FindContentTool implements McpTool {

    private static final int DEFAULT_LIMIT = 40;
    private static final int MAX_LIMIT = 120;

    @Override
    public String name() {
        return "find_content";
    }

    @Override
    public String description() {
        return "Search everything installed in this player's game — mobs, items and blocks from "
                + "vanilla AND from every installed mod. ALWAYS use this before building something "
                + "from scratch: if the child asks for a 'wither storm' or a 'kraken' and a mod "
                + "already provides one, summon the REAL one instead of improvising a lookalike. "
                + "The same goes for ITEMS: if the thing he named already exists, GIVE him that one "
                + "— it arrives with an icon, a name and behaviour someone already wrote and tested. "
                + "SEARCH THE ODD WORDS HE ACTUALLY SAID, not the kind of thing it is: for a 'butt "
                + "cheek water sword', search 'butt cheek water' (which finds the real drinkable "
                + "item) and NOT 'sword' (which finds every sword in the game and tells you "
                + "nothing). Every word of the query must appear in an id or its name (small misspellings "
                + "are forgiven), so the strange specific phrase is the one that discriminates. Search more than once, with "
                + "different parts of what he said. Leave the query empty to list the installed "
                + "mods.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "query", "string",
                "The child's OWN distinctive words, e.g. 'wither storm', 'butt cheek water'. Not the "
                        + "category ('sword', 'potion', 'block') — every word must appear in an id, "
                        + "so a category noun matches everything and discriminates nothing. Empty = "
                        + "list installed mods.");
        Schemas.prop(s, "type", "string", "Filter: mob | item | block | all (default all).");
        Schemas.prop(s, "limit", "integer", "Max results per category (default 40).");
        Schemas.prop(s, "around", "boolean", "true = only the blocks in his view and the things in his bag WHEN HE "
                + "WISHED (\"In view when he wished\"), ranked by how much of each — \"which stone does he have\". With a "
                + "query, those that match it; with none, all of them. Use it when he says \"this\", \"these blocks\" or "
                + "\"the ground\".");
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String query = args.has("query") && args.get("query").isJsonPrimitive()
                ? args.get("query").getAsString().trim().toLowerCase(Locale.ROOT) : "";
        String type = args.has("type") && args.get("type").isJsonPrimitive()
                ? args.get("type").getAsString().trim().toLowerCase(Locale.ROOT) : "all";
        int limit = DEFAULT_LIMIT;
        if (args.has("limit")) {
            try {
                limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").getAsInt()));
            } catch (RuntimeException ignored) {
                limit = DEFAULT_LIMIT;
            }
        }
        final int max = limit;
        if (args.has("around") && args.get("around").isJsonPrimitive() && args.get("around").getAsBoolean()) {
            // §3: the run's wish-time reading, never the live player (read on this thread: it knows the worker)
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            var seen = run == null ? null : run.sawWhenWished();
            return ToolResult.ok(com.moddingfromamod.content.AroundSearch.answer(seen, query,
                    com.moddingfromamod.content.Found.index(), max));
        }
        try {
            // Registry reads are cheap but still game state — do them on the server thread.
            return ToolResult.ok(AgentBridge.onServer(() -> {
                StringBuilder sb = new StringBuilder();
                if (query.isEmpty()) {
                    sb.append("Installed mods (").append(ModList.get().size()).append("):\n");
                    List<String> mods = new ArrayList<>();
                    ModList.get().forEachModContainer((id, c) -> mods.add(id));
                    mods.sort(String::compareTo);
                    sb.append("  ").append(String.join(", ", mods));
                    sb.append("\n\nSearch with a word to see what they add, e.g. query='wither'.");
                    return sb.toString();
                }
                boolean all = type.equals("all");
                // the ONE search the Builder and the Apprentice use too (content/ContentIndex), so the
                // Magician and the menus can never find different things for the same words.
                if (all || type.startsWith("mob") || type.startsWith("entit")) {
                    section(sb, "MOBS (summon these with run_command)",
                            search(query, java.util.EnumSet.of(com.moddingfromamod.content.ContentIndex.Kind.MOB,
                                    com.moddingfromamod.content.ContentIndex.Kind.ENTITY), max));
                }
                if (all || type.startsWith("item")) {
                    section(sb, "ITEMS (give these)",
                            search(query, java.util.EnumSet.of(com.moddingfromamod.content.ContentIndex.Kind.ITEM), max));
                }
                if (all || type.startsWith("block")) {
                    section(sb, "BLOCKS (setblock/fill with these)",
                            search(query, java.util.EnumSet.of(com.moddingfromamod.content.ContentIndex.Kind.BLOCK), max));
                }
                if (sb.length() == 0) {
                    // RECORDED, so a substitute cannot later be scored as a grant. Asked
                    // for a machine making a fluid from a mod that was not installed, the agent
                    // was told this, built a flour mill, told the child it made that fluid, and
                    // the snapshot said `delivered: 4, working: true`. Nothing could disagree.
                    com.moddingfromamod.wish.MissingContent.record(query);
                    // ⚠ "No mod provides it" is a claim about the WHOLE WORLD, and it is FALSE when
                    // the harness simply failed to boot the mod that does provide it. The
                    // agent then builds a lookalike on the strength of an incomplete world and is
                    // never wrong at any step. Inert unless a launcher declared what it meant to
                    // boot, so a real session is untouched.
                    List<String> loadedMods = new ArrayList<>();
                    ModList.get().forEachModContainer((id, c) -> loadedMods.add(id));
                    String incomplete = com.moddingfromamod.wish.ExpectedMods.qualifier(
                            com.moddingfromamod.wish.ExpectedMods.expected(), loadedMods);
                    return "Nothing installed matches \"" + query + "\". No mod provides it, so now"
                            + " it IS right to build a lookalike: define_creature plus run_command."
                            + "\n  ⚠ Say so when you hand it over. A lookalike is a fair answer to "
                            + "\"make me an X\" when no mod has an X — but telling the child it IS "
                            + "an X is not, and this is the one thing they cannot check by looking."
                            + incomplete;
                }
                sb.append("\nModded results are listed FIRST because they are usually what the child"
                        + " actually means. If one of these IS what they asked for, summon the REAL"
                        + " one with run_command — then use modify_mob to make it bigger, glow a"
                        + " colour, or take a name. Do NOT rebuild it as a wish creature.");
                return sb.toString();
            }, 8_000L));
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * The shared index's best matches, as "id — Name". Match on the full id and the English name, so "wither storm"
     * finds witherstormmod:wither_storm and ranks it above minecraft:wither (a mod's thing first), and "honny blok"
     * finds the honey block. The ranking lives in {@link com.moddingfromamod.content.ContentIndex} (ContentIndexTest).
     */
    private static List<String> search(String query, java.util.Set<com.moddingfromamod.content.ContentIndex.Kind> kinds, int limit) {
        List<String> out = new ArrayList<>();
        for (var h : com.moddingfromamod.content.ContentIndexes.live().search(query, kinds, limit)) {
            out.add(h.entry().id() + " — " + h.entry().name());
        }
        return out;
    }

    private static void section(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(title).append(" — ").append(items.size()).append(" found:\n");
        for (String i : items) {
            sb.append("  ").append(i).append('\n');
        }
        sb.append('\n');
    }
}
