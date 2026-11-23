package com.moddingfromamod.marshal;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>Which vanilla item stands for a wish, so a panel of them can be read at a glance.</b>
 *
 * <p>Pure and Minecraft-free: it answers with an item ID STRING and the caller resolves it. That is
 * what makes the whole mapping unit-testable without booting a game, and it is the repo's standing
 * rule for anything that is really a decision rather than a placement.
 *
 * <p>⚠ <b>Deliberately VANILLA items, not the mod's own 27-icon library.</b> {@code WishIcons} is
 * richer and prettier, and it reaches a client only through a resource pack — so on a client that
 * declined the pack every one of those icons falls back to the same base model and the panel becomes
 * a wall of identical leather horse armour. The item spike measured exactly that failure
 * ({@code vanillapack=drop} → <i>"1 distinct model for 27 icons"</i>). A vanilla item carries its own
 * texture with no pack at all, so this mapping degrades to <i>still readable</i> rather than to
 * <i>indistinguishable</i>. The pack, when present, is then an upgrade rather than a prerequisite.
 *
 * <p>The point is not decoration. A child scanning six identical amethyst shards is reading nothing;
 * the row only becomes a menu when the icons differ in a way that means something.
 */
public final class WatchIcons {

    private WatchIcons() {
    }

    /** Fallback for a wish nothing else claims. */
    public static final String UNKNOWN = "minecraft:amethyst_shard";

    /** ✍ Wish for something new — a name tag is the vanilla item you WRITE ON, used at an anvil. */
    public static final String COMPOSE = "minecraft:name_tag";

    /** 🛠 the Wish Builder — a crafting table is where a thing is put together from parts. */
    public static final String BUILDER = "minecraft:crafting_table";

    /**
     * 👎 That was wrong. A barrier renders as a red no-entry sign — the one vanilla icon a child
     * reads as "no" without being taught, and inert in a slot nothing can be taken from.
     */
    public static final String REJECT = "minecraft:barrier";

    /** ✖ Leave. A door is the thing you go out through. */
    public static final String CLOSE = "minecraft:oak_door";
    /** ✋ Stop my wish — a red "no" that is not 👎's barrier. */
    public static final String STOP = "minecraft:red_concrete";
    /** ↩ Take me back. */
    public static final String BACK_HOME = "minecraft:red_bed";
    /** 💡 Help &amp; links. */
    public static final String LINKS = "minecraft:lantern";
    /** 🏆 My goals. */
    public static final String GOALS = "minecraft:gold_ingot";
    /** the Magician's "Typed wishes: Magician ✪ / Apprentice ✨" toggle on the guide. */
    public static final String WISH_MODE = "minecraft:enchanted_book";

    /** Something the agent is granting right now. */
    public static final String HAPPENING = "minecraft:clock";

    /** ▶ More wishes — the next page. An arrow points the way the page turns. */
    public static final String NEXT_PAGE = "minecraft:arrow";

    /**
     * ◀ Back, EVERYWHERE a menu goes back (2026-11-16: "the arrow doesn't convey go back"): Moddy's own
     * LEFT-pointing arrow ({@code VanillaCarrier#BACK_ARROW_ICON}), drawn white — vanilla has no item that points left.
     * A sprite, not an item id, so it is not in {@link #everyIcon}.
     */
    public static final String BACK = "wish:back_arrow#FFFFFF";   // SPRITE_PREFIX + the sprite, white

    /** ◀ Back — the previous page: the back arrow. */
    public static final String PREV_PAGE = BACK;

    // ── "📖 What do I do now?" and its guide panel — vanilla items a STOCK client draws ──

    /** 📖 What do I do now? — the book you learn things from. */
    public static final String GUIDE = "minecraft:knowledge_book";

    /** 🎯 Your next goal — a compass points the way. */
    public static final String GOAL = "minecraft:compass";

    /** 🏆 Every goal done. */
    public static final String ALL_DONE = "minecraft:cake";

    /** 📜 How to play — a book still being written. */
    public static final String HOW_TO = "minecraft:writable_book";

    /** 📖 Get the guide book — the written book he is given. */
    public static final String GUIDE_BOOK = "minecraft:written_book";

