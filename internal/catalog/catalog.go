// Package catalog 定义版本元数据、版本索引，以及静态分发树的对象布局。
//
// 动态服务端和静态发布端共用这里的类型：服务端把它当作内存中的版本表，
// 静态发布端把它序列化成 index.json 供客户端只读拉取。
package catalog

import (
	"sort"
	"time"

	"qentimart/internal/semver"
	"qentimart/internal/wire"
)

// IndexFormatVersion 是 index.json 的格式版本。
const IndexFormatVersion = 1

const MaxJSONBytes = 64 << 20
const MaxMetaBytes = 1 << 20

// VersionInfo 是与每个 manifest 一起存储的元数据。
type VersionInfo struct {
	Version      string    `json:"version"`
	CreatedAt    time.Time `json:"createdAt"`
	Prerelease   bool      `json:"prerelease"`
	FileCount    int       `json:"fileCount"`
	TotalSize    int64     `json:"totalSize"`
	ChunkCount   int       `json:"chunkCount"`
	ManifestSize int64     `json:"manifestSize"`
	// BaseVersion 是发布时最接近的较低版本；DeltaBytes 是本版本在其基础上
	// 新增的块的原始大小。
	BaseVersion string `json:"baseVersion,omitempty"`
	DeltaBytes  int64  `json:"deltaBytes"`
}

// Index 是静态分发树的根索引（index.json），客户端据此定位版本。
type Index struct {
	FormatVersion    int            `json:"formatVersion"`
	Project          string         `json:"project,omitempty"`
	UpdatedAt        time.Time      `json:"updatedAt"`
	Latest           string         `json:"latest,omitempty"`
	LatestPrerelease string         `json:"latestPrerelease,omitempty"`
	Versions         []*VersionInfo `json:"versions"`
}

// Sorted 返回按 SemVer 降序排列的版本。
func Sorted(m map[string]*VersionInfo) []*VersionInfo {
	out := make([]*VersionInfo, 0, len(m))
	for _, v := range m {
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool { return semver.CompareStrings(out[i].Version, out[j].Version) > 0 })
	return out
}

// Latest 选出最高的稳定版本；若允许预发布版本或不存在稳定版本，则选出最高的预发布版本。
func Latest(m map[string]*VersionInfo, allowPre bool) *VersionInfo {
	sorted := Sorted(m)
	for _, v := range sorted {
		if allowPre || !v.Prerelease {
			return v
		}
	}
	if len(sorted) > 0 {
		return sorted[0]
	}
	return nil
}

// BuildIndex 用一组版本构建索引，并预先算好 latest / latestPrerelease。
func BuildIndex(project string, m map[string]*VersionInfo) Index {
	idx := Index{FormatVersion: IndexFormatVersion, Project: project, UpdatedAt: time.Now().UTC(), Versions: Sorted(m)}
	if v := Latest(m, false); v != nil {
		idx.Latest = v.Version
	}
	if v := Latest(m, true); v != nil {
		idx.LatestPrerelease = v.Version
	}
	return idx
}

// —— 静态分发树的对象键（一个输出目录即一个项目，不带 projects/<p>/ 前缀）——

// IndexKey 是根索引对象键。
const IndexKey = "index.json"

// HeadersKey 是 Cloudflare Pages 的 _headers 文件键。
const HeadersKey = "_headers"

// ManifestKey 返回某版本 manifest 的对象键。
func ManifestKey(v string) string { return "versions/" + v + "/manifest.json.gz" }

// MetaKey 返回某版本元数据的对象键。
func MetaKey(v string) string { return "versions/" + v + "/meta.json" }

// VersionsPrefix 是所有版本对象的公共前缀。
const VersionsPrefix = "versions/"

// ChunkKey 返回某个块 blob 的对象键。
func ChunkKey(h wire.Hash) string {
	s := h.String()
	return "chunks/" + s[:2] + "/" + s
}

// ChunksPrefix 是所有块对象的公共前缀。
const ChunksPrefix = "chunks/"
