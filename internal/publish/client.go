package publish

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client 是用于 AssetSync API 的轻量 HTTP 客户端（供 CLI 使用）。
type Client struct {
	Server  string
	Project string
	Token   string
	HTTP    *http.Client
}

// NewClient 创建一个客户端，其连接池大小按 concurrency 配置。
func NewClient(server, project, token string, concurrency int) *Client {
	tr := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		MaxIdleConnsPerHost: concurrency * 2,
		MaxConnsPerHost:     concurrency * 2,
		IdleConnTimeout:     90 * time.Second,
		DisableCompression:  true,
		WriteBufferSize:     256 << 10,
		ReadBufferSize:      256 << 10,
	}
	return &Client{Server: strings.TrimRight(server, "/"), Project: project, Token: token, HTTP: &http.Client{Transport: tr}}
}

func (c *Client) url(p string) string {
	return c.Server + "/api/v1/projects/" + url.PathEscape(c.Project) + p
}

// APIError 表示一个非 2xx 的响应。
type APIError struct {
	Status int
	Msg    string
}

func (e *APIError) Error() string { return fmt.Sprintf("server returned %d: %s", e.Status, e.Msg) }

// Do 发送请求，成功时返回可读取响应体的响应。
func (c *Client) Do(ctx context.Context, method, path string, body io.Reader, ctype string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.url(path), body)
	if err != nil {
		return nil, err
	}
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}
	if ctype != "" {
		req.Header.Set("Content-Type", ctype)
	}
	if br, ok := body.(*bytes.Reader); ok {
		req.ContentLength = int64(br.Len())
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		defer resp.Body.Close()
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		var e struct {
			Error string `json:"error"`
		}
		msg := strings.TrimSpace(string(b))
		if json.Unmarshal(b, &e) == nil && e.Error != "" {
			msg = e.Error
		}
		return nil, &APIError{resp.StatusCode, msg}
	}
	return resp, nil
}

// JSON 执行请求并把 JSON 响应解码到 out 中。
func (c *Client) JSON(ctx context.Context, method, path string, body []byte, out any) error {
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	resp, err := c.Do(ctx, method, path, r, "")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if out == nil {
		_, err = io.Copy(io.Discard, resp.Body)
		return err
	}
	return json.NewDecoder(resp.Body).Decode(out)
}
