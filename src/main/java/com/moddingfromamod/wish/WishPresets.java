package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which wishes get the big buttons on Moddy.
 *
 * <p>They used to be six hardcoded ones, forever. But the whole point of the project is that the
 * watch <b>learns</b>: when the agent grants something new it writes a template, and from then on
 * that wish is instant and offline. Those learned wishes were invisible — the child had to remember
 * he could ask for a floating cheese island, and type it correctly, to get the thing he was proud
 * of yesterday.
 *
 * <p>So the buttons now lead with the server's library (the starter set first) and <b>what he
 * most recently made</b>. The good stuff is one tap away.
 *
 * <p>Pure — no Minecraft imports — so the ordering, de-duplication and capping are unit-tested
 * without a game.
 */
public final class WishPresets {

    /**
     * How far back the scrollable history goes: up to a hundred of the most recent learned wishes,
     * newest first, so a child can scroll back in time and re-run something they built ages ago.
     *
     * <p>The watch stores far more than this ({@link LearnedTemplates#MAX_TEMPLATES}); this is only
     * how many the screen offers to scroll through, kept sane so the sync packet stays small.
     */
    public static final int MAX_LEARNED = 100;

    /**
     * <b>How many of a child's ORPHAN taught wishes the stock panel shows</b>.
     *
     * <p>Since "one tile per thing he made" (2026-09-28) a taught wish whose thing is on the panel
     * lives on that thing's creation tile, not as a button of its own. What is left are ORPHANS —
     * a recipe whose thing was removed, or one that names no thing at all — and they follow the
     * starters, newest first, capped at this many; older ones stay in the scrolling mod screen.
     * It is a choice about the panel, not a limit of it: the panel pages.
     */
    public static final int OWN_SLOTS = 8;

    private WishPresets() {
    }

    /**
     * The wishes Moddy offers, in screen order: the server's curated <b>library</b>, then
     * this world's learned wishes, newest first.
     *
     * <p>Order is precedence made visible. The de-duplication keys on what the button DOES, so a
     * learned wish the server has since promoted appears once — as the library's copy, which is the
     * one whose recipe and icon are being served. There is no third section: the shipped built-ins
     * that used to sit at the bottom were deleted.
     *
     * <p>The cap is per SECTION rather than on the total, so a big library cannot push every learned
     * wish off the end of the list — a child's own things are the reason the scroll exists.
     */
    public static List<WishCatalog.Preset> forScreen(List<WishCatalog.Preset> library,
                                                     List<WishCatalog.Preset> learned) {
        return forScreen("", library, learned);
    }

