package node

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestEqualIgnoresUpdated(t *testing.T) {
	a := Status{State: "running", DNSName: "x.ts.net", IPs: []string{"100.64.0.1"}, Updated: 1}
	b := Status{State: "running", DNSName: "x.ts.net", IPs: []string{"100.64.0.1"}, Updated: 2}
	b.Updated, a.Updated = 0, 0
	if !equal(a, b) {
		t.Fatal("identical statuses must be equal")
	}
	b.IPs = []string{"100.64.0.2"}
	if equal(a, b) {
		t.Fatal("different IPs must differ")
	}
	b.IPs, b.State = a.IPs, "needs_login"
	if equal(a, b) {
		t.Fatal("different state must differ")
	}
}

func TestSetNotifiesInOrderOnlyOnChange(t *testing.T) {
	var got []string
	n := New(Options{OnStatus: func(s Status) { got = append(got, s.State) }})
	for _, st := range []string{"starting", "starting", "needs_login", "needs_login", "running", "starting"} {
		n.set(Status{State: st})
	}
	want := []string{"starting", "needs_login", "running", "starting"}
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v want %v", got, want)
		}
	}
}

func tcpPair(t *testing.T) (client, server net.Conn) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	ch := make(chan net.Conn, 1)
	go func() { c, _ := ln.Accept(); ch <- c }()
	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	return c, <-ch
}

func TestPipeCarriesBothWaysAndHalfClose(t *testing.T) {
	a1, a2 := tcpPair(t) // a1: "phone", a2: pipe side A
	b1, b2 := tcpPair(t) // b2: pipe side B, b1: "bridge"
	go Pipe(a2, b2)

	a1.Write([]byte("ping"))
	buf := make([]byte, 4)
	b1.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(b1, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("a->b: %q %v", buf, err)
	}
	b1.Write([]byte("pong"))
	a1.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(a1, buf); err != nil || string(buf) != "pong" {
		t.Fatalf("b->a: %q %v", buf, err)
	}
	// the phone hangs up: the bridge side must see EOF, not hang
	a1.Close()
	b1.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := b1.Read(buf); err != io.EOF {
		t.Fatalf("expected EOF on the bridge side, got %v", err)
	}
	b1.Close()
}
