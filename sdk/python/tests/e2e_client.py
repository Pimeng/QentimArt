"""tests/e2e.mjs 的 Python 部分（由该测试脚本运行，服务端也由它启动）。

用法： e2e_client.py SERVER PROJECT TOKEN DEST tree1.json tree2.json DELTA_BYTES
"""
import hashlib
import json
import os
import shutil
import sys

from qentimart import QentimArtClient, SyncConflictError

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


def main():
    server, project, token, dest, t1, t2, delta = sys.argv[1:8]
    delta = int(delta)
    with open(t1) as f:
        tree1 = json.load(f)
    with open(t2) as f:
        tree2 = json.load(f)
    shutil.rmtree(dest, ignore_errors=True)

    with QentimArtClient(server, project, token=token, concurrency=8) as c:
        r = c.sync(dest, version="1.0.0")
        print("  fresh sync 1.0.0: %s in %.2fs = %s/s" % (mib(r["bytes"]["downloaded_wire"]), r["elapsed_ms"] / 1000, mib(r["bytes_per_second"])))
        check(tree(dest) == tree1, "fresh sync is byte-identical to 1.0.0")
        rep = QentimArtClient.storage_report(dest)
        check(rep["ratio"] <= 1.10, "storage ratio after fresh sync %.3f%% <= 110%%" % (rep["ratio"] * 100))

        r = c.sync(dest)
        b = r["bytes"]
        print("  update -> %s: downloaded %s, reused locally %s, %.2fs" % (r["version"], mib(b["downloaded_raw"]), mib(b["reused_local"]), r["elapsed_ms"] / 1000))
        check(r["version"] == "1.1.0", "latest resolves to 1.1.0")
        check(tree(dest) == tree2, "incremental update is byte-identical to 1.1.0")
        check(b["downloaded_raw"] <= delta + MiB, "update downloads only the delta (%s <= %s + 1MiB)" % (mib(b["downloaded_raw"]), mib(delta)))
        check(b["reused_local"] > 0, "unchanged chunks of modified/moved files are copied locally")
        rep = QentimArtClient.storage_report(dest)
        check(rep["ratio"] <= 1.10, "storage ratio after update %.3f%% <= 110%%" % (rep["ratio"] * 100))

        r = c.sync(dest)
        check(r["up_to_date"] and r["bytes"]["downloaded_wire"] == 0, "second sync is a no-op")

        victim = sorted(k for k in tree2 if k.startswith("ui/"))[0]
        with open(os.path.join(dest, victim), "w") as f:
            f.write("locally edited")
        with open(os.path.join(dest, "ui", "untracked-by-user.txt"), "w") as f:
            f.write("mine")
        st = c.status(dest)
        check(victim in st["modified"] and "ui/untracked-by-user.txt" in st["untracked"], "status reports modified + untracked files")
        try:
            c.sync(dest, local_changes="fail")
            threw = False
        except SyncConflictError as e:
            threw = len(e.conflicts) == 2
        check(threw, "local_changes='fail' aborts with both conflicts")
        r = c.sync(dest, local_changes="keep")
        with open(os.path.join(dest, victim)) as f:
            kept_ok = f.read() == "locally edited"
        check(len(r["files"]["kept"]) == 2 and kept_ok, "local_changes='keep' preserves local edits")
        c.sync(dest)
        check(tree(dest) == tree2, "local_changes='overwrite' (default) restores 1.1.0 and removes extra files")

        r = c.sync(dest, version="1.0.0")
        check(tree(dest) == tree1, "downgrade to 1.0.0 is byte-identical (downloaded %s, reused %s)" % (mib(r["bytes"]["downloaded_raw"]), mib(r["bytes"]["reused_local"])))

        # Node 与 Python SDK 共享状态格式：status 必须看到一个干净的目录树。
        st = c.status(dest)
        check(st["clean"] and st["version"] == "1.0.0", "state is readable and clean after downgrade")

        wrong = dest + "-wrongdir"
        shutil.rmtree(wrong, ignore_errors=True)
        os.makedirs(os.path.join(wrong, "important"))
        with open(os.path.join(wrong, "important", "user-file.doc"), "w") as f:
            f.write("do not delete")
        r = c.sync(wrong, version="1.0.0")
        check(os.path.exists(os.path.join(wrong, "important", "user-file.doc")) and "important/user-file.doc" in r["files"]["kept"],
              "first sync never deletes unknown files (delete_untracked_on_first_sync=False)")

    fresh = dest + "-fresh"
    shutil.rmtree(fresh, ignore_errors=True)
    with QentimArtClient(server, project, token=token, concurrency=16) as c:
        r = c.sync(fresh)
        secs = r["elapsed_ms"] / 1000
        print("  throughput (fresh %s, concurrency 16): %s in %.2fs = %s/s" % (r["version"], mib(r["bytes"]["written"]), secs, mib(r["bytes"]["written"] / max(secs, 0.001))))
        check(tree(fresh) == tree2, "fresh sync of latest is byte-identical")

    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
