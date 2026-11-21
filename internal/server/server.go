// Package server 实现了 QentimArt 的 HTTP API。
package server

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"qentimart/internal/manifest"
	"qentimart/internal/semver"
	"qentimart/internal/storage"
	"qentimart/internal/wire"
)

const (
	maxMissingRequest  = 65536
	uploadParallelBase = 4
)

var projectRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

type httpError struct {
	code int
	msg  string
}

func (e *httpError) Error() string { return e.msg }
func errNotFound(msg string) error { return &httpError{http.StatusNotFound, msg} }
func errBad(msg string) error      { return &httpError{http.StatusBadRequest, msg} }

// Server 是 HTTP API 服务器。
type Server struct {
	cfg      Config
	store    storage.Backend
	tokens   map[string]TokenConfig
	log      *log.Logger
	mu       sync.Mutex
	projects map[string]*project
	mcache   *manifestCache
}

// New 创建一个服务器。
func New(cfg Config, store storage.Backend, logger *log.Logger) (*Server, error) {
	if err := cfg.normalize(); err != nil {
		return nil, err
	}
	if logger == nil {
		logger = log.Default()
	}
	s := &Server{cfg: cfg, store: store, tokens: map[string]TokenConfig{}, log: logger, projects: map[string]*project{}, mcache: newManifestCache(8)}
	for _, t := range cfg.Tokens {
		s.tokens[tokenKey(t.Token)] = t
	}
	if len(cfg.Tokens) == 0 {
		logger.Printf("WARNING: insecureNoAuth is set - every request is allowed with admin rights")
	}
	return s, nil
}

// tokenKey 以令牌的 SHA-256 作为查表键。查表键不随明文令牌内容变化而逐字节比较，
// 因此令牌校验不会泄露可用于时序攻击的旁路信息。
func tokenKey(tok string) string {
	h := sha256.Sum256([]byte(tok))
	return string(h[:])
}

// Handler 返回根 HTTP 处理器。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	const pv = "/api/v1/projects/{project}/versions"
	const pc = "/api/v1/projects/{project}/chunks"
	mux.Handle("GET "+pv, s.auth(RoleRead, s.handleListVersions))
	mux.Handle("GET "+pv+"/{version}", s.auth(RoleRead, s.handleVersionInfo))
	mux.Handle("GET "+pv+"/{version}/manifest", s.auth(RoleRead, s.handleManifest))
	mux.Handle("GET "+pv+"/{version}/diff", s.auth(RoleRead, s.handleDiff))
	mux.Handle("PUT "+pv+"/{version}", s.auth(RolePublish, s.handleCommit))
	mux.Handle("DELETE "+pv+"/{version}", s.auth(RoleAdmin, s.handleDeleteVersion))
	mux.Handle("POST "+pc+"/fetch", s.auth(RoleRead, s.handleFetch))
	mux.Handle("GET "+pc+"/{hash}", s.auth(RoleRead, s.handleGetChunk))
	mux.Handle("POST "+pc+"/missing", s.auth(RolePublish, s.handleMissing))
	mux.Handle("POST "+pc, s.auth(RolePublish, s.handleUpload))
	mux.Handle("POST /api/v1/projects/{project}/gc", s.auth(RoleAdmin, s.handleGC))
	return s.logRequests(mux)
}

type handlerFunc func(w http.ResponseWriter, r *http.Request, p *project) error

func (s *Server) auth(role int, h handlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name := r.PathValue("project")
		if !projectRe.MatchString(name) || manifest.ValidatePath(name) != nil {
			s.writeErr(w, errBad("invalid project name"))
			return
		}
		if !s.allowed(r, name, role) {
			w.Header().Set("WWW-Authenticate", `Bearer realm="qentimart"`)
			s.writeErr(w, &httpError{http.StatusUnauthorized, "missing or insufficient token"})
			return
		}
		if !s.cfg.AllowAnonymousRead && len(s.tokens) > 0 {
			w.Header().Set("Cache-Control", "private, no-store")
		}
		p, err := s.getProject(name)
		if err != nil {
			s.writeErr(w, err)
			return
		}
		if err := h(w, r, p); err != nil {
			s.writeErr(w, err)
		}
	})
}

