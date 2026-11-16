// Package publish 扫描资源目录，使用 FastCDC 对其分块，只上传服务器尚未拥有的块，
// 然后提交一个新版本。
package publish

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"qentimart/internal/cdc"
	"qentimart/internal/manifest"
	"qentimart/internal/semver"
	"qentimart/internal/wire"
)

// Options 配置一次发布。
type Options struct {
	Dir         string
	Version     string
	Concurrency int        // 并行上传流数量
	Compress    string     // "auto"（默认）或 "none"
	Params      cdc.Params // 分块参数
	Excludes    []string   // 与相对路径及文件名匹配的 glob 模式
	DryRun      bool
	Logf        func(format string, args ...any)
}

// Result 汇总一次发布的结果。
type Result struct {
	Version       string
	Files         int
	TotalBytes    int64
	Chunks        int
	NewChunks     int
	UploadedRaw   int64
	UploadedWire  int64
	ScanDuration  time.Duration
	TotalDuration time.Duration
}

type fileJob struct {
	rel  string
	abs  string
	size int64
}

type chunkLoc struct {
	file   int
	offset int64
	size   int64
}

// skipCompress 列出已经是压缩格式的文件扩展名。
var skipCompress = map[string]bool{
	".png": true, ".jpg": true, ".jpeg": true, ".webp": true, ".gif": true, ".ktx2": true, ".basis": true,
	".mp3": true, ".ogg": true, ".opus": true, ".aac": true, ".m4a": true, ".mp4": true, ".webm": true,
	".zip": true, ".gz": true, ".7z": true, ".rar": true, ".zst": true, ".br": true, ".xz": true, ".bank": true,
	".woff": true, ".woff2": true, ".pak": false,
}

func (o *Options) logf(format string, args ...any) {
	if o.Logf != nil {
		o.Logf(format, args...)
	}
}

func excluded(rel string, patterns []string) bool {
	base := path.Base(rel)
	for _, p := range patterns {
		if ok, _ := path.Match(p, rel); ok {
			return true
		}
		if ok, _ := path.Match(p, base); ok {
			return true
		}
		if strings.HasSuffix(p, "/") && (strings.HasPrefix(rel, p) || strings.Contains(rel, "/"+p)) {
			return true
		}
	}
	return false
}

func scan(dir string, excludes []string) ([]fileJob, error) {
	var out []fileJob
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		rel = filepath.ToSlash(rel)
		if rel == "." {
			return nil
		}
		if d.IsDir() {
			if d.Name() == manifest.StateDir || d.Name() == ".git" || excluded(rel+"/", excludes) || excluded(rel, excludes) {
				return filepath.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() || excluded(rel, excludes) {
			return nil
		}
		if err := manifest.ValidatePath(rel); err != nil {
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		out = append(out, fileJob{rel: rel, abs: p, size: info.Size()})
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].rel < out[j].rel })
	return out, err
}

func (opts *Options) normalize() error {
	if _, err := semver.Parse(opts.Version); err != nil {
		return err
	}
	if opts.Params == (cdc.Params{}) {
		opts.Params = cdc.Default
	}
	if err := opts.Params.Validate(); err != nil {
		return err
	}
	if opts.Concurrency <= 0 {
		opts.Concurrency = 8
	}
	if opts.Compress == "" {
		opts.Compress = "auto"
	}
	return nil
}

// chunkResult 是分块阶段的产物，HTTP 与静态两条发布路径共用。
type chunkResult struct {
	files   []fileJob
	locs    map[wire.Hash]chunkLoc
	total   int64
	m       *manifest.Manifest
	scanDur time.Duration
}

// chunkTree 扫描 opts.Dir，并行做 FastCDC 分块 + SHA-256，构建 manifest。
func chunkTree(opts Options) (*chunkResult, error) {
	start := time.Now()
	files, err := scan(opts.Dir, opts.Excludes)
	if err != nil {
		return nil, fmt.Errorf("scan: %w", err)
	}
	var total int64
	for _, f := range files {
		total += f.size
	}
	opts.logf("scanned %d files (%s), chunking with %d workers...", len(files), human(total), runtime.NumCPU())

	entries := make([]manifest.File, len(files))
	locs := make(map[wire.Hash]chunkLoc)
	var locMu sync.Mutex
	var done int64
	bufPool := sync.Pool{New: func() any { return make([]byte, 2*opts.Params.Max) }}
	jobs := make(chan int)
	errc := make(chan error, 1)
	var wg sync.WaitGroup
	for w := 0; w < runtime.NumCPU(); w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				buf := bufPool.Get().([]byte)
				e, err := chunkFile(files[i], opts.Params, buf)
				bufPool.Put(buf)
				if err != nil {
					select {
					case errc <- err:
					default:
					}
					continue
				}
				entries[i] = e
				var off int64
				locMu.Lock()
				for _, ch := range e.Chunks {
					if _, ok := locs[ch.Hash]; !ok {
						locs[ch.Hash] = chunkLoc{file: i, offset: off, size: ch.Size}
					}
					off += ch.Size
				}
				locMu.Unlock()
				atomic.AddInt64(&done, files[i].size)
			}
		}()
	}
	stopProgress := progress(&done, total, "chunking", opts.logf)
	for i := range files {
		select {
		case err := <-errc:
			close(jobs)
			wg.Wait()
			stopProgress()
			return nil, err
		case jobs <- i:
		}
	}
	close(jobs)
	wg.Wait()
	stopProgress()
	select {
	case err := <-errc:
		return nil, err
	default:
	}
	dur := time.Since(start)
	opts.logf("chunked in %s: %d unique chunks", dur.Round(time.Millisecond), len(locs))
	m := &manifest.Manifest{Version: opts.Version, CreatedAt: time.Now(), Files: entries}
	if err := m.Validate(); err != nil {
		return nil, err
	}
	return &chunkResult{files: files, locs: locs, total: total, m: m, scanDur: dur}, nil
}

