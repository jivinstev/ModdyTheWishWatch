package com.moddingfromamod.reference;

import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Reads the researcher's {@code --output-format stream-json} as it arrives.</b>
 *
 * <p>Two things a plain {@code -p} reply could not give, both measured on a real Plumo lookup
 * (17.9 s end to end):
 * <ul>
 *   <li><b>The pages it opens, as it opens them.</b> It fetched the wiki page it went on to cite at
 *       3.5 s. Each {@code WebFetch} URL is handed to {@code onPageOpened} so its artwork can be
 *       looked up and downloaded while the researcher is still writing.</li>
 *   <li><b>The answer the moment it is written.</b> The JSON reply was complete at 15.0 s; the
 *       process then spent 2.9 s on a post-turn summary nobody reads before exiting.</li>
 * </ul>
 *
 * <p>Pure: lines in, decisions out, no process and no network, so every rule here is tested on
 * recorded lines. A line that is not stream JSON (an older CLI that ignores the flag, a stray
 * warning) is kept verbatim, and {@link #answer()} falls back to it, so the old plain-text reply
 * still parses exactly as it did.
 */
final class ResearcherStream {

    /** Only the first few pages are started early: the cited ones come first, and each costs a fetch. */
    static final int MAX_EARLY_PAGES = 3;

    private final Consumer<String> onPageOpened;
    private final StringBuilder raw = new StringBuilder();
    private final java.util.Set<String> opened = new java.util.LinkedHashSet<>();
    private String answer;
    private boolean early;

    ResearcherStream(Consumer<String> onPageOpened) {
        this.onPageOpened = onPageOpened == null ? u -> { } : onPageOpened;
    }

    /**
     * One line of output. Returns true once the answer is in hand and nothing further is needed.
     * Never throws.
     */
    boolean accept(String line) {
        if (line == null || answer != null) {
            return answer != null;
        }
        JsonObject o = null;
        try {
            JsonElement e = JsonParser.parseString(line);
            o = e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            // fall through: kept verbatim
        }
        if (o == null || !o.has("type")) {
            raw.append(line).append('\n');
            return false;
        }
        String type = str(o, "type");
        if ("assistant".equals(type)) {
            JsonArray content = content(o);
            for (JsonElement c : content) {
                if (!c.isJsonObject()) {
                    continue;
                }
                JsonObject item = c.getAsJsonObject();
                String kind = str(item, "type");
                if ("tool_use".equals(kind) && "WebFetch".equals(str(item, "name"))) {
                    page(item);
                } else if ("text".equals(kind)) {
                    String text = str(item, "text");
                    // The final reply is the one that IS a reference. stop_reason is null on every
                    // streamed message (measured), so the shape of the text is the only signal --
                    // and a thought-aloud sentence never parses as one.
                    if (!text.isBlank() && !ReferenceSpec.parse(text).isEmpty()) {
                        answer = text;
                        early = true;
                        return true;
                    }
                }
            }
        } else if ("result".equals(type)) {
            String result = str(o, "result");
            if (!result.isBlank()) {
                answer = result;
                return true;
            }
        }
        return false;
    }

    private void page(JsonObject toolUse) {
        if (opened.size() >= MAX_EARLY_PAGES || !toolUse.has("input")
                || !toolUse.get("input").isJsonObject()) {
            return;
        }
        String url = str(toolUse.getAsJsonObject("input"), "url").trim();
        // ReferenceSpec's shape rule for a source: a URL and nothing else. What it points at is
        // then guarded exactly as a cited source is (ReferenceImages.allowed, in forPage).
        if (!url.startsWith("https://") || url.length() > 300 || url.contains(" ")
                || !opened.add(url)) {
            return;
        }
        try {
            onPageOpened.accept(url);
        } catch (RuntimeException ignored) {
            // an early start is an optimisation; it must never break the lookup
        }
    }

    /** The reply text: the streamed answer, or everything that was not stream JSON. */
    String answer() {
        return answer != null ? answer : raw.toString();
    }

    /** True when the answer came from the message itself, before the process said it was done. */
    boolean answeredEarly() {
        return early;
    }

    /** The pages handed on, in the order the researcher opened them. */
    java.util.List<String> opened() {
        return java.util.List.copyOf(opened);
    }

    private static JsonArray content(JsonObject event) {
        try {
            JsonObject m = event.getAsJsonObject("message");
            JsonElement c = m == null ? null : m.get("content");
            return c != null && c.isJsonArray() ? c.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException e) {
            return new JsonArray();
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