func (s *Server) allowed(r *http.Request, projectName string, role int) bool {
	if len(s.tokens) == 0 {
		return true
	}
	auth := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(auth) <= len(prefix) || auth[:len(prefix)] != prefix {
		return role == RoleRead && s.cfg.AllowAnonymousRead
	}
	t, ok := s.tokens[tokenKey(auth[len(prefix):])]
	if !ok || roleLevel(t.Role) < role {
		return false
	}
	for _, p := range t.Projects {
		if p == "*" || p == projectName {
			return true
		}
	}
	return false
}

func (s *Server) writeErr(w http.ResponseWriter, err error) {
	code := http.StatusInternalServerError
	msg := err.Error()
	var he *httpError
	if errors.As(err, &he) {
		code = he.code
	} else {
		// 非预期错误：详情只写日志，对外返回通用文案，避免泄露存储路径 / 后端错误等内部信息。
		s.log.Printf("internal error: %v", err)
		msg = "internal server error"
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func writeJSON(w http.ResponseWriter, v any) error {
	w.Header().Set("Content-Type", "application/json")
	return json.NewEncoder(w).Encode(v)
}

func boolQuery(r *http.Request, k string) bool {
	b, _ := strconv.ParseBool(r.URL.Query().Get(k))
	return b
}

// 仅公开可读的制品允许共享缓存跳过回源鉴权。
func (s *Server) cacheImmutable(w http.ResponseWriter) {
	if s.cfg.AllowAnonymousRead || len(s.tokens) == 0 {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
}

// ---- 版本 ----

func (s *Server) handleListVersions(w http.ResponseWriter, r *http.Request, p *project) error {
	vs, err := s.listVersions(r.Context(), p, boolQuery(r, "refresh"))
	if err != nil {
		return err
	}
	resp := map[string]any{"project": p.name, "versions": sortedVersions(vs)}
	if l := latest(vs, false); l != nil {
		resp["latest"] = l.Version
	}
	if l := latest(vs, true); l != nil {
		resp["latestPrerelease"] = l.Version
	}
	return writeJSON(w, resp)
}

func (s *Server) handleVersionInfo(w http.ResponseWriter, r *http.Request, p *project) error {
	vi, err := s.resolve(r.Context(), p, r.PathValue("version"), boolQuery(r, "prerelease"))
	if err != nil {
		return err
	}
	return writeJSON(w, vi)
}

func (s *Server) handleManifest(w http.ResponseWriter, r *http.Request, p *project) error {
	req := r.PathValue("version")
	vi, err := s.resolve(r.Context(), p, req, boolQuery(r, "prerelease"))
	if err != nil {
		return err
	}
	c, err := s.loadManifest(r.Context(), p, vi.Version, false)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "application/gzip")
	w.Header().Set("X-QentimArt-Version", vi.Version)
	w.Header().Set("Content-Length", strconv.Itoa(len(c.gz)))
	if req != "latest" {
		s.cacheImmutable(w)
	}
	_, err = w.Write(c.gz)
	return err
}

func (s *Server) handleDiff(w http.ResponseWriter, r *http.Request, p *project) error {
	ctx := r.Context()
	allowPre := boolQuery(r, "prerelease")
	to, err := s.resolve(ctx, p, r.PathValue("version"), allowPre)
	if err != nil {
		return err
	}
	fromV := r.URL.Query().Get("from")
	if fromV == "" {
		return errBad("query parameter 'from' is required")
	}
	from, err := s.resolve(ctx, p, fromV, allowPre)
	if err != nil {
		return err
	}
	a, err := s.loadManifest(ctx, p, from.Version, true)
	if err != nil {
		return err
	}
	b, err := s.loadManifest(ctx, p, to.Version, true)
	if err != nil {
		return err
	}
	return writeJSON(w, manifest.Compare(a.m, b.m))
}

func (s *Server) handleCommit(w http.ResponseWriter, r *http.Request, p *project) error {
	ctx := r.Context()
	v := r.PathValue("version")
	sv, err := semver.Parse(v)
	if err != nil {
		return errBad(err.Error())
	}
	gz, err := io.ReadAll(io.LimitReader(r.Body, manifest.MaxCompressedBytes+1))
	if err != nil {
		return err
	}
	if len(gz) > manifest.MaxCompressedBytes {
		return errBad("manifest too large")
	}
	m, err := manifest.Decode(gz, manifest.MaxRawBytes)
	if err != nil {
		return errBad(err.Error())
	}

	p.writeMu.Lock()
	defer p.writeMu.Unlock()

	vs, err := s.listVersions(ctx, p, true)
	if err != nil {
		return err
	}
	// 直接查实际存储键，大小写不敏感的文件系统也不能覆盖已有版本。
	if _, err := s.store.Stat(ctx, metaKey(p.name, v)); err == nil {
		return &httpError{http.StatusConflict, "version " + v + " already exists (versions are immutable)"}
	} else if !errors.Is(err, storage.ErrNotFound) {
		return err
	}
	if err := s.ensureChunks(ctx, p); err != nil {
		return err
	}
	chunks := m.UniqueChunks()
	var missing []string
	for h := range chunks {
		if p.hasChunk(h) {
			continue
		}
		if _, err := s.store.Stat(ctx, chunkKey(p.name, h)); err == nil {
			p.addChunk(h)
			continue
		} else if !errors.Is(err, storage.ErrNotFound) {
			return err
		}
		missing = append(missing, h.String())
		if len(missing) >= 20 {
			break
		}
	}
	if len(missing) > 0 {
		return errBad(fmt.Sprintf("manifest references chunks that were not uploaded: %v", missing))
	}

	vi := &VersionInfo{Version: v, CreatedAt: time.Now().UTC(), Prerelease: sv.IsPrerelease(), FileCount: len(m.Files), TotalSize: m.TotalSize(), ChunkCount: len(chunks)}
	// 计算相对于最接近的较低版本的增量。
	var base *VersionInfo
	for _, o := range sortedVersions(vs) {
		if semver.CompareStrings(o.Version, v) < 0 {
			base = o
			break
		}
	}
	if base != nil {
		if bm, err := s.loadManifest(ctx, p, base.Version, true); err == nil {
			have := bm.m.UniqueChunks()
			vi.BaseVersion = base.Version
			for h, sz := range chunks {
				if _, ok := have[h]; !ok {
					vi.DeltaBytes += sz
				}
			}
		}
	} else {
		for _, sz := range chunks {
			vi.DeltaBytes += sz
		}
	}

	m.Version = v
	m.CreatedAt = vi.CreatedAt
	enc, err := manifest.Encode(m)
	if err != nil {
		return err
	}
	vi.ManifestSize = int64(len(enc))
	if err := s.store.Put(ctx, manifestKey(p.name, v), bytes.NewReader(enc), int64(len(enc))); err != nil {
		return err
	}
	meta, _ := json.MarshalIndent(vi, "", "  ")
	// meta.json 最后写入：它是提交完成的标记。
	if err := s.store.Put(ctx, metaKey(p.name, v), bytes.NewReader(meta), int64(len(meta))); err != nil {
		return err
	}
	p.setVersion(vi)
	s.mcache.put(p.name+"@"+v, &cachedManifest{gz: enc, m: m})
	s.log.Printf("project %s: committed %s (%d files, %s, delta %s vs %q)", p.name, v, vi.FileCount, human(vi.TotalSize), human(vi.DeltaBytes), vi.BaseVersion)
	w.WriteHeader(http.StatusCreated)
	return writeJSON(w, vi)
}

func (s *Server) handleDeleteVersion(w http.ResponseWriter, r *http.Request, p *project) error {
	ctx := r.Context()
	v := r.PathValue("version")
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	vs, err := s.listVersions(ctx, p, true)
	if err != nil {
		return err
	}
	if _, ok := vs[v]; !ok {
		return errNotFound("version " + v + " not found")
	}
	if err := s.store.Delete(ctx, metaKey(p.name, v)); err != nil {
		return err
	}
	if err := s.store.Delete(ctx, manifestKey(p.name, v)); err != nil {
		return err
	}
	p.dropVersion(v)
	s.mcache.drop(p.name + "@" + v)
	return writeJSON(w, map[string]any{"deleted": v, "hint": "run gc to reclaim chunk storage"})
}

func (s *Server) handleGC(w http.ResponseWriter, r *http.Request, p *project) error {
	ctx := r.Context()
	dry := boolQuery(r, "dryRun")
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	vs, err := s.listVersions(ctx, p, true)
	if err != nil {
		return err
	}
	ref := make(map[wire.Hash]struct{})
	for v := range vs {
		c, err := s.loadManifest(ctx, p, v, true)
		if err != nil {
			return fmt.Errorf("gc: loading %s: %w", v, err)
		}
		for h := range c.m.UniqueChunks() {
			ref[h] = struct{}{}
		}
	}
	cutoff := time.Now().Add(-s.cfg.gcGrace)
	var deleted, kept int
	var freed int64
	var toDelete []storage.ObjectInfo
	err = s.store.List(ctx, s.chunkPrefix(p.name), func(o storage.ObjectInfo) error {
		if len(o.Key) < 64 {
			return nil
		}
		h, err := wire.ParseHash(o.Key[len(o.Key)-64:])
		if err != nil {
			return nil
		}
		if _, ok := ref[h]; ok || o.ModTime.After(cutoff) {
			kept++
			return nil
		}
		toDelete = append(toDelete, o)
		return nil
	})
	if err != nil {
		return err
	}
	for _, o := range toDelete {
		if !dry {
			if err := s.store.Delete(ctx, o.Key); err != nil {
				return err
			}
			h, _ := wire.ParseHash(o.Key[len(o.Key)-64:])
			p.mu.Lock()
			delete(p.chunks, h)
			p.mu.Unlock()
		}
		deleted++
		freed += o.Size
	}
	s.log.Printf("project %s: gc deleted %d chunks (%s), kept %d, dryRun=%v", p.name, deleted, human(freed), kept, dry)
	return writeJSON(w, map[string]any{"deletedChunks": deleted, "freedBytes": freed, "keptChunks": kept, "dryRun": dry})
}

// ---- 块 ----

func readHashBody(r *http.Request, max int) ([]wire.Hash, error) {
	body, err := io.ReadAll(io.LimitReader(r.Body, int64(max*wire.HashLen)+1))
	if err != nil {
		return nil, err
	}
	if len(body) > max*wire.HashLen {
		return nil, errBad(fmt.Sprintf("too many hashes (max %d)", max))
	}
	hs, err := wire.ParseHashList(body)
	if err != nil {
		return nil, errBad(err.Error())
	}
	return hs, nil
}

// handleFetch 以帧的形式流式返回请求的块。存储读取并行进行；帧按请求顺序写出。
func (s *Server) handleFetch(w http.ResponseWriter, r *http.Request, p *project) error {
	hashes, err := readHashBody(r, s.cfg.MaxFetchChunks)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	type result struct {
		blob []byte
		err  error
	}
	par := s.store.Parallelism()
	results := make([]chan result, len(hashes))
	for i := range results {
		results[i] = make(chan result, 1)
	}
	sem := make(chan struct{}, par)
	go func() {
		for i, h := range hashes {
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				return
			}
			go func(i int, h wire.Hash) {
				b, err := storage.ReadAll(ctx, s.store, chunkKey(p.name, h), wire.MaxBlobSize)
				results[i] <- result{b, err}
			}(i, h)
		}
	}()

	w.Header().Set("Content-Type", "application/x-qentimart-frames")
	w.Header().Set("X-QentimArt-Chunks", strconv.Itoa(len(hashes)))
	bw := bufio.NewWriterSize(w, 256<<10)
	var hdr [wire.FrameHeaderLen]byte
	for i, h := range hashes {
		var res result
		select {
		case res = <-results[i]:
		case <-ctx.Done():
			return nil
		}
		<-sem
		fh := wire.FrameHeader{Hash: h}
		var payload []byte
		switch {
		case errors.Is(res.err, storage.ErrNotFound):
			fh.Enc = wire.EncMissing
		case res.err != nil:
			s.log.Printf("fetch %s/%s: %v", p.name, h, res.err)
			panic(http.ErrAbortHandler) // 截断数据流，使客户端重试
		default:
			enc, rawLen, pl, err := wire.ParseBlob(res.blob)
			if err != nil {
				s.log.Printf("fetch %s/%s: corrupt blob: %v", p.name, h, err)
				fh.Enc = wire.EncMissing
				break
			}
			fh.Enc, fh.RawLen, fh.StoredLen, payload = enc, rawLen, uint32(len(pl)), pl
		}
		fh.Put(hdr[:])
		if _, err := bw.Write(hdr[:]); err != nil {
			return nil
		}
		if _, err := bw.Write(payload); err != nil {
			return nil
		}
	}
	bw.Flush()
	return nil
}

func (s *Server) handleGetChunk(w http.ResponseWriter, r *http.Request, p *project) error {
	h, err := wire.ParseHash(r.PathValue("hash"))
	if err != nil {
		return errBad(err.Error())
	}
	b, err := storage.ReadAll(r.Context(), s.store, chunkKey(p.name, h), wire.MaxBlobSize)
	if errors.Is(err, storage.ErrNotFound) {
		return errNotFound("chunk not found")
	}
	if err != nil {
		return err
	}
	enc, rawLen, pl, err := wire.ParseBlob(b)
	if err != nil {
		return err
	}
	raw, err := wire.Decode(enc, rawLen, pl, h)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(raw)))
	s.cacheImmutable(w)
	_, err = w.Write(raw)
	return err
}

