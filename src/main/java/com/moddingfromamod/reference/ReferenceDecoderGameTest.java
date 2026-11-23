package com.moddingfromamod.reference;

import net.minecraft.network.chat.Component;
import java.util.Arrays;

import javax.imageio.ImageIO;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>Gate B — can the game itself read a WebP?</b>
 *
 * <p>The unit test for this ({@code WebPDecoderTest}) runs on a plain classpath, where the decoder
 * is an ordinary dependency and {@code ImageIO}'s ServiceLoader scan finds it without help. That
 * is not the environment the mod runs in. In the game the reader arrives from a <b>JarJar-nested
 * jar</b> under Minecraft's <b>transforming classloader</b>, and ImageIO's automatic scan is
 * exactly the kind of thing that quietly finds nothing there — which is why
 * {@link ReferenceImages} registers the SPI explicitly instead of trusting it.
 *
 * <p>So this is the gate that proves the registration survives the real classloader. Without it
 * the failure is silent and total in the one place that matters: every Fandom-hosted character
 * falls back to the model's remembered palette, with a green Gate A the whole time.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class ReferenceDecoderGameTest {

    @GameTest(template = "empty_test")
    public static void webPDecodesInsideTheGame(GameTestHelper helper) {
        helper.assertTrue(ReferenceImages.TYPES.contains("image/webp"),
                Component.literal("image/webp is missing from ReferenceImages.TYPES inside the game, so every "
                        + "image on every Fandom wiki is refused before it is downloaded. Either "
                        + "the nested imageio-webp jar is not on the runtime classpath, or the "
                        + "IIORegistry registration did not survive the transforming classloader. "
                        + "ImageIO can read: " + Arrays.toString(ImageIO.getReaderMIMETypes())));
        helper.assertTrue(ReferenceImages.acceptHeader().contains("image/webp"),
                Component.literal("we can decode WebP but do not advertise it, so a wiki that content-negotiates "
                        + "will send something else"));
        helper.succeed();
    }

    /**
     * The alpha rule, live. A page whose artwork we exclude here is a character with no measured
     * palette, and that failure has no other symptom.
     */
    @GameTest(template = "empty_test")
    public static void artworkFilterAcceptsAlphaFormats(GameTestHelper helper) {
        helper.assertTrue(WikiArtwork.ALPHA_FORMATS.contains("image/webp"),
                Component.literal("Fandom's API declares image/png and its CDN serves image/webp; a PNG-only "
                        + "filter passes the declaration and the download is then refused"));
        helper.assertTrue(!WikiArtwork.ALPHA_FORMATS.contains("image/jpeg"),
                Component.literal("a JPEG has no alpha, so the palette would measure its background"));
        helper.succeed();
    }
}
