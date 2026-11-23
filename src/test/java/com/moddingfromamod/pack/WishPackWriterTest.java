package com.moddingfromamod.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.datapack.VanillaCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pack a stock client is pushed — and above all, the NEGATIVE.
 *
 * <p>an earlier issue's regression risk is not "does the pack work", it is <b>"does pushing one change
 * anything for the client that already has the mod"</b>. That question is settled here rather than
 * by a client run, because it is structural: a modded client resolves the carrier's artwork from
 * the mod's own shipped {@code assets/minecraft/items/leather_horse_armor.json}, and if the
 * pack's copy of that file selects the same picture for the same {@code custom_model_data} then the
 * push cannot draw anything different. (Until step 3 this compared the two
 * {@code models/item/} override tables; the pack no longer writes one.) A Gate C run could only ever sample one icon; this compares
 * all of them, and it keeps holding.
 */
class WishPackWriterTest {

    private static final Path SHIPPED =
            Path.of("src/main/resources/assets/minecraft/items/leather_horse_armor.json");

    private static JsonObject json(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * (threshold -> the picture its range_dispatch entry draws), by NAME so namespaces may differ
     * (the jar's models are {@code moddingfromamod:}, the pack's {@code wishpack:}).
     */
    private static List<String> selections(JsonObject definition) {
        JsonArray entries = definition.getAsJsonObject("model").getAsJsonArray("entries");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonObject e = entries.get(i).getAsJsonObject();
            String id = IconNodes.pictureModel(e.getAsJsonObject("model"));
            out.add(e.get("threshold").getAsInt() + " -> " + id.substring(id.lastIndexOf('/') + 1));
        }
        return out;
    }

    private static Path definition(Path pack) {
        return pack.resolve(VanillaItemPack.itemDefinitionPath(WishPackWriter.BASE_ITEM));
    }

    /**
     * <b>The reason the writer had to leave the client, asserted rather than believed.</b>
     *
     * <p>The vanilla wish watch ships ON by default, and its "wish for something new" cell wears
     * Moddy's own artwork at a reserved {@code custom_model_data} — which a client with no mod can
     * only get from the <b>pushed</b> pack. Until this writer existed, the only thing that could
     * build a pack containing him was a {@code Dist.CLIENT} class, so the panel's own button had no
     * face on the one route the panel exists for.
     *
     * <p>Deliberately NOT covered by {@link #aModdedClientIsUnaffectedByThePush}: that compares two
     * files, so it stays green if Moddy is missing from <b>both</b> of them. See
     * {@code docs/VANILLA_WATCH_DECISIONS.md} D4.
     */
    @Test
    @DisplayName("a SERVER-built pack carries Moddy's own face, sprite and all")
    void theServerBuiltPackCarriesModdy(@TempDir Path tmp) throws Exception {
        WishPackWriter.write("on", tmp);
        JsonObject model = json(definition(tmp));
        assertTrue(selections(model).contains(
                        VanillaCarrier.WATCH_MODEL_DATA + " -> " + VanillaCarrier.WATCH_ICON),
                "a client with no mod reads Moddy's face out of the pushed pack, so a pack a "
                        + "SERVER builds without him leaves the panel's own button blank: "
                        + selections(model));
        assertTrue(Files.exists(tmp.resolve(VanillaItemPack.texturePath(
                        VanillaCarrier.WATCH_ICON))),
                "the entry selects Moddy's model but his sprite never rode along — the pack "
                        + "renders him as the missing texture, which is a picture of the wrong "
                        + "thing rather than an error");
    }

    /**
     * <b>: the wish anvil's "Wish!" button is an ANIMATED sprite, on both bindings.</b>
     *
     * <p>Three ways it can arrive broken and still render something: the {@code range_dispatch}
     * entry missing (an old stack falls back to the carrier), the {@code .png.mcmeta} missing (a
     * stock client draws the whole 16x128 strip squashed into one slot — eight wands and no error),
     * or the native {@code items/icon/} definition missing (a new stack draws nothing). Each is
     * asserted by name.
     */
    @Test
    @DisplayName("the pack carries the animated wish wand: sprite, mcmeta, range_dispatch AND item_model")
    void thePackCarriesTheAnimatedWishWand(@TempDir Path tmp) throws Exception {
        WishPackWriter.write("on", tmp);
        JsonObject def = json(tmp.resolve(VanillaItemPack.itemDefinitionPath(
                WishPackWriter.BASE_ITEM))).getAsJsonObject("model");
        boolean dispatched = false;
        for (var e : def.getAsJsonArray("entries")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("threshold").getAsFloat() == VanillaCarrier.WISH_WAND_MODEL_DATA) {
                dispatched = o.getAsJsonObject("model").get("model").getAsString()
                        .endsWith("/" + VanillaCarrier.WISH_WAND_ICON);
            }
        }
        assertTrue(dispatched, "the >=1.21.4 range_dispatch does not bind the wand");
        assertTrue(Files.exists(tmp.resolve(NativeItemModels.definitionPath(
                        VanillaCarrier.WISH_WAND_ICON))),
                "no items/icon/wish_wand.json, so a stack naming its item_model draws nothing");

