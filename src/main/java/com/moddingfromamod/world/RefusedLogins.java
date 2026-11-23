package com.moddingfromamod.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The logins this server refused</b>.
 *
 * <p>The allowlist is checked by vanilla's {@code PlayerList.canPlayerLogin}, in the LOGIN phase —
 * after a proxy in front (if any) has authenticated the player with Mojang and switched the
 * connection to encryption, so that proxy cannot read the refusal: this server is the only place that knows.
 * {@code PlayerListMixin} records each one here, and {@code health.json}'s {@code refusedLogins}
 * carries the newest {@link #KEEP} to a server operator's tools (a server woken only for a refused
 * join can go back to sleep in minutes).
 *
 * <p>A name and a reason, never an address (behind the proxy there is none to keep). Pure apart from
 * its one ring, so the rules are a unit test.
 */
public final class RefusedLogins {

    private RefusedLogins() {
    }

    /** How many refusals the health file carries. */
    public static final int KEEP = 20;

    /** The reason for a player not on the allowlist; anything else is vanilla's own key. */
    public static final String NOT_ON_LIST = "not_on_list";

    /** step 6: a Wish Crafter turned away because every slot a Crafter may take was taken. */
    public static final String CRAFTERS_FULL = "moddingfromamod.crafters_full";

    /** One refusal: when (epoch ms), the name it asked as, why. */
    public record Refusal(long atMs, String name, String why) {
    }

    private static final ArrayDeque<Refusal> RECENT = new ArrayDeque<>();

    /** {@code not_on_list} for vanilla's allowlist key, otherwise the key itself (or "other"). */
    public static String why(String translationKey) {
        if (com.moddingfromamod.net.JoinWords.NOT_WHITELISTED_KEY.equals(translationKey)) {
            return NOT_ON_LIST;
        }
        if (CRAFTERS_FULL.equals(translationKey)) {
            return "crafters_full";
        }
        if (translationKey == null || translationKey.isBlank()) {
            return "other";
        }
        String k = translationKey.replace("multiplayer.disconnect.", "");
        return k.matches("[a-z0-9_.]{1,64}") ? k : "other";
    }

    /** A name fit for a log line: a real account's as it is, anything else reduced and marked. */
    public static String name(String name) {
        if (name == null) {
            return "?";
        }
        if (name.matches("[A-Za-z0-9_]{1,16}")) {
            return name;
        }
        String kept = name.replaceAll("[^A-Za-z0-9_]", "");
        return "?" + (kept.isEmpty() ? "invalid" : kept.substring(0, Math.min(16, kept.length())));
    }

    /** Record one refusal. */
    public static void record(String name, String translationKey, long nowMs) {
        synchronized (RECENT) {
            RECENT.addLast(new Refusal(nowMs, name(name), why(translationKey)));
            while (RECENT.size() > KEEP) {
                RECENT.removeFirst();
            }
        }
    }

    /** The newest refusals, oldest first. */
    public static List<Refusal> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    /** Forget everything (a test's clean slate). */
    public static void resetForTest() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }
}
