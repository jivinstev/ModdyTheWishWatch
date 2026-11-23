package com.moddingfromamod.testing.ledger.fixture;

/** A fixture for {@code GateLedgerTest}: a check that runs {@link FixWalk}. */
public final class FixCheckA {
    private FixCheckA() {
    }

    public static int check(int x) {
        return FixWalk.walk(x) * 2;
    }
}
