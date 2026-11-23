package com.moddingfromamod.agent.tools;

import java.util.Locale;

/**
 * <b>"It was already like that" is not a failure.</b>
 *
 * <p>{@code run_command} takes a batch and <b>stops at the first failure</b>, for a good reason
 * written down where it happens: continuing past a failure builds on a world state the agent
 * believes exists and does not, and the report then blames the wrong command.
 *
 * <p>That rule is right. <b>The classification feeding it was wrong.</b> Vanilla's {@code setblock}
 * and {@code fill} report a hard failure when they changed <i>nothing</i> — and the commonest reason
 * to change nothing is that the world <b>already is</b> what the command asked for. Clearing a
 * region to air that is already air "fails". So does re-placing a block that is already there.
 *
 * <p>For those the premise of the stop rule is not merely absent, it is <b>inverted</b>: the
 * postcondition the agent wanted holds, so every later command in the batch is standing on exactly
 * the ground it expected.
 *
 * <h2>What this cost, measured</h2>
 *
 * <p>World {@code daddus moddy 4}, 2026-08-14, the metal-grinder wish:
 *
 * <pre>
 * FAILED: fill -1773 1 997 -1768 1 997 minecraft:air
 *   No blocks were filled
 * Stopped here — the remaining 4 commands were NOT run.
 * </pre>
 *
 * <p>That region was already air. The four discarded commands included the
 * {@code repeating_command_block} that <b>was the grinder's engine</b> — so the machine the child
 * asked for was thrown away by a command that had nothing to do, and the agent was told its own
 * world-clearing step had failed. The same thing happened three minutes earlier with
 * {@code setblock … minecraft:air} → <i>"Could not set the block"</i>.
 *
 * <h2>Why match on the message, and why that is narrower than it looks</h2>
 *
 * <p>Vanilla signals this through the command's failure channel, so there is no boolean to read: the
 * only distinguishing evidence is which error it raised. Matching is therefore text-based, but it is
 * pinned to <b>one specific vanilla string per command</b> ({@code commands.fill.failed},
 * {@code commands.setblock.failed}, {@code commands.clone.failed}), and it is required to be
 * <b>that command</b> as well as that message. A malformed block id, an unloaded or out-of-world
 * position, and a bad selector all raise different errors and still stop the batch.
 *
 * <p>Kept Minecraft-free and pure so the distinction can be tested exhaustively without a world —
 * including the cases that must NOT be forgiven.
 */
public final class CommandOutcome {

    private CommandOutcome() {
    }

    /**
     * <b>Did this command fail only because the world was already the way it asked for?</b>
     *
     * @param command the command as the agent sent it
     * @param output  what the server said back
     * @return true when the failure is a no-op and the batch should carry on
     */
    public static boolean isAlreadyDone(String command, String output) {
        if (command == null || output == null) {
            return false;
        }
        String verb = verbOf(command);
        String said = output.toLowerCase(Locale.ROOT);
        return switch (verb) {
            // "No blocks were filled" — every block in the region already matched.
            case "fill" -> said.contains("no blocks were filled");
            // "Could not set the block" — the block there is already exactly this.
            case "setblock" -> said.contains("could not set the block");
            // "No blocks were cloned" — the destination already matches the source.
            case "clone" -> said.contains("no blocks were cloned");
            default -> false;
        };
    }

    /**
     * The command word, with any {@code /} and any {@code execute … run} prefix peeled off.
     *
     * <p>The prefix matters: the agent writes {@code execute positioned … run fill …} constantly,
     * and a naive first-word read would classify all of those as {@code execute} and forgive
     * nothing. Only the LAST {@code run} is peeled, which is what nesting means.
     */
    static String verbOf(String command) {
        String s = command.trim();
        if (s.startsWith("/")) {
            s = s.substring(1).trim();
        }
        int run = s.toLowerCase(Locale.ROOT).lastIndexOf(" run ");
        if (s.toLowerCase(Locale.ROOT).startsWith("execute ") && run >= 0) {
            s = s.substring(run + 5).trim();
        }
        int sp = s.indexOf(' ');
        return (sp < 0 ? s : s.substring(0, sp)).toLowerCase(Locale.ROOT);
    }

    /**
     * How to describe it to the agent.
     *
     * <p>Deliberately NOT reported as a plain success. The agent asked for a change and got none,
     * and it should be able to tell the difference — a wish that keeps "already done"-ing its own
     * setup is a wish building somewhere it did not mean to.
     */
    public static String alreadyDoneLine(String command) {
        return "Already like that: " + command
                + "\n(nothing to change — carrying on with the rest of the batch)";
    }

