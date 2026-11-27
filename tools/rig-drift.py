#!/usr/bin/env python3
"""rig-drift.py -- did any part of a rig come off its body on the client?

Reads `rigdrift-frames.csv` written by the client's `RigDriftMeter` (`-Ptestprops=rigdrift=<dir>`): per rendered
frame and rig, how far the worst atom is DRAWN from where the body's shared position and yaw would put it. Every atom
of a rig stands at its carrier's feet on the server, so on a correct build this is 0 on every frame (measured: the
every-tick control, 739 frames, all exactly 0). an earlier change's cadence drew parts up to 0.34 blocks off (77% of frames).

    python3 tools/rig-drift.py DIR [DIR ...]          # exit 1 if any run drifts
    python3 tools/rig-drift.py --json DIR             # the numbers, for a review page

A rig FAILS when, past its first second on screen (spawn and adoption settle there):
  * more than MAX_FRAC of its frames are over TOL blocks, or
  * a stretch over TOL lasts longer than MAX_TICKS client ticks, or
  * its parts, while turning, are drawn still for longer than MAX_SPIN_FREEZE game ticks (spin: a whole-body
    move such as the death flop that stops for want of an ease and then lurches). Only a stall the SAME motion comes
    out of counts (the meter's angvel_fwd): a limb drawn still at the end of its swing and then swinging back
    is a turnaround, which a 3-tick beat draws as a hold of up to a beat and the every-tick control as one tick.
It also reports the drawn body's frame-to-frame motion (freezes and jumps against the local median speed) for the
lagged arms; that is reported beside the drift verdict, measured against the same run's every-tick arm, not judged
alone (a software client's own frame pacing is noisy).
Vanilla can deliver one server tick's packets across two client ticks (docs/VANILLA_RIG_ANIMATION_LADDER.md §5.4),
which shows as a short transient; the stretch rule lets that through and nothing that persists. No tolerance here was
tuned to make a run pass: TOL is 5 mm, a few thousandths of a big creature's height.
"""
import csv
import json
import math
import os
import sys

TOL = 0.005          # blocks
MAX_FRAC = 0.02      # of a rig's frames
MAX_TICKS = 6        # client ticks: two beats of a 3-tick cadence
SETTLE_TICKS = 20    # a rig's first second
WIN = 15             # frames either side for the local median speed
MOVING = 0.05        # blocks/s: below this the body is standing
FREEZE = 0.25        # a frame moving under a quarter of the local median is a freeze
JUMP = 2.5           # and over 2.5x a jump
KINK = 5.0           # a frame whose drawn acceleration is over 5x the run's median is a kink/snap
SPIN_MOVING = 1.0    # deg per game tick: below this local median the parts are not turning
ABRUPT = 0.5         # a stall starts from at least half the local median speed: a sudden stop, not a slowing
RESUME = 0.75        # a stall resumes the same motion when it picks up at this share of its speed before, or more
SPIN_STILL = 0.3     # deg per game tick: a frame turning slower than this (and under FREEZE x the median) is still
MAX_SPIN_FREEZE = 0.5  # game ticks: a turning body drawn still this long, then moving again, had no ease running
AXIS_SAME = 0.7      # cos of the angle between q0's turn before a stall and after it: under this, two motions (#138)


def load(path):
    f = os.path.join(path, "rigdrift-frames.csv") if os.path.isdir(path) else path
    rows = list(csv.DictReader(open(f)))
    rigs = {}
    for r in rows:
        rigs.setdefault(r["rig"], []).append(r)
    return rigs


