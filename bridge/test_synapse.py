"""Unit tests for the bridge logic that does not need tmux: python3 -m unittest bridge.test_synapse"""
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location("synapse", pathlib.Path(__file__).with_name("synapse.py"))
syn = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(syn)


class Clock:
    def __init__(self): self.now = 1000.0
    def time(self): return self.now
    def sleep(self, _): pass


class Helpers(unittest.TestCase):
    def test_strip_ansi_keeps_text_only(self):
        self.assertEqual(syn.strip_ansi("\x1b[1m\x1b[31mRED\x1b[0m plain \x1b[38;2;1;2;3mX\x1b[39m"), "RED plain X")
        self.assertEqual(syn.strip_ansi("\x1b]0;title\x07hi\x1b[2K"), "hi")

    def test_valid_key(self):
        for k in ("Enter", "Escape", "C-c", "y", "1", "BTab"):
            self.assertTrue(syn.valid_key(k), k)
        for k in ("rm -rf", "ls", "C-cc", "", "Enter;", "C-c\n", "Enter\n", "y\n", "\n", "C-\n"):
            self.assertFalse(syn.valid_key(k), k)

    def test_detect_state(self):
        self.assertEqual(syn.detect_state("Do you want to proceed? (y/n)", False), "needs_input")
        self.assertEqual(syn.detect_state("... esc to interrupt", False), "working")
        self.assertEqual(syn.detect_state("$ ", True), "working")
        self.assertEqual(syn.detect_state("$ ", False), "idle")


class QuickActions(unittest.TestCase):
    YN = "deploying…\n\nDo you want to proceed with the deploy? (y/n)\n"
    MENU = "Do you want to run this command?\n❯\xa01. Yes\n  2. Yes, and don't ask again\n  3. No\n\nEsc to cancel"
    MENU_NO = "Choose:\n  1. Yes\n❯ 2. No\n\nEsc to cancel"

    def test_yes_no_prompt(self):
        a = syn.prompt_actions(self.YN)
        self.assertEqual([x["label"] for x in a], ["Yes", "No"])
        self.assertEqual(a[0]["keys"], ["y", "Enter"])

    def test_menu_with_yes_highlighted(self):
        a = syn.prompt_actions(self.MENU)
        self.assertEqual([(x["label"], x["keys"]) for x in a], [("Yes", ["Enter"]), ("Cancel", ["Escape"])])

    def test_no_one_tap_when_highlighted_option_is_not_yes_or_unknown_prompt(self):
        self.assertEqual(syn.prompt_actions(self.MENU_NO), [])
        self.assertEqual(syn.prompt_actions("$ ls\nfile.txt\n"), [])

    def test_prompt_sig_ignores_redrawn_status_lines(self):
        a = syn.prompt_sig(self.YN + "status 1s")
        b = syn.prompt_sig(self.YN + "status 2s")
        self.assertEqual(a, b)
        self.assertNotEqual(a, syn.prompt_sig(self.YN.replace("deploy", "rollback")))


class AttentionGuard(unittest.TestCase):
    def hub_with(self, text, state="needs_input"):
        hub = syn.Hub()
        hub.snap["a"] = {"text": text, "state": state, "agent": "x", "version": 1}
        return hub

    def test_current_then_stale(self):
        hub = self.hub_with("Proceed? (y/n)")
        hub.emit_attention("a", "x", "Proceed? (y/n)", "Proceed? (y/n)")
        eid = hub.snap["a"]["attention_id"]
        self.assertTrue(hub.attention_current("a", eid))
        hub.snap["a"]["state"] = "working"            # answered on the desktop
        self.assertFalse(hub.attention_current("a", eid))
        hub.snap["a"]["state"] = "needs_input"
        hub.snap["a"]["text"] = "Delete everything? (y/n)"  # a different prompt took its place
        self.assertFalse(hub.attention_current("a", eid))
        self.assertFalse(hub.attention_current("a", eid + 1))

    def test_event_carries_actions(self):
        hub = self.hub_with("Proceed? (y/n)")
        hub.emit_attention("a", "x", "m", "Proceed? (y/n)")
        self.assertEqual(hub.events[-1]["actions"][0]["keys"], ["y", "Enter"])


class Tailscale(unittest.TestCase):
    def test_parse_status(self):
        raw = '{"Self": {"DNSName": "omarchy.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.7", "fd7a::7"]}}'
        self.assertEqual(syn.parse_tailscale_status(raw), ["omarchy.tail1234.ts.net", "100.64.0.7"])
        self.assertEqual(syn.parse_tailscale_status("not json"), [])
        self.assertEqual(syn.parse_tailscale_status('{"Self": null}'), [])


