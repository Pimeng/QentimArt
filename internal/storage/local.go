package storage

import (
	"context"
	"crypto/rand"
	"encoding/hex"
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
	root string
	tmp  string
}

// NewLocal 创建（如有必要）并打开本地后端。
func NewLocal(root string) (*Local, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	tmp := filepath.Join(abs, ".tmp")
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return nil, err
	}
	return &Local{root: abs, tmp: tmp}, nil
}

func (l *Local) file(key string) (string, error) {
	if key == "" || strings.Contains(key, "..") || strings.HasPrefix(key, "/") || strings.Contains(key, "\\") {
		return "", errors.New("storage: invalid key")
	}
	return filepath.Join(l.root, filepath.FromSlash(key)), nil
}

func (l *Local) Parallelism() int { return 8 }

func (l *Local) Get(_ context.Context, key string) (io.ReadCloser, int64, error) {
	p, err := l.file(key)
	if err != nil {
		return nil, 0, err
	}
	f, err := os.Open(p)
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
	p, err := l.file(key)
	if err != nil {
		return err
	}
	var rnd [8]byte
	rand.Read(rnd[:])
	tmp := filepath.Join(l.tmp, hex.EncodeToString(rnd[:]))
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, r); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, p); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

func (l *Local) Stat(_ context.Context, key string) (ObjectInfo, error) {
	p, err := l.file(key)
	if err != nil {
		return ObjectInfo{}, err
	}
	st, err := os.Stat(p)
	if errors.Is(err, fs.ErrNotExist) {
		return ObjectInfo{}, ErrNotFound
	}
	if err != nil {
		return ObjectInfo{}, err
	}
	return ObjectInfo{Key: key, Size: st.Size(), ModTime: st.ModTime()}, nil
}

func (l *Local) Delete(_ context.Context, key string) error {
	p, err := l.file(key)
	if err != nil {
		return err
	}
	err = os.Remove(p)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return err
}

func (l *Local) List(ctx context.Context, prefix string, fn func(ObjectInfo) error) error {
	dir := prefix
	if i := strings.LastIndexByte(dir, '/'); i >= 0 {
		dir = dir[:i]
	} else {
		dir = ""
	}
	start := filepath.Join(l.root, filepath.FromSlash(dir))
	err := filepath.WalkDir(start, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				return nil
			}
			return err
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		rel, _ := filepath.Rel(l.root, p)
		key := filepath.ToSlash(rel)
		if d.IsDir() {
			if key == ".tmp" {
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasPrefix(key, prefix) {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		return fn(ObjectInfo{Key: path.Clean(key), Size: info.Size(), ModTime: info.ModTime()})
	})
	return err
}
