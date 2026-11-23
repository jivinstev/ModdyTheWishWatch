package com.moddingfromamod.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The public mod's settings</b>: every setting a player, the server's file or an override can set, with
 * its public default. A key not listed here (the pool's timings, the spawn budget, the watch's switches) is still
 * read through {@link ModdySettings} — an override may set it — but no player can change it and its default is the
 * reading code's own.
 *
 * <p>Pure: no Minecraft. The Magician setup's picks are here too; their choices come from
 * {@link SupportedModels}, the one table of models.
 */
public final class SettingsCatalog {

    private SettingsCatalog() {
    }

    /** Magician mode, the optional AI tier (D5). Off by default: no worker, CLI probe or model call. */
    public static final String MAGICIAN = "magician.enabled";
    /** The wish-granting model. */
    public static final String MODEL = "agent.model";
    /** How hard a worker thinks: fixed at low for players; only an override may set it. */
    public static final String EFFORT = "agent.effort";
    /** The child-safety layer: on, with no off switch anywhere a player or a file reaches (D28). */
    public static final String SAFETY = "safety.mode";
    /** The Magician setup's main model (grants the wish), one of {@link SupportedModels}'s MAIN ids. */
    public static final String MAIN_MODEL = "magician.model";
    /** How the main model is paid for: {@code subscription} or {@code api} (a key from its environment variable). */
    public static final String MAIN_MODE = "magician.mode";
    /** The cheap model (graders, routing, lookups), one of {@link SupportedModels}'s CHEAP ids. */
    public static final String CHEAP_MODEL = "magician.cheapModel";
    /** How the cheap model is paid for. */
    public static final String CHEAP_MODE = "magician.cheapMode";
    /**
     * What runs the wish when the wish model cannot: an ordered list of {@code model:mode} entries, tried in
     * turn when the one before is out of quota, rate-limited, or its sign-in or key fails ({@code agent/ModelFallback}).
     * {@code none} (the default): no fallback.
     */
    public static final String MAIN_FALLBACK = "magician.fallback";
    /** The same for the helper model (graders, routing, lookups). */
    public static final String CHEAP_FALLBACK = "magician.cheapFallback";
    /** The four picks, in the order the setup asks them. Changing one turns Magician off until its check passes. */
    public static final java.util.List<String> PICKS = java.util.List.of(MAIN_MODEL, MAIN_MODE, CHEAP_MODEL, CHEAP_MODE);

    private static final java.util.List<String> MODES =
            java.util.Arrays.stream(SupportedModels.Mode.values()).map(m -> m.id).toList();

    public static final List<Setting> ALL = List.of(
            new Setting(MAGICIAN, "Magician mode", "off", List.of("on", "off"), true),
            new Setting(MODEL, "the model", null, List.of("opus", "default"), true),
            new Setting(EFFORT, "how hard Moddy thinks", null, List.of("low"), false),
            new Setting(SAFETY, "the safety layer", null, List.of("on"), false),
            new Setting(MAIN_MODEL, "the wish model", null, SupportedModels.ids(SupportedModels.Role.MAIN), true),
            new Setting(MAIN_MODE, "how the wish model is paid for", null, MODES, true),
            new Setting(CHEAP_MODEL, "the helper model", null, SupportedModels.ids(SupportedModels.Role.CHEAP), true),
            new Setting(CHEAP_MODE, "how the helper model is paid for", null, MODES, true),
            new Setting(MAIN_FALLBACK, "the wish model's fallbacks", "none", entries(SupportedModels.Role.MAIN), true,
                    true),
            new Setting(CHEAP_FALLBACK, "the helper model's fallbacks", "none", entries(SupportedModels.Role.CHEAP),
                    true, true),
            route(HelperRoute.Job.DESCRIBER, "the picture describer's route"),
            route(HelperRoute.Job.RESEARCHER, "the reference researcher's route"),
            route(HelperRoute.Job.PLAN, "the plan grader's route"),
            route(HelperRoute.Job.WISH_TEXT, "the wish-text grader's route"),
            route(HelperRoute.Job.WORLD_RULES, "the world-rules grader's route"),
            route(HelperRoute.Job.READY_MADE, "the ready-made nudge's route"),
            route(HelperRoute.Job.CHARACTER, "the character lookup's route"));

    /**
     * A helper job's billing route ({@link HelperRoute}): an ordered list of {@code muse-subscription},
     * {@code muse-key}, {@code claude-key}, {@code claude-subscription}; {@code default} (unset) is what the job did
     * before it was configurable, {@code none} no model call at all. Only an override or the server's file sets it: a
     * single-player host's helpers follow their Magician picks, which this can narrow but never widen.
     */
    static Setting route(HelperRoute.Job job, String label) {
        List<String> choices = new java.util.ArrayList<>(List.of("default", "none"));
        choices.addAll(HelperRoute.Step.ids());
        return new Setting(job.key(), label, null, choices, false, true);
    }

    /** {@code none}, then every {@code model:mode} of {@code role}: what a fallback list is made of. */
    static List<String> entries(SupportedModels.Role role) {
        List<String> out = new java.util.ArrayList<>();
        out.add("none");
        for (String id : SupportedModels.ids(role)) {
            for (String mode : MODES) {
                out.add(id + ":" + mode);
            }
        }
        return out;
    }

    private static final Map<String, Setting> BY_KEY = new LinkedHashMap<>();

    static {
        for (Setting s : ALL) {
            if (BY_KEY.put(s.key(), s) != null) {
                throw new IllegalStateException("two settings named " + s.key());
            }
        }
    }

    public static Optional<Setting> find(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    /** The words for a key: its label, or the key itself. */
    public static String label(String key) {
        return find(key).map(Setting::label).orElse(key);
    }
}