class TailnetHosts(unittest.TestCase):
    def test_embedded_hosts_only_when_running(self):
        syn.tailnet_status = lambda: {"state": "needs_login", "active": True}
        self.assertEqual(syn.embedded_tailnet_hosts(), [])
        syn.tailnet_status = lambda: {"state": "running", "dns_name": "synapse-omarchy.tail1.ts.net",
                                      "ips": ["fd7a::1", "100.64.0.7"], "active": True}
        self.assertEqual(syn.embedded_tailnet_hosts(), ["synapse-omarchy.tail1.ts.net", "100.64.0.7"])


class HubEvents(unittest.TestCase):
    """attention must not be followed by a bogus 'done' (it replaced the alert on the phone)."""

    def run_script(self, frames):
        clock, texts = Clock(), {}
        syn.time = clock
        syn.list_sessions = lambda: [{"id": "a", "agent": "x", "cwd": "/", "created": 0, "activity": 0}]
        syn.capture = lambda sid, lines=120, ansi=True: texts["now"]
        hub = syn.Hub()
        hub.mirror.poll = lambda now: []
        for dt, text in frames:
            clock.now += dt
            texts["now"] = text
            hub.tick()
        return [e["type"] for e in hub.events if e["type"] != "state"]

    def test_attention_then_done_once(self):
        types = self.run_script([
            (0, "a"), (1, "b"), (9, "c"),                    # working for 9 s
            (0.1, "Do you want to proceed? (y/n)"),         # -> needs_input: attention, no done
            (1, "d"), (9, "e"),                             # answered, working again
            (10, "e"),                                      # screen still -> idle: done
        ])
        self.assertEqual(types, ["attention", "done"])

    def test_event_ids_survive_restart(self):
        a = syn.Hub(); a.emit("state", "x")
        b = syn.Hub()
        self.assertGreaterEqual(b.next_id, a.events[0]["id"])

    def test_short_work_is_not_done(self):
        # 4 s of work, then the screen goes still
        self.assertEqual(self.run_script([(0, "a"), (1, "b"), (1, "c"), (3, "c")]), [])


def plain(lines):
    return syn.strip_ansi("\n".join(lines))


def claude_ev(kind, content, **kw):
    return {"type": kind, "message": {"role": kind, "content": content}, **kw}


