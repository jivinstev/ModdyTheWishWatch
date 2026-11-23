package com.moddingfromamod.wishbuilder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.world.WorldFacts;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>A world's Wish Builder</b> (docs/WISH_BUILDER_PLAN.md §3): the manifest's
 * {@code crafters.builder} block, read from the bundled {@code /worlds/<id>.json}.
 *
 * <pre>
 *   "crafters": { …, "builder": {
 *       "enabled": true,
 *       "apprenticeLayer": true,          (the Apprentice's trained layer; absent = on)
 *       "ruleScope": "own_island" | "world",
 *       "kinds": ["creature", …], "temperaments": ["friendly", …], "maxCount": 3,
 *       "supplies": {"categories": ["wood", …], "most": 64},   (absent = every category, 64)
 *       "ids": [ …reviewed catalogue ids; absent = BuilderAllowlist.REVIEWED… ] } }
 * </pre>
 *
 * <p><b>{@code ruleScope: own_island} takes RULES off the menu</b>, in code rather than by
 * remembering to leave them out: {@code set_rule} has no way to scope a rule to one island, so a rule
 * made there would change every child's island. Absent = the Builder is ON with the reviewed menu ({@link #DEFAULT}); {@code "enabled": false} is the only way to turn it off; a manifest that does not parse = OFF.
 * {@code -Dmoddingfromamod.builder.enabled=true|false} overrides {@code enabled} (a GameTest, a client
 * test). Pure apart from {@link #current}; {@code BuilderSettingsTest} is the Gate A surface.
 */
public record BuilderSettings(boolean enabled, BuilderAllowlist allow, String ruleScope, boolean apprenticeLayer) {

    /** The Apprentice's trained layer is on unless a world says otherwise. */
    public BuilderSettings(boolean enabled, BuilderAllowlist allow, String ruleScope) {
        this(enabled, allow, ruleScope, true);
    }

    public static final BuilderSettings OFF = new BuilderSettings(false, BuilderAllowlist.REVIEWED, "world");
    /**
     * A world that says nothing about its Builder (no manifest, or no {@code crafters.builder} block): ON, with the
     * reviewed menu and world-wide rules: the Builder is always on, not just part
     * of a custom build. Singleplayer, a dev run and a local install get it; only {@code "enabled": false} turns a
     * world's Builder off.
     */
    public static final BuilderSettings DEFAULT = new BuilderSettings(true, BuilderAllowlist.REVIEWED, "world");

    public static final String ENABLED_PROPERTY = "moddingfromamod.builder.enabled";
    /** {@code true|false} overrides the manifest's {@code apprenticeLayer} (a GameTest, an A/B). */
    public static final String LAYER_PROPERTY = "moddingfromamod.builder.apprenticeLayer";

    private static final Map<String, BuilderSettings> CACHE = new ConcurrentHashMap<>();

    /** This world's settings, the property winning for {@code enabled}. */
    public static BuilderSettings current() {
        String id = WorldFacts.manifestId();
        BuilderSettings base = id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}") ? DEFAULT
                : CACHE.computeIfAbsent(id, BuilderSettings::load);
        String o = System.getProperty(ENABLED_PROPERTY);
        boolean enabled = "true".equals(o) || (!"false".equals(o) && base.enabled());
        String l = System.getProperty(LAYER_PROPERTY);
        boolean layer = "true".equals(l) || (!"false".equals(l) && base.apprenticeLayer());
        return enabled == base.enabled() && layer == base.apprenticeLayer() ? base
                : new BuilderSettings(enabled, base.allow(), base.ruleScope(), layer);
    }

    /**
     * This world's settings as one player meets them: {@link #current()}, with the Builder on for a player a test
     * has marked ({@code seam.PlayerSeams}). Every real player gets {@link #current()} unchanged.
     */
    public static BuilderSettings forPlayer(java.util.UUID player) {
        BuilderSettings s = current();
        return s.enabled() || !com.moddingfromamod.seam.PlayerSeams.hasBuilderOn(player) ? s
                : new BuilderSettings(true, s.allow(), s.ruleScope(), s.apprenticeLayer());
    }

    private static BuilderSettings load(String id) {
        try {
            String text = WorldFacts.manifestText(id);   //: the core's jar or a level jar's
            return text == null ? DEFAULT : parse(text);
        } catch (RuntimeException e) {
            return OFF;   // a manifest that does not parse is a broken file, not a world that said nothing
        }
    }

    /** A manifest's text → its Builder. Throws on a block that names something no tool takes. */
    public static BuilderSettings parse(String json) {
        JsonElement el = JsonParser.parseString(json == null ? "{}" : json);
        if (!el.isJsonObject() || !(el.getAsJsonObject().get("crafters") instanceof JsonObject c)
                || !(c.get("builder") instanceof JsonObject b)) {
            return DEFAULT;
        }
        boolean enabled = !b.has("enabled") || !b.get("enabled").isJsonPrimitive() || b.get("enabled").getAsBoolean();
        String scope = b.has("ruleScope") ? b.get("ruleScope").getAsString() : "world";
        if (!scope.equals("world") && !scope.equals("own_island")) {
            throw new IllegalArgumentException("crafters.builder.ruleScope is world or own_island, not '" + scope + "'");
        }
        BuilderAllowlist allow = BuilderAllowlist.fromJson(b, BuilderAllowlist.REVIEWED);
        if (scope.equals("own_island") && allow.kinds().contains(BuilderSpec.Kind.RULE)) {
            Set<BuilderSpec.Kind> kinds = new LinkedHashSet<>(allow.kinds());
            kinds.remove(BuilderSpec.Kind.RULE);
            allow = new BuilderAllowlist(kinds, allow.ids(), allow.tempers(), allow.maxCount(), allow.itemTier(), allow.supplies());
        }
        boolean layer = !b.has("apprenticeLayer") || !b.get("apprenticeLayer").isJsonPrimitive() || b.get("apprenticeLayer").getAsBoolean();
        return new BuilderSettings(enabled, allow, scope, layer);
    }
}
