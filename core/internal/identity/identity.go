// Package identity holds the persistent device identity: a stable ID, a
// human-readable name and a self-signed TLS certificate whose SHA-256
// fingerprint is published over mDNS and pinned by peers.
package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type Device struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	OS   string `json:"os"`

	Cert        tls.Certificate `json:"-"`
	Fingerprint string          `json:"-"`
	Dir         string          `json:"-"`
}

// Dir returns the OneTouch config directory, creating it if needed.
func Dir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		base, err = os.UserHomeDir()
		if err != nil {
			return "", err
		}
	}
	d := filepath.Join(base, "OneTouch")
	return d, os.MkdirAll(d, 0o700)
}

// Load reads the device identity from disk or creates a new one.
// nameOverride, when non-empty, replaces the stored device name.
func Load(nameOverride string) (*Device, error) {
	dir, err := Dir()
	if err != nil {
		return nil, err
	}
	d := &Device{Dir: dir, OS: runtime.GOOS}
	metaPath := filepath.Join(dir, "device.json")
	if b, err := os.ReadFile(metaPath); err == nil {
		_ = json.Unmarshal(b, d)
	}
	if d.ID == "" {
		buf := make([]byte, 8)
		_, _ = rand.Read(buf)
		d.ID = hex.EncodeToString(buf)
	}
	if nameOverride != "" {
		d.Name = nameOverride
	}
	if d.Name == "" {
		h, _ := os.Hostname()
		h = strings.TrimSuffix(h, ".local")
		if h == "" {
			h = "device"
		}
		d.Name = h
	}
	d.OS = runtime.GOOS
	b, _ := json.MarshalIndent(d, "", "  ")
	if err := os.WriteFile(metaPath, b, 0o600); err != nil {
		return nil, err
	}

	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		if err := generateCert(certPath, keyPath, d.ID); err != nil {
			return nil, fmt.Errorf("generate cert: %w", err)
		}
		if cert, err = tls.LoadX509KeyPair(certPath, keyPath); err != nil {
			return nil, err
		}
	}
	d.Cert = cert
	d.Fingerprint = Fingerprint(cert.Certificate[0])
	return d, nil
}

// Fingerprint is the lowercase hex SHA-256 of a DER certificate.
func Fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

func generateCert(certPath, keyPath, id string) error {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "onetouch-" + id},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(20, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		return err
	}
	return os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600)
}