    /**
     * <b>The same, for ONE CHILD</b>.
     *
     * <p>Order is unchanged — library, then learned — and one thing is added: a learned button
     * another child taught is not his to see. The library is everybody's, so it is not filtered;
     * only the learned section is per child.
     *
     * <h2>Why filtering matters more than it sounds</h2>
     *
     * <p>A stock client's panel was one nine-slot row (§10; it pages, and
     * {@link #forPanel} applies this same filter). With one child, his hundred learned
     * wishes scroll behind the library's nine and everything is reachable. With two, an unfiltered
     * list means <b>his brother's wishes push his own off the end of the row he can actually
     * see</b> — and the loss is silent: the panel looks full, the buttons all work, they are just
     * not his. {@link #MAX_LEARNED} is applied AFTER this filter for the same reason, so a busy
     * afternoon by one child cannot spend another child's budget.
     *
     * <p><b>An unowned learned wish belongs to everyone</b>, not to nobody. Every learned entry on
     * every disk today is unowned, so treating absent as "hide it" would empty the panel on the
     * first boot of this build — which is the failure that looks like the feature working.
     *
     * @param viewerId the child looking at the panel — {@code ""} shows everything, which is the
     *                 single-child case and a gate's case
     */
    public static List<WishCatalog.Preset> forScreen(String viewerId,
                                                     List<WishCatalog.Preset> library,
                                                     List<WishCatalog.Preset> learned) {
        List<WishCatalog.Preset> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        learned = onlyVisibleTo(viewerId, learned);

        if (library != null) {
            int taken = 0;
            for (WishCatalog.Preset p : library) {
                if (taken >= MAX_LEARNED) {
                    break;
                }
                if (p == null || p.wishText() == null || p.wishText().isBlank()) {
                    continue;
                }
                if (seen.add(key(p))) {
                    out.add(p);
                    taken++;
                }
            }
        }
        if (learned != null) {
            int taken = 0;
            for (WishCatalog.Preset p : learned) {
                if (taken >= MAX_LEARNED) {
                    break;
                }
                if (p == null || p.wishText() == null || p.wishText().isBlank()) {
                    continue;
                }
                if (seen.add(key(p))) {
                    out.add(p);
                    taken++;
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The learned buttons this child may see: his own, and the ones nobody claimed.
     *
     * <p>Returns the list unchanged when there is no viewer, so a gate, a direct call and a
     * single-child server all behave exactly as they did before per-child ownership.
     */
    public static List<WishCatalog.Preset> onlyVisibleTo(String viewerId,
                                                        List<WishCatalog.Preset> learned) {
        if (learned == null || learned.isEmpty()
                || com.moddingfromamod.marshal.ArtifactOwner.normalise(viewerId).isEmpty()) {
            return learned;
        }
        List<WishCatalog.Preset> mine = new ArrayList<>();
        for (WishCatalog.Preset p : learned) {
            if (p != null
                    && com.moddingfromamod.marshal.ArtifactOwner.visibleTo(viewerId, p.ownerId())) {
                mine.add(p);
            }
        }
        return mine;
    }

    /**
     * <b>The buttons for the STOCK PANEL, as one pool in panel order</b>.
     *
     * <p>The same precedence and the same per-child filter as {@link #forScreen}: the library's
     * copy of a wish wins, and his brother's taught wishes are not his.
     *
     * <p><b>One tile per thing he made</b>: a taught wish whose thing is
     * already on the panel as a creation is NOT a button here — the creation's tile carries its
     * recipe ({@code WatchSnapshot}, via {@code TaughtJoin}), and two tiles for one dragon read to
     * a child as the same thing twice. So the pool is his ORPHANS — taught wishes with no creation
     * on the panel (the thing was removed, or the recipe names nothing, like a plain chicken rain)
     * — newest first, up to {@link #OWN_SLOTS}, THEN every starter in the library's order (
     * the starters come after all of his own things).
     *
     * @param library the curated buttons, in library order
     * @param learned his learned buttons, <b>newest first</b>
     * @param joined  the template ids a creation on the panel already carries — never shown here
     */
    public static List<WishCatalog.Preset> forPanel(String viewerId,
                                                    List<WishCatalog.Preset> library,
                                                    List<WishCatalog.Preset> learned,
                                                    Set<String> joined) {
        // HIS buttons first, newest first; the starters after all of his own things. A
        // starter whose words he also taught still shows once, as the starter — the library's copy
        // of a wish wins, as it always did.
        Set<String> library0 = new LinkedHashSet<>();
        if (library != null) {
            for (WishCatalog.Preset p : library) {
                if (p != null && p.wishText() != null && !p.wishText().isBlank()) {
                    library0.add(key(p));
                }
            }
        }
        List<WishCatalog.Preset> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<WishCatalog.Preset> visible = onlyVisibleTo(viewerId, learned);
        if (visible != null) {
            int orphans = 0;
            for (WishCatalog.Preset p : visible) {
                if (orphans >= OWN_SLOTS) {
                    break;
                }
                if (p == null || p.wishText() == null || p.wishText().isBlank()) {
                    continue;
                }
                if (joined != null && p.templateId() != null && joined.contains(p.templateId())) {
                    continue;   // his creation's tile is this button now
                }
                if (!library0.contains(key(p)) && seen.add(key(p))) {
                    out.add(p);
                    orphans++;
                }
            }
        }
        if (library != null) {
            int taken = 0;
            for (WishCatalog.Preset p : library) {
                if (taken >= MAX_LEARNED) {
                    break;
                }
                if (p != null && p.wishText() != null && !p.wishText().isBlank()
                        && seen.add(key(p))) {
                    out.add(p);
                    taken++;
                }
            }
        }
        return List.copyOf(out);
    }

    /** {@link #forPanel(String, List, List, Set)} with no creation on the panel to join to. */
    public static List<WishCatalog.Preset> forPanel(String viewerId,
                                                    List<WishCatalog.Preset> library,
                                                    List<WishCatalog.Preset> learned) {
        return forPanel(viewerId, library, learned, Set.of());
    }

    /** De-duplicate on what the button DOES, so a re-learned wish doesn't appear twice. */
    private static String key(WishCatalog.Preset p) {
        return p.wishText() == null ? "" : p.wishText().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * <b>A wish text GUARANTEED to reach this template — or null if none can be built.</b>
     *
     * <p>🔴 <b>The bug this exists for: a learned recipe written with {@code any} got NO BUTTON,
     * while Moddy told the child it had one.</b> {@code LearnedTemplates.asPresets} used to demand
     * an {@code all} array and skip anything without one — with the comment <i>"without `all` terms
     * there is no text guaranteed to match"</i>, which is simply not true of {@code any}: a single
     * one of its terms matches by definition. And {@code AddTemplateTool}'s own schema says
     * <i>"[all|any REQUIRED — give at least one]"</i>, so the agent is explicitly told either will
     * do, and the shipped catalogue itself uses {@code any} for six of its own presets.
     *
     * <p>So which shape the agent happened to pick decided whether the child ever saw the button,
     * silently, with Moddy saying <i>"next time just tap its button and it appears in a flash!"</i>
     * either way. Measured over six consecutive Gate D runs on the same wish: three templates
     * written with {@code all} (button appeared) and three with {@code any} (no button, ever). It
     * is a coin toss per wish on the shipping path, and it is the thing the whole watch is for.
     *
     * <p>The rule is {@code WishTemplates.Template.matches}, read back: every {@code all} term must
     * appear, at least one {@code any} term must appear if there are any, and no {@code none} term
     * may. So join the {@code all} terms and add ONE {@code any} term — the first, which is the one
     * the agent led with. A {@code none} term that turns up inside the result is refused rather than
     * offered, because a button whose own text cannot reach its recipe is worse than no button:
     * the press carries the recipe id, but the text is the seed a child sees and edits.
     */
    public static String matchingText(List<String> all, List<String> any, List<String> none) {
        List<String> parts = new ArrayList<>();
        for (String term : all == null ? List.<String>of() : all) {
            if (term != null && !term.isBlank()) {
                parts.add(term.trim());
            }
        }
        String pick = null;
        for (String term : any == null ? List.<String>of() : any) {
            if (term != null && !term.isBlank()) {
                pick = term.trim();
                break;
            }
        }
        boolean anyOffered = false;
        for (String term : any == null ? List.<String>of() : any) {
            anyOffered |= term != null && !term.isBlank();
        }
        if (anyOffered) {
            parts.add(pick);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String text = String.join(" ", parts);
        String hay = text.toLowerCase(Locale.ROOT);
        for (String term : none == null ? List.<String>of() : none) {
            if (term != null && !term.isBlank() && hay.contains(term.trim().toLowerCase(Locale.ROOT))) {
                return null;
            }
        }
        return text;
    }

    /**
     * A readable button label from a template id: {@code floating_castle} → "Floating Castle".
     *
     * <p>Ids are what the agent writes, and they are snake_case machine names. A child should see
     * words.
     */
    public static String labelFor(String id) {
        if (id == null || id.isBlank()) {
            return "Wish";
        }
        StringBuilder sb = new StringBuilder();
        for (String word : id.trim().split("[_\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)))
              .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        String label = sb.toString();
        return label.length() > 28 ? label.substring(0, 28) : label;
    }
}
