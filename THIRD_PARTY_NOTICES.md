# Third-party notices

The Moddy mod bundles the components below. Each keeps its own license, and each license's full
text is in [`third_party/`](third_party/), which every jar also carries at `META-INF/third_party/`.
Audited 2026-11-16; re-checked 2026-11-20 against the versions inside a built jar
(`META-INF/jarjar/metadata.json`, the CBScript zip) and the license each project publishes at that
version.

| Component | How Moddy uses it | License | Notice obligations |
|---|---|---|---|
| **CBScript** by SethBling (https://github.com/SethBling/cbscript), via Moddy's fork `jivinstev/moddy-cbscript` | bundled (`tools/cbscript.zip`) | **No open-source license.** Used in Moddy and modified for it **with the author's permission** (verbal, November 2026). The original code remains the copyright of its author. Moddy's own changes in the fork are MPL-2.0. | Credit the author; carry the fork's `NOTICE`: `licenses/cbscript.NOTICE.txt` in the jar. |
| PLY (Python Lex-Yacc) 3.11 by David Beazley, unmodified | bundled inside the CBScript zip | BSD-3-Clause | Keep the copyright notice and license text: at the top of each `ply/*.py`, and `third_party/ply-3.11.LICENSE.txt`. |
| TwelveMonkeys ImageIO 3.15.3 (`imageio-webp` and its `common-*` / `imageio-core` / `imageio-metadata` dependencies) | bundled (jarJar) | BSD-3-Clause (its POM and `LICENSE.txt` at tag `twelvemonkeys-3.15.3`) | Keep the copyright notice and license text: `licenses/twelvemonkeys.LICENSE.txt` in the jar. |
| ONNX Runtime 1.31.1 (Java) by Microsoft (the version the jar nests; the build compiles against 1.32.0, also MIT) | bundled (jarJar; natives for Windows x64, macOS arm64, Linux x64 and arm64 — no Intel-Mac native) | MIT (its POM and the 1.31.1 release's `LICENSE`) | Keep the MIT notice (`apprentice/onnxruntime.NOTICE.txt`) and the nested jar's own `ThirdPartyNotices.txt` (protobuf, zlib, Intel MKL and others), copied to `third_party/`. |
| all-MiniLM-L6-v2 (sentence-transformers), as ONNX | **bundled in the store jar** (`apprentice/model/`, D13; built by `tools/store-release.sh`), copied to the game folder at first start, never downloaded by the mod | Apache-2.0 (model card, read 2026-11-16; the files are sha-pinned and unchanged since); its base, MiniLM-L6-H384-uncased (Microsoft), MIT | Attribution and the Apache-2.0 text: `apprentice/sentence-model.NOTICE.txt` and `apprentice/sentence-model.LICENSE.txt`. |
| SCOWL word list (Kevin Atkinson), Debian `wamerican-small` 2020.12.07 (the 3–12-letter lower-case words, identical to that package's list) | bundled (`apprentice/english-words.txt`) | SCOWL's permissive license, which includes the Ispell (BSD-style) and other notices | Keep `apprentice/english-words.NOTICE.txt`, which reproduces Debian's copyright file in full (until 2026-11-20 it stopped short of the Ispell notice). |

**Not shipped in the public mod**: GeckoLib (disabled, `uses_geckolib=false`); the private world's
starter island (BPR02's SkyBlock, MIT) and the SkyBlock datapacks, which stay in the private
repository.

**Minecraft** and its assets are © Mojang. Moddy contains no Minecraft code or textures. A
2026-11-16 check found none of the 574 shipped images to be a copy of any of 1.21.8's 3,261 vanilla
textures.
