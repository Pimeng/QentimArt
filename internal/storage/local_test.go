package storage

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestLocalStatConfinedToRoot(t *testing.T) {
	dir := t.TempDir()
	store, err := NewLocal(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if _, err := store.Stat(t.Context(), "../other"); err == nil {
		t.Fatal("non-local object key accepted")
	}
	if err := os.Symlink(t.TempDir(), filepath.Join(dir, "linked")); err != nil {
		if runtime.GOOS == "windows" {
			t.Skipf("symlink unavailable: %v", err)
		}
		t.Fatal(err)
	}
	if _, err := store.Stat(t.Context(), "linked"); err == nil {
		t.Fatal("resolved an object outside the configured root")
	}
}
