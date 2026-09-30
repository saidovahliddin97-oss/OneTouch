package discovery

import "testing"

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
