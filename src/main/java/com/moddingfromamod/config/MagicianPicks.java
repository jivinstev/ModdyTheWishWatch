package com.moddingfromamod.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * <b>The Magician setup's four picks</b>: the main model and how it is paid for, the cheap model and how it is
 * paid for. Read from {@link ModdySettings} (so an override or the server's file answers first), judged here.
 *
 * <p>Pure: no Minecraft. The environment arrives as a lookup, so each rule is a unit test.
 */
public record MagicianPicks(String mainModel, String mainMode, String cheapModel, String cheapMode) {

    /** The picks as the settings say now (empty strings for what is not chosen yet). */
    public static MagicianPicks read(ModdySettings s) {
        return new MagicianPicks(s.value(SettingsCatalog.MAIN_MODEL).orElse(""),
                s.value(SettingsCatalog.MAIN_MODE).orElse(""),
                s.value(SettingsCatalog.CHEAP_MODEL).orElse(""),
                s.value(SettingsCatalog.CHEAP_MODE).orElse(""));
    }

    public static MagicianPicks current() {
        return read(ModdySettings.shared());
    }

    public Optional<SupportedModels.Model> main() {
        return SupportedModels.find(mainModel).filter(m -> m.role() == SupportedModels.Role.MAIN);
    }

    public Optional<SupportedModels.Model> cheap() {
        return SupportedModels.find(cheapModel).filter(m -> m.role() == SupportedModels.Role.CHEAP);
    }

    public Optional<SupportedModels.Mode> mainPay() {
        return SupportedModels.Mode.of(mainMode);
    }

    public Optional<SupportedModels.Mode> cheapPay() {
        return SupportedModels.Mode.of(cheapMode);
    }

    /** All four chosen, from the table. */
    public boolean complete() {
        return main().isPresent() && cheap().isPresent() && mainPay().isPresent() && cheapPay().isPresent();
    }

    /** What the check's pass is recorded against: a change to any pick asks for the check again. */
    public String fingerprint() {
        return mainModel + "/" + mainMode + "/" + cheapModel + "/" + cheapMode;
    }

    /**
     * Why these picks cannot run on this machine, in plain words; empty when they can. Checks the table's own rules
     * (what this build cannot run, D29 on a server) and the API keys' environment variables. Whether the programs are
     * installed and signed in is the probe's ({@link HarnessProbe}), not this.
     *
     * @param env       an environment lookup (null for a variable that is not set)
     * @param dedicated a dedicated server (Claude's subscription refused there, D29)
     */
    public List<String> problems(Function<String, String> env, boolean dedicated) {
        List<String> out = new ArrayList<>();
        if (main().isEmpty()) {
            out.add("Pick a wish model.");
        }
        if (mainPay().isEmpty()) {
            out.add("Pick how the wish model is paid for.");
        }
        if (cheap().isEmpty()) {
            out.add("Pick a helper model.");
        }
        if (cheapPay().isEmpty()) {
            out.add("Pick how the helper model is paid for.");
        }
        if (!out.isEmpty()) {
            return out;
        }
        judge(main().get(), mainPay().get(), env, dedicated, out);
        judge(cheap().get(), cheapPay().get(), env, dedicated, out);
        return out;
    }

    private static void judge(SupportedModels.Model m, SupportedModels.Mode mode, Function<String, String> env,
                              boolean dedicated, List<String> out) {
        if (dedicated) {
            SupportedModels.refusedOnServer(m, mode).ifPresent(out::add);
        }
        if (mode == SupportedModels.Mode.API) {
            String v = env.apply(m.provider().keyVar);
            if (v == null || v.isBlank()) {
                out.add(m.label() + " with an API key needs " + m.provider().keyVar
                        + " set where Minecraft starts (it is never typed into the game).");
            }
        }
    }

    /** One line for a player: "Opus 5.5 (low), API key · Ember 1.0, Subscription". */
    public String describe() {
        return main().map(SupportedModels.Model::label).orElse("no wish model") + ", "
                + mainPay().map(m -> m.label).orElse("?") + " · "
                + cheap().map(SupportedModels.Model::label).orElse("no helper model") + ", "
                + cheapPay().map(m -> m.label).orElse("?");
    }
}
