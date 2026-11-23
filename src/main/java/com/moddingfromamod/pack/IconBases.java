package com.moddingfromamod.pack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <b>Which vanilla item draws each icon-library sprite.</b>
 *
 * <p>A vanilla item reads better than our own sprite wherever one fits — a
 * wished sword is drawn as an iron sword, a wand as a blaze rod — and the wish stays special by
 * what is composed AROUND it (a sparkle backing in his colour, an overlay for what it does), never
 * by being a vanilla item alone. The map is data ({@code items/icon-bases.json}) because the jar's
 * copy of the definitions is written by {@code tools/gen-carrier-model.py}, which reads the same
 * file: one list, two writers, and {@code NativeItemModelsTest} holds them together.
 *
 * <p>A vanilla item cannot be dyed, so his colour moves to the backing glow, which is tinted by the
 * stack's {@code dyed_color} — the component every wished thing has always carried.
 */
public final class IconBases {

    private static volatile Map<String, String> bases;

    private IconBases() {
    }

    /** The vanilla model id that draws this icon, or null to keep the icon's own dyed sprite. */
    public static String forIcon(String iconName) {
        return all().get(iconName);
    }

    /** Every mapping, in file order. */
    public static Map<String, String> all() {
        Map<String, String> b = bases;
        if (b == null) {
            b = load();
            bases = b;
        }
        return b;
    }

    private static Map<String, String> load() {
        try (InputStream in = IconBases.class.getResourceAsStream("/items/icon-bases.json")) {
            if (in == null) {
                return Map.of();
            }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("bases");
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : o.entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
            return Collections.unmodifiableMap(out);
        } catch (Exception e) {
            throw new IllegalStateException("items/icon-bases.json is unreadable", e);
        }
    }
}
