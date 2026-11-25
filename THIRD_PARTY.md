# Third-party files

Every committed file that carries a copyright that is not ours, or is copied from someone else's work, has a row here:
where it came from, its licence and why it is here. `tools/licence-scan.py --tree` (CI and Gate A) fails a foreign
copyright in a file with no row, a row that matches no tracked file, and a licence that is not on the allow-list in
`tools/licence-rules.json` (see "Third-party code and text" in [CLAUDE.md](CLAUDE.md)). A path may be a glob. A
licence that reads `PENDING #<issue>` is a decision held for the owner: the scan prints it and passes.

What the jar SHIPS from others, and what each component requires, is [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md);
`./gradlew licenceCheck` (part of `check`, so of `build`) checks every library nested in the jar's `META-INF/jarjar/`.

| file | source | licence | why |
|---|---|---|---|
| `gradlew` | Gradle wrapper script, github.com/gradle/gradle | Apache-2.0 | the build's wrapper |
| `gradlew.bat` | Gradle wrapper script, github.com/gradle/gradle | Apache-2.0 | the build's wrapper on Windows |
| `gradle/wrapper/gradle-wrapper.jar` | Gradle wrapper, github.com/gradle/gradle | Apache-2.0 | downloads the pinned Gradle |
| `third_party/twelvemonkeys-3.15.3.LICENSE.txt` | TwelveMonkeys ImageIO 3.15.3, its `LICENSE.txt` | BSD-3-Clause | the licence text of the WebP decoder the jar nests |
| `src/main/resources/licenses/twelvemonkeys.LICENSE.txt` | the same text, carried in the jar | BSD-3-Clause | the licence travels with the library |
| `third_party/onnxruntime-1.31.1.LICENSE.txt` | ONNX Runtime 1.31.1 (Microsoft), its `LICENSE` | MIT | the licence text of the runtime the jar nests |
| `src/main/resources/apprentice/onnxruntime.NOTICE.txt` | ONNX Runtime's MIT notice, carried in the jar | MIT | the notice travels with the library |
| `third_party/onnxruntime-1.31.1.ThirdPartyNotices.txt` | the notices ONNX Runtime 1.31.1 ships for what it includes | PENDING #142 | names components under licences off the list (Intel Simplified Software License, Boost); whether the nested CPU build contains them is unconfirmed |
| `third_party/all-MiniLM-L6-v2.Apache-2.0.txt` | all-MiniLM-L6-v2 (sentence-transformers), the Apache-2.0 text | Apache-2.0 | the Apprentice's sentence model, in the store jar |
| `src/main/resources/apprentice/sentence-model.*.txt` | that model's licence and attribution, carried in the jar | Apache-2.0 | the notice travels with the model |
| `third_party/MiniLM-L6-H384-uncased.MIT.txt` | MiniLM-L6-H384-uncased (Microsoft), the model's base | MIT | the base model's licence |
| `third_party/ply-3.11.LICENSE.txt` | PLY (Python Lex-Yacc) 3.11 by David Beazley | BSD-3-Clause | PLY is inside the CBScript zip |
| `src/main/resources/tools/cbscript.zip` | CBScript by SethBling, via Moddy's fork moddy-cbscript (`cbscript.SOURCE`); PLY 3.11 inside (BSD-3-Clause) | PENDING #142 | the datapack compiler; no open-source licence, used with the author's verbal permission |
| `src/main/resources/licenses/cbscript.NOTICE.txt` | CBScript's notice, carried in the jar | PENDING #142 | the notice travels with the compiler |
| `third_party/cbscript.NOTICE.txt` | the same notice | PENDING #142 | the notice travels with the compiler |
| `src/main/resources/apprentice/english-words.txt` | SCOWL word list (Kevin Atkinson), Debian `wamerican-small` 2020.12.07 | PENDING #142 | the Apprentice's spelling check; SCOWL's own permissive licence, not a listed one |
| `src/main/resources/apprentice/english-words.NOTICE.txt` | Debian's copyright file for that list, in full | PENDING #142 | the notice travels with the list |
| `third_party/scowl.LICENSE.txt` | SCOWL's licence and the notices it includes | PENDING #142 | the notice travels with the list |
