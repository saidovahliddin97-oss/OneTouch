package transfer

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"sync"
	"time"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
)

// Protocol (v2).
//
// Desktop node, TLS 1.3 with a self-signed, fingerprint-pinned cert:
//
//	GET  /v1/info                        -> {"id","name","os","fp"}
//	PUT  /v1/files?name=X&from=Y         body = raw file bytes, saved to OutDir
//	GET  /v1/offers/{id}/{n}             n-th file of an offer made to a phone
//
// Phone node, plain HTTP (metadata only; the file itself is then pulled
// from the desktop over pinned TLS):
//
//	POST /v1/offer                       Offer JSON -> phone shows "Получить"
//
// Control API for the local menu-bar app, bound to 127.0.0.1 only:
//
//	POST /local/register-offer {"paths":[...]} -> Offer JSON (no delivery)
//	POST /local/offer {"paths":[...], "targets":[...]}
//	                                     offer files to phones (targets: phones
//	                                     already found by the app; else mDNS)
//	GET  /local/peers                    peers found via mDNS
type Server struct {
	Dev    *identity.Device
	OutDir string
	Port   int // TLS port, announced in offers
	// SessionAddr is the desktop app media endpoint (default 127.0.0.1:SessionPort).
	SessionAddr string
	Notify      bool        // OS notification per received file (CLI use)
	Events      func(Event) // structured events for the menu-bar app
	Offer       func([]string, []Target) (OfferResult, error)
	Peers       func() any
	Logf        func(format string, a ...any)

	mu     sync.Mutex
	offers map[string]*offer
}

// Event is emitted as one JSON line on stdout with `serve --events`.
type Event struct {
	Type  string   `json:"type"` // ready | received | offered | error
	ID    string   `json:"id,omitempty"`
	FP    string   `json:"fp,omitempty"`
	Port  int      `json:"port,omitempty"`
	Path  string   `json:"path,omitempty"`
	Name  string   `json:"name,omitempty"`
	From  string   `json:"from,omitempty"`
	Bytes int64    `json:"bytes,omitempty"`
	Peers []string `json:"peers,omitempty"`
	Error string   `json:"error,omitempty"`
}

type offer struct {
	paths   []string
	expires time.Time
}

func (s *Server) logf(f string, a ...any) {
	if s.Logf != nil {
		s.Logf(f, a...)
	} else {
		log.Printf(f, a...)
	}
}

func (s *Server) emit(e Event) {
	if s.Events != nil {
		s.Events(e)
	}
}

// RegisterOffer makes paths downloadable at /v1/offers/{id}/{n} for 15 min.
func (s *Server) RegisterOffer(id string, paths []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.offers == nil {
		s.offers = map[string]*offer{}
	}
	now := time.Now()
	for k, o := range s.offers {
		if now.After(o.expires) {
			delete(s.offers, k)
		}
	}
	s.offers[id] = &offer{paths: paths, expires: now.Add(15 * time.Minute)}
}

func (s *Server) apiMux() *http.ServeMux {
	m := http.NewServeMux()
	m.HandleFunc("GET /v1/info", s.handleInfo)
	m.HandleFunc("PUT /v1/files", s.handleUpload)
	m.HandleFunc("POST /v1/files", s.handleUpload)
	m.HandleFunc("GET /v1/offers/{id}/{n}", s.handleOfferData)
	m.HandleFunc("GET /v1/screen", s.handleSession("screen"))
	m.HandleFunc("GET /v1/mirror", s.handleSession("mirror"))
	return m
}

