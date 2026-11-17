package storage

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

// S3Config 配置一个 S3 兼容的后端（AWS S3、MinIO、阿里云 OSS、腾讯云 COS、
// Cloudflare R2 等）。请求使用 SigV4 签名。
type S3Config struct {
	Endpoint    string `json:"endpoint"` // 例如 "https://s3.us-east-1.amazonaws.com" 或 "http://127.0.0.1:9000"
	Region      string `json:"region"`
	Bucket      string `json:"bucket"`
	AccessKey   string `json:"accessKey"`
	SecretKey   string `json:"secretKey"`
	PathStyle   bool   `json:"pathStyle"`             // MinIO 使用 true；虚拟主机（virtual-hosted）风格使用 false
	Prefix      string `json:"prefix,omitempty"`      // 可选：bucket 内的 key 前缀
	Parallelism int    `json:"parallelism,omitempty"` // 每批的并发读取数（默认 32）
	// AllowInsecureHTTP 允许对非回环地址使用明文 http endpoint。
	// 明文下对象数据与请求体均不加密、不受完整性保护（SigV4 只签名请求头），
	// 因此必须显式开启；回环地址（localhost / 127.0.0.1，如本地 MinIO）无需开启。
	AllowInsecureHTTP bool `json:"allowInsecureHTTP,omitempty"`
}

// S3 是一个最小化、无外部依赖的 S3 客户端。
type S3 struct {
	cfg    S3Config
	base   *url.URL
	client *http.Client
}

// NewS3 创建一个 S3 后端。
func NewS3(c S3Config) (*S3, error) {
	if c.Endpoint == "" || c.Bucket == "" {
		return nil, errors.New("s3: endpoint and bucket are required")
	}
	if c.Region == "" {
		c.Region = "us-east-1"
	}
	if c.Parallelism <= 0 {
		c.Parallelism = 32
	}
	c.Prefix = strings.Trim(c.Prefix, "/")
	u, err := url.Parse(strings.TrimRight(c.Endpoint, "/"))
	if err != nil {
		return nil, err
	}
	if u.Scheme != "https" && !c.AllowInsecureHTTP && !isLoopback(u.Hostname()) {
		return nil, fmt.Errorf("s3: endpoint %q is not https; data and request bodies would be sent in plaintext. "+
			`set "allowInsecureHTTP": true to override, or use a loopback address for local testing`, c.Endpoint)
	}
	tr := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		MaxIdleConns:        256,
		MaxIdleConnsPerHost: 128,
		IdleConnTimeout:     90 * time.Second,
		DisableCompression:  true,
	}
	return &S3{cfg: c, base: u, client: &http.Client{Transport: tr}}, nil
}

// isLoopback 判断主机名是否为回环地址（localhost 或回环 IP）。
func isLoopback(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func (s *S3) Parallelism() int { return s.cfg.Parallelism }

func (s *S3) objectKey(key string) string {
	if s.cfg.Prefix != "" {
		return s.cfg.Prefix + "/" + key
	}
	return key
}

func (s *S3) url(key string, query url.Values) *url.URL {
	u := *s.base
	if s.cfg.PathStyle {
		u.Path = "/" + s.cfg.Bucket
		if key != "" {
			u.Path += "/" + key
		}
	} else {
		u.Host = s.cfg.Bucket + "." + u.Host
		u.Path = "/" + key
	}
	u.RawPath = awsEscapePath(u.Path)
	u.RawQuery = canonicalQuery(query)
	return &u
}

func (s *S3) do(ctx context.Context, method, key string, query url.Values, body io.Reader, size int64) (*http.Response, error) {
	u := s.url(key, query)
	req, err := http.NewRequestWithContext(ctx, method, u.String(), body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.ContentLength = size
	}
	SignV4(req, "UNSIGNED-PAYLOAD", time.Now(), s.cfg.Region, "s3", s.cfg.AccessKey, s.cfg.SecretKey)
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode == http.StatusNotFound {
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		return nil, ErrNotFound
	}
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		resp.Body.Close()
		return nil, fmt.Errorf("s3 %s %s: %s: %s", method, key, resp.Status, strings.TrimSpace(string(b)))
	}
	return resp, nil
}

func (s *S3) Get(ctx context.Context, key string) (io.ReadCloser, int64, error) {
	resp, err := s.do(ctx, http.MethodGet, s.objectKey(key), nil, nil, 0)
	if err != nil {
		return nil, 0, err
	}
	return resp.Body, resp.ContentLength, nil
}

func (s *S3) Put(ctx context.Context, key string, r io.Reader, size int64) error {
	resp, err := s.do(ctx, http.MethodPut, s.objectKey(key), nil, r, size)
	if err != nil {
		return err
	}
	io.Copy(io.Discard, resp.Body)
	return resp.Body.Close()
}

