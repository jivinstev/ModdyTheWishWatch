package com.moddingfromamod.marshal;

/**
 * Where <b>"take me back"</b> should put him.
 *
 * <p><b>The rule that was wrong.</b> The departure point was remembered with a
 * {@code computeIfAbsent} — first hop only — for a good reason: hopping from one wish straight to
 * another must still lead home, not to the wish he visited in between. But the entry was only ever
 * cleared by actually pressing "take me back". A child who teleports out to look at his castle and
 * then simply carries on playing leaves that entry behind forever, and the next "take me back",
 * hours later, throws him to wherever he happened to be standing that time.
 *
 * <p>Steve hit exactly that: it put him back where he was when he made an earlier wish rather than
 * where he was standing when he pressed the button.
 *
 * <p><b>The rule that is right</b> distinguishes the two cases by asking whether a trip is still in
 * progress: he is mid-trip if he is still near where the last hop put him down. Then hop → hop keeps
 * the original home (he never went back to anything of his own), while hop → play → hop starts
 * fresh (the first trip is over, whether or not he used the button to end it).
 *
 * <p>Pure and Minecraft-free — it takes coordinates and answers a question about them.
 */
public final class TripHome {

    /**
     * How far he can be from the landing spot and still count as "on that trip".
     *
     * <p>Generous on purpose. He lands next to his castle and walks around it, in it and on top of
     * it — all of that is still the visit. It only has to be tight enough that going off to play
     * somewhere else reads as leaving, and a castle is nothing like this wide.
     */
    public static final double STILL_THERE = 64.0;

    private TripHome() {
    }

    /**
     * Should this hop record a new home, or keep the one already stored?
     *
     * @param hasHome        is a departure point already remembered?
     * @param sameDimension  is he in the same world as the last landing?
     * @param distanceToLast how far he is from where the last hop put him down; negative if unknown
     * @return true to overwrite the remembered departure point with where he is now
     */
    public static boolean shouldRecord(boolean hasHome, boolean sameDimension,
                                       double distanceToLast) {
        if (!hasHome) {
            return true;              // nothing remembered: this is the start of a trip
        }
        if (!sameDimension) {
            return true;              // he left the world he was taken to; that trip is over
        }
        if (distanceToLast < 0) {
            return true;              // we never saw him land, so the old entry is not trustworthy
        }
        // Still standing about where he was dropped → the trip is ongoing, keep the real home.
        return distanceToLast > STILL_THERE;
    }
}
