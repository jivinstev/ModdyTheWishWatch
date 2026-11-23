package com.moddingfromamod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * <b>Gate A — an icon cannot ship without its artwork.</b>
 *
 * <p>The failure this exists for is the one the vehicles feature already paid for once: a declared
 * icon with no texture is an <i>invisible</i> item — it renders as the magenta missing-texture cube
 * while every server-side test stays green, because nothing server-side ever looks at a sprite.
 */
class WishIconsTest {

    @Test
    void everyIconHasArtworkAndAModelOnTheClasspath() throws Exception {
        for (WishIcons.Icon icon : WishIcons.all()) {
            String model = "/assets/moddingfromamod/models/item/wish/" + icon.name() + ".json";
            var in = WishIconsTest.class.getResourceAsStream(model);
            assertNotNull(in, icon.name() + " ships no model (" + model + ")");
            // Every layer the model names, not "<name>.png": a icon with a fixed picture has
            // no dyed body, so its layer0 is the shared blank and its picture is the detail layer.
            var textures = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("textures");
            for (String key : textures.keySet()) {
                String tex = "/assets/moddingfromamod/textures/item/"
                        + textures.get(key).getAsString().split(":item/")[1] + ".png";
                assertNotNull(WishIconsTest.class.getResourceAsStream(tex),
                        icon.name() + " is offered as an icon but ships no sprite (" + tex + ") — it "
                                + "would render as the missing-texture cube with every other gate green");
            }
        }
    }

    /**
     * The item definition must actually route every number, or the stack falls back to one icon.
     * (the model's own {@code overrides} are gone — 1.21.4 never read them — so the routing
     * is {@code items/wish_item.json}'s {@code range_dispatch}, written by tools/gen-carrier-model.py.)
     */
    @Test
    void theItemDefinitionRoutesEveryIconNumber() throws Exception {
        String json = new String(WishIconsTest.class
                .getResourceAsStream("/assets/moddingfromamod/items/wish_item.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (WishIcons.Icon icon : WishIcons.all()) {
            assertTrue(json.contains("\"threshold\": " + icon.id() + ","),
                    "items/wish_item.json has no entry for icon " + icon.id() + " (" + icon.name()
                            + "), so that wish would silently wear the default artwork");
            // An icon with a vanilla base (items/icon-bases.json) draws that item instead.
            String vanilla = com.moddingfromamod.pack.IconBases.forIcon(icon.name());
            assertTrue(json.contains(vanilla != null ? "\"" + vanilla + "\"" : "item/wish/" + icon.name() + "\""),
                    "items/wish_item.json never points at " + icon.name());
        }
    }

    /**
     * Numbers are a CONTRACT: a wish stores its icon id, so reusing a number would silently change
     * the shape of a thing a child already owns.
     */
    @Test
    void iconNumbersAreUniqueAndStable() {
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (WishIcons.Icon icon : WishIcons.all()) {
            assertTrue(ids.add(icon.id()), "icon number " + icon.id() + " is used twice");
            assertTrue(names.add(icon.name()), "icon name " + icon.name() + " is used twice");
            assertTrue(icon.id() > 0, "0 is the no-icon-chosen case and must not be an icon");
        }
    }

    /**
     * <b>No two icons are the same sprite twice.</b> A duplicate check, and deliberately nothing more.
     *
     * <p>This started life as a "no two icons may read alike" gate, written after an instruction to
     * draw every held item <i>"on the same diagonal, business end at the top-right"</i> produced an
     * axe, a hammer and a helmet that a human immediately called too similar. Calibrating it against
     * that reported defect is what killed it: <b>those pairs differ by 32, 32 and 34 pixels</b> — far
     * above the threshold that was supposed to catch them — while an unremarked pair (bag vs orb)
     * differs by 18.
     *
     * <p>So pixel distance does not measure "looks alike". What made those three confusable was a
     * shared <i>gross form</i> — a diagonal band with its mass in the same quadrant — not a shared
     * outline, and the metric cannot see that. Rather than keep a gate that would have missed the
     * defect it was written for while failing on pairs nobody minds, it is narrowed to what it CAN
     * honestly assert: that no icon has been pasted in twice. Whether two icons read alike is a
     * question for the contact sheet and a person, which is how the original problem was found.
     */
    @Test
    void noTwoIconsAreTheSameSpriteTwice() throws Exception {
        Map<String, boolean[]> sil = new LinkedHashMap<>();
        for (WishIcons.Icon icon : originals()) {
            sil.put(icon.name(), silhouetteOf(icon.name()));
        }
        List<String> names = new ArrayList<>(sil.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                boolean[] a = sil.get(names.get(i));
                boolean[] b = sil.get(names.get(j));
                int diff = 0;
                for (int k = 0; k < a.length; k++) {
                    if (a[k] != b[k]) {
                        diff++;
                    }
                }
                assertTrue(diff >= 10, names.get(i) + " and " + names.get(j) + " differ in only "
                        + diff + " pixels — that is the same sprite twice, not two icons");
            }
        }
    }

