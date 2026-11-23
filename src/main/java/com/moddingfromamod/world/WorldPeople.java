package com.moddingfromamod.world;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>A world's rules for PEOPLE</b> (D18) — the manifest's {@code people} block, read from the
 * same bundled {@code /worlds/<id>.json} {@link WorldFacts} reads, so it rides a release and needs no
 * launch-config line.
 *
 * <pre>
 *   "people": { "chatSweep": "on" | "shadow" | "off", "idleSeatDays": 14,
 *               "chat": "everyone" | "self", "islandGuard": true, "pvp": false, "placedText": "sweep" | "off" }
 * </pre>
 *
 * <ul>
 *   <li>{@code chatSweep} — whether players' own chat on this world passes through the safety lexicon
 *       ({@link com.moddingfromamod.safety.SafetyChatSweep}). <b>Absent is {@code off}</b>: a
 *       family world's chat is exactly what it was. A world for strangers (13+) says {@code on}. {@code shadow} logs what it would have held back and holds back nothing.</li>
 *   <li>{@code idleSeatDays} — read by the ACCOUNT service, not here: a public seat whose holder has not played for this many days goes back
 *       to the queue. Parsed here only so the one manifest reader in the jar knows the whole block.</li>
 *   <li>{@code chat} (step 4, docs/OPEN_DOOR_PLAN.md §11.3) — {@code self}: a player's chat line
 *       goes back to HIM ONLY, and {@code /msg /tell /w /teammsg /me} are refused — for a world that is
 *       pseudo-single-player and open to any age. Absent is {@code everyone}: chat as it was.</li>
 *   <li>{@code islandGuard} — a player standing in ANOTHER player's island cell (the overworld, or
 *       the Nether mapped back ×8) is sent home. Absent is off.</li>
 *   <li>{@code pvp} — {@code false}: no player hurts another, by hand or by anything he fired or
 *       threw. Absent is vanilla (true).</li>
 *   <li>{@code placedText} — {@code sweep}: signs, books and anvil names are refused when they hold a
 *       lexicon CLEARLY term or a way to find someone elsewhere ({@code chat.PlacedText}). Absent is off.</li>
 * </ul>
 *
 * <p>{@code -Dmoddingfromamod.chat.sweep=on|shadow|off} overrides {@code chatSweep} (a GameTest, a
 * rehearsal on a world with no manifest). Minecraft-free; {@link #parse} is the Gate A surface.
 *
 * @param chatSweep    the mode, never null
 * @param idleSeatDays 0 when the world never reclaims a seat
 * @param chatToSelf   chat stays with its sender, and private messages are refused
 * @param islandGuard  a player in someone else's island cell is sent home
 * @param pvp          players may hurt each other (vanilla)
 * @param placedText   signs, books and anvil names are swept
 */
public record WorldPeople(Sweep chatSweep, int idleSeatDays, boolean chatToSelf, boolean islandGuard, boolean pvp,
                          boolean placedText) {

    /** The three modes, the same vocabulary as {@code child_safety}. */
    public enum Sweep {
        ON, SHADOW, OFF;

        /** A manifest word (any case) → a mode; anything else is {@code null}. */
        public static Sweep of(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "on" -> ON;
                case "shadow" -> SHADOW;
                case "off" -> OFF;
                default -> null;
            };
        }
    }

    /** A world that says nothing: chat untouched, no seat ever reclaimed. */
    public static final WorldPeople NONE = new WorldPeople(Sweep.OFF, 0, false, false, true, false);

    /** The two-field form every world had step 4: the rest as vanilla. */
    public WorldPeople(Sweep chatSweep, int idleSeatDays) {
        this(chatSweep, idleSeatDays, false, false, true, false);
    }

    /**
     * JVM overrides of the rules, for a GameTest or a rehearsal on a world with no manifest:
     * {@code -Dmoddingfromamod.people.chat=self|everyone}, {@code ….islandGuard=true|false},
     * {@code ….pvp=true|false}, {@code ….placedText=sweep|off}.
     */
    public static final String PROPERTY_PREFIX = "moddingfromamod.people.";

    /** The JVM override of {@code chatSweep}. */
    public static final String SWEEP_PROPERTY = "moddingfromamod.chat.sweep";

    private static final Map<String, WorldPeople> CACHE = new ConcurrentHashMap<>();

    public WorldPeople {
        chatSweep = chatSweep == null ? Sweep.OFF : chatSweep;
        idleSeatDays = Math.max(0, idleSeatDays);
    }

    /** This world's rules (the manifest's, with the property winning for the sweep). */
    public static WorldPeople current() {
        WorldPeople base = withOverride(forId(WorldFacts.manifestId()), System.getProperty(SWEEP_PROPERTY));
        return withRuleOverrides(base, System.getProperty(PROPERTY_PREFIX + "chat"),
                System.getProperty(PROPERTY_PREFIX + "islandGuard"), System.getProperty(PROPERTY_PREFIX + "pvp"),
                System.getProperty(PROPERTY_PREFIX + "placedText"));
    }

    /** The overrides of {@link #current()}: a valid word replaces the rule, anything else is ignored. Pure. */
    static WorldPeople withRuleOverrides(WorldPeople b, String chat, String guard, String pvp, String placed) {
        boolean self = "self".equals(chat) ? true : "everyone".equals(chat) ? false : b.chatToSelf();
        boolean g = "true".equals(guard) ? true : "false".equals(guard) ? false : b.islandGuard();
        boolean pv = "true".equals(pvp) ? true : "false".equals(pvp) ? false : b.pvp();
        boolean pt = "sweep".equals(placed) ? true : "off".equals(placed) ? false : b.placedText();
        return new WorldPeople(b.chatSweep(), b.idleSeatDays(), self, g, pv, pt);
    }

    /** The rule of {@link #current()}: a valid override replaces the sweep, anything else is ignored. Pure. */
    static WorldPeople withOverride(WorldPeople base, String override) {
        Sweep s = Sweep.of(override);
        return s == null ? base : new WorldPeople(s, base.idleSeatDays(), base.chatToSelf(), base.islandGuard(),
                base.pvp(), base.placedText());
    }

    /** The bundled manifest's block for {@code id}, or {@link #NONE}. */
    public static WorldPeople forId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            return NONE;
        }
        return CACHE.computeIfAbsent(id, WorldPeople::load);
    }

    private static WorldPeople load(String id) {
        try {
            String text = WorldFacts.manifestText(id);   //: the core's jar or a level jar's
            return text == null ? NONE : parse(text);
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    /**
     * A manifest's text → its people rules. Malformed falls back field by field to {@link #NONE}'s —
     * an unknown sweep word is OFF, never a guess at ON, because a manifest check refuses it before a
     * release can carry it and a family world's chat must never be swept by accident. Pure.
     */
    public static WorldPeople parse(String json) {
        JsonObject people;
        try {
            JsonElement el = JsonParser.parseString(json == null ? "" : json);
            if (!el.isJsonObject()) {
                return NONE;
            }
            JsonElement p = el.getAsJsonObject().get("people");
            if (p == null || !p.isJsonObject()) {
                return NONE;
            }
            people = p.getAsJsonObject();
        } catch (RuntimeException e) {
            return NONE;
        }
        Sweep sweep = null;
        JsonElement s = people.get("chatSweep");
        if (s != null && s.isJsonPrimitive()) {
            sweep = Sweep.of(s.getAsString());
        }
        int days = 0;
        JsonElement d = people.get("idleSeatDays");
        if (d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
            days = d.getAsInt();
        }
        // step 4 — each absent or unreadable rule is the vanilla one, never a guess at the strict one:
        // world-manifest.py refuses a bad word before a release can carry it.
        boolean self = "self".equals(str(people, "chat"));
        boolean guard = bool(people, "islandGuard", false);
        boolean pvp = bool(people, "pvp", true);
        boolean placed = "sweep".equals(str(people, "placedText"));
        return new WorldPeople(sweep == null ? Sweep.OFF : sweep, days, self, guard, pvp, placed);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k, boolean absent) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : absent;
    }
}
