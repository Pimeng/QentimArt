package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"path"
	"strings"
	"sync"
	"time"

	"qentimart/internal/catalog"
	"qentimart/internal/manifest"
	"qentimart/internal/storage"
	"qentimart/internal/wire"
)

// VersionInfo 是与每个 manifest 一起存储的元数据（类型定义在 catalog 包，
// 供服务端与静态发布端共用）。
type VersionInfo = catalog.VersionInfo

type project struct {
	name string

	mu           sync.RWMutex
	chunks       map[wire.Hash]struct{}
	chunksLoaded bool
	loadChunks   sync.Mutex

	vmu        sync.Mutex
	versions   map[string]*VersionInfo
	versionsAt time.Time

	// writeMu 使提交、删除和 GC 串行执行。
	writeMu sync.Mutex
}

func chunkKey(p string, h wire.Hash) string {
	s := h.String()
	return "projects/" + p + "/chunks/" + s[:2] + "/" + s
}
func versionPrefix(p string) string           { return "projects/" + p + "/versions/" }
func manifestKey(p, v string) string          { return versionPrefix(p) + v + "/manifest.json.gz" }
func metaKey(p, v string) string              { return versionPrefix(p) + v + "/meta.json" }
func (s *Server) chunkPrefix(p string) string { return "projects/" + p + "/chunks/" }

func (s *Server) getProject(name string) (*project, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.projects[name]
	if p == nil {
		if s.cfg.maxProjects > 0 && len(s.projects) >= s.cfg.maxProjects {
			return nil, &httpError{http.StatusServiceUnavailable, "server project cache is full"}
		}
		p = &project{name: name}
		s.projects[name] = p
	}
	return p, nil
}

// ensureChunks 加载已存储块的哈希集合（只加载一次）。
func (s *Server) ensureChunks(ctx context.Context, p *project) error {
	p.mu.RLock()
	ok := p.chunksLoaded
	p.mu.RUnlock()
	if ok {
		return nil
	}
	p.loadChunks.Lock()
	defer p.loadChunks.Unlock()
	p.mu.RLock()
	ok = p.chunksLoaded
	p.mu.RUnlock()
	if ok {
		return nil
	}
	set := make(map[wire.Hash]struct{})
	start := time.Now()
	err := s.store.List(ctx, s.chunkPrefix(p.name), func(o storage.ObjectInfo) error {
		if h, err := wire.ParseHash(path.Base(o.Key)); err == nil {
			set[h] = struct{}{}
		}
		return nil
	})
	if err != nil {
		return err
	}
	p.mu.Lock()
	// 合并列举期间并发新增的块。
	for h := range p.chunks {
		set[h] = struct{}{}
	}
	p.chunks = set
	p.chunksLoaded = true
	p.mu.Unlock()
	s.log.Printf("project %s: indexed %d chunks in %s", p.name, len(set), time.Since(start).Round(time.Millisecond))
	return nil
}

func (p *project) hasChunk(h wire.Hash) bool {
	p.mu.RLock()
	_, ok := p.chunks[h]
	p.mu.RUnlock()
	return ok
}

func (p *project) addChunk(h wire.Hash) {
	p.mu.Lock()
	if p.chunks == nil {
		p.chunks = make(map[wire.Hash]struct{})
	}
	p.chunks[h] = struct{}{}
	p.mu.Unlock()
}

