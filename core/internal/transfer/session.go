package transfer

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Screen sessions.
//
// A phone opens a pinned-TLS connection and upgrades it:
//
//	GET /v1/screen   Upgrade: onetouch   — watch and control this computer
//	GET /v1/mirror   Upgrade: onetouch   — show the phone's screen here
//
// The core does no media work: after "101 Switching Protocols" it connects
// to the desktop app's media endpoint on 127.0.0.1:SessionPort, writes one
// JSON preamble line describing the peer, and splices the two streams. The
// app (ScreenCaptureKit/VideoToolbox on macOS) then speaks the frame
// protocol directly with the phone:
//
//	[type u8][length u32 big-endian][payload]
//
// Desktop → phone: 1 hello JSON, 2 H.264 SPS/PPS (Annex-B), 3 H.264 frame
// (Annex-B), 4 status JSON, 6 offer JSON (files to download right away).
// Phone → desktop: 16 input JSON, 17 command JSON; in a mirror session the
// phone sends 1/2/3 and the desktop may send 6.
const SessionPort = 47472

// SessionPreamble is the first line the desktop app reads on a new session.
type SessionPreamble struct {
	Kind  string `json:"kind"` // screen | mirror
	ID    string `json:"id"`
	Name  string `json:"name"`
	Token string `json:"token"`
	Addr  string `json:"addr"`
}

func (s *Server) handleSession(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Upgrade") != "onetouch" {
			http.Error(w, "upgrade required", http.StatusUpgradeRequired)
			return
		}
		q := r.URL.Query()
		pre := SessionPreamble{Kind: kind, ID: q.Get("id"), Name: q.Get("name"), Token: q.Get("token"), Addr: r.RemoteAddr}
		if pre.ID == "" || pre.Token == "" {
			http.Error(w, "id and token required", http.StatusBadRequest)
			return
		}
		addr := s.SessionAddr
		if addr == "" {
			addr = "127.0.0.1:" + strconv.Itoa(SessionPort)
		}
		back, err := net.DialTimeout("tcp", addr, 2*time.Second)
		if err != nil {
			http.Error(w, "экран недоступен: приложение OneTouch не запущено", http.StatusServiceUnavailable)
			return
		}
		hj, ok := w.(http.Hijacker)
		if !ok {
			back.Close()
			http.Error(w, "hijack unsupported", 500)
			return
		}
		conn, rw, err := hj.Hijack()
		if err != nil {
			back.Close()
			return
		}
		rw.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: onetouch\r\nConnection: Upgrade\r\n\r\n")
		if err := rw.Flush(); err != nil {
			conn.Close()
			back.Close()
			return
		}
		b, _ := json.Marshal(pre)
		if _, err := back.Write(append(b, '\n')); err != nil {
			conn.Close()
			back.Close()
			return
		}
		s.logf("🖥 %s session from %s (%s)", kind, pre.Name, r.RemoteAddr)
		splice(conn, rw.Reader, back)
		s.logf("🖥 %s session from %s ended", kind, pre.Name)
	}
}

// splice copies both directions until either side closes. phoneR holds any
// bytes the HTTP server already buffered from the phone.
func splice(phone net.Conn, phoneR io.Reader, app net.Conn) {
	var once sync.Once
	closeBoth := func() { once.Do(func() { phone.Close(); app.Close() }) }
	done := make(chan struct{}, 2)
	go func() { io.Copy(app, phoneR); closeBoth(); done <- struct{}{} }()
	go func() { io.Copy(phone, app); closeBoth(); done <- struct{}{} }()
	<-done
	<-done
}
