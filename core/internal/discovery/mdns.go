// Package discovery announces this device and finds peers via mDNS/DNS-SD
// (Bonjour / ZeroConf). Announcing is passive: the responder only answers
// queries, so an idle device sends nothing. Browsing happens only on demand.
package discovery

import (
	"context"
	"net"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/grandcat/zeroconf"
)

const (
	Service = "_onetouch._tcp"
	Domain  = "local."
)

type Peer struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	OS          string   `json:"os"`
	Fingerprint string   `json:"fp"`
	Port        int      `json:"port"`
	Addrs       []string `json:"addrs"`
}

// Addr returns the best host:port to dial (IPv4 preferred).
func (p Peer) Addr() string {
	if len(p.Addrs) == 0 {
		return ""
	}
	return net.JoinHostPort(p.Addrs[0], strconv.Itoa(p.Port))
}

type Announcement struct {
	ID, Name, OS, Fingerprint string
	Port                      int
}

// Announce registers the service. Call Shutdown on the result to withdraw it.
func Announce(a Announcement) (*zeroconf.Server, error) {
	txt := []string{
		"v=1",
		"id=" + a.ID,
		"name=" + a.Name,
		"os=" + a.OS,
		"fp=" + a.Fingerprint,
	}
	instance := InstanceName(a.Name, a.ID)
	return zeroconf.Register(instance, Service, Domain, a.Port, txt, nil)
}

// InstanceName builds the DNS-SD instance label "<name>-<id6>", which must
// fit in 63 bytes (RFC 6763) and must not contain dots: long or dotted
// computer names otherwise make every announcement fail ("bad rdata").
func InstanceName(name, id string) string {
	suffix := "-" + id[:min(6, len(id))]
	name = strings.ReplaceAll(name, ".", "-")
	max := 63 - len(suffix)
	if len(name) > max {
		cut := max
		for cut > 0 && !utf8.RuneStart(name[cut]) {
			cut--
		}
		name = strings.TrimRight(name[:cut], " -")
	}
	return name + suffix
}

// Browse queries the network for `timeout` and returns all peers except selfID.
func Browse(ctx context.Context, timeout time.Duration, selfID string) ([]Peer, error) {
	resolver, err := zeroconf.NewResolver(nil)
	if err != nil {
		// Hosts with IPv6 disabled (common on Windows/Android) fail dual-stack.
		resolver, err = zeroconf.NewResolver(zeroconf.SelectIPTraffic(zeroconf.IPv4))
		if err != nil {
			return nil, err
		}
	}
	entries := make(chan *zeroconf.ServiceEntry, 16)
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if err := resolver.Browse(ctx, Service, Domain, entries); err != nil {
		return nil, err
	}
	byID := map[string]*Peer{}
	for {
		select {
		case e, ok := <-entries:
			if !ok {
				return collect(byID), nil
			}
			p := fromEntry(e)
			if p.ID == "" || p.ID == selfID {
				continue
			}
			if old, ok := byID[p.ID]; ok {
				old.Addrs = mergeAddrs(old.Addrs, p.Addrs)
				continue
			}
			byID[p.ID] = &p
		case <-ctx.Done():
			return collect(byID), nil
		}
	}
}

func fromEntry(e *zeroconf.ServiceEntry) Peer {
	p := Peer{Port: e.Port, Name: unescape(e.Instance)}
	for _, kv := range e.Text {
		k, v, _ := strings.Cut(unescape(kv), "=")
		switch k {
		case "id":
			p.ID = v
		case "name":
			p.Name = v
		case "os":
			p.OS = v
		case "fp":
			p.Fingerprint = v
		}
	}
	var addrs []string
	for _, ip := range e.AddrIPv4 {
		addrs = append(addrs, ip.String())
	}
	for _, ip := range e.AddrIPv6 {
		if ip.IsLinkLocalUnicast() {
			continue // needs a zone; skip for simplicity
		}
		addrs = append(addrs, ip.String())
	}
	p.Addrs = addrs
	return p
}

// unescape decodes the RFC 1035 master-file escapes (\DDD and \X) that the
// zeroconf library applies to non-ASCII TXT bytes, e.g. Cyrillic device names.
func unescape(s string) string {
	if !strings.Contains(s, "\\") {
		return s
	}
	var b []byte
	for i := 0; i < len(s); i++ {
		if s[i] != '\\' || i+1 >= len(s) {
			b = append(b, s[i])
			continue
		}
		if i+3 < len(s) && isDigit(s[i+1]) && isDigit(s[i+2]) && isDigit(s[i+3]) {
			n := int(s[i+1]-'0')*100 + int(s[i+2]-'0')*10 + int(s[i+3]-'0')
			if n < 256 {
				b = append(b, byte(n))
				i += 3
				continue
			}
		}
		b = append(b, s[i+1])
		i++
	}
	return string(b)
}

func isDigit(c byte) bool { return c >= '0' && c <= '9' }

func mergeAddrs(a, b []string) []string {
	seen := map[string]bool{}
	for _, x := range a {
		seen[x] = true
	}
	for _, x := range b {
		if !seen[x] {
			a = append(a, x)
		}
	}
	return a
}

func collect(m map[string]*Peer) []Peer {
	out := make([]Peer, 0, len(m))
	for _, p := range m {
		out = append(out, *p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// LocalIPv4 lists IPv4 addresses of up, non-loopback interfaces, private
// (LAN) addresses first.
func LocalIPv4() []string {
	var out, other []string
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil || ipn.IP.IsLinkLocalUnicast() {
				continue
			}
			if ipn.IP.IsPrivate() {
				out = append(out, ipn.IP.String())
			} else {
				other = append(other, ipn.IP.String())
			}
		}
	}
	return append(out, other...)
}
