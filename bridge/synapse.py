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
    cfg = {"port": DEFAULT_PORT, "agents": dict(DEFAULT_AGENTS)}
    try:
        user = json.loads((CONFIG_DIR / "config.json").read_text())
        cfg["port"] = int(user.get("port", cfg["port"]))
        cfg["agents"].update(user.get("agents", {}))
    except (OSError, ValueError):
        pass
    return cfg


def get_token(rotate: bool = False) -> str:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = CONFIG_DIR / "token"
    if rotate or not path.exists():
        path.write_text(secrets.token_urlsafe(32))
        path.chmod(0o600)
    return path.read_text().strip()


def ensure_cert() -> tuple[Path, Path]:
    """Self-signed EC cert, created once. The phone pins its SHA-256 (from the QR)."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    cert, key = CONFIG_DIR / "cert.pem", CONFIG_DIR / "key.pem"
    if not (cert.exists() and key.exists()):
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
             "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "3650", "-subj", "/CN=synapse"],
            check=True, capture_output=True,
        )
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
        if not ID_RE.match(name):
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
    return k in NAMED_KEYS or bool(CHORD_RE.match(k)) or len(k) == 1


def send_keys(sid: str, keys: list[str]) -> None:
    if not keys or not all(isinstance(k, str) and valid_key(k) for k in keys):
        raise ValueError("invalid keys")
    tmux("send-keys", "-t", target(sid), *keys, check=True)


def send_text(sid: str, text: str, submit: bool = True) -> None:
    if text:
        buf = f"synapse-{sid}"
        tmux("set-buffer", "-b", buf, "--", text, check=True)
        tmux("paste-buffer", "-p", "-d", "-b", buf, "-t", target(sid), check=True)
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
        self.next_id = 1
        self.version = 0

    def emit(self, type_: str, session: str, **kw) -> None:
        with self.cv:
            ev = {"id": self.next_id, "type": type_, "session": session, "ts": time.time(), **kw}
            self.next_id += 1
            self.events.append(ev)
            del self.events[:-200]
            self.cv.notify_all()

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
            digest = hashlib.sha1(text.encode()).hexdigest()
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
                    s.update(text=text, state=state, version=self.version)
                s.update(agent=meta["agent"], cwd=meta["cwd"], created=meta["created"], activity=meta["activity"])
                self.cv.notify_all()
            if state != prev:
                self.emit("state", sid, agent=meta["agent"], state=state)
                if state == "needs_input":
                    self.emit("attention", sid, agent=meta["agent"], state=state, message=attention_message(plain))
                if state == "working":
                    s["working_since"] = now
                elif prev == "working" and s["working_since"] and now - s["working_since"] >= 8:
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
            return {"text": s["text"], "state": s["state"] or "idle"} if s else None

    def wait_screen(self, sid: str, version: int, timeout: float) -> tuple[dict | None, int]:
        with self.cv:
            end = time.time() + timeout
            while True:
                s = self.snap.get(sid)
                if s is None:
                    return None, version
                if s["version"] > version:
                    return {"text": s["text"], "state": s["state"] or "idle"}, s["version"]
                left = end - time.time()
                if left <= 0:
                    return {"text": s["text"], "state": s["state"] or "idle"}, version  # keepalive tick
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
    token = ""
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
        return h.startswith("Bearer ") and hmac.compare_digest(h[7:].strip(), self.token)

    def send_json(self, code: int, obj) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise ValueError("body too large")
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
            if kind and ID_RE.match(sid) and sid in HUB.snap:
                HUB.emit(kind, sid, agent=HUB.snap[sid].get("agent", ""), state="", message=str(b.get("message", ""))[:200])
            return self.send_json(200, {"ok": True})

        m = SESSION_PATH.match(path)
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
            return self.send_json(200, {"text": text, "state": snap["state"] if snap else "idle"})
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
            keys = self.read_json().get("keys", [])
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
        self.sse_start()
        last = int(self.headers.get("Last-Event-ID") or HUB.last_event_id())
        self.wfile.write(b": connected\n\n")
        self.wfile.flush()
        while True:
            evs = HUB.wait_events(last, 15)
            if not evs:
                self.wfile.write(b": keepalive\n\n")
                self.wfile.flush()
            for e in evs:
                self.sse("event", e, e["id"])
                last = e["id"]


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    ctx: ssl.SSLContext | None = None

    def get_request(self):
        sock, addr = super().get_request()
        if self.ctx:
            sock.settimeout(10)
            sock = self.ctx.wrap_socket(sock, server_side=True, do_handshake_on_connect=False)
        return sock, addr

    def handle_error(self, request, client_address) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, (ssl.SSLError, ConnectionError, socket.timeout, OSError)):
            return  # port scanners, phones going to sleep, ...
        super().handle_error(request, client_address)


def serve(host: str, port: int, tls: bool, verbose: bool) -> None:
    if not shutil.which("tmux"):
        sys.exit("synapse: tmux not found")
    Handler.token = get_token()
    Handler.tls = tls
    Handler.verbose = verbose
    srv = Server((host, port), Handler)
    if tls:
        cert, key = ensure_cert()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_3  # both ends are ours: no legacy fallback
        ctx.load_cert_chain(cert, key)
        srv.ctx = ctx
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


def local_api(path: str, method: str = "GET", body: dict | None = None, timeout: float = 2.0):
    cfg = load_config()
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE  # loopback to ourselves
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
    hosts = a.host or lan_hosts()
    if not hosts:
        sys.exit("synapse: no network address found, pass --host")
    uri = pair_uri(hosts)
    if a.format == "uri":
        print(uri)
    elif a.format == "svg":
        sys.stdout.write(subprocess.run(["qrencode", "-t", "SVG", "-o", "-", "-m", "2", uri],
                                        capture_output=True, text=True, check=True).stdout)
    elif a.format == "json":
        print(json.dumps({"uri": uri, "hosts": hosts}))
    else:
        if shutil.which("qrencode"):
            subprocess.run(["qrencode", "-t", "ANSIUTF8", "-m", "2", uri])
        else:
            print("(install `qrencode` to see a QR code here)")
        print(f"\nHosts offered to the phone: {', '.join(hosts)}")
        print("The QR contains the access token: do not share a screenshot of it.\n")
        print(uri)


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


def cmd_status(a) -> None:
    """Snapshot consumed by the Omarchy bar plugin."""
    health = local_api("/api/health")
    sessions = (local_api("/api/sessions") or {}).get("sessions", []) if health else []
    print(json.dumps({"running": bool(health), "port": load_config()["port"], "sessions": sessions,
                      "attention": sum(1 for s in sessions if s["state"] == "needs_input")}))


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
    s.add_argument("--host", default="0.0.0.0")
    s.add_argument("--port", type=int, default=None)
    s.add_argument("--no-tls", action="store_true", help="plain HTTP (development only!)")
    s.add_argument("-v", "--verbose", action="store_true")
    s.set_defaults(fn=lambda a: serve(a.host, a.port or load_config()["port"], not a.no_tls, a.verbose))

    s = sub.add_parser("pair", help="show the QR code to pair the phone")
    s.add_argument("--host", action="append", help="address the phone should use (repeatable)")
    s.add_argument("--format", choices=["ansi", "svg", "uri", "json"], default="ansi")
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