    /**
     * <b>Did this command fail because it called one of OUR datapack functions directly?</b>
     *
     * <p>A raw {@code /function <id>:<entry>} into a saved CBScript pack can throw
     * {@code java.lang.UnsupportedOperationException: This function should not run}, and retrying
     * does not help. {@code AddTemplateTool.rewriteDatapackFunction} already rewrites the BARE form
     * to {@code wishdatapack}, which loads the pack first and runs the entry elevated.
     *
     * <p><b>It only matches a command that STARTS with {@code function }</b>, so the form the agent
     * actually types when it tests its own work slips straight through:
     *
     * <pre>
     *   execute as @e[tag=fbtest] at @s run function fireball_sword:shoot
     *       java.lang.UnsupportedOperationException: This function should not run
     * </pre>
     *
     * <p>Measured in {@code corpus/real-tool-calls.jsonl}: 6 of the agent's 12 real
     * {@code run_command} failures are this, across two wishes. It is a guard tested where it is
     * DEFINED (a bare command) and not where it RUNS (inside an {@code execute}).
     *
     * <p><b>This is, which is closed as completed</b> — and it is: the bare form really was
     * fixed, and stayed fixed. What recurred is the same defect in the other syntax, which is the
     * "a defect found is a defect class found" rule in CLAUDE.md arriving in the post. The agent
     * writes the nested form because it is <i>testing its own work on a tagged entity</i>, which is
     * exactly the loop the fix was meant to protect.
     *
     * <h2>⚠ Why this ADVISES instead of rewriting, which is what the bare form does</h2>
     *
     * <p>Extending the rewrite to the nested case looks obvious and is wrong.
     * {@code WishDatapackCommand} calls {@code getPlayerOrException()}, so rewriting
     * {@code execute as @e[tag=x] at @s run function …} into {@code … run wishdatapack …} would
     * change the command's subject from the tagged entity to a player — destroying the entity
     * context the {@code execute} exists to establish, and failing outright when the source is a
     * mob. The two forms are not interchangeable; only the bare one can be swapped safely.
     *
     * <p>So the agent is told, at the moment it is refused. That matters more than the tidiness of
     * a rewrite: the note on {@code RunCommandTool}'s own rewrite records what happened last time
     * this failed unexplained — the agent <i>"gave up on the datapack and hand-placed a repeating
     * command block instead — the one that then spammed the child's screen "</i>. A bare Java
     * exception is not a reason anybody can act on (docs/WISH_LINTERS.md P2).
     *
     * @return the advice to append, or empty when this is not that failure
     */
    public static String datapackFunctionAdvice(String command, String output) {
        if (command == null || output == null
                || !output.contains("This function should not run")) {
            return "";
        }
        // ⚠ THE MESSAGE ALONE IS NOT ENOUGH, and a test written from the principle rather than from
        // this code is what caught it: gating only on the output gave `summon minecraft:pig` the
        // full datapack lecture whenever that string appeared anywhere in the server's reply. A
        // message on the wrong failure sends the agent to rewrite a command that was never the
        // problem -- docs/WISH_LINTERS.md P6, do not cry wolf. So the command must actually contain
        // a resolvable function reference, and the advice always names THAT one.
        String ref = functionRefIn(command);
        if (ref.isEmpty()) {
            return "";
        }
        String how = "wishdatapack " + ref.substring(0, ref.indexOf(':'))
                + " " + ref.substring(ref.indexOf(':') + 1);
        return "\n  ↳ That is one of your OWN datapack functions, and calling it with /function can "
                + "throw this whenever the pack is mid-reload. Run `" + how + "` instead — it loads "
                + "the pack first, then runs the entry.\n"
                + "  ↳ It runs AS THE CHILD, not as the entity your `execute` selected, so if you "
                + "were testing the function on a tagged mob you cannot keep that context: test it "
                + "on yourself, or give the pack a trigger and set that off instead. Do NOT fall "
                + "back to a repeating command block — one of those spammed a child's screen (#142).";
    }

    /** The {@code <id>:<entry>} of a {@code function} call anywhere in the command, or empty. */
    static String functionRefIn(String command) {
        int at = command.indexOf("function ");
        if (at < 0) {
            return "";
        }
        String rest = command.substring(at + "function ".length()).strip();
        int end = rest.indexOf(' ');
        String ref = end < 0 ? rest : rest.substring(0, end);
        int colon = ref.indexOf(':');
        return colon > 0 && colon < ref.length() - 1 ? ref : "";
    }
}
