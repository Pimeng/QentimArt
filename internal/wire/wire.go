// Package wire 定义了服务端、发布端和各 SDK 共用的二进制块帧格式。
//
// 帧（用于块上传和批量拉取）：
//
//	hash      [32]byte  原始（未压缩）块的 SHA-256
//	enc       uint8     0 = 原始数据，1 = 裸 DEFLATE（RFC 1951），0xFF = 缺失
//	rawLen    uint32 BE 原始块的长度
//	storedLen uint32 BE 随后 payload 的长度
//	payload   [storedLen]byte
//
// 存储 blob（存储后端中每个块对应一个对象）：
//
//	enc uint8 | rawLen uint32 BE | payload
package wire

import (
	"bytes"
	"compress/flate"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sync"
)

const (
	HashLen        = 32
	FrameHeaderLen = 41
	BlobHeaderLen  = 5
	MaxChunkSize   = 16 << 20

	EncRaw     byte = 0
	EncDeflate byte = 1
	EncMissing byte = 0xFF
)

// Hash 是一个 SHA-256 摘要。
type Hash [HashLen]byte

func (h Hash) String() string { return hex.EncodeToString(h[:]) }

// Sum 计算 data 的哈希。
func Sum(data []byte) Hash { return sha256.Sum256(data) }

// ParseHash 解析一个 64 字符的小写十六进制摘要。
func ParseHash(s string) (Hash, error) {
	var h Hash
	if len(s) != 2*HashLen {
		return h, fmt.Errorf("invalid hash %q", s)
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return h, fmt.Errorf("invalid hash %q (must be lowercase hex)", s)
		}
	}
	_, err := hex.Decode(h[:], []byte(s))
	return h, err
}

// ParseHashList 拆分由多个 32 字节哈希拼接而成的二进制请求体。
func ParseHashList(b []byte) ([]Hash, error) {
	if len(b)%HashLen != 0 {
		return nil, errors.New("hash list length must be a multiple of 32")
	}
	out := make([]Hash, len(b)/HashLen)
	for i := range out {
		copy(out[i][:], b[i*HashLen:])
	}
	return out, nil
}

// FrameHeader 是帧的固定长度部分。
type FrameHeader struct {
	Hash      Hash
	Enc       byte
	RawLen    uint32
	StoredLen uint32
}

// Put 把帧头编码到 b 中（len >= FrameHeaderLen）。
func (f FrameHeader) Put(b []byte) {
	copy(b, f.Hash[:])
	b[32] = f.Enc
	binary.BigEndian.PutUint32(b[33:], f.RawLen)
	binary.BigEndian.PutUint32(b[37:], f.StoredLen)
}

// ReadFrameHeader 读取一个帧头。仅当恰好位于帧边界时才返回 io.EOF。
func ReadFrameHeader(r io.Reader) (FrameHeader, error) {
	var b [FrameHeaderLen]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		if err == io.ErrUnexpectedEOF {
			return FrameHeader{}, errors.New("truncated frame header")
		}
		return FrameHeader{}, err
	}
	var f FrameHeader
	copy(f.Hash[:], b[:32])
	f.Enc = b[32]
	f.RawLen = binary.BigEndian.Uint32(b[33:])
	f.StoredLen = binary.BigEndian.Uint32(b[37:])
	if f.Enc != EncMissing && (f.RawLen > MaxChunkSize || f.StoredLen > MaxChunkSize+1024) {
		return FrameHeader{}, fmt.Errorf("frame too large (%d/%d)", f.RawLen, f.StoredLen)
	}
	return f, nil
}

var flateReaders = sync.Pool{}

// Decode 返回 payload 对应的原始块字节，并校验其哈希。
func Decode(enc byte, rawLen uint32, payload []byte, want Hash) ([]byte, error) {
	var raw []byte
	switch enc {
	case EncRaw:
		if uint32(len(payload)) != rawLen {
			return nil, errors.New("raw length mismatch")
		}
		raw = payload
	case EncDeflate:
		raw = make([]byte, rawLen)
		br := bytes.NewReader(payload)
		var fr io.ReadCloser
		if v := flateReaders.Get(); v != nil {
			fr = v.(io.ReadCloser)
			if err := fr.(flate.Resetter).Reset(br, nil); err != nil {
				return nil, err
			}
		} else {
			fr = flate.NewReader(br)
		}
		_, err := io.ReadFull(fr, raw)
		if err == nil {
			var one [1]byte
			if n, _ := fr.Read(one[:]); n != 0 {
				err = errors.New("deflate payload longer than rawLen")
			}
		}
		flateReaders.Put(fr)
		if err != nil {
			return nil, fmt.Errorf("deflate: %w", err)
		}
	default:
		return nil, fmt.Errorf("unknown encoding %d", enc)
	}
	if Sum(raw) != want {
		return nil, fmt.Errorf("chunk %s: hash mismatch", want)
	}
	return raw, nil
}

var flateWriters = sync.Pool{New: func() any {
	w, _ := flate.NewWriter(nil, flate.BestSpeed)
	return w
}}

// Deflate 使用 DEFLATE level 1 压缩 raw。
func Deflate(raw []byte) []byte {
	var buf bytes.Buffer
	buf.Grow(len(raw) / 2)
	w := flateWriters.Get().(*flate.Writer)
	w.Reset(&buf)
	w.Write(raw)
	w.Close()
	flateWriters.Put(w)
	return buf.Bytes()
}

// Blob 编码一个存储 blob。
func Blob(enc byte, rawLen uint32, payload []byte) []byte {
	b := make([]byte, BlobHeaderLen+len(payload))
	b[0] = enc
	binary.BigEndian.PutUint32(b[1:], rawLen)
	copy(b[BlobHeaderLen:], payload)
	return b
}

// ParseBlob 拆分一个存储 blob。
func ParseBlob(b []byte) (enc byte, rawLen uint32, payload []byte, err error) {
	if len(b) < BlobHeaderLen {
		return 0, 0, nil, errors.New("blob too short")
	}
	return b[0], binary.BigEndian.Uint32(b[1:]), b[BlobHeaderLen:], nil
}
