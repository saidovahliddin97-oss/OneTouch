package discovery

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func TestUnescape(t *testing.T) {
	cases := map[string]string{
		`name=\208\156\208\190\208\185 Mac`: "name=Мой Mac",
		`name=a\.b\\c`:                      `name=a.b\c`,
		"plain":                             "plain",
	}
	for in, want := range cases {
		if got := unescape(in); got != want {
			t.Errorf("unescape(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestInstanceName(t *testing.T) {
	long := "sat12-bq152-1982f183-b246-4817-97bc-d7c035d473b6-E28C118083F3"
	if got := InstanceName(long, "abcdef0123"); len(got) > 63 || !strings.HasSuffix(got, "-abcdef") {
		t.Errorf("long name: %q (%d bytes)", got, len(got))
	}
	cyr := strings.Repeat("Ахлиддин ", 8) // 136 bytes of 2-byte runes
	got := InstanceName(cyr, "abcdef")
	if len(got) > 63 || !utf8.ValidString(got) {
		t.Errorf("cyrillic name: %q (%d bytes, valid=%v)", got, len(got), utf8.ValidString(got))
	}
	if got := InstanceName("Mr.Mac", "abcdef"); got != "Mr-Mac-abcdef" {
		t.Errorf("dots: %q", got)
	}
}
