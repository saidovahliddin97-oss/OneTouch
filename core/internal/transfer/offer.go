package transfer

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// OfferMsg is POSTed to a phone to announce files it can pull from us.
type OfferMsg struct {
	ID     string      `json:"id"`
	From   string      `json:"from"`
	FromID string      `json:"fromId"`
	Port   int         `json:"port"`
	FP     string      `json:"fp"`
	Files  []OfferFile `json:"files"`
}

type OfferFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
}

type OfferResult struct {
	ID    string   `json:"id"`
	Peers []string `json:"peers"`
}

// NewOffer validates paths and builds the announcement for them.
func NewOffer(paths []string, from, fromID, fp string, port int) (OfferMsg, error) {
	buf := make([]byte, 8)
	rand.Read(buf)
	o := OfferMsg{ID: hex.EncodeToString(buf), From: from, FromID: fromID, Port: port, FP: fp}
	for _, p := range paths {
		st, err := os.Stat(p)
		if err != nil {
			return o, err
		}
		if st.IsDir() {
			return o, fmt.Errorf("%s: папки пока не поддерживаются", filepath.Base(p))
		}
		o.Files = append(o.Files, OfferFile{Name: SafeName(st.Name()), Size: st.Size()})
	}
	return o, nil
}

// SendOffer delivers an offer to a phone listening on host:port.
func SendOffer(ctx context.Context, host string, port int, o OfferMsg) error {
	b, _ := json.Marshal(o)
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	u := "http://" + net.JoinHostPort(host, strconv.Itoa(port)) + "/v1/offer"
	req, _ := http.NewRequestWithContext(ctx, "POST", u, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("phone returned %s", resp.Status)
	}
	return nil
}
