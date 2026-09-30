// Command onetouch is the OneTouch desktop node: it announces itself over
// mDNS, receives files from phones over TLS, and offers files to phones.
// The macOS menu-bar app runs it as `onetouch serve --events`.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/discovery"
	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
	"github.com/saidovahliddin97-oss/onetouch/core/internal/transfer"
)

var version = "0.2.0"

const (
	defaultPort  = 47470
	controlPort  = 47471 // loopback only
	browseWindow = 1500 * time.Millisecond
)

const usage = `OneTouch %s — мгновенная передача файлов по локальной сети (P2P, TLS, mDNS)

  onetouch serve  [--name N] [--out DIR]        узел: принимает файлы с телефона
  onetouch offer  <файл...>                     предложить файлы телефону (там появится «Получить»)
  onetouch send   <файл...> [--to ИМЯ]          отправить файл другому компьютеру
  onetouch peers                                устройства в сети
  onetouch id     [--name N]                    имя и отпечаток сертификата
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, usage, version)
		os.Exit(2)
	}
	cmd, args := os.Args[1], os.Args[2:]
	var err error
	switch cmd {
	case "serve":
		err = cmdServe(args)
	case "offer":
		err = cmdOffer(args)
	case "send":
		err = cmdSend(args)
	case "peers":
		err = cmdPeers(args)
	case "id":
		err = cmdID(args)
	case "version", "--version", "-v":
		fmt.Println(version)
	case "help", "-h", "--help":
		fmt.Printf(usage, version)
	default:
		fmt.Fprintf(os.Stderr, "неизвестная команда %q\n\n"+usage, cmd, version)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "ошибка:", err)
		os.Exit(1)
	}
}

// parse lets flags appear before or after positional args.
func parse(fs *flag.FlagSet, args []string) []string {
	var pos []string
	for {
		fs.Parse(args)
		if fs.NArg() == 0 {
			return pos
		}
		pos = append(pos, fs.Arg(0))
		args = fs.Args()[1:]
	}
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	name := fs.String("name", "", "имя устройства в сети")
	port := fs.Int("port", defaultPort, "TLS-порт")
	out := fs.String("out", transfer.DesktopDir(), "куда сохранять файлы")
	events := fs.Bool("events", false, "печатать события JSON-строками (для приложения в строке меню)")
	noNotify := fs.Bool("no-notify", false, "без системных уведомлений")
	noMDNS := fs.Bool("no-mdns", false, "не объявлять себя по mDNS (это делает приложение через системный Bonjour)")
	parse(fs, args)

	dev, err := identity.Load(*name)
	if err != nil {
		return err
	}
	apiLn, err := net.Listen("tcp", ":"+strconv.Itoa(*port))
	if err != nil {
		return fmt.Errorf("порт %d занят (OneTouch уже запущен?): %w", *port, err)
	}
	localLn, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(controlPort))
	if err != nil {
		return fmt.Errorf("порт %d занят: %w", controlPort, err)
	}

	var outMu sync.Mutex
	emit := func(e transfer.Event) {
		if !*events {
			return
		}
		b, _ := json.Marshal(e)
		outMu.Lock()
		os.Stdout.Write(append(b, '\n'))
		outMu.Unlock()
	}
	logf := func(f string, a ...any) {
		line := time.Now().Format("15:04:05 ") + fmt.Sprintf(f, a...)
		if *events {
			fmt.Fprintln(os.Stderr, line) // stdout is reserved for JSON events
		} else {
			fmt.Println(line)
		}
	}

	srv := &transfer.Server{Dev: dev, OutDir: *out, Notify: !*noNotify && !*events, Events: emit, Logf: logf}
	srv.Offer = func(paths []string, targets []transfer.Target) (transfer.OfferResult, error) {
		res, err := offerToPhones(context.Background(), dev, srv, paths, targets, *port)
		if err != nil {
			logf("offer %v failed: %v", paths, err)
			emit(transfer.Event{Type: "error", Error: err.Error()})
		} else {
			logf("📨 offered %d file(s) to %s", len(paths), strings.Join(res.Peers, ", "))
			emit(transfer.Event{Type: "offered", Name: filepath.Base(paths[0]), Peers: res.Peers})
		}
		return res, err
	}
	srv.Peers = func() any {
		ps, _ := discovery.Browse(context.Background(), browseWindow, dev.ID)
		return ps
	}

	if !*noMDNS {
		mdns, err := discovery.Announce(discovery.Announcement{
			ID: dev.ID, Name: dev.Name, OS: dev.OS, Fingerprint: dev.Fingerprint, Port: *port,
		})
		if err != nil {
			logf("⚠ mDNS недоступен: %v", err)
		} else {
			defer mdns.Shutdown()
		}
	}
	emit(transfer.Event{Type: "ready", ID: dev.ID, Name: dev.Name, FP: dev.Fingerprint, Port: *port})
	logf("OneTouch %s • %s • сохраняю в %s • жду файлы…", version, dev.Name, *out)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return srv.Run(ctx, apiLn, localLn)
}

// offerToPhones announces the files to the given phones, or, when none are
// given, to every phone found via mDNS.
func offerToPhones(ctx context.Context, dev *identity.Device, srv *transfer.Server, paths []string, targets []transfer.Target, port int) (transfer.OfferResult, error) {
	o, err := transfer.NewOffer(paths, dev.Name, dev.ID, dev.Fingerprint, port)
	if err != nil {
		return transfer.OfferResult{}, err
	}
	srv.RegisterOffer(o.ID, paths)
	if len(targets) == 0 {
		peers, err := discovery.Browse(ctx, browseWindow, dev.ID)
		if err != nil {
			return transfer.OfferResult{}, err
		}
		for _, p := range peers {
			if p.OS == "android" {
				for _, a := range p.Addrs {
					targets = append(targets, transfer.Target{Name: p.Name, Host: a, Port: p.Port})
				}
			}
		}
	}
	res := transfer.OfferResult{ID: o.ID}
	var lastErr error
	for _, t := range targets {
		if slices.Contains(res.Peers, t.Name) {
			continue // already reached via another address
		}
		if lastErr = transfer.SendOffer(ctx, t.Host, t.Port, o); lastErr == nil {
			res.Peers = append(res.Peers, t.Name)
		}
	}
	if len(res.Peers) == 0 && lastErr != nil {
		return res, fmt.Errorf("телефон найден, но не отвечает: %w", lastErr)
	}
	if len(res.Peers) == 0 {
		return res, errors.New("телефон не найден: откройте OneTouch на Android (та же Wi‑Fi сеть)")
	}
	return res, nil
}

// cmdOffer asks the running local node to offer files (it must serve them).
func cmdOffer(args []string) error {
	fs := flag.NewFlagSet("offer", flag.ExitOnError)
	files := parse(fs, args)
	if len(files) == 0 {
		return errors.New("укажите файл")
	}
	for i, f := range files {
		abs, err := filepath.Abs(f)
		if err != nil {
			return err
		}
		files[i] = abs
	}
	b, _ := json.Marshal(map[string][]string{"paths": files})
	resp, err := httpPost(fmt.Sprintf("http://127.0.0.1:%d/local/offer", controlPort), b)
	if err != nil {
		return fmt.Errorf("OneTouch не запущен (onetouch serve): %w", err)
	}
	var res struct {
		Peers []string `json:"peers"`
		Error string   `json:"error"`
	}
	json.Unmarshal(resp, &res)
	if res.Error != "" {
		return errors.New(res.Error)
	}
	fmt.Printf("✓ предложено: %s — нажмите «Получить» на телефоне\n", strings.Join(res.Peers, ", "))
	return nil
}

func resolve(ctx context.Context, dev *identity.Device, to, addr, fp string) (*transfer.Client, string, error) {
	if addr != "" {
		if _, _, err := net.SplitHostPort(addr); err != nil {
			addr = net.JoinHostPort(addr, strconv.Itoa(defaultPort))
		}
		c := transfer.NewClient(addr, fp, dev.Name)
		info, err := c.Info(ctx)
		if err != nil {
			return nil, "", err
		}
		return c, info["name"], nil
	}
	peers, err := discovery.Browse(ctx, 2*time.Second, dev.ID)
	if err != nil {
		return nil, "", err
	}
	var match []discovery.Peer
	for _, p := range peers {
		if p.Fingerprint == "" {
			continue // phones don't accept pushes; use `offer`
		}
		if to == "" || strings.Contains(strings.ToLower(p.Name), strings.ToLower(to)) || strings.HasPrefix(p.ID, to) {
			match = append(match, p)
		}
	}
	switch {
	case len(match) == 0:
		return nil, "", errors.New("компьютер-получатель не найден (там должен работать OneTouch); можно указать --addr")
	case len(match) > 1:
		return nil, "", fmt.Errorf("найдено несколько устройств, уточните --to: %s", names(match))
	}
	p := match[0]
	var lastErr error
	for _, a := range p.Addrs {
		c := transfer.NewClient(net.JoinHostPort(a, strconv.Itoa(p.Port)), p.Fingerprint, dev.Name)
		cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
		_, err := c.Info(cctx)
		cancel()
		if err == nil {
			return c, p.Name, nil
		}
		lastErr = err
	}
	return nil, "", fmt.Errorf("не удалось подключиться к %s: %v", p.Name, lastErr)
}

func names(ps []discovery.Peer) string {
	var s []string
	for _, p := range ps {
		s = append(s, p.Name)
	}
	return strings.Join(s, ", ")
}

func cmdPeers(args []string) error {
	fs := flag.NewFlagSet("peers", flag.ExitOnError)
	timeout := fs.Duration("timeout", 2*time.Second, "время поиска")
	parse(fs, args)
	dev, err := identity.Load("")
	if err != nil {
		return err
	}
	peers, err := discovery.Browse(context.Background(), *timeout, dev.ID)
	if err != nil {
		return err
	}
	if len(peers) == 0 {
		fmt.Println("Никого не найдено.")
	}
	for _, p := range peers {
		fmt.Printf("• %-20s %-8s %s:%d  id=%s\n", p.Name, p.OS, strings.Join(p.Addrs, ","), p.Port, p.ID)
	}
	return nil
}

func cmdSend(args []string) error {
	fs := flag.NewFlagSet("send", flag.ExitOnError)
	to := fs.String("to", "", "имя (часть имени) или id получателя")
	addr := fs.String("addr", "", "адрес получателя host:port (без mDNS)")
	fp := fs.String("fp", "", "ожидаемый отпечаток сертификата (с --addr)")
	files := parse(fs, args)
	if len(files) == 0 {
		return errors.New("укажите файл")
	}
	dev, err := identity.Load("")
	if err != nil {
		return err
	}
	ctx := context.Background()
	c, peerName, err := resolve(ctx, dev, *to, *addr, *fp)
	if err != nil {
		return err
	}
	for _, f := range files {
		start := time.Now()
		base := filepath.Base(f)
		res, err := c.SendFile(ctx, f, func(sent, total int64) {
			pct := 100.0
			if total > 0 {
				pct = 100 * float64(sent) / float64(total)
			}
			fmt.Fprintf(os.Stderr, "\r  %s  %5.1f%%   ", base, pct)
		})
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return fmt.Errorf("%s: %w", base, err)
		}
		fmt.Printf("✓ %s → %s: %s за %s\n", base, peerName, transfer.HumanBytes(res.Bytes), time.Since(start).Round(time.Millisecond))
	}
	return nil
}

func cmdID(args []string) error {
	fs := flag.NewFlagSet("id", flag.ExitOnError)
	name := fs.String("name", "", "задать новое имя устройства")
	parse(fs, args)
	dev, err := identity.Load(*name)
	if err != nil {
		return err
	}
	fmt.Printf("name: %s\nid:   %s\nos:   %s\nfp:   %s\ndir:  %s\n", dev.Name, dev.ID, dev.OS, dev.Fingerprint, dev.Dir)
	return nil
}

func httpPost(url string, body []byte) ([]byte, error) {
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}
