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
        for k in ("rm -rf", "ls", "C-cc", "", "Enter;"):
            self.assertFalse(syn.valid_key(k), k)

    def test_detect_state(self):
        self.assertEqual(syn.detect_state("Do you want to proceed? (y/n)", False), "needs_input")
        self.assertEqual(syn.detect_state("... esc to interrupt", False), "working")
        self.assertEqual(syn.detect_state("$ ", True), "working")
        self.assertEqual(syn.detect_state("$ ", False), "idle")


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

    def test_short_work_is_not_done(self):
        # 4 s of work, then the screen goes still
        self.assertEqual(self.run_script([(0, "a"), (1, "b"), (1, "c"), (3, "c")]), [])


if __name__ == "__main__":
    unittest.main()
