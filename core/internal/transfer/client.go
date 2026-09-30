package transfer

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
)

// Client talks to a peer's TLS API, pinning its certificate fingerprint.
type Client struct {
	Addr        string // host:port
	Fingerprint string // expected cert SHA-256; empty = trust on first use
	From        string // our device name, sent as ?from=
	SeenFP      string // fingerprint actually presented by the peer
	http        *http.Client
}

func NewClient(addr, fp, from string) *Client {
	c := &Client{Addr: addr, Fingerprint: fp, From: from}
	tlsCfg := &tls.Config{
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true, // self-signed: verified by fingerprint below
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return errors.New("peer sent no certificate")
			}
			got := identity.Fingerprint(cs.PeerCertificates[0].Raw)
			c.SeenFP = got
			if c.Fingerprint != "" && got != c.Fingerprint {
				return fmt.Errorf("certificate fingerprint mismatch: got %s, want %s", got[:16], c.Fingerprint[:min(16, len(c.Fingerprint))])
			}
			return nil
		},
	}
	c.http = &http.Client{Transport: &http.Transport{
		TLSClientConfig:     tlsCfg,
		DialContext:         (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		TLSHandshakeTimeout: 5 * time.Second,
		DisableCompression:  true,
		WriteBufferSize:     1 << 20,
		ReadBufferSize:      1 << 20,
		IdleConnTimeout:     30 * time.Second,
	}}
	return c
}

func (c *Client) url(path string, q url.Values) string {
	u := url.URL{Scheme: "https", Host: c.Addr, Path: path, RawQuery: q.Encode()}
	return u.String()
}

func (c *Client) Info(ctx context.Context) (map[string]string, error) {
	req, _ := http.NewRequestWithContext(ctx, "GET", c.url("/v1/info", nil), nil)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var m map[string]string
	return m, json.NewDecoder(resp.Body).Decode(&m)
}

type progressReader struct {
	r     io.Reader
	n     int64
	total int64
	fn    func(sent, total int64)
	last  time.Time
}

func (p *progressReader) Read(b []byte) (int, error) {
	n, err := p.r.Read(b)
	p.n += int64(n)
	if p.fn != nil && (time.Since(p.last) > 200*time.Millisecond || err == io.EOF) {
		p.last = time.Now()
		p.fn(p.n, p.total)
	}
	return n, err
}

// SendFile streams a local file to the peer.
func (c *Client) SendFile(ctx context.Context, path string, progress func(sent, total int64)) (UploadResult, error) {
	var res UploadResult
	f, err := os.Open(path)
	if err != nil {
		return res, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return res, err
	}
	if st.IsDir() {
		return res, fmt.Errorf("%s is a directory (folders are not supported yet)", path)
	}
	body := &progressReader{r: f, total: st.Size(), fn: progress}
	q := url.Values{"name": {filepath.Base(path)}, "from": {c.From}}
	req, _ := http.NewRequestWithContext(ctx, "PUT", c.url("/v1/files", q), body)
	req.ContentLength = st.Size()
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := c.http.Do(req)
	if err != nil {
		return res, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return res, fmt.Errorf("peer returned %s: %s", resp.Status, b)
	}
	return res, json.NewDecoder(resp.Body).Decode(&res)
}
