package server

import "testing"

// TestNormalizeRequiresTokens 确认无令牌时必须显式 insecureNoAuth 才能启动（#4）。
func TestNormalizeRequiresTokens(t *testing.T) {
	c := Config{}
	if err := c.normalize(); err == nil {
		t.Fatal("expected error when no tokens and insecureNoAuth is false")
	}

	c = Config{InsecureNoAuth: true}
	if err := c.normalize(); err != nil {
		t.Fatalf("insecureNoAuth should permit zero tokens: %v", err)
	}
	// #9：默认 maxProjects 上限生效。
	if c.maxProjects != 100000 {
		t.Fatalf("default maxProjects = %d, want 100000", c.maxProjects)
	}

	c = Config{InsecureNoAuth: true, MaxProjects: 5}
	if err := c.normalize(); err != nil {
		t.Fatal(err)
	}
	if c.maxProjects != 5 {
		t.Fatalf("maxProjects = %d, want 5", c.maxProjects)
	}
}

// TestNormalizeTokenValidation 确认令牌长度与角色仍被校验。
func TestNormalizeTokenValidation(t *testing.T) {
	c := Config{Tokens: []TokenConfig{{Token: "short", Role: "read"}}}
	if err := c.normalize(); err == nil {
		t.Fatal("expected error for token shorter than 16 chars")
	}

	c = Config{Tokens: []TokenConfig{{Token: "0123456789abcdef0", Role: "bogus"}}}
	if err := c.normalize(); err == nil {
		t.Fatal("expected error for invalid role")
	}

	c = Config{Tokens: []TokenConfig{{Token: "0123456789abcdef0", Role: "read"}}}
	if err := c.normalize(); err != nil {
		t.Fatalf("valid token config rejected: %v", err)
	}
}
