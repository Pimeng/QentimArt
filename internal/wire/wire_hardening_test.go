package wire

import (
	"bytes"
	"testing"
)

// TestDecodeRejectsDeflateOverrun 确认解压结果超过声明的 rawLen 时被拒绝，
// 而不是不受限制地继续解压（deflate 炸弹防护）。
func TestDecodeRejectsDeflateOverrun(t *testing.T) {
	raw := bytes.Repeat([]byte("asset "), 1000)
	z := Deflate(raw)
	h := Sum(raw)
	// 声称 rawLen 比实际解压结果少 1 字节：必须报错。
	if _, err := Decode(EncDeflate, uint32(len(raw)-1), z, h); err == nil {
		t.Fatal("expected error when inflated output exceeds declared rawLen")
	}
	// 合法 rawLen 仍能正常解码。
	if got, err := Decode(EncDeflate, uint32(len(raw)), z, h); err != nil || !bytes.Equal(got, raw) {
		t.Fatalf("valid decode failed: %v", err)
	}
}

// TestFrameHeaderRejectsOversize 确认帧头在分配内存前拒绝超过上限的 rawLen / storedLen。
func TestFrameHeaderRejectsOversize(t *testing.T) {
	var b [FrameHeaderLen]byte

	FrameHeader{Enc: EncDeflate, RawLen: MaxChunkSize + 1, StoredLen: 10}.Put(b[:])
	if _, err := ReadFrameHeader(bytes.NewReader(b[:])); err == nil {
		t.Fatal("oversized RawLen accepted")
	}

	FrameHeader{Enc: EncRaw, RawLen: 10, StoredLen: MaxChunkSize + 2048}.Put(b[:])
	if _, err := ReadFrameHeader(bytes.NewReader(b[:])); err == nil {
		t.Fatal("oversized StoredLen accepted")
	}

	// 边界内的帧头正常接受。
	ok := FrameHeader{Enc: EncDeflate, RawLen: MaxChunkSize, StoredLen: 1024}
	ok.Put(b[:])
	if got, err := ReadFrameHeader(bytes.NewReader(b[:])); err != nil || got != ok {
		t.Fatalf("valid header rejected: %v", err)
	}
}
