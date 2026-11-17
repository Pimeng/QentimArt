package storage

import "testing"

// TestNewS3RejectsPlaintextHTTP 确认非回环地址的明文 http endpoint 默认被拒，
// 显式 AllowInsecureHTTP 或回环地址 / https 才允许（#5）。
func TestNewS3RejectsPlaintextHTTP(t *testing.T) {
	base := S3Config{Bucket: "b", Region: "us-east-1"}

	with := func(endpoint string, allow bool) S3Config {
		c := base
		c.Endpoint = endpoint
		c.AllowInsecureHTTP = allow
		return c
	}

	// 非回环 http：拒绝。
	if _, err := NewS3(with("http://s3.example.com", false)); err == nil {
		t.Fatal("expected error for non-loopback http endpoint")
	}
	// 显式开启：允许。
	if _, err := NewS3(with("http://s3.example.com", true)); err != nil {
		t.Fatalf("allowInsecureHTTP should permit http: %v", err)
	}
	// 回环 http：允许（本地 MinIO 等）。
	for _, ep := range []string{"http://127.0.0.1:9000", "http://localhost:9000", "http://[::1]:9000"} {
		if _, err := NewS3(with(ep, false)); err != nil {
			t.Fatalf("loopback http %q should be allowed: %v", ep, err)
		}
	}
	// https：允许。
	if _, err := NewS3(with("https://s3.example.com", false)); err != nil {
		t.Fatalf("https should be allowed: %v", err)
	}
}
