package com.moddingfromamod.wish;

/**
 * The template set the watch can grant on its own — no AI, no network, no waiting.
 *
 * <p><b>It ships EMPTY.</b> It used to carry ~18 hard-coded
 * built-in templates ({@code super_fast}, {@code tower}, {@code cats}, {@code cake}, the six Create
 * machines …) and six retired panel buttons. They had not been tuned in months, measured
 * zero live hits for any of them in a week, and the loose lane turned "breakfast" into
 * {@code super_fast} and "knight" into {@code night_spooky}. They are DELETED rather than switched
 * off ({@code docs/COMPAT_SHIMS.md}, DECISION: nobody else holds them).
 *
 * <p>What the active set holds now is composed by {@code LearnedTemplates.rebuild} from exactly two
 * sources: the library (the server's curated set layered over the jar's starter library) and
 * the templates the agent saved with {@code add_template}. A wish the two cannot answer goes to the
 * agent.
 */
public final class WishCatalog {

    /**
     * A button on the watch: what the kid sees, and the wish text it submits.
     *
     * <p>{@code templateId} is what makes a press EXACT. A learned button carries the id of the
     * recipe it came from, so pressing it reinstates that precise design rather than being
     * re-matched from its label — which is both fragile (the terms may not route back to
     * themselves) and wrong in spirit: a button is not a guess at what the child meant, it is the
     * thing he already has.
     *
     * @param icon a vanilla item id the panel should draw for this button, or {@code ""} to let
     *             {@code WatchIcons} keyword-scan the label and the text. A curated library entry
     *             (and a learned one) may name its own; the scan is the fallback
     *             for a recipe an agent invented five minutes ago.
     */
    public record Preset(String label, String wishText, String templateId, String icon,
                         String ownerId) {

        public Preset(String label, String wishText) {
            this(label, wishText, "", "", "");
        }

        public Preset(String label, String wishText, String templateId) {
            this(label, wishText, templateId, "", "");
        }

        /**
         * A button with no owner: the library, and anything a caller has no owner for. Empty means
         * SHARED — everybody's — which is right for both
         * ({@link com.moddingfromamod.marshal.ArtifactOwner}).
         */
        public Preset(String label, String wishText, String templateId, String icon) {
            this(label, wishText, templateId, icon, "");
        }

        public Preset {
            ownerId = com.moddingfromamod.marshal.ArtifactOwner.normalise(ownerId);
        }
    }

    private static WishTemplates templates = WishTemplates.empty();

    private WishCatalog() {
    }

    /** The active template set. */
    public static WishTemplates templates() {
        return templates;
    }

    /** Replace the active templates (used by tests today; by the agent's learned set in Phase 2). */
    public static void setTemplates(WishTemplates replacement) {
        templates = replacement == null ? WishTemplates.empty() : replacement;
    }

    /** Forget every template: the state before the library and the learned set are loaded. */
    public static void resetToDefaults() {
        templates = WishTemplates.empty();
    }
}
