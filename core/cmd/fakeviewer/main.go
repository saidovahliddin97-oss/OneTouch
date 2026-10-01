// fakeviewer plays the phone's side of screen sessions in automated tests.
//
//	fakeviewer screen <host:port> <record-file> [grab-expected-file]
//	    watches the desktop: checks hello/config/H.264 frames, sends input,
//	    asks for the Finder selection («Забрать») and downloads the offer.
//	fakeviewer mirror <host:port> <record-file>
//	    replays recorded frames as if they were the phone's screen.
package main

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

func main() {
	if len(os.Args) < 4 {
		fmt.Fprintln(os.Stderr, "usage: fakeviewer screen|mirror host:port record-file [expected-grab-file]")
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "screen":
		expect := ""
		if len(os.Args) > 4 {
			expect = os.Args[4]
		}
		err = screen(os.Args[2], os.Args[3], expect)
	case "mirror":
		err = mirror(os.Args[2], os.Args[3])
	}
	if err != nil {
		fmt.Println("FAIL:", err)
		os.Exit(1)
	}
}

func connect(addr, kind string) (net.Conn, *bufio.Reader, error) {
	c, err := tls.Dial("tcp", addr, &tls.Config{InsecureSkipVerify: true})
	if err != nil {
		return nil, nil, err
	}
	c.SetDeadline(time.Now().Add(60 * time.Second))
	fmt.Fprintf(c, "GET /v1/%s?id=ci-phone&name=CI%%20Phone&token=ci-token HTTP/1.1\r\nHost: x\r\nUpgrade: onetouch\r\nConnection: Upgrade\r\n\r\n", kind)
	r := bufio.NewReader(c)
	resp, err := http.ReadResponse(r, nil)
	if err != nil {
		return nil, nil, err
	}
	if resp.StatusCode != 101 {
		b, _ := io.ReadAll(resp.Body)
		return nil, nil, fmt.Errorf("upgrade: %s %s", resp.Status, b)
	}
	return c, r, nil
}

func readFrame(r io.Reader) (byte, []byte, error) {
	var h [5]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return 0, nil, err
	}
	p := make([]byte, binary.BigEndian.Uint32(h[1:]))
	_, err := io.ReadFull(r, p)
	return h[0], p, err
}

func writeFrame(w io.Writer, t byte, p []byte) error {
	var h [5]byte
	h[0] = t
	binary.BigEndian.PutUint32(h[1:], uint32(len(p)))
	_, err := w.Write(append(h[:], p...))
	return err
}

func writeJSON(w io.Writer, t byte, v any) error {
	b, _ := json.Marshal(v)
	return writeFrame(w, t, b)
}

func screen(addr, record, expect string) error {
	c, r, err := connect(addr, "screen")
	if err != nil {
		return err
	}
	defer c.Close()
	rec, err := os.Create(record)
	if err != nil {
		return err
	}
	defer rec.Close()
	var configs, frames, keys int
	var hello map[string]any
	grabbed := false
	for frames < 45 || (expect != "" && !grabbed) {
		t, p, err := readFrame(r)
		if err != nil {
			return fmt.Errorf("after %d frames: %w", frames, err)
		}
		switch t {
		case 1:
			json.Unmarshal(p, &hello)
			fmt.Printf("✓ hello %v\n", hello)
			writeFrame(rec, 1, p)
		case 2:
			if !bytes.HasPrefix(p, []byte{0, 0, 0, 1}) || p[4]&0x1f != 7 {
				return fmt.Errorf("config is not Annex-B SPS: % x", p[:min(8, len(p))])
			}
			configs++
			writeFrame(rec, 2, p)
		case 3:
			if configs == 0 {
				return fmt.Errorf("frame before SPS/PPS")
			}
			frames++
			if p[0] == 1 {
				keys++
			}
			writeFrame(rec, 3, p)
			if frames == 5 {
				writeJSON(c, 16, map[string]any{"t": "move", "x": 0.5, "y": 0.5})
				if expect != "" {
					writeJSON(c, 17, map[string]any{"cmd": "grab"})
				}
			}
		case 4:
			fmt.Printf("status: %s\n", p)
		case 6:
			if err := download(addr, p, expect); err != nil {
				return err
			}
			grabbed = true
		}
	}
	fmt.Printf("✓ screen: %d H.264 frames (%d key), %d SPS/PPS\n", frames, keys, configs)
	return nil
}

func download(addr string, offerJSON []byte, expect string) error {
	var o struct {
		ID    string `json:"id"`
		Port  int    `json:"port"`
		Files []struct {
			Name string `json:"name"`
		} `json:"files"`
	}
	if err := json.Unmarshal(offerJSON, &o); err != nil || len(o.Files) == 0 {
		return fmt.Errorf("bad offer %s", offerJSON)
	}
	host, _, _ := net.SplitHostPort(addr)
	cl := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}
	resp, err := cl.Get(fmt.Sprintf("https://%s/v1/offers/%s/0", net.JoinHostPort(host, fmt.Sprint(o.Port)), o.ID))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	got, _ := io.ReadAll(resp.Body)
	want, _ := os.ReadFile(expect)
	if !bytes.Equal(got, want) {
		return fmt.Errorf("grabbed file differs (%d vs %d bytes)", len(got), len(want))
	}
	fmt.Printf("✓ grab: received %s (%d bytes) during the session\n", o.Files[0].Name, len(got))
	return nil
}

func mirror(addr, record string) error {
	data, err := os.ReadFile(record)
	if err != nil {
		return err
	}
	c, r, err := connect(addr, "mirror")
	if err != nil {
		return err
	}
	defer c.Close()
	// Print what the desktop sends back (input events from clicks in its window).
	go func() {
		for {
			t, p, err := readFrame(r)
			if err != nil {
				return
			}
			fmt.Printf("got frame %d: %s\n", t, p)
		}
	}()
	in := bytes.NewReader(data)
	n := 0
	for {
		t, p, err := readFrame(in)
		if err != nil {
			break
		}
		if err := writeFrame(c, t, p); err != nil {
			return err
		}
		if t == 3 {
			n++
			time.Sleep(66 * time.Millisecond)
		}
	}
	fmt.Printf("✓ mirror: sent %d frames\n", n)
	time.Sleep(8 * time.Second) // keep the window open for input tests
	return nil
}