        Path png = tmp.resolve(VanillaItemPack.texturePath(VanillaCarrier.WISH_WAND_ICON));
        assertTrue(Files.exists(png), "the wand's sprite never rode along");
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(png.toFile());
        assertEquals(16, img.getWidth(), "an item sprite is 16 wide");
        assertEquals(8 * 16, img.getHeight(), "the wand is an 8-frame strip of 16x16 frames");
        Path mcmeta = Path.of(png + ".mcmeta");
        assertTrue(Files.exists(mcmeta),
                "the strip rode along WITHOUT its .png.mcmeta, so a stock client draws all eight "
                        + "frames squashed into one slot instead of animating them");
        JsonObject anim = json(mcmeta).getAsJsonObject("animation");
        assertTrue(anim != null && anim.get("frametime").getAsInt() >= 1,
                "the mcmeta has no animation block: " + Files.readString(mcmeta));
        // Every frame must be a real picture: an all-transparent frame is a blink, not a sparkle.
        for (int f = 0; f < 8; f++) {
            int opaque = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if ((img.getRGB(x, f * 16 + y) >>> 24) == 0xFF) {
                        opaque++;
                    }
                }
            }
            assertTrue(opaque >= 60, "frame " + f + " has only " + opaque + " opaque pixels");
        }
    }

    @Test
    @DisplayName("the pushed pack selects exactly what a modded client already resolves locally")
    void aModdedClientIsUnaffectedByThePush(@TempDir Path tmp) throws Exception {
        WishPackWriter.write("on", tmp);
        JsonObject fromPack = json(definition(tmp));
        assertEquals(selections(json(SHIPPED)), selections(fromPack),
                "a client WITH the mod resolves the carrier out of the mod's own shipped definition, "
                        + "and a client without one gets these entries from the pushed pack. If the "
                        + "two ever select different icons for the same custom_model_data then "
                        + "pushing the pack changes what a modded child sees — which is the one "
                        + "regression this whole route must not have.");
    }

    @Test
    @DisplayName("the zip hash is deterministic — a rejoin is only cheap if the sha1 is stable")
    void theHashIsStableAcrossWrites(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        WishPackWriter.write("on", a);
        WishPackWriter.write("on", b);
        String ha = WishPackWriter.zip(a, tmp.resolve("a.zip"));
        String hb = WishPackWriter.zip(b, tmp.resolve("b.zip"));
        assertEquals(ha, hb,
                "the client caches a pushed pack by its sha1, so an unstable hash turns every "
                        + "rejoin into a fresh download. Measured: with a stable hash a warm rejoin "
                        + "fetched 0 bytes.");
        assertEquals(40, ha.length(), "a sha1 is 40 hex characters; " + ha + " is not one");
    }

    @Test
    @DisplayName("the two break modes really break it — an A/B whose arms agree is not an oracle")
    void theBreakModesActuallyDiffer(@TempDir Path tmp) throws Exception {
        Path on = tmp.resolve("on");
        Path shift = tmp.resolve("shift");
        Path drop = tmp.resolve("drop");
        WishPackWriter.write("on", on);
        WishPackWriter.write("shift", shift);
        WishPackWriter.write("drop", drop);

        List<String> real = selections(json(definition(on)));
        List<String> shifted = selections(json(definition(shift)));
        JsonObject dropped = json(definition(drop));

        assertFalse(real.isEmpty(), "the real pack selects nothing, so nothing below means anything");
        assertNotEquals(real, shifted,
                "`shift` is the more interesting break: nothing is missing and every model is real, "
                        + "each icon simply wears its neighbour's artwork — which renders "
                        + "beautifully and is completely wrong");
        assertTrue(selections(dropped).isEmpty(),
                "`drop` must leave NO range_dispatch entries, so every wished thing falls through "
                        + "to plain horse armour — the state a client with no pack at all is in");
        assertEquals("minecraft:item/" + WishPackWriter.BASE_ITEM,
                dropped.getAsJsonObject("model").getAsJsonObject("fallback").get("model").getAsString(),
                "`drop` keeps the plain carrier's fallback, or it breaks the carrier too");
    }

    @Test
    @DisplayName("every sprite a binding names is actually in the pack")
    void thePackCarriesTheArtworkItPromises(@TempDir Path tmp) throws Exception {
        WishPackWriter.write("on", tmp);
        List<VanillaItemPack.Icon> icons = WishPackWriter.icons("on");
        assertFalse(icons.isEmpty(), "no icons — the pack would be empty and the test vacuous");
        for (String sprite : WishPackWriter.sprites(icons)) {
            assertTrue(Files.exists(tmp.resolve(VanillaItemPack.texturePath(sprite))),
                    "icon art " + sprite + " is named by the model and absent from the pack, so "
                            + "a stock client draws the missing texture for it");
        }
    }
}
