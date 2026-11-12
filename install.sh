#!/usr/bin/env bash
# Installs Synapse for the current user by symlinking from this checkout (edits apply live).
#   ./install.sh              install CLI + systemd unit + Omarchy plugin
#   ./install.sh --enable     ...and also add the widget to the bar
#   ./install.sh --uninstall
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bin="$HOME/.local/bin/synapse"
plugin="$HOME/.config/omarchy/plugins/synapse-remote"
unit="$HOME/.config/systemd/user/synapse.service"

if [[ "${1:-}" == "--uninstall" ]]; then
  systemctl --user disable --now synapse.service 2>/dev/null || true
  omarchy plugin disable synapse.remote 2>/dev/null || true
  systemctl --user disable --now synapse-net.service 2>/dev/null || true
  rm -f "$bin" "$plugin" "$unit" "$HOME/.local/bin/synapse-net" "$HOME/.config/systemd/user/synapse-net.service"
  systemctl --user daemon-reload
  echo "Removed. Pairing data is kept in ~/.config/synapse (delete it to revoke the token)."
  exit 0
fi

for dep in tmux python3 openssl; do
  command -v "$dep" >/dev/null || { echo "missing dependency: $dep" >&2; exit 1; }
done
command -v qrencode >/dev/null || echo "note: install 'qrencode' to get the pairing QR (pacman -S qrencode)"

mkdir -p "$(dirname "$bin")" "$(dirname "$plugin")" "$(dirname "$unit")"
ln -sfn "$here/bridge/synapse.py" "$bin"
ln -sfn "$here/plugin" "$plugin"
install -m 644 "$here/systemd/synapse.service" "$unit"

# Optional: the embedded tailnet node (needs Go only to build it; the result is one static binary)
net="$HOME/.local/bin/synapse-net"
gobuild=()
if go version >/dev/null 2>&1; then gobuild=(go)  # a mise shim with no version set fails here, on purpose
elif command -v mise >/dev/null && mise exec go@latest -- go version >/dev/null 2>&1; then gobuild=(mise exec go@latest -- go); fi
if (( ${#gobuild[@]} )); then
  echo "building synapse-net (embedded tailnet node)…"
  (cd "$here/tailnet" && "${gobuild[@]}" build -trimpath -ldflags "-s -w" -o "$net" ./cmd/synapse-net)
  install -m 644 "$here/systemd/synapse-net.service" "$HOME/.config/systemd/user/synapse-net.service"
  systemctl --user try-restart synapse-net.service 2>/dev/null || true  # pick up the rebuilt binary
  tailnet_ok=1
else
  echo "note: Go not found, skipping the tailnet node (install Go or mise to enable it)"
fi

systemctl --user daemon-reload
systemctl --user enable --now synapse.service
if [[ -n "${tailnet_ok:-}" ]]; then
  systemctl --user enable --now synapse-net.service
  echo "tailnet: run 'synapse tailnet login' to sign this machine in (one time)"
fi
omarchy-shell shell rescanPlugins 2>/dev/null || true
[[ "${1:-}" == "--enable" ]] && omarchy plugin enable synapse.remote

cat <<MSG

Synapse installed.
  1. Pair the phone:      synapse pair
  2. Run an agent:        synapse run claude     (also: codex, kimi, grok)
  3. Bar widget:          omarchy plugin enable synapse.remote   (or re-run with --enable)
  4. Rich Claude alerts:  synapse claude-hooks   (prints a snippet for ~/.claude/settings.json)

Firewall: the bridge listens on TCP 7433. Keep it reachable only from your LAN/VPN.
MSG
