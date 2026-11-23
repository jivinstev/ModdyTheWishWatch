#!/usr/bin/env bash
# fetch-apprentice-model.sh — get the sentence model the Apprentice's trained layer reads with.
#
#   ./tools/fetch-apprentice-model.sh [DIR]        # default DIR: .apprentice-model/ (gitignored)
#
# Two files land in DIR, each checked against a pinned sha256 before it is put there:
#   model.onnx   all-MiniLM-L6-v2 as ONNX (about 90 MB; Apache-2.0, see src/main/resources/apprentice/sentence-model.NOTICE.txt)
#   vocab.txt    its WordPiece vocabulary
# The layer in the jar (src/main/resources/apprentice/layer.json) names the same two hashes, and the game refuses
# any other file. A different model means retraining: `python3 tools/apprentice-layer.py train`.
#
# Where from: the public archive Chroma publishes for its default embedding function. To fetch from somewhere else
# (your own bucket, a mirror), set MODDY_APPRENTICE_MODEL_URL to either
#   - an archive (.tar.gz) holding onnx/model.onnx and onnx/vocab.txt, or
#   - a folder URL that serves model.onnx and vocab.txt directly, or
#   - a gs:// folder holding the two files (copied with gcloud).
# The hashes are checked either way, so the source does not have to be trusted.
set -euo pipefail
cd "$(dirname "$0")/.."

DIR="${1:-.apprentice-model}"
URL="${MODDY_APPRENTICE_MODEL_URL:-https://chroma-onnx-models.s3.amazonaws.com/all-MiniLM-L6-v2/onnx.tar.gz}"
MODEL_SHA256=4f148ba8ae9c2c7fbee4af2b132db8d06c6a6545b47fc83bbb98c3d22b8393e6
VOCAB_SHA256=07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3

sha() { sha256sum "$1" | cut -d' ' -f1; }
ok() { [ -f "$DIR/model.onnx" ] && [ -f "$DIR/vocab.txt" ] && [ "$(sha "$DIR/model.onnx")" = "$MODEL_SHA256" ] && [ "$(sha "$DIR/vocab.txt")" = "$VOCAB_SHA256" ]; }

if ok; then
  echo "apprentice model: already in $DIR (sha256 ok)"
  exit 0
fi
TMP="$(mktemp -d)" || exit 1
trap 'rm -rf "$TMP"' EXIT
get() { curl -fsSL --retry 3 --retry-delay 2 -o "$2" "$1" || { echo "apprentice model: could not download $1 (is its host allowed out of here?)" >&2; exit 2; }; }
case "$URL" in
  gs://*)
    # A cloud bucket holding a pinned copy: fetched as the machine's gcloud account.
    command -v gcloud >/dev/null || { echo "apprentice model: $URL needs gcloud, which is not installed here" >&2; exit 2; }
    gcloud storage cp --quiet "${URL%/}/model.onnx" "${URL%/}/vocab.txt" "$TMP/" >/dev/null 2>"$TMP/err" \
      || { echo "apprentice model: could not copy from $URL: $(tail -n 1 "$TMP/err")" >&2; exit 2; } ;;
  *.tar.gz|*.tgz)
    get "$URL" "$TMP/model.tar.gz"
    tar -xzf "$TMP/model.tar.gz" -C "$TMP" onnx/model.onnx onnx/vocab.txt
    mv "$TMP/onnx/model.onnx" "$TMP/onnx/vocab.txt" "$TMP/" ;;
  *)
    get "${URL%/}/model.onnx" "$TMP/model.onnx"
    get "${URL%/}/vocab.txt" "$TMP/vocab.txt" ;;
esac
[ "$(sha "$TMP/model.onnx")" = "$MODEL_SHA256" ] || { echo "apprentice model: model.onnx from $URL is not the pinned file (sha256 $(sha "$TMP/model.onnx"))" >&2; exit 1; }
[ "$(sha "$TMP/vocab.txt")" = "$VOCAB_SHA256" ] || { echo "apprentice model: vocab.txt from $URL is not the pinned file (sha256 $(sha "$TMP/vocab.txt"))" >&2; exit 1; }
mkdir -p "$DIR"
mv "$TMP/model.onnx" "$TMP/vocab.txt" "$DIR/"
echo "apprentice model: fetched to $DIR (sha256 ok)"
