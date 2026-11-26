#!/usr/bin/env bash
# CI only: run a command (a Gradle build) and, when it failed for want of a download (a busy repository answering 429, a
# dropped connection), wait a minute and run it once more. Any other failure is returned as it is, at once.
# docs/GATE_B.md, "Downloads in CI".
set -o pipefail
log="$(mktemp)"
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ] && grep -qE 'status code 429|Could not GET |Could not HEAD |Could not resolve all|Connection reset|Read timed out' "$log"; then
  echo "retry-on-download: the build failed for want of a download; running it once more in 60 s"
  sleep 60
  "$@"
  status=$?
fi
rm -f "$log"
exit "$status"
