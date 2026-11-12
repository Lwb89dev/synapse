package node

import (
	"crypto/subtle"
	"io"
	"net"
	"time"
)

// CheckPreamble reads len(secret) bytes from c within d and reports whether they equal secret (constant time).
// The Android app's loopback forwarder uses it so that other apps on the phone, which can connect to 127.0.0.1 too,
// cannot use our tailnet node as a bridge to the desktop. Fails closed: an empty secret never matches.
func CheckPreamble(c net.Conn, secret []byte, d time.Duration) bool {
	if len(secret) == 0 {
		return false
	}
	c.SetReadDeadline(time.Now().Add(d))
	buf := make([]byte, len(secret))
	if _, err := io.ReadFull(c, buf); err != nil {
		return false
	}
	c.SetReadDeadline(time.Time{})
	return subtle.ConstantTimeCompare(buf, secret) == 1
}