    /**
     * <b> — two icons must not be the same SHAPE, which is a far stronger bar than not being
     * the same sprite.</b>
     *
     * <p>The check above passes at ten differing pixels out of 256, and that is how the library
     * came to hold {@code bag} and {@code orb} with <b>88%</b> of their silhouette in common. The
     * consequence was measured rather than imagined: in one 10-wish run at production settings the
     * model chose {@code orb} three times and twice said in its own words it was a nearest miss —
     * <i>"No egg icon in the list"</i>, <i>"No bomb/TNT icon"</i>, <i>"No snowball icon"</i>. Three
     * different wishes, one sphere, every gate green.
     *
     * <p><b>The threshold is 75%, and the 19 pairs already above it are named here rather than
     * hidden by raising it</b> (WISH_LINTERS P9: derive from real output, never choose). That is
     * the {@code RealBuildCalibrationTest} shape — the gate is proven to fire, the backlog is
     * visible, and silencing one is an edit somebody has to read. A new pair joining this list
     * fails the build.
     *
     * <p>The metric is silhouette Jaccard, and it is deliberately blind to interior detail:
     * {@code food_round} is a burger and shares 78% of its outline with {@code snowball} while
     * being unmistakable in a chest. So this is a screen for "did we just add another sphere", not
     * a verdict on whether two icons are confusable — which is why the list is a backlog to work
     * through by eye rather than a set of bugs.
     */
    @Test
    void noNewIconMayShareItsSilhouetteWithOneWeAlreadyShip() throws Exception {
        // Measured on HEAD. Each entry is a real overlap, worst first.
        Set<String> known = new HashSet<>(List.of(
                "bag|orb",              // 88%
                "egg|machine",          // 83%
                "lamp|machine",         // 82%
                "egg|orb",              // 80%
                "machine|orb",          // 80%
                "bag|machine",          // 80%
                "food_round|machine",   // 78%
                "food_round|snowball",  // 78%
                "egg|lamp",             // 77%
                "block|vehicle",        // 76%
                "block|orb",            // 76%
                "bucket|machine",       // 76%
                "lamp|rocket",          // 76%
                "bag|egg",              // 75%
                "bottle|lamp",          // 75%
                "bucket|orb",           // 75%
                "coin|food_round",      // 75%
                "egg|rocket",           // 75%
                "food_round|lamp"));    // 75%

        Map<String, boolean[]> sil = new LinkedHashMap<>();
        for (WishIcons.Icon icon : originals()) {
            sil.put(icon.name(), silhouetteOf(icon.name()));
        }
        List<String> names = new ArrayList<>(sil.keySet());
        java.util.List<String> fresh = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String a = names.get(i);
                String b = names.get(j);
                int overlap = jaccard(sil.get(a), sil.get(b));
                String pair = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                if (overlap >= 75 && !known.contains(pair)) {
                    fresh.add(pair + " (" + overlap + "%)");
                }
            }
        }
        // Collected, not thrown on the first — "one icon collides" and "the new batch all collide"
        // need different fixes, and failing on the first cannot tell them apart.
        assertTrue(fresh.isEmpty(),
                "these icon pairs share 75%+ of their silhouette and are not on the known list: "
                + fresh + ". At 16x16 a new round thing is indistinguishable from every round "
                + "thing already shipped, which is how `orb` came to serve eggs, bombs and "
                + "snowballs. Give it a different OUTLINE — interior detail does not survive at "
                + "this size — or add the pair above with the measured number and a reason.");
    }

    /** Silhouette overlap as a percentage: shared drawn pixels over drawn pixels in either. */
    private static int jaccard(boolean[] a, boolean[] b) {
        int both = 0;
        int either = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] && b[k]) {
                both++;
            }
            if (a[k] || b[k]) {
                either++;
            }
        }
        return either == 0 ? 0 : both * 100 / either;
    }

    /** Which pixels are drawn at all, read from the shipped sprite rather than from a copy of it. */
    private static boolean[] silhouetteOf(String name) throws Exception {
        var url = WishIconsTest.class.getResource(
                "/assets/moddingfromamod/textures/item/wish/" + name + ".png");
        assertNotNull(url, name + " ships no sprite");
        var img = javax.imageio.ImageIO.read(url);
        var detail = WishIconsTest.class.getResource(
                "/assets/moddingfromamod/textures/item/wish/" + name + "_detail.png");
        var det = detail == null ? null : javax.imageio.ImageIO.read(detail);
        boolean[] out = new boolean[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean on = (img.getRGB(x, y) >>> 24) != 0
                        || (det != null && (det.getRGB(x, y) >>> 24) != 0);
                out[y * 16 + x] = on;
            }
        }
        return out;
    }

    /** Lookup is by the child's word, and an unknown word must NOT quietly become icon 1. */
    @Test
    void anUnknownIconIsRefusedRatherThanDefaulted() {
        assertEquals(1, WishIcons.byName("machine").id());
        assertEquals(17, WishIcons.byName("SWORD").id(), "matching is case-insensitive");
        assertEquals(10, WishIcons.byName("  wand ").id(), "and tolerant of stray spaces");
        assertNull(WishIcons.byName("teleporter"), "an unknown icon must be refused, not defaulted — "
                + "a silent default is a decision made after the wish text is gone (playbook 4f)");
        assertNull(WishIcons.byName(null));
    }

    /**
     * The hand-drawn 16 px originals (an earlier issue's rules). The library is 32 px Muse art whose
     * pictures differ by interior line and colour, which DOES survive at that size; its own
     * checks are IconLibraryTest's.
     */
    private static List<WishIcons.Icon> originals() {
        List<WishIcons.Icon> out = new ArrayList<>();
        for (WishIcons.Icon icon : WishIcons.all()) {
            // Still a 16 px sprite: neither in the drawn library nor redrawn (art/icons/originals).
            var url = WishIconsTest.class.getResource(
                    "/assets/moddingfromamod/textures/item/wish/" + icon.name() + ".png");
            try {
                if (IconSprites.drawnByName(icon.name()) == null && url != null
                        && javax.imageio.ImageIO.read(url).getWidth() == 16) {
                    out.add(icon);
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

}
