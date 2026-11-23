"""secret_patterns.py — the ONE library of what a credential looks like.

Every scanner that keeps a key or token out of a repository reads this file: tools/secret-scan.py (the git hooks, the
tracked-tree check, the history scan, the Claude Code PreToolUse guard) and tools/leak-scan.py (the public tree, the
mirror and the public clone's pre-push). It is public-safe on purpose: it names SHAPES, never values.

Two kinds of check, and they fail in opposite directions:

* SHAPES (`find_shapes`): a provider's documented token format (ghp_…, AIza…, sk-ant-…, a PEM private key WITH a body,
  a service-account `private_key_id`), plus three context rules (`*_API_KEY= / *_TOKEN= / *_SECRET=` assignments,
  `Authorization: Bearer …`, a JWT) and a base64 blob that DECODES to any of those. A shape finds a key nobody told us
  about, at the cost of false positives, which are kept down three ways: every rule wants the full documented length,
  a value that looks made up (EXAMPLE, fake, xxxx, 0123456789, one character repeated, low entropy) is not a finding,
  and a line carrying the marker `secret-scan: allow` is skipped (for a documented fake that must be literal; build
  test fixtures by concatenation instead, so the file is not itself a hit).
* LIVE VALUES (`LiveGuard`): the real secret values in THIS process's environment (anything named *_KEY, *_TOKEN,
  *_SECRET, *_PASSWORD… with a long value; GCP_SA_KEY_B64 and the service-account key it decodes to; the file
  GOOGLE_APPLICATION_CREDENTIALS names). Matched as exact substrings, in memory only: a value is never written,
  logged or printed, and a finding names the VARIABLE. A long value is matched by overlapping 32-character windows,
  so a key that was wrapped, truncated or re-encoded with `\\n` escapes is still found. No allowlist applies.

A finding is (line, rule_id, description, length). It never carries the matched text.
"""
from __future__ import annotations

import base64
import binascii
import bisect
import json
import math
import os
import re
from collections import Counter

ALLOW_MARKER = "secret-scan: allow"


class SecretRule:
    def __init__(self, rid, desc, pattern, hints=(), group=0, check=None, fake_check=True):
        """HINTS: literal substrings one of which every match contains (lower-case for a case-insensitive rule).
        A text holding none of them is not searched: that is what makes a 900 MB tree a minute, not ten."""
        self.id, self.desc, self.pattern, self.group = rid, desc, re.compile(pattern), group
        self.hints, self.check, self.fake_check = tuple(hints), check, fake_check
        self.folded = bool(self.pattern.flags & re.I)


def entropy(s: str) -> float:
    if not s:
        return 0.0
    n = len(s)
    return -sum(c / n * math.log2(c / n) for c in Counter(s).values())


_FAKE_WORDS = re.compile(r"example|fake|dummy|placeholder|redacted|changeme|change_me|sample|your[_-]?|notreal|"
                         r"not[_-]a[_-]|xxxxxx|XXXXXX|\*\*\*\*|<[a-z_-]+>|\.\.\.|…|abcdefgh|01234567|12345678|98765432|fedcba98|test", re.I)


def looks_fake(value: str) -> bool:
    """A value someone typed as a placeholder, not a credential a provider minted."""
    if _FAKE_WORDS.search(value):
        return True
    if 16 <= len(value) <= 400 and re.fullmatch(r"[A-Za-z0-9+/_-]+=*", value):    # base64 of "secret-for-tests"
        inner = _decoded(value.replace("-", "+").replace("_", "/"))
        if inner and _FAKE_WORDS.search(inner):
            return True
    body = re.sub(r"^[A-Za-z]{2,12}[_-]", "", value)            # the provider prefix carries no randomness
    if re.search(r"(.)\1{7,}", body):
        return True
    return len(body) >= 16 and entropy(body) < 3.0


def _mixed(value: str) -> bool:
    """Random credential text: letters AND digits, and not a word or a path."""
    return bool(re.search(r"[A-Za-z]", value) and re.search(r"[0-9]", value)) and entropy(value) >= 3.5


_REF = re.compile(r"^(\$|\{|%|<|@|env\.|process\.env|os\.environ|secrets\.)", re.I)


_NAME_BEFORE = re.compile(r"[A-Za-z0-9_.-]{0,60}$")


_WORDY = re.compile(r"(?:^|[-_.])[a-z]{6,}(?:[-_.]|$)")


