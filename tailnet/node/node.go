// Package node wraps a tsnet (userspace Tailscale) server: no tun device, no system VPN, no daemon.
// The same code runs in the desktop sidecar and, via gomobile, inside the Android app.
package node

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netns"
	"tailscale.com/tsnet"
)

// Status is what UIs (the bar widget, the app) show about the node.
type Status struct {
	State   string   `json:"state"` // stopped | starting | needs_login | running | error
	AuthURL string   `json:"auth_url,omitempty"`
	DNSName string   `json:"dns_name,omitempty"` // MagicDNS name, no trailing dot
	IPs     []string `json:"ips,omitempty"`
	Error   string   `json:"error,omitempty"`
	Updated int64    `json:"updated"`
}

type Options struct {
	Dir        string // state directory (keys live here)
	Hostname   string // machine name shown in the admin console
	AuthKey    string // optional: join without the browser login
	ControlURL string // optional: a Headscale/other control server
	Logf       func(format string, args ...any)
	OnStatus   func(Status)
}

type Node struct {
	opts   Options
	srv    *tsnet.Server
	mu     sync.Mutex
	status Status
	stop   context.CancelFunc
}

func New(o Options) *Node {
	if o.Logf == nil {
		o.Logf = func(string, ...any) {}
	}
	return &Node{opts: o, status: Status{State: "stopped"}}
}

func (n *Node) Start() error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv != nil {
		return errors.New("already started")
	}
	prepareEnv(n.opts.Dir)
	// A userspace node has no tailscale interface or routes to bypass. Left enabled, netns pins every socket to the
	// physical default-route interface (SO_BINDTODEVICE), which sends traffic around a system VPN such as Mullvad,
	// whose kill switch then refuses it. Disabled, sockets simply follow the system routes (through the VPN).
	netns.SetEnabled(false)
	n.srv = &tsnet.Server{
		Dir:        n.opts.Dir,
		Hostname:   n.opts.Hostname,
		AuthKey:    n.opts.AuthKey,
		ControlURL: n.opts.ControlURL,
		Logf:       n.engineLogf(), // tsnet's own chatter is far too verbose: only with SYNAPSE_TS_DEBUG=1
		UserLogf:   n.opts.Logf,
	}
	if err := n.srv.Start(); err != nil {
		n.srv = nil
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	n.stop = cancel
	n.setLocked(Status{State: "starting"})
	go n.poll(ctx)
	return nil
}

// prepareEnv gives tailscale's logger a place to live (Android has no HOME or cache dir, and it panics
// with "no safe place found to store log state") and turns off uploading client logs to Tailscale.
func prepareEnv(stateDir string) {
	if os.Getenv("TS_LOGS_DIR") == "" {
		logs := filepath.Join(stateDir, "logs")
		os.MkdirAll(logs, 0o700)
		os.Setenv("TS_LOGS_DIR", logs)
	}
	if os.Getenv("TS_NO_LOGS_NO_SUPPORT") == "" {
		os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	}
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", stateDir)
	}
}

func (n *Node) poll(ctx context.Context) {
	lc, err := n.srv.LocalClient()
	if err != nil {
		n.set(Status{State: "error", Error: err.Error()})
		return
	}
	var lastProbe string
	for ctx.Err() == nil {
		st, err := lc.StatusWithoutPeers(ctx)
		if k := probeKey(st, err); k != lastProbe {
			lastProbe = k
			n.opts.Logf("backend: %s", k)
		}
		switch {
		case ctx.Err() != nil:
			return
		case err != nil:
			n.set(Status{State: "starting", Error: err.Error()})
		default:
			s := Status{}
			switch st.BackendState {
			case "Running":
				s.State = "running"
			case "NeedsLogin", "NeedsMachineAuth":
				s.State, s.AuthURL = "needs_login", st.AuthURL
				if st.AuthURL == "" {
					s.State = "starting" // the login URL has not been issued yet
				}
			case "Stopped":
				s.State = "stopped"
			default:
				s.State = "starting"
			}
			if st.Self != nil {
				s.DNSName = trimDot(st.Self.DNSName)
				for _, ip := range st.Self.TailscaleIPs {
					s.IPs = append(s.IPs, ip.String())
				}
			}
			n.set(s)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(1500 * time.Millisecond):
		}
	}
}

func (n *Node) engineLogf() func(string, ...any) {
	if os.Getenv("SYNAPSE_TS_DEBUG") != "" {
		return n.opts.Logf
	}
	return func(string, ...any) {}
}

func probeKey(st *ipnstate.Status, err error) string {
	if err != nil {
		return "error: " + err.Error()
	}
	return fmt.Sprintf("state=%s authURL=%t self=%t", st.BackendState, st.AuthURL != "", st.Self != nil)
}

func trimDot(s string) string {
	if len(s) > 0 && s[len(s)-1] == '.' {
		return s[:len(s)-1]
	}
	return s
}

func (n *Node) set(s Status) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.setLocked(s)
}

func (n *Node) setLocked(s Status) {
	s.Updated = time.Now().Unix()
	prev := n.status
	n.status = s
	// only notify on real changes, not on every poll
	prev.Updated, s.Updated = 0, 0
	if n.opts.OnStatus != nil && !equal(prev, s) {
		go n.opts.OnStatus(n.status)
	}
}

func equal(a, b Status) bool {
	if a.State != b.State || a.AuthURL != b.AuthURL || a.DNSName != b.DNSName || a.Error != b.Error || len(a.IPs) != len(b.IPs) {
		return false
	}
	for i := range a.IPs {
		if a.IPs[i] != b.IPs[i] {
			return false
		}
	}
	return true
}

func (n *Node) Status() Status {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.status
}

func (n *Node) server() (*tsnet.Server, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.srv == nil {
		return nil, errors.New("tailnet node not started")
	}
	return n.srv, nil
}

func (n *Node) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	s, err := n.server()
	if err != nil {
		return nil, err
	}
	return s.Dial(ctx, network, addr)
}

func (n *Node) Listen(network, addr string) (net.Listener, error) {
	s, err := n.server()
	if err != nil {
		return nil, err
	}
	return s.Listen(network, addr)
}

func (n *Node) Close() {
	n.mu.Lock()
	srv, stop := n.srv, n.stop
	n.srv, n.stop = nil, nil
	n.mu.Unlock()
	if stop != nil {
		stop()
	}
	if srv != nil {
		srv.Close()
	}
	n.set(Status{State: "stopped"})
}

// Pipe copies both ways until either side closes; it closes both connections.
func Pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if c, ok := dst.(interface{ CloseWrite() error }); ok {
			c.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	<-done
	a.Close()
	b.Close()
}
