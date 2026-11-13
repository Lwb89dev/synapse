"""Security tests for the bridge: python3 -m unittest bridge.test_security

A real TLS server runs in-process on a random port with a throwaway config dir (no tmux needed): these tests attack
authentication, token revocation, malformed requests, the TLS floor, connection floods, the local API's certificate
pinning and the permissions of the secret files.
"""
import http.client
import importlib.util
import json
import os
import pathlib
import socket
import ssl
import stat
import subprocess
import tempfile
import threading
import time
import unittest

TMP = pathlib.Path(tempfile.mkdtemp(prefix="synapse-sec-"))
os.environ["SYNAPSE_CONFIG_DIR"] = str(TMP)  # must be set before the module is imported

_spec = importlib.util.spec_from_file_location("synapse_sec", pathlib.Path(__file__).with_name("synapse.py"))
syn = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(syn)

UNVERIFIED = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
UNVERIFIED.check_hostname = False
UNVERIFIED.verify_mode = ssl.CERT_NONE


class ServerCase(unittest.TestCase):
    srv = None
    port = 0

    @classmethod
    def setUpClass(cls):
        syn.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        cls.srv = syn.make_server("127.0.0.1", 0, True)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    @property
    def token(self):
        return syn.get_token()

    def call(self, method, path, token="", body=None, headers=None, timeout=4):
        c = http.client.HTTPSConnection("127.0.0.1", self.port, context=UNVERIFIED, timeout=timeout)
        h = dict(headers or {})
        if token:
            h["Authorization"] = f"Bearer {token}"
        if body is not None and not isinstance(body, (bytes, str)):
            body = json.dumps(body)
        c.request(method, path, body=body, headers=h)
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data

    def raw(self, payload: bytes, timeout=4) -> bytes:
        """Send bytes over TLS exactly as given (to test things http.client refuses to send)."""
        s = socket.create_connection(("127.0.0.1", self.port), timeout=timeout)
        t = UNVERIFIED.wrap_socket(s)
        t.sendall(payload)
        out = b""
        try:
            while True:
                chunk = t.recv(4096)
                if not chunk:
                    break
                out += chunk
        except (socket.timeout, ssl.SSLError, ConnectionError):
            pass
        t.close()
        return out

    def alive(self):
        return self.call("GET", "/api/health", self.token)[0] == 200


class Authentication(ServerCase):
    def test_valid_token_is_accepted(self):
        self.assertEqual(self.call("GET", "/api/health", self.token)[0], 200)

    def test_missing_wrong_and_malformed_credentials_are_refused(self):
        for tok in ("", "x", self.token[:-1], self.token + "x", "A" * 5000):
            self.assertEqual(self.call("GET", "/api/health", tok)[0], 401, tok[:12])
        # token in the query string, other schemes, lowercase scheme
        self.assertEqual(self.call("GET", f"/api/health?token={self.token}")[0], 401)
        self.assertEqual(self.call("GET", "/api/health", headers={"Authorization": f"Basic {self.token}"})[0], 401)
        self.assertEqual(self.call("GET", "/api/health", headers={"Authorization": f"bearer {self.token}"})[0], 401)
        self.assertEqual(self.call("GET", "/api/health", headers={"Authorization": "Bearer"})[0], 401)

    def test_non_ascii_authorization_header_is_refused_not_a_crash(self):
        out = self.raw(b"GET /api/health HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer \xff\xfe\xc3\x28\r\n\r\n")
        self.assertIn(b" 401 ", out.split(b"\r\n")[0])
        self.assertTrue(self.alive())

    def test_every_route_requires_auth(self):
        for method, path in [("GET", "/api/sessions"), ("POST", "/api/sessions"), ("GET", "/api/events"),
                             ("POST", "/api/hook"), ("GET", "/api/sessions/x/screen"),
                             ("POST", "/api/sessions/x/keys"), ("POST", "/api/sessions/x/input"),
                             ("POST", "/api/sessions/x/resize"), ("DELETE", "/api/sessions/x"),
                             ("GET", "/api/sessions/x/stream"), ("GET", "/nonexistent")]:
            self.assertEqual(self.call(method, path, body="{}" if method == "POST" else None)[0], 401,
                             f"{method} {path} answered without credentials")

    def test_rotating_the_token_revokes_the_old_one_immediately(self):
        old = self.token
        self.assertEqual(self.call("GET", "/api/health", old)[0], 200)
        new = syn.get_token(rotate=True)
        try:
            self.assertNotEqual(old, new)
            self.assertEqual(self.call("GET", "/api/health", old)[0], 401, "old token still accepted after rotation")
            self.assertEqual(self.call("GET", "/api/health", new)[0], 200)
        finally:
            self.assertTrue(self.alive())


