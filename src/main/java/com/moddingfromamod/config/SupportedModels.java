package com.moddingfromamod.config;

import java.util.List;
import java.util.Optional;

/**
 * <b>The models Magician mode may use: the ONE table</b> (D20). The setup menu, the dedicated server's file
 * ({@link ServerConfigRules}) and the code that launches a worker all read it, and {@code SupportedModelsTest} pins it,
 * so nothing can offer a model that is not here. A model is added only once it is measured in that role; never
 * Fable 5.1.
 *
 * <ul>
 *   <li><b>Cheap</b> (graders, routing, lookups): Haiku 5.5 or Ember 1.0.</li>
 *   <li><b>Main</b> (grants the wish, effort fixed at low): Opus 5.5, Muse Spark 1.2 (Contributor optional) or
 *       Muse Wonder 1.0.</li>
 * </ul>
 *
 * <p><b>Two ways to pay for each</b>: the provider's {@link Mode#SUBSCRIPTION} (the program's own sign-in), or
 * {@link Mode#API} with a key read ONLY from the provider's environment variable ({@link Provider#keyVar}), never
 * stored anywhere. Which program runs a pick is {@link #harness}.
 *
 * <p>Pure: no Minecraft.
 */
public final class SupportedModels {

    private SupportedModels() {
    }

    public enum Role { CHEAP, MAIN }

    public enum Mode {
        SUBSCRIPTION("subscription", "Subscription"),
        API("api", "API key");

        public final String id;
        public final String label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Optional<Mode> of(String id) {
            for (Mode m : values()) {
                if (m.id.equals(id)) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }
    }

    /** Who serves the model, and where its API key comes from (an environment variable, never a file or a menu). */
    public enum Provider {
        ANTHROPIC("Anthropic", "ANTHROPIC_API_KEY"),
        MUSE("Muse", "MUSE_API_KEY");

        public final String label;
        public final String keyVar;

        Provider(String label, String keyVar) {
            this.label = label;
            this.keyVar = keyVar;
        }
    }

    /** The program that runs a model on this machine. */
    public enum Harness {
        CLAUDE_CODE("Claude Code", "claude"),
        MUSE_CODE("Muse Code", "muse");

        public final String label;
        public final String command;

        Harness(String label, String command) {
            this.label = label;
            this.command = command;
        }
    }

    /**
     * One supported model.
     *
     * @param id         the stored value ({@code magician.model} / {@code magician.cheapModel}, and the server file's)
     * @param label      the words a player reads
     * @param workerSpec what the worker or grader is launched with ({@code agent/WishModel}'s {@code provider:id}
     *                   form, or a Claude id)
     * @param apiPrice   the list price shown beside API-key mode (D27: Wonder through a player's own key is offered
     *                   with its price shown; prices as tools/wish-cost.py's RATES)
     */
    public record Model(String id, String label, Role role, Provider provider, String workerSpec, String apiPrice) {
    }

    public static final String HAIKU = "haiku-5.5";
    public static final String EMBER = "muse-ember-1.0";
    public static final String OPUS = "opus-5.5";
    public static final String SPARK = "muse-spark-1.2";
    public static final String SPARK_CONTRIBUTOR = "muse-spark-1.2-contributor";
    public static final String WONDER = "muse-wonder-1.0";

    /** THE table. {@code SupportedModelsTest} pins every row. */
    public static final List<Model> ALL = List.of(
            new Model(HAIKU, "Haiku 5.5", Role.CHEAP, Provider.ANTHROPIC, "claude-haiku-5-5",
                    "$0.10 in / $0.50 out per million tokens"),
            new Model(EMBER, "Ember 1.0", Role.CHEAP, Provider.MUSE, "muse:muse-ember-1.0",
                    "$0.10 in / $0.50 out per million tokens"),
            new Model(OPUS, "Opus 5.5 (low)", Role.MAIN, Provider.ANTHROPIC, "opus",
                    "$4 in / $20 out per million tokens"),
            new Model(SPARK, "Muse Spark 1.2", Role.MAIN, Provider.MUSE, "muse:muse-spark-1.2",
                    "$1.25 in / $4.25 out per million tokens"),
            new Model(SPARK_CONTRIBUTOR, "Muse Spark 1.2 Contributor", Role.MAIN, Provider.MUSE,
                    "muse:muse-spark-1.2-contributor", "$0.10 in / $0.20 out per million tokens"),
            new Model(WONDER, "Muse Wonder 1.0", Role.MAIN, Provider.MUSE, "muse:muse-wonder-1.0",
                    "$4 in / $20 out per million tokens, list only (no Contributor tier)"));

    public static Optional<Model> find(String id) {
        for (Model m : ALL) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public static List<Model> of(Role role) {
        return ALL.stream().filter(m -> m.role() == role).toList();
    }

    public static List<String> ids(Role role) {
        return of(role).stream().map(Model::id).toList();
    }

    /** The main model ids a provider serves (the server file's {@code magician.model} for that provider). */
    public static List<String> mainIds(Provider p) {
        return of(Role.MAIN).stream().filter(m -> m.provider() == p).map(Model::id).toList();
    }

    /**
     * Which program runs {@code m} paid for by {@code mode}: Claude Code runs every Anthropic model, Muse Code every
     * Muse model, on its sign-in or on {@code MUSE_API_KEY} ({@code agent/MuseWorker} for the wish model).
     */
    public static Harness harness(Model m, Mode mode) {
        return m.provider() == Provider.ANTHROPIC ? Harness.CLAUDE_CODE : Harness.MUSE_CODE;
    }

    /** D29: on a dedicated server, Claude's subscription is refused (an Anthropic model needs an API key there). */
    public static Optional<String> refusedOnServer(Model m, Mode mode) {
        if (m.provider() == Provider.ANTHROPIC && mode == Mode.SUBSCRIPTION) {
            return Optional.of("A server uses an Anthropic API key (" + Provider.ANTHROPIC.keyVar
                    + "), not a Claude subscription.");
        }
        return Optional.empty();
    }
}
