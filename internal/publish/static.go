package publish

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"qentimart/internal/catalog"
	"qentimart/internal/manifest"
	"qentimart/internal/semver"
	"qentimart/internal/storage"
	"qentimart/internal/wire"
)

// _headers 让 Cloudflare Pages（及兼容的托管）对不可变对象永久缓存，
// 对唯一可变的 index.json 用短 TTL。
const staticHeaders = `# QentimArt 静态分发缓存策略（Cloudflare Pages _headers 格式）
/chunks/*
  Cache-Control: public, max-age=31536000, immutable
/versions/*
  Cache-Control: public, max-age=31536000, immutable
/index.json
  Cache-Control: public, max-age=30, must-revalidate
`

// RunStatic 把 opts.Dir 构建进一棵静态分发树（写入 backend），不需要运行服务端。
// backend 通常是指向输出目录的本地存储；输出目录是持久镜像，重复发布只写新块。
func RunStatic(ctx context.Context, backend storage.Backend, project string, opts Options) (*Result, error) {
	start := time.Now()
	if err := opts.normalize(); err != nil {
		return nil, err
	}
	ck, err := chunkTree(opts)
	if err != nil {
		return nil, err
	}
	res := &Result{Version: opts.Version, Files: len(ck.files), TotalBytes: ck.total, Chunks: len(ck.locs), ScanDuration: ck.scanDur}

	existing, err := readVersions(ctx, backend)
	if err != nil {
		return nil, fmt.Errorf("read existing versions: %w", err)
	}
	if _, ok := existing[opts.Version]; ok {
		return nil, fmt.Errorf("version %s already exists (versions are immutable)", opts.Version)
	}

	// ---- 查缺：对每个唯一块做 Stat ----
	all := make([]wire.Hash, 0, len(ck.locs))
	var uniqueBytes int64
	for h, l := range ck.locs {
		all = append(all, h)
		uniqueBytes += l.size
	}
	missing, err := staticMissing(ctx, backend, all)
	if err != nil {
		return nil, fmt.Errorf("missing check: %w", err)
	}
	var missingBytes int64
	for _, h := range missing {
		missingBytes += ck.locs[h].size
	}
	res.NewChunks = len(missing)
	opts.logf("store is missing %d/%d chunks (%s of %s)", len(missing), len(all), human(missingBytes), human(ck.total))
	if opts.DryRun {
		res.TotalDuration = time.Since(start)
		return res, nil
	}

	// ---- 写缺失块：顺序读源文件、并行写 blob ----
	if len(missing) > 0 {
		upStart := time.Now()
		stored, err := staticWriteChunks(ctx, backend, ck.files, ck.locs, missing, opts)
		if err != nil {
			return nil, fmt.Errorf("write chunks: %w", err)
		}
		res.UploadedRaw, res.UploadedWire = missingBytes, stored
		secs := time.Since(upStart).Seconds()
		opts.logf("wrote %s (%s stored) in %.1fs", human(missingBytes), human(stored), secs)
	}

	// ---- 写 manifest + meta，重建 index.json ----
	gz, err := manifest.Encode(ck.m)
	if err != nil {
		return nil, err
	}
	vi := &catalog.VersionInfo{
		Version: opts.Version, CreatedAt: time.Now().UTC(), Prerelease: mustPrerelease(opts.Version),
		FileCount: len(ck.files), TotalSize: ck.total, ChunkCount: len(ck.locs), ManifestSize: int64(len(gz)),
	}
	// delta：相对最接近的较低版本新增的原始字节数。
	if base := closestLower(existing, opts.Version); base != nil {
		if bm, err := readManifest(ctx, backend, base.Version); err == nil {
			vi.BaseVersion = base.Version
			vi.DeltaBytes = manifest.Compare(bm, ck.m).DownloadBytes
		} else {
			vi.DeltaBytes = uniqueBytes
		}
	} else {
		vi.DeltaBytes = uniqueBytes
	}

	if err := backend.Put(ctx, catalog.ManifestKey(opts.Version), bytes.NewReader(gz), int64(len(gz))); err != nil {
		return nil, err
	}
	meta, _ := json.MarshalIndent(vi, "", "  ")
	if err := backend.Put(ctx, catalog.MetaKey(opts.Version), bytes.NewReader(meta), int64(len(meta))); err != nil {
		return nil, err
	}
	existing[opts.Version] = vi
	if err := writeIndex(ctx, backend, project, existing); err != nil {
		return nil, err
	}
	if err := backend.Put(ctx, catalog.HeadersKey, strings.NewReader(staticHeaders), int64(len(staticHeaders))); err != nil {
		return nil, err
	}

	res.TotalDuration = time.Since(start)
	opts.logf("built version %s into static tree (manifest %s, %d total versions)", opts.Version, human(int64(len(gz))), len(existing))
	return res, nil
}

