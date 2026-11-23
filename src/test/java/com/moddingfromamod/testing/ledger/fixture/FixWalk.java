package com.moddingfromamod.testing.ledger.fixture;

/** A fixture for {@code GateLedgerTest}: the "walk" one check depends on and another does not. */
public final class FixWalk {
    private FixWalk() {
    }

    public static int walk(int x) {
        return x + 1;
    }
}
