#!/usr/bin/env python3
"""Self-test for tools/rig-drift.py: the part-drift check must fail a drifting rig, not only pass a still one.

A check that passes the known-bad build is no check: an earlier change's cadence passed the old rigidity oracle. Synthetic frames,
shaped like src/main/java/com/moddingfromamod/gatec/RigDriftMeter's output:
  * every part on its body on every frame              -> PASS (the every-tick control measured exactly this)
  * posed parts a tick ahead of the body on most frames  -> FAIL (up to 0.34 blocks, 77% of frames)
  * one part drawn a fixed 0.3 blocks off for the run    -> FAIL (a part that never eased, e.g. ease 0)
  * a 4-tick transient (a split delivery) and then fine  -> PASS
  * no frames at all                                     -> FAIL (a meter that never ran is not a pass)
SPIN (the drawn rotation, timed by game time; an earlier change's death fall):
  * a body turning steadily, one frame's dip at each new pose -> PASS (the client restarting its ease)
  * turning, a whole tick drawn still, then a catch-up      -> FAIL (an ease no longer than its gap, under lag)
  * a turn that ends (the body lands flat and stays)        -> PASS (an end is not a stall)
  * the client's clock stepping back for a tick             -> PASS (a time sync pauses every entity, not the rig)
  * a limb swinging, a beat drawn still at the end of its swing, then swinging BACK -> PASS (a 3-tick sample
    either side of the swing's extreme; angvel_fwd says the motion reversed)
  * the same beat still, then carrying on THE SAME WAY                             -> FAIL (a stall, angvel_fwd says so)
"""
import csv
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
COLS = ["nanos", "gametime", "clienttick", "partial", "rig", "atoms", "spread_max", "spread_mean", "yaw_spread",
        "pos_spread", "worst_id", "worst_yaw", "mean_yaw", "body_x", "body_y", "body_z", "acc_part_max", "acc_body",
        "angvel_max", "angacc_max", "q0x", "q0y", "q0z", "q0w"]
FAILS = 0


def write(d, spreads, spins=None, clock=None, fwds=None):
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "rigdrift-frames.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(COLS + (["angvel_fwd"] if fwds else []))
        t = 0
        for i, s in enumerate(spreads):
            tick = i // 3
            t += 16_000_000
            x = 0.09 * i / 3
            gt = clock(i) if clock else 1000 + tick
            av = spins[i] if spins else 0
            w.writerow([t, gt, tick, (i % 3) / 3, "abc", 40, s, s / 4, 0, s, 7, 0, 0, x, 64, 0, 10, 8, av, 0, 0, 0, 0, 1]
                       + ([fwds[i]] if fwds else []))


def verdict(d):
    r = subprocess.run([sys.executable, os.path.join(HERE, "rig-drift.py"), d], capture_output=True, text=True)
    return r.returncode


def check(name, spreads, want, spins=None, clock=None, fwds=None):
    global FAILS
    with tempfile.TemporaryDirectory() as tmp:
        d = os.path.join(tmp, "drift")
        if spreads is not None:
            write(d, spreads, spins, clock, fwds)
        got = verdict(d)
    ok = (got == 0) == (want == "PASS")
    print(f"  {'ok ' if ok else 'BAD'}  {name}: wanted {want}, rc {got}")
    FAILS += 0 if ok else 1


N = 900   # 300 ticks of 3 frames
check("every part on its body on every frame", [0.0] * N, "PASS")
check(": posed parts a tick ahead on most frames", [0.0 if i % 9 == 0 else 0.06 + 0.2 * (i % 3) / 3 for i in range(N)],
      "FAIL")
check("one part a fixed 0.3 blocks off all run", [0.3] * N, "FAIL")
check("a 4-tick transient, then fine", [0.15 if 300 <= i < 312 else 0.0 for i in range(N)], "PASS")
check("no frames (the meter never ran)", None, "FAIL")
# spin: 3 frames a tick, turning 6 deg a tick from tick 60 to 240
turn = [0.0 if (i < 180 or i >= 720) else 6.0 for i in range(N)]
dips = [2.0 if (180 <= i < 720 and i % 9 == 0) else v for i, v in enumerate(turn)]
check("spin: a steady turn, one frame's dip at each new pose", [0.0] * N, "PASS", spins=dips)
stall = [0.0 if 400 <= i < 404 else (12.0 if 404 <= i < 407 else v) for i, v in enumerate(turn)]
check("spin: a whole tick still mid-turn, then a catch-up", [0.0] * N, "FAIL", spins=stall)
check("spin: a turn that ends and stays ended", [0.0] * N, "PASS", spins=turn)
back = [0.0 if 400 <= i < 404 else v for i, v in enumerate(turn)]
check("spin: the client's clock steps back for a tick", [0.0] * N, "PASS", spins=back,
      clock=lambda i: 1000 + 399 // 3 - 1 if 400 <= i < 404 else 1000 + i // 3)
# a limb swinging 6 deg a tick, sampled every 3rd tick: a beat (3 ticks = 9 frames) drawn still at the end of a
# swing, then the swing back at full speed. angvel_fwd is the turn along the way it last went: -6 once it reverses.
swing = [0.0 if 400 <= i < 409 else v for i, v in enumerate(turn)]
back_fwd = [-v if i >= 409 and 180 <= i < 720 else v for i, v in enumerate(swing)]
check("spin: a beat still at the end of a swing, then swinging back", [0.0] * N, "PASS", spins=swing, fwds=back_fwd)
check("spin: a beat still, then carrying on the same way", [0.0] * N, "FAIL", spins=swing, fwds=swing)
check("spin: a beat still, no angvel_fwd column (an older meter)", [0.0] * N, "FAIL", spins=swing)
print("✅ test-rig-drift: all checks passed" if FAILS == 0 else f"❌ test-rig-drift: {FAILS} check(s) failed")
sys.exit(1 if FAILS else 0)
