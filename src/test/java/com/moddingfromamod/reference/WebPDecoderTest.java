package com.moddingfromamod.reference;

import javax.imageio.ImageIO;
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Can this JVM read a WebP?</b>
 *
 * <p>A test for a DEPENDENCY, which is unusual and is the point. Java's own {@code ImageIO} cannot
 * decode WebP, and {@link ReferenceImages#TYPES} is deliberately the intersection of what we would
 * accept with what we can actually read — so with no decoder on the classpath the format is
 * dropped <b>silently</b> and every Fandom-hosted character falls back to the model's remembered
 * palette. There is no error, no crash and no log line at the point it matters.
 *
 * <p>Measured, which is why this exists: Fandom's thumbnailer serves {@code image/webp} for every
 * image on every wiki it hosts, regardless of the {@code Accept} header, while its own API
 * declares the file as {@code image/png}. Omega Lad's only artwork is a 407x613 RGBA cut-out we
 * could reach over the network and could not decode, and the symptom was indistinguishable from
 * "that character has no artwork".
 */
class WebPDecoderTest {

    @Test
    @DisplayName("WebP is decodable, so Fandom-hosted characters can have a measured palette")
    void webPIsDecodable() {
        assertTrue(ReferenceImages.TYPES.contains("image/webp"),
                "ReferenceImages.TYPES does not contain image/webp, so every image on every "
                        + "Fandom wiki will be refused before it is downloaded — and refused "
                        + "SILENTLY, which reads as the character having no artwork. Either the "
                        + "imageio-webp dependency was dropped from build.gradle, or the SPI is "
                        + "no longer being registered. ImageIO can currently read: "
                        + Arrays.toString(ImageIO.getReaderMIMETypes()));
    }

    @Test
    @DisplayName("the Accept header offers only what we can decode")
    void acceptHeaderMatchesWhatWeCanRead() {
        // A browser-shaped Accept invites a server to negotiate its way to a format we cannot
        // read. This is the same bug as above from the other end.
        String accept = ReferenceImages.acceptHeader().toLowerCase(Locale.ROOT);
        for (String type : ReferenceImages.TYPES) {
            assertTrue(accept.contains(type), "we accept " + type + " but do not advertise it");
        }
        assertTrue(accept.contains("image/webp"), "WebP is decodable but not advertised, so a "
                + "wiki that content-negotiates will send us something else");
    }

    @Test
    @DisplayName("alpha is the subject mask, so WikiArtwork takes WebP and still refuses JPEG")
    void alphaFormatsAreTheRealRule() {
        // The filter used to name PNG, which is the same rule written as one format — and that
        // cost every Fandom character, because the API declares image/png and the CDN serves
        // image/webp. The reason to exclude JPEG is that it has no alpha channel, so its
        // background is counted and the palette becomes the palette of a white rectangle.
        assertTrue(WikiArtwork.ALPHA_FORMATS.contains("image/png"));
        assertTrue(WikiArtwork.ALPHA_FORMATS.contains("image/webp"));
        assertTrue(!WikiArtwork.ALPHA_FORMATS.contains("image/jpeg"),
                "a JPEG has no alpha, so it measures its own background");
    }
}