def stutter(fr):
    """Frame-to-frame motion of the drawn body: freezes (it stopped while walking) and jumps (it leapt)."""
    if "body_x" not in fr[0] or not fr[0]["body_x"]:
        return None
    t = [int(r["nanos"]) / 1e9 for r in fr]
    p = [(float(r["body_x"]), float(r["body_y"]), float(r["body_z"])) for r in fr]
    v = []
    for i in range(1, len(fr)):
        dt = t[i] - t[i - 1]
        if dt <= 0:
            continue
        v.append((i, math.dist(p[i], p[i - 1]) / dt, dt))
    if len(v) < 30:
        return None
    speeds = [x[1] for x in v]
    freezes = jumps = moving = 0
    longest_freeze = 0.0
    run = 0.0
    for k, (i, sp, dt) in enumerate(v):
        win = sorted(speeds[max(0, k - WIN):k + WIN + 1])
        med = win[len(win) // 2]
        if med < MOVING:
            run = 0.0
            continue
        moving += 1
        if sp < FREEZE * med:
            freezes += 1
            run += dt
            longest_freeze = max(longest_freeze, run)
        else:
            run = 0.0
            if sp > JUMP * med:
                jumps += 1
    if moving == 0:
        return None
    kinks = kink(fr)
    return {**kinks, "moving_frames": moving, "freeze_frac": round(freezes / moving, 4), "jump_frac": round(jumps / moving, 4),
            "longest_freeze_ms": round(1000 * longest_freeze)}


def kink(fr):
    """Spikes in how sharply the drawn parts (and the body) change velocity: a late move landing mid-ease shows as a
    kink, an overshoot or a snap. A spike is a frame over KINK x the run's own median, so a smooth curve (the walk circle)
    sets its own floor and the two arms of one comparison are judged alike."""
    if "acc_part_max" not in fr[0] or not fr[0]["acc_part_max"]:
        return {}
    out = {}
    for col, key in (("acc_part_max", "part"), ("acc_body", "body")):
        a = [float(r[col]) for r in fr if float(r[col]) > 0]
        if len(a) < 30:
            continue
        srt = sorted(a)
        med = srt[len(srt) // 2]
        spikes = sum(x > KINK * med for x in a)
        out[f"kink_{key}_frac"] = round(spikes / len(a), 4)
        out[f"acc_{key}_p50"] = round(med, 2)
        out[f"acc_{key}_p99"] = round(srt[int(0.99 * (len(srt) - 1))], 2)
    return out


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz)


def turn_of(fr, k, t):
    """Part 0's drawn turn from frame k-1 to k, deg per game tick about its axis (x, y, z); None without q0 columns."""
    try:
        a = tuple(float(fr[k][c]) for c in ("q0x", "q0y", "q0z", "q0w"))
        b = tuple(float(fr[k - 1][c]) for c in ("q0x", "q0y", "q0z", "q0w"))
    except (KeyError, ValueError, TypeError):
        return None
    d = qmul(a, (-b[0], -b[1], -b[2], b[3]))
    if d[3] < 0:
        d = tuple(-x for x in d)
    dt = t[k] - t[k - 1]
    sn = math.sqrt(max(0.0, 1 - d[3] * d[3]))
    if dt <= 0 or sn < 1e-9:
        return (0.0, 0.0, 0.0)
    ang = math.degrees(2 * math.acos(min(1.0, d[3]))) / dt
    return (d[0] / sn * ang, d[1] / sn * ang, d[2] / sn * ang)


def another_motion(fr, t, before_k, k):
    """#138: is what moves after a stall a DIFFERENT motion from what moved before it? Read off part 0's own drawn turn
    (the CSV's q0): turning before AND after, about axes more than ~45 degrees apart (a leg's swing ending as the walker
    stops, then the body turning round). When part 0 did not turn on either side (it is not the moving part), or the CSV
    has no q0, nothing is vetoed: the check falls back to angvel_fwd alone."""
    if before_k < 1:
        return False
    a = turn_of(fr, before_k, t)
    if a is None:
        return False
    after = [turn_of(fr, j, t) for j in range(k, min(k + 3, len(fr)))]
    after = [v for v in after if v is not None]
    if not after:
        return False
    b = max(after, key=lambda v: math.hypot(*v))
    na, nb = math.hypot(*a), math.hypot(*b)
    if na < SPIN_STILL or nb < SPIN_STILL:
        return False
    return (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb) < AXIS_SAME


def spin(fr):
    """The drawn ROTATION of the parts, timed by game time (client tick + partial), the meter's angvel_max: a whole-body
    turn (the death flop, a swing) that freezes and then lurches. Position stutter misses a part turning about its own
    centre. A freeze is a turning frame drawn still (under SPIN_STILL and FREEZE x the local median); the longest run of them, in game ticks, is judged:
    one frame's dip at each new pose is the client restarting its ease from the last frame it drew, a whole tick still is
    a gap with no ease running (the death film's one-tick ease under lag)."""
    if "angvel_max" not in fr[0] or not fr[0]["angvel_max"]:
        return None
    t = [int(r["clienttick"]) + float(r["partial"]) for r in fr]
    w = [float(r["angvel_max"]) for r in fr]
    # how much of the turn carries on THE WAY IT WAS GOING (the meter's angvel_fwd). Without it (an older CSV)
    # only the speed is known, and a limb reversing at the end of its swing reads as a stall that resumed.
    fwd = [float(r["angvel_fwd"]) for r in fr] if fr[0].get("angvel_fwd") not in (None, "") else w
    g = [int(r["gametime"]) for r in fr]
    turning = freezes = jumps = 0
    run = longest = before = 0.0
    frames = 0
    before_k = 0
    seen = g[0]
    for k in range(1, len(fr)):
        win = sorted(w[max(0, k - WIN):k + WIN + 1])
        med = win[len(win) // 2]
        dt = t[k] - t[k - 1]
        # the client's clock stepped BACK (a time sync or a tick-rate change, e.g. the film rate being set): every
        # interpolation in the world pauses for that tick, the rig's included; it is not the rig's to answer for
        backwards = g[k] < seen
        seen = max(seen, g[k])
        if backwards:
            run = 0.0
            continue
        if med < SPIN_MOVING or dt <= 0:
            run = 0.0
            continue
        turning += 1
        # still, not merely slower: one motion ending as a slower one goes on (a turn finishing while the head keeps
        # looking) is not a stall
        if w[k] < FREEZE * med and w[k] < SPIN_STILL:
            if run == 0.0:
                before = w[k - 1]
                before_k = k - 1
                frames = 0
            freezes += 1
            frames += 1
            run += dt
        else:
            # a stall counts once the SAME motion carries on, at RESUME x its speed before or faster (a catch-up): one
            # that never resumes is the move ending (the body lands flat), and one followed by a slower, different
            # motion is two motions meeting (an attack swing ends, a turn starts two ticks later)
            # and only an ABRUPT stop of more than one frame: one frame's dip is the client restarting its ease from
            # the last frame it drew (at real speed a software client draws about one frame a tick, so that frame
            # alone spans most of a tick), and a speed that eases down to a turnaround (a head sweep) is not a stall
            # and only the SAME motion: angvel_fwd after a stall can be measured against the new motion's own first
            # frame (a part still too long to remember its way adopts the new one at once), so part 0's own turn
            # before and after is compared too (#138)
            if (run > 0.0 and frames >= 2 and before >= ABRUPT * med
                    and max(fwd[k:k + 3]) >= RESUME * before
                    and not another_motion(fr, t, before_k, k)):
                longest = max(longest, run)
            run = 0.0
            if w[k] > JUMP * med:
                jumps += 1
    if turning == 0:
        return None
    return {"turning_frames": turning, "spin_freeze_frac": round(freezes / turning, 4),
            "spin_jump_frac": round(jumps / turning, 4), "longest_spin_freeze_ticks": round(longest, 2)}


def judge(frames):
    first = int(frames[0]["clienttick"])
    fr = [r for r in frames if int(r["clienttick"]) - first >= SETTLE_TICKS]
    if not fr:
        return {"frames": 0, "verdict": "NO-DATA"}
    sp = [float(r["spread_max"]) for r in fr]
    over = [s > TOL for s in sp]
    longest = 0
    start = None
    for r, o in zip(fr, over):
        t = int(r["clienttick"])
        if o and start is None:
            start = t
        if not o:
            start = None
        if start is not None:
            longest = max(longest, t - start + 1)
    srt = sorted(sp)
    frac = sum(over) / len(sp)
    sp_ = spin(fr)
    spin_ok = sp_ is None or sp_["longest_spin_freeze_ticks"] <= MAX_SPIN_FREEZE
    ok = frac <= MAX_FRAC and longest <= MAX_TICKS and spin_ok
    return {
        "frames": len(sp),
        "max": round(srt[-1], 4),
        "p95": round(srt[int(0.95 * (len(srt) - 1))], 4),
        "p50": round(srt[len(srt) // 2], 4),
        "frac_over": round(frac, 4),
        "longest_ticks": longest,
        "yaw_max": round(max(float(r["yaw_spread"]) for r in fr), 3),
        "verdict": "PASS" if ok else "FAIL",
        "stutter": stutter(fr),
        "spin": sp_,
        "why": [] if ok else ([f"part drift over {TOL} on {100 * frac:.1f}% of frames, longest {longest} ticks"]
                              if not (frac <= MAX_FRAC and longest <= MAX_TICKS) else [])
                             + ([f"a turning body drawn still for {sp_['longest_spin_freeze_ticks']} game ticks"]
                                if not spin_ok else []),
    }


def main(argv):
    as_json = "--json" in argv
    paths = [a for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__)
        return 2
    out = {}
    bad = False
    for p in paths:
        try:
            rigs = load(p)
        except FileNotFoundError:
            out[p] = {"verdict": "NO-DATA", "why": "no rigdrift-frames.csv (was rigdrift= set?)"}
            bad = True
            continue
        if not rigs:
            out[p] = {"verdict": "NO-DATA", "why": "no rig was on screen"}
            bad = True
            continue
        out[p] = {rig: judge(fr) for rig, fr in rigs.items()}
        bad |= any(v["verdict"] != "PASS" for v in out[p].values())
    if as_json:
        print(json.dumps(out, indent=1))
    else:
        for p, rigs in out.items():
            print(p)
            if "verdict" in rigs:
                print(f"  {rigs['verdict']}: {rigs.get('why', '')}")
                continue
            for rig, v in rigs.items():
                if v["verdict"] == "NO-DATA":
                    print(f"  rig {rig}: NO-DATA")
                    continue
                print(f"  rig {rig}: {v['verdict']}  frames {v['frames']}  worst part {v['max']} blocks  "
                      f"p95 {v['p95']}  over {TOL}: {100 * v['frac_over']:.1f}%  longest {v['longest_ticks']} ticks  "
                      f"yaw spread {v['yaw_max']} deg")
                if v.get("why"):
                    print("      FAIL: " + "; ".join(v["why"]))
                sv = v.get("spin")
                if sv:
                    print(f"      spin: {sv['turning_frames']} turning frames, freezes {100 * sv['spin_freeze_frac']:.1f}%, "
                          f"lurches {100 * sv['spin_jump_frac']:.1f}%, longest freeze {sv['longest_spin_freeze_ticks']} ticks")
                st = v.get("stutter")
                if st:
                    print(f"      motion: {st['moving_frames']} moving frames, freezes {100 * st['freeze_frac']:.1f}%, "
                          f"jumps {100 * st['jump_frac']:.1f}%, longest freeze {st['longest_freeze_ms']} ms")
                    if "kink_part_frac" in st:
                        print(f"      kinks: parts {100 * st['kink_part_frac']:.1f}% of frames (acc p50 {st['acc_part_p50']}, "
                              f"p99 {st['acc_part_p99']}), body {100 * st.get('kink_body_frac', 0):.1f}% "
                              f"(p50 {st.get('acc_body_p50')}, p99 {st.get('acc_body_p99')})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
