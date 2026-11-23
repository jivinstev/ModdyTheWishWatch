package com.moddingfromamod.net;

import java.util.List;

/**
 * What a modded viewer is handed of a film path (the smooth film camera, 2026-11-16): the path, when it started,
 * which rig the viewer looks through, and each key's look entity. Plain data, so the film player builds and tests it
 * without the payload that carries it, which is the modded client's and private ({@link ClientChannels#filmPath}).
 *
 * @param lookIds per key, the network id of the entity its {@code look} resolved to on the server ({@link #NO_ENTITY}
 *                for a point look, or a selector that found nothing yet)
 */
public record FilmPathHandover(boolean active, String pathJson, long startGameTime, int rigId, List<Integer> lookIds) {

    public static final int NO_ENTITY = -1;

    /** Keys a path may carry to a client; a longer one is not handed over (the server still plays it). */
    public static final int MAX_KEYS = 8192;

    /** Characters of path JSON; a key written in full is about 150. */
    public static final int MAX_JSON = 2 * 1024 * 1024;

    /** The handover that ends a client's drawing of the path. */
    public static FilmPathHandover stop() {
        return new FilmPathHandover(false, "", 0, NO_ENTITY, List.of());
    }
}
