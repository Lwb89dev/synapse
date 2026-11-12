"""Unit tests for the bridge logic that does not need tmux: python3 -m unittest bridge.test_synapse"""
import importlib.util
import pathlib
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


if __name__ == "__main__":
    unittest.main()
