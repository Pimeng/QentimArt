package storage

import (
	"context"
	"crypto/rand"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// Local 把对象以文件形式存储在根目录下。
type Local struct {
	root *os.Root
}

// NewLocal 创建（如有必要）并打开本地后端。
func NewLocal(root string) (*Local, error) {
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, err
	}
	dir, err := os.OpenRoot(root)
	if err != nil {
		return nil, err
	}
	if err := dir.MkdirAll(".tmp", 0o700); err != nil {
		dir.Close()
		return nil, err
	}
	return &Local{root: dir}, nil
}

func (l *Local) Close() error { return l.root.Close() }

func localFile(key string) (string, error) {
	if !fs.ValidPath(key) || key == "." || key == ".tmp" || strings.HasPrefix(key, ".tmp/") || strings.Contains(key, "\\") {
		return "", errors.New("storage: invalid key")
	}
	return filepath.Localize(key)
}

func (l *Local) Parallelism() int { return 8 }

func (l *Local) Get(_ context.Context, key string) (io.ReadCloser, int64, error) {
	p, err := localFile(key)
	if err != nil {
		return nil, 0, err
	}
	f, err := l.root.Open(p)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, 0, ErrNotFound
	}
	if err != nil {
		return nil, 0, err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, 0, err
	}
	return f, st.Size(), nil
}

func (l *Local) Put(_ context.Context, key string, r io.Reader, _ int64) error {
	p, err := localFile(key)
	if err != nil {
		return err
	}
	tmp := filepath.Join(".tmp", rand.Text())
	f, err := l.root.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o666)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, r); err != nil {
		f.Close()
		l.root.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		l.root.Remove(tmp)
		return err
	}
	if err := l.root.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		l.root.Remove(tmp)
		return err
	}
	if err := l.root.Rename(tmp, p); err != nil {
		l.root.Remove(tmp)
		return err
	}
	return nil
}

func (l *Local) Stat(_ context.Context, key string) (ObjectInfo, error) {
	p, err := localFile(key)
	if err != nil {
		return ObjectInfo{}, err
	}
	st, err := l.root.Stat(p)
	if errors.Is(err, fs.ErrNotExist) {
		return ObjectInfo{}, ErrNotFound
	}
	if err != nil {
		return ObjectInfo{}, err
	}
	return ObjectInfo{Key: key, Size: st.Size(), ModTime: st.ModTime()}, nil
}

func (l *Local) Delete(_ context.Context, key string) error {
	p, err := localFile(key)
	if err != nil {
		return err
	}
	err = l.root.Remove(p)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

func (l *Local) List(ctx context.Context, prefix string, fn func(ObjectInfo) error) error {
	if prefix != "" {
		if _, err := localFile(strings.TrimSuffix(prefix, "/")); err != nil {
			return err
		}
	}
	return fs.WalkDir(l.root.FS(), path.Dir(prefix), func(key string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				return nil
			}
			return err
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if d.IsDir() {
			if key == ".tmp" {
				return fs.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() || !strings.HasPrefix(key, prefix) {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		return fn(ObjectInfo{Key: key, Size: info.Size(), ModTime: info.ModTime()})
	})
}
