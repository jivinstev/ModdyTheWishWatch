package com.moddingfromamod.redstone;

import java.util.List;

/**
 * A self-contained mechanism, already placed in the world's coordinates: its cells (with their own internal wiring,
 * casing and riser), what must change when it is powered, and the channel ports it takes its signal from. A port is a
 * position at channel depth where a wire may arrive; the module's own wire continues from there.
 */
public record Module(String kind, List<RsCell> cells, List<Expect> expects, List<RsPos> ports, int channelY,
                     List<String> notes) {
}
