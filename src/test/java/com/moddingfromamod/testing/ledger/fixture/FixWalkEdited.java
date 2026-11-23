package com.moddingfromamod.testing.ledger.fixture;

/** A fixture for {@code GateLedgerTest}: {@link FixWalk} after an edit (the test serves these bytes under its name). */
public final class FixWalkEdited {
    private FixWalkEdited() {
    }

    public static int walk(int x) {
        return x + 2;
    }
}
