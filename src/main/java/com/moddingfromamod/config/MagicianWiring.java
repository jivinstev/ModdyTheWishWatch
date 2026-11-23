package com.moddingfromamod.config;

import java.util.Map;
import java.util.Optional;

/**
 * <b>Where the Magician setup's picks reach the programs</b>: the wish worker's model and environment, and
 * the cheap-model jobs' model and key. The picks apply only where nobody else chose: an override that locks the
 * model (a plug-in that locks {@code agent.model} and {@code magician.*}) keeps its own path untouched.
 *
 * <p>Keys: an API key is NEVER stored. In API-key mode the program reads it from its own environment variable; in
 * subscription mode that variable is removed from the program's environment, so a key that happens to be set cannot
 * quietly bill a player who chose their subscription.
 */
public final class MagicianWiring {

    private MagicianWiring() {
    }

    /** The picks, when they are the ones in charge: complete, and not overridden. */
    static Optional<MagicianPicks> active(ModdySettings s) {
        if (s.locked(SettingsCatalog.MODEL) || s.locked(SettingsCatalog.MAIN_MODEL)) {
            return Optional.empty();
        }
        MagicianPicks p = MagicianPicks.read(s);
        return p.complete() ? Optional.of(p) : Optional.empty();
    }

    public static Optional<MagicianPicks> active() {
        return active(ModdySettings.shared());
    }

    /** The wish worker's model spec ({@code agent/WishModel} form), when the picks choose it. */
    public static Optional<String> mainSpec() {
        return active().flatMap(MagicianPicks::main).map(SupportedModels.Model::workerSpec);
    }

    /**
     * How the wish worker is paid for when the picks run it on <b>Muse Code</b> ({@code agent/MuseWorker});
     * empty when they do not (a Claude model, the picks not complete, or an override that locks the model:
     * a server whose own launcher keeps running exactly as before).
     */
    public static Optional<SupportedModels.Mode> museMain() {
        return museMain(active());
    }

    static Optional<SupportedModels.Mode> museMain(Optional<MagicianPicks> picks) {
        return picks.filter(p -> SupportedModels.harness(p.main().get(), p.mainPay().get())
                        == SupportedModels.Harness.MUSE_CODE)
                .flatMap(MagicianPicks::mainPay);
    }

    /** The cheap-model jobs' model spec, when the picks choose it; else {@code fallback} (the job's own default). */
    public static String cheapSpec(String fallback) {
        return active().flatMap(MagicianPicks::cheap).map(SupportedModels.Model::workerSpec).orElse(fallback);
    }

    /**
     * The wish model's fallback CHAIN: the pick first, then {@code magician.fallback} in order. Empty when the
     * picks are not in charge (an override locks the model: that server keeps its own launcher, untouched).
     */
    public static Optional<java.util.List<com.moddingfromamod.agent.ModelFallback.Entry>> mainChain() {
        return chain(ModdySettings.shared(), SupportedModels.Role.MAIN);
    }

    /**
     * The helper model's chain: its pick, then {@code magician.cheapFallback}. Every helper job's provider and payment
     * follow it ({@link HelperRoute}), so a key for a provider the host did not choose is never used.
     */
    public static Optional<java.util.List<com.moddingfromamod.agent.ModelFallback.Entry>> cheapChain() {
        return chain(ModdySettings.shared(), SupportedModels.Role.CHEAP);
    }

    static Optional<java.util.List<com.moddingfromamod.agent.ModelFallback.Entry>> chain(ModdySettings s,
                                                                                       SupportedModels.Role role) {
        return active(s).map(p -> {
            boolean main = role == SupportedModels.Role.MAIN;
            var primary = new com.moddingfromamod.agent.ModelFallback.Entry(
                    main ? p.main().get() : p.cheap().get(), main ? p.mainPay().get() : p.cheapPay().get());
            String csv = s.value(main ? SettingsCatalog.MAIN_FALLBACK : SettingsCatalog.CHEAP_FALLBACK).orElse("none");
            return com.moddingfromamod.agent.ModelFallback.chain(primary,
                    com.moddingfromamod.agent.ModelFallback.parse(csv, role).entries());
        });
    }

    /**
     * May Claude Code be started at all (D20)? Not when the host's picks are in charge and neither they nor
     * their fallbacks name an Anthropic model: a Muse-only setup never starts the {@code claude} CLI, whose own sign-in
     * would otherwise answer the jobs that ask for it by name (the reference researcher, a Haiku fallback). With no
     * picks in charge (an override that locks the model) nothing changes.
     */
    public static boolean claudeCodeChosen() {
        return claudeCodeChosen(ModdySettings.shared());
    }

    static boolean claudeCodeChosen(ModdySettings s) {
        if (active(s).isEmpty()) {
            return true;
        }
        return java.util.stream.Stream.of(SupportedModels.Role.MAIN, SupportedModels.Role.CHEAP)
                .flatMap(r -> chain(s, r).orElse(java.util.List.of()).stream())
                .anyMatch(e -> e.model().provider() == SupportedModels.Provider.ANTHROPIC);
    }

    /** One launch's environment for {@code entry}: in subscription mode, no Anthropic key to bill instead. */
    public static void applyEntry(Map<String, String> env, com.moddingfromamod.agent.ModelFallback.Entry entry) {
        strip(env, entry.model(), entry.mode());
    }

    /** The worker's environment: in subscription mode, no Anthropic key to bill instead of the sign-in. */
    public static void applyMain(Map<String, String> env) {
        active().ifPresent(p -> strip(env, p.main().get(), p.mainPay().get()));
    }

    static void strip(Map<String, String> env, SupportedModels.Model m, SupportedModels.Mode mode) {
        if (mode == SupportedModels.Mode.SUBSCRIPTION && m.provider() == SupportedModels.Provider.ANTHROPIC) {
            env.remove(SupportedModels.Provider.ANTHROPIC.keyVar);
        }
    }
}