    /** 🎨 Be creative — a painting. */
    public static final String CREATIVE = "minecraft:painting";

    /** The kind {@code WatchSnapshot} writes for a creature — not a {@link MadeKind}. */
    public static final String SPECIES = "species";

    // ── A creation's icon is what it IS, not what kind of thing it is ────────────────────
    //
    // The kind table below answers "a creature", "a building", "an item" — so a child who has made
    // six creatures and three buildings sees six identical eggs and three identical brick blocks,
    // which is a row that says nothing. What each made thing actually is, is known on the server:
    // a creature's colour, the block a building is mostly made of, the item exactly as he holds it.
    // These three spellings carry that to WatchMenu, which is the one place that turns an icon
    // string into a stack. Pure strings, so every rule here is testable with no game.

    /** {@code stack:<catalogue key>} — the item itself, exactly as he holds it. */
    public static final String STACK_PREFIX = "stack:";

    /** {@code wish:<sprite>#RRGGBB} — an icon-library sprite in a colour. */
    public static final String SPRITE_PREFIX = "wish:";

    /** The icon-library sprite a made creature is shown as, in the creature's own colour. */
    public static final String CREATURE_SPRITE = "egg";

    /** A made creature: a wish egg in its own colour, so six creatures are six different eggs. */
    public static String forCreature(int rgb) {
        return sprite(CREATURE_SPRITE, rgb);
    }

    /** A made item: the item itself. */
    public static String forItem(String catalogueKey) {
        return catalogueKey == null || catalogueKey.isBlank() ? forKind(MadeKind.ITEM)
                : STACK_PREFIX + catalogueKey.trim();
    }

