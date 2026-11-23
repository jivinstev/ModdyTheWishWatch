package com.moddingfromamod.world;

/**
 * <b>Who gets a slot</b> (step 6, docs/OPEN_DOOR_PLAN.md §11.8): at most
 * {@code max} players at once, and a Wish Crafter may never take the last {@code magicianSlots} of them,
 * so a Wish Magician always fits. Nobody is bumped to make room. Pure.
 */
public final class CrafterSlots {

    private CrafterSlots() {
    }

    public enum Decision {
        /** Not ours to judge: vanilla's own answer stands (a Magician, an operator, a shut door). */
        VANILLA,
        /** A Crafter, and there is a Crafter slot free: let him in. */
        ADMIT,
        /** A Crafter, and every slot a Crafter may take is taken. */
        CRAFTERS_FULL
    }

    /**
     * @param crafters players online now who are Crafters (off the list, no operator)
     * @param online   every player online now
     */
    public static Decision decide(boolean open, boolean listed, boolean operator, int crafters, int online,
                                  int max, int magicianSlots) {
        if (!open || listed || operator) {
            return Decision.VANILLA;
        }
        int crafterCap = Math.max(0, max - Math.max(0, magicianSlots));
        if (online >= max || crafters >= crafterCap) {
            return Decision.CRAFTERS_FULL;
        }
        return Decision.ADMIT;
    }

    /** What a Crafter turned away is told. Plain words, the world named; a Magician still fits. */
    public static String full(String world, String signupHost) {
        String place = world == null || world.isBlank() ? "This world" : world;
        return place + "'s Wish Crafter spots are all taken right now - try again in a little while!\n"
                + "Wish Magicians always have room: " + (signupHost == null || signupHost.isBlank()
                        ? "moddythewishwatch.com" : signupHost);
    }
}