class MalformedRequests(ServerCase):
    def test_bad_bodies_are_rejected_quickly(self):
        t = self.token
        self.assertEqual(self.call("POST", "/api/sessions", t, body="not json")[0], 400)
        self.assertEqual(self.call("POST", "/api/sessions", t, body="[1,2,3]")[0], 400)
        self.assertEqual(self.call("POST", "/api/hook", t, body='"str"')[0], 400)
        # negative / absurd Content-Length must not make the handler wait for EOF
        t0 = time.time()
        out = self.raw(f"POST /api/sessions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\n"
                       f"Content-Length: -5\r\n\r\n".encode(), timeout=6)
        self.assertIn(b" 400 ", out.split(b"\r\n")[0])
        self.assertLess(time.time() - t0, 3)
        out = self.raw(f"POST /api/sessions HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\n"
                       f"Content-Length: 99999999999\r\n\r\n".encode())
        self.assertIn(b" 400 ", out.split(b"\r\n")[0])
        self.assertTrue(self.alive())

    def test_deeply_nested_json_does_not_kill_the_server(self):
        body = "[" * 200000 + "]" * 200000
        status, _ = self.call("POST", "/api/sessions", self.token, body=body)
        self.assertIn(status, (400, 500))
        self.assertTrue(self.alive())

    def test_bad_parameters_never_crash(self):
        t = self.token
        # an unknown session is a 404 before any parameter is trusted
        for q in ("abc", "-1", "99999999999999999999", "1;2", ""):
            self.assertEqual(self.call("GET", f"/api/sessions/x/screen?lines={q}", t)[0], 404, q)

    def test_bad_last_event_id_is_a_clean_400_before_any_stream_bytes(self):
        out = self.raw(f"GET /api/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {self.token}\r\n"
                       f"Last-Event-ID: abc\r\n\r\n".encode())
        self.assertIn(b" 400 ", out.split(b"\r\n")[0])
        self.assertNotIn(b"text/event-stream", out)

    def test_paths_cannot_escape_the_api(self):
        t = self.token
        for path in ("/api/sessions/../../etc/passwd", "/api/sessions/%2e%2e/screen", "/api/sessions/..",
                     "/api/sessions/a%00b/screen", "/api/sessions/" + "a" * 5000 + "/screen", "/api/sessions/x/../../health"):
            status, _ = self.call("GET", path, t)
            self.assertIn(status, (400, 404, 414), path[:40])
        self.assertTrue(self.alive())

    def test_session_creation_cannot_run_arbitrary_commands_or_escape_names(self):
        t = self.token
        for agent in ("claude; touch /tmp/pwned", "$(id)", "../../bin/sh", "", "rm -rf ~", "claude\nid"):
            status, _ = self.call("POST", "/api/sessions", t, body={"agent": agent})
            self.assertEqual(status, 400, repr(agent))
        for name in ("a;b", "../x", "x y", "a" * 200, "$(id)", "`id`", "x\n"):
            status, _ = self.call("POST", "/api/sessions", t, body={"agent": "claude", "name": name})
            self.assertEqual(status, 400, repr(name))
        status, _ = self.call("POST", "/api/sessions", t, body={"agent": "claude", "cwd": "/definitely/not/a/dir"})
        self.assertEqual(status, 400)
        self.assertFalse(os.path.exists("/tmp/pwned"))

    def test_unsupported_methods_do_not_crash(self):
        for m in ("PUT", "PATCH", "OPTIONS", "TRACE", "HEAD"):
            status, _ = self.call(m, "/api/health", self.token)
            self.assertIn(status, (401, 404, 405, 501), m)
        self.assertTrue(self.alive())


class MirrorIsReadOnly(ServerCase):
    """An agent running in a plain terminal is shown from its transcript; nothing may be typed into it or killed."""

    def setUp(self):
        syn.HUB.snap["ext-claude-1"] = {"text": "hello", "state": "idle", "agent": "claude", "version": 1, "readonly": True}

    def tearDown(self):
        syn.HUB.snap.pop("ext-claude-1", None)

    def test_it_can_be_read(self):
        t = self.token
        code, body = self.call("GET", "/api/sessions/ext-claude-1/screen", t)
        self.assertEqual(code, 200)
        self.assertTrue(json.loads(body)["readonly"])
        sessions = json.loads(self.call("GET", "/api/sessions", t)[1])["sessions"]
        self.assertTrue([s for s in sessions if s["id"] == "ext-claude-1"][0]["readonly"])

    def test_it_cannot_be_driven_or_killed(self):
        t = self.token
        for method, path, body in [("POST", "/api/sessions/ext-claude-1/input", {"text": "rm -rf ~"}),
                                   ("POST", "/api/sessions/ext-claude-1/keys", {"keys": ["Enter"]}),
                                   ("POST", "/api/sessions/ext-claude-1/resize", {"cols": 40, "rows": 20}),
                                   ("DELETE", "/api/sessions/ext-claude-1", None)]:
            self.assertEqual(self.call(method, path, t, body=body)[0], 409, f"{method} {path}")
        self.assertEqual(self.call("POST", "/api/sessions", t, body={"agent": "claude", "name": "ext-x"})[0], 400)
        self.assertEqual(self.call("GET", "/api/sessions/ext-nope/screen", t)[0], 404)


