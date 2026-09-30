package transfer

import "testing"

func TestSafeName(t *testing.T) {
	cases := map[string]string{
		"../../etc/passwd":   "passwd",
		`..\..\win\evil.exe`: "evil.exe",
		"a<b>:c?.jpg":        "a_b__c_.jpg",
		"Фото 2026.heic":     "Фото 2026.heic",
		"":                   "",
	}
	for in, want := range cases {
		got := SafeName(in)
		if want == "" {
			if got == "" {
				t.Errorf("SafeName(%q) is empty", in)
			}
			continue
		}
		if got != want {
			t.Errorf("SafeName(%q) = %q, want %q", in, got, want)
		}
	}
}
