package transfer

import (
	"bytes"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
)

// The menu-bar app asks its core to push a file to another computer.
func TestLocalSendToComputer(t *testing.T) {
	out := t.TempDir()
	recv := &Server{Dev: &identity.Device{ID: "b", Name: "Other"}, OutDir: out, Logf: t.Logf}
	rts := httptest.NewTLSServer(recv.apiMux())
	defer rts.Close()
	fp := identity.Fingerprint(rts.Certificate().Raw)
	host, port, _ := net.SplitHostPort(strings.TrimPrefix(rts.URL, "https://"))

	src := filepath.Join(t.TempDir(), "doc.pdf")
	os.WriteFile(src, []byte("hello pdf"), 0o644)
	send := &Server{Dev: &identity.Device{ID: "a", Name: "Mac"}, Logf: t.Logf}
	lts := httptest.NewServer(send.localMux())
	defer lts.Close()
	body := `{"paths":["` + src + `"],"target":{"name":"Other","host":"` + host + `","port":` + port + `,"fp":"` + fp + `"}}`
	resp, err := http.Post(lts.URL+"/local/send", "application/json", strings.NewReader(body))
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("send: %v %v", resp.StatusCode, err)
	}
	got, _ := os.ReadFile(filepath.Join(out, "doc.pdf"))
	if !bytes.Equal(got, []byte("hello pdf")) {
		t.Fatalf("receiver got %q", got)
	}

	// A wrong fingerprint must be refused.
	bad := strings.Replace(body, fp, strings.Repeat("0", 64), 1)
	resp, _ = http.Post(lts.URL+"/local/send", "application/json", strings.NewReader(bad))
	if resp.StatusCode == 200 {
		t.Fatal("send with a wrong fingerprint succeeded")
	}
}
