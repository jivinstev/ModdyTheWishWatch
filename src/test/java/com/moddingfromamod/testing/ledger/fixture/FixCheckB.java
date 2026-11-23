package com.moddingfromamod.testing.ledger.fixture;

/** A fixture for {@code GateLedgerTest}: a check that never runs {@link FixWalk}. */
public final class FixCheckB {
    private FixCheckB() {
    }

    public static String check(String s) {
        return FixOther.other(s);
    }
}
