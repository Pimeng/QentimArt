package wire

import (
	"bytes"
	"testing"
)

func TestDeflateRoundTripAndVerify(t *testing.T) {
	raw := bytes.Repeat([]byte("game asset "), 10000)
	h := Sum(raw)
	z := Deflate(raw)
	if len(z) >= len(raw) {
		t.Fatal("expected compression")
	}
	got, err := Decode(EncDeflate, uint32(len(raw)), z, h)
	if err != nil || !bytes.Equal(got, raw) {
		t.Fatal("deflate round trip failed", err)
	}
	if _, err := Decode(EncRaw, uint32(len(raw)), raw[:len(raw)-1], h); err == nil {
		t.Fatal("length mismatch not detected")
	}
	bad := append([]byte{}, raw...)
	bad[5] ^= 1
	if _, err := Decode(EncRaw, uint32(len(bad)), bad, h); err == nil {
		t.Fatal("corruption not detected")
	}
}

func TestFrameHeader(t *testing.T) {
	f := FrameHeader{Hash: Sum([]byte("x")), Enc: EncDeflate, RawLen: 123, StoredLen: 45}
	var b [FrameHeaderLen]byte
	f.Put(b[:])
	got, err := ReadFrameHeader(bytes.NewReader(b[:]))
	if err != nil || got != f {
		t.Fatal(got, err)
	}
	if _, err := ReadFrameHeader(bytes.NewReader(b[:10])); err == nil {
		t.Fatal("truncated header accepted")
	}
}
