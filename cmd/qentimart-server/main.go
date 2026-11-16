// Command qentimart-server 是 QentimArt 的服务端。
//
//	qentimart-server serve     -config server.json [-listen :8080]
//	qentimart-server gen-token
//
// 发布与查询等客户端操作请使用 qentimart 命令。
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"time"

	"qentimart/internal/server"
	"qentimart/internal/storage"
)

var version = "0.1.0"

const usage = `QentimArt 服务端 %s

用法:
  qentimart-server serve     -config server.json [-listen :8080]
  qentimart-server gen-token                      生成一个随机令牌，填入配置文件

发布与查询等客户端操作请使用 qentimart 命令。
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, usage, version)
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	var err error
	switch os.Args[1] {
	case "serve":
		err = cmdServe(ctx, os.Args[2:])
	case "gen-token":
		var b [24]byte
		rand.Read(b[:])
		fmt.Println(hex.EncodeToString(b[:]))
	case "version", "-v", "--version":
		fmt.Println(version)
	case "help", "-h", "--help":
		fmt.Printf(usage, version)
	default:
		fmt.Fprintf(os.Stderr, usage, version)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func cmdServe(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	cfgPath := fs.String("config", "server.json", "配置文件路径")
	listen := fs.String("listen", "", "覆盖配置中的监听地址")
	fs.Parse(args)
	cfg, err := server.LoadConfig(*cfgPath)
	if err != nil {
		return err
	}
	if *listen != "" {
		cfg.Listen = *listen
	}
	store, err := storage.Open(cfg.Storage)
	if err != nil {
		return err
	}
	logger := log.New(os.Stderr, "", log.LstdFlags|log.Lmicroseconds)
	srv, err := server.New(cfg, store, logger)
	if err != nil {
		return err
	}
	hs := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 16,
	}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		hs.Shutdown(sctx)
	}()
	logger.Printf("qentimart-server %s listening on %s (storage: %s)", version, cfg.Listen, storageDesc(cfg.Storage))
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func storageDesc(c storage.Config) string {
	if c.Type == "s3" && c.S3 != nil {
		return "s3 " + c.S3.Endpoint + "/" + c.S3.Bucket
	}
	return "local " + c.Path
}
