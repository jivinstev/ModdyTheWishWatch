package com.moddingfromamod.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * <b>What a dedicated server's configuration file may turn on</b> (D28, D29): the rules, pure, so each refusal is a
 * unit test. Safe by construction:
 *
 * <ol>
 *   <li>No secrets in the file: it names a provider and a mode; an API key comes only from its environment
 *       variable ({@link #KEY_VARS}).</li>
 *   <li>Magician is off unless the file turns it on.</li>
 *   <li>Only supported models (D20); an unknown or misspelt one keeps Magician off with a line saying which, never
 *       a silent fallback.</li>
 *   <li>The file has no safety switch at all.</li>
 *   <li>Claude's SUBSCRIPTION mode is refused (D29): Claude on a server needs an Anthropic API key. Muse Code's
 *       subscription mode is allowed (the public Muse Code worker).</li>
 *   <li>The start-up check ({@link MagicianCheck}: a wish, every grader, a forced refusal) must pass before Magician
 *       turns on; {@link ServerSettings} runs it once the server has started and turns Magician on only if it
 *       passes.</li>
 * </ol>
 */
public final class ServerConfigRules {

    private ServerConfigRules() {
    }

    public static final List<String> PROVIDERS = List.of("claude", "muse");
    public static final List<String> MODES = List.of("api", "subscription");
    /** D20's main models, by provider: read from the one table ({@link SupportedModels}). */
    public static final Map<String, List<String>> MAIN_MODELS = Map.of(
            "claude", SupportedModels.mainIds(SupportedModels.Provider.ANTHROPIC),
            "muse", SupportedModels.mainIds(SupportedModels.Provider.MUSE));
    /** D20's cheap models (graders, routing, lookups), from either provider. */
    public static final List<String> CHEAP_MODELS = SupportedModels.ids(SupportedModels.Role.CHEAP);
    /** Where each provider's API key comes from: an environment variable, never the file. */
    public static final Map<String, String> KEY_VARS = Map.of("claude", SupportedModels.Provider.ANTHROPIC.keyVar,
            "muse", SupportedModels.Provider.MUSE.keyVar);

    /** What the file decided: the settings it supplies, whether Magician is on, and the lines for the log. */
    public record Result(Map<String, String> values, boolean magician, List<String> log) {

        /** Did the file pass every rule, so that only the start-up check stands between it and Magician on? */
        public boolean readyForCheck() {
            return !magician && values.containsKey(SettingsCatalog.MAIN_MODEL);
        }
    }

    /**
     * @param raw the file's values by key ({@code magician.enabled}, {@code magician.provider}, {@code magician.mode},
     *            {@code magician.model}, {@code magician.cheapModel}, and optionally {@code magician.fallback} and
     *            {@code magician.cheapFallback})
     * @param env an environment lookup (null for a variable that is not set)
     * @param startupCheckPassed whether the start-up check passed
     */
    public static Result apply(Map<String, String> raw, Function<String, String> env, boolean startupCheckPassed) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> log = new ArrayList<>();
        values.put(SettingsCatalog.MAGICIAN, "off");
        if (!"true".equals(norm(raw.get("magician.enabled")))) {
            log.add("Magician is off: the server's configuration file does not turn it on.");
            return new Result(values, false, log);
        }
        String provider = norm(raw.get("magician.provider"));
        String mode = norm(raw.get("magician.mode"));
        String model = norm(raw.get("magician.model"));
        String cheap = norm(raw.get("magician.cheapModel"));
        String refused = null;
        if (!PROVIDERS.contains(provider)) {
            refused = "magician.provider \"" + provider + "\" is not one of " + PROVIDERS;
        } else if (!MODES.contains(mode)) {
            refused = "magician.mode \"" + mode + "\" is not one of " + MODES;
        } else if ("claude".equals(provider) && "subscription".equals(mode)) {
            refused = "Claude subscription mode is not allowed on a dedicated server: set magician.mode = \"api\" and "
                    + KEY_VARS.get("claude") + " in the server's environment";
        } else if (!MAIN_MODELS.get(provider).contains(model)) {
            refused = "magician.model \"" + model + "\" is not a supported " + provider + " model "
                    + MAIN_MODELS.get(provider);
        } else if (!CHEAP_MODELS.contains(cheap)) {
            refused = "magician.cheapModel \"" + cheap + "\" is not a supported cheap model " + CHEAP_MODELS;
        } else if ("api".equals(mode) && blank(env.apply(KEY_VARS.get(provider)))) {
            refused = "API-key mode needs " + KEY_VARS.get(provider) + " in the server's environment (never in the file)";
        } else if (SupportedModels.HAIKU.equals(cheap) && blank(env.apply(KEY_VARS.get("claude")))) {
            // D29: no Claude subscription on a server, so Haiku there always needs the Anthropic key.
            refused = "the cheap model haiku-5.5 needs " + KEY_VARS.get("claude") + " in the server's environment";
        }
        // the fallback chains. Every entry a supported model with a way to pay that works on a server; a
        // misspelt one keeps Magician off with its line, never a silent shorter chain.
        String fallback = norm(raw.get(SettingsCatalog.MAIN_FALLBACK));
        String cheapFallback = norm(raw.get(SettingsCatalog.CHEAP_FALLBACK));
        if (refused == null) {
            refused = fallbackRefusal(SettingsCatalog.MAIN_FALLBACK, fallback, SupportedModels.Role.MAIN, env);
        }
        if (refused == null) {
            refused = fallbackRefusal(SettingsCatalog.CHEAP_FALLBACK, cheapFallback, SupportedModels.Role.CHEAP, env);
        }
        // The helper routes (HelperRoute): a misspelt step keeps Magician off with its line, never a half-read route.
        for (HelperRoute.Job job : HelperRoute.Job.values()) {
            if (refused == null) {
                refused = HelperRoute.invalid(raw.get(job.key())).map(why -> job.key() + ": " + why).orElse(null);
            }
        }
        if (refused != null) {
            log.add("Magician stays OFF: " + refused + ".");
            return new Result(values, false, log);
        }
        values.put("magician.provider", provider);
        values.put("magician.mode", mode);
        values.put("magician.model", model);
        values.put("magician.cheapModel", cheap);
        values.put(SettingsCatalog.MAIN_FALLBACK, com.moddingfromamod.agent.ModelFallback.format(
                com.moddingfromamod.agent.ModelFallback.parse(fallback, SupportedModels.Role.MAIN).entries()));
        values.put(SettingsCatalog.CHEAP_FALLBACK, com.moddingfromamod.agent.ModelFallback.format(
                com.moddingfromamod.agent.ModelFallback.parse(cheapFallback, SupportedModels.Role.CHEAP).entries()));
        for (HelperRoute.Job job : HelperRoute.Job.values()) {
            String r = norm(raw.get(job.key()));
            if (!r.isEmpty() && !"default".equals(r)) {
                values.put(job.key(), r.replace(" ", ""));
            }
        }
        // Haiku on a server is always the API key (D29); Ember follows the file's mode.
        values.put(SettingsCatalog.CHEAP_MODE, SupportedModels.HAIKU.equals(cheap) ? "api" : mode);
        if (!startupCheckPassed) {
            log.add("Magician stays OFF until the start-up check (a wish, every grader, a forced refusal) passes.");
            return new Result(values, false, log);
        }
        values.put(SettingsCatalog.MAGICIAN, "on");
        log.add("Magician is ON from the server's configuration file: " + provider + " " + mode + ", " + model
                + " with " + cheap + ".");
        return new Result(values, true, log);
    }

    /** Why a fallback list may not run on a server, or null when it may. */
    static String fallbackRefusal(String key, String csv, SupportedModels.Role role, Function<String, String> env) {
        var parsed = com.moddingfromamod.agent.ModelFallback.parse(csv, role);
        if (!parsed.problems().isEmpty()) {
            return key + ": " + parsed.problems().get(0);
        }
        for (var e : parsed.entries()) {
            var server = SupportedModels.refusedOnServer(e.model(), e.mode());
            if (server.isPresent()) {
                return key + " \"" + e.key() + "\": " + server.get();
            }
            if (e.paid() && blank(env.apply(e.model().provider().keyVar))) {
                return key + " \"" + e.key() + "\" needs " + e.model().provider().keyVar
                        + " in the server's environment (never in the file)";
            }
        }
        return null;
    }

    private static String norm(String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
