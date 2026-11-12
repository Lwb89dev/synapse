# Security model

Synapse lets a phone type into terminal agents on your desktop. That is remote code execution *by design*, so the
rules below matter. Report issues privately to the repository owner.

## What an attacker needs

To act on your desktop an attacker needs **all** of: a network path to the bridge (LAN, your tailnet, USB), the
bridge's **TLS certificate pin check passing** (only the real server's key does), and the **256-bit bearer token**.
The token and the pin both travel only in the pairing QR/link; treat that QR like a password.

## Defences

| Layer | What |
|---|---|
| Transport | TLS 1.3 only, self-signed EC P-256 certificate pinned by SHA-256 (from the QR). No CA, no hostname trust. |
| Tailnet | Optional. `synapse-net` forwards only devices of **your own account** (WhoIs), tagged/other users refused. Runs under a systemd sandbox. The tunnel is transport only: TLS + token still apply. |
| Authentication | Bearer token, constant-time compare, 0600 file created atomically. `synapse revoke` rotates it and the running bridge honours that **immediately**. |
| API surface | Only configured agents can be started (never arbitrary commands); keys are whitelisted (named keys, `C-x`, one printable ASCII char); session ids/names are fully matched; bodies capped at 1 MB; connection count capped; pre-auth timeout 5 s. |
| One-tap answers | Notification buttons carry the attention-event id; the bridge refuses (409) unless that exact prompt is still pending. Buttons need the phone unlocked; the lock screen shows no prompt text. |
| App | Pairing (token) encrypted with an Android Keystore AES-GCM key; links from outside the app need explicit confirmation (host, port, fingerprint shown); no cleartext traffic; backup disabled. |
| Local tools | `synapse status`/hook talk to the bridge over TLS **pinned to our own certificate**, so a process squatting on the port cannot harvest the token. |
| Other users/processes on the desktop | Secrets never travel in argv (world-readable `/proc/<pid>/cmdline`): typed text goes to tmux and the pairing link to `qrencode` on stdin; a failed paste deletes its tmux buffer. `synapse pair` does not print the link (no token in scrollback) unless `--show-uri`; it prints the certificate fingerprint to compare with the app's confirmation dialog. The tailnet sign-in URL is not written to the journal. |
| Other apps on the phone | The tailnet forwarder listens on 127.0.0.1, which any app can reach: every connection must start with a 32-byte per-process secret (constant-time check) or it is dropped before touching the tailnet. |
| Floods / probing | Global cap of 128 connections, 16 per address (loopback, i.e. tailnet sidecar and adb, exempt), 5 s pre-auth timeout. Failed authentications are logged (once a minute per address) to the journal. |
| Screen privacy | `FLAG_SECURE` on by default: no screenshots, recordings or recents thumbnail of the terminal (⋮ → Block screenshots). |
| Secrets at rest | `~/.config/synapse` is 0700; token, key and tailnet state 0600; the QR image lives in `$XDG_RUNTIME_DIR` (0600) and is deleted when the popup closes. |

## Known limits

* **The bridge listens on `0.0.0.0:7433` by default.** Anyone on your LAN can reach the TLS port (they still need
  the token). If you only use the tailnet or USB, set `"host": "127.0.0.1"` in `~/.config/synapse/config.json`.
* **The token is a bearer credential**: whoever gets the QR/link (screenshot, shoulder-surfing) can act until you run
  `synapse revoke`. There is no per-device token yet, so revoking one phone revokes all.
* **A screen can imitate a prompt.** One-tap buttons are offered from what is on screen; text an agent prints can look
  like a "(y/n)" question. The buttons only ever send `y`/`n`/Enter/Esc and the notification shows the line they
  answer: read it before tapping.
* **Other apps can open the app on a given session** (the launcher activity must be exported for links). That only
  navigates: it cannot send keys.
* **The agent runs with your privileges.** A phone that can answer an agent's prompt can approve whatever that
  prompt asks. The one-tap guard prevents answering the *wrong* prompt, not approving a bad one.
* Debug builds are `debuggable` (adb `run-as` can read app data). Ship a release build for daily use.
* No automated dependency/CVE scan is wired in: run `govulncheck ./...` in `tailnet/` and keep `tailscale.com` current.

## Lost or compromised phone

1. `synapse revoke` (the token stops working at once; `--cert` also forces every phone to pair again).
2. In the Tailscale admin console, remove the `synapse-android-<model>` machine.
3. Pair the new phone with `synapse pair`.

## Verifying

`python3 -m unittest bridge.test_security` attacks a real in-process server: missing/wrong/malformed credentials,
every route unauthenticated, token revocation, malformed bodies/headers/paths, command injection through agent/name/cwd,
TLS < 1.3, plaintext on the TLS port, connection floods (global and per address), auth-failure logging, a rogue
listener on the local API port, secrets in argv, the pairing link in terminal output, and file permissions.
`go test ./...` in `tailnet/` covers the forwarder preamble (wrong/short/silent/ClientHello clients).
