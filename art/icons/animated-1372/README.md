# Animated Wish Bag and Moddy icons: the sources

The owner drew both sheets with ChatGPT on 2026-10-04, from the prompts 's thread (in the same format as `docs/overlay-art-prompts.md`).

| file | what |
|---|---|
| `wish_bag-sheet-chatgpt.png`, `wish_watch-sheet-chatgpt.png` | the sheets as delivered: 1254×1254, a 4×4 grid of 16 frames, on a magenta key |
| `wish_bag-strip-steadied.png`, `wish_watch-strip-steadied.png` | 32×512 vertical strips, the form Minecraft animates with a `.png.mcmeta` |

## How the strips were made
1. **Cut** with `tools/import-overlay-sheet.py <name> <sheet> --size 32` (frametime 3 for the bag, 2 for the watch). Each art pixel is the majority colour of its block, and magenta is treated as transparent.
2. **Steadied.** An image model redraws every frame slightly differently: in the raw cut, 474 of the bag's ~454 visible pixels and 480 of the watch's 479 changed between frames, so the whole icon shimmered. The owner chose steadied versions:
   - **Watch:** each pixel of the case is fixed to its most common colour across the 16 frames. Only the face animates, from the model's own frames, and only from one pixel inside the ring. Sparkles may appear only at least 2 px clear of the case.
   - **Bag:** the whole bag is held to the frame closest to all the others (frame 4). A diagonal glint sweeps the star over frames 0–7. Sparkles may appear only where they touch nothing of the bag.
3. **Verified:** across all 16 frames, **0 pixels change on either outline**. The watch's moving pixels are 139 in the face and 12 in the sparkles; the bag's are 38, in the star glint and the sparkles.
4. **Sparkles removed (2026-10-08).** In play, anything that added shape outside the icon was distracting, so every pixel outside the icon's own body is cleared in all 16 frames:
   - **Watch:** the 12 blinking sparkle pixels at its top right.
   - **Bag:** the 8 blinking and 3 constant sparkle pixels above it, plus the pale cross above its tie (rows 0–5).

   The body is the largest group of pixels opaque in every frame, so the watch face's swirl and the bag's star glint, both inside it, are untouched. The strips here and the shipped textures are still byte-identical.

## Where they went
- `src/main/resources/assets/moddingfromamod/textures/item/wish_bag.png` / `wish_watch.png` are these strips byte for byte, each with a `.png.mcmeta` (`{"animation":{"frametime":3}}` / `2`).
- The item pack the server pushes to stock clients carries both, with their `.mcmeta`, on both bindings — `AnimatedPinnedIconsTest`.
- Photographed animating on 1.21.1, 1.21.4 and 26.2 with `./tools/forward-compat-gate.sh --all --pinned`: `docs/evidence-1372/`.