func (s *Server) localMux() *http.ServeMux {
	m := http.NewServeMux()
	m.HandleFunc("POST /local/offer", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Paths   []string `json:"paths"`
			Targets []Target `json:"targets"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.Paths) == 0 {
			http.Error(w, "paths required", 400)
			return
		}
		if s.Offer == nil {
			http.Error(w, "offers disabled", 501)
			return
		}
		res, err := s.Offer(req.Paths, req.Targets)
		if err != nil {
			writeJSON(w, 502, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, res)
	})
	// Registers files for download without notifying anyone; the app hands
	// the returned offer to a phone over an open screen session.
	m.HandleFunc("POST /local/register-offer", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Paths []string `json:"paths"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.Paths) == 0 {
			http.Error(w, "paths required", 400)
			return
		}
		o, err := NewOffer(req.Paths, s.Dev.Name, s.Dev.ID, s.Dev.Fingerprint, s.Port)
		if err != nil {
			writeJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		s.RegisterOffer(o.ID, req.Paths)
		writeJSON(w, 200, o)
	})
	// Pushes files to another computer (its Desktop) over pinned TLS.
	m.HandleFunc("POST /local/send", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Paths  []string `json:"paths"`
			Target Target   `json:"target"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.Paths) == 0 || req.Target.Host == "" || req.Target.FP == "" {
			http.Error(w, "paths and target required", 400)
			return
		}
		c := NewClient(net.JoinHostPort(req.Target.Host, strconv.Itoa(req.Target.Port)), req.Target.FP, s.Dev.Name)
		var sent []string
		for _, p := range req.Paths {
			res, err := c.SendFile(r.Context(), p, nil)
			if err != nil {
				writeJSON(w, 502, map[string]any{"error": err.Error(), "sent": sent})
				return
			}
			sent = append(sent, res.Name)
			s.logf("📤 %s → %s", res.Name, req.Target.Name)
		}
		writeJSON(w, 200, map[string]any{"sent": sent})
	})
	m.HandleFunc("GET /local/peers", func(w http.ResponseWriter, r *http.Request) {
		if s.Peers == nil {
			writeJSON(w, 200, []any{})
			return
		}
		writeJSON(w, 200, s.Peers())
	})
	return m
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
	Name  string `json:"name"`
	Path  string `json:"path,omitempty"`
	Bytes int64  `json:"bytes"`
}

func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	name := SafeName(q.Get("name"))
	from := q.Get("from")
	if from == "" {
		from = r.RemoteAddr
	}
	start := time.Now()
	p, n, err := writeAtomic(s.OutDir, name, r.Body)
	if err != nil {
		s.logf("file from %s failed: %v", from, err)
		s.emit(Event{Type: "error", Name: name, From: from, Error: err.Error()})
		http.Error(w, err.Error(), 500)
		return
	}
	s.logf("📥 %s → %s (%s, %s)", from, p, HumanBytes(n), rate(n, start))
	s.emit(Event{Type: "received", Path: p, Name: name, From: from, Bytes: n})
	s.notify("OneTouch: файл получен", fmt.Sprintf("%s от %s", name, from))
	writeJSON(w, 200, UploadResult{Name: name, Path: p, Bytes: n})
}

func (s *Server) handleOfferData(w http.ResponseWriter, r *http.Request) {
	n, err := strconv.Atoi(r.PathValue("n"))
	s.mu.Lock()
	o := s.offers[r.PathValue("id")]
	s.mu.Unlock()
	if err != nil || o == nil || time.Now().After(o.expires) || n < 0 || n >= len(o.paths) {
		http.Error(w, "offer expired", 404)
		return
	}
	f, err := os.Open(o.paths[n])
	if err != nil {
		http.Error(w, "file gone", 410)
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	name := SafeName(st.Name())
	w.Header().Set("X-OneTouch-Name", name)
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, name, st.ModTime(), f)
	s.logf("📤 %s → %s", name, r.RemoteAddr)
}

// Run serves the TLS API and the loopback control API until ctx ends.
func (s *Server) Run(ctx context.Context, apiLn, localLn net.Listener) error {
	api := &http.Server{
		Handler:           s.apiMux(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second, // drop idle keep-alives: nothing runs while idle
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{s.Dev.Cert},
			MinVersion:   tls.VersionTLS12, // Android 9 and older lack TLS 1.3 in HttpsURLConnection
		},
	}
	local := &http.Server{Handler: s.localMux(), ReadHeaderTimeout: 5 * time.Second}
	errc := make(chan error, 2)
	go func() { errc <- api.ServeTLS(apiLn, "", "") }()
	go func() { errc <- local.Serve(localLn) }()
	select {
	case <-ctx.Done():
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			api.Close()
			local.Close()
			return err
		}
	}
	sctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	api.Shutdown(sctx)
	local.Shutdown(sctx)
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
