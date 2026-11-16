// Package cdc 实现了 FastCDC 内容定义分块（content-defined chunking）。
//
// 块边界只取决于内容本身，因此在大文件中插入或删除字节时，只有编辑位置附近的块会改变；
// 其余所有块保持哈希不变，可与之前的版本去重。
package cdc

import (
	"errors"
	"fmt"
	"io"
	"math/bits"
)

// Params 控制块大小。Avg 必须是 2 的幂。
type Params struct {
	Min int `json:"min"`
	Avg int `json:"avg"`
	Max int `json:"max"`
}

// Default 是默认参数：256 KiB / 1 MiB / 4 MiB。足够大以获得高吞吐量和较小的 manifest，
// 又足够小，能在大型打包资源文件内部实现有效去重。
var Default = Params{Min: 256 << 10, Avg: 1 << 20, Max: 4 << 20}

var gear [256]uint64

func init() {
	// 确定性的 splitmix64 序列；该表绝不能改变，否则块边界（以及与旧版本的去重）
	// 都会发生偏移。
	var s uint64 = 0x2545F4914F6CDD1D
	for i := range gear {
		s += 0x9E3779B97F4A7C15
		z := s
		z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
		z = (z ^ (z >> 27)) * 0x94D049BB133111EB
		gear[i] = z ^ (z >> 31)
	}
}

// Validate 校验参数。
func (p Params) Validate() error {
	if p.Min < 4096 || p.Avg <= p.Min || p.Max <= p.Avg {
		return fmt.Errorf("cdc: need 4096 <= min < avg < max, got %d/%d/%d", p.Min, p.Avg, p.Max)
	}
	if p.Avg&(p.Avg-1) != 0 {
		return errors.New("cdc: avg must be a power of two")
	}
	if p.Max > 16<<20 {
		return errors.New("cdc: max must be <= 16 MiB")
	}
	return nil
}

func (p Params) masks() (small, large uint64) {
	b := bits.TrailingZeros(uint(p.Avg))
	// 归一化分块（level 2）：在 avg 之前使用更严格的掩码，之后使用更宽松的掩码。
	small = ^uint64(0) << (64 - (b + 2))
	large = ^uint64(0) << (64 - (b - 2))
	return
}

// cut 返回 data 起始处下一个块的长度。除非输入已到达 EOF，否则 data 必须
// 至少包含 p.Max 字节。
func (p Params) cut(data []byte, maskS, maskL uint64) int {
	n := len(data)
	if n <= p.Min {
		return n
	}
	if n > p.Max {
		n = p.Max
	}
	normal := p.Avg
	if normal > n {
		normal = n
	}
	var h uint64
	i := p.Min
	for ; i < normal; i++ {
		h = (h << 1) + gear[data[i]]
		if h&maskS == 0 {
			return i + 1
		}
	}
	for ; i < n; i++ {
		h = (h << 1) + gear[data[i]]
		if h&maskL == 0 {
			return i + 1
		}
	}
	return n
}

// Chunker 把数据流切分为内容定义的块。
type Chunker struct {
	r            io.Reader
	p            Params
	maskS, maskL uint64
	buf          []byte
	start, end   int
	eof          bool
}

// NewChunker 返回一个从 r 读取数据的分块器。buf 可以为 nil；若提供，其容量必须
// >= 2*p.Max，并会被复用（适合配合 sync.Pool 使用）。
func NewChunker(r io.Reader, p Params, buf []byte) *Chunker {
	if cap(buf) < 2*p.Max {
		buf = make([]byte, 2*p.Max)
	}
	s, l := p.masks()
	return &Chunker{r: r, p: p, maskS: s, maskL: l, buf: buf[:cap(buf)]}
}

func (c *Chunker) fill() error {
	if c.eof || c.end-c.start >= c.p.Max {
		return nil
	}
	if c.start > 0 {
		c.end = copy(c.buf, c.buf[c.start:c.end])
		c.start = 0
	}
	for c.end < len(c.buf) && c.end < c.p.Max {
		n, err := c.r.Read(c.buf[c.end:])
		c.end += n
		if err == io.EOF {
			c.eof = true
			return nil
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// Next 返回下一个块。返回的切片仅在下一次调用之前有效。
// 输入耗尽时返回 io.EOF。
func (c *Chunker) Next() ([]byte, error) {
	if err := c.fill(); err != nil {
		return nil, err
	}
	if c.end == c.start {
		return nil, io.EOF
	}
	n := c.p.cut(c.buf[c.start:c.end], c.maskS, c.maskL)
	out := c.buf[c.start : c.start+n]
	c.start += n
	return out, nil
}
