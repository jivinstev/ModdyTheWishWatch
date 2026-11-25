# gate-data

Data only, written by the workflows; never merged anywhere. See docs/GATE_B.md on the default branch.

- `runs/<YYYY-MM>/<started>-<kind>-<commit>.json.gz`: one Gate B run each (nightly, release, pull request), never
  edited after it is written. Read them with `python3 tools/gate/gate.py report --data <a checkout of this branch>`.
