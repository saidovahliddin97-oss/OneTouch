package transfer

import (
	"bufio"
	"crypto/tls"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
)

// A phone upgrades /v1/screen; the core must forward a preamble to the app
// endpoint and then pass bytes through in both directions.
func TestScreenSessionSplice(t *testing.T) {
	app, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	gotPre := make(chan SessionPreamble, 1)
	go func() {
		c, err := app.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		r := bufio.NewReader(c)
		line, _ := r.ReadString('\n')
		var p SessionPreamble
		json.Unmarshal([]byte(line), &p)
		gotPre <- p
		buf := make([]byte, 4)
		io.ReadFull(r, buf)                    // "ping" from the phone
		c.Write([]byte("pong:" + string(buf))) // echo back through the splice
	}()

	srv := &Server{Dev: &identity.Device{ID: "mac", Name: "Mac"}, SessionAddr: app.Addr().String(), Logf: t.Logf}
	ts := httptest.NewTLSServer(srv.apiMux())
	defer ts.Close()

	conn, err := tls.Dial("tcp", strings.TrimPrefix(ts.URL, "https://"), &tls.Config{InsecureSkipVerify: true})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	io.WriteString(conn, "GET /v1/screen?id=p1&name=Pixel&token=t0k HTTP/1.1\r\nHost: x\r\nUpgrade: onetouch\r\nConnection: Upgrade\r\n\r\n")
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, nil)
	if err != nil || resp.StatusCode != 101 {
		t.Fatalf("upgrade failed: %v %v", resp, err)
	}
	io.WriteString(conn, "ping")
	p := <-gotPre
	if p.Kind != "screen" || p.ID != "p1" || p.Name != "Pixel" || p.Token != "t0k" {
		t.Fatalf("bad preamble %+v", p)
	}
	out := make([]byte, 9)
	if _, err := io.ReadFull(br, out); err != nil || string(out) != "pong:ping" {
		t.Fatalf("splice: %q %v", out, err)
	}
}

func TestScreenSessionNeedsApp(t *testing.T) {
	srv := &Server{Dev: &identity.Device{ID: "mac"}, SessionAddr: "127.0.0.1:1", Logf: t.Logf}
	ts := httptest.NewTLSServer(srv.apiMux())
	defer ts.Close()
	req, _ := http.NewRequest("GET", ts.URL+"/v1/screen?id=a&token=b", nil)
	req.Header.Set("Upgrade", "onetouch")
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("want 503, got %d", resp.StatusCode)
	}
}
