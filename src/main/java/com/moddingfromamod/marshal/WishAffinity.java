package com.moddingfromamod.marshal;

import java.util.Locale;

import com.moddingfromamod.ModdingFromAModMod;

/**
 * <b>Who gets a wish about a thing somebody has already worked on.</b> The routing control the
 * wish-context experiment cannot run without.
 *
 * <p>Refining one thing repeatedly currently <i>feels</i> coherent, and some of that is luck: at one
 * slot, consecutive wishes about the plant usually landed on the same worker, which still held the
 * earlier turns in its own context. {@link WishSlots#DEFAULT} is now 3, so that luck is gone. Before
 * anything can be done about it, "same worker" and "different worker" have to be things a run can be
 * <b>forced</b> into — otherwise every measurement is of whichever routing the scheduler happened to
 * produce that night, and the two arms are distinguishable only by the operator's memory. That is
 * the same defect that misfiled.
 *
 * <p>So this is arm A ({@link #SAME}) and arm B ({@link #DIFFERENT}) as a switch:
 *
 * <ul>
 *   <li>{@link #OFF} — today's behaviour exactly. Whoever asks first takes it. <b>The default</b>,
 *       because Phase 1 is instrumentation and a control must not change what a child sees before
 *       the thing it controls has been measured.</li>
 *   <li>{@link #SAME} — a wish about a thing goes back to the worker that last worked it. The
 *       fidelity ceiling: what in-context history buys, measured rather than assumed.</li>
 *   <li>{@link #DIFFERENT} — a wish about a thing goes to <b>anyone but</b> that worker. Today's
 *       floor at three slots, made reproducible instead of occasional.</li>
 * </ul>
 *
 * <p><b>Read per call, like {@link WishSlots}</b> — never cached — so a test can flip it mid-run and
 * a single client boot can measure both arms. A cached value here would mean one process per arm,
 * which at ~117 s a wish is the difference between an experiment and an evening.
 *
 * <p>Neither forcing mode is allowed to starve a wish: see {@link ArtifactAffinity#HOLD_MS}. A
 * control that can wedge a child's wish forever is not a control, it is an outage, and an arm whose
 * wishes never landed measures nothing anyway.
 */
public enum WishAffinity {

    /** Whoever asks first takes it. Today's behaviour, and the default. */
    OFF,

    /** Back to the worker that made the thing — the ceiling. */
    SAME,

    /** Anyone but the worker that made the thing — the floor at three slots. */
    DIFFERENT;

    /** {@code -Dmoddingfromamod.affinity=same} (or {@code -Ptestprops="affinity=same"}). */
    public static final String PROPERTY = ModdingFromAModMod.MODID + ".affinity";

    /** Nothing changes until an experiment says so. */
    public static final WishAffinity DEFAULT = OFF;

    private static volatile WishAffinity override;

    /** How wishes are being routed <b>right now</b>. Never cached — see the class note. */
    public static WishAffinity current() {
        WishAffinity forced = override;
        if (forced != null) {
            return forced;
        }
        return parse(System.getProperty(PROPERTY));
    }

    /**
     * A name to a mode, tolerantly.
     *
     * <p>An unreadable value falls back to {@link #DEFAULT} rather than to a forcing mode. A typo'd
     * flag that silently turned forcing ON would produce an arm nobody asked for and label it with
     * whatever the operator believed they had set — which is precisely the failure this class exists
     * to remove one level up.
     */
    public static WishAffinity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /**
     * Force a mode for this JVM; {@code null} restores the property/default.
     *
     * <p>Public and named for what it does, like {@link WishSlots#override(Integer)}: a seam nobody
     * can find gets reinvented, worse, in every test that needs it.
     */
    public static void override(WishAffinity mode) {
        override = mode;
    }

    /** True when this mode constrains who may take a wish at all. */
    public boolean forcing() {
        return this != OFF;
    }

    /** The lower-case name the action log and the scorer use as an arm label. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
