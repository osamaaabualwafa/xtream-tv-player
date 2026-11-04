#!/usr/bin/env python3
"""
Turns qa-out/ (what run-on-emulator.sh collected) into report/:
  index.html    the report to look at (screenshots, problems, automatic checks, crashes)
  summary.json  everything in one file, for the weekly review
  summary.md    short summary for the GitHub run page
  shots/        the screenshots
Usage: make_report.py qa-out report [run_url]
"""
import glob
import html
import json
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

SRC = sys.argv[1] if len(sys.argv) > 1 else "qa-out"
DST = sys.argv[2] if len(sys.argv) > 2 else "report"
RUN_URL = sys.argv[3] if len(sys.argv) > 3 else ""
PKG = "com.osama.xtreamtv"

os.makedirs(os.path.join(DST, "shots"), exist_ok=True)


def read(path, default=""):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return default


# ------------------------------------------------------------------ device
device = {}
device_files = sorted(glob.glob(os.path.join(SRC, "**", "device.txt"), recursive=True))
for line in (read(device_files[0]) if device_files else "").splitlines():
    if "=" in line:
        k, v = line.split("=", 1)
        device[k.strip()] = v.strip()

# ------------------------------------------------------------------ per-test records (TestStorage)
records = {}
for path in glob.glob(os.path.join(SRC, "**", "qa", "tests", "*.json"), recursive=True):
    try:
        r = json.loads(read(path))
        records[r["test"]] = r
    except Exception as e:  # noqa
        print("bad record", path, e)
for path in glob.glob(os.path.join(SRC, "**", "qa", "shots", "*.jpg"), recursive=True):
    shutil.copy(path, os.path.join(DST, "shots", os.path.basename(path)))

# ------------------------------------------------------------------ JUnit results (the truth on pass/fail)
junit = {}
for path in glob.glob(os.path.join(SRC, "**", "TEST-*.xml"), recursive=True):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for tc in root.iter("testcase"):
        cls = tc.get("classname", "").split(".")[-1]
        name = "%s.%s" % (cls, tc.get("name"))
        failure = tc.find("failure")
        if failure is None:
            failure = tc.find("error")
        skipped = tc.find("skipped") is not None
        junit[name] = {
            "result": "skipped" if skipped else ("failed" if failure is not None else "passed"),
            "seconds": float(tc.get("time") or 0),
            "error": (failure.get("message") or (failure.text or "")[:1500]) if failure is not None else "",
            "trace": (failure.text or "")[:4000] if failure is not None else "",
        }

tests = []
for name in sorted(set(junit) | set(records)):
    rec = records.get(name, {})
    j = junit.get(name)
    result = j["result"] if j else ("crashed" if rec.get("result") == "running" else rec.get("result", "unknown"))
    error = (j or {}).get("error") or rec.get("error", "")
    if result in ("crashed", "unknown") and not error:
        error = "The test stopped without finishing (the app or the test process crashed - see Crashes)."
    tests.append({
        "name": name,
        "result": result,
        "seconds": (j or {}).get("seconds") or rec.get("seconds", 0),
        "started": rec.get("started"),
        "error": error,
        "trace": (j or {}).get("trace", ""),
        "steps": rec.get("steps", []),
        "findings": rec.get("findings", []),
        "shots": rec.get("shots", []),
        "metrics": rec.get("metrics", {}),
    })

# ------------------------------------------------------------------ crashes and ANRs from logcat
logcat = "\n".join(read(p) for p in sorted(glob.glob(os.path.join(SRC, "**", "logcat.txt"), recursive=True)))
crashes, anrs = [], []
lines = logcat.splitlines()
i = 0
while i < len(lines):
    ln = lines[i]
    if "FATAL EXCEPTION" in ln:
        block = [ln]
        j = i + 1
        while j < len(lines) and j < i + 60 and (" E AndroidRuntime" in lines[j] or "AndroidRuntime:" in lines[j]):
            block.append(lines[j])
            j += 1
        text = "\n".join(block)
        if PKG in text:
            crashes.append({"time": ln[:18], "text": re.sub(r"^.*?AndroidRuntime:\s?", "", text, flags=re.M)[:3000]})
        i = j
        continue
    if "ANR in " + PKG in ln:
        block = lines[i:i + 12]
        anrs.append({"time": ln[:18], "text": "\n".join(re.sub(r"^.*?ActivityManager:\s?", "", b) for b in block)})
    i += 1

