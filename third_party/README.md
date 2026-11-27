# Third-party licence texts

The full licence text of every component the Moddy mod bundles, one file each. The table that says what each one is,
how it ships and what it requires is [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). Every jar the build makes
carries this directory at `META-INF/third_party/`.

| File | Component (version shipped) |
|---|---|
| `cbscript.NOTICE.txt` | CBScript by SethBling, via Moddy's fork (no open-source licence; used with the author's permission) |
| `fdlibm.NOTICE.txt` | fdlibm's notice (Sun Microsystems), for the formulas the CBScript compiler's sinh, cosh and tanh follow |
| `moddy-cbscript/` | the CBScript compiler (Java), vendored by `tools/refresh-cbscript-bundle.sh`: its `LICENSE`, `NOTICE.md` and `THIRD_PARTY.md` ship; its sources are compiled into the jar |
| `twelvemonkeys-3.15.3.LICENSE.txt` | TwelveMonkeys ImageIO 3.15.3 and its common libraries (BSD-3-Clause) |
| `onnxruntime-1.31.1.LICENSE.txt` | ONNX Runtime (Java) 1.31.1 (MIT) |
| `onnxruntime-1.31.1.ThirdPartyNotices.txt` | the notices ONNX Runtime 1.31.1 ships for the libraries it includes |
| `all-MiniLM-L6-v2.Apache-2.0.txt` | the all-MiniLM-L6-v2 sentence model, in the store jar (Apache-2.0) |
| `MiniLM-L6-H384-uncased.MIT.txt` | MiniLM-L6-H384-uncased (Microsoft), that model's base (MIT) |
| `scowl.LICENSE.txt` | the SCOWL word list, Debian `wamerican-small` 2020.12.07 (SCOWL's licence and the ones it includes) |
