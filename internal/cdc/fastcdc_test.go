package cdc

import (
	"bytes"
	"crypto/sha256"
	"io"
	"math/rand"
	"testing"
)

func chunks(t *testing.T, data []byte, p Params) [][32]byte {
	t.Helper()
	c := NewChunker(bytes.NewReader(data), p, nil)
	var out [][32]byte
	total := 0
	for {
		b, err := c.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if len(b) > p.Max {
			t.Fatalf("chunk %d > max", len(b))
		}
		total += len(b)
		out = append(out, sha256.Sum256(b))
	}
	if total != len(data) {
		t.Fatalf("chunks cover %d of %d bytes", total, len(data))
	}
	return out
}

func TestChunkingIsShiftResistant(t *testing.T) {
	p := Params{Min: 8 << 10, Avg: 32 << 10, Max: 128 << 10}
	data := make([]byte, 8<<20)
	rand.New(rand.NewSource(1)).Read(data)
	a := chunks(t, data, p)
	if n := len(a); n < 128 || n > 512 {
		t.Fatalf("unexpected chunk count %d for 8MiB at 32KiB avg", n)
	}
	// 在中间插入 100 字节：几乎所有块都必须保持不变。
	mod := append(append(append([]byte{}, data[:4<<20]...), bytes.Repeat([]byte{7}, 100)...), data[4<<20:]...)
	b := chunks(t, mod, p)
	set := map[[32]byte]bool{}
	for _, h := range a {
		set[h] = true
	}
	shared := 0
	for _, h := range b {
		if set[h] {
			shared++
		}
	}
	if shared < len(a)-3 {
		t.Fatalf("only %d of %d chunks shared after small insert", shared, len(a))
	}
}

func TestDeterministicGear(t *testing.T) {
	p := Default
	data := make([]byte, 20<<20)
	rand.New(rand.NewSource(42)).Read(data)
	a := chunks(t, data, p)
	b := chunks(t, data, p)
	if len(a) != len(b) {
		t.Fatal("non deterministic")
	}
}

func TestSmallInputs(t *testing.T) {
	for _, n := range []int{0, 1, 4095, 256 << 10, 256<<10 + 1} {
		data := make([]byte, n)
		got := chunks(t, data, Default)
		if n == 0 && len(got) != 0 {
			t.Fatal("empty input produced chunks")
		}
	}
}
