package com.moddingfromamod.datapack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.moddingfromamod.wish.CommandGuard;

/**
 * <b>A datapack never runs a command the table DENIES</b> (step 1) <b>— nor changes every
 * player at once, nor kills what could be a child</b> (step 2, {@link #reachesPlayers}).
 *
 * <p>{@code CommandGuard}'s table governs every command a wish runs by hand; a compiled CBScript
 * datapack was the one path it did not reach, and functions run at permission level 2 — so a
 * datapack could {@code worldborder}, {@code forceload}, {@code reload}, {@code tick} or set the
 * difficulty while {@code run_command} refused all of them (live, 2026-09-24: a refused
 * {@code gamerule} was re-done as a datapack). This reads the COMPILED functions — what will really
 * run, after CBScript expanded its own statements — and refuses the compile if any line's command
 * (followed through {@code execute … run}) is a DENY row.
 *
 * <p><b>Only the DENY rows, on purpose.</b> Measured over every datapack children have wished (178,
 *): the DENY rows hit 0 of them, while {@code run_command}'s LIMITED rows would refuse 33
 * ({@code kill @s}: a fireball removing itself) and its player narrowing 160 (a dragon's breath
 * hurting whoever stands near). Those need datapack-shaped rules — an earlier issue's later steps. The
 * compiler's own boilerplate ({@code gamerule maxCommandChainLength}) is a LIMITED row and so is
 * never seen here. {@code CbScriptCorpusTest} replays the whole corpus through
 * {@code CbScriptCompiler.compile}, so a corpus pack this starts refusing fails Gate A by name.
 */
public final class DatapackCommands {

    private DatapackCommands() {
    }

    private static final int MAX_DEPTH = 8;

    /** The refusal for the first DENY-row command in this compiled pack, or null when there is none. */
    public static String refusal(Path zip) {
        if (zip == null || !Files.isRegularFile(zip)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().endsWith(".mcfunction")) {
                    continue;
                }
                String text = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    String why = refusalForLine(line);
                    if (why != null) {
                        return why;
                    }
                }
            }
        } catch (IOException ex) {
            return null;   // an unreadable pack is the compiler's failure to report, not this rule's
        }
        return null;
    }

    /** One function line: the refusal if its command is a DENY row, else null. Pure. */
    static String refusalForLine(String raw) {
        String c = raw == null ? "" : raw.strip();
        if (c.isEmpty() || c.startsWith("#")) {
            return null;
        }
        if (c.startsWith("$")) {
            c = c.substring(1).strip();       // a macro line: the command after the marker
        }
        // `execute as <sel>` makes @s whatever <sel> picks: as @a, `give @s …` gives everyone.
        String executor = null;
        for (int depth = 0; depth < MAX_DEPTH && !c.isEmpty(); depth++) {
            if (c.startsWith("/")) {
                c = c.substring(1);
            }
            String root = root(c);
            // never a command block, structure block or jigsaw, whatever the server allows.
            String forbidden = com.moddingfromamod.wish.ForbiddenBlocks.inCommand(c);
            if (forbidden != null) {
                return "This datapack makes `" + forbidden + "`. " + com.moddingfromamod.wish.ForbiddenBlocks
                        .refusal(forbidden);
            }
            if (!root.equals("execute")) {
                CommandGuard.Rule r = CommandGuard.rule(root);
                if (r != null && r.tier() == CommandGuard.Tier.DENY) {
                    return "This datapack runs `" + root + "`, which a wish never runs: " + r.why()
                            + ". Nothing was saved. Grant the wish without it.";
                }
                return CommandGuard.changesAPlayer(root) ? reachesPlayers(root, c, executor) : null;
            }
            int run = c.toLowerCase(Locale.ROOT).indexOf(" run ");
            String head = run < 0 ? c : c.substring(0, run);
            Matcher as = AS.matcher(head);
            while (as.find()) {
                if (!as.group(1).startsWith("@s")) {
                    executor = as.group(1);         // `as @s` keeps whoever @s already was
                }
            }
            if (run < 0) {
                return null;
            }
            c = c.substring(run + " run ".length()).strip();
        }
        return null;
    }

    // ── step 2: other players ─────────────────────────────────────────────────────────────

    private static final Pattern SELECTOR = Pattern.compile("@([aeprsn])(?![A-Za-z0-9_])(\\[[^\\]]*\\])?");
    private static final Pattern AS = Pattern.compile("(?:^|\\s)as\\s+(@[aeprsn](?![A-Za-z0-9_])(?:\\[[^\\]]*\\])?)");

    /**
     * {@code root} changes a player's state ({@code CommandGuard.changesAPlayer}); is its target
     * every player? Its target is the command's first selector, or the {@code execute as} selector
     * when that target is {@code @s}. Measured over the 178 wished packs: one pack refused.
     */
    static String reachesPlayers(String root, String command, String executor) {
        Matcher m = SELECTOR.matcher(command);
        if (!m.find()) {
            return null;                        // a name, or no target: PlayerReach's question
        }
        String target = m.group();
        if (m.group(1).equals("s") && executor != null) {
            target = executor;
        }
        Matcher t = SELECTOR.matcher(target);
        if (!t.find()) {
            return null;
        }
        char kind = t.group(1).charAt(0);
        String args = t.group(2) == null ? "" : t.group(2);
        if (root.equals("kill")) {
            // A fireball removes itself (@s, 55 lines) or what a wish tagged (17): never a player.
            if (kind == 's' || isMob(kind, args) || tagged(args)) {
                return null;
            }
            return "This datapack would `kill " + target + "`, which can be a child. A datapack only ever kills "
                    + "itself (@s) or what the wish tagged (@e[tag=…]). Nothing was saved.";
        }
        boolean everyone = (kind == 'a' || kind == 'r' || ((kind == 'e' || kind == 'n') && !isMob(kind, args)))
                && !bounded(args);
        if (!everyone) {
            return null;
        }
        return "This datapack would `" + root + "` " + target + " — every player at once, every child in the "
                + "world. A wish touches only the child who made it: aim it at @s (the one who presses it), or "
                + "at the players near it with distance=..N. Nothing was saved.";
    }

    private static boolean isMob(char kind, String args) {
        if (kind != 'e' && kind != 'n') {
            return false;
        }
        Matcher m = Pattern.compile("type=([^,\\]]+)").matcher(args);
        while (m.find()) {
            String v = m.group(1).strip();
            if (!v.startsWith("!") && !v.equals("player") && !v.equals("minecraft:player")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marked by the pack itself: a positive {@code tag=}, or a {@code scores=} filter — our own effect
     * templates mark what they picked with a score and then aim at {@code @e[scores={mfam_imp=1..}]}
     * (impulse, grow). Gate B caught step 2's first cut refusing them, which the corpus of wished packs
     * could not show: the templates are not in it.
     */
    private static boolean tagged(String args) {
        return Pattern.compile("(?:\\[|,)\\s*(?:tag=(?!!)[^,\\]]+|scores=\\{)").matcher(args).find();
    }

    /** Bounded to SOME players: a distance, a box, a name, a tag, or the one nearest. */
    private static boolean bounded(String args) {
        String a = args.toLowerCase(Locale.ROOT);
        return a.contains("distance=") || a.contains("dx=") || a.contains("dz=") || a.matches(".*name=(?!!).*")
                || tagged(args) || (a.contains("limit=1") && a.contains("sort=nearest"));
    }

    private static String root(String command) {
        int sp = command.indexOf(' ');
        String first = (sp < 0 ? command : command.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }
}
