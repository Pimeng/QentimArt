// Package semver 解析语义化版本 2.0（Semantic Versioning 2.0）版本字符串并对其排序
// （不含构建元数据：构建元数据不参与优先级比较，此处也不允许使用）。
package semver

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

var re = regexp.MustCompile(`^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?$`)

// Version 是解析后的语义化版本。
type Version struct {
	Major, Minor, Patch uint64
	Pre                 []string
	raw                 string
}

// Parse 解析 s，例如 "1.2.3" 或 "2.0.0-beta.1"。
func Parse(s string) (Version, error) {
	m := re.FindStringSubmatch(s)
	if m == nil {
		return Version{}, fmt.Errorf("invalid semantic version %q (expected MAJOR.MINOR.PATCH[-prerelease])", s)
	}
	var v Version
	var err error
	if v.Major, err = strconv.ParseUint(m[1], 10, 64); err != nil {
		return Version{}, err
	}
	if v.Minor, err = strconv.ParseUint(m[2], 10, 64); err != nil {
		return Version{}, err
	}
	if v.Patch, err = strconv.ParseUint(m[3], 10, 64); err != nil {
		return Version{}, err
	}
	if m[4] != "" {
		v.Pre = strings.Split(m[4], ".")
	}
	v.raw = s
	return v, nil
}

// Valid 报告 s 是否为合法的版本号。
func Valid(s string) bool { _, err := Parse(s); return err == nil }

func (v Version) String() string     { return v.raw }
func (v Version) IsPrerelease() bool { return len(v.Pre) > 0 }

func cmpU(a, b uint64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

func isNum(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return s != ""
}

// Compare 按照 SemVer 优先级规则返回 -1、0 或 1。
func Compare(a, b Version) int {
	if c := cmpU(a.Major, b.Major); c != 0 {
		return c
	}
	if c := cmpU(a.Minor, b.Minor); c != 0 {
		return c
	}
	if c := cmpU(a.Patch, b.Patch); c != 0 {
		return c
	}
	switch {
	case len(a.Pre) == 0 && len(b.Pre) == 0:
		return 0
	case len(a.Pre) == 0:
		return 1
	case len(b.Pre) == 0:
		return -1
	}
	for i := 0; i < len(a.Pre) && i < len(b.Pre); i++ {
		x, y := a.Pre[i], b.Pre[i]
		xn, yn := isNum(x), isNum(y)
		switch {
		case xn && yn:
			xi, _ := strconv.ParseUint(x, 10, 64)
			yi, _ := strconv.ParseUint(y, 10, 64)
			if c := cmpU(xi, yi); c != 0 {
				return c
			}
		case xn:
			return -1
		case yn:
			return 1
		default:
			if c := strings.Compare(x, y); c != 0 {
				return c
			}
		}
	}
	return cmpU(uint64(len(a.Pre)), uint64(len(b.Pre)))
}

// CompareStrings 比较两个版本字符串；不合法的字符串排在最前面。
func CompareStrings(a, b string) int {
	va, ea := Parse(a)
	vb, eb := Parse(b)
	switch {
	case ea != nil && eb != nil:
		return strings.Compare(a, b)
	case ea != nil:
		return -1
	case eb != nil:
		return 1
	}
	return Compare(va, vb)
}
