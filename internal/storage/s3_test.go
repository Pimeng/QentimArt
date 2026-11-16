package storage

import (
	"net/http"
	"net/url"
	"testing"
	"time"
)

// AWS SigV4 测试套件中的 "get-vanilla" 用例。
func TestSignV4Vanilla(t *testing.T) {
	req, _ := http.NewRequest("GET", "https://example.amazonaws.com/", nil)
	ts, _ := time.Parse("20060102T150405Z", "20150830T123600Z")
	SignV4(req, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ts, "us-east-1", "service", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
	want := "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
	if got := req.Header.Get("Authorization"); got != want {
		t.Fatalf("signature mismatch\n got %s\nwant %s", got, want)
	}
}

func TestCanonicalQuery(t *testing.T) {
	q := url.Values{"prefix": {"projects/a b/"}, "list-type": {"2"}}
	if got := canonicalQuery(q); got != "list-type=2&prefix=projects%2Fa%20b%2F" {
		t.Fatal(got)
	}
}
