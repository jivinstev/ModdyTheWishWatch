package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>"Which one do you mean?" — the question the AGENT asks, and the answer it waits for</b>
 * ({@code ask_which}).
 *
 * <p>One open question per child. The tool opens it and waits on the future; every way he can
 * answer — the modded picture picker, the stock client's chest of pictures, a clickable name in
 * chat — runs {@code /moddy which <id>}, which lands in {@link #answer}. Only an id that was
 * offered completes it, so a stale click on an old question cannot answer a new one.
 *
 * <p>Minecraft-free, so the answering rules are unit-tested ({@code WhichQuestionTest}).
 */
public final class WhichQuestion {

    /** What {@code /moddy which} carries when he says it was none of them. */
    public static final String NONE = "none";

    /** An open question: what was offered, and the answer it is waiting for. */
    public record Open(List<String> ids, CompletableFuture<String> answer) {

        public Open {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    private static final Map<UUID, Open> OPEN = new ConcurrentHashMap<>();

    private WhichQuestion() {
    }

    /**
     * Open a question for him, replacing (and answering {@link #NONE} to) any older one — a child
     * can only be asked one thing at a time, and the older asker should stop waiting.
     */
    public static Open ask(UUID playerId, List<String> ids) {
        Open q = new Open(ids, new CompletableFuture<>());
        Open old = OPEN.put(playerId, q);
        if (old != null) {
            old.answer().complete(NONE);
        }
        return q;
    }

    /** The question open for him, or null. */
    public static Open openFor(UUID playerId) {
        return playerId == null ? null : OPEN.get(playerId);
    }

    /**
     * He answered. True when that answered an open question: {@code id} is one of the offered ids
     * (case-insensitive) or {@link #NONE}.
     */
    public static boolean answer(UUID playerId, String id) {
        return answer(playerId, openFor(playerId), id);
    }

    /**
     * He answered THIS question — the one a particular chest was opened for. False when another
     * question has replaced it since, so a chest that is closed because a newer one opened over it
     * cannot answer the newer one. Measured live (the stock which-gate): two wishes asked about
     * the same three castles 12 ms apart; opening the second chest closed the first, whose close
     * matched the new question by its ids and answered it "none" before the child could click.
     */
    public static boolean answer(UUID playerId, Open asked, String id) {
        Open q = openFor(playerId);
        if (q == null || asked == null || q != asked || id == null) {
            return false;
        }
        String picked = null;
        if (NONE.equalsIgnoreCase(id.strip())) {
            picked = NONE;
        } else {
            for (String offered : q.ids()) {
                if (offered.equalsIgnoreCase(id.strip())) {
                    picked = offered;
                }
            }
        }
        if (picked == null) {
            return false;
        }
        OPEN.remove(playerId, q);
        return q.answer().complete(picked);
    }

    /** The asker stopped waiting (timed out, or the wish was stopped). */
    public static void close(UUID playerId, Open q) {
        if (playerId != null && q != null) {
            OPEN.remove(playerId, q);
        }
    }

    public static void clear() {
        OPEN.values().forEach(q -> q.answer().complete(NONE));
        OPEN.clear();
    }
}