    /** {@code wish:<sprite>#RRGGBB}. */
    public static String sprite(String name, int rgb) {
        return SPRITE_PREFIX + name + String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** A {@code wish:} icon, parsed; null for anything else. */
    public record Sprite(String name, int rgb) {
    }

    public static Sprite spriteOf(String icon) {
        if (icon == null || !icon.startsWith(SPRITE_PREFIX)) {
            return null;
        }
        String body = icon.substring(SPRITE_PREFIX.length());
        int hash = body.indexOf('#');
        if (hash <= 0) {
            return null;
        }
        try {
            return new Sprite(body.substring(0, hash), Integer.parseInt(body.substring(hash + 1), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The catalogue key of a {@code stack:} icon; null for anything else. */
    public static String stackKeyOf(String icon) {
        return icon != null && icon.startsWith(STACK_PREFIX) && icon.length() > STACK_PREFIX.length()
                ? icon.substring(STACK_PREFIX.length()) : null;
    }

    /**
     * Blocks that cannot stand for a building in a slot: nothing to see, or no item to show.
     * Fluids are mapped to their bucket instead ({@link #forBuild}).
     */
    private static final java.util.Set<String> NOT_A_LOOK = java.util.Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light",
            "minecraft:barrier", "minecraft:structure_void", "minecraft:fire",
            "minecraft:soul_fire", "minecraft:moving_piston");

    /**
     * A made building: the block it is mostly made of — a castle of stone bricks is stone bricks,
     * a treehouse of oak is oak. Water and lava show as their bucket; nothing visible counts. Ties
     * go to the first block named, so the answer does not depend on map order.
     *
     * @param volumeByBlock block id → how many of it (shape volumes, not exact counts, is enough)
     */
    public static String forBuild(Map<String, Integer> volumeByBlock) {
        String best = null;
        int most = 0;
        if (volumeByBlock != null) {
            for (Map.Entry<String, Integer> e : volumeByBlock.entrySet()) {
                String id = normalise(e.getKey());
                if (id == null || NOT_A_LOOK.contains(id) || e.getValue() == null) {
                    continue;
                }
                if (e.getValue() > most) {
                    most = e.getValue();
                    best = id;
                }
            }
        }
        if (best == null) {
            return forKind(MadeKind.BUILD);
        }
        return switch (best) {
            case "minecraft:water" -> "minecraft:water_bucket";
            case "minecraft:lava" -> "minecraft:lava_bucket";
            default -> best;
        };
    }

    /** {@code stone_bricks} or {@code minecraft:stone_bricks[facing=north]} → {@code minecraft:stone_bricks}. */
    private static String normalise(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String id = block.trim().toLowerCase(Locale.ROOT);
        int cut = id.indexOf('[');
        if (cut >= 0) {
            id = id.substring(0, cut);
        }
        cut = id.indexOf('{');
        if (cut >= 0) {
            id = id.substring(0, cut);
        }
        return id.contains(":") ? id : "minecraft:" + id;
    }

    // ── A remembered wish's icon is what its RECIPE MAKES ─────────────────────────────
    //
    // A preset (a button for a wish he taught) used to be iconised from its WORDS by the keyword
    // rules below, and measured on the 84 wishes a child actually typed that sends 34 of them to
    // the amethyst. Its recipe already says what it makes — `wishbuild castle`, `wishmob spawn
    // spikor` — so the icon comes from THAT, the same honest source the creation rows use. This is
    // the pure half: reading a command. The server looks the thing up (wish.PresetIcons).

    /** What one recipe command makes: a kind of thing and its id. */
    public record RecipeTarget(String kind, String id) {
    }

    /**
     * Read one recipe command. Answers null for a command that makes nothing we can picture —
     * a tellraw, a weather change — so the next command gets its turn.
     */
    public static RecipeTarget recipeTarget(String command) {
        if (command == null) {
            return null;
        }
        String[] t = command.trim().replaceFirst("^/", "").split("\\s+");
        if (t.length < 2) {
            return null;
        }
        return switch (t[0]) {
            case "wishdatapack" -> new RecipeTarget(MadeKind.ITEM, t[1]);
            case "wishbuild" -> new RecipeTarget(MadeKind.BUILD, t[1]);
            case "wishpaint" -> new RecipeTarget(MadeKind.PAINTING, t[1]);
            case "moddyevent" -> t.length >= 3 && t[1].equals("stage")
                    ? new RecipeTarget(MadeKind.EVENT, t[2]) : null;
            case "wishmob" -> t.length >= 3 && t[1].equals("spawn")
                    ? new RecipeTarget(SPECIES, t[2]) : null;
            case "summon" -> new RecipeTarget(MadeKind.MOB, normalise(t[1]));
            default -> null;
        };
    }

    /** A vanilla mob's own spawn egg — the item the game itself uses to stand for that mob. */
    public static String forVanillaMob(String entityId) {
        String id = normalise(entityId);
        return id == null ? forKind(MadeKind.MOB) : id + "_spawn_egg";
    }

    /** What each kind of made thing looks like. Keys are {@link MadeKind}'s own strings. */
    private static final Map<String, String> BY_KIND = Map.of(
            MadeKind.CREATURE, "minecraft:cow_spawn_egg",
            MadeKind.MOB, "minecraft:cow_spawn_egg",
            // ⚠ "species" is what the panel's own snapshot (WatchSnapshot.creature) actually SENDS
            // for a creature, and it was missing — so every creature a child had made showed as the
            // amethyst fallback (found from a screenshot). The test that pinned
            // this table fed it MadeKind's constants, never what the snapshot writes.
            SPECIES, "minecraft:cow_spawn_egg",
            MadeKind.BUILD, "minecraft:bricks",
            MadeKind.DATAPACK, "minecraft:enchanted_book",
            MadeKind.ITEM, "minecraft:diamond",
            MadeKind.PAINTING, "minecraft:painting",
            // A staged event.
            MadeKind.EVENT, "minecraft:lightning_rod");

    /**
     * Words that decide a preset's icon, in order — FIRST MATCH WINS, so the list is ordered by how
     * specific the word is rather than alphabetically.
     *
     * <p>Ordering is load-bearing and is the one thing a test has to pin: "make it rain chickens"
     * contains both <i>rain</i> and <i>chickens</i>, and it is a wish for chickens. Sorting this
     * list, or letting a later rule win, silently turns it into a wish about weather.
     */
    private static final List<Rule> RULES = List.of(
            new Rule("minecraft:chicken_spawn_egg", "chicken", "cluck"),
            new Rule("minecraft:cow_spawn_egg", "creature", "animal", "pet", "monster", "dragon",
                    "puppy", "dog", "cat", "mob"),
            new Rule("minecraft:chest", "treasure", "rich", "diamond", "gold", "loot", "present"),
            new Rule("minecraft:elytra", "fly", "flying", "wings", "float"),
            new Rule("minecraft:sugar", "fast", "speed", "quick", "zoom"),
            new Rule("minecraft:clock", "night", "day", "time", "spooky", "dark"),
            new Rule("minecraft:bricks", "tower", "house", "castle", "build", "base", "shop"),
            new Rule("minecraft:minecart", "train", "rail", "track"),
            new Rule("minecraft:piston", "machine", "factory", "contraption", "engine"),
            new Rule("minecraft:cake", "cake", "food", "eat", "feast", "pizza"),
            new Rule("minecraft:blaze_powder", "fire", "lava", "burn", "flame"),
            new Rule("minecraft:snowball", "snow", "ice", "frozen", "cold"),
            new Rule("minecraft:water_bucket", "rain", "water", "storm", "weather"),
            new Rule("minecraft:golden_apple", "strong", "power", "super", "big"));

    private record Rule(String item, String... words) { }

    /** The icon for a thing he has already made. */
    public static String forKind(String kind) {
        if (kind == null) {
            return UNKNOWN;
        }
        return BY_KIND.getOrDefault(kind.trim().toLowerCase(Locale.ROOT), UNKNOWN);
    }

    /**
     * The icon for a preset, read off the wish itself.
     *
     * <p>A preset carries no kind — {@code WishCatalog.Preset} is (label, wishText, templateId) and
     * nothing more — so the only thing available to key on is the sentence a child would read. That
     * is a keyword match, with all the imprecision that implies, and it is still worth doing: the
     * alternative on the shipped panel was six identical shards.
     */
    public static String forWish(String label, String wishText) {
        return forWish(label, wishText, "");
    }

    /**
     * The icon for a preset that may NAME its own.
     *
     * <p><b>The explicit icon wins, and the keyword scan is the fallback</b> — never the other way
     * round. The scan is a first-match sweep over ~50 rules against a label the agent invented, so a
     * recipe id like {@code sparkly_cheese_island} matches nothing and every one of them lands on
     * the same amethyst shard; a curated row saying {@code minecraft:cake} is a person's answer and
     * there is nothing to improve on it by guessing.
     *
     * <p>A nonsense value falls through to the scan rather than being drawn: an unresolvable item id
     * renders as a missing model on one path and as an empty slot on another, and a curated button
     * that shows nothing is worse than one that scanned its words. {@code LibrarySnapshot.iconOf}
     * does that filtering when the template is read, so what arrives here is either a plausible
     * namespaced id or {@code ""}.
     */
    public static String forWish(String label, String wishText, String explicitIcon) {
        if (!isUnset(explicitIcon)) {
            return explicitIcon.trim();
        }
        String hay = ((label == null ? "" : label) + " " + (wishText == null ? "" : wishText))
                .toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            for (String word : rule.words()) {
                if (hay.contains(word)) {
                    return rule.item();
                }
            }
        }
        return UNKNOWN;
    }

    /**
     * <b>True for an icon that says nothing</b>: blank, or {@link #UNKNOWN} itself. The
     * amethyst is this class's own "no idea" answer, and before creation icons it was written into stored
     * presets as if it were a choice — so a button saved that way would show it forever, because an
     * explicit icon always wins. Reading it as unset re-assesses every such button from its recipe
     * and words the next time the panel is drawn, with nothing rewritten in anyone's save.
     */
    public static boolean isUnset(String icon) {
        return icon == null || icon.isBlank() || UNKNOWN.equals(icon.trim());
    }

    /** Every item id this class can ever return — so a gate can check they are all real. */
    public static List<String> everyIcon() {
        List<String> out = new java.util.ArrayList<>(List.of(UNKNOWN, COMPOSE, BUILDER, REJECT, CLOSE, STOP, BACK_HOME, LINKS, GOALS,
                HAPPENING, NEXT_PAGE, GUIDE, GOAL, ALL_DONE, HOW_TO, GUIDE_BOOK, CREATIVE));
        out.addAll(BY_KIND.values());
        for (Rule rule : RULES) {
            out.add(rule.item());
        }
        return List.copyOf(out.stream().distinct().toList());
    }
}
