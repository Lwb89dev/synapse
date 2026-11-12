#!/usr/bin/env bash
# Synapse backend installer. Safe to re-run. Needs no root and writes nothing outside your home directory.
#
#   ./install.sh [--yes] [--enable]       install the CLI + systemd user units (+ the tailnet node if Go is available)
#   ./install.sh --uninstall [--purge]    remove them; --purge also deletes ~/.config/synapse
#                                         (access token, certificate, tailnet identity)
#
# The Omarchy plugin itself is managed by Omarchy:  omarchy plugin add / enable / disable / remove <id>
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$here/manifest.json")"
plugins="$HOME/.config/omarchy/plugins"
bin="$HOME/.local/bin/synapse"
net="$HOME/.local/bin/synapse-net"
units="$HOME/.config/systemd/user"

yes=0 enable=0 uninstall=0 purge=0
for a in "$@"; do
  case "$a" in
    --yes|-y) yes=1 ;;
    --enable) enable=1 ;;
    --uninstall) uninstall=1 ;;
    --purge) purge=1 ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $a (see --help)" >&2; exit 2 ;;
  esac
done

confirm() {
  (( yes )) && return 0
  [[ -t 0 ]] || { echo "not interactive: re-run with --yes to proceed" >&2; exit 1; }
  read -r -p "$1 [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]]
}

# ------------------------------------------------------------------ uninstall
if (( uninstall )); then
  echo "This will stop and remove the Synapse services, the 'synapse' / 'synapse-net' commands and the unit files."
  (( purge )) && echo "--purge: it will ALSO delete ~/.config/synapse (token, certificate, tailnet identity). Paired phones stop working."
  echo "The plugin folder is not touched: remove it with  omarchy plugin remove $id"
  confirm "Continue?" || { echo "aborted"; exit 1; }
  systemctl --user disable --now synapse-net.service synapse.service 2>/dev/null || true
  omarchy plugin disable "$id" 2>/dev/null || true
  rm -f "$bin" "$net" "$units/synapse.service" "$units/synapse-net.service"
  [[ -L "$plugins/$id" && "$(readlink -f "$plugins/$id")" == "$here" ]] && rm -f "$plugins/$id"
  rm -f "$plugins/synapse-remote"  # link made by development checkouts before the plugin had its final id
  systemctl --user daemon-reload
  if (( purge )); then rm -rf "$HOME/.config/synapse"; echo "~/.config/synapse deleted"; else echo "kept ~/.config/synapse (use --purge to delete it)"; fi
  echo "Removed. tmux sessions named synapse-* (if any) are left running: tmux kill-server, or end them yourself."
  exit 0
fi

# ------------------------------------------------------------------ install
for dep in tmux python3 openssl; do
  command -v "$dep" >/dev/null || { echo "missing dependency: $dep" >&2; exit 1; }
done
command -v qrencode >/dev/null || echo "note: install 'qrencode' to get the pairing QR (pacman -S qrencode)"

# Optional: the embedded tailnet node. Go is only needed to build it; the result is one static binary.
gobuild=()
if go version >/dev/null 2>&1; then gobuild=(go)  # a mise shim with no version set fails here, on purpose
elif command -v mise >/dev/null && mise exec go@latest -- go version >/dev/null 2>&1; then gobuild=(mise exec go@latest -- go); fi

cat <<PLAN

Synapse will:
  - link the CLI:            $bin  ->  $here/bridge/synapse.py
  - install systemd user units in $units and start them:
      synapse.service        the bridge, TLS on TCP 7433 (all interfaces by default; see SECURITY.md)
PLAN
if (( ${#gobuild[@]} )); then
  echo "      synapse-net.service    your own tailnet node (built from tailnet/ with Go; modules pinned by go.sum)"
else
  echo "  - skip the tailnet node (Go not found: install Go or mise to enable it)"
fi
[[ "$(readlink -f "$here")" != "$(readlink -f "$plugins/$id" 2>/dev/null)" ]] \
  && echo "  - link this folder as the Omarchy plugin: $plugins/$id" || true
echo "  No sudo, nothing outside \$HOME. Undo with: ./install.sh --uninstall"
echo
confirm "Install?" || { echo "aborted"; exit 1; }

mkdir -p "$(dirname "$bin")" "$plugins" "$units"
chmod +x "$here/bridge/synapse.py"
ln -sfn "$here/bridge/synapse.py" "$bin"
install -m 644 "$here/systemd/synapse.service" "$units/synapse.service"

# plugin folder: installed by `omarchy plugin add` it already sits in $plugins; a development checkout gets linked
if [[ "$(readlink -f "$here")" != "$(readlink -f "$plugins/$id" 2>/dev/null)" ]]; then
  ln -sfn "$here" "$plugins/$id"
fi
rm -f "$plugins/synapse-remote" # link left by development checkouts before the plugin had its final id

if (( ${#gobuild[@]} )); then
  echo "building synapse-net (embedded tailnet node)…"
  (cd "$here/tailnet" && "${gobuild[@]}" build -trimpath -ldflags "-s -w" -o "$net" ./cmd/synapse-net)
  install -m 644 "$here/systemd/synapse-net.service" "$units/synapse-net.service"
  systemctl --user try-restart synapse-net.service 2>/dev/null || true  # pick up a rebuilt binary
  tailnet_ok=1
fi

systemctl --user daemon-reload
systemctl --user enable --now synapse.service
if [[ -n "${tailnet_ok:-}" ]]; then
  systemctl --user enable --now synapse-net.service
  sleep 4
  case "$("$bin" tailnet --json 2>/dev/null)" in
    *'"running"'*) echo "tailnet: already signed in" ;;
    *) echo "tailnet: run 'synapse tailnet login' to sign this machine in (one time)" ;;
  esac
fi
omarchy-shell shell rescanPlugins 2>/dev/null || true
(( enable )) && omarchy plugin enable "$id"

cat <<MSG

Synapse installed.
  1. Pair the phone:      synapse pair
  2. Run an agent:        synapse run claude     (also: codex, kimi, grok)
  3. Bar widget:          omarchy plugin enable $id   (or re-run with --enable)
  4. Rich Claude alerts:  synapse claude-hooks   (prints a snippet for ~/.claude/settings.json)

Firewall: the bridge listens on TCP 7433. Keep it reachable only from your LAN/VPN, or set "host": "127.0.0.1"
in ~/.config/synapse/config.json to use only the tailnet or USB.
MSG