class TransportSecurity(ServerCase):
    def test_tls_below_1_3_is_refused(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
        ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        with self.assertRaises((ssl.SSLError, ConnectionError)):
            with socket.create_connection(("127.0.0.1", self.port), timeout=3) as s:
                ctx.wrap_socket(s)

    def test_negotiates_tls_1_3(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as s:
            with UNVERIFIED.wrap_socket(s) as t:
                self.assertEqual(t.version(), "TLSv1.3")

    def test_plain_http_on_the_tls_port_is_dropped_without_a_response_or_crash(self):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=3)
        s.sendall(b"GET /api/health HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + self.token.encode() + b"\r\n\r\n")
        s.settimeout(3)
        try:
            data = s.recv(4096)
        except (socket.timeout, ConnectionError):
            data = b""
        s.close()
        self.assertNotIn(b"200 OK", data, "a plaintext request must never be answered (token would be exposed)")
        self.assertTrue(self.alive())

    def test_certificate_is_ec_and_key_is_private(self):
        cert, key = syn.ensure_cert()
        self.assertEqual(stat.S_IMODE(key.stat().st_mode), 0o600)
        text = subprocess.run(["openssl", "x509", "-in", str(cert), "-noout", "-text"],
                              capture_output=True, text=True).stdout
        self.assertIn("prime256v1", text)


class DenialOfService(ServerCase):
    def test_connection_flood_is_capped_and_recovers(self):
        socks = []
        try:
            for _ in range(syn.MAX_CONNECTIONS + 60):
                try:
                    socks.append(socket.create_connection(("127.0.0.1", self.port), timeout=2))
                except OSError:
                    break
            time.sleep(0.5)
            self.assertLessEqual(self.srv.active, syn.MAX_CONNECTIONS, "thread count must be bounded")
        finally:
            for s in socks:
                s.close()
        deadline = time.time() + 12  # idle pre-auth connections are dropped after 5 s
        while time.time() < deadline and self.srv.active:
            time.sleep(0.3)
        self.assertEqual(self.srv.active, 0, "connections were not reclaimed")
        self.assertTrue(self.alive())

    def test_slow_unauthenticated_client_is_dropped(self):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=10)
        t0 = time.time()
        s.settimeout(10)
        try:
            s.recv(1)  # we never handshake: the server must give up by itself
        except (socket.timeout, ConnectionError, OSError):
            pass
        s.close()
        self.assertLess(time.time() - t0, 8)


class PerAddressLimits(ServerCase):
    def test_one_address_cannot_take_every_slot(self):
        self.srv.exempt_loopback = False  # the test can only connect from loopback: treat it as a LAN host
        socks = []
        try:
            for _ in range(syn.PER_IP_CONNECTIONS + 20):
                try:
                    socks.append(socket.create_connection(("127.0.0.1", self.port), timeout=2))
                except OSError:
                    break
            time.sleep(0.5)
            self.assertLessEqual(self.srv.per_ip.get("127.0.0.1", 0), syn.PER_IP_CONNECTIONS)
            self.assertLessEqual(self.srv.active, syn.PER_IP_CONNECTIONS)
        finally:
            for s_ in socks:
                s_.close()
            self.srv.exempt_loopback = True
        deadline = time.time() + 12
        while time.time() < deadline and self.srv.active:
            time.sleep(0.3)
        self.assertEqual(self.srv.per_ip, {}, "per-address counters leaked")
        self.assertTrue(self.alive())

    def test_failed_logins_are_logged_but_rate_limited(self):
        logged = []
        real = syn.security_log
        syn.security_log = logged.append
        self.srv.auth_failures.clear()
        try:
            for _ in range(5):
                self.call("GET", "/api/health", "wrong")
        finally:
            syn.security_log = real
        self.assertEqual(len(logged), 1, logged)
        self.assertIn("127.0.0.1", logged[0])
        self.assertEqual(self.srv.auth_failures["127.0.0.1"][1], 5)


class SecretsStayOutOfArgv(unittest.TestCase):
    """/proc/<pid>/cmdline is readable by every local user: secrets must travel on stdin."""

    def test_typed_text_goes_to_tmux_on_stdin(self):
        calls = []
        real = syn.tmux
        syn.tmux = lambda *a, check=False, input_=None: calls.append((a, input_))
        try:
            syn.send_text("demo", "hunter2 my password", submit=False)
        finally:
            syn.tmux = real
        self.assertTrue(calls)
        for args, stdin in calls:
            self.assertFalse(any("hunter2" in x for x in args), args)
        self.assertIn("hunter2 my password", [stdin for _, stdin in calls])

    def _run_pair(self, fmt, show_uri=False):
        import argparse
        import contextlib
        import io
        seen = []

        class Done:
            stdout = ""

        def fake_run(cmd, *a, **kw):
            seen.append((list(cmd), kw.get("input")))
            return Done()

        real_run, real_which = syn.subprocess.run, syn.shutil.which
        syn.subprocess.run = fake_run
        syn.shutil.which = lambda name: "/usr/bin/" + name
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                syn.cmd_pair(argparse.Namespace(host=["127.0.0.1"], format=fmt, show_uri=show_uri))
        finally:
            syn.subprocess.run, syn.shutil.which = real_run, real_which
        return seen, out.getvalue()

    def test_pairing_link_goes_to_qrencode_on_stdin(self):
        token = syn.get_token()
        for fmt in ("svg", "ansi"):
            seen, _ = self._run_pair(fmt)
            qr = [c for c in seen if c[0] and c[0][0] == "qrencode"]
            self.assertTrue(qr, fmt)
            self.assertFalse(any(token in arg for arg in qr[0][0]), f"token in qrencode argv ({fmt})")
            self.assertIn(token, qr[0][1])

    def test_default_output_does_not_print_the_link_but_shows_the_fingerprint(self):
        token = syn.get_token()
        _, out = self._run_pair("ansi")
        self.assertNotIn(token, out)
        self.assertIn(syn.cert_fingerprint()[:16], out)
        _, out = self._run_pair("ansi", show_uri=True)
        self.assertIn(token, out)


class LocalApiPinning(ServerCase):
    def test_local_api_refuses_a_rogue_listener_and_never_sends_it_the_token(self):
        rogue_dir = TMP / "rogue"
        rogue_dir.mkdir(exist_ok=True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                        "-nodes", "-keyout", str(rogue_dir / "k.pem"), "-out", str(rogue_dir / "c.pem"),
                        "-days", "1", "-subj", "/CN=rogue"], check=True, capture_output=True)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(rogue_dir / "c.pem", rogue_dir / "k.pem")
        ls = socket.socket()
        ls.bind(("127.0.0.1", 0))
        ls.listen(5)
        seen = []

        def serve_rogue():
            ls.settimeout(6)
            try:
                while True:
                    c, _ = ls.accept()
                    try:
                        t = ctx.wrap_socket(c, server_side=True)
                        seen.append(t.recv(4096))
                        t.close()
                    except (ssl.SSLError, OSError):
                        c.close()
            except OSError:
                pass

        threading.Thread(target=serve_rogue, daemon=True).start()
        cfg = TMP / "config.json"
        cfg.write_text(json.dumps({"port": ls.getsockname()[1]}))
        try:
            self.assertIsNone(syn.local_api("/api/health"))
            self.assertFalse(any(self.token.encode() in b for b in seen), "token was sent to a rogue listener")
            # and it does work against the real bridge (our own certificate is the pinned anchor)
            cfg.write_text(json.dumps({"port": self.port}))
            self.assertEqual((syn.local_api("/api/health") or {}).get("ok"), True)
        finally:
            ls.close()
            cfg.unlink()


