"""静态模式的 Python 端检查（由 tests/e2e.mjs 调用）。

自己用标准库 http.server 托管静态树目录，再以 base_url 做同步，
这样不依赖调用方的进程（Node 的 spawnSync 会阻塞其事件循环）。

usage: e2e_static.py SITE_DIR DEST tree1.json tree2.json DELTA_BYTES
"""
import functools
import hashlib
import http.server
import json
import os
import shutil
import socketserver
import sys
import threading

from qentimart import QentimArtClient

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

    httpd.shutdown()
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
