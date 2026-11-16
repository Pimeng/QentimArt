// Package storage 对服务端使用的 blob 存储进行抽象。
package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"
)

// ErrNotFound 在 key 不存在时返回。
var ErrNotFound = errors.New("storage: not found")

// ObjectInfo 描述一个已存储的对象。
type ObjectInfo struct {
	Key     string
	Size    int64
	ModTime time.Time
}

// Backend 是一个扁平的键值 blob 存储。key 使用 "/" 作为分隔符。
type Backend interface {
	Get(ctx context.Context, key string) (io.ReadCloser, int64, error)
	Put(ctx context.Context, key string, r io.Reader, size int64) error
	Stat(ctx context.Context, key string) (ObjectInfo, error)
	Delete(ctx context.Context, key string) error
	// List 对每个带有给定前缀的 key 调用 fn。
	List(ctx context.Context, prefix string, fn func(ObjectInfo) error) error
	// Parallelism 返回推荐的并发读取数。
	Parallelism() int
}

// Config 选择并配置后端。
type Config struct {
	Type string `json:"type"` // "local" 或 "s3"
	// 本地存储
	Path string `json:"path,omitempty"`
	// S3 存储
	S3 *S3Config `json:"s3,omitempty"`
}

// Open 根据配置创建后端。
func Open(c Config) (Backend, error) {
	switch c.Type {
	case "", "local":
		if c.Path == "" {
			c.Path = "./data"
		}
		return NewLocal(c.Path)
	case "s3":
		if c.S3 == nil {
			return nil, errors.New("storage: s3 config missing")
		}
		return NewS3(*c.S3)
	}
	return nil, fmt.Errorf("storage: unknown type %q", c.Type)
}

// ReadAll 完整读取一个对象。
func ReadAll(ctx context.Context, b Backend, key string) ([]byte, error) {
	rc, size, err := b.Get(ctx, key)
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	if size >= 0 && size < 1<<30 {
		buf := make([]byte, size)
		if _, err := io.ReadFull(rc, buf); err != nil {
			return nil, err
		}
		return buf, nil
	}
	return io.ReadAll(rc)
}
