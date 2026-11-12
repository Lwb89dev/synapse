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
  moduleName: "synapse.remote"
  ipcTarget: "synapse.remote"
  manageIpc: false

  readonly property string cli: Quickshell.env("HOME") + "/.local/bin/synapse"
  readonly property string qrPath: (Quickshell.env("XDG_RUNTIME_DIR") || "/tmp") + "/synapse-pair.svg"

  readonly property color foreground: bar ? bar.foreground : Commons.Color.foreground
  readonly property color urgent: bar ? bar.urgent : Commons.Color.urgent
  readonly property color dim: Qt.darker(foreground, 1.55)
  readonly property string fontFamily: bar ? bar.fontFamily : Style.font.family

  property bool running: false
  property var sessions: []
  property int attention: 0
  property string qrSource: ""
  property string error: ""

  function refresh() {
    if (!statusProcess.running) statusProcess.running = true
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

  // omarchy-shell synapse.remote open|close|toggle|refresh|pair  (pair = open the popup and show the QR)
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

  Process {
    id: statusProcess
    command: [root.cli, "status"]
    stdout: StdioCollector { id: statusOut; waitForEnd: true }
    onExited: function(code) {
      if (code !== 0) { root.running = false; root.error = "synapse not installed?"; return }
      try {
        const s = JSON.parse(statusOut.text)
        root.running = s.running
        root.sessions = s.sessions || []
        root.attention = s.attention || 0
        root.error = ""
      } catch (e) { root.error = "bad status output" }
    }
  }

  Process {
    id: qrProcess
    command: ["sh", "-c", "umask 077; '" + root.cli + "' pair --format svg > '" + root.qrPath + "'"]
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
            text: root.running ? "bridge on" : "bridge off"
            color: root.running ? root.foreground : root.urgent
            font.family: root.fontFamily
            font.pixelSize: Style.font.icon * 0.8
          }
          ToggleSwitch {
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

        PanelSeparator { foreground: root.foreground }

        Text {
          visible: root.running && root.sessions.length === 0
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

        PanelSeparator { foreground: root.foreground }

        Button {
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