func (s *S3) Stat(ctx context.Context, key string) (ObjectInfo, error) {
	resp, err := s.do(ctx, http.MethodHead, s.objectKey(key), nil, nil, 0)
	if err != nil {
		return ObjectInfo{}, err
	}
	resp.Body.Close()
	mt, _ := http.ParseTime(resp.Header.Get("Last-Modified"))
	return ObjectInfo{Key: key, Size: resp.ContentLength, ModTime: mt}, nil
}

func (s *S3) Delete(ctx context.Context, key string) error {
	resp, err := s.do(ctx, http.MethodDelete, s.objectKey(key), nil, nil, 0)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	return resp.Body.Close()
}

type listResult struct {
	IsTruncated           bool   `xml:"IsTruncated"`
	NextContinuationToken string `xml:"NextContinuationToken"`
	Contents              []struct {
		Key          string    `xml:"Key"`
		Size         int64     `xml:"Size"`
		LastModified time.Time `xml:"LastModified"`
	} `xml:"Contents"`
}

func (s *S3) List(ctx context.Context, prefix string, fn func(ObjectInfo) error) error {
	full := s.objectKey(prefix)
	strip := 0
	if s.cfg.Prefix != "" {
		strip = len(s.cfg.Prefix) + 1
	}
	token := ""
	for {
		q := url.Values{"list-type": {"2"}, "prefix": {full}, "max-keys": {"1000"}}
		if token != "" {
			q.Set("continuation-token", token)
		}
		resp, err := s.do(ctx, http.MethodGet, "", q, nil, 0)
		if err != nil {
			return err
		}
		var lr listResult
		err = xml.NewDecoder(resp.Body).Decode(&lr)
		resp.Body.Close()
		if err != nil {
			return fmt.Errorf("s3 list: %w", err)
		}
		for _, c := range lr.Contents {
			if len(c.Key) < strip {
				continue
			}
			if err := fn(ObjectInfo{Key: c.Key[strip:], Size: c.Size, ModTime: c.LastModified}); err != nil {
				return err
			}
		}
		if !lr.IsTruncated || lr.NextContinuationToken == "" {
			return nil
		}
		token = lr.NextContinuationToken
	}
}

// ---- SigV4 签名 ----

func isUnreserved(c byte) bool {
	return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~'
}

func awsEscape(s string, keepSlash bool) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if isUnreserved(c) || (keepSlash && c == '/') {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func awsEscapePath(p string) string { return awsEscape(p, true) }

func canonicalQuery(q url.Values) string {
	if len(q) == 0 {
		return ""
	}
	keys := make([]string, 0, len(q))
	for k := range q {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var parts []string
	for _, k := range keys {
		vs := append([]string(nil), q[k]...)
		sort.Strings(vs)
		for _, v := range vs {
			parts = append(parts, awsEscape(k, false)+"="+awsEscape(v, false))
		}
	}
	return strings.Join(parts, "&")
}

func hmacSHA256(key []byte, data string) []byte {
	h := hmac.New(sha256.New, key)
	h.Write([]byte(data))
	return h.Sum(nil)
}

// SignV4 原地对 req 签名（会签名 host、x-amz-date、x-amz-content-sha256 以及
// 所有已存在的 x-amz-* 请求头）。
func SignV4(req *http.Request, payloadHash string, t time.Time, region, service, accessKey, secretKey string) {
	amzDate := t.UTC().Format("20060102T150405Z")
	date := amzDate[:8]
	req.Header.Set("X-Amz-Date", amzDate)
	if service == "s3" {
		req.Header.Set("X-Amz-Content-Sha256", payloadHash)
	}
	host := req.Host
	if host == "" {
		host = req.URL.Host
	}
	headers := map[string]string{"host": host}
	for k, v := range req.Header {
		lk := strings.ToLower(k)
		if strings.HasPrefix(lk, "x-amz-") {
			headers[lk] = strings.TrimSpace(strings.Join(v, ","))
		}
	}
	names := make([]string, 0, len(headers))
	for k := range headers {
		names = append(names, k)
	}
	sort.Strings(names)
	var ch strings.Builder
	for _, n := range names {
		ch.WriteString(n + ":" + headers[n] + "\n")
	}
	signed := strings.Join(names, ";")
	uri := req.URL.EscapedPath()
	if uri == "" {
		uri = "/"
	}
	creq := req.Method + "\n" + uri + "\n" + req.URL.RawQuery + "\n" + ch.String() + "\n" + signed + "\n" + payloadHash
	scope := date + "/" + region + "/" + service + "/aws4_request"
	sum := sha256.Sum256([]byte(creq))
	sts := "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex.EncodeToString(sum[:])
	k := hmacSHA256([]byte("AWS4"+secretKey), date)
	k = hmacSHA256(k, region)
	k = hmacSHA256(k, service)
	k = hmacSHA256(k, "aws4_request")
	sig := hex.EncodeToString(hmacSHA256(k, sts))
	req.Header.Set("Authorization", "AWS4-HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+signed+", Signature="+sig)
}