# App log (only our app's processes), for diagnosing failures.
pids = set(re.findall(r"Start proc (\d+):" + re.escape(PKG), logcat))
app_lines = []
for ln in lines:
    parts = ln.split(None, 3)
    if len(parts) >= 3 and (parts[2] in pids or PKG in ln):
        app_lines.append(ln)
if app_lines:
    import gzip
    with gzip.open(os.path.join(DST, "logcat_app.txt.gz"), "wt", encoding="utf-8") as f:
        f.write("\n".join(app_lines))
app_errors = [ln for ln in app_lines if re.match(r"^\S+ \S+\s+\d+\s+\d+ [EW] ", ln) and "QA" not in ln][-300:]

# The instrumentation runner stops the app on purpose between tests; only real crashes count.
crashes = [c for c in crashes if "Instrumentation" not in c["text"].split("\n")[0]]

# ------------------------------------------------------------------ monkey
monkey_files = glob.glob(os.path.join(SRC, "**", "monkey.txt"), recursive=True)
monkey_txt = read(monkey_files[0]) if monkey_files else ""
monkey_png = glob.glob(os.path.join(SRC, "**", "monkey_end.png"), recursive=True)
monkey = None
if monkey_txt:
    injected = re.search(r"Events injected:\s*(\d+)", monkey_txt)
    monkey = {
        "events": int(injected.group(1)) if injected else 0,
        "finished": "Monkey finished" in monkey_txt,
        "crash": "// CRASH" in monkey_txt,
        "anr": "NOT RESPONDING" in monkey_txt,
        "detail": "",
    }
    m = re.search(r"// CRASH:.*?(?=\n[^/]|\Z)", monkey_txt, re.S)
    if m:
        monkey["detail"] = m.group(0)[:3000]
    m = re.search(r"// NOT RESPONDING:.*?\n(?:.*\n){0,15}", monkey_txt)
    if m and not monkey["detail"]:
        monkey["detail"] = m.group(0)[:3000]
    if monkey_png:
        shutil.copy(monkey_png[0], os.path.join(DST, "shots", "monkey_end.png"))

# ------------------------------------------------------------------ automatic screen checks, findings, metrics
check_counts = {}
check_examples = {}
for t in tests:
    for s in t["shots"]:
        for c in s.get("checks", []):
            k = c.get("kind", "?")
            if k.startswith("info"):
                continue
            check_counts[k] = check_counts.get(k, 0) + 1
            check_examples.setdefault(k, [])
            if len(check_examples[k]) < 12:
                check_examples[k].append({"shot": s["file"], "text": c.get("text", ""), "detail": c.get("detail", ""), "test": t["name"]})

findings = [f for t in tests for f in t["findings"]]
for t in tests:
    for f in t["findings"]:
        f["test"] = t["name"]
metrics = {}
for t in tests:
    for k, v in t["metrics"].items():
        metrics["%s · %s" % (t["name"].split(".")[-1], k)] = v

totals = {
    "tests": len(tests),
    "passed": sum(1 for t in tests if t["result"] == "passed"),
    "failed": sum(1 for t in tests if t["result"] in ("failed", "crashed", "unknown")),
    "skipped": sum(1 for t in tests if t["result"] == "skipped"),
    "screenshots": sum(len(t["shots"]) for t in tests),
    "problems": sum(1 for f in findings if f["severity"] == "problem"),
    "warnings": sum(1 for f in findings if f["severity"] == "warning"),
}

videos = sorted(os.path.basename(p) for p in glob.glob(os.path.join(SRC, "**", "video", "*.mp4"), recursive=True))

