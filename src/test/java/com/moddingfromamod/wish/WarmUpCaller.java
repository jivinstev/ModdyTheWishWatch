package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Calls {@link WarmUp} the way {@code ApprenticeService.warm} does: {@link WarmUp#load()} first, then a direct call of
 * {@link WarmUp#run}, with no reflection that would load {@link WarmUp.Outcome} early. {@link WarmUpTest} loads it in a
 * class loader of its own that it then closes.
 */
public final class WarmUpCaller implements Supplier<String> {

    private final BooleanSupplier running;
    private final Runnable job;
    private final List<String> said = new ArrayList<>();
    private final WarmUp.Log log = new WarmUp.Log() {
        @Override
        public void debug(String message) {
            said.add("debug");
        }

        @Override
        public void warn(String message) {
            said.add("warn");
        }

        @Override
        public void error(String message, Throwable cause) {
            said.add("error");
        }
    };

    public WarmUpCaller(BooleanSupplier running, Runnable job) {
        this.running = running;
        this.job = job;
        WarmUp.load();
    }

    /** "<outcome> <what was said>", e.g. "STOPPED [debug]". */
    @Override
    public String get() {
        WarmUp.Outcome outcome = WarmUp.run("the matcher", running, job, log);
        return outcome + " " + said;
    }
}
