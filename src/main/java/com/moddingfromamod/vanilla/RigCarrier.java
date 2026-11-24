package com.moddingfromamod.vanilla;

/**
 * <b>The vanilla item every rig display wears</b>, and the one name-sanitiser rig keys go through.
 *
 * <p>What is left of {@code RigPackWriter} deleted the pack rig (2026-09-23).
 * That class wrote a per-creature resource pack; the atom rig has none, so the only facts it held
 * that are still true are these two.
 *
 * <h2>Why a leather horse armour</h2>
 *
 * <p>The atom rig's ONLY colour channel is {@code minecraft:dyed_color} on a shared white cube
 * ({@link AtomLibrary}), and vanilla registers the {@code dyed_color} colour handler only for items
 * in {@code #minecraft:dyeable}. {@code paper} — the pack rig's carrier before {@code rigtint} —
 * has no colour handler at all, so every face's {@code tintindex} would be inert and every creature
 * would render pure white: bright, plausible, and passing any "the frame is not dark" check. It is
 * the same item Moddy's watch and the wish icons ride ({@code VanillaCarrier.ITEM_ID}), which is
 * safe because the atom rig delivers no per-creature override table that could shadow theirs —
 * {@code CarrierCollisionTest} pins that.
 */
public final class RigCarrier {

    private RigCarrier() {
    }

    /** The carrier item's path in the {@code minecraft} namespace. */
    public static final String CARRIER = "leather_horse_armor";

    /**
     * A resource-location-safe spelling of an agent-written name.
     *
     * <p>Sculpt part names are agent-written and routinely contain capitals ({@code armL},
     * {@code eyeR}) — which a {@code Identifier} rejects outright. A model id that fails to
     * parse is not a crash, it is a missing model, which is the magenta cube.
     */
    public static String safe(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            b.append("0123456789abcdefghijklmnopqrstuvwxyz_-.".indexOf(c) >= 0 ? c : '_');
        }
        return b.isEmpty() ? "x" : b.toString();
    }
}