// listVersions 返回版本映射，若缓存过期则从存储刷新。
func (s *Server) listVersions(ctx context.Context, p *project, force bool) (map[string]*VersionInfo, error) {
	p.vmu.Lock()
	defer p.vmu.Unlock()
	if !force && p.versions != nil && time.Since(p.versionsAt) < s.cfg.cacheTTL {
		return p.versions, nil
	}
	out := make(map[string]*VersionInfo)
	var keys []string
	err := s.store.List(ctx, versionPrefix(p.name), func(o storage.ObjectInfo) error {
		if strings.HasSuffix(o.Key, "/meta.json") {
			keys = append(keys, o.Key)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, k := range keys {
		// 复用已缓存的条目；元数据是不可变的。
		v := path.Base(path.Dir(k))
		if old, ok := p.versions[v]; ok {
			out[v] = old
			continue
		}
		b, err := storage.ReadAll(ctx, s.store, k)
		if errors.Is(err, storage.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		var vi VersionInfo
		if err := json.Unmarshal(b, &vi); err != nil {
			return nil, fmt.Errorf("%s: %w", k, err)
		}
		out[vi.Version] = &vi
	}
	p.versions = out
	p.versionsAt = time.Now()
	return out, nil
}

func (p *project) setVersion(vi *VersionInfo) {
	p.vmu.Lock()
	if p.versions != nil {
		p.versions[vi.Version] = vi
	}
	p.vmu.Unlock()
}

func (p *project) dropVersion(v string) {
	p.vmu.Lock()
	delete(p.versions, v)
	p.vmu.Unlock()
}

// sortedVersions 返回按降序排列的版本。
func sortedVersions(m map[string]*VersionInfo) []*VersionInfo { return catalog.Sorted(m) }

// latest 选出最高的稳定版本；若允许预发布版本或不存在稳定版本，则选出最高的预发布版本。
func latest(m map[string]*VersionInfo, allowPre bool) *VersionInfo {
	return catalog.Latest(m, allowPre)
}

// resolve 把 "latest" 或明确的版本号映射到对应的版本信息。
func (s *Server) resolve(ctx context.Context, p *project, v string, allowPre bool) (*VersionInfo, error) {
	vs, err := s.listVersions(ctx, p, false)
	if err != nil {
		return nil, err
	}
	if v == "latest" {
		if vi := latest(vs, allowPre); vi != nil {
			return vi, nil
		}
		return nil, errNotFound("project has no versions")
	}
	if vi, ok := vs[v]; ok {
		return vi, nil
	}
	// 可能是通过其他实例发布的：强制刷新一次。
	if vs, err = s.listVersions(ctx, p, true); err != nil {
		return nil, err
	}
	if vi, ok := vs[v]; ok {
		return vi, nil
	}
	return nil, errNotFound("version " + v + " not found")
}

// manifestCache 在内存中保留少量最近使用的 manifest。
type manifestCache struct {
	mu    sync.Mutex
	order []string
	items map[string]*cachedManifest
	max   int
}

type cachedManifest struct {
	gz []byte
	m  *manifest.Manifest
}

func newManifestCache(max int) *manifestCache {
	return &manifestCache{items: make(map[string]*cachedManifest), max: max}
}

func (c *manifestCache) get(k string) *cachedManifest {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.items[k]
}

func (c *manifestCache) put(k string, v *cachedManifest) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.items[k]; !ok {
		c.order = append(c.order, k)
	}
	c.items[k] = v
	for len(c.order) > c.max {
		delete(c.items, c.order[0])
		c.order = c.order[1:]
	}
}

func (c *manifestCache) drop(k string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, k)
	for i, o := range c.order {
		if o == k {
			c.order = append(c.order[:i], c.order[i+1:]...)
			break
		}
	}
}

// loadManifest 返回原始 gz 字节以及（可选的）解析后的 manifest。
func (s *Server) loadManifest(ctx context.Context, p *project, v string, parse bool) (*cachedManifest, error) {
	k := p.name + "@" + v
	if c := s.mcache.get(k); c != nil && (!parse || c.m != nil) {
		return c, nil
	}
	gz, err := storage.ReadAll(ctx, s.store, manifestKey(p.name, v))
	if errors.Is(err, storage.ErrNotFound) {
		return nil, errNotFound("manifest for " + v + " not found")
	}
	if err != nil {
		return nil, err
	}
	c := &cachedManifest{gz: gz}
	if parse {
		if c.m, err = manifest.Decode(gz, maxManifestBytes); err != nil {
			return nil, err
		}
	}
	s.mcache.put(k, c)
	return c, nil
}
