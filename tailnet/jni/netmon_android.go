//go:build android

package main

// Android denies app UIDs the NETLINK_ROUTE socket, so net.Interfaces() fails and Tailscale's network monitor
// cannot start (golang/go#40569). Tailscale's own app feeds netmon from Java; here we do what tailscale's
// `androidbin` feature does for raw binaries: report one synthetic interface whose addresses are the source
// addresses the kernel picks for outbound UDP sockets (dialling UDP sends nothing and is permitted).

import (
	"errors"
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"
)

const syntheticIf = "android"

func init() {
	netmon.RegisterInterfaceGetter(syntheticInterfaces)
	netmon.UpdateLastKnownDefaultRouteInterface(syntheticIf)
}

func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("no outbound route")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{Index: 1, MTU: 1500, Name: syntheticIf, Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	c, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
