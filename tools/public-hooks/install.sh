#!/usr/bin/env bash
# Install the leak check in a clone of this repository: git runs tools/public-hooks/pre-commit (credentials,
# tools/secret-scan.py; licences, tools/licence-scan.py) before every commit and tools/public-hooks/pre-push (private
# work and credentials; licences) before every push.
#
#   ./tools/public-hooks/install.sh                    # built-in rules only (what any contributor gets)
#   ./tools/public-hooks/install.sh --forbidden FILE   # also refuse the terms in a forbidden list kept outside
#                                                      #   this repository (the maintainers' clones)
set -euo pipefail
top="$(git rev-parse --show-toplevel)"
case "${1:-}" in
    "") ;;
    --forbidden)
        f="$(cd "$(dirname "${2:?--forbidden needs a file}")" && pwd)/$(basename "$2")"
        [ -f "$f" ] || { echo "install: no such forbidden list: $f" >&2; exit 2; }
        git -C "$top" config moddy.forbidden "$f" ;;
    *) echo "install: unknown argument $1" >&2; exit 2 ;;
esac
chmod +x "$top/tools/public-hooks/pre-push" "$top/tools/public-hooks/pre-commit"
git -C "$top" config core.hooksPath tools/public-hooks
echo "install: pre-commit secret and licence checks and pre-push leak and licence checks on in $top$(git -C "$top" config --get moddy.forbidden >/dev/null && echo ' (with a forbidden list)')"
