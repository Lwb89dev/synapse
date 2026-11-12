package node

import (
	"bytes"
	"io"
	"testing"
	"time"
)

func TestPreamble(t *testing.T) {
	secret := bytes.Repeat([]byte{7}, 32)
	cases := []struct {
		name  string
		send  []byte
		close bool
		want  bool
	}{
		{"correct", secret, false, true},
		{"wrong", bytes.Repeat([]byte{8}, 32), false, false},
		{"short then hang up", secret[:10], true, false},
		{"a TLS ClientHello instead", append([]byte{0x16, 0x03, 0x01}, make([]byte, 40)...), false, false},
	}
	for _, tc := range cases {
		a, b := tcpPair(t)
		go func() {
			a.Write(tc.send)
			if tc.close {
				a.Close()
			}
		}()
		if got := CheckPreamble(b, secret, 2*time.Second); got != tc.want {
			t.Errorf("%s: got %v want %v", tc.name, got, tc.want)
		}
		a.Close()
		b.Close()
	}
}

func TestPreambleSilentClientTimesOut(t *testing.T) {
	a, b := tcpPair(t)
	defer a.Close()
	defer b.Close()
	t0 := time.Now()
	if CheckPreamble(b, bytes.Repeat([]byte{1}, 32), 300*time.Millisecond) {
		t.Fatal("silent client accepted")
	}
	if time.Since(t0) > 2*time.Second {
		t.Fatal("deadline not enforced")
	}
}

func TestEmptySecretFailsClosed(t *testing.T) {
	a, b := tcpPair(t)
	defer a.Close()
	defer b.Close()
	if CheckPreamble(b, nil, time.Second) {
		t.Fatal("empty secret must never match")
	}
}

func TestPreambleDoesNotEatPayload(t *testing.T) {
	secret := bytes.Repeat([]byte{9}, 32)
	a, b := tcpPair(t)
	defer a.Close()
	defer b.Close()
	go a.Write(append(append([]byte{}, secret...), []byte("hello")...))
	if !CheckPreamble(b, secret, time.Second) {
		t.Fatal("rejected")
	}
	buf := make([]byte, 5)
	if _, err := io.ReadFull(b, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("payload after the preamble lost: %q %v", buf, err)
	}
}
