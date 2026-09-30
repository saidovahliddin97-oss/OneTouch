// fakephone simulates the Android app for tests: announces os=android over
// mDNS, accepts an offer and pulls the files over fingerprint-pinned TLS.
package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"

	"github.com/grandcat/zeroconf"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/transfer"
)

func main() {
	out := os.Args[1]
	srv, err := zeroconf.Register("Pixel-fake01", "_onetouch._tcp", "local.", 47480, []string{"v=2", "id=fake01", "name=Pixel", "os=android"}, nil)
	if err != nil {
		panic(err)
	}
	defer srv.Shutdown()
	http.HandleFunc("POST /v1/offer", func(w http.ResponseWriter, r *http.Request) {
		var o transfer.OfferMsg
		json.NewDecoder(r.Body).Decode(&o)
		w.Write([]byte("{}"))
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		go func() {
			c := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true,
				VerifyConnection: func(cs tls.ConnectionState) error {
					s := sha256.Sum256(cs.PeerCertificates[0].Raw)
					if hex.EncodeToString(s[:]) != o.FP {
						return errors.New("pin mismatch")
					}
					return nil
				}}}}
			for i, f := range o.Files {
				resp, err := c.Get(fmt.Sprintf("https://%s/v1/offers/%s/%d", net.JoinHostPort(host, strconv.Itoa(o.Port)), o.ID, i))
				if err != nil {
					fmt.Println("ERR", err)
					continue
				}
				dst, _ := os.Create(filepath.Join(out, f.Name))
				n, _ := io.Copy(dst, resp.Body)
				dst.Close()
				resp.Body.Close()
				fmt.Printf("PHONE got %s (%d bytes) from %s\n", f.Name, n, o.From)
			}
		}()
	})
	fmt.Println("fake phone listening")
	http.ListenAndServe(":47480", nil)
}
