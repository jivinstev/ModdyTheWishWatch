# Gates A/B for Minecraft 1.21.8 (rung 0 of #6)

**Gate C now:** Port CI runs core's rig films (`tools/gate-c-rig.sh`, every film) as Gate C on the full environment,
declared in [`gate-c.json`](gate-c.json), from the first release built with it on. Each release's `gates-full.md`
records the row `Gate C -- fork's own (tools/gate-c-rig.sh, release set) | PASS`. The record below is the rung 0 run
of `7f5e22c`, kept as it was: Gate C was open then.

Not MMVU's `GATES.md`. That file records a green full run only, Gate C included, and this record is
not one. The table below is MMVU `ci-gates.py`'s own summary of `fork-port.py --target 1.21.8
--from-stage measure --gates ab --modid moddingfromamod`, copied unedited. MMVU here is origin/main
474c18e plus MMVU PRs #249-#251 and two local fixes, sent upstream: the moves stage on a port branch
above the base, and a null OpenAL device for a headless client. In the same run, fork-port's audit
stage reported `all targets match` (62 mixin targets) and checked 246 event listeners.

Gates for `moddingfromamod` at `7f5e22c` for Minecraft 1.21.8 (full environment) -- run by CI with no model and no fixes applied:

| check | result | detail |
|---|---|---|
| author's own build (`build`) | PASS | moddingfromamod-1.0.5+mc1.21.8.jar |
| Gate A -- mixin-config integrity | PASS | 6220 test(s) |
| Data -- nothing NeoForge would silently ignore | PASS | 1.21-clean |
| Payloads -- every StreamCodec.unit payload has equal instances | PASS | audit-unit-codecs: checked 1530 file(s), 0 finding(s) |
| Data loads -- the server rejected none of the mod's data files | PASS | no rejection logged |
| Gate B -- headless server loads the mod, GameTests | PASS | 1372 required test(s) passed |

Not covered by any automated gate: gameplay by a person.

## Gate C (real client) at rung 0: open, not passed (closed since by the rig films, above)

MMVU's Gate C runs `runClient -Pboottest -Ptestmode=launch|spawn|battle|gauntlet` and waits for a
`MODDINGFROMAMOD_BOOT_TEST: PASS|FAIL` line. Nothing in this repository prints that line: the client boot
harness is not in the public tree, and MMVU's injected harness wires ModDevGradle builds only.

On `7f5e22c` the client did boot:
- OpenAL started on the null device, and every atlas loaded.
- A thread dump showed the render thread presenting frames at the title screen.

With no verdict line, the gate stopped it after 420 s. The ways to close it are on #6 (an MMVU Gate C through its
carrier jar, or the boot harness brought into this repository), for the owner to decide.

Separate real-client evidence, which is not a Gate C pass: the owner's pre-release validation ran MMVU
carrier playtests in a real client on core 1.0.4 and 1.0.5.
