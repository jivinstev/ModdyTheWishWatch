package com.moddingfromamod.testing.ledger.fixture;

/** A fixture for {@code GateLedgerTest}: code only {@link FixCheckB} runs. */
public final class FixOther {
    private FixOther() {
    }

    public static String other(String s) {
        return s + "!";
    }
}
