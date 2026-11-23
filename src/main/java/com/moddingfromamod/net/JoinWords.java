package com.moddingfromamod.net;

/**
 * What a player is told when they cannot get in, and the "trouble joining?" line under the server
 * name in the server list. Pure, so the wording is pinned by a unit test rather than by a login.
 *
 * <p>Plain text on purpose. Vanilla's refusal is a TRANSLATION KEY that the player's own game looks
 * up, so the server cannot change its words; a literal string is shown as sent, by every version
 * the proxy lets in (1.21.1 to 26.x).
 *
 * <p>Only the not-on-the-list refusal can be ours. "Invalid session" is decided by the player's own
 * game, which hangs up on Mojang's answer before this server can say anything; the server-list line
 * is what reaches a player whatever went wrong, which is why it points at the join page's
 * troubleshooting section.
 */
public final class JoinWords {

    private JoinWords() {}

    /** The key vanilla's {@code PlayerList.canPlayerLogin} returns for a player not on the list. */
    public static final String NOT_WHITELISTED_KEY = "multiplayer.disconnect.not_whitelisted";

    // Both refusals send a player to /join — the guide's three
    // steps, whose first is Get my spot (one sign-in that also asks for a seat). /access and /account
    // still land where they did, so a server configured with an old address keeps working.
    public static final String DEFAULT_ACCESS_URL = "moddythewishwatch.com/join";
    // The server list has room for about 45 characters, so it keeps the short /join, which
    // redirects to the guide's join section, with Trouble joining right under it.
    public static final String DEFAULT_JOIN_URL = "moddythewishwatch.com/join";

    /** The key vanilla's {@code PlayerList.canPlayerLogin} returns when every slot is taken (1.21.1 and 1.21.4). */
    public static final String SERVER_FULL_KEY = "multiplayer.disconnect.server_full";

    /**
     * Where a stranger asks for a SEAT on a world that takes requests — /join, the
     * guide's three steps, whose Get my spot signs in and asks in one press (/join redirects to the
     * guide's join section).
     */
    public static final String DEFAULT_SEAT_URL = "moddythewishwatch.com/join";

    /**
     * the not-on-the-list refusal, knowing whether the proxy vouched for this login. An UNCHECKED
     * login (identity on, no forwarded answer) is a forwarding failure on a server where every real player
     * is forwarded, so it hears {@code IdentityGate.REFUSED}'s "try again", never "you don't have a seat"
     * (his seat is under his key, which an unforwarded login cannot reach).
     */
    public static String notOnTheList(String name, com.moddingfromamod.world.WorldFacts world,
            String accessUrl, String seatUrl, boolean unchecked) {
        return unchecked ? PlayerIdentity.TRY_AGAIN
                : notOnTheList(name, world, accessUrl, seatUrl);
    }

    /** The refusal for a player not on the allowlist. */
    public static String notOnTheList(String name, String accessUrl) {
        return "Moddy is invite-only, and " + who(name, "isn't", "you aren't") + " on the list yet.\n"
                + "Get your spot at " + url(accessUrl, DEFAULT_ACCESS_URL);
    }

    /**
     * The not-on-the-list refusal, PER WORLD. A world a stranger joins by
     * asking for a seat ({@code access.join: "request"}) says where to ask and that it is
     * quick; every other world — including one whose manifest says nothing — keeps the
     * invite-only words of {@link #notOnTheList(String, String)}.
     *
     * <p>It does NOT say how many seats there are: that number is the account service's, and a server cannot know it — the
     * server's player cap is a different number (how many at once, not how many may join).
     */
    public static String notOnTheList(String name, com.moddingfromamod.world.WorldFacts world,
            String accessUrl, String seatUrl) {
        if (world == null || !world.bySeat()) {
            return notOnTheList(name, accessUrl);
        }
        String place = world.name().isEmpty() ? "this world" : world.name();
        String first = world.minAge() > 0
                ? place + " is for players " + world.minAge() + " and up, and "
                        + who(name, "doesn't", "you don't") + " have a seat yet."
                : capital(who(name, "doesn't", "you don't")) + " have a seat on " + place + " yet.";
        return first + "\n"
                + "Get your spot at " + url(seatUrl, DEFAULT_SEAT_URL) + " — it only takes a minute.";
    }

    /**
     * Vanilla's "Server is full" in friendlier words, with the world's name and its real
     * player cap ({@code max-players}). Only a non-operator ever sees it (an op bypasses the cap).
     */
    public static String serverFull(com.moddingfromamod.world.WorldFacts world, int maxPlayers) {
        String place = world == null || world.name().isEmpty() ? "Moddy" : world.name();
        String count = maxPlayers > 0 ? " (" + maxPlayers + (maxPlayers == 1 ? " player" : " players") + ")" : "";
        return place + " is full right now" + count + ".\nTry again in a few minutes!";
    }

    /** "Kid isn't" / "you aren't": a real account name, or the second person when it is not one. */
    private static String who(String name, String verb, String second) {
        return name == null || !name.matches("[A-Za-z0-9_]{1,16}") ? second : name + " " + verb;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The server-list line; blank when switched off. */
    public static String helpLine(String joinUrl) {
        if (joinUrl != null && joinUrl.strip().equalsIgnoreCase("off")) {
            return "";
        }
        return "§7Trouble joining? §f" + url(joinUrl, DEFAULT_JOIN_URL);
    }

    /**
     * The server's first MOTD line, then the help line — never twice, never a third line (the server
     * list shows two). A MOTD that already ends in the help line comes back unchanged.
     */
    public static String withHelpLine(String motd, String helpLine) {
        String first = motd == null ? "" : motd.split("\n", 2)[0];
        if (helpLine == null || helpLine.isBlank()) {
            return motd == null ? "" : motd;
        }
        return first.isBlank() ? helpLine : first + "\n" + helpLine;
    }

    private static String url(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured.strip();
    }
}
