package server_test

import (
	"bytes"
	"context"
	"io"
	"log"
	"math/rand"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"qentimart/internal/manifest"
	"qentimart/internal/publish"
	"qentimart/internal/server"
	"qentimart/internal/storage"
	"qentimart/internal/wire"
)

const pubTok = "publish-token-0123456789"
const readTok = "read-token-0123456789ab"
const adminTok = "admin-token-0123456789a"

func setup(t *testing.T) (*httptest.Server, string) {
	dataDir := t.TempDir()
	store, err := storage.NewLocal(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	cfg := server.Config{Tokens: []server.TokenConfig{
		{Token: pubTok, Role: "publish", Projects: []string{"*"}},
		{Token: readTok, Role: "read", Projects: []string{"game"}},
		{Token: adminTok, Role: "admin", Projects: []string{"*"}},
	}, GCGrace: "0s"}
	srv, err := server.New(cfg, store, log.New(io.Discard, "", 0))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, dataDir
}

func writeFile(t *testing.T, p string, data []byte) {
	os.MkdirAll(filepath.Dir(p), 0o755)
	if err := os.WriteFile(p, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func dirSize(dir string) int64 {
	var n int64
	filepath.Walk(dir, func(_ string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() {
			n += info.Size()
		}
		return nil
	})
	return n
}

func TestPublishDedupFetchGC(t *testing.T) {
	ts, dataDir := setup(t)
	ctx := context.Background()
	src := t.TempDir()
	rng := rand.New(rand.NewSource(7))
	big := make([]byte, 24<<20)
	rng.Read(big)
	writeFile(t, filepath.Join(src, "big.pak"), big)
	for i := 0; i < 50; i++ {
		b := make([]byte, 1000+rng.Intn(50000))
		rng.Read(b)
		writeFile(t, filepath.Join(src, "tex", string(rune('a'+i%26))+string(rune('0'+i/26))+".png"), b)
	}
	writeFile(t, filepath.Join(src, "cfg", "text.json"), bytes.Repeat([]byte(`{"k":"value"},`), 20000))
	writeFile(t, filepath.Join(src, "empty.txt"), nil)

	pc := publish.NewClient(ts.URL, "game", pubTok, 4)
	if _, err := publish.Run(ctx, pc, publish.Options{Dir: src, Version: "1.0.0"}); err != nil {
		t.Fatal(err)
	}
	size1 := dirSize(dataDir)

	// v1.1.0：在大文件中间插入字节，并修改一张纹理。
	mod := append(append(append([]byte{}, big[:10<<20]...), []byte("PATCHED")...), big[10<<20:]...)
	writeFile(t, filepath.Join(src, "big.pak"), mod)
	writeFile(t, filepath.Join(src, "tex", "a0.png"), []byte("new texture"))
	res, err := publish.Run(ctx, pc, publish.Options{Dir: src, Version: "1.1.0"})
	if err != nil {
		t.Fatal(err)
	}
	size2 := dirSize(dataDir)
	growth := size2 - size1
	if growth > 6<<20 {
		t.Fatalf("server grew by %d bytes for a tiny change (expected only changed chunks)", growth)
	}
	t.Logf("v1.1.0 uploaded %d new chunks, server grew %d bytes", res.NewChunks, growth)

	// 版本是不可变的。
	if _, err := publish.Run(ctx, pc, publish.Options{Dir: src, Version: "1.1.0"}); err == nil {
		t.Fatal("expected conflict on republish")
	}

	// 只读令牌可以拉取 manifest 和块；只读令牌不能发布。
	rc := publish.NewClient(ts.URL, "game", readTok, 2)
	resp, err := rc.Do(ctx, "GET", "/versions/latest/manifest", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	gz, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.Header.Get("X-QentimArt-Version") != "1.1.0" {
		t.Fatal("latest should be 1.1.0")
	}
	m, err := manifest.Decode(gz, 1<<30)
	if err != nil {
		t.Fatal(err)
	}
	var body []byte
	want := map[wire.Hash]bool{}
	for _, f := range m.Files {
		for _, c := range f.Chunks {
			if !want[c.Hash] {
				want[c.Hash] = true
				body = append(body, c.Hash[:]...)
			}
		}
	}
	resp, err = rc.Do(ctx, "POST", "/chunks/fetch", bytes.NewReader(body), "")
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for {
		fh, err := wire.ReadFrameHeader(resp.Body)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if fh.Enc == wire.EncMissing {
			t.Fatalf("chunk %s missing", fh.Hash)
		}
		pl := make([]byte, fh.StoredLen)
		io.ReadFull(resp.Body, pl)
		if _, err := wire.Decode(fh.Enc, fh.RawLen, pl, fh.Hash); err != nil {
			t.Fatal(err)
		}
		n++
	}
	resp.Body.Close()
	if n != len(want) {
		t.Fatalf("got %d frames, want %d", n, len(want))
	}
	if _, err := rc.Do(ctx, "POST", "/chunks/missing", bytes.NewReader(body[:32]), ""); err == nil {
		t.Fatal("read token must not access publish endpoints")
	}
	if _, err := publish.NewClient(ts.URL, "other", readTok, 1).Do(ctx, "GET", "/versions", nil, ""); err == nil {
		t.Fatal("read token is scoped to project game")
	}

	// 删除 1.0.0 并执行 GC：仅被 1.0.0 使用的块必须被删除。
	ac := publish.NewClient(ts.URL, "game", adminTok, 1)
	if err := ac.JSON(ctx, "DELETE", "/versions/1.0.0", nil, nil); err != nil {
		t.Fatal(err)
	}
	var gc struct {
		DeletedChunks int `json:"deletedChunks"`
	}
	if err := ac.JSON(ctx, "POST", "/gc", nil, &gc); err != nil {
		t.Fatal(err)
	}
	if gc.DeletedChunks == 0 {
		t.Fatal("gc should delete chunks only referenced by 1.0.0")
	}
	// 1.1.0 必须仍然可以完整拉取。
	resp, err = rc.Do(ctx, "POST", "/chunks/fetch", bytes.NewReader(body), "")
	if err != nil {
		t.Fatal(err)
	}
	for {
		fh, err := wire.ReadFrameHeader(resp.Body)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if fh.Enc == wire.EncMissing {
			t.Fatal("gc removed a live chunk")
		}
		io.CopyN(io.Discard, resp.Body, int64(fh.StoredLen))
	}
	resp.Body.Close()
}
