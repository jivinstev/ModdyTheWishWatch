package com.moddingfromamod.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The URL a third party hands us is made safe before it is fetched. */
class SkinFetchTest {

    private static final String HASH = "ea3e87971a6fb8e3d976c7dab632572ee200d282a44eda5d0a2a3c4152464d2f";

    @AfterEach
    void reset() {
        SkinFetch.reset();
    }

    @Test
    @DisplayName("the profile's http:// texture URL is UPGRADED to https (plain http is refused)")
    void httpIsUpgraded() {
        assertEquals("https://textures.minecraft.net/texture/" + HASH,
                SkinFetch.textureUri("http://textures.minecraft.net/texture/" + HASH).toString());
        assertEquals("https://textures.minecraft.net/texture/" + HASH,
                SkinFetch.textureUri("https://textures.minecraft.net/texture/" + HASH).toString());
    }

    @Test
    @DisplayName("anything off textures.minecraft.net/texture/<hash> is never fetched")
    void otherUrlsAreRefused() {
        for (String bad : new String[]{
                "https://evil.example/texture/" + HASH,
                "https://textures.minecraft.net.evil.example/texture/" + HASH,
                "https://textures.minecraft.net:8080/texture/" + HASH,
                "https://user@textures.minecraft.net/texture/" + HASH,
                "https://textures.minecraft.net/texture/" + HASH + "?x=1",
                "https://textures.minecraft.net/other/" + HASH,
                "https://textures.minecraft.net/texture/../../etc/passwd",
                "file:///textures.minecraft.net/texture/" + HASH,
                "ftp://textures.minecraft.net/texture/" + HASH,
                "",
                "not a url at all"}) {
            assertNull(SkinFetch.textureUri(bad), bad);
        }
        assertNull(SkinFetch.textureUri(null));
    }

    @Test
    @DisplayName("a name that cannot be a username is 'no such player' without asking anybody")
    void anImpossibleNameIsNotAsked() {
        SkinFetch.useSource(n -> {
            throw new AssertionError("asked about " + n);
        });
        assertInstanceOf(SkinFetch.NoSuchPlayer.class, SkinFetch.fetch("Seth Bling"));
        assertInstanceOf(SkinFetch.NoSuchPlayer.class, SkinFetch.fetch("../etc"));
        assertInstanceOf(SkinFetch.NoSuchPlayer.class, SkinFetch.fetch(""));
        assertInstanceOf(SkinFetch.NoSuchPlayer.class, SkinFetch.fetch(null));
    }

    @Test
    @DisplayName("a source that throws is a Failed, never an exception into the tool")
    void aThrowingSourceIsAFailure() {
        SkinFetch.useSource(n -> {
            throw new IllegalStateException("boom");
        });
        assertInstanceOf(SkinFetch.Failed.class, SkinFetch.fetch("BlockyBob"));
    }
}
