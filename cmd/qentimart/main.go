// Command qentimart 是 QentimArt 的客户端：把素材发布到服务端，并查询、管理版本。
//
//	qentimart publish -server URL -project P -token T -version 1.2.3 ./Assets
//	qentimart versions | info | diff | delete-version | gc
//
// 启动服务端请使用 qentimart-server 命令。
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"time"

	"qentimart/internal/catalog"
	"qentimart/internal/cdc"
	"qentimart/internal/publish"
	"qentimart/internal/storage"
)

var version = "0.1.0"

const usage = `QentimArt 客户端 %s

用法:
  qentimart publish        [连接参数] -version 1.2.3 [-concurrency 8] [-compress auto|none] [-exclude glob,...] [-dry-run] DIR
  qentimart versions       [连接参数]
  qentimart info           [连接参数] -version latest
  qentimart diff           [连接参数] -from 1.0.0 -to 1.1.0
  qentimart delete-version [连接参数] -version 1.0.0
  qentimart gc             [连接参数] [-dry-run]

静态模式（无需服务端，构建静态分发树到本地目录，再用 wrangler / rsync 部署）:
  qentimart publish        --static -o DIR -version 1.2.3 [-chunk-avg 4194304] [-project NAME] SRC
  qentimart versions       --static -o DIR
  qentimart delete-version --static -o DIR -version 1.0.0
  qentimart gc             --static -o DIR [-dry-run]

连接参数（也可使用环境变量 QENTIMART_SERVER / QENTIMART_PROJECT / QENTIMART_TOKEN）:
  -server URL   -project NAME   -token TOKEN   [-token-file PATH]

启动服务端、生成令牌请使用 qentimart-server 命令。
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
	case "publish":
		err = cmdPublish(ctx, os.Args[2:])
	case "versions":
		err = cmdSimple(ctx, os.Args[2:], "versions")
	case "info":
		err = cmdSimple(ctx, os.Args[2:], "info")
	case "diff":
		err = cmdSimple(ctx, os.Args[2:], "diff")
	case "delete-version":
		err = cmdSimple(ctx, os.Args[2:], "delete")
	case "gc":
		err = cmdSimple(ctx, os.Args[2:], "gc")
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

type conn struct{ server, project, token, tokenFile *string }

func connFlags(fs *flag.FlagSet) conn {
	return conn{
		server:    fs.String("server", os.Getenv("QENTIMART_SERVER"), "服务器 URL"),
		project:   fs.String("project", os.Getenv("QENTIMART_PROJECT"), "项目名称"),
		token:     fs.String("token", os.Getenv("QENTIMART_TOKEN"), "API 令牌（命令行传入会出现在进程列表中；优先用 QENTIMART_TOKEN 或 -token-file）"),
		tokenFile: fs.String("token-file", "", "从文件读取 API 令牌（优先于 -token，避免令牌出现在进程列表中）"),
	}
}

func (c conn) client(concurrency int) (*publish.Client, error) {
	if *c.server == "" || *c.project == "" {
		return nil, errors.New("-server and -project are required")
	}
	token := *c.token
	if *c.tokenFile != "" {
		b, err := os.ReadFile(*c.tokenFile)
		if err != nil {
			return nil, fmt.Errorf("read token file: %w", err)
		}
		token = strings.TrimSpace(string(b))
	}
	return publish.NewClient(*c.server, *c.project, token, concurrency), nil
}

func cmdPublish(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("publish", flag.ExitOnError)
	cf := connFlags(fs)
	ver := fs.String("version", "", "要发布的语义化版本号，例如 1.2.3")
	conc := fs.Int("concurrency", 8, "并行上传流数量")
	compress := fs.String("compress", "auto", "压缩模式：auto | none")
	exclude := fs.String("exclude", "", "要跳过的 glob 模式，以逗号分隔（例如 '*.tmp,Library/'）")
	dry := fs.Bool("dry-run", false, "仅分块并报告将要处理的内容，不实际写入")
	static := fs.Bool("static", false, "静态模式：直接构建静态分发树（无需服务端），配合 -o")
	outDir := fs.String("o", "", "静态模式的输出目录（持久镜像，重复发布只写新块）")
	minC := fs.Int("chunk-min", cdc.Default.Min, "最小块大小")
	avgC := fs.Int("chunk-avg", cdc.Default.Avg, "平均块大小（须为 2 的幂）")
	maxC := fs.Int("chunk-max", cdc.Default.Max, "最大块大小")
	fs.Parse(args)
	if fs.NArg() != 1 || *ver == "" {
		return errors.New("usage: qentimart publish [--static -o DIR] -version X.Y.Z [flags] DIR")
	}
	var ex []string
	for _, e := range strings.Split(*exclude, ",") {
		if e = strings.TrimSpace(e); e != "" {
			ex = append(ex, e)
		}
	}
	opts := publish.Options{
		Dir: fs.Arg(0), Version: *ver, Concurrency: *conc, Compress: *compress, Excludes: ex, DryRun: *dry,
		Params: cdc.Params{Min: *minC, Avg: *avgC, Max: *maxC},
		Logf:   func(f string, a ...any) { fmt.Fprintf(os.Stderr, f+"\n", a...) },
	}
	var res *publish.Result
	var err error
	if *static {
		if *outDir == "" {
			return errors.New("--static 需要 -o <输出目录>")
		}
		var backend storage.Backend
		if backend, err = storage.NewLocal(*outDir); err != nil {
			return err
		}
		res, err = publish.RunStatic(ctx, backend, *cf.project, opts)
	} else {
		var c *publish.Client
		if c, err = cf.client(*conc); err != nil {
			return err
		}
		res, err = publish.Run(ctx, c, opts)
	}
	if err != nil {
		return err
	}
	out, _ := json.MarshalIndent(res, "", "  ")
	fmt.Println(string(out))
	return nil
}

func cmdSimple(ctx context.Context, args []string, what string) error {
	fs := flag.NewFlagSet(what, flag.ExitOnError)
	cf := connFlags(fs)
	ver := fs.String("version", "latest", "版本号")
	from := fs.String("from", "", "diff：起始版本")
	to := fs.String("to", "latest", "diff：目标版本")
	dry := fs.Bool("dry-run", false, "gc：仅报告，不实际删除")
	gcGrace := fs.Duration("gc-grace", time.Hour, "gc：宽限期，保护最近写入但尚未被引用的块（对齐服务端默认 1h）")
	static := fs.Bool("static", false, "静态模式：直接操作本地静态树，配合 -o")
	outDir := fs.String("o", "", "静态模式的静态树目录")
	fs.Parse(args)

	if *static {
		if *outDir == "" {
			return errors.New("--static 需要 -o <目录>")
		}
		backend, err := storage.NewLocal(*outDir)
		if err != nil {
			return err
		}
		var out any
		switch what {
		case "versions":
			b, err := storage.ReadAll(ctx, backend, catalog.IndexKey)
			if err != nil {
				return err
			}
			fmt.Println(string(b))
			return nil
		case "delete":
			if *ver == "latest" {
				return errors.New("-version must be explicit")
			}
			if err := publish.DeleteVersionStatic(ctx, backend, *cf.project, *ver); err != nil {
				return err
			}
			out = map[string]any{"deleted": *ver, "hint": "run gc --static -o DIR to reclaim chunk storage"}
		case "gc":
			r, err := publish.GCStatic(ctx, backend, *dry, *gcGrace)
			if err != nil {
				return err
			}
			out = r
		default:
			return fmt.Errorf("%s 不支持 --static（请直接读取静态树或使用 SDK）", what)
		}
		b, _ := json.MarshalIndent(out, "", "  ")
		fmt.Println(string(b))
		return nil
	}

	c, err := cf.client(2)
	if err != nil {
		return err
	}
	var out any
	switch what {
	case "versions":
		err = c.JSON(ctx, "GET", "/versions?refresh=1", nil, &out)
	case "info":
		err = c.JSON(ctx, "GET", "/versions/"+*ver, nil, &out)
	case "diff":
		if *from == "" {
			return errors.New("-from is required")
		}
		err = c.JSON(ctx, "GET", "/versions/"+*to+"/diff?from="+*from, nil, &out)
	case "delete":
		if *ver == "latest" {
			return errors.New("-version must be explicit")
		}
		err = c.JSON(ctx, "DELETE", "/versions/"+*ver, nil, &out)
	case "gc":
		err = c.JSON(ctx, "POST", fmt.Sprintf("/gc?dryRun=%v", *dry), nil, &out)
	}
	if err != nil {
		return err
	}
	b, _ := json.MarshalIndent(out, "", "  ")
	fmt.Println(string(b))
	return nil
}
