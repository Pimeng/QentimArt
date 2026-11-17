package server_test

import (
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"testing"

	"qentimart/internal/server"
	"qentimart/internal/storage"
)

func getStatus(t *testing.T, url, token string) int {
	t.Helper()
	req, _ := http.NewRequest("GET", url, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

// TestAuthRejectsBadToken 确认无效令牌 / 缺失令牌被拒（#3 的鉴权行为）。
func TestAuthRejectsBadToken(t *testing.T) {
	ts, _ := setup(t)
	base := ts.URL + "/api/v1/projects/game/versions"

	if c := getStatus(t, base, readTok); c != http.StatusOK {
		t.Fatalf("valid read token: got %d want 200", c)
	}
	if c := getStatus(t, base, "wrong-token-0123456789xyz"); c != http.StatusUnauthorized {
		t.Fatalf("bad token: got %d want 401", c)
	}
	if c := getStatus(t, base, ""); c != http.StatusUnauthorized {
		t.Fatalf("no token: got %d want 401", c)
	}
	// 持有 read（game）令牌访问其它项目应被拒。
	other := ts.URL + "/api/v1/projects/other/versions"
	if c := getStatus(t, other, readTok); c != http.StatusUnauthorized {
		t.Fatalf("read token on unauthorized project: got %d want 401", c)
	}
}

// TestMaxProjectsCap 确认常驻项目数达上限后对新项目名返回 503（#9）。
func TestMaxProjectsCap(t *testing.T) {
	store, err := storage.NewLocal(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cfg := server.Config{
		Tokens:      []server.TokenConfig{{Token: adminTok, Role: "admin", Projects: []string{"*"}}},
		MaxProjects: 1,
	}
	srv, err := server.New(cfg, store, log.New(io.Discard, "", 0))
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	url := func(p string) string { return ts.URL + "/api/v1/projects/" + p + "/versions" }
	if c := getStatus(t, url("alpha"), adminTok); c != http.StatusOK {
		t.Fatalf("first project: got %d want 200", c)
	}
	// 重复访问同一项目不占用新配额。
	if c := getStatus(t, url("alpha"), adminTok); c != http.StatusOK {
		t.Fatalf("existing project: got %d want 200", c)
	}
	if c := getStatus(t, url("beta"), adminTok); c != http.StatusServiceUnavailable {
		t.Fatalf("second distinct project: got %d want 503", c)
	}
}
