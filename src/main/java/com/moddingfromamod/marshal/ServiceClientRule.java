package com.moddingfromamod.marshal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <b>Where a child can meet the mod's own clients, and the one answer for every one of them</b>
 *.
 *
 * <p>{@code CameraPresence} hid the camera's BODY and named what it left: a child still read
 * "Moddy-Camera joined the game", saw it in the tab list, saw "1 player" on an empty server in the
 * server list, and could not skip the night while a camera stood awake. Each of those is a
 * different packet or a different count, so each gets its own filter on the Minecraft side — and
 * every filter asks THIS class, so there is one rule rather than six copies of it.
 *
 * <p><b>The rule: a service client is shown only to other service clients.</b> Stated over the two
 * roles, never as "hide the camera from children", because puts several cameras in one world
 * and a camera must keep seeing the others (a second camera in the first one's shot is the same
 * defect with the roles swapped). A surface with no single viewer — the server list, the sleep
 * count, a broadcast line, a datapack's selector — is judged as the PUBLIC, which is not a service
 * client: that is where a child would read it.
 *
 * <p>Minecraft-free, so the whole table is a unit test ({@code ServiceClientRuleTest}); the mixins
 * only filter.
 */
public final class ServiceClientRule {

    private ServiceClientRule() {
    }

    /** Every place a child could meet a service client, each with the mechanism that hides it. */
    public enum Surface {
        /** Its body and name tag: entity packets removed ({@code CameraPresence.sweep}). */
        BODY,
        /** "joined / left the game", an advancement, a death: a system line ABOUT it, not sent. */
        CHAT_LINE,
        /** The tab list: its entry dropped from {@code ClientboundPlayerInfoUpdatePacket}. */
        TAB_LIST,
        /** The server list: left out of the ping's player count and its name sample. */
        SERVER_LIST,
        /** Sleeping: left out of the count of players the night waits for. */
        SLEEP_COUNT,
        /** {@code /list} (any player may run it; so may the server's own tooling, over RCON). */
        LIST_COMMAND,
        /** A selector that picks ONE ({@code @p}, {@code @r}, {@code limit=1}) never picks it; {@code @a} still reaches it, for the veil. */
        SELECTOR,
        /** "Is anybody on": the pool's reap/boot decision and the counts the logs carry. */
        PLAYER_COUNT
    }

    /**
     * <b>Is this subject shown to this viewer, on this surface?</b> The same answer on every surface
     * today — the enum exists so the table is written out, tested row by row, and a surface that
     * ever needs a different answer has somewhere to say so.
     */
    public static boolean shown(Surface surface, boolean viewerIsService, boolean subjectIsService) {
        if (surface == null) {
            throw new IllegalArgumentException("a surface");
        }
        return !subjectIsService || viewerIsService;
    }

    /** The same, for a surface with no single viewer: judged as the public, which is not a service client. */
    public static boolean shownToPublic(Surface surface, boolean subjectIsService) {
        return shown(surface, false, subjectIsService);
    }

    /** Of these subjects, the ones this viewer is shown, in order. Never null. */
    public static List<UUID> visible(boolean viewerIsService, List<UUID> subjects, Collection<UUID> service) {
        List<UUID> out = new ArrayList<>();
        if (subjects == null) {
            return out;
        }
        for (UUID id : subjects) {
            if (shown(Surface.TAB_LIST, viewerIsService, id != null && service != null && service.contains(id))) {
                out.add(id);
            }
        }
        return out;
    }

    /** How many of these are children — the count a surface with no viewer should say. */
    public static int publicCount(Collection<UUID> online, Collection<UUID> service) {
        int n = 0;
        if (online == null) {
            return 0;
        }
        for (UUID id : online) {
            if (shownToPublic(Surface.PLAYER_COUNT, id != null && service != null && service.contains(id))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Is a broadcast line ABOUT a service client? {@code mentioned} is every entity the line names
     * (a player's display name carries its UUID in its hover), so "Moddy-Camera joined the game",
     * "…left the game", "…has made the advancement" and a death line all answer yes here, and a line
     * about a child answers no.
     */
    public static boolean aboutServiceClient(Collection<UUID> mentioned, Collection<UUID> service) {
        if (mentioned == null || service == null || service.isEmpty()) {
            return false;
        }
        for (UUID id : mentioned) {
            if (id != null && service.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
