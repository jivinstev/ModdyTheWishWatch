# Public corpus fixtures (§1)

Small committed stand-ins for corpora that stay private: a test reads its corpus through
`CorpusSource` (`src/test/java/com/moddingfromamod/CorpusSource.java`), which gives it the real file
when `-Dmoddy.corpus.dir` names one, and the file here when nothing does.

- **Never a player's words.** Every row is synthetic or model-written. Nothing is copied from a private
  corpus, from `corpus/holdout/`, or from a sealed set.
- **Named as a stand-in.** A corpus named `real-x.jsonl` has its fixture at `sample-x.jsonl`.
- **Same code path, its own floor.** A test checks the same thing on both; a count measured on the real
  corpus is held only there (`CorpusSource.real()`).
- **Run the tests on these:** `./gradlew test -Pcorpus.fixture --tests <Class>`.

| fixture | stands in for | read by |
|---|---|---|
| `sample-wishes.jsonl` | `src/main/resources/corpus/real-wishes.jsonl` | `SafetyLexiconTest` |
| `compat/spelling-1.21.5.jsonl` | `corpus/compat/spelling-1.21.5.jsonl` | `SpellingCorpusTest` |
| `apprentice/corpus.jsonl` | `corpus/apprentice/corpus.jsonl` | `ApprenticeCorpusTest`, `ApprenticeWordsTest` |
| `apprentice/sensitivity/cases.jsonl` | `corpus/apprentice/sensitivity/cases.jsonl` (only `id` and `text`, the fields the Java reads) | `ApprenticeCorpusTest` |
| `apprentice/typos/cases.jsonl` | `corpus/apprentice/typos/cases.jsonl` | `ApprenticeCorpusTest` |
| `behaviour-choice/expected.jsonl` | `corpus/behaviour-choice/expected.jsonl`: 80 creatures drawn by `tools/behaviour-specs.py --seed 27080 --sealed corpus/behaviour-choice/specs*.jsonl` (none of the real specs), rule and choice from `tools/behaviour-train.py`'s reference over the committed model | `BehaviourChoiceTest` |
| `behaviour-choice/labels-fx.jsonl`, `behaviour-choice/floor.json` | the real labels and floor: here the labeller is a stand-in, the hand-written rule asked twice, and the floor is what the committed model scores against it | `BehaviourChoiceTest` |
| `synthetic-wishes/wishes.jsonl`, `synthetic-player/sample.json` | `corpus/synthetic-wishes/wishes.jsonl` and the pre-registered `corpus/synthetic-player/sample.json` | `WishReferenceFollowupTest` |
| `sample-builds.jsonl` | `src/main/resources/corpus/real-builds.jsonl`: every shipped preset building (builder-library and starter-library), one per row in the real corpus's shape; read by `CorpusSource.realBuilds()` | `BuildLightingTest`, `ComposedLightCensusTest` |
