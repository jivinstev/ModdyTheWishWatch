# Cascade decisions (append-only; tools/fork-branches.py cascade)

| date | decision | file | version | choice | by | reasoning |
|---|---|---|---|---|---|---|
| 2026-11-26 | b569d97821bb | `src/main/resources/precompiled/manifest.json` | 1.21.10 | patch | default | the authors' comment taken, our version's fingerprint kept: the round's post-merge step (precompile-ready-mades.sh --store) regenerates the fingerprint from this version's compiler |
| 2026-11-26 | 981868f3863b | `src/main/resources/precompiled/ready-mades.json.gz` | 1.21.10 | keep | default | a generated binary pack a diff cannot express, compiled per version: ours kept, and the round's post-merge step (precompile-ready-mades.sh --store) regenerates it from this version's compiler; the authors' 1.21.8 pack would be the wrong version's output |
| 2026-11-26 | 9c45eec5e1ce | `src/main/resources/tools/cbscript.SOURCE` | 1.21.10 | patch | default | the authors' vendored-tree record taken (the zip is gone): it then describes the third_party/moddy-cbscript tree the merge brings; the round's post-merge step re-vendors java-port-<v> and rewrites it |
| 2026-11-26 | 1a0c327e0196 | `src/main/resources/tools/cbscript.zip` | 1.21.10 | patch | default | the authors' deletion taken: the compiler is the vendored Java tree now, so the per-version zip goes at every version (a full-index deletion, applied without the binary data) |