class MirrorRendering(unittest.TestCase):
    def test_claude_conversation(self):
        out = plain(syn.render_claude([
            claude_ev("user", "fix the bug"),
            claude_ev("assistant", [{"type": "thinking", "thinking": "secret"}, {"type": "text", "text": "On it."},
                                    {"type": "tool_use", "name": "Bash", "input": {"command": "ls -la", "description": "x"}}]),
            claude_ev("user", [{"type": "tool_result", "content": "a\nb\nc\nd\ne\nf"}]),
        ]))
        self.assertIn("❯ fix the bug", out)
        self.assertIn("● On it.", out)
        self.assertIn("⏺ Bash(ls -la)", out)
        self.assertIn("⎿ a", out)
        self.assertIn("… +2 lines", out)
        self.assertNotIn("secret", out)

    def test_claude_noise_is_hidden(self):
        out = plain(syn.render_claude([
            claude_ev("user", "<local-command-caveat>x</local-command-caveat>"),
            claude_ev("user", "meta", isMeta=True),
            claude_ev("assistant", [{"type": "text", "text": "from a subagent"}], isSidechain=True),
            claude_ev("user", "<system-reminder>do not show</system-reminder>"),
            {"type": "attachment", "message": {"content": "x"}},
            claude_ev("user", "<command-name>/model</command-name><command-args>opus</command-args>"),
        ]))
        self.assertEqual(out.strip(), "❯ /model opus")

    def test_untrusted_output_cannot_inject_escapes(self):
        out = syn.render_claude([claude_ev("user", [{"type": "tool_result", "content": "\x1b]0;pwn\x07\x1b[2Jhi\x00\x08"}])])
        self.assertNotIn("pwn", "\n".join(out))
        self.assertNotIn("\x1b[2J", "\n".join(out))
        self.assertIn("hi", plain(out))

    def test_claude_error_result_is_red(self):
        out = syn.render_claude([claude_ev("user", [{"type": "tool_result", "content": "boom", "is_error": True}])])
        self.assertIn(syn.SGR_RED, out[0])

    def test_codex_items_and_turn_state(self):
        def item(it): return {"type": "event_msg", "payload": {"type": "item_completed", "item": it}}
        def marker(t): return {"type": "event_msg", "payload": {"type": t}}
        events = [marker("task_started"),
                  item({"type": "UserMessage", "content": [{"type": "text", "text": "go"}]}),
                  item({"type": "AgentMessage", "content": [{"type": "Text", "text": "ok"}]}),
                  item({"type": "CommandExecution", "command": ["/usr/bin/bash", "-lc", "ls -la"], "aggregated_output": "x\ny"}),
                  item({"type": "CommandExecution", "command": ["false"], "aggregated_output": "", "status": "failed"}),
                  item({"type": "FileChange", "changes": {"/a/b/c.py": {"type": "add"}}}),
                  item({"type": "Reasoning"}), {"type": "response_item", "payload": {"type": "message"}}]
        lines, working = syn.render_codex(events)
        out = plain(lines)
        self.assertTrue(working)
        for needle in ("❯ go", "● ok", "⏺ $ ls -la", "⎿ x", "⏺ $ false", "⎿ failed", "⏺ Edit(add c.py)"):
            self.assertIn(needle, out)
        _, working = syn.render_codex(events + [marker("task_complete")])
        self.assertFalse(working)
        _, working = syn.render_codex(events + [marker("turn_aborted")])
        self.assertFalse(working)
        self.assertIsNone(syn.render_codex([item({"type": "UserMessage", "content": []})])[1])

    def test_read_tail_drops_the_partial_first_line_and_a_half_written_last_one(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "t.jsonl"
            f.write_text("".join(json.dumps({"n": i, "pad": "x" * 100}) + "\n" for i in range(50)) + '{"n": 99, "pa')
            got = syn.read_tail(f, 1500)
            self.assertTrue(got and all(isinstance(e["n"], int) for e in got))
            self.assertEqual(got[-1]["n"], 49)
            self.assertGreater(got[0]["n"], 30)


class AgentName(unittest.TestCase):
    def test_binary_and_node_wrapper(self):
        self.assertEqual(syn.agent_name([b"/home/u/.local/bin/claude"]), "claude")
        self.assertEqual(syn.agent_name([b"/usr/bin/node", b"/usr/lib/node_modules/@openai/codex/bin/codex.js", b"x"]), "codex")
        self.assertIsNone(syn.agent_name([b"/usr/bin/node", b"server.js"]))
        self.assertIsNone(syn.agent_name([b"/usr/bin/codex-code-mode-host"]))
        self.assertIsNone(syn.agent_name([b""]))


class MirrorDiscovery(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tmp.name)
        self.claude, self.codex = root / "claude", root / "codex"
        os.environ["SYNAPSE_CLAUDE_DIR"], os.environ["SYNAPSE_CODEX_DIR"] = str(self.claude), str(self.codex)
        self.real = syn.agent_processes, syn.start_time

    def tearDown(self):
        syn.agent_processes, syn.start_time = self.real
        os.environ.pop("SYNAPSE_CLAUDE_DIR"), os.environ.pop("SYNAPSE_CODEX_DIR")
        self.tmp.cleanup()

    def claude_session(self, pid, cwd, status, waiting=""):
        (self.claude / "sessions").mkdir(parents=True, exist_ok=True)
        (self.claude / "sessions" / f"{pid}.json").write_text(json.dumps(
            {"pid": pid, "sessionId": "aaaaaaaa-bbbb", "cwd": cwd, "status": status, "waitingFor": waiting, "name": "n"}))
        proj = self.claude / "projects" / cwd.replace("/", "-")
        proj.mkdir(parents=True, exist_ok=True)
        (proj / "aaaaaaaa-bbbb.jsonl").write_text(json.dumps(claude_ev("user", "hello there")) + "\n")

    def codex_rollout(self, name, cwd, marker="task_started"):
        d = self.codex / "sessions" / "2026" / "11" / "13"
        d.mkdir(parents=True, exist_ok=True)
        (d / name).write_text(json.dumps({"type": "session_meta", "payload": {"cwd": cwd}}) + "\n" + json.dumps(
            {"type": "event_msg", "payload": {"type": marker}}) + "\n" + json.dumps(
            {"type": "event_msg", "payload": {"type": "item_completed", "item": {
                "type": "UserMessage", "content": [{"text": f"in {name}"}]}}}) + "\n")

    def test_claude_states_and_ids(self):
        syn.start_time = lambda pid: 1.0
        for status, expected in (("busy", "working"), ("idle", "idle"), ("waiting", "needs_input")):
            self.claude_session(77, "/work/app", status, "approve Bash")
            syn.agent_processes = lambda: [{"pid": 77, "agent": "claude", "cwd": "/work/app"}]
            (m,) = syn.Mirror().poll(time_now())
            self.assertEqual((m["id"], m["state"]), ("ext-claude-77", expected))
            self.assertTrue(m["meta"]["readonly"])
            self.assertIn("hello there", syn.strip_ansi(m["text"]))
        self.assertEqual(m["waiting"], "approve Bash")
        self.assertEqual(m["meta"]["title"], "n")

    def test_codex_matches_by_cwd_and_each_rollout_once(self):
        syn.start_time = lambda pid: 1.0
        self.codex_rollout("rollout-a.jsonl", "/p/one")
        self.codex_rollout("rollout-b.jsonl", "/p/two", marker="task_complete")
        syn.agent_processes = lambda: [{"pid": 5, "agent": "codex", "cwd": "/p/two"},
                                       {"pid": 6, "agent": "codex", "cwd": "/p/one"},
                                       {"pid": 7, "agent": "codex", "cwd": "/p/one"}]  # second one has nothing of its own
        got = {m["id"]: m for m in syn.Mirror().poll(time_now())}
        self.assertIn("in rollout-b.jsonl", syn.strip_ansi(got["ext-codex-5"]["text"]))
        self.assertIn("in rollout-a.jsonl", syn.strip_ansi(got["ext-codex-6"]["text"]))
        self.assertIn("No conversation yet", syn.strip_ansi(got["ext-codex-7"]["text"]))
        self.assertEqual(got["ext-codex-6"]["state"], "working")

    def test_a_rollout_older_than_the_process_is_not_its_transcript(self):
        syn.start_time = lambda pid: time_now() + 3600
        self.codex_rollout("rollout-a.jsonl", "/p/one")
        syn.agent_processes = lambda: [{"pid": 5, "agent": "codex", "cwd": "/p/one"}]
        (m,) = syn.Mirror().poll(time_now())
        self.assertIn("No conversation yet", syn.strip_ansi(m["text"]))

    def test_session_id_cannot_walk_out_of_the_projects_dir(self):
        (self.claude / "sessions").mkdir(parents=True)
        (self.claude / "sessions" / "9.json").write_text(json.dumps({"sessionId": "../../../etc/passwd", "cwd": "/x"}))
        syn.start_time = lambda pid: None
        syn.agent_processes = lambda: [{"pid": 9, "agent": "claude", "cwd": "/x"}]
        (m,) = syn.Mirror().poll(time_now())
        self.assertIn("No conversation yet", syn.strip_ansi(m["text"]))


def time_now():
    import time
    return time.time()


class MirrorHub(unittest.TestCase):
    def setUp(self):
        self.saved = syn.list_sessions, syn.load_config

    def tearDown(self):
        syn.list_sessions, syn.load_config = self.saved

    def mirror(self, state, text="Proceed? (y/n)"):
        return [{"id": "ext-claude-1", "state": state, "waiting": "approve Bash", "text": text,
                 "meta": {"agent": "claude", "cwd": "/w", "created": 1, "activity": 2, "title": "w", "readonly": True}}]

    def test_mirror_is_a_readonly_session_with_a_real_attention_and_no_quick_actions(self):
        syn.list_sessions = lambda: []
        syn.load_config = lambda: {"mirror": True}
        hub = syn.Hub()
        feed = {"now": self.mirror("working")}
        hub.mirror.poll = lambda now: feed["now"]
        hub.tick()
        (s,) = hub.sessions()
        self.assertEqual((s["id"], s["readonly"], s["title"], s["state"]), ("ext-claude-1", True, "w", "working"))
        self.assertTrue(hub.screen("ext-claude-1")["readonly"])
        # the transcript shows "(y/n)", but a mirror must never offer a one-tap answer
        feed["now"] = self.mirror("needs_input")
        hub.tick()
        att = [e for e in hub.events if e["type"] == "attention"]
        self.assertEqual(len(att), 1)
        self.assertEqual((att[0]["message"], att[0]["actions"]), ("approve Bash", []))
        feed["now"] = []
        hub.tick()
        self.assertEqual(hub.sessions(), [])
        self.assertEqual(hub.events[-1]["state"], "closed")

    def test_mirror_can_be_switched_off(self):
        syn.list_sessions = lambda: []
        syn.load_config = lambda: {"mirror": False}
        hub = syn.Hub()
        hub.mirror.poll = lambda now: self.mirror("working")
        hub.tick()
        self.assertEqual(hub.sessions(), [])

    def test_tmux_sessions_cannot_take_a_mirror_id(self):
        with self.assertRaises(ValueError):
            syn.create_session("claude", None, "ext-claude-1")


if __name__ == "__main__":
    unittest.main()
