package manifest

import (
	"bytes"
	"compress/gzip"
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
	x := wire.Sum([]byte("x"))
	bad := [][]File{
		{{Path: "../x", Hash: h}},
		{{Path: ".qentimart/state.json", Hash: h}},
		{{Path: "a\\b", Hash: h}},
		{{Path: "con:", Hash: h}},
		{{Path: "dir/AuX .txt", Hash: h}},
		{{Path: "COM².bin", Hash: h}},
		{{Path: "A.png", Hash: h}, {Path: "a.png", Hash: h}},
		{{Path: "a", Hash: h}, {Path: "a/b", Hash: h}},
		{{Path: "x", Size: 5, Hash: h, Chunks: []ChunkRef{{h, 4}}}},
		{{Path: "empty", Hash: x}},
		{{Path: "x", Size: 1, Hash: h, Chunks: []ChunkRef{{x, 1}}}},
		{{Path: "a", Size: 1, Hash: x, Chunks: []ChunkRef{{x, 1}}}, {Path: "b", Size: 2, Hash: x, Chunks: []ChunkRef{{x, 2}}}},
	}
	for i, files := range bad {
		m := &Manifest{Files: files}
		if err := m.Validate(); err == nil {
			t.Errorf("case %d should fail validation", i)
		}
	}
}

func TestDecodeRequiresFilesArray(t *testing.T) {
	for _, document := range []string{
		`{"formatVersion":1}`,
		`{"formatVersion":1,"files":null}`,
		`{"formatVersion":1,"files":[]}`,
	} {
		var data bytes.Buffer
		zw := gzip.NewWriter(&data)
		if _, err := zw.Write([]byte(document)); err != nil {
			t.Fatal(err)
		}
		if err := zw.Close(); err != nil {
			t.Fatal(err)
		}
		_, err := Decode(data.Bytes(), 1<<20)
		wantValid := document == `{"formatVersion":1,"files":[]}`
		if (err == nil) != wantValid {
			t.Errorf("Decode(%s): want valid=%v, got %v", document, wantValid, err)
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
