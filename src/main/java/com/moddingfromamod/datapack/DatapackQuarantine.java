package com.moddingfromamod.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.store.WishStoreFile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A wish datapack that was eating the server is paused, said out loud, and given back by a grown-up</b>
 *. {@link PackCostMeter} decides WHEN and WHICH ({@link QuarantineRule}); this does it.
 *
 * <p><b>What a quarantine does</b>, in order:
 * <ol>
 *   <li>switches the pack off ({@link DatapackReloader#disableAndReload}): reversible, the zip is kept;</li>
 *   <li>clears away what it spawned in the last minute ({@link PackCostMeter#discardRecentSpawns}) — the
 *       runaway crowd is what makes the ticks slow, and pausing the magic alone would leave it colliding;</li>
 *   <li>writes it down in {@code <world>/wishes/datapack-quarantine.json} (store {@code datapack-quarantine}
 *       v1), so it STAYS off across a restart — and {@link DatapackReloader} keeps every path that rebuilds the
 *       selection from switching it back on, a recompile of the same pack included;</li>
 *   <li>tells the child who wished it, in kid words, if he is on; and the server's grown-ups, through one line in
 *       {@code $MODDY_HOME/datapack-quarantine.jsonl}, which a server operator's tools can read.</li>
 * </ol>
 * {@code /moddyop quarantine list|restore <pack>} is the grown-up's door. A restore clears the record first and
 * then switches the pack on, and tells the child it is back.
 *
 * <p><b>After a crash</b> ({@link #afterCrash}): at boot, a watchdog crash report this world has not handled yet
 * quarantines the pack the meter last suspected before it, or else the packs installed in the ten minutes before
 * it ({@link QuarantineRule#crashSuspects}). Every other pack loads.
 */
public final class DatapackQuarantine {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-quarantine");

    public static final String STORE = "datapack-quarantine";
    public static final int SCHEMA = 1;
    static final String EVENTS = "datapack-quarantine.jsonl";
    /** How often the meter's breadcrumb may be rewritten while ticks are slow. */
    static final long SUSPECT_EVERY_MS = 1_000;

    /** One paused pack. */
    public record Entry(String pack, long atMs, String why, String owner, String source) {
    }

    private static final Map<String, Entry> PAUSED = new LinkedHashMap<>();
    private static final Map<String, String> OWNERS = new LinkedHashMap<>();
    private static final List<String> HANDLED_CRASHES = new ArrayList<>();
    private static String suspect;
    private static long suspectAtMs;
    private static long suspectWrittenMs;
    private static Path loadedFrom;
    /** A file this build cannot read is never written over (the latch). */
    private static String refused;
    /** Test seam: where the event log goes (null = {@code RuntimeConfig.home()}). */
    private static Path eventsOverride;

    private DatapackQuarantine() {
    }

    // ── the store ──────────────────────────────────────────────────────────────────────────────────

    static Path storeFile() {
        Path wishes = com.moddingfromamod.runtime.WorldPaths.wishesDir();
        return wishes == null ? null : wishes.resolve(STORE + ".json");
    }

    /** Read the store for the open world, once per world. */
    static synchronized void ensureLoaded() {
        Path f = storeFile();
        if (f == null || f.equals(loadedFrom)) {
            return;
        }
        loadedFrom = f;
        PAUSED.clear();
        OWNERS.clear();
        HANDLED_CRASHES.clear();
        suspect = null;
        suspectAtMs = 0;
        refused = null;
        WishStoreFile.Read read = WishStoreFile.readFile(f, STORE, SCHEMA);
        if (read instanceof WishStoreFile.Read.Refused r) {
            refused = r.reason();
            LOG.error("[quarantine] {} {} — it is left untouched and NOTHING new is recorded in it", f, r.reason());
            return;
        }
        JsonObject root = WishStoreFile.rootOf(read);
        if (root == null) {
            return;
        }
        for (JsonElement e : arr(root, "paused")) {
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                String pack = str(o, "pack");
                if (!pack.isEmpty()) {
                    PAUSED.put(pack, new Entry(pack, num(o, "at"), str(o, "why"), "", str(o, "source")));
                }
            }
        }
        // whose each pack is: rows by playerId, so erasing a player drops his (PER_WORLD_PLAYER)
        for (JsonElement e : arr(root, "owners")) {
            if (e.isJsonObject() && !str(e.getAsJsonObject(), "pack").isEmpty()) {
                OWNERS.put(str(e.getAsJsonObject(), "pack"), str(e.getAsJsonObject(), "playerId"));
            }
        }
        PAUSED.replaceAll((k, v) -> new Entry(v.pack(), v.atMs(), v.why(), OWNERS.getOrDefault(k, ""), v.source()));
        for (JsonElement e : arr(root, "handledCrashes")) {
            HANDLED_CRASHES.add(e.getAsString());
        }
        if (root.has("suspect") && root.get("suspect").isJsonObject()) {
            suspect = str(root.getAsJsonObject("suspect"), "pack");
            suspectAtMs = num(root.getAsJsonObject("suspect"), "at");
        }
    }

    private static synchronized void save() {
        Path f = storeFile();
        if (f == null || refused != null) {
            return;
        }
        JsonObject o = new JsonObject();
        JsonArray paused = new JsonArray();
        for (Entry e : PAUSED.values()) {
            JsonObject p = new JsonObject();
            p.addProperty("pack", e.pack());
            p.addProperty("at", e.atMs());
            p.addProperty("why", e.why());
            p.addProperty("source", e.source());
            paused.add(p);
        }
        o.add("paused", paused);
        JsonArray owners = new JsonArray();
        OWNERS.forEach((pack, who) -> {
            JsonObject r = new JsonObject();
            r.addProperty("pack", pack);
            r.addProperty("playerId", who);
            owners.add(r);
        });
        o.add("owners", owners);
        JsonArray crashes = new JsonArray();
        HANDLED_CRASHES.forEach(crashes::add);
        o.add("handledCrashes", crashes);
        if (suspect != null) {
            JsonObject s = new JsonObject();
            s.addProperty("pack", suspect);
            s.addProperty("at", suspectAtMs);
            o.add("suspect", s);
        }
        try {
            WishStoreFile.writeFile(f, STORE, SCHEMA, o);
        } catch (IOException e) {
            LOG.warn("[quarantine] could not save {}: {}", f, e.toString());
        }
    }

    // ── reading it ─────────────────────────────────────────────────────────────────────────────────

    /** Is this pack (a feature id) paused in the open world? */
    public static synchronized boolean isQuarantined(String pack) {
        ensureLoaded();
        return pack != null && PAUSED.containsKey(pack);
    }

    /** Every paused pack in the open world. */
    public static synchronized List<Entry> paused() {
        ensureLoaded();
        return new ArrayList<>(PAUSED.values());
    }

    /** The paused packs as selection ids ({@code file/<id>.zip}) — what {@link DatapackReloader} keeps off. */
    static synchronized List<String> pausedSelectionIds() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (String p : PAUSED.keySet()) {
            out.add(DatapackReloader.packId(p));
        }
        return out;
    }

    /** Who wished this pack, as a UUID string, or "" when nobody is recorded. */
    public static synchronized String ownerOf(String pack) {
        ensureLoaded();
        return OWNERS.getOrDefault(pack, "");
    }

    /**
     * Remember whose wish compiled this pack (called where every wish compile lands, {@code DatapackForge}),
     * so a quarantine can tell HIM. Nothing is written when no wish is bound (a gate, a replay).
     */
    public static synchronized void rememberOwner(String pack, UUID owner) {
        if (pack == null || owner == null) {
            return;
        }
        ensureLoaded();
        if (!owner.toString().equals(OWNERS.get(pack))) {
            OWNERS.put(pack, owner.toString());
            save();
        }
    }

    // ── doing it ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Pause {@code pack} now. Server thread. A pack already paused is left as it is.
     *
     * @param source {@code meter}, {@code hard-stop}, {@code crash} or {@code op}
     * @return true when this call paused it
     */
    public static boolean quarantine(MinecraftServer server, String pack, String why, String source) {
        Entry entry;
        synchronized (DatapackQuarantine.class) {
            ensureLoaded();
            if (pack == null || pack.isBlank() || PAUSED.containsKey(pack)) {
                return false;
            }
            entry = new Entry(pack, System.currentTimeMillis(), why == null ? "" : why,
                    OWNERS.getOrDefault(pack, ""), source);
            PAUSED.put(pack, entry);
            save();
        }
        DatapackReloader.rememberDisabled(DatapackReloader.packId(pack));
        int cleared = 0;
        if (server != null) {
            try {
                DatapackReloader.disableAndReload(server, pack);
            } catch (RuntimeException e) {
                LOG.warn("[quarantine] could not switch {} off: {}", pack, e.toString());
            }
            cleared = PackCostMeter.discardRecentSpawns(pack);
            tellChild(server, entry.owner(), kidWords(pack, true));
        }
        PackCostMeter.forget(pack);
        LOG.warn("[quarantine] paused wish datapack {} ({}): {} — cleared {} thing(s) it had just spawned; "
                + "/moddyop quarantine restore {} turns it back on", pack, source, why, cleared, pack);
        event(server, "quarantined", entry, cleared, "");
        return true;
    }

    /** Give a paused pack back. Server thread. */
    public static boolean restore(MinecraftServer server, String pack, String by) {
        Entry entry;
        synchronized (DatapackQuarantine.class) {
            ensureLoaded();
            entry = PAUSED.remove(pack);
            if (entry == null) {
                return false;
            }
            save();
        }
        PackCostMeter.forget(pack);
        if (server != null) {
            DatapackReloader.enableAndReload(server, pack);
            tellChild(server, entry.owner(), kidWords(pack, false));
        }
        LOG.info("[quarantine] wish datapack {} restored by {}", pack, by);
        event(server, "restored", entry, 0, by);
        return true;
    }

    /** The meter's breadcrumb while ticks are slow: the pack it would blame now. At most once a second. */
    static void noteSuspect(String pack, long nowMs) {
        synchronized (DatapackQuarantine.class) {
            if (pack == null || nowMs - suspectWrittenMs < SUSPECT_EVERY_MS) {
                return;
            }
            ensureLoaded();
            suspect = pack;
            suspectAtMs = nowMs;
            suspectWrittenMs = nowMs;
            save();
        }
    }

    // ── at boot ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Boot: keep every paused pack off (a pack the world saved as ON before it crashed is switched off here),
     * then handle any watchdog crash this world has not seen. Server thread.
     */
    public static void onServerStarted(MinecraftServer server) {
        ensureLoaded();
        for (Entry e : paused()) {
            DatapackReloader.rememberDisabled(DatapackReloader.packId(e.pack()));
            if (DatapackReloader.isEnabled(server, e.pack())) {
                DatapackReloader.disableAndReload(server, e.pack());
            }
        }
        if (!paused().isEmpty()) {
            LOG.info("[quarantine] {} wish datapack(s) stay paused: {}", paused().size(),
                    paused().stream().map(Entry::pack).toList());
        }
        afterCrash(server, server.getServerDirectory().resolve("crash-reports"), DatapackReloader.worldDatapackDir(server));
    }

    /** The watchdog's own words in a crash report: a tick that never ended. */
    static boolean isWatchdogCrash(String report) {
        if (report == null) {
            return false;
        }
        String r = report.toLowerCase(Locale.ROOT);
        return r.contains("single server tick took") || r.contains("watching server");
    }

    /** Handle each unhandled watchdog crash report in {@code crashDir}. Returns the packs it paused. */
    public static List<String> afterCrash(MinecraftServer server, Path crashDir, Path worldDatapacks) {
        List<String> paused = new ArrayList<>();
        if (crashDir == null || !Files.isDirectory(crashDir)) {
            return paused;
        }
        List<QuarantineRule.Installed> installed = installed(worldDatapacks);
        try (var files = Files.list(crashDir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                long crashMs = modified(f);
                synchronized (DatapackQuarantine.class) {
                    ensureLoaded();
                    if (!name.endsWith(".txt") || HANDLED_CRASHES.contains(name)
                            || crashMs < System.currentTimeMillis() - 24 * 3_600_000L) {
                        continue;
                    }
                }
                String text;
                try {
                    text = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                List<String> suspects;
                synchronized (DatapackQuarantine.class) {
                    HANDLED_CRASHES.add(name);
                    while (HANDLED_CRASHES.size() > 50) {
                        HANDLED_CRASHES.remove(0);
                    }
                    if (!isWatchdogCrash(text)) {
                        save();
                        continue;
                    }
                    suspects = QuarantineRule.crashSuspects(crashMs, suspect, suspectAtMs, installed);
                    save();
                }
                if (suspects.isEmpty()) {
                    LOG.warn("[quarantine] the server froze and was stopped ({}), and no wish datapack points at it",
                            name);
                }
                for (String p : suspects) {
                    if (DatapackStore.exists(p) && quarantine(server, p, "the server froze and was stopped at "
                            + Instant.ofEpochMilli(crashMs) + " (" + name + "), and this pack "
                            + (p.equals(suspect) ? "was the one making the ticks slow" : "was installed just before"),
                            "crash")) {
                        paused.add(p);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("[quarantine] could not read {}: {}", crashDir, e.toString());
        }
        return paused;
    }

    private static List<QuarantineRule.Installed> installed(Path dir) {
        List<QuarantineRule.Installed> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (var zips = Files.list(dir)) {
            for (Path z : zips.filter(p -> p.getFileName().toString().endsWith(".zip")).toList()) {
                String n = z.getFileName().toString();
                out.add(new QuarantineRule.Installed(n.substring(0, n.length() - 4), modified(z)));
            }
        } catch (IOException ignored) {
            // nothing to suspect is the answer for an unreadable folder
        }
        return out;
    }

    // ── telling people ─────────────────────────────────────────────────────────────────────────────

    /** The pack's name as a child would say it: {@code baby_calling_magic} → "baby calling magic". */
    static String spoken(String pack) {
        String s = pack == null ? "" : pack.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        return s.isEmpty() ? "a" : s;
    }

    /** What the child reads. Pure. */
    static String kidWords(String pack, boolean paused) {
        return paused
                ? "Moddy paused the " + spoken(pack) + " magic because it was making the world freeze. "
                        + "Everything else still works, and a grown-up can turn it back on."
                : "The " + spoken(pack) + " magic is back on!";
    }

    private static void tellChild(MinecraftServer server, String owner, String words) {
        if (server == null || owner == null || owner.isBlank()) {
            return;
        }
        try {
            ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(owner));
            if (p != null) {
                p.sendSystemMessage(Component.literal(words).withStyle(ChatFormatting.GOLD));
            }
        } catch (IllegalArgumentException ignored) {
            // an owner that is not a UUID is nobody to tell
        }
    }

    /** Test seam for the event log. */
    public static void eventsTo(Path f) {
        eventsOverride = f;
    }

    private static void event(MinecraftServer server, String what, Entry e, int cleared, String by) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "datapack-quarantine");
        o.addProperty("event", what);
        o.addProperty("at", Instant.now().toString());
        o.addProperty("world", com.moddingfromamod.world.WorldModuleHost.worldId());
        o.addProperty("pack", e.pack());
        o.addProperty("name", spoken(e.pack()));
        o.addProperty("why", e.why());
        o.addProperty("source", e.source());
        o.addProperty("playerId", e.owner());
        if (server != null && !e.owner().isBlank()) {
            try {
                var cache = server.services().nameToIdCache();
                var prof = cache == null ? null
                        : cache.get(UUID.fromString(e.owner())).orElse(null);
                if (prof != null) {
                    o.addProperty("ownerName", prof.name());
                }
            } catch (RuntimeException ignored) {
                // a name is a nicety
            }
        }
        o.addProperty("cleared", cleared);
        if (!by.isEmpty()) {
            o.addProperty("by", by);
        }
        o.addProperty("test", com.moddingfromamod.RunContext.isTestRun());
        Path f = eventsOverride != null ? eventsOverride
                : com.moddingfromamod.runtime.RuntimeConfig.home().resolve(EVENTS);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            LOG.warn("[quarantine] could not write {}: {}", f, ex.toString());
        }
    }

    // ── /moddyop quarantine ────────────────────────────────────────────────────────────────────────

    /** {@code /moddyop quarantine list|restore <pack>}: one line each, for RCON. */
    public static LiteralArgumentBuilder<CommandSourceStack> opBranch() {
        return Commands.literal("quarantine")
                .executes(ctx -> list(ctx.getSource()))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("restore").then(Commands.argument("pack", StringArgumentType.word())
                        .suggests((c, b) -> {
                            paused().forEach(e -> b.suggest(e.pack()));
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            String pack = StringArgumentType.getString(ctx, "pack");
                            boolean ok = restore(ctx.getSource().getServer(), pack, ctx.getSource().getTextName());
                            if (ok) {
                                ctx.getSource().sendSuccess(() -> Component.literal("quarantine: restored " + pack), true);
                            } else {
                                ctx.getSource().sendFailure(Component.literal("quarantine: " + pack + " is not paused"));
                            }
                            return ok ? 1 : 0;
                        })));
    }

    private static int list(CommandSourceStack src) {
        List<Entry> all = paused();
        String line = all.isEmpty() ? "quarantine: nothing paused"
                : "quarantine: " + all.size() + " paused — " + String.join("; ", all.stream()
                        .map(e -> e.pack() + " (" + e.source() + ", " + Instant.ofEpochMilli(e.atMs()) + ")").toList());
        src.sendSuccess(() -> Component.literal(line), false);
        return all.size();
    }

    /** Tests: forget the open world's store so the next read is a fresh boot's. */
    static synchronized void forgetForTest() {
        loadedFrom = null;
        PAUSED.clear();
        OWNERS.clear();
        HANDLED_CRASHES.clear();
        suspect = null;
        suspectAtMs = 0;
        suspectWrittenMs = 0;
        refused = null;
    }

    private static Iterable<JsonElement> arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    @SuppressWarnings("unused")
    private static final String MODID = ModdingFromAModMod.MODID;
}
