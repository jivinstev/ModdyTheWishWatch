package com.moddingfromamod.config;

import java.util.Map;
import java.util.function.Function;

/**
 * <b>Are the AI programs on this machine, and do they work?</b> (step 1) Claude Code and Muse Code: installed,
 * which version, and does a trivial call answer on the program's own sign-in (subscription) and, when its key's
 * environment variable is set, on the API key. Run only when the host presses "Check programs" in the Magician setup
 * (or a dedicated server's start-up check, D28): with Magician off nothing probes by itself ({@code MagicianOffTest}).
 *
 * <p>A GameTest hands a stub ({@link MagicianSetup#forTest}); the real one is {@link CliHarnessProbe}. <b>A test seam,
 * not an extension point</b>: no plug-in registers one, and it may change without notice.
 */
public interface HarnessProbe {

    /**
     * What one program said.
     *
     * @param answers per mode: empty string = a trivial call answered; anything else = why not, in plain words. A mode
     *                not tried (its key not set) is absent.
     */
    record Result(SupportedModels.Harness harness, boolean installed, String version,
                  Map<SupportedModels.Mode, String> answers) {

        public Result {
            answers = Map.copyOf(answers);
        }

        public static Result missing(SupportedModels.Harness h) {
            return new Result(h, false, "", Map.of());
        }

        public boolean works(SupportedModels.Mode mode) {
            return installed && "".equals(answers.get(mode));
        }

        /** One plain line: "Claude Code 2.1.3: signed in ✔, API key ✖ (…)", or how to get it. */
        public String line() {
            if (!installed) {
                return harness.label + ": not installed.";
            }
            StringBuilder sb = new StringBuilder(harness.label);
            String v = version.strip();
            // `muse --version` prints "Muse Code 1.4.24": the name is already in front (smoke saw it twice).
            if (v.regionMatches(true, 0, harness.label, 0, harness.label.length())) {
                v = v.substring(harness.label.length()).strip();
            }
            if (!v.isBlank()) {
                sb.append(' ').append(v);
            }
            sb.append(':');
            if (answers.isEmpty()) {
                sb.append(" installed, not tried.");
            }
            for (SupportedModels.Mode m : SupportedModels.Mode.values()) {
                String a = answers.get(m);
                if (a == null) {
                    continue;
                }
                sb.append(' ').append(m == SupportedModels.Mode.SUBSCRIPTION ? "sign-in" : "API key")
                        .append(a.isEmpty() ? " works." : " doesn't work (" + a + ").");
            }
            return sb.toString();
        }
    }

    /** Probe {@code harness}. Blocking (seconds): never on the server thread. */
    Result probe(SupportedModels.Harness harness, Function<String, String> env);

    /**
     * Is {@code harness} on this computer at all? A file lookup that runs nothing (the setup offers one button
     * per program found). Asked only once the host has opened the setup.
     */
    default boolean installed(SupportedModels.Harness harness) {
        return true;
    }
}
