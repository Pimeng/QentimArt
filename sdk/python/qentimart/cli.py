"""qentimart-py：Python SDK 的命令行封装。"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

from . import QentimArtClient, QentimArtError, SyncConflictError


def _fmt(n: float) -> str:
    units = ["B", "KiB", "MiB", "GiB", "TiB"]
    i = 0
    while n >= 1024 and i < len(units) - 1:
        n /= 1024
        i += 1
    return ("%.1f%s" if i else "%d%s") % (n, units[i])


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="qentimart-py", description="QentimArt 下游客户端")
    p.add_argument("command", choices=["sync", "plan", "status", "verify", "versions", "report"])
    p.add_argument("--server", default=os.environ.get("QENTIMART_SERVER"))
    p.add_argument("--project", default=os.environ.get("QENTIMART_PROJECT"))
    p.add_argument("--token", default=os.environ.get("QENTIMART_TOKEN"))
    p.add_argument("--base-url", default=os.environ.get("QENTIMART_BASE_URL"), help="静态模式：公开静态树根地址（只读，无需鉴权）")
    p.add_argument("--dir")
    p.add_argument("--version", default="latest")
    p.add_argument("--local-changes", default="overwrite", choices=["overwrite", "keep", "fail"])
    p.add_argument("--prerelease", action="store_true")
    p.add_argument("--verify-all", action="store_true")
    p.add_argument("--delete-untracked-on-first-sync", action="store_true")
    p.add_argument("--concurrency", type=int, default=8)
    p.add_argument("--json", action="store_true", help="机器可读的输出")
    a = p.parse_args(argv)

    def need(name: str) -> str:
        v = getattr(a, name)
        if not v:
            p.error("--%s is required" % name.replace("_", "-"))
        return v

    def out(obj, text=None) -> None:
        print(json.dumps(obj, indent=2, ensure_ascii=False) if a.json or text is None else text)

    try:
        if a.command == "report":
            r = QentimArtClient.storage_report(need("dir"))
            out(r, "assets %s, overhead %s, ratio %.3f%%" % (_fmt(r["asset_bytes"]), _fmt(r["overhead_bytes"]), r["ratio"] * 100))
            return 0
        if a.base_url:
            client = QentimArtClient(base_url=a.base_url, project=a.project, concurrency=a.concurrency)
        else:
            client = QentimArtClient(need("server"), need("project"), token=a.token, concurrency=a.concurrency)
        with client as c:
            if a.command == "versions":
                v = c.list_versions()
                lines = ["%s %-16s %10s  +%-10s %d files  %s" % ("*" if x["version"] == v.get("latest") else " ", x["version"],
                         _fmt(x["totalSize"]), _fmt(x["deltaBytes"]), x["fileCount"], x["createdAt"]) for x in v["versions"]]
                out(v, "\n".join(lines) or "(no versions)")
            elif a.command == "status":
                out(c.status(need("dir")))
            elif a.command == "verify":
                out(c.verify(need("dir")))
            else:
                last = [0.0]

                def progress(pr):
                    now = time.time()
                    if now - last[0] < 1 and pr["phase"] != "done":
                        return
                    last[0] = now
                    pct = pr["done"] * 100.0 / pr["total"] if pr["total"] else 0.0
                    sys.stderr.write("  [%s] %.1f%%  %s/%s  %s/s\n" % (pr["phase"], pct, _fmt(pr["done"]), _fmt(pr["total"]), _fmt(pr.get("bytes_per_second", 0))))

                fn = c.plan if a.command == "plan" else c.sync
                r = fn(need("dir"), version=a.version, local_changes=a.local_changes, prerelease=a.prerelease,
                       verify_all=a.verify_all, delete_untracked_on_first_sync=a.delete_untracked_on_first_sync,
                       on_progress=None if a.json else progress)
                f, b = r["files"], r["bytes"]
                lines = [
                    "%s %s %s -> %s%s" % ("plan" if r["dry_run"] else "synced", r["project"], r["previous_version"] or "(none)",
                                          r["version"], " (already up to date)" if r["up_to_date"] else ""),
                    "files: %d total, %d unchanged, %d adopted, %d created, %d updated, %d deleted, %d kept" % (
                        f["total"], f["unchanged"], f["adopted"], f["created"], f["updated"], f["deleted"], len(f["kept"])),
                ]
                if r["dry_run"]:
                    lines.append("would write %s, estimated download %s" % (_fmt(b["to_write"]), _fmt(b["estimated_download"])))
                else:
                    lines.append("wrote %s: %s reused locally, %s downloaded (%s on the wire) in %.2fs" % (
                        _fmt(b["written"]), _fmt(b["reused_local"]), _fmt(b["downloaded_raw"]), _fmt(b["downloaded_wire"]), r["elapsed_ms"] / 1000))
                    s = r["storage"]
                    lines.append("storage: assets %s + overhead %s = %.3f%%" % (_fmt(s["asset_bytes"]), _fmt(s["overhead_bytes"]), s["ratio"] * 100))
                out(r, "\n".join(lines))
        return 0
    except SyncConflictError as e:
        print("\n".join(e.conflicts), file=sys.stderr)
        print("error: %s" % e, file=sys.stderr)
        return 1
    except QentimArtError as e:
        print("error: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
