package manifest

import (
	"testing"
	"time"

	"qentimart/internal/wire"
)

func TestRoundTripAndImpliedChunks(t *testing.T) {
	small := wire.Sum([]byte("a"))
	c1, c2 := wire.Sum([]byte("x")), wire.Sum([]byte("y"))
	m := &Manifest{Version: "1.0.0", CreatedAt: time.Now(), Files: []File{
		{Path: "b/big.pak", Size: 3, Hash: wire.Sum([]byte("xyy")), Chunks: []ChunkRef{{c1, 1}, {c2, 2}}},
		{Path: "a.txt", Size: 1, Hash: small, Chunks: []ChunkRef{{small, 1}}},
		{Path: "empty", Size: 0, Hash: wire.Sum(nil)},
	}}
	gz, err := Encode(m)
	if err != nil {
		t.Fatal(err)
	}
	got, err := Decode(gz, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	if got.Files[0].Path != "a.txt" || len(got.Files[0].Chunks) != 1 || got.Files[0].Chunks[0].Hash != small {
		t.Fatal("implied single chunk not expanded")
	}
	if len(got.Files[1].Chunks) != 2 || len(got.Files[2].Chunks) != 0 {
		t.Fatal("chunks not preserved")
	}
}

func TestValidation(t *testing.T) {
	h := wire.Sum(nil)
	bad := [][]File{
		{{Path: "../x", Hash: h}},
		{{Path: ".qentimart/state.json", Hash: h}},
		{{Path: "a\\b", Hash: h}},
		{{Path: "con:", Hash: h}},
		{{Path: "A.png", Hash: h}, {Path: "a.png", Hash: h}},
		{{Path: "a", Hash: h}, {Path: "a/b", Hash: h}},
		{{Path: "x", Size: 5, Hash: h, Chunks: []ChunkRef{{h, 4}}}},
	}
	for i, files := range bad {
		m := &Manifest{Files: files}
		if err := m.Validate(); err == nil {
			t.Errorf("case %d should fail validation", i)
		}
	}
}

func TestCompare(t *testing.T) {
	x, y, z := wire.Sum([]byte("x")), wire.Sum([]byte("y")), wire.Sum([]byte("z"))
	a := &Manifest{Version: "1.0.0", Files: []File{{Path: "keep", Size: 1, Hash: x, Chunks: []ChunkRef{{x, 1}}}, {Path: "gone", Size: 1, Hash: y, Chunks: []ChunkRef{{y, 1}}}}}
	b := &Manifest{Version: "1.1.0", Files: []File{{Path: "keep", Size: 1, Hash: x, Chunks: []ChunkRef{{x, 1}}}, {Path: "new", Size: 1, Hash: z, Chunks: []ChunkRef{{z, 1}}}}}
	d := Compare(a, b)
	if len(d.Added) != 1 || len(d.Removed) != 1 || len(d.Modified) != 0 || d.DownloadBytes != 1 {
		t.Fatalf("%+v", d)
	}
}