// Run 把 opts.Dir 通过 HTTP 发布到动态服务端。
func Run(ctx context.Context, c *Client, opts Options) (*Result, error) {
	start := time.Now()
	if err := opts.normalize(); err != nil {
		return nil, err
	}
	ck, err := chunkTree(opts)
	if err != nil {
		return nil, err
	}
	files, locs, total, m := ck.files, ck.locs, ck.total, ck.m
	res := &Result{Version: opts.Version, Files: len(files), TotalBytes: total, Chunks: len(locs), ScanDuration: ck.scanDur}
	// ---- 查找缺失的块 ----
	all := make([]wire.Hash, 0, len(locs))
	for h := range locs {
		all = append(all, h)
	}
	missing, err := findMissing(ctx, c, all)
	if err != nil {
		return nil, fmt.Errorf("missing check: %w", err)
	}
	var missingBytes int64
	for _, h := range missing {
		missingBytes += locs[h].size
	}
	res.NewChunks = len(missing)
	opts.logf("server is missing %d/%d chunks (%s of %s)", len(missing), len(all), human(missingBytes), human(total))
	if opts.DryRun {
		res.TotalDuration = time.Since(start)
		return res, nil
	}

	// ---- 上传 ----
	if len(missing) > 0 {
		upStart := time.Now()
		wireBytes, err := upload(ctx, c, files, locs, missing, missingBytes, opts)
		if err != nil {
			return nil, fmt.Errorf("upload: %w", err)
		}
		res.UploadedRaw, res.UploadedWire = missingBytes, wireBytes
		secs := time.Since(upStart).Seconds()
		opts.logf("uploaded %s (%s on the wire) in %.1fs, %s/s", human(missingBytes), human(wireBytes), secs, human(int64(float64(missingBytes)/secs)))
	}

	// ---- 提交 ----
	gz, err := manifest.Encode(m)
	if err != nil {
		return nil, err
	}
	var vi map[string]any
	if err := c.JSON(ctx, "PUT", "/versions/"+opts.Version, gz, &vi); err != nil {
		return nil, fmt.Errorf("commit: %w", err)
	}
	res.TotalDuration = time.Since(start)
	opts.logf("committed version %s (manifest %s)", opts.Version, human(int64(len(gz))))
	return res, nil
}

func chunkFile(f fileJob, p cdc.Params, buf []byte) (manifest.File, error) {
	e := manifest.File{Path: f.rel}
	fh, err := os.Open(f.abs)
	if err != nil {
		return e, err
	}
	defer fh.Close()
	whole := sha256.New()
	ch := cdc.NewChunker(fh, p, buf)
	for {
		b, err := ch.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return e, fmt.Errorf("%s: %w", f.rel, err)
		}
		whole.Write(b)
		e.Chunks = append(e.Chunks, manifest.ChunkRef{Hash: wire.Sum(b), Size: int64(len(b))})
		e.Size += int64(len(b))
	}
	copy(e.Hash[:], whole.Sum(nil))
	if e.Size != f.size {
		return e, fmt.Errorf("%s changed while publishing", f.rel)
	}
	return e, nil
}

func findMissing(ctx context.Context, c *Client, all []wire.Hash) ([]wire.Hash, error) {
	var out []wire.Hash
	const batch = 32768
	for i := 0; i < len(all); i += batch {
		j := min(i+batch, len(all))
		body := make([]byte, 0, (j-i)*wire.HashLen)
		for _, h := range all[i:j] {
			body = append(body, h[:]...)
		}
		resp, err := c.Do(ctx, "POST", "/chunks/missing", bytes.NewReader(body), "application/octet-stream")
		if err != nil {
			return nil, err
		}
		b, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			return nil, err
		}
		hs, err := wire.ParseHashList(b)
		if err != nil {
			return nil, err
		}
		out = append(out, hs...)
	}
	return out, nil
}

