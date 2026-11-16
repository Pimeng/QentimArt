"""静态模式的 Python 端检查（由 tests/e2e.mjs 调用）。

自己用标准库 http.server 托管静态树目录，再以 base_url 做同步，
这样不依赖调用方的进程（Node 的 spawnSync 会阻塞其事件循环）。

usage: e2e_static.py SITE_DIR DEST tree1.json tree2.json DELTA_BYTES
"""
import functools
import gzip
import hashlib
import http.server
import json
import os
import shutil
import socketserver
import sys
import threading

from qentimart import QentimArtClient, QentimArtError

MiB = 1 << 20
failures = 0


def check(cond, msg):
    global failures
    print(("  ok   " if cond else "  FAIL ") + msg, flush=True)
    if not cond:
        failures += 1


def tree(root):
    out = {}
    for base, dirs, names in os.walk(root):
        rel_base = os.path.relpath(base, root).replace(os.sep, "/")
        if rel_base == ".":
            rel_base = ""
            if ".qentimart" in dirs:
                dirs.remove(".qentimart")
        for n in names:
            rel = rel_base + "/" + n if rel_base else n
            with open(os.path.join(base, n), "rb") as f:
                out[rel] = hashlib.sha256(f.read()).hexdigest()
    return out


def mib(n):
    return "%.1fMiB" % (n / MiB)


class _Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


def _craft_evil_tree(root, file_path):
    """伪造一棵只含单个空文件（路径为 file_path）的静态树，用于测试消费端路径安全。"""
    empty = hashlib.sha256(b"").hexdigest()
    meta = {"version": "1.0.0", "createdAt": "2026-01-01T00:00:00Z", "prerelease": False,
            "fileCount": 1, "totalSize": 0, "chunkCount": 0, "manifestSize": 0, "baseVersion": "", "deltaBytes": 0}
    manifest = {"formatVersion": 1, "version": "1.0.0", "createdAt": meta["createdAt"],
                "files": [{"path": file_path, "size": 0, "hash": empty}]}
    index = {"formatVersion": 1, "project": "evil", "updatedAt": meta["createdAt"],
             "latest": "1.0.0", "latestPrerelease": None, "versions": [meta]}
    os.makedirs(os.path.join(root, "versions", "1.0.0"), exist_ok=True)
    with open(os.path.join(root, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f)
    with open(os.path.join(root, "versions", "1.0.0", "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    with open(os.path.join(root, "versions", "1.0.0", "manifest.json.gz"), "wb") as f:
        f.write(gzip.compress(json.dumps(manifest).encode("utf-8")))


def main():
    site, dest, t1, t2, delta = sys.argv[1:6]
    delta = int(delta)
    with open(t1) as f:
        tree1 = json.load(f)
    with open(t2) as f:
        tree2 = json.load(f)
    shutil.rmtree(dest, ignore_errors=True)

    handler = functools.partial(_Quiet, directory=site)
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base_url = "http://127.0.0.1:%d" % httpd.server_address[1]

    with QentimArtClient(base_url=base_url, concurrency=16) as c:
        r = c.sync(dest, version="1.0.0")
        print("  静态全新同步 1.0.0: %s in %.2fs = %s/s" % (mib(r["bytes"]["downloaded_wire"]), r["elapsed_ms"] / 1000, mib(r["bytes_per_second"])))
        check(tree(dest) == tree1, "静态模式全新同步逐字节一致 (1.0.0)")
        rep = QentimArtClient.storage_report(dest)
        check(rep["ratio"] <= 1.10, "静态模式 ratio %.3f%% <= 110%%" % (rep["ratio"] * 100))

        r = c.sync(dest)
        check(r["version"] == "1.1.0" and tree(dest) == tree2, "静态模式增量更新逐字节一致 (1.1.0)")
        check(r["bytes"]["downloaded_raw"] <= delta + MiB, "静态模式只下载差异 (%s <= %s + 1MiB)" % (mib(r["bytes"]["downloaded_raw"]), mib(delta)))
        check(r["bytes"]["reused_local"] > 0, "静态模式复用本地已有的块")

        r = c.sync(dest)
        check(r["up_to_date"] and r["bytes"]["downloaded_wire"] == 0, "静态模式第二次同步为空操作")

        d = c.diff("1.0.0", "1.1.0")
        check(d["downloadBytes"] > 0 and (len(d["added"]) + len(d["modified"])) > 0, "静态模式客户端 diff 可用")

    # 消费端路径安全：清单路径合法，但本地父目录是指向外部的符号链接 → 必须拒绝，不逃逸。
    evil = dest + "-evil-symlink"
    shutil.rmtree(evil, ignore_errors=True)
    _craft_evil_tree(evil, "sub/empty.txt")
    ehandler = functools.partial(_Quiet, directory=evil)
    ehttpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), ehandler)
    ehttpd.daemon_threads = True
    threading.Thread(target=ehttpd.serve_forever, daemon=True).start()
    eurl = "http://127.0.0.1:%d" % ehttpd.server_address[1]
    edst = dest + "-escape"
    out = dest + "-escape-out"
    shutil.rmtree(edst, ignore_errors=True)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(edst)
    os.makedirs(out)
    symlinked = True
    try:
        os.symlink(out, os.path.join(edst, "sub"), target_is_directory=True)
    except (OSError, NotImplementedError, AttributeError):
        symlinked = False
    if not symlinked:
        print("  skip  符号链接父目录逃逸（本机无权限创建符号链接）", flush=True)
    else:
        threw = False
        try:
            with QentimArtClient(base_url=eurl) as ec:
                ec.sync(edst)
        except QentimArtError as e:
            threw = e.code == "EUNSAFE" or "symlink" in str(e) or "unsafe" in str(e)
        check(threw and len(os.listdir(out)) == 0, "符号链接父目录不会让写入逃逸到 root 外")
    ehttpd.shutdown()

    httpd.shutdown()
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
