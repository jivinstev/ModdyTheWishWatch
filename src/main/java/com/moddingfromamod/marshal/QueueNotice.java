package com.moddingfromamod.marshal;

/**
 * <b>"2 wishes ahead of yours"</b> — the one queue fact a child is ever shown (plan §9 item 6;
 * {@code MULTIPLAYER_DECISIONS.md} §4, <i>"make the wait visible first"</i>).
 *
 * <p>Minecraft-free, because the whole of it is a decision about wording and a decision about when
 * to say nothing, and both are worth a unit test.
 *
 * <h2>Why this is not the queue notices that were deleted</h2>
 *
 * <p>{@code WishProgress.renderLine} used to carry <i>"🪄 I heard your next wish!"</i> and
 * <i>"…and I've got your next wish ready — right after this one!"</i>, and its own note says why
 * they went: they were <b>queue management wearing a friendly voice</b>, about the child's OWN
 * second wish, which already had a bar and a beam of its own. Nothing was hidden from him by
 * removing them.
 *
 * <p>This is the other case, and it is the one a home server created: his wish is behind
 * <b>somebody else's</b>. There is no bar for that, no beam, and no way for him to find out — the
 * screen says "Making something amazing…" for four minutes while nothing of his has started. A
 * count is not queue management there; it is the difference between waiting and being ignored.
 *
 * <h2>Two rules</h2>
 *
 * <ul>
 *   <li><b>Silence when he is next</b> (ahead = 0) <b>and when he is not waiting at all</b>
 *       (ahead &lt; 0, which is the ordinary case: his wish is being granted). A line that said
 *       "0 wishes ahead of yours" is a queue he has been made to think about for no reason.</li>
 *   <li><b>Plural is counted, never "(s)".</b> A seven-year-old reads the line; the parenthesis is
 *       for a log.</li>
 * </ul>
 */
public final class QueueNotice {

    private QueueNotice() {
    }

    /**
     * The suffix for the status line, or {@code ""} when there is nothing worth saying.
     *
     * @param ahead what {@link Marshal#aheadOf} returned
     */
    public static String aheadLine(int ahead) {
        if (ahead <= 0) {
            return "";
        }
        return ahead == 1 ? "1 wish ahead of yours" : ahead + " wishes ahead of yours";
    }
}
