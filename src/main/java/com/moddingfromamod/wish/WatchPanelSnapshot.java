package com.moddingfromamod.wish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.runtime.RuntimeConfig;

/**
 * <b>The watch a new child sees, written down for an operator's view</b>.
 *
 * <p>An operator's "On the watch" grid is a VIEW, and the only honest thing for it to view is what the
 * server actually lays out: every page, every cell, its slot, name, icon, hover text and recipe id.
 * So the server writes {@code ~/.moddingfromamod/watch-panel.json} each time its library changes (at
 * boot and on every reload, which is also when a promote, a demote or a reorder lands), and an
 * operator's tool can draw that. The
 * layout comes from {@link WatchMenu#presetRows} and {@link WatchPanel#lay}, the code a child's
 * own panel is built by, so that view cannot show a watch no child sees.
 *
 * <p>"A new child": no taught wishes of his own and nothing made yet, which is the panel every child
 * starts from and the part the server's operator controls. A child's own wishes and creations follow it on his
 * real panel.
 */
public final class WatchPanelSnapshot {

    /** The snapshot's schema; the tower refuses a newer one by name rather than drawing it wrong. */
    public static final int SCHEMA = 1;

    private WatchPanelSnapshot() {
    }

    public static Path file() {
        return RuntimeConfig.home().resolve("watch-panel.json");
    }

    /** Every page of a panel laid out from these preset rows, as the JSON an operator's dashboard reads. Pure. */
    public static JsonObject of(List<WatchPanel.PresetRow> rows, long atMs) {
        List<WatchPanel.Layout> pages = new ArrayList<>();
        WatchPanel.Layout first = WatchPanel.lay(rows, List.of(), List.of(), 0);
        pages.add(first);
        for (int p = 1; p < first.pageCount(); p++) {
            pages.add(WatchPanel.lay(rows, List.of(), List.of(), p));
        }
        JsonObject root = new JsonObject();
        root.addProperty("store", "watch-panel");
        root.addProperty("v", SCHEMA);
        root.addProperty("atMs", atMs);
        root.addProperty("columns", WatchPanel.COLS);
        root.addProperty("buttons", rows.size());
        JsonArray arr = new JsonArray();
        for (WatchPanel.Layout l : pages) {
            JsonObject page = new JsonObject();
            page.addProperty("page", l.page());
            JsonArray cells = new JsonArray();
            for (WatchPanel.Cell c : l.cells()) {
                JsonObject o = new JsonObject();
                o.addProperty("slot", c.slot());
                o.addProperty("family", c.family().name());
                o.addProperty("action", c.action().name());
                o.addProperty("label", c.label());
                o.addProperty("icon", c.icon());
                if (!c.recipeId().isBlank()) {
                    o.addProperty("recipeId", c.recipeId());
                }
                JsonArray lore = new JsonArray();
                c.lore().forEach(lore::add);
                o.add("lore", lore);
                cells.add(o);
            }
            page.add("cells", cells);
            arr.add(page);
        }
        root.add("pages", arr);
        return root;
    }

    /** The per-player view's schema (2026-10-12); the account page refuses a newer one by name. */
    public static final int OWN_SCHEMA = 1;

    /** Where {@code /moddyop watchview} leaves one player's view for the catalog exporter. */
    public static Path ownFile(String viewerId) {
        return RuntimeConfig.home().resolve("watch-views").resolve(viewerId + ".json");
    }

    /**
     * <b>HIS watch, as the account page draws it</b> (2026-10-12) — every tile of his own, in the
     * watch's own order, each with the name, the card lines and the icon the watch gives it
     * ({@link WatchMenu#hisOwnFor}, {@link WatchMenu#face}). Server thread.
     */
    public static JsonObject hisOwn(String viewerId, long atMs) {
        List<WatchPanel.Cell> cells = new ArrayList<>();
        WatchPanel.Layout first = WatchMenu.hisOwnFor(viewerId, 0);
        for (int p = 0; p < first.pageCount(); p++) {
            cells.addAll((p == 0 ? first : WatchMenu.hisOwnFor(viewerId, p)).cells());
        }
        return own(viewerId, cells, atMs, icon -> WatchMenu.faceJson(WatchMenu.face(icon)));
    }

    /**
     * The per-player view as JSON. Pure given {@code face}: only his tiles (a creation or a button
     * — never a page control), in order, each {@code {family, name, id, recipeId, lore, face}}.
     * {@code corpus/watch-view-golden.json} is this shape, read by the account page's own test too.
     */
    public static JsonObject own(String viewerId, List<WatchPanel.Cell> cells, long atMs,
                                 java.util.function.Function<String, JsonObject> face) {
        JsonObject root = new JsonObject();
        root.addProperty("store", "watch-own");
        root.addProperty("v", OWN_SCHEMA);
        root.addProperty("player", viewerId);
        root.addProperty("atMs", atMs);
        JsonArray arr = new JsonArray();
        for (WatchPanel.Cell c : cells) {
            if (c.family() != WatchPanel.Family.CREATION && c.family() != WatchPanel.Family.PRESET) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("family", c.family().name());
            o.addProperty("name", c.label());
            if (c.family() == WatchPanel.Family.CREATION) {
                o.addProperty("id", c.arg());
            }
            if (!c.recipeId().isBlank()) {
                o.addProperty("recipeId", c.recipeId());
            }
            JsonArray lore = new JsonArray();
            c.lore().forEach(lore::add);
            o.add("lore", lore);
            o.add("face", face.apply(c.icon()));
            arr.add(o);
        }
        root.add("cells", arr);
        return root;
    }

    /** Write one player's view where the exporter reads it. Never throws. */
    public static int publishOwn(String viewerId) {
        JsonObject view = hisOwn(viewerId, System.currentTimeMillis());
        write(ownFile(viewerId), view);
        return view.getAsJsonArray("cells").size();
    }

    /**
     * Write it, temp-then-move, the way {@code health.json} is written. Never throws: a snapshot
     * that cannot be written is a stale operator's view, not a reason to fail a library reload. Skipped
     * on a test run, so a gate on a live server never replaces the real watch with a test library's.
     */
    public static void publish() {
        if (com.moddingfromamod.RunContext.isTestRun()) {
            return;
        }
        try {
            write(file(), of(WatchMenu.presetRows("", List.of(), java.util.Set.of()),
                    System.currentTimeMillis()));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(WatchPanelSnapshot.class)
                    .warn("could not lay out the watch snapshot: {}", e.toString());
        }
    }

    static void write(Path file, JsonObject snap) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, snap + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(WatchPanelSnapshot.class)
                    .warn("could not write {}: {}", file, e.toString());
        }
    }
}
