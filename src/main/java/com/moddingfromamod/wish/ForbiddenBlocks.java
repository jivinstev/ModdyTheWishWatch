package com.moddingfromamod.wish;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The blocks and the entity a wish never places or hands over</b>: command blocks (all three),
 * the command-block minecart, structure blocks, jigsaws, and the test blocks. Pure, so every path that can
 * produce one asks the same question.
 *
 * <p><b>Why, when a server can run with {@code enable-command-block=false}.</b> That property is the only thing
 * standing between {@code setblock ~ ~1 ~ command_block{Command:"op @a",auto:1b}} and a command run at the
 * server's own permission, past every guard this mod has ({@code CommandGuard}, {@code BaseReach},
 * {@code PlayerReach}, the safety sweep). A property in a template file is one edit from flipping, so this
 * refusal does NOT read it: a wish never makes one of these, on any world, whatever the server allows.
 * A structure block or a jigsaw loads saved structures at its own say-so, which is the same kind of reach.
 *
 * <p><b>Where it is asked</b> — every path a wish can make one in:
 * <ul>
 *   <li>a command line: {@code CommandGuard.isAllowed}/{@code whyRefused}, so {@code run_command},
 *       {@code add_template}, {@code set_rule}, a button or template replay ({@code WishExecutor}) and
 *       {@code wishspot} all refuse it ({@link #inCommand});</li>
 *   <li>a compiled datapack line ({@code datapack.DatapackCommands}), which is every {@code author_datapack}
 *       and every {@code make_item}/{@code DatapackForge} pack;</li>
 *   <li>a build plan's palette ({@code builder.Builder.submit}), so {@code build_structure} and a replayed
 *       {@code wishbuild} ({@link #isForbiddenId});</li>
 *   <li>an item handed over by id ({@code give_items}, {@code make_item}'s {@code base_item}).</li>
 * </ul>
 *
 * <p><b>What it reads.</b> A command line is scanned for the ids as words, vanilla namespace or none, in any
 * shape the grammar takes them — {@code setblock}, {@code fill}, {@code clone … filtered}, {@code give},
 * {@code item replace … with}, {@code summon}, a block-state inside NBT — plus {@code data merge|modify block}
 * writing the {@code Command}/{@code auto} keys of a block that is already one. A text command's words
 * ({@code say}, {@code tellraw}, {@code title}, {@code bossbar}, {@code team}) are not ids and are not read,
 * and {@code place jigsaw <pool>} is the jigsaw STRUCTURE generator, not the block, so its sub-command word is
 * skipped. Not read, said out loud: a {@code clone} or {@code place template} that copies a command block
 * already in the world without naming it (only an operator can have put one there), and an id built from a
 * datapack macro's variables.
 */
public final class ForbiddenBlocks {

    private ForbiddenBlocks() {
    }

    /** The ids, without their namespace. */
    public static final Set<String> IDS = Set.of("command_block", "chain_command_block",
            "repeating_command_block", "command_block_minecart", "structure_block", "jigsaw", "test_block",
            "test_instance_block");

    /** An id as a word: not inside a longer id, and in the vanilla namespace or none. */
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9_.:/-])(?:minecraft:)?"
            + "(chain_command_block|repeating_command_block|command_block_minecart|command_block|structure_block"
            + "|jigsaw|test_instance_block|test_block)(?![A-Za-z0-9_:])", Pattern.CASE_INSENSITIVE);

    /** {@code data merge|modify block …}: the NBT keys that make an existing command block run something. */
    private static final Pattern DATA_BLOCK = Pattern.compile("^data\\s+(?:merge|modify)\\s+block\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND_KEYS = Pattern.compile("(?<![A-Za-z0-9_])(Command|auto|LastOutput)"
            + "(?![A-Za-z0-9_])");

    /** Commands whose arguments are words a child reads, never block ids. */
    private static final Set<String> TEXT = Set.of("say", "tellraw", "title", "bossbar", "team");

    /** Is this block or item id (with or without {@code minecraft:}, with or without a block state) one? */
    public static boolean isForbiddenId(String id) {
        if (id == null) {
            return false;
        }
        String s = id.strip().toLowerCase(Locale.ROOT);
        int cut = indexOfAny(s, "[{");
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        } else if (s.contains(":")) {
            return false;
        }
        return IDS.contains(s);
    }

    /**
     * The forbidden thing ONE command segment names — the part before any {@code execute … run}, whose tail
     * the caller checks as its own command — or null. {@code data merge block … {Command:…}} answers
     * {@code "command_block"}: it is what that command makes run.
     */
    public static String inCommand(String segment) {
        if (segment == null) {
            return null;
        }
        String c = segment.strip();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        if (c.startsWith("$")) {
            c = c.substring(1).strip();
        }
        String root = root(c);
        if (TEXT.contains(root)) {
            return null;
        }
        if (root.equals("execute")) {
            int run = c.toLowerCase(Locale.ROOT).indexOf(" run ");
            if (run >= 0) {
                c = c.substring(0, run);
            }
        }
        if (root.equals("place")) {
            // `place jigsaw <pool> <target> <depth>` generates a structure; the word is a sub-command.
            c = c.replaceFirst("(?i)^(\\S+\\s+)jigsaw(?=\\s|$)", "$1");
        }
        Matcher m = ID.matcher(c);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        if (DATA_BLOCK.matcher(c).find() && COMMAND_KEYS.matcher(c).find()) {
            return "command_block";
        }
        return null;
    }

    /** The line the agent is told: what did not happen, why, and what to do instead. */
    public static String refusal(String what) {
        return "`" + what + "` is never made for a wish, on any world: a command block runs commands at the "
                + "server's own permission, past every rule a wish follows, and a structure or jigsaw block loads "
                + "saved structures by itself (#2466). Nothing was placed. Grant the wish with ordinary blocks, "
                + "a datapack function, or a button the child presses.";
    }

    private static String root(String c) {
        int sp = c.indexOf(' ');
        String first = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (char ch : chars.toCharArray()) {
            int i = s.indexOf(ch);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }
}
