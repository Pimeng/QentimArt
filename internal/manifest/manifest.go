// Package manifest 定义了每个版本的文件清单。
//
// manifest 是一个经 gzip 压缩的 JSON 文档：
//
//	{
//	  "formatVersion": 1,
//	  "version": "1.2.3",
//	  "createdAt": "2026-11-16T10:00:00Z",
//	  "files": [
//	    {"path": "tex/a.png", "size": 1234, "hash": "<sha256 hex>"},
//	    {"path": "big.pak", "size": 9000000, "hash": "<sha256 hex>",
//	     "chunks": [["<sha256 hex>", 4194304], ["<sha256 hex>", 4805696]]}
//	  ]
//	}
//
// 当文件恰好只由一个块组成（此时块哈希等于文件哈希）或文件为空时，省略 "chunks"。
// 文件按 path 排序。
package manifest

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	"assetsync/internal/wire"
)

const FormatVersion = 1

// StateDir 是为每个客户端保留的状态目录名。
const StateDir = ".assetsync"

// ChunkRef 引用文件中的一个块。
type ChunkRef struct {
	Hash wire.Hash
	Size int64
}

func (c ChunkRef) MarshalJSON() ([]byte, error) {
	return []byte(fmt.Sprintf("[%q,%d]", c.Hash.String(), c.Size)), nil
}

func (c *ChunkRef) UnmarshalJSON(b []byte) error {
	var arr []json.RawMessage
	if err := json.Unmarshal(b, &arr); err != nil || len(arr) != 2 {
		return errors.New("chunk ref must be [hash, size]")
	}
	var hs string
	if err := json.Unmarshal(arr[0], &hs); err != nil {
		return err
	}
	h, err := wire.ParseHash(hs)
	if err != nil {
		return err
	}
	c.Hash = h
	return json.Unmarshal(arr[1], &c.Size)
}

// File 是一条文件记录。在内存中 Chunks 总是完整展开的。
type File struct {
	Path   string
	Size   int64
	Hash   wire.Hash
	Chunks []ChunkRef
}

type jsonFile struct {
	Path   string     `json:"path"`
	Size   int64      `json:"size"`
	Hash   string     `json:"hash"`
	Chunks []ChunkRef `json:"chunks,omitempty"`
}

// Manifest 描述一个版本。
type Manifest struct {
	Version   string
	CreatedAt time.Time
	Files     []File
}

type jsonManifest struct {
	FormatVersion int        `json:"formatVersion"`
	Version       string     `json:"version"`
	CreatedAt     time.Time  `json:"createdAt"`
	Files         []jsonFile `json:"files"`
}

// TotalSize 返回所有文件大小之和。
func (m *Manifest) TotalSize() int64 {
	var n int64
	for _, f := range m.Files {
		n += f.Size
	}
	return n
}

// UniqueChunks 返回去重后的块集合及其大小。
func (m *Manifest) UniqueChunks() map[wire.Hash]int64 {
	out := make(map[wire.Hash]int64)
	for _, f := range m.Files {
		for _, c := range f.Chunks {
			out[c.Hash] = c.Size
		}
	}
	return out
}

// Encode 序列化 manifest 并进行 gzip 压缩（文件按 path 排序）。
func Encode(m *Manifest) ([]byte, error) {
	sort.Slice(m.Files, func(i, j int) bool { return m.Files[i].Path < m.Files[j].Path })
	jm := jsonManifest{FormatVersion: FormatVersion, Version: m.Version, CreatedAt: m.CreatedAt.UTC(), Files: make([]jsonFile, len(m.Files))}
	for i, f := range m.Files {
		jf := jsonFile{Path: f.Path, Size: f.Size, Hash: f.Hash.String()}
		implied := len(f.Chunks) == 0 || (len(f.Chunks) == 1 && f.Chunks[0].Hash == f.Hash && f.Chunks[0].Size == f.Size)
		if !implied {
			jf.Chunks = f.Chunks
		}
		jm.Files[i] = jf
	}
	var buf bytes.Buffer
	zw, _ := gzip.NewWriterLevel(&buf, gzip.BestSpeed)
	enc := json.NewEncoder(zw)
	if err := enc.Encode(&jm); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// Decode 解析并校验经 gzip 压缩的 manifest。maxBytes 限制解压后的大小。
func Decode(gz []byte, maxBytes int64) (*Manifest, error) {
	zr, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		return nil, fmt.Errorf("manifest: %w", err)
	}
	dec := json.NewDecoder(io.LimitReader(zr, maxBytes))
	var jm jsonManifest
	if err := dec.Decode(&jm); err != nil {
		return nil, fmt.Errorf("manifest: %w", err)
	}
	if jm.FormatVersion != FormatVersion {
		return nil, fmt.Errorf("manifest: unsupported formatVersion %d", jm.FormatVersion)
	}
	m := &Manifest{Version: jm.Version, CreatedAt: jm.CreatedAt, Files: make([]File, len(jm.Files))}
	for i, jf := range jm.Files {
		h, err := wire.ParseHash(jf.Hash)
		if err != nil {
			return nil, fmt.Errorf("manifest: %s: %w", jf.Path, err)
		}
		f := File{Path: jf.Path, Size: jf.Size, Hash: h, Chunks: jf.Chunks}
		if len(f.Chunks) == 0 && f.Size > 0 {
			f.Chunks = []ChunkRef{{Hash: h, Size: f.Size}}
		}
		m.Files[i] = f
	}
	if err := m.Validate(); err != nil {
		return nil, err
	}
	return m, nil
}