func (s *Server) handleMissing(w http.ResponseWriter, r *http.Request, p *project) error {
	hashes, err := readHashBody(r, maxMissingRequest)
	if err != nil {
		return err
	}
	if err := s.ensureChunks(r.Context(), p); err != nil {
		return err
	}
	out := make([]byte, 0, 1024)
	for _, h := range hashes {
		if !p.hasChunk(h) {
			out = append(out, h[:]...)
		}
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	_, err = w.Write(out)
	return err
}

// handleUpload 存储一个帧流。每个块在存储前都会根据其哈希进行校验。
func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request, p *project) error {
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	if err := s.ensureChunks(ctx, p); err != nil {
		return err
	}
	br := bufio.NewReaderSize(r.Body, 1<<20)
	par := s.store.Parallelism()
	if par < uploadParallelBase {
		par = uploadParallelBase
	}
	sem := make(chan struct{}, par)
	var wg sync.WaitGroup
	var errMu sync.Mutex
	var firstErr error
	var stored, skipped, bytesIn int64
	setErr := func(err error) {
		errMu.Lock()
		if firstErr == nil {
			firstErr = err
			cancel()
		}
		errMu.Unlock()
	}
	getErr := func() error {
		errMu.Lock()
		defer errMu.Unlock()
		return firstErr
	}
	for {
		if getErr() != nil {
			break
		}
		fh, err := wire.ReadFrameHeader(br)
		if err == io.EOF {
			break
		}
		if err != nil {
			setErr(errBad(err.Error()))
			break
		}
		if fh.Enc == wire.EncMissing {
			setErr(errBad("invalid encoding in upload"))
			break
		}
		payload := make([]byte, fh.StoredLen)
		if _, err := io.ReadFull(br, payload); err != nil {
			setErr(errBad("truncated frame payload"))
			break
		}
		bytesIn += int64(len(payload))
		if p.hasChunk(fh.Hash) {
			skipped++
			continue
		}
		sem <- struct{}{}
		wg.Add(1)
		go func(fh wire.FrameHeader, payload []byte) {
			defer func() { <-sem; wg.Done() }()
			if _, err := wire.Decode(fh.Enc, fh.RawLen, payload, fh.Hash); err != nil {
				setErr(errBad(err.Error()))
				return
			}
			blob := wire.Blob(fh.Enc, fh.RawLen, payload)
			if err := s.store.Put(ctx, chunkKey(p.name, fh.Hash), bytes.NewReader(blob), int64(len(blob))); err != nil {
				setErr(err)
				return
			}
			p.addChunk(fh.Hash)
			atomic.AddInt64(&stored, 1)
		}(fh, payload)
	}
	wg.Wait()
	if e := getErr(); e != nil {
		io.Copy(io.Discard, io.LimitReader(r.Body, 64<<20))
		return e
	}
	return writeJSON(w, map[string]any{"stored": stored, "skipped": skipped, "bytes": bytesIn})
}

// ---- 日志 ----

type statusWriter struct {
	http.ResponseWriter
	status int
	n      int64
}

func (w *statusWriter) WriteHeader(c int) { w.status = c; w.ResponseWriter.WriteHeader(c) }
func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = 200
	}
	n, err := w.ResponseWriter.Write(b)
	w.n += int64(n)
	return n, err
}
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (s *Server) logRequests(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		defer func() {
			if v := recover(); v != nil {
				s.log.Printf("%s %s aborted after %s (%s)", r.Method, r.URL.EscapedPath(), time.Since(start).Round(time.Millisecond), human(sw.n))
				panic(v)
			}
			if r.URL.Path != "/healthz" {
				s.log.Printf("%s %s %d %s %s", r.Method, r.URL.EscapedPath(), sw.status, human(sw.n), time.Since(start).Round(time.Millisecond))
			}
		}()
		h.ServeHTTP(sw, r)
	})
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
