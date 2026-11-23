package com.moddingfromamod.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * <b>The dedicated server's configuration file</b> (D28): {@code config/moddingfromamod-server.toml}, NeoForge's own
 * config, written by the operator and read as the server starts. Registered on a dedicated server only, so a
 * single-player game never grows one. It holds no secrets and no safety switch ({@link ServerConfigRules}).
 *
 * <p><b>A documented extension point</b>: the server operator's own configuration.
 */
public final class ServerConfigFile {

    public static final String FILE = "moddingfromamod-server.toml";

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    static {
        B.comment("Moddy the Wish Watch, on a dedicated server. Nobody changes these in game; edit this file and",
                "restart. API keys are NEVER written here: Claude reads ANTHROPIC_API_KEY and Muse reads MUSE_API_KEY",
                "from the server's environment. The child-safety layer is always on and has no switch.").push("magician");
    }

    private static final ModConfigSpec.BooleanValue ENABLED = B
            .comment("Turn on Magician mode, the optional AI tier. Off: the Apprentice and the Builder only, and no",
                    "AI program is ever started.")
            .define("enabled", false);
    private static final ModConfigSpec.ConfigValue<String> PROVIDER = B
            .comment("Who grants wishes: \"claude\" (Claude Code) or \"muse\" (Muse Code).")
            .define("provider", "claude");
    private static final ModConfigSpec.ConfigValue<String> MODE = B
            .comment("\"api\" (a key from the environment) or \"subscription\". Claude's subscription is not allowed on",
                    "a dedicated server; Muse Code's is (the server's own `muse login`). Magician turns on only after the",
                    "start-up check (one small wish, every safety grader, a forced refusal) passes.")
            .define("mode", "api");
    private static final ModConfigSpec.ConfigValue<String> MODEL = B
            .comment("The main model: opus-5.5 (claude); muse-spark-1.2, muse-spark-1.2-contributor or muse-wonder-1.0",
                    "(muse). Anything else keeps Magician off.")
            .define("model", "opus-5.5");
    private static final ModConfigSpec.ConfigValue<String> CHEAP = B
            .comment("The cheap model for the graders and lookups: haiku-5.5 or muse-ember-1.0.")
            .define("cheapModel", "haiku-5.5");
    private static final ModConfigSpec.ConfigValue<String> FALLBACK = B
            .comment("What grants wishes when the main model is out of quota, rate-limited or its sign-in or key fails:",
                    "an ordered, comma-separated list of model:mode, tried in turn (\"none\": no fallback). For example",
                    "\"muse-spark-1.2-contributor:api,opus-5.5:api\". Each paid entry is charged to its key only when",
                    "Moddy falls back to it; a misspelt entry keeps Magician off.")
            .define("fallback", "none");
    private static final ModConfigSpec.ConfigValue<String> CHEAP_FALLBACK = B
            .comment("The same for the cheap model, e.g. \"muse-ember-1.0:api\" behind Ember on the subscription.")
            .define("cheapFallback", "none");

    /** Each helper job's billing route ({@link HelperRoute}), in {@link HelperRoute.Job} order. */
    private static final Map<HelperRoute.Job, ModConfigSpec.ConfigValue<String>> ROUTES =
            new java.util.EnumMap<>(HelperRoute.Job.class);

    static {
        B.pop();
        B.comment("Which provider each HELPER job (not the wish itself) may call, and how it is paid: an ordered,",
                "comma-separated list of muse-subscription, muse-key, claude-key, claude-subscription, tried in turn",
                "until one answers. \"default\": what the job does with the picks above (the cheap model and its",
                "fallbacks, and nothing else); a value here may narrow or reorder those, never add a provider.",
                "\"none\": no model call for that job.").push("helpers");
        for (HelperRoute.Job job : HelperRoute.Job.values()) {
            ROUTES.put(job, B.define(job.id, "default"));
        }
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private ServerConfigFile() {
    }

    /** The file's values by key, or the defaults when it is not loaded (Magician off). */
    static Map<String, String> raw() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!SPEC.isLoaded()) {
            m.put("magician.enabled", "false");
            return m;
        }
        m.put("magician.enabled", String.valueOf(ENABLED.get()));
        m.put("magician.provider", PROVIDER.get());
        m.put("magician.mode", MODE.get());
        m.put("magician.model", MODEL.get());
        m.put("magician.cheapModel", CHEAP.get());
        m.put(SettingsCatalog.MAIN_FALLBACK, FALLBACK.get());
        m.put(SettingsCatalog.CHEAP_FALLBACK, CHEAP_FALLBACK.get());
        m.put("helper.route.describer", route(HelperRoute.Job.DESCRIBER));
        m.put("helper.route.researcher", route(HelperRoute.Job.RESEARCHER));
        m.put("helper.route.plan", route(HelperRoute.Job.PLAN));
        m.put("helper.route.wishText", route(HelperRoute.Job.WISH_TEXT));
        m.put("helper.route.worldRules", route(HelperRoute.Job.WORLD_RULES));
        m.put("helper.route.readyMade", route(HelperRoute.Job.READY_MADE));
        m.put("helper.route.character", route(HelperRoute.Job.CHARACTER));
        return m;
    }

    private static String route(HelperRoute.Job job) {
        return ROUTES.get(job).get();
    }
}
