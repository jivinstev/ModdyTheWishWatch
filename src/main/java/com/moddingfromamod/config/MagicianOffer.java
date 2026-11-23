package com.moddingfromamod.config;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * <b>What the Magician setup offers</b> (steps 2–3): a model, or a way to pay for it, is offered only when this
 * build can run it, the program it needs passed the program check, and (for an API key) the key's environment
 * variable is set. Anything else is shown greyed with the reason, never hidden without one.
 *
 * <p>Pure: the program check's results and the environment arrive as arguments ({@code MagicianOfferTest}).
 */
public final class MagicianOffer {

    private MagicianOffer() {
    }

    public static final String CHECK_FIRST = "Moddy checks the programs first (a few seconds).";

    /** Why {@code m} cannot be paid for by {@code mode} here; empty when it can. */
    public static Optional<String> blocked(SupportedModels.Model m, SupportedModels.Mode mode,
                                           Map<SupportedModels.Harness, HarnessProbe.Result> probed,
                                           Function<String, String> env, boolean dedicated) {
        if (dedicated) {
            Optional<String> server = SupportedModels.refusedOnServer(m, mode);
            if (server.isPresent()) {
                return server;
            }
        }
        if (mode == SupportedModels.Mode.API) {
            String v = env.apply(m.provider().keyVar);
            if (v == null || v.isBlank()) {
                return Optional.of("Set " + m.provider().keyVar + " where Minecraft starts to use an API key.");
            }
        }
        SupportedModels.Harness h = SupportedModels.harness(m, mode);
        HarnessProbe.Result r = probed == null ? null : probed.get(h);
        if (r == null) {
            return Optional.of(CHECK_FIRST);
        }
        if (!r.installed()) {
            return Optional.of("Install " + h.label + " first.");
        }
        if (!r.works(mode)) {
            String why = r.answers().get(mode);
            return Optional.of(h.label + (mode == SupportedModels.Mode.SUBSCRIPTION ? "'s sign-in" : "'s API key")
                    + " didn't work" + (why == null || why.isEmpty() ? "." : ": " + why + "."));
        }
        return Optional.empty();
    }

    /** Can {@code m} be used at all, either way of paying? */
    public static Optional<String> blocked(SupportedModels.Model m,
                                           Map<SupportedModels.Harness, HarnessProbe.Result> probed,
                                           Function<String, String> env, boolean dedicated) {
        Optional<String> first = Optional.empty();
        for (SupportedModels.Mode mode : SupportedModels.Mode.values()) {
            Optional<String> b = blocked(m, mode, probed, env, dedicated);
            if (b.isEmpty()) {
                return b;
            }
            if (first.isEmpty()) {
                first = b;
            }
        }
        return first;
    }
}
