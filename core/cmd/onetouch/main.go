// Command onetouch is the OneTouch desktop/CLI node: it announces itself over
// mDNS, receives files over TLS, serves a zero-install web page for phones,
// and sends/pastes files to and from peers.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/mdp/qrterminal/v3"

	"github.com/saidovahliddin97-oss/onetouch/core/internal/discovery"
	"github.com/saidovahliddin97-oss/onetouch/core/internal/identity"
	"github.com/saidovahliddin97-oss/onetouch/core/internal/transfer"
)

var version = "0.1.0"

const usage = `OneTouch %s — мгновенная передача файлов по локальной сети (P2P, TLS, mDNS)

Использование:
  onetouch serve  [--name N] [--out DIR] [--auto-save]   запустить узел (приём + веб для телефона)
  onetouch peers                                         найти устройства в сети
  onetouch send   <файл...> [--to ИМЯ]                   отправить файл(ы) на рабочий стол пира
  onetouch clip   <файл>    [--to ИМЯ]                   положить файл в буфер пира («копировать»)
  onetouch paste  [--out DIR] [--from ИМЯ]               вставить файл из буфера («вставить»)
  onetouch copy   <файл>                                 положить файл в свой буфер (пиры заберут)
  onetouch id                                            показать имя и отпечаток сертификата

Без mDNS (если роутер режет multicast):  --addr 192.168.1.20:47470 [--fp ОТПЕЧАТОК]
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
	case "peers":
		err = cmdPeers(args)
	case "send":
		err = cmdSend(args, "save")
	case "clip":
		err = cmdSend(args, "clip")
	case "paste":
		err = cmdPaste(args)
	case "copy":
		err = cmdCopy(args)
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

func buffer(dev *identity.Device) *transfer.Buffer {
	return &transfer.Buffer{Dir: filepath.Join(dev.Dir, "buffer")}
}

func webToken(dev *identity.Device) string {
	p := filepath.Join(dev.Dir, "web-token")
	if b, err := os.ReadFile(p); err == nil && len(strings.TrimSpace(string(b))) >= 16 {
		return strings.TrimSpace(string(b))
	}
	buf := make([]byte, 12)
	rand.Read(buf)
	t := hex.EncodeToString(buf)
	os.WriteFile(p, []byte(t), 0o600)
	return t
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	name := fs.String("name", "", "имя устройства в сети")
	port := fs.Int("port", 47470, "TLS-порт API")
	webPort := fs.Int("web-port", 47471, "HTTP-порт веб-страницы для телефона")
	out := fs.String("out", transfer.DesktopDir(), "куда сохранять файлы")
	autoSave := fs.Bool("auto-save", false, "класть «щипки» сразу на рабочий стол, минуя буфер")
	noNotify := fs.Bool("no-notify", false, "без системных уведомлений")
	noQR := fs.Bool("no-qr", false, "не печатать QR-код")
	parse(fs, args)

	dev, err := identity.Load(*name)
	if err != nil {
		return err
	}
	apiLn, err := net.Listen("tcp", ":"+strconv.Itoa(*port))
	if err != nil {
		return fmt.Errorf("порт %d занят (onetouch уже запущен?): %w", *port, err)
	}
	webLn, err := net.Listen("tcp", ":"+strconv.Itoa(*webPort))
	if err != nil {
		return fmt.Errorf("порт %d занят: %w", *webPort, err)
	}
	token := webToken(dev)
	srv := &transfer.Server{
		Dev: dev, OutDir: *out, Buffer: buffer(dev), AutoSave: *autoSave,
		Token: token, Notify: !*noNotify,
		Logf: func(f string, a ...any) { fmt.Printf(time.Now().Format("15:04:05 ")+f+"\n", a...) },
	}
	mdns, err := discovery.Announce(discovery.Announcement{
		ID: dev.ID, Name: dev.Name, OS: dev.OS, Fingerprint: dev.Fingerprint, Port: *port, WebPort: *webPort,
	})
	if err != nil {
		fmt.Println("⚠ mDNS недоступен:", err, "— используйте --addr")
	} else {
		defer mdns.Shutdown()
	}

	fmt.Printf("OneTouch %s  •  %s (%s)\n", version, dev.Name, dev.OS)
	fmt.Printf("  отпечаток: %s\n", dev.Fingerprint)
	fmt.Printf("  сохраняю в: %s\n", *out)
	fmt.Printf("  API (TLS):  :%d   mDNS: %s\n", *port, discovery.Service)
	ips := discovery.LocalIPv4()
	for i, ip := range ips {
		u := fmt.Sprintf("http://%s:%d/?t=%s", ip, *webPort, token)
		fmt.Printf("  📱 телефон:  %s\n", u)
		if i == 0 && !*noQR {
			fmt.Println("\n  Наведите камеру телефона (та же Wi‑Fi сеть):")
			qrterminal.GenerateWithConfig(u, qrterminal.Config{
				Level: qrterminal.L, Writer: os.Stdout, HalfBlocks: true,
				BlackChar: qrterminal.BLACK_BLACK, WhiteBlackChar: qrterminal.WHITE_BLACK,
				WhiteChar: qrterminal.WHITE_WHITE, BlackWhiteChar: qrterminal.BLACK_WHITE, QuietZone: 2,
			})
		}
	}
	if len(ips) == 0 {
		fmt.Println("  ⚠ нет локального IPv4 — подключитесь к Wi‑Fi")
	}
	fmt.Println("Жду файлы… (Ctrl+C — выход)")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return srv.Run(ctx, apiLn, webLn)
}

type target struct {
	addr, fp, name string
	ok             bool
}

func peerFlags(fs *flag.FlagSet) (to, addr, fp *string) {
	return fs.String("to", "", "имя (часть имени) или id пира"),
		fs.String("addr", "", "адрес пира host:port (без mDNS)"),
		fs.String("fp", "", "ожидаемый отпечаток сертификата (с --addr)")
}

// resolve finds the peer to talk to and returns a connected client.
func resolve(ctx context.Context, dev *identity.Device, to, addr, fp string) (*transfer.Client, string, error) {
	if addr != "" {
		if _, _, err := net.SplitHostPort(addr); err != nil {
			addr = net.JoinHostPort(addr, "47470")
		}
		c := transfer.NewClient(addr, fp, dev.Name)
		info, err := c.Info(ctx)
		if err != nil {
			return nil, "", err
		}
		if fp == "" {
			fmt.Fprintf(os.Stderr, "⚠ отпечаток не задан, доверяю при первом подключении: %s\n", c.SeenFP)
		}
		return c, info["name"], nil
	}
	peers, err := discovery.Browse(ctx, 2*time.Second, dev.ID)
	if err != nil {
		return nil, "", err
	}
	var match []discovery.Peer
	for _, p := range peers {
		if to == "" || strings.Contains(strings.ToLower(p.Name), strings.ToLower(to)) || strings.HasPrefix(p.ID, to) {
			match = append(match, p)
		}
	}
	switch {
	case len(match) == 0 && len(peers) == 0:
		return nil, "", errors.New("никого не найдено в сети. Запущен ли `onetouch serve` на другом устройстве? Та же Wi‑Fi сеть? Можно указать --addr")
	case len(match) == 0:
		return nil, "", fmt.Errorf("нет пира %q; найдены: %s", to, names(peers))
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
		return nil
	}
	for _, p := range peers {
		fmt.Printf("• %-20s %-8s %s:%d  id=%s  fp=%s…\n", p.Name, p.OS, strings.Join(p.Addrs, ","), p.Port, p.ID, p.Fingerprint[:min(16, len(p.Fingerprint))])
	}
	return nil
}

func cmdSend(args []string, mode string) error {
	fs := flag.NewFlagSet(mode, flag.ExitOnError)
	to, addr, fp := peerFlags(fs)
	files := parse(fs, args)
	if len(files) == 0 {
		return errors.New("укажите файл")
	}
	if mode == "clip" && len(files) > 1 {
		return errors.New("в буфер кладётся один файл")
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
		res, err := c.SendFile(ctx, f, mode, func(sent, total int64) {
			pct := 100.0
			if total > 0 {
				pct = 100 * float64(sent) / float64(total)
			}
			speed := float64(sent) / time.Since(start).Seconds()
			fmt.Fprintf(os.Stderr, "\r  %s  %5.1f%%  %s/s   ", base, pct, transfer.HumanBytes(int64(speed)))
		})
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return fmt.Errorf("%s: %w", base, err)
		}
		d := time.Since(start)
		where := "рабочий стол"
		if res.Mode == "clip" {
			where = "буфер"
		}
		fmt.Printf("✓ %s → %s (%s): %s за %s\n", base, peerName, where, transfer.HumanBytes(res.Bytes), d.Round(time.Millisecond))
	}
	return nil
}

func cmdPaste(args []string) error {
	fs := flag.NewFlagSet("paste", flag.ExitOnError)
	out := fs.String("out", transfer.DesktopDir(), "куда вставить")
	from, addr, fp := peerFlags(fs)
	fs.Lookup("to").Usage = "не используется"
	fs.StringVar(from, "from", "", "забрать из буфера конкретного пира")
	parse(fs, args)
	dev, err := identity.Load("")
	if err != nil {
		return err
	}
	if *from == "" && *addr == "" {
		if p, m, err := buffer(dev).PasteTo(*out); err == nil {
			fmt.Printf("✓ вставлено: %s (%s, от %s)\n", p, transfer.HumanBytes(m.Size), m.From)
			return nil
		}
	}
	// Local buffer empty: pull from a peer's buffer (the pull model).
	ctx := context.Background()
	if *from != "" || *addr != "" {
		c, name, err := resolve(ctx, dev, *from, *addr, *fp)
		if err != nil {
			return err
		}
		return pull(ctx, c, name, *out)
	}
	peers, _ := discovery.Browse(ctx, 2*time.Second, dev.ID)
	var best *transfer.Client
	var bestMeta transfer.ClipMeta
	var bestName string
	for _, p := range peers {
		for _, a := range p.Addrs {
			c := transfer.NewClient(net.JoinHostPort(a, strconv.Itoa(p.Port)), p.Fingerprint, dev.Name)
			cctx, cancel := context.WithTimeout(ctx, 3*time.Second)
			m, ok, err := c.ClipMeta(cctx)
			cancel()
			if err != nil {
				continue
			}
			if ok && (best == nil || m.Time.After(bestMeta.Time)) {
				best, bestMeta, bestName = c, m, p.Name
			}
			break
		}
	}
	if best == nil {
		return errors.New("буфер пуст (ни локально, ни у пиров)")
	}
	return pull(ctx, best, bestName, *out)
}

func pull(ctx context.Context, c *transfer.Client, name, out string) error {
	start := time.Now()
	p, n, err := c.PullClip(ctx, out)
	if err != nil {
		return err
	}
	fmt.Printf("✓ вставлено из буфера %s: %s (%s за %s)\n", name, p, transfer.HumanBytes(n), time.Since(start).Round(time.Millisecond))
	return nil
}

func cmdCopy(args []string) error {
	fs := flag.NewFlagSet("copy", flag.ExitOnError)
	files := parse(fs, args)
	if len(files) != 1 {
		return errors.New("укажите один файл")
	}
	dev, err := identity.Load("")
	if err != nil {
		return err
	}
	f, err := os.Open(files[0])
	if err != nil {
		return err
	}
	defer f.Close()
	m, err := buffer(dev).Put(f, filepath.Base(files[0]), dev.Name)
	if err != nil {
		return err
	}
	fmt.Printf("✓ в буфере: %s (%s) — заберите с другого устройства\n", m.Name, transfer.HumanBytes(m.Size))
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
