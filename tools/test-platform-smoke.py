#!/usr/bin/env python3
"""Tests for tools/platform-smoke.py's boot-log check (bad()): the one stock-server line it lets through, and that
everything near it still fails the boot.

    python3 tools/test-platform-smoke.py
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("platform_smoke", os.path.join(HERE, "platform-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

PFX = "[27Nov2026 10:00:00.000] [Server thread/ERROR] [STDERR/]: "
APPENDER = "org.apache.logging.log4j.core.appender.AppenderLoggingException: "
CASES = [
    # (line, fails the boot?)
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native", False),
    (APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native  ", False),
    ("[Server thread/INFO] [minecraft/DedicatedServer]: Done (4.2s)! For help, type \"help\"", False),
    (PFX + "java.lang.NoClassDefFoundError: com/moddingfromamod/wish/WarmUp$Outcome", True),
    ("java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native", True),
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: com/moddingfromamod/wish/WarmUp$Outcome", True),
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.epoll.Native", True),
    (PFX + APPENDER + "java.io.IOException: No space left on device", True),
    (PFX + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native"
     " -- java.lang.UnsatisfiedLinkError: no onnxruntime", True),
    ("Exception in server tick loop " + APPENDER + "java.lang.NoClassDefFoundError: Could not initialize class "
     "io.netty.channel.kqueue.Native", True),
]

failed = 0
for line, want in CASES:
    got = smoke.bad(line)
    if got != want:
        failed += 1
        print("FAIL: bad(%r) = %s, want %s" % (line, got, want))
print("%d of %d cases %s" % (len(CASES) - failed, len(CASES), "passed" if not failed else "right"))
sys.exit(1 if failed else 0)
