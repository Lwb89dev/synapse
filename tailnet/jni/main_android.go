//go:build android

// Command jni builds (as a c-shared library) the embedded tailnet node for the Android app.
// The exported C functions are called from jni.c, which exposes them to Kotlin. Only strings cross the
// boundary. The app never routes system traffic through the node: it asks for a loopback port that is
// forwarded to a tailnet address, and OkHttp connects there (TLS stays end to end).
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/Lwb89dev/synapse/tailnet/node"
)

var (
	mu       sync.Mutex
	nd       *node.Node
	forwards = map[string]*fwd{}
	// per-process secret the app writes first on every forwarder connection (see node.CheckPreamble)
	preamble []byte
)

type fwd struct {
	ln   net.Listener
	port int
}

func cstr(s string) *C.char { return C.CString(s) }

// SynapseStart brings the node up (non-blocking). Returns "" or an error message.
//
//export SynapseStart
func SynapseStart(stateDir, hostname, authKey, controlURL, secretHex *C.char) *C.char {
	mu.Lock()
	defer mu.Unlock()
	secret, err := hex.DecodeString(C.GoString(secretHex))
	if err != nil || len(secret) != 32 {
		return cstr("forwarder secret must be 32 bytes of hex")
	}
	preamble = secret
	if nd != nil {
		return cstr("")
	}
	n := node.New(node.Options{
		Dir: C.GoString(stateDir), Hostname: C.GoString(hostname),
		AuthKey: C.GoString(authKey), ControlURL: C.GoString(controlURL),
	})
	if err := n.Start(); err != nil {
		return cstr(err.Error())
	}
	nd = n
	return cstr("")
}

// SynapseStatus returns {"state","auth_url","dns_name","ips","error"} as JSON.
//
//export SynapseStatus
func SynapseStatus() *C.char {
	mu.Lock()
	n := nd
	mu.Unlock()
	if n == nil {
		return cstr(`{"state":"stopped"}`)
	}
	b, _ := json.Marshal(n.Status())
	return cstr(string(b))
}

// SynapseForward returns "port:<n>" for a 127.0.0.1 port that forwards to target over the tailnet,
// or "error:<message>".
//
//export SynapseForward
func SynapseForward(target *C.char) *C.char {
	t := C.GoString(target)
	mu.Lock()
	defer mu.Unlock()
	if nd == nil {
		return cstr("error:tailnet not started")
	}
	if f, ok := forwards[t]; ok {
		return cstr(fmt.Sprintf("port:%d", f.port))
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return cstr("error:" + err.Error())
	}
	f := &fwd{ln: ln, port: ln.Addr().(*net.TCPAddr).Port}
	forwards[t] = f
	n := nd
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				// only our own app knows the secret: anything else connecting to 127.0.0.1 is dropped before it
				// can touch the tailnet
				mu.Lock()
				secret := preamble
				mu.Unlock()
				if !node.CheckPreamble(c, secret, 5*time.Second) {
					c.Close()
					return
				}
				ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
				defer cancel()
				r, err := n.Dial(ctx, "tcp", t)
				if err != nil {
					c.Close()
					return
				}
				node.Pipe(c, r)
			}()
		}
	}()
	return cstr(fmt.Sprintf("port:%d", f.port))
}

// SynapseStop shuts the node and every forward down.
//
//export SynapseStop
func SynapseStop() {
	mu.Lock()
	defer mu.Unlock()
	for k, f := range forwards {
		f.ln.Close()
		delete(forwards, k)
	}
	if nd != nil {
		nd.Close()
		nd = nil
	}
}

func main() {}