# Shards (parallel emulators): which ran, and the build/emulator output of any that produced no results.
shards = []
for sf in sorted(glob.glob(os.path.join(SRC, "**", "shard.txt"), recursive=True)):
    d = os.path.dirname(sf)
    n_results = len(glob.glob(os.path.join(d, "**", "TEST-*.xml"), recursive=True))
    shards.append({"shard": read(sf).strip(), "gradle_exit": read(os.path.join(d, "gradle_exit.txt")).strip(),
                   "has_results": n_results > 0,
                   "tail": "" if n_results else read(os.path.join(d, "gradle.txt"))[-4000:]})

# ---- APP BUG list: real app bugs confirmed by review (qa/known_app_bugs.json), matched to this run.
app_bugs = []
known_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "known_app_bugs.json")
known = json.loads(read(known_path, "[]") or "[]")
for bug in known:
    hits = []
    for t in tests:
        matched_finding = [f for f in t["findings"] if any(m.lower() in f["title"].lower() for m in bug.get("finding_contains", []))]
        test_hit = t["result"] != "passed" and (
            t["name"] in bug.get("tests", []) or
            any(m in (t["error"] + t["trace"]) for m in bug.get("error_contains", [])))
        if matched_finding or test_hit:
            shot = (matched_finding[0].get("shot") if matched_finding else None) or (t["shots"][-1]["file"] if t["shots"] else None)
            hits.append({"test": t["name"], "result": t["result"], "shot": shot,
                         "steps": [x["text"] for x in t["steps"]][-25:],
                         "error": t["error"][:600], "finding": matched_finding[0]["title"] if matched_finding else ""})
            if test_hit:
                t["app_bug"] = bug["id"]
            for f in matched_finding:
                f["app_bug"] = bug["id"]
    crash_hits = [c for c in crashes if any(m in c["text"] for m in bug.get("error_contains", []))]
    app_bugs.append(dict(bug, seen_this_run=bool(hits or crash_hits), hits=hits, crash_count=len(crash_hits),
                         crash_example=crash_hits[0]["text"][:2500] if crash_hits else ""))
summary = {
    "generated": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
    "run_url": RUN_URL,
    "device": device,
    "gradle_exit": read(os.path.join(SRC, "gradle_exit.txt")).strip(),
    "totals": totals,
    "tests": tests,
    "findings": findings,
    "screen_checks": {"counts": check_counts, "examples": check_examples},
    "crashes": crashes,
    "anrs": anrs,
    "app_errors": app_errors,
    "monkey": monkey,
    "metrics": metrics,
    "videos": videos,
    "shards": shards,
    "app_bugs": app_bugs,
    "video_parts": read(os.path.join(SRC, "video", "parts.txt")),
}
if not tests:
    tails = "\n\n".join("[%s]\n%s" % (x["shard"], x["tail"]) for x in shards if x["tail"])
    summary["setup_error"] = tails or "No test results at all (the emulator or the build failed before the tests ran)."
missing = [x["shard"] for x in shards if not x["has_results"]]
if missing:
    summary["missing_shards"] = missing

