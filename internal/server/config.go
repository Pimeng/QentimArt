package server

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"assetsync/internal/storage"
)

// 角色等级；高等级包含低等级的全部权限。
const (
	RoleRead    = 1
	RolePublish = 2
	RoleAdmin   = 3
)

// TokenConfig 授予令牌在一组项目上的某个角色（"*" 表示所有项目）。
type TokenConfig struct {
	Name     string   `json:"name,omitempty"`
	Token    string   `json:"token"`
	Role     string   `json:"role"` // read | publish | admin
	Projects []string `json:"projects"`
}

// Config 是服务端配置文件。
type Config struct {
	Listen             string         `json:"listen"`
	Storage            storage.Config `json:"storage"`
	Tokens             []TokenConfig  `json:"tokens"`
	AllowAnonymousRead bool           `json:"allowAnonymousRead"`
	// MaxFetchChunks 限制每个批量拉取请求中的哈希数量。
	MaxFetchChunks int `json:"maxFetchChunks,omitempty"`
	// GCGrace 保护刚上传（尚未提交）的块不被 GC 回收。
	GCGrace string `json:"gcGrace,omitempty"`
	// VersionCacheTTL 控制从存储重新读取版本列表的频率
	// （当多个服务端实例共享同一个 bucket 时很重要）。
	VersionCacheTTL string `json:"versionCacheTTL,omitempty"`

	gcGrace  time.Duration
	cacheTTL time.Duration
}

// LoadConfig 读取 JSON 配置文件。
func LoadConfig(path string) (Config, error) {
	var c Config
	b, err := os.ReadFile(path)
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, fmt.Errorf("config %s: %w", path, err)
	}
	return c, nil
}

func roleLevel(r string) int {
	switch r {
	case "read":
		return RoleRead
	case "publish":
		return RolePublish
	case "admin":
		return RoleAdmin
	}
	return 0
}

func (c *Config) normalize() error {
	if c.Listen == "" {
		c.Listen = ":8080"
	}
	if c.MaxFetchChunks <= 0 {
		c.MaxFetchChunks = 4096
	}
	var err error
	c.gcGrace = time.Hour
	if c.GCGrace != "" {
		if c.gcGrace, err = time.ParseDuration(c.GCGrace); err != nil {
			return fmt.Errorf("gcGrace: %w", err)
		}
	}
	c.cacheTTL = 15 * time.Second
	if c.VersionCacheTTL != "" {
		if c.cacheTTL, err = time.ParseDuration(c.VersionCacheTTL); err != nil {
			return fmt.Errorf("versionCacheTTL: %w", err)
		}
	}
	for _, t := range c.Tokens {
		if len(t.Token) < 16 {
			return fmt.Errorf("token %q: must be at least 16 characters", t.Name)
		}
		if roleLevel(t.Role) == 0 {
			return fmt.Errorf("token %q: invalid role %q", t.Name, t.Role)
		}
	}
	return nil
}