func mustPrerelease(v string) bool {
	sv, err := semver.Parse(v)
	return err == nil && sv.IsPrerelease()
}

// staticMissing 并行地查出 backend 中还不存在的块。
func staticMissing(ctx context.Context, backend storage.Backend, all []wire.Hash) ([]wire.Hash, error) {
	par := backend.Parallelism()
	if par < 1 {
		par = 1
	}
	sem := make(chan struct{}, par)
	var mu sync.Mutex
	var missing []wire.Hash
	var firstErr error
	var wg sync.WaitGroup
	for _, h := range all {
		if ctx.Err() != nil {
			break
		}
		sem <- struct{}{}
		wg.Add(1)
		go func(h wire.Hash) {
			defer func() { <-sem; wg.Done() }()
			_, err := backend.Stat(ctx, catalog.ChunkKey(h))
			mu.Lock()
			defer mu.Unlock()
			switch {
			case errors.Is(err, storage.ErrNotFound):
				missing = append(missing, h)
			case err != nil && firstErr == nil:
				firstErr = err
			}
		}(h)
	}
	wg.Wait()
	return missing, firstErr
}

type chunkWrite struct {
	key  string
	blob []byte
}

// staticWriteChunks 按 (文件, 偏移) 顺序读源数据，并行写入 backend，返回已存储字节数。
func staticWriteChunks(ctx context.Context, backend storage.Backend, files []fileJob, locs map[wire.Hash]chunkLoc, missing []wire.Hash, opts Options) (int64, error) {
	sort.Slice(missing, func(i, j int) bool {
		a, b := locs[missing[i]], locs[missing[j]]
		if a.file != b.file {
			return a.file < b.file
		}
		return a.offset < b.offset
	})
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	par := backend.Parallelism()
	if par < 4 {
		par = 4
	}
	jobs := make(chan chunkWrite, par)
	var stored int64
	var firstErr atomic.Pointer[error]
	setErr := func(err error) {
		if firstErr.CompareAndSwap(nil, &err) {
			cancel()
		}
	}
	var wg sync.WaitGroup
	for w := 0; w < par; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				if err := backend.Put(ctx, j.key, bytes.NewReader(j.blob), int64(len(j.blob))); err != nil {
					setErr(err)
					return
				}
				atomic.AddInt64(&stored, int64(len(j.blob)))
			}
		}()
	}

	// 生产者：顺序读，切换文件时复用句柄。
	produce := func() error {
		var open *os.File
		openIdx := -1
		defer func() {
			if open != nil {
				open.Close()
			}
		}()
		buf := make([]byte, wire.MaxChunkSize)
		for _, h := range missing {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			l := locs[h]
			if l.file != openIdx {
				if open != nil {
					open.Close()
				}
				f, err := os.Open(files[l.file].abs)
				if err != nil {
					return err
				}
				open, openIdx = f, l.file
			}
			raw := buf[:l.size]
			if _, err := open.ReadAt(raw, l.offset); err != nil {
				return fmt.Errorf("%s: %w", files[l.file].rel, err)
			}
			if wire.Sum(raw) != h {
				return fmt.Errorf("%s changed while publishing", files[l.file].rel)
			}
			enc, payload := wire.EncRaw, raw
			if opts.Compress == "auto" && len(raw) >= 1024 && !skipCompress[strings.ToLower(path.Ext(files[l.file].rel))] {
				if z := wire.Deflate(raw); len(z) < len(raw)*9/10 {
					enc, payload = wire.EncDeflate, z
				}
			}
			blob := wire.Blob(enc, uint32(len(raw)), payload)
			select {
			case jobs <- chunkWrite{key: catalog.ChunkKey(h), blob: blob}:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		return nil
	}
	if err := produce(); err != nil {
		setErr(err)
	}
	close(jobs)
	wg.Wait()
	if p := firstErr.Load(); p != nil {
		return stored, *p
	}
	return stored, nil
}

// readVersions 从静态树读出所有版本的元数据。
func readVersions(ctx context.Context, backend storage.Backend) (map[string]*catalog.VersionInfo, error) {
	out := make(map[string]*catalog.VersionInfo)
	var keys []string
	err := backend.List(ctx, catalog.VersionsPrefix, func(o storage.ObjectInfo) error {
		if strings.HasSuffix(o.Key, "/meta.json") {
			keys = append(keys, o.Key)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, k := range keys {
		b, err := storage.ReadAll(ctx, backend, k)
		if errors.Is(err, storage.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		var vi catalog.VersionInfo
		if err := json.Unmarshal(b, &vi); err != nil {
			return nil, fmt.Errorf("%s: %w", k, err)
		}
		out[vi.Version] = &vi
	}
	return out, nil
}

func readManifest(ctx context.Context, backend storage.Backend, v string) (*manifest.Manifest, error) {
	gz, err := storage.ReadAll(ctx, backend, catalog.ManifestKey(v))
	if err != nil {
		return nil, err
	}
	return manifest.Decode(gz, 1<<30)
}

// closestLower 返回按 SemVer 严格小于 v 的最高版本。
func closestLower(m map[string]*catalog.VersionInfo, v string) *catalog.VersionInfo {
	for _, o := range catalog.Sorted(m) {
		if semver.CompareStrings(o.Version, v) < 0 {
			return o
		}
	}
	return nil
}

func writeIndex(ctx context.Context, backend storage.Backend, project string, m map[string]*catalog.VersionInfo) error {
	idx := catalog.BuildIndex(project, m)
	b, err := json.MarshalIndent(idx, "", "  ")
	if err != nil {
		return err
	}
	return backend.Put(ctx, catalog.IndexKey, bytes.NewReader(b), int64(len(b)))
}

// GCResult 汇总一次静态 GC。
type GCResult struct {
	DeletedChunks int   `json:"deletedChunks"`
	FreedBytes    int64 `json:"freedBytes"`
	KeptChunks    int   `json:"keptChunks"`
	DryRun        bool  `json:"dryRun"`
}

// GCStatic 删除静态树中不再被任何版本引用的块。
func GCStatic(ctx context.Context, backend storage.Backend, dryRun bool) (*GCResult, error) {
	versions, err := readVersions(ctx, backend)
	if err != nil {
		return nil, err
	}
	ref := make(map[wire.Hash]struct{})
	for v := range versions {
		m, err := readManifest(ctx, backend, v)
		if err != nil {
			return nil, fmt.Errorf("gc: 读取 %s: %w", v, err)
		}
		for h := range m.UniqueChunks() {
			ref[h] = struct{}{}
		}
	}
	res := &GCResult{DryRun: dryRun}
	var del []storage.ObjectInfo
	err = backend.List(ctx, catalog.ChunksPrefix, func(o storage.ObjectInfo) error {
		if len(o.Key) < 64 {
			return nil
		}
		h, err := wire.ParseHash(o.Key[len(o.Key)-64:])
		if err != nil {
			return nil
		}
		if _, ok := ref[h]; ok {
			res.KeptChunks++
		} else {
			del = append(del, o)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, o := range del {
		if !dryRun {
			if err := backend.Delete(ctx, o.Key); err != nil {
				return nil, err
			}
		}
		res.DeletedChunks++
		res.FreedBytes += o.Size
	}
	return res, nil
}

// DeleteVersionStatic 删除某版本的 manifest 与 meta，并重建 index.json。
// 遗留的孤块可随后用 GCStatic 清理。
func DeleteVersionStatic(ctx context.Context, backend storage.Backend, project, v string) error {
	versions, err := readVersions(ctx, backend)
	if err != nil {
		return err
	}
	if _, ok := versions[v]; !ok {
		return fmt.Errorf("version %s not found", v)
	}
	if err := backend.Delete(ctx, catalog.MetaKey(v)); err != nil {
		return err
	}
	if err := backend.Delete(ctx, catalog.ManifestKey(v)); err != nil {
		return err
	}
	delete(versions, v)
	return writeIndex(ctx, backend, project, versions)
}