with open(os.path.join(DST, "summary.json"), "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=1)

# ------------------------------------------------------------------ summary.md
md = ["## Weekly QA — %s" % summary["generated"], ""]
if summary.get("setup_error"):
    md += ["**The tests did not run.** See the job log.", ""]
md.append("| Tests | Passed | Failed | Problems | Warnings | Crashes | Freezes (ANR) | Screenshots |")
md.append("|---|---|---|---|---|---|---|---|")
md.append("| %d | %d | %d | %d | %d | %d | %d | %d |" % (
    totals["tests"], totals["passed"], totals["failed"], totals["problems"], totals["warnings"],
    len(crashes), len(anrs), totals["screenshots"]))
md.append("")
failed = [t for t in tests if t["result"] in ("failed", "crashed", "unknown")]
seen_bugs = [b for b in app_bugs if b["seen_this_run"]]
if seen_bugs:
    md.append("### APP BUG")
    for b in seen_bugs:
        md.append("- **APP BUG %s — %s** (%s)" % (b["id"], b["title"], ", ".join(sorted({h["test"] for h in b["hits"]}))))
    md.append("")
other = [t for t in failed if not t.get("app_bug")]
if other:
    md.append("### Failed (not an app bug on record)")
    for t in other:
        md.append("- **%s** — %s" % (t["name"], html.escape(t["error"].splitlines()[0][:200] if t["error"] else "")))
    md.append("")
if summary.get("missing_shards"):
    md.append("**Shards with no results:** %s" % ", ".join(summary["missing_shards"]))
    md.append("")
probs = [f for f in findings if f["severity"] == "problem" and not f.get("app_bug")]
if probs:
    md.append("### Problems found")
    for f in probs[:30]:
        md.append("- %s (%s) %s" % (f["title"], f["test"], f.get("detail", "")[:150]))
    md.append("")
if monkey:
    md.append("Random remote presses: %d events, %s." % (monkey["events"], "crash" if monkey["crash"] else "freeze" if monkey["anr"] else "no crash"))
md.append("")
md.append("Download **qa-report** below for screenshots (open index.html).")
with open(os.path.join(DST, "summary.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(md) + "\n")

# ------------------------------------------------------------------ index.html
CHECK_NAMES = {
    "no_focus": "Nothing highlighted (remote has no starting point)",
    "focus_hidden": "Highlighted item off screen",
    "text_cut": "Text cut short with …",
    "text_clipped": "Text taller than its box",
    "text_offscreen": "Text outside the screen / panel",
    "low_contrast": "Hard-to-read text (low contrast)",
    "overlap": "Text drawn over other text",
    "no_screen": "No app screen showing",
    "audit_error": "Check could not run",
}
E = html.escape
out = ["""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Weekly QA report</title><style>
body{font-family:Arial,Helvetica,sans-serif;margin:0;background:#f5f2ed;color:#23262b}
header{background:#23262b;color:#fff;padding:20px 24px}h1{margin:0;font-size:22px}
.sub{color:#c9cdd3;font-size:13px;margin-top:4px}main{padding:16px 24px;max-width:1300px}
.kpis{display:flex;flex-wrap:wrap;gap:10px;margin:12px 0}.kpi{background:#fff;border:1px solid #c9cdd3;padding:10px 14px;min-width:110px}
.kpi b{display:block;font-size:24px;color:#ed6927}.bad b{color:#c0392b}.good b{color:#1b7f4b}
h2{border-bottom:2px solid #ed6927;padding-bottom:4px;margin-top:28px}
table{border-collapse:collapse;width:100%;background:#fff}td,th{border:1px solid #c9cdd3;padding:6px 8px;font-size:13px;text-align:left;vertical-align:top}
th{background:#2b2f36;color:#fff}.pass{color:#1b7f4b;font-weight:bold}.fail{color:#c0392b;font-weight:bold}
.problem{color:#c0392b;font-weight:bold}.warning{color:#b7791f;font-weight:bold}.info{color:#6b7280}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}
figure{margin:0;background:#fff;border:1px solid #c9cdd3}figure img{width:100%;display:block}
figcaption{font-size:12px;padding:6px 8px}figcaption .w{color:#c0392b}
pre{background:#fff;border:1px solid #c9cdd3;padding:8px;overflow:auto;font-size:12px;white-space:pre-wrap}
details{margin:8px 0}summary{cursor:pointer;font-weight:bold}
</style></head><body>"""]
out.append("<header><h1>Xtream TV — weekly QA report</h1><div class='sub'>%s · %s %s · Android %s · %s %s</div></header><main>" % (
    E(summary["generated"]), E(device.get("model", "")), E(device.get("screen", "")), E(device.get("android", "")),
    "<a style='color:#ed6927' href='%s'>GitHub run</a>" % E(RUN_URL) if RUN_URL else "", ""))
if summary.get("setup_error"):
    out.append("<h2>The tests did not run</h2><pre>%s</pre>" % E(summary["setup_error"]))
if summary.get("missing_shards"):
    out.append("<h2>Some test groups did not run</h2><p>%s</p>" % E(", ".join(summary["missing_shards"])))


def kpi(label, value, cls=""):
    return "<div class='kpi %s'><b>%s</b>%s</div>" % (cls, value, label)


out.append("<div class='kpis'>" + "".join([
    kpi("tests", totals["tests"]),
    kpi("passed", totals["passed"], "good"),
    kpi("failed", totals["failed"], "bad" if totals["failed"] else "good"),
    kpi("APP BUGs", len(seen_bugs), "bad" if seen_bugs else "good"),
    kpi("problems", totals["problems"], "bad" if totals["problems"] else "good"),
    kpi("warnings", totals["warnings"]),
    kpi("crashes", len(crashes), "bad" if crashes else "good"),
    kpi("freezes (ANR)", len(anrs), "bad" if anrs else "good"),
    kpi("screenshots", totals["screenshots"]),
]) + "</div>")


def img(file, caption="", warn=None):
    w = "".join("<div class='w'>⚠ %s %s</div>" % (E(c.get("text", "")), E(c.get("detail", "")[:80])) for c in (warn or []) if not c.get("kind", "").startswith("info"))
    return "<figure><a href='shots/%s'><img loading='lazy' src='shots/%s'></a><figcaption>%s%s</figcaption></figure>" % (E(file), E(file), E(caption), w)


if seen_bugs:
    out.append("<h2 style='color:#c0392b'>APP BUG</h2><p class='info'>Real problems in the app, confirmed by review. The tests are right; the app needs a fix.</p>")
    for b in seen_bugs:
        out.append("<h3 class='fail'>APP BUG %s — %s</h3>" % (E(b["id"]), E(b["title"])))
        out.append("<p><b>Screen:</b> %s<br><b>What happens:</b> %s<br><b>Expected:</b> %s</p>" % (E(b.get("screen", "")), E(b.get("what", "")), E(b.get("expected", ""))))
        if b.get("steps"):
            out.append("<p><b>Steps to see it:</b></p><ol>%s</ol>" % "".join("<li>%s</li>" % E(x) for x in b["steps"]))
        out.append("<div class='grid'>%s</div>" % "".join(img(h["shot"], "%s (%s)" % (h["test"], h["result"])) for h in b["hits"] if h["shot"]))
        for h in b["hits"]:
            out.append("<details><summary>Test steps: %s</summary><pre>%s\n%s</pre></details>" % (E(h["test"]), E("\n".join(h["steps"])), E(h["error"])))
        if b.get("crash_example"):
            out.append("<details><summary>App crash log (%d time(s) this run)</summary><pre>%s</pre></details>" % (b["crash_count"], E(b["crash_example"])))
        if b.get("cause"):
            out.append("<p><b>Likely cause in the code:</b> %s</p>" % E(b["cause"]))

if failed:
    out.append("<h2>Failed tests</h2>")
    for t in failed:
        last = t["shots"][-1]["file"] if t["shots"] else None
        out.append("<h3 class='fail'>%s%s</h3><p>%s</p>" % ("APP BUG %s · " % E(t["app_bug"]) if t.get("app_bug") else "", E(t["name"]), E(t["error"][:800])))
        if last:
            out.append("<div class='grid'>%s</div>" % img(last, "Last screen"))
        if t["steps"]:
            out.append("<details><summary>Steps</summary><pre>%s</pre></details>" % E("\n".join("%6.1fs  %s" % (s["t"], s["text"]) for s in t["steps"])))
        if t["trace"]:
            out.append("<details><summary>Technical details</summary><pre>%s</pre></details>" % E(t["trace"]))

if findings:
    out.append("<h2>Things to look at</h2><table><tr><th>Severity</th><th>What</th><th>Test</th><th>Screen</th></tr>")
    order = {"problem": 0, "warning": 1, "info": 2}
    for f in sorted(findings, key=lambda f: order.get(f["severity"], 3)):
        shot = f.get("shot")
        sev = "APP BUG " + f["app_bug"] if f.get("app_bug") else f["severity"]
        out.append("<tr><td class='%s'>%s</td><td>%s<br><span class='info'>%s</span></td><td>%s</td><td>%s</td></tr>" % (
            E(f["severity"]), E(sev), E(f["title"]), E(f.get("detail", "")), E(f["test"]),
            "<a href='shots/%s'><img loading='lazy' src='shots/%s' width='220'></a>" % (E(shot), E(shot)) if shot else ""))
    out.append("</table>")

if crashes or anrs:
    out.append("<h2>Crashes and freezes</h2>")
    for c in crashes:
        out.append("<h3 class='fail'>Crash at %s</h3><pre>%s</pre>" % (E(c["time"]), E(c["text"])))
    for a in anrs:
        out.append("<h3 class='fail'>Freeze (ANR) at %s</h3><pre>%s</pre>" % (E(a["time"]), E(a["text"])))

if check_counts:
    out.append("<h2>Automatic screen checks</h2><p class='info'>Every screenshot is checked for these. Some are design choices; the examples show where.</p><table><tr><th>Check</th><th>Times</th><th>Examples</th></tr>")
    for k, n in sorted(check_counts.items(), key=lambda kv: -kv[1]):
        ex = "".join("<div><a href='shots/%s'>%s</a> — %s</div>" % (E(e["shot"]), E(e["shot"][:60]), E((e["detail"] or e["text"])[:90])) for e in check_examples.get(k, [])[:6])
        out.append("<tr><td>%s</td><td>%d</td><td>%s</td></tr>" % (E(CHECK_NAMES.get(k, k)), n, ex))
    out.append("</table>")

if monkey:
    out.append("<h2>Random remote presses</h2><p>%d presses, %s.</p>" % (
        monkey["events"], "<span class='fail'>the app crashed</span>" if monkey["crash"] else "<span class='fail'>the app froze</span>" if monkey["anr"] else "<span class='pass'>no crash or freeze</span>"))
    if monkey["detail"]:
        out.append("<pre>%s</pre>" % E(monkey["detail"]))
    if os.path.exists(os.path.join(DST, "shots", "monkey_end.png")):
        out.append("<div class='grid'>%s</div>" % img("monkey_end.png", "Screen after the random presses"))

if metrics:
    out.append("<h2>Measurements</h2><table><tr><th>What</th><th>Value</th></tr>")
    for k, v in sorted(metrics.items()):
        out.append("<tr><td>%s</td><td>%s</td></tr>" % (E(k), E(str(v))))
    out.append("</table>")

out.append("<h2>All tests</h2><table><tr><th>Test</th><th>Result</th><th>Time</th><th>Screens</th></tr>")
for t in tests:
    cls = "pass" if t["result"] == "passed" else "fail"
    out.append("<tr><td><a href='#%s'>%s</a></td><td class='%s'>%s</td><td>%.0f s</td><td>%d</td></tr>" % (
        E(t["name"]), E(t["name"]), cls, E(t["result"]), t["seconds"] or 0, len(t["shots"])))
out.append("</table>")

out.append("<h2>Screenshots</h2>")
for t in tests:
    if not t["shots"]:
        continue
    out.append("<details id='%s'><summary>%s (%d)</summary><div class='grid'>" % (E(t["name"]), E(t["name"]), len(t["shots"])))
    for s in t["shots"]:
        out.append(img(s["file"], "%s — %s [%s, highlight: %s]" % (s.get("name", ""), s.get("caption", ""), s.get("screen", ""), s.get("focus", "")), s.get("checks")))
    out.append("</div></details>")

if videos:
    out.append("<h2>Screen recording</h2><p>Download the <b>qa-video</b> artifact from the GitHub run: %s</p>" % E(", ".join(videos[:40])))
out.append("</main></body></html>")
with open(os.path.join(DST, "index.html"), "w", encoding="utf-8") as f:
    f.write("\n".join(out))

print(json.dumps(totals))
