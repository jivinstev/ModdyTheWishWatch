package com.moddingfromamod.wish;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>What a wish may run — ONE table, one row per command</b>. Pure, so the whole policy is
 * unit-testable against a nasty-input corpus without booting the game.
 *
 * <p><b>Why a table.</b> This used to be five sets (allowed, forbidden, tag-scoped, sub-commands,
 * game rules) and anything in none of them was refused by omission, with its reason written down
 * nowhere — which is how {@code locate}, the one command a "take me to a jungle" wish needs, came to
 * be refused three times in three weeks of live play while nobody had ever decided it should be.
 * Now every command has a {@link Rule}: a {@link Tier}, the one-line reason, and for a
 * {@link Tier#LIMITED} row the {@link Limit} that implements it. The review behind each row is
 *; change a row there first.
 *
 * <p><b>Every root the server registers must have a row</b> — {@code CommandPolicyGameTest} walks the
 * live dispatcher and fails on any that does not, so a new vanilla version or a new mod command is
 * decided here rather than refused (or allowed) by accident. A root with no row is still refused:
 * the table is an allowlist, and a list of what is allowed fails safe.
 *
 * <p><b>It recurses through {@code execute}.</b> {@code execute} can wrap any other command after
 * {@code run}, so a guard that only inspects the first token is trivially defeated by
 * {@code execute as @a run stop}. Every {@code run} tail is re-checked, to a bounded depth.
 *
 * <p>Enforced wherever a wish's command runs: {@code run_command}, {@code add_template},
 * {@code set_rule}, a template replay ({@code WishExecutor}), {@code wishspot} and
 * {@code ReplayContract} — and every compiled datapack ({@code datapack.DatapackCommands}): its
 * {@link Tier#DENY} rows (step 1, refusing none of the 178 packs children have wished) and the
 * player rules below (step 2). A datapack's other LIMITED rows ({@code kill @s} is a fireball removing
 * itself) are deliberately not applied there.
 *
 * <h2>How a row is decided (the rubric)</h2>
 *
 * <p><b>Wishes are open-ended: a command is ALLOWED unless its abuse is LIKELY and CANNOT be
 * prevented by a simple rule.</b> For each command the review answers, in this order:
 * <ol>
 *   <li>what the command is;</li>
 *   <li>two or three valid wishes that would use it;</li>
 *   <li>two or three wishes that would abuse it against our standards;</li>
 *   <li>how likely each is, for a 5–12-year-old typing a wish (H: within weeks of play, M: months,
 *       L: rare) — "abuse" includes an innocent wish that does harm by accident;</li>
 *   <li>whether a simple rule keeps the valid wishes and stops the abuse — that rule is the row's
 *       {@link Limit};</li>
 *   <li>the verdict: {@link Tier#ALLOW}, {@link Tier#LIMITED} with that limit, or {@link Tier#DENY}.</li>
 * </ol>
 * The per-command answers are in the comments beside each reviewed row below; the full tables are
 *.
 *
 * <p><b>Three kinds of harm are weighed, and they fail differently:</b>
 * <ul>
 *   <li><b>words a child reads</b> — handled by the {@code showsText} column: those rows' arguments
 *       go through {@code SafetyTextSweep}, so a text command is never denied for its text alone;</li>
 *   <li><b>another child as the subject</b> — handled OUTSIDE this table, by the selector and name
 *       rules (see the next table), so a command is not denied merely because it could name a
 *       player;</li>
 *   <li><b>the shared server</b> — the whole server's clock, every child's world at once, money on a
 *       metered server, or a frozen server thread. No selector rule can scope these, so they are the
 *       ones that end up DENY ({@code tick}, {@code difficulty}, {@code forceload}, …) or answered
 *       by our own bounded code ({@code locate}).</li>
 * </ul>
 *
 * <p><b>What keeps a command off another child, by path</b> — the rows here assume these:
 * <pre>
 *   path                                  @a @p @r                 @e that can be a player   a named other child
 *   run_command (live agent)              narrowed to the wisher   refused                   refused unless a visit
 *   a button / template / rule replayed   narrowed to the presser  refused                   refused unless a visit
 *   a compiled datapack                   see below (step 2) see below                 refused (DatapackReach)
 *   (and in every path: a DENY row is refused — in a datapack by DatapackCommands, step 1)
 * </pre>
 * A datapack runs later, for whoever sets it off, so its selectors are never narrowed: a sound for
 * everyone and a dragon's breath on whoever stands near ({@code @a[distance=..4]}) are the wish. What
 * it may not do (step 2, {@code DatapackCommands.reachesPlayers}, measured to refuse 1 of 178
 * wished packs): aim a {@link #PLAYER_STATE} command at EVERY player — {@code @a}/{@code @r}/a
 * player-capable {@code @e} with no distance, box, name, tag or nearest-one bound, including
 * {@code execute as @a run give @s} — or {@code kill} anything that could be a child (only {@code @s},
 * a mob type, or what a wish tagged).
 * <pre>
 * </pre>
 * The narrowing is {@code agent.PersonSelectors.narrow} (both of the first two paths call it; the
 * replay half is, {@code ReplayNarrowGameTest}); the name rule is {@code agent.PlayerReach}
 *. A positive {@code tag=} counts as "something a wish tagged", never a child — the same
 * reasoning as {@link Limit#TAG_SCOPED}. A row that is safe ONLY because of this table (clear,
 * spreadplayers, advancement, spectate, team join) must not be opened to a path whose column says
 * NOT.
 *
 * <p><b>Per world.</b> A world's rules file ({@code worlds/rules/<id>.rules.json}) denies
 * {@code world.WorldEffect}s, and {@code world.CommandCoords} + {@code world.BaseReach} enforce them
 * on the agent's commands: a command an effect lists ({@code WorldEffect.commands()}) is refused
 * where that effect is denied, and every coordinate must be on the child's own base where the base
 * rule is on. So a row here is the most a wish may EVER run; a world only takes away. On a world with
 * bases (an islands world module) that means: time, weather, game rules — and {@code advancement} and {@code recipe}
 * ("the goal list is his to earn") — are refused; {@code tp}, {@code spreadplayers},
 * {@code fillbiome} stay on his island; {@code locate} answers without searching. A REPLAYED
 * button, template or rule is checked too, measured from where it runs ({@code BaseReach.replayRefusal}
 * from {@code WishExecutor.runOne}).
 *
 * <p><b>Never a command block, structure block or jigsaw</b> ({@link ForbiddenBlocks}): whatever the row,
 * a command that names one (or writes an existing command block's {@code Command}) is refused on every world and
 * in every path above, without reading {@code enable-command-block} — that property is the only thing between a
 * command block and a command run at the server's own permission.
 *
 * <p><b>Changing a row:</b> decide it with the rubric above, write the answers as a comment beside
 * the row, keep {@code why} to the one line the agent is told when refused, and note it.
 * Opening a command also means: its text through the sweep if he reads it ({@code text(…)}), the
 * world effect it changes if it changes everybody's world ({@code WorldEffect}), and a test where it
 * RUNS, not only here.
 */
public final class CommandGuard {

    /** How much of a command a wish gets. */
    public enum Tier {
        /** Runs as written (other children are still guarded by PlayerReach / PersonSelectors). */
        ALLOW,
        /** Runs only in the shape its {@link Limit} accepts. */
        LIMITED,
        /** Never — server or account control, the whole server's clock, or nothing a wish needs. */
        DENY
    }

    /** The named rule a {@link Tier#LIMITED} row is checked by. */
    public enum Limit {
        NONE,
        /** {@code execute}: whatever follows {@code run} is checked as a command of its own. */
        RUN_TAIL,
        /** {@code wishspot}: like {@link #RUN_TAIL}, but the {@code run} is required. */
        RUN_TAIL_REQUIRED,
        /** Only against a selector with a positive {@code tag=} — something a wish tagged. */
        TAG_SCOPED,
        /** Only the {@link #GAMERULES} rules, and only to true or false. */
        ONLY_THESE_GAMERULES,
        /** Only the sub-commands the row names. */
        SUBCOMMANDS,
        /** {@code grant} anything; {@code revoke} only {@code only <not minecraft:>} — our own triggers. */
        REVOKE_OWN_ONLY,
        /** A spread no wider than {@link #MAX_SPREAD} blocks. */
        SPREAD_RANGE,
        /**
         * Never dispatched: {@code run_command} ANSWERS it itself ({@code agent/tools/WishLocate}) with
         * the mod's bounded search, because vanilla's runs on the server thread and once took 59 s.
         */
        ANSWERED_NOT_RUN
    }

    /**
     * One command's decision.
     *
     * @param showsText its arguments are words a child reads on screen, so {@code SafetyTextSweep}
     *                  sweeps them
     */
    public record Rule(String root, Tier tier, Limit limit, Set<String> subs, boolean showsText, String why) {
    }

    /**
     * The two game rules "make it daytime forever" and its weather twin need: their 1.21.11 registry ids, bare or
     * namespaced, and the camelCase names the agent learned. The guard sees the command before {@link CommandDialect}
     * runs, and GameRuleIds rewrites doDaylightCycle / doWeatherCycle to advance_time / advance_weather before it
     * executes; every other rule stays refused under either spelling.
     */
    private static final Set<String> GAMERULES = Set.of("dodaylightcycle", "doweathercycle",
            "advance_time", "advance_weather", "minecraft:advance_time", "minecraft:advance_weather");

    /**
     * <b>Commands that change a PLAYER's lasting state</b> — what he holds, where he is, his health,
     * his effects, his progress — as opposed to what he sees or hears ({@code tellraw},
     * {@code playsound}, {@code particle}, {@code title}, {@code bossbar}). Read by a compiled
     * datapack's guard (step 2, {@code datapack.DatapackCommands}): there no selector is narrowed
     * to the presser, so one of these aimed at EVERY player ({@code @a} with no distance, name or tag)
     * would reach every child, which "a wish touches only the child who made it"
     * forbids — while {@code @a[distance=..4]} (a dragon's breath on whoever stands near) is the wish's
     * own point and stays. Measured over the 178 wished packs: one pack ({@code give @a}) breaks.
     * {@code kill} is stricter, below: never at anything that could be a player.
     */
    static final Set<String> PLAYER_STATE = Set.of("give", "clear", "effect", "damage", "kill", "tp", "teleport",
            "spreadplayers", "advancement", "recipe", "experience", "xp", "attribute", "enchant", "spawnpoint",
            "item", "ride", "spectate", "loot");

    /** Does this command change a player's lasting state when it is aimed at one? */
    public static boolean changesAPlayer(String root) {
        return root != null && PLAYER_STATE.contains(root.toLowerCase(Locale.ROOT));
    }

    /** The widest {@code spreadplayers} a wish may ask for, in blocks. */
    static final int MAX_SPREAD = 10_000;

    /** Bounded recursion through nested {@code execute ... run ...} chains. */
    private static final int MAX_DEPTH = 8;

    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    static {
        // ── things a wish does to the world and to him ─────────────────────────────────────────
        allow("summon", "makes a mob or thing; how many is SpawnBudget's job (#1265)");
        allow("give", "hands him an item (a WISHED item is make_item's — the tool says so)");
        allow("effect", "a potion effect on him or a mob");
        allow("particle", "sparkles");
        allow("playsound", "a sound");
        allow("stopsound", "stops a sound");
        allow("weather", "the weather");
        allow("time", "the time of day");
        allow("setblock", "one block");
        allow("fill", "a box of blocks; vanilla caps it at 32,768");
        allow("clone", "copies blocks; vanilla caps it at 32,768");
        allow("place", "a feature, structure or template");
        // fillbiome — valid: "make it snow around my house", "turn my island into a cherry grove".
        // Abuse: a huge area's biome changed — L; vanilla caps the volume at 32,768. ALLOW.
        allow("fillbiome", "a biome over an area (\"make it snow around my house\"); vanilla caps it at 32,768 (#1602)");
        allow("tp", "moves him or a mob; another child only as a visit (PlayerReach, #1262)");
        allow("teleport", "moves him or a mob; another child only as a visit (PlayerReach, #1262)");
        // spreadplayers — valid: "drop me somewhere random far away", "scatter my sheep across the
        // field" (M). Abuse: throw every child to random spots (L — the selector rules narrow it to
        // him), or a 30-million-block range loading chunks at the world's edge (L, by accident).
        // Rule: a range cap. LIMITED. On a world with bases CommandCoords reads its centre and range.
        allow("spreadplayers", "\"drop me somewhere random\"; limited in range only (#1602)", Limit.SPREAD_RANGE);
        // rotate — "make the statue face me", "turn my horse around". No abuse. ALLOW.
        allow("rotate", "turns a mob or him (#1602)");
        allow("ride", "puts him or a mob on another");
        allow("attribute", "a mob's or his size, speed, health");
        allow("enchant", "enchants what he holds");
        allow("experience", "XP");
        allow("xp", "XP");
        // clear — valid: "clear my inventory", "take away all the dirt" (M). Abuse: wipe a sibling's
        // inventory (L) — never reaches him: @a/@p narrowed, a named child refused. The watch comes
        // back by itself (WatchPinGameTest). ALLOW — and only on paths whose selector column says so.
        allow("clear", "\"clear my inventory\" — players are narrowed to the wisher, and another child is refused (#1602)");
        // recipe — "unlock all the recipes" (L). No abuse on an open world; on a world with bases it skips what
        // he earns, so it is a gamerules_difficulty effect there. ALLOW.
        allow("recipe", "unlocks recipes; on a world with bases that is his to earn (WorldEffect.GAMERULES_DIFFICULTY) (#1602)");
        // advancement — valid: "give me all the achievements" (L); our machine templates reset their
        // own trigger with `revoke … only <ns>:…`. Abuse: `revoke @s everything` wipes his real
        // progress and cannot be undone (L). Rule: grant anything; revoke only a non-minecraft id.
        // LIMITED. On a world with bases a gamerules_difficulty effect: the goal list is his to earn.
        allow("advancement", "\"give me all the achievements\"; revoking his real progress is not undoable, and on"
                + " a world with bases the goal list is his to earn (WorldEffect.GAMERULES_DIFFICULTY) (#1602)",
                Limit.REVOKE_OWN_ONLY);
        allow("damage", "hurts a mob");
        allow("item", "changes an item in a slot");
        allow("loot", "drops a loot table");
        allow("spawnpoint", "his respawn point");
        // spectate — "let me see through my dragon's eyes" (L). Abuse: spy on another child (L) —
        // a child in either slot is a PlayerReach refusal. ALLOW.
        allow("spectate", "\"see through my dragon's eyes\"; another child is a PlayerReach refusal (#1602)");
        allow("playanimation", "plays an animation");
        allow("kill", "only what a wish tagged: an untagged kill took the child's animals and another wish's"
                + " creatures (#174)", Limit.TAG_SCOPED);
        allow("gamerule", "only the sun and the rain stopping (advance_time, advance_weather: #770); every other rule changes how the whole"
                + " world plays for everyone", Limit.ONLY_THESE_GAMERULES);

        // ── words on screen (swept by SafetyTextSweep) ─────────────────────────────────────────
        text("say", "a line in chat");
        text("tellraw", "a line in chat");
        text("title", "big words across his screen");
        // bossbar — "a health bar for my dragon boss", "a countdown bar for the race" (M). Abuse: bad
        // words on a bar everyone sees (L) — swept as text. ALLOW. (Live: none yet.)
        text("bossbar", "\"a health bar for my dragon\" (#1602)");
        // team — "red team vs blue team" (live, 2026-10-13, refused), "make my wolves
        // glow blue" (the glow colour IS the team colour), "my pets don't hurt each other" (M).
        // Abuse: a bad team name on everyone's tab list (L) — swept as text; joining another child
        // to a team — the selector rules. ALLOW.
        // On a world with base_reach a player in a team is refused — the tab list is every child's
        // (CommandCoords.sharedScreen); teams for his own creatures are kept.
        text("team", "\"red team vs blue team\", a glow colour, friendly fire; names show on the tab list (#1602);"
                + " on a world with bases only his creatures join a team (#2466)");

        // ── the plumbing of datapacks and machines ─────────────────────────────────────────────
        allow("execute", "conditions and scoping; whatever follows `run` is checked as a command", Limit.RUN_TAIL);
        allow("function", "runs a function");
        // schedule — "in five minutes start the meteor shower", "every minute rain diamonds" (M).
        // Abuse: none of its own — what it schedules is a function, checked where it is compiled
        //. World-wide on a world with bases (WorldEffect). ALLOW.
        allow("schedule", "\"in five minutes, start the meteor shower\"; the function is checked where it is made (#1602)");
        // return, trigger — control flow inside functions and datapacks; nothing to abuse. ALLOW.
        allow("return", "a function's control flow (#1602)");
        allow("trigger", "a scoreboard trigger (#1602)");
        allow("data", "reads or changes block and entity data");
        allow("tag", "tags a mob, which is how a wish names its own");
        // On a world with bases a display slot (sidebar, list, below_name) is refused: it is on every child's screen.
        allow("scoreboard", "scores and objectives; on a world with bases never a display slot, which every player sees (#2466)");
        // random — "roll a dice", "pick a random number for my game" (L). `random roll` is announced
        // to everyone, which is the point of a dice. ALLOW.
        allow("random", "\"roll a dice\" (#1602)");
        // list, help — reads. The agent checked who was online twice in live play and was refused.
        allow("list", "who is online — the agent checking (#1602)");
        allow("help", "harmless (#1602)");
        // version — new in 1.21.6+: reads the server's version. DENY: no wish
        // needs it, and the agent's own prompt already says which Minecraft it builds for.
        deny("version", "no wish needs it; the agent already knows the version (#1786)");
        // fetchprofile — new in 1.21.9: looks a player's profile up by name or id on Mojang's servers (a web call
        // with words the agent chose) and offers a head or a mannequin with that skin. DENY, the verdict a root with
        // no row already had: no wish has needed it, and a make never calls the web.
        deny("fetchprofile", "looks a profile up on Mojang's servers by any name; no wish needs it yet");
        // stopwatch — new in 1.21.11: creates, queries, restarts and removes named server-wide stopwatches (world
        // state every player shares, kept in the save). DENY, the verdict a root with no row already had: no wish has
        // needed it, and a race timer a wish makes is a datapack's scoreboard, scoped to that wish.
        deny("stopwatch", "server-wide named stopwatches kept in the save; no wish needs it yet");

        // ── this mod's own commands: each runs something SAVED, never anything arbitrary ───────
        allow("wishbuild", "runs a saved, volume-capped build plan by id");
        limited("wishmob", Limit.SUBCOMMANDS, Set.of("spawn"),
                "re-summons a saved creature; its other branches reload, repaint and export models");
        allow("wishsummon", "a real mob with modify_mob's own spec applied (#572)");
        allow("wishdatapack", "replays a saved action datapack by id");
        allow("wishvisit", "takes him to something he made, never to coordinates (#1271)");
        allow("wishpaint", "hangs a saved picture (#677)");
        limited("wishspot", Limit.RUN_TAIL_REQUIRED, Set.of(),
                "one command on a saved building's named spot; the command is checked (#1355)");
        allow("wishput", "puts down a thing make_item already made (#1379)");
        limited("moddyevent", Limit.SUBCOMMANDS, Set.of("stage"),
                "stages a kept event design; defining, scheduling and undoing stay tools (#1047)");

        // ── answered by run_command, never run ─────────────────────────────────────────────────
        // locate — valid: "teleport me to a jungle", "take me to a village", "how far is the
        // nearest ocean" (H — three live wishes in three weeks, each refused and then guessing with
        // tp). Abuse: none of content; the harm is the SERVER — vanilla's search runs on the server
        // thread and froze it 59 s once (M, by accident). Rule: never dispatched; run_command answers
        // it with item.Locate's bounded search (structures 48 placement cells, biomes 3,200 blocks
        // off the server thread). poi is refused (no wish needs it). LIMITED.
        limited("locate", Limit.ANSWERED_NOT_RUN, Set.of("structure", "biome"),
                "\"take me to a jungle\": run_command answers it with the mod's bounded search and says where"
                        + " to stand; vanilla's runs on the server thread and once froze it 59 s (#1602)");

        // ── never ───────────────────────────────────────────────────────────────────────────────
        String server = "server control — no wish needs it";
        for (String c : new String[]{"stop", "save-all", "save-on", "save-off", "publish", "debug", "perf",
                "jfr", "setidletimeout"}) {
            deny(c, server);
        }
        String account = "who may play on the server — never a wish's";
        for (String c : new String[]{"op", "deop", "ban", "ban-ip", "banlist", "pardon", "pardon-ip", "kick",
                "whitelist"}) {
            deny(c, account);
        }
        // The server's machinery. Each has a real wish ("make the world tiny" → worldborder, "keep my
        // farm running while I'm away" → forceload), but each changes every child's world, costs
        // money on a metered server, or hitches the server, and no selector can scope that. DENY.
        deny("datapack", "switches datapacks on and off, ours included");
        // dialog, waypoint — new in 1.21.6+. A dialog puts the agent's words in front of a child
        // outside the chat text sweep, and no wish needs one (the wish box's own dialog, is sent by
        // the mod, not through a command). A waypoint drives the locator bar, which can show where OTHER
        // players are — a world with bases keeps each base private. DENY until a wish needs either.
        deny("dialog", "shows a server dialog — the agent's words outside the text sweep; no wish needs one yet (#1786)");
        deny("waypoint", "the locator bar can show where other players are; a world with bases keeps each base private (#1786)");
        deny("reload", "reloads every datapack: a multi-second hitch for every child");
        deny("forceload", "keeps chunks loaded forever on a metered server");
        deny("worldborder", "changes the world for everyone, and a border shrunk onto a child hurts him");
        deny("seed", "no wish needs it; it gives away the server's seed");
        // tick — "slow motion", "freeze time" (M). It is the whole server's clock: every child, the
        // camera, the agent's own timing (H by accident). A bounded `tick rate` would still change
        // everyone's game. DENY (D3).
        deny("tick", "freezes or speeds up the whole server — every child, the camera, the agent's own clock (#1602 D3)");
        deny("defaultgamemode", "changes every future join");
        // D2: allowed until then, and each changes the world for every child
        // ("make it peaceful" takes every child's monsters away) or the game a world is built on
        // (creative on a survival island). The wish's own way is a power, an item or a build. DENY.
        deny("difficulty", "the difficulty is every child's (\"make it peaceful\" takes everyone's monsters away)");
        deny("setworldspawn", "the world's spawn is every child's");
        deny("gamemode", "a game mode changes the game a world is built on (creative on a survival island); a power or an item is the wish's way");
        deny("transfer", "sends a child to another server");
        // msg/tell/w/me/teammsg/tm — "tell my brother hi" (L). A private, unmoderated message TO
        // another child (M) is that child as the subject, and say/tellraw/title already do
        // every valid version, swept. Nothing valid is lost. DENY.
        String message = "a private message to another child is that child as a subject (#1262);"
                + " say, tellraw and title do every wish's version";
        for (String c : new String[]{"msg", "tell", "w", "me", "teammsg", "tm"}) {
            deny(c, message);
        }
        // Operator commands the GameTest found registered (this mod's and NeoForge's).
        deny("moddyop", "the server's operator commands");
        deny("neoforge", "NeoForge's operator commands");
        deny("config", "NeoForge's config files");
        deny("paint", "a debugging command; a wish paints with paint_surface");
        deny("wish", "a child's own wish command — a wish never makes another wish");
        deny("moddy", "the child's own watch and chat links — not something a wish runs for him");
        // Vanilla 1.21.8, registered only in a dev build's dedicated-command block (beside
        // spawn_armor_trims and serverpack), so it appears in a GameTest run and not on a live server.
        deny("debugconfig", "vanilla's developer debug switch for a connection — server control, never a wish's");
        // Developer commands: vanilla registers these only in a development or GameTest server, and
        // `serverpack` is NeoForge's — CommandPolicyGameTest found them on its first run.
        for (String c : new String[]{"test", "debugpath", "debugmobspawning", "raid", "spawn_armor_trims",
                "warden_spawn_tracker", "serverpack"}) {
            deny(c, "a developer's debugging command");
        }
    }

    private static void allow(String root, String why) {
        put(new Rule(root, Tier.ALLOW, Limit.NONE, Set.of(), false, why));
    }

    private static void allow(String root, String why, Limit limit) {
        put(new Rule(root, Tier.LIMITED, limit, Set.of(), false, why));
    }

    private static void limited(String root, Limit limit, Set<String> subs, String why) {
        put(new Rule(root, Tier.LIMITED, limit, subs, false, why));
    }

    private static void text(String root, String why) {
        put(new Rule(root, Tier.ALLOW, Limit.NONE, Set.of(), true, why));
    }

    private static void deny(String root, String why) {
        put(new Rule(root, Tier.DENY, Limit.NONE, Set.of(), false, why));
    }

    private static void put(Rule r) {
        if (RULES.put(r.root(), r) != null) {
            throw new IllegalStateException("two rows for /" + r.root());
        }
    }

    private CommandGuard() {
    }

    // ── reading the table ───────────────────────────────────────────────────────────────────────

    /** Every row, in the order above. */
    public static Map<String, Rule> rules() {
        return java.util.Collections.unmodifiableMap(RULES);
    }

    /** The row for a command word (namespace and slash ignored), or null when it has none. */
    public static Rule rule(String rootWord) {
        if (rootWord == null) {
            return null;
        }
        String w = rootWord.trim().toLowerCase(Locale.ROOT);
        if (w.startsWith("/")) {
            w = w.substring(1);
        }
        int colon = w.indexOf(':');
        return RULES.get(colon >= 0 ? w.substring(colon + 1) : w);
    }

    /** The commands whose arguments are words a child reads — what {@code SafetyTextSweep} sweeps. */
    public static Set<String> textVerbs() {
        Set<String> out = new LinkedHashSet<>();
        for (Rule r : RULES.values()) {
            if (r.showsText()) {
                out.add(r.root());
            }
        }
        return out;
    }

    /** The commands a wish may run in some form (ALLOW and LIMITED). */
    public static Set<String> allowedRoots() {
        Set<String> out = new LinkedHashSet<>();
        for (Rule r : RULES.values()) {
            if (r.tier() != Tier.DENY) {
                out.add(r.root());
            }
        }
        return out;
    }

    /**
     * Is this a command {@code run_command} answers itself rather than runs ({@link Limit#ANSWERED_NOT_RUN})?
     * Only at the top level: {@code execute … run locate} would reach vanilla's search and is refused.
     */
    public static boolean answeredNotRun(String command) {
        if (command == null) {
            return false;
        }
        String c = command.trim();
        Rule r = rule(root(c));
        if (r == null || r.limit() != Limit.ANSWERED_NOT_RUN) {
            return false;
        }
        String[] parts = c.split("\\s+");
        return parts.length == 3 && r.subs().contains(parts[1].toLowerCase(Locale.ROOT));
    }

    /**
     * Why this command is refused, in one line for the agent, or null when it is allowed. Names the
     * row's reason, so a refusal is never "not on a list" with nothing to go on.
     */
    public static String whyRefused(String command) {
        if (isAllowed(command)) {
            return null;
        }
        String c = command == null ? "" : command.trim();
        String tail = c;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Rule r = rule(root(tail));
            if (r == null) {
                return "`" + root(tail) + "` is not a command a wish can run.";
            }
            if (r.tier() == Tier.DENY) {
                return "`" + r.root() + "` is never run for a wish: " + r.why() + ".";
            }
            String forbidden = ForbiddenBlocks.inCommand(tail);
            if (forbidden != null) {
                return ForbiddenBlocks.refusal(forbidden);
            }
            if ((r.limit() == Limit.RUN_TAIL || r.limit() == Limit.RUN_TAIL_REQUIRED) && indexOfRunKeyword(tail) >= 0
                    && !check(tail.substring(indexOfRunKeyword(tail) + " run ".length()), 0)) {
                tail = tail.substring(indexOfRunKeyword(tail) + " run ".length()).trim();
                continue;
            }
            if (r.limit() == Limit.ANSWERED_NOT_RUN) {
                return "`" + r.root() + "` works only on its own, as `" + r.root() + " "
                        + String.join("|", r.subs()) + " <id>`: " + r.why() + ".";
            }
            return "`" + r.root() + "` is allowed only in one shape: " + r.why() + ".";
        }
        return "that command is nested too deeply to check.";
    }

    // ── the check ───────────────────────────────────────────────────────────────────────────────

    /** True when the command is safe to run for a wish. */
    public static boolean isAllowed(String command) {
        return check(command, 0);
    }

    private static boolean check(String command, int depth) {
        if (command == null || depth > MAX_DEPTH) {
            return false;
        }
        String c = command.trim();
        if (c.isEmpty()) {
            return false;
        }
        // A leading slash is not part of the API we call, and newlines could smuggle a second
        // command past a single-command check.
        if (c.startsWith("/") || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0) {
            return false;
        }
        Rule r = RULES.get(root(c));
        if (r == null || r.tier() == Tier.DENY) {
            return false;
        }
        // A command block, structure block or jigsaw, on every world and whatever the server allows.
        if (ForbiddenBlocks.inCommand(c) != null) {
            return false;
        }
        String[] parts = c.split("\\s+");
        return switch (r.limit()) {
            case NONE -> true;
            case RUN_TAIL -> {
                // Only the tail AFTER `run` is another command; `execute` without `run` just tests/scopes.
                int idx = indexOfRunKeyword(c);
                yield idx < 0 || check(c.substring(idx + " run ".length()), depth + 1);
            }
            case RUN_TAIL_REQUIRED -> {
                int idx = indexOfRunKeyword(c);
                yield idx >= 0 && check(c.substring(idx + " run ".length()), depth + 1);
            }
            case TAG_SCOPED -> isTagScoped(c);
            case ONLY_THESE_GAMERULES -> parts.length == 3
                    && GAMERULES.contains(parts[1].toLowerCase(Locale.ROOT))
                    && (parts[2].equals("true") || parts[2].equals("false"));
            case SUBCOMMANDS -> parts.length >= 2 && r.subs().contains(parts[1].toLowerCase(Locale.ROOT));
            case REVOKE_OWN_ONLY -> revokesOnlyOwn(parts);
            case SPREAD_RANGE -> spreadWithinRange(parts);
            // Never dispatched — run_command answers it before anything runs (answeredNotRun).
            case ANSWERED_NOT_RUN -> false;
        };
    }

    /**
     * Is this command aimed at something a wish tagged, and nothing else?
     *
     * <p>Requires a positive {@code tag=} inside a selector's brackets. {@code tag=!x} is a
     * NEGATIVE filter — "everything except x" — and is the exact opposite of a scope, so it is
     * refused even though it contains the characters {@code tag=}. A bare {@code @e}, any player
     * selector, a player NAME, or a type-only filter are all refused — none of those can be shown to
     * be the agent's own work.
     */
    static boolean isTagScoped(String command) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@[aeprs]\\[([^\\]]*)\\]").matcher(command);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String t = part.trim();
                if (t.startsWith("tag=") && !t.startsWith("tag=!") && t.length() > "tag=".length()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code advancement grant …} anything; {@code advancement revoke <who> only <ns:id>} with ns ≠ minecraft. */
    private static boolean revokesOnlyOwn(String[] parts) {
        if (parts.length < 2) {
            return false;
        }
        String verb = parts[1].toLowerCase(Locale.ROOT);
        if (verb.equals("grant")) {
            return true;
        }
        if (!verb.equals("revoke") || parts.length < 5 || !parts[3].equalsIgnoreCase("only")) {
            return false;
        }
        String id = parts[4].toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon > 0 && !id.substring(0, colon).equals("minecraft");
    }

    /** {@code spreadplayers <x> <z> <spreadDistance> <maxRange> …} with maxRange ≤ {@link #MAX_SPREAD}. */
    private static boolean spreadWithinRange(String[] parts) {
        if (parts.length < 5) {
            return false;
        }
        try {
            double range = Double.parseDouble(parts[4]);
            return range > 0 && range <= MAX_SPREAD;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The command word, lower-cased and stripped of any namespace. */
    private static String root(String command) {
        String c = command.trim();
        int sp = c.indexOf(' ');
        String first = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }

    /** Index of the first standalone {@code run} keyword (what follows it is another command), or -1. */
    private static int indexOfRunKeyword(String command) {
        return command.toLowerCase(Locale.ROOT).indexOf(" run ");
    }
}
