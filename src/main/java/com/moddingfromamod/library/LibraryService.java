package com.moddingfromamod.library;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.LearnedTemplates;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The thin, Minecraft-facing half of the wish library</b>.
 *
 * <p>{@link WishLibrary} is pure and knows nothing about a server. This is everything that needs
 * one: holding it while a world is open, re-reading {@code learned.json} in the same breath as the
 * library, and pushing the buttons back out so a panel updates without anybody relogging.
 *
 * <p><b>Both files, one reload.</b> {@code learned.json} was read once at boot,
 * so a grown-up's hand edit needed a restart — the same
 * restart this chunk exists to remove. Re-reading it here closes that gap for free, and it has to
 * happen anyway: the composed template set is library + learned, so half a reload would
 * leave the matcher holding a set neither file describes.
 *
 * <p><b>The poll thread never touches the game.</b> Its callback hops onto the server thread through
 * {@code server.execute}, because the reload ends in a packet to every client.
 */
public final class LibraryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private static volatile MinecraftServer server;

    private LibraryService() {
    }

    /**
     * World open: read the library, start the poll.
     *
     * <p>Called BEFORE {@code LearnedTemplates.load}, because that load rebuilds the active template
     * set and the rebuild folds the library in. The other order gives a first-boot window in which
     * the curated wishes exist on disk and are not being served.
     */
    public static void open(MinecraftServer mc) {
        server = mc;
        WishLibrary.onReload(LibraryService::reloadOnServerThread);
        WishLibrary.reload();
        WishLibrary.startPolling();
        com.moddingfromamod.wish.WatchPanelSnapshot.publish();   // What an operator's dashboard draws
    }

    /** World close. */
    public static void close() {
        WishLibrary.clear();
        WishLibrary.onReload(null);
        server = null;
    }

    /**
     * Re-read the library AND {@code learned.json}, then push the buttons. Server thread.
     *
     * @return the one-line summary an operator (or {@code /moddyop library reload}) reads
     */
    public static String reloadNow(MinecraftServer mc) {
        MinecraftServer target = mc == null ? server : mc;
        LibrarySnapshot next = WishLibrary.reload();
        int learned = 0;
        if (target != null) {
            // load() ends in rebuild(), which is what folds library + learned together.
            LearnedTemplates.load(target);
            learned = LearnedTemplates.count();
            LearnedTemplates.pushPresets(target);
        }
        // A promote, a demote and a reorder all land here, so this is where an operator
        // dashboard's copy of the watch is refreshed.
        com.moddingfromamod.wish.WatchPanelSnapshot.publish();
        return "library " + next.summary() + "; learned " + learned + " template(s)";
    }

    private static void reloadOnServerThread() {
        MinecraftServer target = server;
        if (target == null) {
            WishLibrary.reload();
            return;
        }
        target.execute(() -> {
            try {
                LOGGER.info("{}", reloadNow(target));
            } catch (RuntimeException e) {
                LOGGER.warn("could not reload the wish library: {}", e.toString());
            }
        });
    }
}