class SecretFiles(unittest.TestCase):
    def test_permissions(self):
        syn.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        syn.get_token()
        syn.ensure_cert()
        self.assertEqual(stat.S_IMODE(syn.CONFIG_DIR.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((syn.CONFIG_DIR / "token").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((syn.CONFIG_DIR / "key.pem").stat().st_mode), 0o600)

    def test_a_loose_existing_directory_is_tightened(self):
        syn.CONFIG_DIR.chmod(0o755)
        syn.private_dir()
        self.assertEqual(stat.S_IMODE(syn.CONFIG_DIR.stat().st_mode), 0o700)

    def test_write_private_is_never_looser_than_0600_and_replaces_atomically(self):
        p = syn.CONFIG_DIR / "secret-test"
        old = os.umask(0)  # even a permissive umask must not widen the file
        try:
            syn.write_private(p, "one")
            syn.write_private(p, "two")
        finally:
            os.umask(old)
        self.assertEqual(p.read_text(), "two")
        self.assertEqual(stat.S_IMODE(p.stat().st_mode), 0o600)
        self.assertFalse(p.with_name(p.name + ".tmp").exists())
        p.unlink()

    def test_tokens_are_long_and_unique(self):
        a = syn.get_token(rotate=True)
        b = syn.get_token(rotate=True)
        self.assertNotEqual(a, b)
        self.assertGreaterEqual(len(a), 40)  # 256 bits, url-safe base64


if __name__ == "__main__":
    unittest.main()
