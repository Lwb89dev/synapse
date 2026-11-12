import QtQuick
import QtQuick.Layouts
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Commons as Commons
import qs.Ui

// Synapse: bar widget for the phone bridge. Talks to the `synapse` CLI only:
//   synapse status          -> JSON snapshot (polled)
//   synapse pair --format svg  -> QR for pairing (shown on demand, wiped on close)
Panel {
  id: root
  moduleName: "io.github.lwb89dev.synapse"
  ipcTarget: "synapse"
  manageIpc: false

  // Where this plugin lives (the repo root): the installer sits next to Panel.qml.
  readonly property string pluginDir: decodeURIComponent(String(Qt.resolvedUrl(".")).replace(/^file:\/\//, "")).replace(/\/$/, "")
  readonly property string installer: pluginDir + "/install.sh"

  readonly property string cli: Quickshell.env("HOME") + "/.local/bin/synapse"
  readonly property string qrPath: (Quickshell.env("XDG_RUNTIME_DIR") || "/tmp") + "/synapse-pair.svg"

  readonly property color foreground: bar ? bar.foreground : Commons.Color.foreground
  readonly property color urgent: bar ? bar.urgent : Commons.Color.urgent
  readonly property color dim: Qt.darker(foreground, 1.55)
  readonly property string fontFamily: bar ? bar.fontFamily : Style.font.family

  // true when ~/.local/bin/synapse is missing: only the widget is installed, not the backend yet
  property bool backendMissing: false
  property bool running: false
  property var sessions: []
  property int attention: 0
  property var tailnet: ({ state: "stopped", active: false })
  property string qrSource: ""
  property string error: ""

  function refresh() {
    if (!existsProcess.running && !statusProcess.running) existsProcess.running = true
  }

  // Never installs anything by itself: opens the installer in a visible terminal so you can read it first and
  // watch it run. The path goes in as an argument ($0), never interpolated into a shell string.
  function runInstaller() {
    if (!/^[A-Za-z0-9_.\/-]+$/.test(root.installer)) return
    Quickshell.execDetached(["xdg-terminal-exec", "-e", "bash", "-c",
      "\"$0\"; echo; read -n 1 -s -r -p 'Done. Press any key to close.'", root.installer])
  }

  function showQr() {
    if (!qrProcess.running) qrProcess.running = true
  }

  function clearQr() {
    qrSource = ""
    Quickshell.execDetached(["rm", "-f", qrPath])
  }

  function togglePanel() {
    if (root.opened) root.close()
    else { root.open(); root.refresh() }
  }

  function stateLabel(s) {
    return s === "needs_input" ? "needs you" : s === "working" ? "working" : s
  }

  onOpenedChanged: if (!opened) clearQr()
  // The bar sizes this slot from these; without them it collapses to 0×0 and the glyph floats off the bar.
  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight

  Component.onCompleted: refresh()

  // omarchy-shell synapse open|close|toggle|refresh|pair  (pair = open the popup and show the QR)
  IpcHandler {
    target: root.ipcTarget
    function open(): void { root.open(); root.refresh() }
    function close(): void { root.close() }
    function show(): void { root.open(); root.refresh() }
    function hide(): void { root.close() }
    function toggle(): void { root.togglePanel() }
    function refresh(): string { root.refresh(); return "ok" }
    function pair(): string { root.open(); root.refresh(); root.showQr(); return "ok" }
  }

  Timer {
    interval: root.opened ? 2000 : 8000
    running: true
    repeat: true
    onTriggered: root.refresh()
  }

  // is the backend there at all? (a widget installed with `omarchy plugin add` has no backend yet)
  Process {
    id: existsProcess
    command: ["test", "-x", root.cli]
    onExited: function(code) {
      root.backendMissing = code !== 0
      if (root.backendMissing) { root.running = false; root.sessions = []; root.attention = 0; root.error = ""; return }
      statusProcess.running = true
    }
  }

  Process {
    id: statusProcess
    command: [root.cli, "status"]
    stdout: StdioCollector { id: statusOut; waitForEnd: true }
    onExited: function(code) {
      if (code !== 0) { root.running = false; root.error = "the synapse command failed: run  synapse status  in a terminal"; return }
      try {
        const s = JSON.parse(statusOut.text)
        root.running = s.running
        root.sessions = s.sessions || []
        root.attention = s.attention || 0
        root.tailnet = s.tailnet || ({ state: "stopped", active: false })
        root.error = ""
      } catch (e) { root.error = "bad status output" }
    }
  }

  Process {
    id: qrProcess
    // paths go in as arguments ($0, $1), never interpolated into the shell string
    command: ["sh", "-c", "umask 077; exec \"$0\" pair --format svg > \"$1\"", root.cli, root.qrPath]
    onExited: function(code) {
      if (code === 0) root.qrSource = "file://" + root.qrPath + "?" + Date.now()
      else root.error = "could not build the QR"
    }
  }

  // service toggle: the bridge is a systemd user unit
  Process { id: serviceProcess }
  function setService(on) {
    serviceProcess.command = ["systemctl", "--user", on ? "start" : "stop", "synapse.service"]
    serviceProcess.running = true
    Qt.callLater(function() { refreshSoon.restart() })
  }
  Timer { id: refreshSoon; interval: 800; onTriggered: root.refresh() }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: "󰄜"            // nf-md-cellphone-link; the bar centres and sizes it optically
    active: root.attention > 0   // turns urgent-red while an agent waits for you
    dimmed: !root.running
    onPressed: function(buttonCode) { root.togglePanel() }
  }

  KeyboardPanel {
    id: panel
    anchorItem: button
    owner: root
    bar: root.bar
    open: root.opened
    centerOnBar: false
    focusTarget: keyCatcher
    contentWidth: panel.fittedContentWidth(Style.space(340))
    contentHeight: panel.fittedContentHeight(column.implicitHeight + Style.space(16), Style.space(560))

    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent
      onCloseRequested: root.close()
      onTextKey: function(t) {
        var k = t.toLowerCase()
        if (k === "r") root.refresh()
        else if (k === "p") root.showQr()
      }

      Column {
        id: column
        x: Style.space(8)
        y: Style.space(8)
        width: parent.width - Style.space(16)
        spacing: Style.space(10)

        RowLayout {
          width: parent.width
          Text {
            text: "Synapse"
            color: root.foreground
            font.family: root.fontFamily
            font.pixelSize: Style.font.icon
            font.bold: true
            Layout.fillWidth: true
          }
          Text {
            visible: !root.backendMissing
            text: root.running ? "bridge on" : "bridge off"
            color: root.running ? root.foreground : root.urgent
            font.family: root.fontFamily
            font.pixelSize: Style.font.icon * 0.8
          }
          ToggleSwitch {
            visible: !root.backendMissing
            checked: root.running
            foreground: root.foreground
            onToggled: root.setService(!root.running)
          }
        }

        Text {
          visible: root.error !== ""
          width: parent.width
          text: root.error
          color: root.urgent
          wrapMode: Text.Wrap
          font.family: root.fontFamily
        }

        // --- backend not installed yet: explain, and offer to run the installer in a visible terminal ---
        Column {
          visible: root.backendMissing
          width: parent.width
          spacing: Style.space(8)
          Text {
            width: parent.width
            text: "Only the widget is installed. The Synapse backend (a small Python bridge, optionally a tailnet node) is not set up yet."
            color: root.foreground
            wrapMode: Text.Wrap
            font.family: root.fontFamily
          }
          Text {
            width: parent.width
            text: "Nothing is installed until you run the installer. It opens in a terminal so you can watch it."
            color: root.dim
            wrapMode: Text.Wrap
            font.family: root.fontFamily
          }
          Button {
            width: parent.width
            text: "Set up…"
            foreground: root.foreground
            fontFamily: root.fontFamily
            onClicked: root.runInstaller()
          }
          Text {
            width: parent.width
            text: root.installer
            color: root.dim
            wrapMode: Text.WrapAnywhere
            font.family: root.fontFamily
            font.pixelSize: Style.font.icon * 0.7
          }
        }

        PanelSeparator { visible: !root.backendMissing; foreground: root.foreground }

        // embedded tailnet node (synapse-net service): state, and the sign-in link while it needs one
        RowLayout {
          visible: !root.backendMissing && root.tailnet.active === true
          width: parent.width
          spacing: Style.space(8)
          Text {
            text: root.tailnet.state === "running" ? "tailnet · " + (root.tailnet.dns_name || "up")
                : root.tailnet.state === "needs_login" ? "tailnet · sign-in needed"
                : "tailnet · " + root.tailnet.state
            color: root.tailnet.state === "running" ? root.dim : root.urgent
            font.family: root.fontFamily
            elide: Text.ElideRight
            Layout.fillWidth: true
          }
          Button {
            visible: root.tailnet.state === "needs_login" && !!root.tailnet.auth_url
            text: "Sign in"
            foreground: root.foreground
            fontFamily: root.fontFamily
            onClicked: {
              var u = String(root.tailnet.auth_url || "")
              if (u.indexOf("https://") === 0) Quickshell.execDetached(["xdg-open", u])
            }
          }
        }

        Text {
          visible: !root.backendMissing && root.running && root.sessions.length === 0
          width: parent.width
          text: "No sessions. In a terminal:  synapse run claude"
          color: root.dim
          wrapMode: Text.Wrap
          font.family: root.fontFamily
        }

        Repeater {
          model: root.sessions
          delegate: RowLayout {
            required property var modelData
            width: column.width
            spacing: Style.space(8)
            Rectangle {
              Layout.preferredWidth: Style.space(8); Layout.preferredHeight: Style.space(8)
              radius: Style.space(4)
              color: modelData.state === "needs_input" ? root.urgent
                   : modelData.state === "working" ? "#3e9b4f" : root.dim
            }
            Text {
              text: modelData.agent + " · " + modelData.id
              color: root.foreground
              font.family: root.fontFamily
              elide: Text.ElideRight
              Layout.fillWidth: true
            }
            Text {
              text: root.stateLabel(modelData.state)
              color: modelData.state === "needs_input" ? root.urgent : root.dim
              font.family: root.fontFamily
            }
          }
        }

        PanelSeparator { visible: !root.backendMissing; foreground: root.foreground }

        Button {
          visible: !root.backendMissing
          width: parent.width
          text: root.qrSource === "" ? "Pair a phone…  (p)" : "Hide QR"
          enabled: root.running
          foreground: root.foreground
          fontFamily: root.fontFamily
          onClicked: root.qrSource === "" ? root.showQr() : root.clearQr()
        }

        Image {
          visible: root.qrSource !== ""
          source: root.qrSource
          width: Math.min(parent.width, Style.space(260))
          height: width
          anchors.horizontalCenter: parent.horizontalCenter
          sourceSize.width: 520
          sourceSize.height: 520
          fillMode: Image.PreserveAspectFit
          cache: false
        }

        Text {
          visible: root.qrSource !== ""
          width: parent.width
          text: "Scan with the Synapse app. The code contains the access token: don't share it."
          color: root.dim
          wrapMode: Text.Wrap
          font.family: root.fontFamily
          font.pixelSize: Style.font.icon * 0.7
        }
      }
    }
  }
}
