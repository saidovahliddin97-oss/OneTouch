package transfer

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

// DesktopDir returns the user's Desktop (OneDrive-redirected on Windows too),
// falling back to the home directory.
func DesktopDir() string {
	home, _ := os.UserHomeDir()
	cands := []string{filepath.Join(home, "Desktop")}
	if runtime.GOOS == "windows" {
		if od := os.Getenv("OneDrive"); od != "" {
			cands = append([]string{filepath.Join(od, "Desktop")}, cands...)
		}
	}
	for _, c := range cands {
		if st, err := os.Stat(c); err == nil && st.IsDir() {
			return c
		}
	}
	return home
}

// SafeName strips any path components and characters that are illegal on
// Windows/macOS/Linux from a sender-supplied file name.
func SafeName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = filepath.Base(name)
	name = strings.Map(func(r rune) rune {
		if r < 32 || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.Trim(name, " .")
	if name == "" {
		name = "file-" + time.Now().Format("20060102-150405")
	}
	if len(name) > 200 {
		ext := filepath.Ext(name)
		if len(ext) > 20 {
			ext = ""
		}
		name = name[:200-len(ext)] + ext
	}
	return name
}

// UniquePath returns dir/name, or dir/"name (N).ext" if that already exists.
func UniquePath(dir, name string) string {
	p := filepath.Join(dir, name)
	if _, err := os.Lstat(p); os.IsNotExist(err) {
		return p
	}
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 1; ; i++ {
		p = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", base, i, ext))
		if _, err := os.Lstat(p); os.IsNotExist(err) {
			return p
		}
	}
}

// writeAtomic streams r into a temp file in dir and renames it to a unique
// final name, so a half-received file never appears under its real name.
func writeAtomic(dir, name string, r io.Reader) (string, int64, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", 0, err
	}
	tmp, err := os.CreateTemp(dir, ".onetouch-*.part")
	if err != nil {
		return "", 0, err
	}
	n, err := io.CopyBuffer(tmp, r, make([]byte, 1<<20))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp.Name())
		return "", n, err
	}
	final := UniquePath(dir, name)
	if err := os.Rename(tmp.Name(), final); err != nil {
		os.Remove(tmp.Name())
		return "", n, err
	}
	return final, n, nil
}

// ClipMeta describes the item in the ecosystem clipboard buffer.
type ClipMeta struct {
	Name string    `json:"name"`
	Size int64     `json:"size"`
	From string    `json:"from"`
	Time time.Time `json:"time"`
}

// Buffer is the single-slot "ecosystem clipboard": a new clip replaces the
// previous one; pasting copies it out and keeps it (like a normal clipboard).
type Buffer struct {
	Dir string
	mu  sync.Mutex
}

func (b *Buffer) itemPath() string { return filepath.Join(b.Dir, "item.bin") }
func (b *Buffer) metaPath() string { return filepath.Join(b.Dir, "meta.json") }

func (b *Buffer) Put(r io.Reader, name, from string) (ClipMeta, error) {
	if err := os.MkdirAll(b.Dir, 0o700); err != nil {
		return ClipMeta{}, err
	}
	tmp, err := os.CreateTemp(b.Dir, ".incoming-*")
	if err != nil {
		return ClipMeta{}, err
	}
	n, err := io.CopyBuffer(tmp, r, make([]byte, 1<<20))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp.Name())
		return ClipMeta{}, err
	}
	m := ClipMeta{Name: SafeName(name), Size: n, From: from, Time: time.Now()}
	b.mu.Lock()
	defer b.mu.Unlock()
	os.Remove(b.itemPath())
	if err := os.Rename(tmp.Name(), b.itemPath()); err != nil {
		os.Remove(tmp.Name())
		return ClipMeta{}, err
	}
	mb, _ := json.Marshal(m)
	return m, os.WriteFile(b.metaPath(), mb, 0o600)
}

func (b *Buffer) Get() (ClipMeta, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	mb, err := os.ReadFile(b.metaPath())
	if err != nil {
		return ClipMeta{}, false
	}
	var m ClipMeta
	if json.Unmarshal(mb, &m) != nil {
		return ClipMeta{}, false
	}
	if _, err := os.Stat(b.itemPath()); err != nil {
		return ClipMeta{}, false
	}
	return m, true
}

// Open returns a reader over the buffered item.
func (b *Buffer) Open() (*os.File, ClipMeta, error) {
	m, ok := b.Get()
	if !ok {
		return nil, m, os.ErrNotExist
	}
	f, err := os.Open(b.itemPath())
	return f, m, err
}

// PasteTo copies the buffered item into dir and returns the resulting path.
func (b *Buffer) PasteTo(dir string) (string, ClipMeta, error) {
	f, m, err := b.Open()
	if err != nil {
		return "", m, err
	}
	defer f.Close()
	p, _, err := writeAtomic(dir, m.Name, f)
	return p, m, err
}