def _assigned(m) -> bool:
    v = m.group("v")
    if _WORDY.search(v):                       # runGameTestServer-waiter-…: an identifier, not a minted key
        return False
    name = _NAME_BEFORE.search(m.string, max(0, m.start() - 60), m.start()).group(0) + m.group("k")
    if not _SECRET_NAME_RE.fullmatch(name):
        return False
    return not _REF.search(v) and _mixed(v) and "/" not in v[:1] and not re.fullmatch(r"[0-9a-f-]{36}", v) \
        and not re.fullmatch(r"[0-9.]+", v)


def _bearer(m) -> bool:
    v = m.group("v")
    return _mixed(v) and not _REF.search(v)


_SECRET_NAME = (r"[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|secret[_-]?key|access[_-]?key|client[_-]?secret|auth[_-]?token"
                r"|access[_-]?token|refresh[_-]?token|bot[_-]?token|private[_-]?key|password|passwd|secret|token)")
_SECRET_NAME_RE = re.compile(_SECRET_NAME, re.I)

RULES = [
    SecretRule("private-key", "a PEM or OpenSSH private key with its body",
               r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----(?:\\[rn]|\s){1,4}"
               r"(?:[A-Za-z0-9-]+: [^\n]*(?:\\n|\n)){0,4}(?:\\[rn]|\s)*[A-Za-z0-9+/=]{40,}", hints=("PRIVATE KEY",)),
    SecretRule("gcp-service-account", "a Google service-account key id",
               r"\"private_key_id\"\s*:\s*\"[0-9a-f]{40}\"", hints=("private_key_id",)),
    SecretRule("google-api-key", "a Google API key", r"\bAIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])", hints=("AIza",)),
    SecretRule("google-oauth-access", "a Google OAuth access token", r"\bya29\.[0-9A-Za-z_-]{30,}", hints=("ya29.",)),
    SecretRule("google-oauth-refresh", "a Google OAuth refresh token", r"(?<![\w/])1//0[0-9A-Za-z_-]{35,}", hints=("1//0",)),
    SecretRule("google-client-secret", "a Google OAuth client secret", r"\bGOCSPX-[0-9A-Za-z_-]{28}\b", hints=("GOCSPX-",)),
    SecretRule("aws-access-key", "an AWS access key id", r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b", hints=("AKIA", "ASIA")),
    SecretRule("aws-secret-key", "an AWS secret access key",
               r"(?i)aws_?secret_?access_?key[\"']?\s*[:=]\s*[\"']?(?P<v>[A-Za-z0-9/+=]{40})(?![A-Za-z0-9/+=])",
               group="v", hints=("secret",)),
    SecretRule("github-token", "a GitHub token", r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\b", hints=("ghp_", "gho_", "ghu_", "ghs_", "ghr_")),
    SecretRule("github-pat", "a GitHub fine-grained token", r"\bgithub_pat_[A-Za-z0-9_]{50,}", hints=("github_pat_",)),
    SecretRule("anthropic-key", "an Anthropic API key", r"\bsk-ant-[A-Za-z0-9_-]{32,}", hints=("sk-ant-",)),
    SecretRule("openai-key", "an OpenAI-style API key",
               r"\bsk-(?:proj|svcacct|admin)-[A-Za-z0-9_-]{40,}|\bsk-[A-Za-z0-9]{20}T3BlbkFJ[A-Za-z0-9]{20}\b"
               r"|\bsk-[A-Za-z0-9]{48}\b", hints=("sk-",)),
    SecretRule("muse-key", "a Muse (Meta) API key", r"\bLLM_[A-Za-z0-9]{12,24}_[A-Za-z0-9]{20,40}\b", hints=("LLM_",)),
    SecretRule("slack-token", "a Slack token", r"\bxox[abposr]-[0-9A-Za-z-]{20,}|\bxapp-\d-[0-9A-Za-z-]{20,}", hints=("xox", "xapp-")),
    SecretRule("slack-webhook", "a Slack webhook URL",
               r"hooks\.slack\.com/(?:services|workflows)/T[0-9A-Z]{6,}/[0-9A-Za-z]{6,}/[0-9A-Za-z]{16,}", hints=("hooks.slack.com",)),
    SecretRule("stripe-key", "a Stripe live key", r"\b(?:sk|rk)_live_[0-9A-Za-z]{24,}", hints=("_live_",)),
    SecretRule("telegram-token", "a Telegram bot token", r"\b\d{8,10}:AA[A-Za-z0-9_-]{33}\b", hints=(":AA",)),
    SecretRule("discord-token", "a Discord bot token",
               r"\b[MNO][A-Za-z0-9_-]{23,25}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,38}\b", hints=(".",)),
    SecretRule("discord-webhook", "a Discord webhook URL",
               r"discord(?:app)?\.com/api/webhooks/\d{15,}/[A-Za-z0-9_-]{60,}", hints=("/api/webhooks/",)),
    SecretRule("modrinth-token", "a Modrinth personal access token", r"\bmrp_[A-Za-z0-9]{30,}\b", hints=("mrp_",)),
    SecretRule("curseforge-key", "a CurseForge API key", r"\$2[aby]\$10\$[./A-Za-z0-9]{53}(?![./A-Za-z0-9])", hints=("$2",)),
    SecretRule("curseforge-token", "a CurseForge upload token",
               r"(?i)(?:curseforge|\bcf)[_-]?(?:api[_-]?)?(?:token|key)[\"']?\s*[:=]\s*[\"']?"
               r"(?P<v>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", group="v", hints=("cf", "curseforge")),
    SecretRule("npm-token", "an npm token", r"\bnpm_[A-Za-z0-9]{36}\b", hints=("npm_",)),
    SecretRule("pypi-token", "a PyPI token", r"\bpypi-AgEIcHlwaS5vcmc[A-Za-z0-9_-]{50,}", hints=("pypi-",)),
    SecretRule("jwt", "a JSON web token",
               r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}", hints=("eyJ",)),
    SecretRule("bearer", "a literal Authorization: Bearer credential",
               r"(?i)\bauthorization[\"']?\s*[:=]\s*[\"']?bearer\s+(?P<v>[A-Za-z0-9._~+/-]{24,}=*)", group="v",
               check=_bearer, hints=("bearer",)),
    SecretRule("assigned-secret", "a long random value assigned to a *_KEY / *_TOKEN / *_SECRET / password",
               r"(?i)(?P<k>key|token|secret|passw(?:or)?d)[\"']?\s*(?::=|=|:)\s*[\"']?"
               r"(?P<v>[A-Za-z0-9._~+/=-]{20,})(?=[\"'\s,;)}\]]|$)", group="v", check=_assigned,
               hints=("key", "token", "secret", "passw")),
]

_B64_RUN = re.compile(r"[A-Za-z0-9+/]{120,}={0,2}")
_B64_CAP = 200_000


def _line_index(text):
    starts = [0]
    starts.extend(m.end() for m in re.finditer("\n", text))
    return starts


def _allowed(text, starts, pos):
    i = bisect.bisect_right(starts, pos) - 1
    end = starts[i + 1] if i + 1 < len(starts) else len(text)
    return ALLOW_MARKER in text[starts[i]:end]


def _decoded(blob: str):
    """The text a base64 blob decodes to, or None when it is not mostly printable (an image, a compressed pack)."""
    try:
        raw = base64.b64decode(blob[:_B64_CAP] + "=" * (-len(blob[:_B64_CAP]) % 4), validate=False)
    except (binascii.Error, ValueError):
        return None
    head = raw[:512]
    if not head or sum(32 <= b < 127 or b in (9, 10, 13) for b in head) < 0.9 * len(head):
        return None
    return raw.decode("utf-8", "replace")


def find_shapes(text: str, decode_base64: bool = True):
    """[(line, rule_id, description, length)] for every credential-shaped value in TEXT."""
    out = []
    starts = None
    lowered = None
    for r in RULES:
        if r.hints:
            if r.folded:
                lowered = text.lower() if lowered is None else lowered
                if not any(h in lowered for h in r.hints):
                    continue
            elif not any(h in text for h in r.hints):
                continue
        for m in r.pattern.finditer(text):
            value = m.group(r.group)
            if r.check and not r.check(m):
                continue
            if r.fake_check and looks_fake(value if r.group else m.group(0)):
                continue
            if starts is None:
                starts = _line_index(text)
            if _allowed(text, starts, m.start()):
                continue
            out.append((bisect.bisect_right(starts, m.start()), r.id, r.desc, len(value)))
    if decode_base64 and len(text) >= 120:
        for m in _B64_RUN.finditer(text):
            inner = _decoded(m.group(0))
            if not inner:
                continue
            hits = find_shapes(inner, decode_base64=False)
            if hits:
                if starts is None:
                    starts = _line_index(text)
                if _allowed(text, starts, m.start()):
                    continue
                line = bisect.bisect_right(starts, m.start())
                out.extend((line, "base64:" + rid, f"base64 that decodes to {desc}", len(m.group(0)))
                           for _l, rid, desc, _n in hits[:1])
    return sorted(out)


# ── the live values of this process's environment ───────────────────────────────────────────

_LIVE_NAME = re.compile(r"(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIALS?|AUTH|PAT|COOKIE)S?(_B64|_BASE64|_JSON)?$",
                        re.I)
_NOT_SECRET_NAME = re.compile(r"^GIT_CONFIG_(KEY|VALUE)_\d+$|DISTRUSTED|_(PATH|FILE|DIR|URL|HOST|NAME|ID|USER)$", re.I)
_PLACEHOLDER = {"proxy-injected"}
WINDOW, STRIDE, MIN_LIVE = 32, 16, 20


def _windows(value: str):
    if len(value) <= WINDOW:
        return [value]
    w = [value[i:i + WINDOW] for i in range(0, len(value) - WINDOW + 1, STRIDE)]
    w.append(value[-WINDOW:])
    return w


def _key_json_needles(data: str):
    """The secret parts of a service-account (or OAuth client) JSON: the key id, the key body, a client secret."""
    try:
        d = json.loads(data)
    except ValueError:
        return []
    if not isinstance(d, dict):
        return []
    out = []
    pk = d.get("private_key")
    if isinstance(pk, str):
        body = re.sub(r"-----[A-Z ]+-----|\s", "", pk)
        out.extend(_windows(body) if len(body) >= MIN_LIVE else [])
    for k in ("private_key_id", "client_secret", "refresh_token"):
        if isinstance(d.get(k), str) and len(d[k]) >= MIN_LIVE:
            out.append(d[k])
    return out


def live_needles(environ=None):
    """[(variable, needle)]: substrings that only the live secrets contain. In memory only; never print a needle."""
    environ = os.environ if environ is None else environ
    out = []
    for name, value in environ.items():
        if not _LIVE_NAME.search(name) or _NOT_SECRET_NAME.search(name):
            continue
        v = value.strip()
        if len(v) < MIN_LIVE or v in _PLACEHOLDER or re.search(r"[\s,]", v) or v.startswith(("/", "~", "./")) \
                or "://" in v or entropy(v) < 3.0:
            continue
        out.extend((name, n) for n in _windows(v))
        if len(v) >= 64 and re.fullmatch(r"[A-Za-z0-9+/=_-]+", v):     # a base64 key (GCP_SA_KEY_B64)
            dec = _decoded(v)
            if dec:
                out.extend((name, n) for n in _key_json_needles(dec))
    for name in ("GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_CREDENTIALS"):
        path = environ.get(name, "")
        try:
            if path.startswith("/") and os.path.isfile(path) and os.path.getsize(path) < 65536:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    out.extend((name, n) for n in _key_json_needles(fh.read()))
        except OSError:
            pass
    seen, uniq = set(), []
    for name, n in out:
        if len(n) >= MIN_LIVE and n not in seen:
            seen.add(n)
            uniq.append((name, n))
    return uniq


def _trie(words):
    """One regex for many literals, factored by common prefix: a flat alternation of ~300 windows makes `re` try
    every branch at every position (50 s over the tree); the trie fails on the first character instead."""
    root = {}
    for w in words:
        node = root
        for ch in w:
            node = node.setdefault(ch, {})
        node[""] = True

    def emit(node):
        alts, end = [], "" in node
        for ch in sorted(k for k in node if k):
            alts.append(re.escape(ch) + emit(node[ch]))
        if not alts:
            return ""
        body = alts[0] if len(alts) == 1 else "(?:" + "|".join(alts) + ")"
        return f"(?:{body})?" if end else body
    return emit(root)


class LiveGuard:
    """Exact-substring matching of the environment's live secrets. Holds them in memory; reports variable names."""

    def __init__(self, environ=None):
        self.needles = live_needles(environ)
        self._owner = {n: name for name, n in self.needles}
        self._re = re.compile(_trie(list(self._owner))) if self.needles else None

    def __bool__(self):
        return self._re is not None

    def variables(self):
        return sorted(set(self._owner.values()))

    def find(self, text: str):
        """[(line, variable)] for every place TEXT carries a live secret (one per variable per line)."""
        if self._re is None or not text:
            return []
        starts, out, seen = None, [], set()
        for m in self._re.finditer(text):
            if starts is None:
                starts = _line_index(text)
            key = (bisect.bisect_right(starts, m.start()), self._owner[m.group(0)])
            if key not in seen:
                seen.add(key)
                out.append(key)
        return out
