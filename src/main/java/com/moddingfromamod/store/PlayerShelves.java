package com.moddingfromamod.store;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Every store that keeps a child's things on his shelf, so a login can freshen them</b>.
 *
 * <p>{@link PlayerShelf}'s merge keeps the DISK right when two servers write one child's shelf.
 * What it cannot do is make a server that booted at breakfast KNOW about the wand the child made on
 * the other server at lunch — that server's memory still holds the breakfast shelf, so the wand is
 * on disk and invisible to him here. A child can only be on one server at a time, so the moment he
 * arrives is the moment to look: a store whose shelf for him changed on disk since it last read or
 * wrote it is reloaded, before the join sends him his creatures, items and buttons.
 *
 * <p>Reloading the whole store rather than splicing one child's records in is deliberate: every
 * store here saves synchronously on every change, so its memory and the disk agree, and a reload is
 * the one operation each already has and already tests.
 */
public final class PlayerShelves {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-shelves");

    private record Entry(String name, PlayerShelf shelf, Consumer<MinecraftServer> reload) {}

    private static final List<Entry> STORES = new CopyOnWriteArrayList<>();

    private PlayerShelves() {
    }

    /** Called once from each shelved store's static initialiser. */
    public static void register(String name, PlayerShelf shelf, Consumer<MinecraftServer> reload) {
        STORES.add(new Entry(name, shelf, reload));
    }

    /**
     * Reload every store whose shelf for this child changed on disk behind this server's back.
     *
     * @return the names of the stores reloaded, for the log line and a test
     */
    public static List<String> refreshFor(MinecraftServer server, String owner) {
        java.nio.file.Path root = com.moddingfromamod.runtime.RuntimeConfig.playersDir();
        List<String> reloaded = new java.util.ArrayList<>();
        for (Entry e : STORES) {
            if (!e.shelf().changedOnDisk(root, owner)) {
                continue;
            }
            try {
                e.reload().accept(server);
                reloaded.add(e.name());
            } catch (RuntimeException ex) {
                // A stale view is a nuisance; a join that throws is a child who cannot play.
                LOG.warn("could not refresh {} for {}: {}", e.name(), owner, ex.toString());
            }
        }
        if (!reloaded.isEmpty()) {
            LOG.info("{} arrived with things made on another server; reloaded {}", owner, reloaded);
        }
        return reloaded;
    }
}
