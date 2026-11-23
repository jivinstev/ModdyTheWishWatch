package com.moddingfromamod.agent;

/**
 * What a tool gives back to the agent.
 *
 * <p>Note the MCP distinction that is easy to get wrong: a tool that <em>fails</em> still returns a
 * successful JSON-RPC <b>result</b> carrying {@code isError:true} — it is not a JSON-RPC error.
 * JSON-RPC errors are reserved for protocol problems (bad method, malformed request). Getting this
 * backwards makes clients treat a recoverable tool failure as a broken connection.
 *
 * <p>A result may also carry an <b>image</b>, and that isn't a nicety. The agent runs with
 * {@code --permission-mode dontAsk} and only the MCP tools pre-approved — no Bash, no file reading.
 * Handing it the <i>path</i> to a screenshot would be handing it nothing. If a tool wants the agent
 * to actually <i>see</i> something, the pixels have to travel inside the result.
 */
public record ToolResult(String text, boolean isError, String imageBase64, String imageMime) {

    public static ToolResult ok(String text) {
        return new ToolResult(text == null ? "" : text, false, null, null);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text == null ? "unknown error" : text, true, null, null);
    }

    /**
     * <b>The wish is being granted with NOBODY ONLINE</b> — a named outcome, and a counted one.
     *
     * <p>Ten tools refuse for this reason and each one said it in its own words, so the outcome had
     * no name and nothing could count it. That matters because it is not rare: it is the permanent
     * state of <b>Gate C½</b>, whose submitter is a {@code FakePlayer} and which therefore has an
     * empty player list for the whole run.
     *
     * <p>Measured across the first eight real wishes mined into {@code corpus/real-tool-calls.jsonl}:
     * {@code conjure_at} was called in the first three calls of <b>7 of 8</b> wishes and failed
     * <b>7 of 7</b>, purely for this — <b>6–14% of every C½ wish</b> is a turn spent on a tool that
     * could not have worked. Invisible unless you read every result string, and twice in one session
     * it was nearly written up as agent waste against.
     *
     * <p>So it is counted at the source rather than matched on later. Matching the message would be
     * the "denylist that silently rots" {@link com.moddingfromamod.RunContext} was written to avoid
     * — ten phrasings, and the eleventh tool would simply not be counted.
     *
     * @param what what could not be done, in the agent's terms — the message still has to be useful
     */
    public static ToolResult noPlayerOnline(String what) {
        NO_PLAYER_REFUSALS.incrementAndGet();
        return error(what);
    }

    private static final java.util.concurrent.atomic.AtomicInteger NO_PLAYER_REFUSALS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** How many tool calls have been refused because nobody was online. */
    public static int noPlayerRefusals() {
        return NO_PLAYER_REFUSALS.get();
    }

    /** Reset the tally — a probe measuring one wish, or a test that wants its own count. */
    public static void resetNoPlayerRefusals() {
        NO_PLAYER_REFUSALS.set(0);
    }

    /** A result the agent can look at: some words, plus the image they describe. */
    public static ToolResult image(String text, String base64, String mimeType) {
        return new ToolResult(text == null ? "" : text, false, base64,
                mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
    }

    public boolean hasImage() {
        return imageBase64 != null && !imageBase64.isEmpty();
    }
}
