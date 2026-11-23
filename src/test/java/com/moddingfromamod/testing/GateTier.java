package com.moddingfromamod.testing;

/**
 * <b>Which run a test is in</b>. Gate A ({@code ./gradlew build}) is the default and must stay small: nothing
 * in it enumerates a product that can keep growing, and a test that wants MORE samples than Gate A can afford takes them
 * only when asked. Two seams, both system properties the {@code test} task forwards from Gradle properties:
 * <ul>
 *   <li>{@code -Pgate.nightly} → {@code moddy.gate.nightly=true}: the nightly's larger, budgeted samples
 *       ({@link com.moddingfromamod.testing.ledger.NightlySampler}) and the fingerprint ledger's re-verification slice
 *       ({@link com.moddingfromamod.testing.ledger.GateLedger}); {@code tools/gate-a-nightly.sh} runs it every night
 *       (phase 2).</li>
 *   <li>{@code -Pgate.apprentice} → {@code moddy.gate.apprentice=true}: what {@code tools/apprentice-gate.sh} runs and
 *       Gate A does not (the trained layer over the whole corpus).</li>
 * </ul>
 */
public final class GateTier {

    private GateTier() {
    }

    public static boolean nightly() {
        return Boolean.getBoolean("moddy.gate.nightly");
    }

    /** The apprentice gate, which the nightly also includes. */
    public static boolean apprentice() {
        return Boolean.getBoolean("moddy.gate.apprentice") || nightly();
    }
}
