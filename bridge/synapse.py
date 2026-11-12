#!/usr/bin/env python3
"""synapse — bridge between terminal AI agents (claude, codex, kimi, grok, ...) and a phone.

Agents run inside tmux sessions named ``synapse-<id>``. The bridge never talks to an
agent directly: it captures the tmux pane and types keys into it, so it works with
any CLI. An HTTPS server (self-signed cert, pinned by the phone through the pairing
QR, plus a bearer token) exposes the sessions to the Android app.

Zero third-party dependencies: Python >= 3.9, tmux, and (for the QR) qrencode.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "0.1.0"
PREFIX = "synapse-"
DEFAULT_PORT = 7433
CONFIG_DIR = Path(os.environ.get("SYNAPSE_CONFIG_DIR", Path.home() / ".config" / "synapse"))
DEFAULT_AGENTS = {"claude": "claude", "codex": "codex", "kimi": "kimi", "grok": "grok"}
ID_RE = re.compile(r"^[A-Za-z0-9_.-]{1,40}$")

# ---------------------------------------------------------------- config / secrets


def load_config() -> dict:
    cfg = {"port": DEFAULT_PORT, "host": "0.0.0.0", "agents": dict(DEFAULT_AGENTS)}
    try:
        user = json.loads((CONFIG_DIR / "config.json").read_text())
        cfg["port"] = int(user.get("port", cfg["port"]))
        cfg["host"] = str(user.get("host", cfg["host"]))
        cfg["agents"].update(user.get("agents", {}))
    except (OSError, ValueError):
        pass
    return cfg


def private_dir() -> Path:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    CONFIG_DIR.chmod(0o700)  # mkdir(mode=) is ignored for a directory that already existed
    return CONFIG_DIR


def write_private(path: Path, data: str) -> None:
    """Write a secret so it is never visible with looser permissions, even for an instant: 0600 from creation,
    via a temp file renamed into place."""
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(data)
    os.replace(tmp, path)


def get_token(rotate: bool = False) -> str:
    path = private_dir() / "token"
    if rotate or not path.exists():
        write_private(path, secrets.token_urlsafe(32))
    return path.read_text().strip()


_token_cache: dict = {"stamp": None, "value": ""}


def current_token() -> str:
    """The token as it is on disk now. Re-read when the file changes, so `synapse token --rotate` revokes every
    paired phone immediately instead of at the next restart."""
    path = CONFIG_DIR / "token"
    try:
        st = path.stat()
    except OSError:
        return secrets.token_urlsafe(32)  # no token file: nobody can authenticate
    stamp = (st.st_mtime_ns, st.st_size, st.st_ino)
    if _token_cache["stamp"] != stamp:
        _token_cache.update(stamp=stamp, value=path.read_text().strip())
    return _token_cache["value"]


def ensure_cert() -> tuple[Path, Path]:
    """Self-signed EC cert, created once. The phone pins its SHA-256 (from the QR)."""
    private_dir()
    cert, key = CONFIG_DIR / "cert.pem", CONFIG_DIR / "key.pem"
    if not (cert.exists() and key.exists()):
        old = os.umask(0o077)  # the private key must never exist with group/other bits
        try:
            subprocess.run(
                ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                 "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "3650", "-subj", "/CN=synapse"],
                check=True, capture_output=True,
            )
        finally:
            os.umask(old)
        key.chmod(0o600)
    return cert, key


def cert_fingerprint() -> str:
    cert, _ = ensure_cert()
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return hashlib.sha256(der).hexdigest()


# ---------------------------------------------------------------- tmux layer


def tmux(*args: str, check: bool = False, input_: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["tmux", *args], capture_output=True, text=True, input=input_, check=check)


def target(sid: str) -> str:
    return f"={PREFIX}{sid}:"  # '=' = exact session name match


def list_sessions() -> list[dict]:
    fmt = "\t".join(["#{session_name}", "#{@synapse_agent}", "#{session_created}", "#{session_activity}",
                     "#{pane_current_path}"])
    r = tmux("list-sessions", "-F", fmt)
    out = []
    if r.returncode != 0:
        return out
    for line in r.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 5 or not parts[0].startswith(PREFIX):
            continue
        out.append({"id": parts[0][len(PREFIX):], "agent": parts[1], "created": int(parts[2] or 0),
                    "activity": int(parts[3] or 0), "cwd": parts[4]})
    return out


ANSI_RE = re.compile(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b\[[0-9;:?<>=]*[ -/]*[@-~]|\x1b[()][A-Za-z0-9]")


def strip_ansi(text: str) -> str:
    return ANSI_RE.sub("", text)


def capture(sid: str, lines: int = 120, ansi: bool = True) -> str | None:
    """Pane contents incl. scrollback; with ansi=True SGR colour/style escapes are kept."""
    r = tmux("capture-pane", "-p", *(["-e"] if ansi else []), "-t", target(sid), "-S", f"-{lines}")
    if r.returncode != 0:
        return None
    return r.stdout.rstrip("\n ")


def cursor_info(sid: str, lines: int = 120) -> dict | None:
    """Cursor position as (x, row-in-captured-text, visible). Row counts the scrollback capture() includes."""
    r = tmux("display-message", "-p", "-t", target(sid),
             "#{cursor_x} #{cursor_y} #{cursor_flag} #{history_size} #{alternate_on}")
    parts = r.stdout.split()
    if r.returncode != 0 or len(parts) != 5:
        return None
    x, y, flag, hist, _alt = (int(v) for v in parts)
    # capture-pane -S -N prepends min(history, N) lines (also with the alternate screen on, verified on tmux 3.8)
    return {"x": x, "y": min(hist, lines) + y, "visible": bool(flag)}


def session_exists(sid: str) -> bool:
    return tmux("has-session", "-t", target(sid)).returncode == 0


def create_session(agent: str, cwd: str | None = None, name: str | None = None,
                   allow_raw_command: bool = False) -> dict:
    agents = load_config()["agents"]
    if agent in agents:
        cmd = agents[agent]
    elif allow_raw_command:
        cmd = agent
    else:
        raise ValueError(f"unknown agent '{agent}' (known: {', '.join(sorted(agents))})")
    workdir = os.path.expanduser(cwd) if cwd else str(Path.home())
    if not os.path.isdir(workdir):
        raise ValueError(f"not a directory: {workdir}")
    if name:
        if not ID_RE.fullmatch(name):
            raise ValueError("invalid session name")
        sid = name
    else:
        base = re.sub(r"[^A-Za-z0-9_.-]", "-", os.path.basename(workdir.rstrip("/")) or "home")[:24]
        sid, n = f"{agent}-{base}", 1
        while session_exists(sid):
            n += 1
            sid = f"{agent}-{base}-{n}"
    if session_exists(sid):
        raise ValueError(f"session '{sid}' already exists")
    shell = os.environ.get("SHELL", "/bin/bash")
    # interactive shell so PATH tweaks from the user's rc files (mise, nvm, ...) apply
    r = tmux("new-session", "-d", "-s", PREFIX + sid, "-c", workdir, "-x", "110", "-y", "45",
             "-e", f"SYNAPSE_SESSION={sid}", shell, "-ic", f"exec {cmd}")
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "tmux new-session failed")
    tmux("set-option", "-t", target(sid), "@synapse_agent", agent)
    return {"id": sid, "agent": agent, "cwd": workdir, "state": "idle", "created": int(time.time()),
            "activity": int(time.time())}


NAMED_KEYS = {"Enter", "Escape", "Tab", "BTab", "Up", "Down", "Left", "Right", "Space", "BSpace", "DC",
              "Home", "End", "PageUp", "PageDown"}
CHORD_RE = re.compile(r"^[CMS]-[A-Za-z0-9]$")


def valid_key(k: str) -> bool:
    # a bare character must be printable ASCII: control characters (newline, ESC, ^C...) only through their names
    return k in NAMED_KEYS or bool(CHORD_RE.fullmatch(k)) or (len(k) == 1 and k.isascii() and k.isprintable())


def send_keys(sid: str, keys: list[str]) -> None:
    if not keys or not all(isinstance(k, str) and valid_key(k) for k in keys):
        raise ValueError("invalid keys")
    tmux("send-keys", "-t", target(sid), *keys, check=True)


def send_text(sid: str, text: str, submit: bool = True) -> None:
    if text:
        buf = f"synapse-{sid}"
        # via stdin, never argv: what the user types (maybe a password) must not show up in /proc/<pid>/cmdline,
        # which every local user can read
        tmux("load-buffer", "-b", buf, "-", input_=text, check=True)
        try:
            tmux("paste-buffer", "-p", "-d", "-b", buf, "-t", target(sid), check=True)
        except subprocess.CalledProcessError:
            tmux("delete-buffer", "-b", buf)  # the paste failed: do not leave the text in tmux's buffer list
            raise
        time.sleep(0.15)  # let the TUI digest the paste before Enter
    if submit:
        tmux("send-keys", "-t", target(sid), "Enter", check=True)


def resize_session(sid: str, cols: int | None, rows: int | None) -> None:
    """Fit the tmux window to the phone (cols/rows), or give it back to the desktop (None)."""
    if cols is None or rows is None:
        tmux("resize-window", "-t", target(sid), "-A", check=True)
        return
    cols, rows = min(max(cols, 20), 400), min(max(rows, 5), 200)
    tmux("resize-window", "-t", target(sid), "-x", str(cols), "-y", str(rows), check=True)


def kill_session(sid: str) -> None:
    tmux("kill-session", "-t", target(sid), check=True)


# ---------------------------------------------------------------- state detection

NEEDS_RE = re.compile(
    r"\(y/n\)|\[y/N\]|\[Y/n\]|Do you want to|Would you like to|❯ ?1\. ?Yes|Allow (this|once|always)"
    r"|Esc to cancel|Enter to confirm|Press Enter to continue|waiting for (your )?(input|approval)"
    r"|approve (this|the)|Proceed\?",
    re.I,
)
WORKING_RE = re.compile(r"esc to interrupt|ctrl\+c to interrupt|\(\d+s[ ·)]|Thinking|Working…", re.I)


def tail_lines(text: str, n: int = 14) -> list[str]:
    return [l for l in text.splitlines() if l.strip()][-n:]


def detect_state(text: str, recently_changed: bool) -> str:
    tail = "\n".join(tail_lines(text))
    if NEEDS_RE.search(tail):
        return "needs_input"
    if recently_changed or WORKING_RE.search(tail):
        return "working"
    return "idle"


YN_RE = re.compile(r"\(y/n\)|\[y/N\]|\[Y/n\]|\(yes/no\)|\[yes/no\]", re.I)
MENU_YES_RE = re.compile(r"^\s*[❯›>]\s*\d\.\s*Yes\b", re.M | re.I)


def prompt_actions(plain: str) -> list[dict]:
    """One-tap answers a notification may offer for the prompt currently on screen. Deliberately conservative:
    only prompts whose answer is unambiguous; anything else gets no quick action (open the app)."""
    tail = "\n".join(tail_lines(plain))
    if YN_RE.search(tail):
        return [{"label": "Yes", "keys": ["y", "Enter"]}, {"label": "No", "keys": ["n", "Enter"]}]
    if MENU_YES_RE.search(tail) and re.search(r"esc to cancel", tail, re.I):
        # a menu whose highlighted option is "Yes" (Claude Code style): Enter accepts it, Esc cancels
        return [{"label": "Yes", "keys": ["Enter"]}, {"label": "Cancel", "keys": ["Escape"]}]
    return []


def prompt_sig(plain: str) -> str:
    """Fingerprint of the prompt lines only (a status line that keeps redrawing must not change it)."""
    lines = [l.strip() for l in tail_lines(plain) if NEEDS_RE.search(l)]
    return hashlib.sha1("\n".join(lines).encode()).hexdigest()


def attention_message(text: str) -> str:
    for l in reversed(tail_lines(text)):
        if NEEDS_RE.search(l):
            return l.strip(" │┃|╭╮╰╯─")[:160]
    return "Waiting for input"


# ---------------------------------------------------------------- monitor + event bus


class Hub:
    """Polls tmux once for everybody, caches screens, and fans out events."""

    def __init__(self) -> None:
        self.cv = threading.Condition()
        self.snap: dict[str, dict] = {}
        self.events: list[dict] = []
        self.next_id = int(time.time() * 1000)  # monotonic across bridge restarts: clients can resume with Last-Event-ID
        self.version = 0

    def emit(self, type_: str, session: str, **kw) -> dict:
        with self.cv:
            ev = {"id": self.next_id, "type": type_, "session": session, "ts": time.time(), **kw}
            self.next_id += 1
            self.events.append(ev)
            del self.events[:-200]
            self.cv.notify_all()
            return ev

    def emit_attention(self, sid: str, agent: str, message: str, plain: str) -> None:
        """An attention event with the one-tap answers valid for the prompt on screen right now."""
        ev = self.emit("attention", sid, agent=agent, state="needs_input", message=message,
                       actions=prompt_actions(plain))
        with self.cv:
            s = self.snap.get(sid)
            if s is not None:
                s["attention_id"], s["attention_sig"] = ev["id"], prompt_sig(plain)

    def attention_current(self, sid: str, event_id: int) -> bool:
        """Is `event_id` still the pending prompt of the session (same event, still waiting, same prompt)?"""
        with self.cv:
            s = self.snap.get(sid)
            if not s or s.get("attention_id") != event_id or s.get("state") != "needs_input":
                return False
            return s.get("attention_sig") == prompt_sig(strip_ansi(s.get("text", "")))

    def run(self) -> None:
        while True:
            try:
                self.tick()
            except Exception as e:  # keep the monitor alive whatever tmux does
                print(f"monitor error: {e}", file=sys.stderr)
            time.sleep(0.25)

    def tick(self) -> None:
        now = time.time()
        live = {s["id"]: s for s in list_sessions()}
        for sid, meta in live.items():
            text = capture(sid)
            if text is None:
                continue
            plain = strip_ansi(text)
            cursor = cursor_info(sid)
            digest = hashlib.sha1((text + repr(cursor)).encode()).hexdigest()
            with self.cv:
                s = self.snap.get(sid)
                if s is None:
                    s = self.snap[sid] = {"hash": None, "changed": 0.0, "state": None, "working_since": None,
                                          "text": "", "version": 0}
            changed = digest != s["hash"]
            if changed:
                s["hash"], s["changed"] = digest, now
            state = detect_state(plain, now - s["changed"] < 2.5 and s["state"] is not None)
            prev = s["state"]
            with self.cv:
                if changed or state != prev:
                    self.version += 1
                    s.update(text=text, state=state, cursor=cursor, version=self.version)
                s.update(agent=meta["agent"], cwd=meta["cwd"], created=meta["created"], activity=meta["activity"])
                self.cv.notify_all()
            if state != prev:
                self.emit("state", sid, agent=meta["agent"], state=state)
                if state == "needs_input":
                    self.emit_attention(sid, meta["agent"], attention_message(plain), plain)
                if state == "working":
                    s["working_since"] = now
                elif state == "idle" and prev == "working" and s["working_since"] and now - s["working_since"] >= 8:
                    self.emit("done", sid, agent=meta["agent"], state=state, message="Finished working")
                    s["working_since"] = None
        for sid in [k for k in self.snap if k not in live]:
            with self.cv:
                self.snap.pop(sid, None)
                self.version += 1
                self.cv.notify_all()
            self.emit("state", sid, state="closed")

    def sessions(self) -> list[dict]:
        with self.cv:
            return [{"id": sid, "agent": s.get("agent", ""), "cwd": s.get("cwd", ""), "state": s["state"] or "idle",
                     "created": s.get("created", 0), "activity": s.get("activity", 0)}
                    for sid, s in sorted(self.snap.items())]

    def screen(self, sid: str) -> dict | None:
        with self.cv:
            s = self.snap.get(sid)
            return {"text": s["text"], "state": s["state"] or "idle", "cursor": s.get("cursor")} if s else None

    def wait_screen(self, sid: str, version: int, timeout: float) -> tuple[dict | None, int]:
        with self.cv:
            end = time.time() + timeout
            while True:
                s = self.snap.get(sid)
                if s is None:
                    return None, version
                if s["version"] > version:
                    return {"text": s["text"], "state": s["state"] or "idle", "cursor": s.get("cursor")}, s["version"]
                left = end - time.time()
                if left <= 0:
                    return {"text": s["text"], "state": s["state"] or "idle", "cursor": s.get("cursor")}, version  # keepalive tick
                self.cv.wait(left)

    def wait_events(self, after: int, timeout: float) -> list[dict]:
        with self.cv:
            end = time.time() + timeout
            while True:
                new = [e for e in self.events if e["id"] > after]
                if new:
                    return new
                left = end - time.time()
                if left <= 0:
                    return []
                self.cv.wait(left)

    def last_event_id(self) -> int:
        with self.cv:
            return self.next_id - 1


HUB = Hub()

# ---------------------------------------------------------------- HTTP API

SESSION_PATH = re.compile(r"^/api/sessions/([A-Za-z0-9_.-]{1,40})(?:/(screen|stream|input|keys|resize))?$")


class Handler(BaseHTTPRequestHandler):
    server_version = f"synapse/{VERSION}"
    tls = True
    verbose = False

    def setup(self) -> None:
        if self.tls:
            self.request.do_handshake()
        super().setup()

    def log_message(self, fmt, *args) -> None:
        if self.verbose:
            super().log_message(fmt, *args)

    # -- helpers
    def authed(self) -> bool:
        h = self.headers.get("Authorization", "")
        if not h.startswith("Bearer "):
            return False
        # compare bytes: hmac.compare_digest raises on non-ASCII str, and the header is client-controlled
        return hmac.compare_digest(h[7:].strip().encode("utf-8", "replace"), current_token().encode())

    def send_json(self, code: int, obj) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n < 0 or n > 1_000_000:  # a negative length would make read() wait for EOF
            raise ValueError("bad body length")
        data = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(data, dict):
            raise ValueError("expected a JSON object")
        return data

    def sse_start(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.request.settimeout(None)

    def sse(self, event: str, data, eid: int | None = None) -> None:
        msg = (f"id: {eid}\n" if eid else "") + f"event: {event}\ndata: {json.dumps(data)}\n\n"
        self.wfile.write(msg.encode())
        self.wfile.flush()

    # -- routing
    def dispatch(self, method: str) -> None:
        if not self.authed():
            note = getattr(self.server, "note_auth_failure", None)
            if note:
                note(self.client_address[0])
            return self.send_json(401, {"error": "unauthorized"})
        path = urllib.parse.urlparse(self.path)
        try:
            self.route(method, path.path, urllib.parse.parse_qs(path.query))
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError, socket.timeout):
            pass
        except ValueError as e:
            self.send_json(400, {"error": str(e)})
        except subprocess.CalledProcessError as e:
            self.send_json(500, {"error": (e.stderr or "tmux error").strip()})
        except Exception as e:  # noqa: BLE001
            self.send_json(500, {"error": f"{type(e).__name__}: {e}"})

    def do_GET(self) -> None: self.dispatch("GET")
    def do_POST(self) -> None: self.dispatch("POST")
    def do_DELETE(self) -> None: self.dispatch("DELETE")

    def route(self, method: str, path: str, query: dict) -> None:
        if method == "GET" and path == "/api/health":
            return self.send_json(200, {"ok": True, "host": socket.gethostname(), "version": VERSION,
                                        "agents": sorted(load_config()["agents"])})
        if method == "GET" and path == "/api/sessions":
            return self.send_json(200, {"sessions": HUB.sessions()})
        if method == "POST" and path == "/api/sessions":
            b = self.read_json()
            s = create_session(str(b.get("agent", "")), b.get("cwd"), b.get("name"))
            return self.send_json(201, s)
        if method == "GET" and path == "/api/events":
            return self.stream_events()
        if method == "POST" and path == "/api/hook":
            b = self.read_json()
            sid = str(b.get("session", ""))
            kind = {"Notification": "attention", "Stop": "done"}.get(str(b.get("event", "")))
            if kind and ID_RE.fullmatch(sid) and sid in HUB.snap:
                snap = HUB.snap[sid]
                msg = str(b.get("message", ""))[:200]
                if kind == "attention":
                    HUB.emit_attention(sid, snap.get("agent", ""), msg, strip_ansi(snap.get("text", "")))
                else:
                    HUB.emit(kind, sid, agent=snap.get("agent", ""), state="", message=msg)
            return self.send_json(200, {"ok": True})

        m = SESSION_PATH.fullmatch(path)
        if not m:
            return self.send_json(404, {"error": "not found"})
        sid, action = m.group(1), m.group(2)
        if not session_exists(sid):
            return self.send_json(404, {"error": "no such session"})
        if method == "DELETE" and action is None:
            kill_session(sid)
            return self.send_json(200, {"ok": True})
        if method == "GET" and action == "screen":
            lines = min(max(int(query.get("lines", ["120"])[0]), 1), 2000)
            text = capture(sid, lines)
            if text is None:
                return self.send_json(404, {"error": "no such session"})
            snap = HUB.screen(sid)
            return self.send_json(200, {"text": text, "state": snap["state"] if snap else "idle",
                                        "cursor": cursor_info(sid, lines)})
        if method == "GET" and action == "stream":
            return self.stream_screen(sid)
        if method == "POST" and action == "input":
            b = self.read_json()
            send_text(sid, str(b.get("text", "")), bool(b.get("submit", True)))
            return self.send_json(200, {"ok": True})
        if method == "POST" and action == "resize":
            b = self.read_json()
            cols, rows = b.get("cols"), b.get("rows")
            if cols is not None and not (isinstance(cols, int) and isinstance(rows, int)):
                raise ValueError("cols/rows must be integers")
            resize_session(sid, cols, rows)
            return self.send_json(200, {"ok": True})
        if method == "POST" and action == "keys":
            body = self.read_json()
            keys = body.get("keys", [])
            guard = body.get("if_attention")
            if guard is not None and not HUB.attention_current(sid, int(guard)):
                # a notification action must never answer a different prompt than the one the user saw
                return self.send_json(409, {"error": "stale", "detail": "that prompt is no longer pending"})
            send_keys(sid, keys if isinstance(keys, list) else [])
            return self.send_json(200, {"ok": True})
        self.send_json(405, {"error": "method not allowed"})

    # -- streams
    def stream_screen(self, sid: str) -> None:
        self.sse_start()
        version = -1
        first = True
        missing = 0
        while True:
            snap, new_version = HUB.wait_screen(sid, version, 15)
            if snap is None:
                # just created: the monitor has not seen it yet; only give up if the session is really gone
                missing += 1
                if missing > 12 or not session_exists(sid):
                    return
                time.sleep(0.25)
                continue
            if new_version != version or first:
                self.sse("screen", snap)
                first = False
            else:
                self.wfile.write(b": keepalive\n\n")
                self.wfile.flush()
            version = new_version

    def stream_events(self) -> None:
        last = int(self.headers.get("Last-Event-ID") or HUB.last_event_id())  # may raise: before any bytes are sent
        self.sse_start()
        self.wfile.write(b": connected\n\n")
        self.wfile.flush()
        while True:
            evs = HUB.wait_events(last, 15)
            if not evs:
                self.wfile.write(b": keepalive\n\n")
                self.wfile.flush()
            for e in evs:
                # age lets a client tell a replayed (missed while offline) event from a live one
                self.sse("event", {**e, "age": round(time.time() - e["ts"], 1)}, e["id"])
                last = e["id"]


_count_lock = threading.Lock()
MAX_CONNECTIONS = 128  # every connection costs a thread; an unauthenticated flood must not exhaust them
PER_IP_CONNECTIONS = 16  # one LAN host must not be able to take every slot and lock the phone out


def security_log(msg: str) -> None:
    print(f"security: {msg}", file=sys.stderr, flush=True)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    ctx: ssl.SSLContext | None = None

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.active = 0
        self.per_ip: dict[str, int] = {}
        # Loopback is the tailnet sidecar (it already filters by account) and adb reverse: never throttled per IP.
        self.exempt_loopback = True
        self.auth_failures: dict[str, list] = {}

    def verify_request(self, request, client_address) -> bool:
        ip = client_address[0]
        with _count_lock:  # reserve the slot here, in the accept thread, so a burst cannot overshoot the caps
            if self.active >= MAX_CONNECTIONS:
                return False
            limited = not (self.exempt_loopback and ip in ("127.0.0.1", "::1"))
            if limited and self.per_ip.get(ip, 0) >= PER_IP_CONNECTIONS:
                return False
            self.active += 1
            self.per_ip[ip] = self.per_ip.get(ip, 0) + 1
        return True

    def process_request_thread(self, request, client_address) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            ip = client_address[0]
            with _count_lock:
                self.active -= 1
                left = self.per_ip.get(ip, 1) - 1
                if left > 0:
                    self.per_ip[ip] = left
                else:
                    self.per_ip.pop(ip, None)

    def note_auth_failure(self, ip: str) -> None:
        """Log failed authentications (at most once a minute per address) so an attack is visible in the journal."""
        now = time.time()
        with _count_lock:
            first, count, logged = self.auth_failures.get(ip, [now, 0, 0.0])
            count += 1
            should_log = now - logged >= 60
            self.auth_failures[ip] = [first, count, now if should_log else logged]
            if len(self.auth_failures) > 1024:  # bounded memory under a spray of addresses
                self.auth_failures.pop(next(iter(self.auth_failures)))
        if should_log:
            security_log(f"{count} failed authentication(s) from {ip}")

    def get_request(self):
        sock, addr = super().get_request()
        if self.ctx:
            sock.settimeout(5)  # handshake and request line must arrive quickly; SSE removes it after auth
            sock = self.ctx.wrap_socket(sock, server_side=True, do_handshake_on_connect=False)
        return sock, addr

    def handle_error(self, request, client_address) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, (ssl.SSLError, ConnectionError, socket.timeout, OSError)):
            return  # port scanners, phones going to sleep, ...
        super().handle_error(request, client_address)


def make_server(host: str, port: int, tls: bool, verbose: bool = False) -> "Server":
    get_token()  # make sure a token exists
    Handler.tls = tls
    Handler.verbose = verbose
    srv = Server((host, port), Handler)
    if tls:
        cert, key = ensure_cert()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_3  # both ends are ours: no legacy fallback
        ctx.load_cert_chain(cert, key)
        srv.ctx = ctx
    return srv


def serve(host: str, port: int, tls: bool, verbose: bool) -> None:
    if not shutil.which("tmux"):
        sys.exit("synapse: tmux not found")
    srv = make_server(host, port, tls, verbose)
    threading.Thread(target=HUB.run, daemon=True).start()
    print(f"synapse {VERSION} listening on {'https' if tls else 'http'}://{host}:{port}", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


# ---------------------------------------------------------------- CLI helpers


def lan_hosts() -> list[str]:
    """Addresses the phone could plausibly reach, most likely first. VPN-egress tunnels are skipped."""
    r = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True)
    lan, other = [], []
    for line in r.stdout.splitlines():
        f = line.split()
        iface, addr = f[1], f[3].split("/")[0]
        if iface == "lo" or re.match(r"(docker|br-|veth|virbr|wg|tun)", iface) and "tailscale" not in iface:
            continue
        private = addr.startswith(("192.168.", "10.")) or re.match(r"172\.(1[6-9]|2\d|3[01])\.", addr)
        (lan if private and not iface.startswith("tailscale") else other).append(addr)
    return lan + other


def parse_tailscale_status(raw: str) -> list[str]:
    """MagicDNS name first (stable, survives IP changes), then the 100.x address."""
    try:
        me = json.loads(raw).get("Self") or {}
    except ValueError:
        return []
    out = []
    name = (me.get("DNSName") or "").rstrip(".")
    if name:
        out.append(name)
    out += [ip for ip in me.get("TailscaleIPs", []) if ":" not in ip][:1]
    return out


TAILNET_DIR = CONFIG_DIR / "tailnet"


def tailnet_status() -> dict:
    """Status written by the synapse-net sidecar (embedded tailnet node), plus whether the service runs."""
    try:
        st = json.loads((TAILNET_DIR / "status.json").read_text())
    except (OSError, ValueError):
        st = {"state": "stopped"}
    active = subprocess.run(["systemctl", "--user", "is-active", "--quiet", "synapse-net.service"]).returncode == 0
    if not active:
        st = {"state": "stopped"}
    st["active"] = active
    return st


def embedded_tailnet_hosts() -> list[str]:
    st = tailnet_status()
    if st.get("state") != "running":
        return []
    ips = [i for i in st.get("ips", []) if ":" not in i][:1]
    return ([st["dns_name"]] if st.get("dns_name") else []) + ips


def tailscale_hosts() -> list[str]:
    if not shutil.which("tailscale"):
        return []
    try:
        r = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=3)
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_tailscale_status(r.stdout) if r.returncode == 0 else []


def local_api(path: str, method: str = "GET", body: dict | None = None, timeout: float = 2.0):
    cfg = load_config()
    # The token goes to whatever listens on 127.0.0.1:<port>. Trust only our own certificate (pinned as the sole
    # CA), so a process that grabbed the port while the bridge was down cannot harvest it.
    cert, _ = ensure_cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_verify_locations(cafile=str(cert))
    ctx.check_hostname = False  # identity is the pinned certificate, not a hostname
    req = urllib.request.Request(
        f"https://127.0.0.1:{cfg['port']}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {get_token()}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            return json.loads(r.read() or b"{}")
    except Exception:  # noqa: BLE001
        return None


def pair_uri(hosts: list[str]) -> str:
    q = urllib.parse.urlencode({"hosts": ",".join(hosts), "port": load_config()["port"], "token": get_token(),
                                "fp": cert_fingerprint(), "name": socket.gethostname()}, safe=",")
    return f"synapse://pair?{q}"


def cmd_pair(a) -> None:
    hosts = a.host or list(dict.fromkeys(lan_hosts() + embedded_tailnet_hosts() + tailscale_hosts()))
    if not hosts:
        sys.exit("synapse: no network address found, pass --host")
    uri = pair_uri(hosts)
    fp = cert_fingerprint()
    # The URI carries the token: hand it to qrencode on stdin (argv is world-readable in /proc).
    if a.format == "uri":
        print(uri)
    elif a.format == "svg":
        sys.stdout.write(subprocess.run(["qrencode", "-t", "SVG", "-o", "-", "-m", "2"], input=uri,
                                        capture_output=True, text=True, check=True).stdout)
    elif a.format == "json":
        print(json.dumps({"uri": uri, "hosts": hosts, "fingerprint": fp}))
    else:
        if shutil.which("qrencode"):
            subprocess.run(["qrencode", "-t", "ANSIUTF8", "-m", "2"], input=uri, text=True)
        else:
            print("(install `qrencode` to see a QR code here, or use --show-uri)")
        print(f"\nHosts offered to the phone: {', '.join(hosts)}")
        print(f"Certificate: {fp[:16]}…  (the app shows the same prefix before pairing: compare them)")
        print("The QR contains the access token: do not share a screenshot of it.")
        if a.show_uri:
            print(f"\n{uri}")
        else:
            print("(the link itself is not printed, so it does not stay in your scrollback: --show-uri)")


def cmd_run(a) -> None:
    cwd = a.cwd or os.getcwd()
    s = create_session(a.agent, cwd, a.name, allow_raw_command=True)
    print(f"session '{s['id']}' started", file=sys.stderr)
    if a.detach:
        return
    if os.environ.get("TMUX"):
        os.execvp("tmux", ["tmux", "switch-client", "-t", PREFIX + s["id"]])
    os.execvp("tmux", ["tmux", "attach", "-t", target(s["id"])])


def cmd_ls(a) -> None:
    sessions = (local_api("/api/sessions") or {}).get("sessions") or [
        {**s, "state": "?"} for s in list_sessions()]
    if a.json:
        print(json.dumps(sessions))
    for s in ([] if a.json else sessions):
        print(f"{s['id']:<28} {s['agent']:<8} {s['state']:<12} {s['cwd']}")


def cmd_revoke(a) -> None:
    """Cut off every paired phone: new token (effective immediately), and with --cert a new certificate."""
    get_token(rotate=True)
    print("token rotated: every paired phone is refused from now on (no restart needed)")
    if a.cert:
        for f in ("cert.pem", "key.pem"):
            (CONFIG_DIR / f).unlink(missing_ok=True)
        ensure_cert()
        subprocess.run(["systemctl", "--user", "try-restart", "synapse.service"])
        print("certificate regenerated and the bridge restarted: phones must be paired again anyway")
    print("pair a phone again with:  synapse pair")


def cmd_tailnet(a) -> None:
    st = tailnet_status()
    if a.action == "logs":
        os.execvp("journalctl", ["journalctl", "--user", "-u", "synapse-net.service", "-n", "60", "--no-pager"])
    if a.action == "login":
        url = st.get("auth_url")
        if st["state"] == "needs_login" and url:
            print(f"Sign in to add this machine to your tailnet:\n  {url}")
            if shutil.which("xdg-open") and url.startswith("https://"):
                subprocess.Popen(["xdg-open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
    if a.json:
        print(json.dumps(st))
        return
    if not st["active"]:
        print("tailnet: sidecar not running (systemctl --user enable --now synapse-net, or ./install.sh)")
        return
    print(f"tailnet: {st['state']}")
    if st.get("auth_url") and st["state"] == "needs_login":
        print(f"  sign in: {st['auth_url']}   (or: synapse tailnet login)")
    if st.get("dns_name"):
        print(f"  name: {st['dns_name']}\n  ips:  {', '.join(st.get('ips', []))}")


def cmd_status(a) -> None:
    """Snapshot consumed by the Omarchy bar plugin."""
    health = local_api("/api/health")
    sessions = (local_api("/api/sessions") or {}).get("sessions", []) if health else []
    print(json.dumps({"running": bool(health), "port": load_config()["port"], "sessions": sessions,
                      "attention": sum(1 for s in sessions if s["state"] == "needs_input"),
                      "tailnet": tailnet_status()}))


def cmd_hook(_a) -> None:
    """Claude Code hook: forwards Notification/Stop events so the phone gets the real message."""
    sid = os.environ.get("SYNAPSE_SESSION")
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        payload = {}
    if sid:
        local_api("/api/hook", "POST", {"session": sid, "event": payload.get("hook_event_name", ""),
                                        "message": payload.get("message", "")})


def cmd_claude_hooks(_a) -> None:
    exe = shutil.which("synapse") or str(Path(__file__).resolve())
    hook = [{"hooks": [{"type": "command", "command": f"{exe} hook"}]}]
    print(json.dumps({"hooks": {"Notification": hook, "Stop": hook}}, indent=2))
    print("\n# Merge the \"hooks\" block above into ~/.claude/settings.json", file=sys.stderr)


def main() -> None:
    p = argparse.ArgumentParser(prog="synapse", description=__doc__.splitlines()[0])
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve", help="run the bridge server")
    s.add_argument("--host", default=None, help='address to bind (default: config "host", else 0.0.0.0)')
    s.add_argument("--port", type=int, default=None)
    s.add_argument("--no-tls", action="store_true", help="plain HTTP (development only!)")
    s.add_argument("-v", "--verbose", action="store_true")
    s.set_defaults(fn=lambda a: serve(a.host or load_config()["host"], a.port or load_config()["port"],
                                      not a.no_tls, a.verbose))

    s = sub.add_parser("pair", help="show the QR code to pair the phone")
    s.add_argument("--host", action="append", help="address the phone should use (repeatable)")
    s.add_argument("--format", choices=["ansi", "svg", "uri", "json"], default="ansi")
    s.add_argument("--show-uri", action="store_true", help="also print the synapse:// link (contains the token)")
    s.set_defaults(fn=cmd_pair)

    s = sub.add_parser("run", help="start an agent in a phone-controllable tmux session and attach to it")
    s.add_argument("agent", help="claude | codex | kimi | grok | any command")
    s.add_argument("-n", "--name")
    s.add_argument("-C", "--cwd")
    s.add_argument("-d", "--detach", action="store_true")
    s.set_defaults(fn=cmd_run)

    s = sub.add_parser("ls", help="list sessions")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_ls)

    s = sub.add_parser("kill", help="kill a session")
    s.add_argument("id")
    s.set_defaults(fn=lambda a: kill_session(a.id))

    s = sub.add_parser("attach", help="attach to a session in this terminal")
    s.add_argument("id")
    s.set_defaults(fn=lambda a: os.execvp("tmux", ["tmux", "attach", "-t", target(a.id)]))

    s = sub.add_parser("token", help="print (or --rotate) the access token")
    s.add_argument("--rotate", action="store_true")
    s.set_defaults(fn=lambda a: print(get_token(a.rotate)))

    s = sub.add_parser("tailnet", help="state of the embedded tailnet node (synapse-net): status | login | logs")
    s.add_argument("action", nargs="?", default="status", choices=["status", "login", "logs"])
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_tailnet)

    s = sub.add_parser("revoke", help="revoke every paired phone (rotate the token; --cert also the certificate)")
    s.add_argument("--cert", action="store_true")
    s.set_defaults(fn=cmd_revoke)

    sub.add_parser("status", help="JSON snapshot for the bar plugin").set_defaults(fn=cmd_status)
    sub.add_parser("hook", help="Claude Code hook entry point (reads JSON on stdin)").set_defaults(fn=cmd_hook)
    sub.add_parser("claude-hooks", help="print the Claude Code hooks snippet").set_defaults(fn=cmd_claude_hooks)

    a = p.parse_args()
    try:
        a.fn(a)
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as e:
        sys.exit(f"synapse: {getattr(e, 'stderr', None) or e}")


if __name__ == "__main__":
    main()
