package semver

import "testing"

func TestOrder(t *testing.T) {
	order := []string{"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.2.0", "1.10.0", "2.0.0"}
	for i := 0; i+1 < len(order); i++ {
		if CompareStrings(order[i], order[i+1]) >= 0 {
			t.Errorf("%s should be < %s", order[i], order[i+1])
		}
	}
	for _, bad := range []string{"1.0", "01.0.0", "1.0.0+build", "v1.0.0", "1.0.0-", "1.0.0-01"} {
		if Valid(bad) {
			t.Errorf("%q should be invalid", bad)
		}
	}
}
