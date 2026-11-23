package com.moddingfromamod.wish;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The live record of wishes for the running server: an in-memory map (fast, ordered, server-thread
 * confined) backed by {@link WishJournal} on disk.
 *
 * <p>Threading: <b>server thread only.</b> Everything that mutates a wish happens here, so once the
 * MCP agent exists its tools must hop onto the server thread before touching this class. That keeps
 * "who owns this wish" a simple question with a single answer.
 */
public final class WishBook {

    /** Cap on wishes held in memory; oldest are dropped (the journal still has them). */
    public static final int MAX_IN_MEMORY = 500;   // public for the GameTests that count the book

    private static WishBook instance;

    private final Map<UUID, Wish> wishes = new LinkedHashMap<>();
    private final WishJournal journal;

    private WishBook(WishJournal journal) {
        this.journal = journal;
    }

    /** The book for this server, creating it (and loading history) on first use. */
    public static synchronized WishBook get(MinecraftServer server) {
        if (instance == null) {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("wishes");
            WishBook book = new WishBook(new WishJournal(dir.resolve("journal.jsonl")));
            for (Wish w : book.journal.readAll()) {
                book.remember(w);
            }
            instance = book;
        }
        return instance;
    }

    /** A book over a journal of the test's own, never the singleton. Unit tests only. */
    static WishBook forTest(WishJournal journal) {
        return new WishBook(journal);
    }

    /** Drop the cached book (server stop) so a different world starts clean. */
    public static synchronized void reset() {
        instance = null;
    }

    /** Record a brand-new wish and journal it. */
    public synchronized Wish add(Wish wish) {
        remember(wish);
        journal.append(wish);
        // Every wish passes here, so here is where "another wish" ends the Apprentice's free change.
        com.moddingfromamod.wishbuilder.FreeEdit.anotherWish(wish.playerId(), wish.id());
        return wish;
    }

    /** Update a wish's outcome and journal the new state. */
    public synchronized Optional<Wish> complete(UUID id, WishStatus status, String result) {
        Wish existing = wishes.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        // STAMPED HERE, at the one place a status moves —. Recording a completion time in some
        // code paths and not others is worse than recording none, because the average it produces
        // looks real. `withStatus` only stamps a FINAL status, and only the first time.
        Wish updated = existing.withStatus(status, result, System.currentTimeMillis());
        wishes.put(id, updated);
        journal.append(updated);
        // THE ENGINE'S HALF OF spend.wish, at the same one place, and only the FIRST time
        // a wish settles — so the cost record is joined to one outcome, never to a re-stamp.
        if (!existing.isSettled() && updated.isSettled()) {
            com.moddingfromamod.agent.ActionLog.settled(
                    updated.id().toString().substring(0, 8),
                    updated.playerId() == null ? "" : updated.playerId().toString(),
                    updated.status().name(),
                    updated.source() == null ? "" : updated.source().name(),
                    updated.tookMs(),
                    // the model that SERVED it when the Muse shim chose per worker
                    // (finish_wish settles on that worker's own call), else the requested spec.
                    com.moddingfromamod.agent.MuseShimState.servedSpecForCurrentWorker()
                            .orElseGet(com.moddingfromamod.agent.AgentSession::resolvedModel),
                    com.moddingfromamod.agent.AgentSession.resolvedEffort());
            // however it ended, the wish's gate and its claims, trigger sentences and
            // machine complaints go with it — and only ITS; another wish in flight keeps its own.
            BuildGate.settle(updated.id());
        }
        // The wish bank hears every status move HERE, at the one place one happens — a charged
        // wish that ends ungranted is refunded, a granted one says what is left. A no-op on a world
        // with no bank, and it must never cost the book its record.
        try {
            WishCharge.get().onStatus(existing, updated);
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("moddingfromamod/wish-bank")
                    .warn("wish bank could not hear wish {} settle: {}", updated.shortId(), e.toString());
        }
        return Optional.of(updated);
    }

    /**
     * <b>Re-open a wish in new words</b> — the {@code /moddy redirect} path
     * ({@code docs/CHILD_SAFETY_PLAN.md} §12.7).
     *
     * <p>One method rather than {@code withText} followed by {@link #complete}, and one journal
     * append rather than two: the text and the re-opening are one thing that happened, and a
     * journal carrying a PENDING wish under its old words for one line is a record of a state that
     * never existed.
     *
     * <p>Status is always {@link WishStatus#PENDING} — a re-aimed wish is by definition going back
     * to the agent, so there is no other status to pass and nothing for a caller to get wrong.
     *
     * @param newText the whole new text, already sanitised by {@link WishText#reaimed}
     * @param note    what the agent should know about why it came back
     */
    public synchronized Optional<Wish> reaim(UUID id, String newText, String note) {
        Wish existing = wishes.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        Wish updated = existing.withText(newText)
                .withStatus(WishStatus.PENDING, note, System.currentTimeMillis());
        wishes.put(id, updated);
        journal.append(updated);
        return Optional.of(updated);
    }

    /**
     * Record which template granted this wish, so a later rejection can un-teach that exact one.
     * Not journalled by itself — the executor's completion writes the wish's final state.
     */
    public synchronized Optional<Wish> tagTemplate(UUID id, String templateId) {
        Wish existing = wishes.get(id);
        if (existing == null) {
            return Optional.empty();
        }
        Wish updated = existing.withTemplate(templateId);
        wishes.put(id, updated);
        return Optional.of(updated);
    }

    public synchronized Optional<Wish> byId(UUID id) {
        return Optional.ofNullable(wishes.get(id));
    }

    /** Wishes still waiting — this is what the agent will ask for. */
    public synchronized List<Wish> pending() {
        List<Wish> out = new ArrayList<>();
        for (Wish w : wishes.values()) {
            if (w.status() == WishStatus.PENDING) {
                out.add(w);
            }
        }
        return out;
    }

    /** The most recent wishes, newest last. */
    public synchronized List<Wish> recent(int limit) {
        List<Wish> all = new ArrayList<>(wishes.values());
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public synchronized int size() {
        return wishes.size();
    }

    public WishJournal journal() {
        return journal;
    }

    private void remember(Wish wish) {
        wishes.remove(wish.id()); // re-insert so ordering reflects the latest update
        wishes.put(wish.id(), wish);
        while (wishes.size() > MAX_IN_MEMORY) {
            UUID oldest = wishes.keySet().iterator().next();
            wishes.remove(oldest);
        }
    }
}