// Validate 检查路径、排序以及块的一致性。
func (m *Manifest) Validate() error {
	seen := make(map[string]string, len(m.Files))
	dirs := make(map[string]bool)
	for i, f := range m.Files {
		if err := ValidatePath(f.Path); err != nil {
			return err
		}
		if i > 0 && m.Files[i-1].Path >= f.Path {
			return fmt.Errorf("manifest: files not sorted/unique at %q", f.Path)
		}
		low := strings.ToLower(f.Path)
		if other, ok := seen[low]; ok {
			return fmt.Errorf("manifest: %q and %q differ only in case (breaks Windows/macOS clients)", other, f.Path)
		}
		seen[low] = f.Path
		for j := strings.IndexByte(low, '/'); j >= 0; j = nextSlash(low, j) {
			dirs[low[:j]] = true
		}
		if f.Size < 0 {
			return fmt.Errorf("manifest: %q negative size", f.Path)
		}
		var sum int64
		for _, c := range f.Chunks {
			if c.Size <= 0 || c.Size > wire.MaxChunkSize {
				return fmt.Errorf("manifest: %q has invalid chunk size %d", f.Path, c.Size)
			}
			sum += c.Size
		}
		if sum != f.Size {
			return fmt.Errorf("manifest: %q chunk sizes (%d) != file size (%d)", f.Path, sum, f.Size)
		}
	}
	for low, p := range seen {
		if dirs[low] {
			return fmt.Errorf("manifest: %q is both a file and a directory", p)
		}
	}
	return nil
}

func nextSlash(s string, j int) int {
	k := strings.IndexByte(s[j+1:], '/')
	if k < 0 {
		return -1
	}
	return j + 1 + k
}

// ValidatePath 确保路径是使用 "/" 分隔符的可移植相对路径。
func ValidatePath(p string) error {
	if p == "" || len(p) > 1024 {
		return fmt.Errorf("invalid path %q", p)
	}
	segs := strings.Split(p, "/")
	if strings.EqualFold(segs[0], StateDir) {
		return fmt.Errorf("path %q uses reserved directory %s", p, StateDir)
	}
	for _, s := range segs {
		if s == "" || s == "." || s == ".." {
			return fmt.Errorf("invalid path %q", p)
		}
		if strings.HasSuffix(s, ".") || strings.HasSuffix(s, " ") {
			return fmt.Errorf("path %q: segment ends with '.' or ' ' (not portable to Windows)", p)
		}
		for _, r := range s {
			if r < 0x20 || strings.ContainsRune(`\<>:"|?*`, r) {
				return fmt.Errorf("path %q contains a character not portable to Windows", p)
			}
		}
	}
	return nil
}

// Diff 汇总两个版本之间的变化。
type Diff struct {
	From          string   `json:"from"`
	To            string   `json:"to"`
	Added         []string `json:"added"`
	Modified      []string `json:"modified"`
	Removed       []string `json:"removed"`
	DownloadBytes int64    `json:"downloadBytes"` // To 中存在而 From 中不存在的块的原始字节数
	TotalBytes    int64    `json:"totalBytes"`    // To 的总大小
}

// Compare 计算从 a 到 b 的差异。
func Compare(a, b *Manifest) Diff {
	d := Diff{From: a.Version, To: b.Version, Added: []string{}, Modified: []string{}, Removed: []string{}, TotalBytes: b.TotalSize()}
	old := make(map[string]wire.Hash, len(a.Files))
	for _, f := range a.Files {
		old[f.Path] = f.Hash
	}
	for _, f := range b.Files {
		h, ok := old[f.Path]
		switch {
		case !ok:
			d.Added = append(d.Added, f.Path)
		case h != f.Hash:
			d.Modified = append(d.Modified, f.Path)
		}
		delete(old, f.Path)
	}
	for p := range old {
		d.Removed = append(d.Removed, p)
	}
	sort.Strings(d.Removed)
	have := a.UniqueChunks()
	for h, sz := range b.UniqueChunks() {
		if _, ok := have[h]; !ok {
			d.DownloadBytes += sz
		}
	}
	return d
}
