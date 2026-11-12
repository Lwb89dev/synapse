# Changelog

## 0.2.0 — 2026-11-12

First public release: the Omarchy plugin, the desktop backend and the Android app.

### Highlights

- **Omarchy marketplace-ready plugin.** The repository root is the plugin (`manifest.json`, `Panel.qml`, id
  `io.github.lwb89dev.synapse`). Installing the widget installs nothing else: it offers a **Set up…** button that runs the
  installer in a visible terminal. `install.sh` shows its plan and asks first, needs no sudo, touches nothing outside
  `$HOME`, and `--uninstall` / `--purge` remove everything.
- **Your own Tailscale node on both ends, no VPN app and no daemon.** The desktop runs `synapse-net` (a sandboxed
  user service) and the Android app embeds the same node (userspace, no `VpnService`), so it coexists with a system VPN such
  as Mullvad. TLS stays pinned end to end on top of the tunnel. Only devices of your own account are forwarded.
- **Answer from the notification.** One-tap Yes / No / Cancel on `(y/n)` prompts and Yes-highlighted menus. Every button
  carries the prompt it was shown for: the bridge refuses it if that prompt is no longer pending ("Already handled,
  nothing was sent"). Buttons need the phone unlocked; the lock screen shows no prompt text.
- **Signed release APK** (this release), built from the tagged commit.

### Android app

- Material You terminal UI: live ANSI-coloured screen, block cursor, extra-keys bar, `❯` prompt, fit-to-phone width.
- Pairing by QR or link with an explicit confirmation (hosts, port, certificate fingerprint). The pairing is stored
  encrypted with an Android Keystore key; `FLAG_SECURE` is on by default (toggle in the menu).
- Resumes events after being offline (`Last-Event-ID`), reconnects on network changes, self-heals the tailnet node
  after 90 s of unreachability, and asks to be exempted from battery optimisation so alerts survive Doze.
- Races every address in the pairing at once (LAN, USB, tailnet) and shows which one won.

### Bridge and widget

- `synapse` CLI: `run`, `ls`, `attach`, `kill`, `pair`, `status`, `tailnet`, `revoke`, `token`, `claude-hooks`.
- TLS 1.3 only with a pinned self-signed certificate; bearer token that can be revoked instantly (`synapse revoke`).
- Bar widget: sessions, red "needs you" badge, bridge switch, tailnet state and sign-in, pairing QR (wiped when closed).

### Security

Two audits and 47 automated tests (`bridge/test_security.py`, Go tests). Highlights: secrets never in argv, token revocation
takes effect immediately, per-address connection limits, local API pinned to our own certificate, pairing link never stored in
clear text, a per-process secret protecting the phone's loopback forwarder. See `SECURITY.md` for the model and the known limits
(the bridge listens on `0.0.0.0` by default; one token for all devices).

### Known limits

- The APK contains the tailnet library for **arm64** only (all current phones; not x86 emulators).
- Android 10 or newer. The app is sideloaded; there is no store release.
- Terminal content is a polled `capture-pane` snapshot (4 Hz), not a raw PTY stream: no mouse.

### Verify the APK

`synapse-0.2.0.apk` is signed with the project's release key. Certificate SHA-256:
`A9:97:92:9A:9A:5A:3B:AC:2E:2D:36:82:C2:14:38:58:2D:CA:F4:AD:44:BA:69:E0:D1:5E:3A:8A:A9:33:89:8E`
(`apksigner verify --print-certs synapse-0.2.0.apk`). The SHA-256 of the file itself is in `synapse-0.2.0.apk.sha256`.