// upload 通过 Concurrency 个并行请求，分批流式上传缺失的块。
func upload(ctx context.Context, c *Client, files []fileJob, locs map[wire.Hash]chunkLoc, missing []wire.Hash, totalBytes int64, opts Options) (int64, error) {
	// 按文件/偏移排序，使读取保持顺序。
	sort.Slice(missing, func(i, j int) bool {
		a, b := locs[missing[i]], locs[missing[j]]
		if a.file != b.file {
			return a.file < b.file
		}
		return a.offset < b.offset
	})
	const batchBytes = 32 << 20
	const batchCount = 4096
	var batches [][]wire.Hash
	var cur []wire.Hash
	var curBytes int64
	for _, h := range missing {
		cur = append(cur, h)
		curBytes += locs[h].size
		if curBytes >= batchBytes || len(cur) >= batchCount {
			batches = append(batches, cur)
			cur, curBytes = nil, 0
		}
	}
	if len(cur) > 0 {
		batches = append(batches, cur)
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var done, wireBytes int64
	stop := progress(&done, totalBytes, "uploading", opts.logf)
	defer stop()

	work := make(chan []wire.Hash)
	var firstErr error
	var errOnce sync.Once
	var wg sync.WaitGroup
	for w := 0; w < opts.Concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for b := range work {
				var err error
				for attempt := 0; attempt < 3; attempt++ {
					if err = uploadBatch(ctx, c, files, locs, b, opts.Compress, &done, &wireBytes); err == nil || ctx.Err() != nil {
						break
					}
					var ae *APIError
					if errors.As(err, &ae) && ae.Status < 500 {
						break
					}
					opts.logf("upload batch failed (attempt %d): %v", attempt+1, err)
					time.Sleep(time.Duration(attempt+1) * time.Second)
				}
				if err != nil {
					errOnce.Do(func() { firstErr = err; cancel() })
				}
			}
		}()
	}
	for _, b := range batches {
		select {
		case work <- b:
		case <-ctx.Done():
		}
	}
	close(work)
	wg.Wait()
	return wireBytes, firstErr
}

func uploadBatch(ctx context.Context, c *Client, files []fileJob, locs map[wire.Hash]chunkLoc, batch []wire.Hash, compress string, done, wireBytes *int64) error {
	pr, pw := io.Pipe()
	var sent int64
	go func() {
		var open *os.File
		openIdx := -1
		defer func() {
			if open != nil {
				open.Close()
			}
		}()
		var hdr [wire.FrameHeaderLen]byte
		buf := make([]byte, wire.MaxChunkSize)
		for _, h := range batch {
			l := locs[h]
			if l.file != openIdx {
				if open != nil {
					open.Close()
				}
				f, err := os.Open(files[l.file].abs)
				if err != nil {
					pw.CloseWithError(err)
					return
				}
				open, openIdx = f, l.file
			}
			raw := buf[:l.size]
			if _, err := open.ReadAt(raw, l.offset); err != nil {
				pw.CloseWithError(fmt.Errorf("%s: %w", files[l.file].rel, err))
				return
			}
			if wire.Sum(raw) != h {
				pw.CloseWithError(fmt.Errorf("%s changed while publishing", files[l.file].rel))
				return
			}
			enc, payload := wire.EncRaw, raw
			if compress == "auto" && len(raw) >= 1024 && !skipCompress[strings.ToLower(path.Ext(files[l.file].rel))] {
				if z := wire.Deflate(raw); len(z) < len(raw)*9/10 {
					enc, payload = wire.EncDeflate, z
				}
			}
			wire.FrameHeader{Hash: h, Enc: enc, RawLen: uint32(len(raw)), StoredLen: uint32(len(payload))}.Put(hdr[:])
			if _, err := pw.Write(hdr[:]); err != nil {
				return
			}
			if _, err := pw.Write(payload); err != nil {
				return
			}
			sent += int64(len(payload))
			atomic.AddInt64(done, l.size)
		}
		pw.Close()
	}()
	resp, err := c.Do(ctx, "POST", "/chunks", pr, "application/x-qentimart-frames")
	pr.CloseWithError(errors.New("request finished"))
	if err != nil {
		return err
	}
	io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	atomic.AddInt64(wireBytes, sent)
	return nil
}

func progress(done *int64, total int64, label string, logf func(string, ...any)) func() {
	if logf == nil || total == 0 {
		return func() {}
	}
	stop := make(chan struct{})
	var once sync.Once
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		start := time.Now()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				d := atomic.LoadInt64(done)
				rate := float64(d) / time.Since(start).Seconds()
				logf("  %s %5.1f%%  %s / %s  %s/s", label, float64(d)*100/float64(total), human(d), human(total), human(int64(rate)))
			}
		}
	}()
	return func() { once.Do(func() { close(stop) }) }
}

func human(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%dB", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f%ciB", float64(n)/float64(div), "KMGTPE"[exp])
}
