package publish_test

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"qentimart/internal/catalog"
	"qentimart/internal/publish"
	"qentimart/internal/storage"
	"qentimart/internal/wire"
)

// 静态 GC 的宽限期必须保护最近写入、尚未被任何版本引用的块
// （静态树无进程锁，发布与 GC 可能并发；块先落盘、index.json 后更新）。
func TestGCStaticGraceProtectsOrphanChunks(t *testing.T) {
	ctx := context.Background()
	backend, err := storage.NewLocal(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "a.bin"), bytes.Repeat([]byte("static-committed"), 2000), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := publish.RunStatic(ctx, backend, "game", publish.Options{Dir: src, Version: "1.0.0"}); err != nil {
		t.Fatal(err)
	}

	// 直接写入一个不被任何版本引用的孤块（模拟发布中断：块已落盘、index 未更新）。
	raw := []byte("orphan static chunk not referenced by any version")
	h := wire.Sum(raw)
	blob := wire.Blob(wire.EncRaw, uint32(len(raw)), raw)
	if err := backend.Put(ctx, catalog.ChunkKey(h), bytes.NewReader(blob), int64(len(blob))); err != nil {
		t.Fatal(err)
	}

	// 宽限期内：孤块被保留。
	res, err := publish.GCStatic(ctx, backend, false, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if res.DeletedChunks != 0 {
		t.Fatalf("GCStatic within grace deleted %d chunk(s); recent orphan chunks must be protected", res.DeletedChunks)
	}

	// 宽限期为 0：孤块被回收。
	res, err = publish.GCStatic(ctx, backend, false, 0)
	if err != nil {
		t.Fatal(err)
	}
	if res.DeletedChunks != 1 {
		t.Fatalf("GCStatic with zero grace should delete exactly the 1 orphan chunk, got %d", res.DeletedChunks)
	}
}
