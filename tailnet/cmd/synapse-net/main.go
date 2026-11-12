// synapse-net makes the Synapse bridge reachable from the user's tailnet without installing Tailscale:
// it joins the tailnet as its own userspace node and forwards tailnet TCP connections to the local bridge.
// TLS (pinned) and the bearer token still protect the bridge end to end; this is only the transport.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"time"

	"github.com/Lwb89dev/synapse/tailnet/node"
)

func main() {
	home, _ := os.UserHomeDir()
	host, _ := os.Hostname()
	dir := flag.String("dir", filepath.Join(home, ".config", "synapse", "tailnet"), "state directory")
	name := flag.String("hostname", "synapse-"+host, "name of this node in the tailnet")
	port := flag.String("port", "7433", "tailnet port to listen on")
	target := flag.String("target", "127.0.0.1:7433", "local bridge to forward to")
	anyPeer := flag.Bool("any-peer", false, "also accept devices of other users of your tailnet (default: only your own devices)")
	control := flag.String("control-url", os.Getenv("SYNAPSE_TS_CONTROL_URL"), "alternative control server (Headscale)")
	flag.Parse()

	if err := os.MkdirAll(*dir, 0o700); err != nil {
		log.Fatal(err)
	}
	statusFile := filepath.Join(*dir, "status.json")
	writeStatus := func(s node.Status) {
		b, _ := json.Marshal(s)
		tmp := statusFile + ".tmp"
		if os.WriteFile(tmp, b, 0o600) == nil {
			os.Rename(tmp, statusFile)
		}
		if s.State == "needs_login" {
			// not the URL itself: whoever opens it first can attach this machine to *their* account, and the
			// journal is readable by admins. It is in status.json (0600) and `synapse tailnet login`.
			log.Printf("sign-in needed: run `synapse tailnet login`")
		} else {
			log.Printf("tailnet: %s %s %v", s.State, s.DNSName, s.IPs)
		}
	}

	n := node.New(node.Options{
		Dir:        filepath.Join(*dir, "state"),
		Hostname:   *name,
		AuthKey:    firstNonEmpty(os.Getenv("SYNAPSE_TS_AUTHKEY"), os.Getenv("TS_AUTHKEY")),
		ControlURL: *control,
		Logf:       log.Printf,
		OnStatus:   writeStatus,
	})
	if err := n.Start(); err != nil {
		log.Fatal(err)
	}
	defer n.Close()

	ln, err := n.Listen("tcp", ":"+*port)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("forwarding tailnet :%s -> %s", *port, *target)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go func() {
			if !*anyPeer {
				ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
				ok := n.SameUser(ctx, c.RemoteAddr().String())
				cancel()
				if !ok {
					log.Printf("refused %s: not a device of this account", c.RemoteAddr())
					c.Close()
					return
				}
			}
			log.Printf("accepted %s", c.RemoteAddr())
			local, err := net.Dial("tcp", *target)
			if err != nil {
				fmt.Fprintln(os.Stderr, "bridge unreachable:", err)
				c.Close()
				return
			}
			node.Pipe(c, local)
		}()
	}
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}
