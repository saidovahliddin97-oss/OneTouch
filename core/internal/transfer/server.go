package transfer

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os/exec"
	"runtime"
	"strconv"
	"time"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
)

//go:embed web/index.html
var indexHTML []byte

// Protocol (v1), served over TLS 1.3 with a self-signed, fingerprint-pinned cert:
//
//	GET  /v1/info                         -> {"id","name","os","fp"}
//	PUT  /v1/files?name=X&mode=save|clip&from=Y  body = raw file bytes
//	GET  /v1/clip                         -> ClipMeta (404 if empty)
//	GET  /v1/clip/data                    -> raw bytes of the buffered item
//
// The same upload endpoint is exposed on the plain-HTTP web port under
// /web/files, guarded by a random token, for phones without the app.
type Server struct {
	Dev      *identity.Device
	OutDir   string
	Buffer   *Buffer
	AutoSave bool // clips are saved straight to OutDir instead of buffered
	Token    string
	Notify   bool
	Logf     func(format string, a ...any)
}

func (s *Server) logf(f string, a ...any) {
	if s.Logf != nil {
		s.Logf(f, a...)
	} else {
		log.Printf(f, a...)
	}
}

func (s *Server) apiMux() *http.ServeMux {
	m := http.NewServeMux()
	m.HandleFunc("GET /v1/info", s.handleInfo)
	m.HandleFunc("PUT /v1/files", s.handleUpload)
	m.HandleFunc("POST /v1/files", s.handleUpload)
	m.HandleFunc("GET /v1/clip", s.handleClipMeta)
	m.HandleFunc("GET /v1/clip/data", s.handleClipData)
	return m
}

func (s *Server) webMux() *http.ServeMux {
	m := http.NewServeMux()
	m.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		if !s.tokenOK(r) {
			http.Error(w, "OneTouch: scan the QR code shown by `onetouch serve`", http.StatusForbidden)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "ot", Value: s.Token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 30 * 24 * 3600})
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(indexHTML)
	})
	guard := func(h http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if !s.tokenOK(r) {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			h(w, r)
		}
	}
	m.HandleFunc("GET /web/info", guard(s.handleInfo))
	m.HandleFunc("PUT /web/files", guard(s.handleUpload))
	m.HandleFunc("POST /web/files", guard(s.handleUpload))
	m.HandleFunc("GET /web/clip", guard(s.handleClipMeta))
	m.HandleFunc("GET /web/clip/data", guard(s.handleClipData))
	return m
}

func (s *Server) tokenOK(r *http.Request) bool {
	t := r.URL.Query().Get("t")
	if t == "" {
		if c, err := r.Cookie("ot"); err == nil {
			t = c.Value
		}
	}
	return s.Token != "" && subtle.ConstantTimeCompare([]byte(t), []byte(s.Token)) == 1
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]string{"id": s.Dev.ID, "name": s.Dev.Name, "os": s.Dev.OS, "fp": s.Dev.Fingerprint})
}

type UploadResult struct {
	Mode  string `json:"mode"`
	Name  string `json:"name"`
	Path  string `json:"path,omitempty"`
	Bytes int64  `json:"bytes"`
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	name := SafeName(q.Get("name"))
	mode := q.Get("mode")
	from := q.Get("from")
	if from == "" {
		from = r.RemoteAddr
	}
	start := time.Now()
	var res UploadResult
	if mode == "clip" && !s.AutoSave {
		m, err := s.Buffer.Put(r.Body, name, from)
		if err != nil {
			s.logf("clip from %s failed: %v", from, err)
			http.Error(w, err.Error(), 500)
			return
		}
		res = UploadResult{Mode: "clip", Name: m.Name, Bytes: m.Size}
		s.logf("📋 %s → buffer: %s (%s, %s)", from, m.Name, HumanBytes(m.Size), rate(m.Size, start))
		s.notify("OneTouch: в буфере", fmt.Sprintf("%s от %s — вставьте хоткеем/жестом", m.Name, from))
	} else {
		p, n, err := writeAtomic(s.OutDir, name, r.Body)
		if err != nil {
			s.logf("file from %s failed: %v", from, err)
			http.Error(w, err.Error(), 500)
			return
		}
		res = UploadResult{Mode: "save", Name: name, Path: p, Bytes: n}
		s.logf("📥 %s → %s (%s, %s)", from, p, HumanBytes(n), rate(n, start))
		s.notify("OneTouch: файл получен", fmt.Sprintf("%s от %s", name, from))
	}
	writeJSON(w, 200, res)
}

func (s *Server) handleClipMeta(w http.ResponseWriter, r *http.Request) {
	m, ok := s.Buffer.Get()
	if !ok {
		writeJSON(w, 404, map[string]string{"error": "buffer empty"})
		return
	}
	writeJSON(w, 200, m)
}

func (s *Server) handleClipData(w http.ResponseWriter, r *http.Request) {
	f, m, err := s.Buffer.Open()
	if err != nil {
		http.Error(w, "buffer empty", 404)
		return
	}
	defer f.Close()
	w.Header().Set("X-OneTouch-Name", m.Name)
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", m.Name))
	http.ServeContent(w, r, m.Name, m.Time, f)
}

// Run serves the TLS API on apiPort and the web UI on webPort until ctx ends.
func (s *Server) Run(ctx context.Context, apiLn, webLn net.Listener) error {
	api := &http.Server{
		Handler:           s.apiMux(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second, // drop idle keep-alives: nothing runs while idle
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{s.Dev.Cert},
			MinVersion:   tls.VersionTLS13,
		},
	}
	web := &http.Server{Handler: s.webMux(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second}
	errc := make(chan error, 2)
	go func() { errc <- api.ServeTLS(apiLn, "", "") }()
	go func() { errc <- web.Serve(webLn) }()
	select {
	case <-ctx.Done():
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			api.Close()
			web.Close()
			return err
		}
	}
	sctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	api.Shutdown(sctx)
	web.Shutdown(sctx)
	return nil
}

func (s *Server) notify(title, msg string) {
	if !s.Notify {
		return
	}
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("osascript", "-e", fmt.Sprintf("display notification %s with title %s", strconv.Quote(msg), strconv.Quote(title)))
	case "linux":
		cmd = exec.Command("notify-send", title, msg)
	case "windows":
		ps := `Add-Type -AssemblyName System.Windows.Forms;$n=New-Object System.Windows.Forms.NotifyIcon;` +
			`$n.Icon=[System.Drawing.SystemIcons]::Information;$n.Visible=$true;` +
			`$n.ShowBalloonTip(4000,$env:OT_T,$env:OT_M,'Info');Start-Sleep 5;$n.Dispose()`
		cmd = exec.Command("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps)
		cmd.Env = append(cmd.Environ(), "OT_T="+title, "OT_M="+msg)
	default:
		return
	}
	go cmd.Run()
}

func HumanBytes(n int64) string {
	const u = 1024
	if n < u {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(u), 0
	for m := n / u; m >= u; m /= u {
		div *= u
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}

func rate(n int64, start time.Time) string {
	d := time.Since(start).Seconds()
	if d <= 0 {
		d = 1e-6
	}
	return HumanBytes(int64(float64(n)/d)) + "/s"
}
