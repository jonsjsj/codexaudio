"""audex-align: forced-alignment service for Audex immersion reading (Tier 3).

Takes a book's audio + EPUB, transcribes with faster-whisper (CPU int8 by
default; ALIGN_DEVICE=cuda uses the GPU when the NVIDIA container runtime is
wired), aligns the recognition against the book text, and serves a per-book
sync map: [{t0,t1,c0,c1,p,href}] — audio seconds ↔ book char range /
progression. One job runs at a time (the box is shared).
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .align import build_map, transcribe
from .epub_text import extract_epub_text

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("audex-align")

DEVICE = os.environ.get("ALIGN_DEVICE", "cpu")
MODEL = os.environ.get("ALIGN_MODEL", "small")
COMPUTE = os.environ.get("ALIGN_COMPUTE", "float16" if DEVICE == "cuda" else "int8")
DATA_DIR = Path(os.environ.get("ALIGN_DATA", "/data"))
MAPS_DIR = DATA_DIR / "maps"
# Raw word-level transcripts, saved so a map can be REBUILT after an algorithm
# change without re-transcribing (transcription is the multi-hour CPU cost).
TRANSCRIPTS_DIR = DATA_DIR / "transcripts"
MAPS_DIR.mkdir(parents=True, exist_ok=True)
TRANSCRIPTS_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="audex-align", version="0.1.0")
executor = ThreadPoolExecutor(max_workers=1)
jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()

# Persistent, resumable batch: a manifest on the data volume so a full series
# keeps aligning across container restarts (e.g. the CPU→GPU flip) — books whose
# map already exists are skipped, so nothing is redone.
BATCH_FILE = DATA_DIR / "batch.json"


def book_key(server_url: str, item_id: str) -> str:
    return hashlib.sha1(f"{server_url}|{item_id}".encode()).hexdigest()[:16]


class AbsJobRequest(BaseModel):
    serverUrl: str
    token: str
    libraryItemId: str
    # For works where audio and ebook are SEPARATE ABS items: audio comes from
    # libraryItemId, the EPUB from this one. Defaults to the same item.
    ebookLibraryItemId: str | None = None


class BatchItem(BaseModel):
    audioItemId: str
    ebookItemId: str | None = None


class BatchRequest(BaseModel):
    serverUrl: str
    token: str
    items: list[BatchItem]


@app.get("/", include_in_schema=False)
def index():
    from fastapi.responses import HTMLResponse

    return HTMLResponse(
        "<title>Audex</title><body style='font-family:sans-serif;max-width:32em;margin:4em auto'>"
        "<h2>Audex</h2><p>Native Android app for Audiobookshelf — audio + ebooks, offline-first, "
        "word-sync read-along.</p><p><a href='/audex.apk'>Download the app (APK)</a></p>"
        "<p style='color:#777'>This box also runs the audex-align word-sync service "
        "(<a href='/health'>health</a>).</p></body>"
    )


@app.api_route("/audex.apk", methods=["GET", "HEAD"], include_in_schema=False)
def audex_apk():
    """The current Audex build, dropped into /data by the deploy flow."""
    from fastapi.responses import FileResponse

    apk = DATA_DIR / "audex.apk"
    if not apk.exists():
        raise HTTPException(404, "no APK published yet")
    return FileResponse(
        apk,
        media_type="application/vnd.android.package-archive",
        filename="audex.apk",
        headers={"Cache-Control": "no-cache, must-revalidate"},
    )


@app.api_route("/audex-latest.json", methods=["GET", "HEAD"], include_in_schema=False)
def audex_latest():
    """Version manifest for the app's in-app updater: {versionCode, versionName,
    url, notes}. The deploy flow writes it to /data next to the APK."""
    manifest = DATA_DIR / "audex-latest.json"
    if not manifest.exists():
        raise HTTPException(404, "no update manifest published yet")
    return JSONResponse(
        json.loads(manifest.read_text()),
        headers={"Cache-Control": "no-cache, must-revalidate"},
    )


@app.api_route("/audex-beta.apk", methods=["GET", "HEAD"], include_in_schema=False)
def audex_beta_apk():
    """The current BETA build — published by the autofix flow, promoted by hand."""
    from fastapi.responses import FileResponse

    apk = DATA_DIR / "audex-beta.apk"
    if not apk.exists():
        raise HTTPException(404, "no beta APK published yet")
    return FileResponse(
        apk,
        media_type="application/vnd.android.package-archive",
        filename="audex-beta.apk",
        headers={"Cache-Control": "no-cache, must-revalidate"},
    )


@app.api_route("/audex-{version}.apk", methods=["GET", "HEAD"], include_in_schema=False)
def audex_versioned_apk(version: str):
    """Serve the canonical build under any version-stamped name
    (audex-0.3.8.0.apk -> audex.apk; audex-beta-<v>.apk -> audex-beta.apk) so the
    OTA manifest + web links can use a cache-bustable, version-named URL while
    there is one real file. Folds the previously-ephemeral versioned route into
    git so an align redeploy no longer drops it."""
    from fastapi.responses import FileResponse

    beta = version.startswith("beta-") or version == "beta"
    apk = DATA_DIR / ("audex-beta.apk" if beta else "audex.apk")
    if not apk.exists():
        raise HTTPException(404, "no APK published yet")
    return FileResponse(
        apk,
        media_type="application/vnd.android.package-archive",
        filename=f"audex-{version}.apk",
        headers={"Cache-Control": "no-cache, must-revalidate"},
    )
@app.api_route("/audex-beta-latest.json", methods=["GET", "HEAD"], include_in_schema=False)
def audex_beta_latest():
    manifest = DATA_DIR / "audex-beta-latest.json"
    if not manifest.exists():
        raise HTTPException(404, "no beta manifest published yet")
    return JSONResponse(
        json.loads(manifest.read_text()),
        headers={"Cache-Control": "no-cache, must-revalidate"},
    )


class ReportRequest(BaseModel):
    kind: str  # bug | idea | feedback
    title: str
    body: str = ""
    appVersion: str = ""


def _report_cfg() -> tuple[str, str]:
    """(repo, token) for the reporter, from DATA_DIR/report.json
    ({"repo": "owner/name", "token": "…"}). 503 until that file exists so the
    app never embeds credentials."""
    cfg_path = DATA_DIR / "report.json"
    if not cfg_path.exists():
        raise HTTPException(503, "report service not configured")
    cfg = json.loads(cfg_path.read_text())
    repo, token = cfg.get("repo"), cfg.get("token")
    if not repo or not token:
        raise HTTPException(503, "report service not configured")
    return repo, token


def _gh_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}


# A label like `fixed:0.1.6` / `fixed-in:v0.1.6` records the release that fixed
# an issue — this is the maintainer (or codex-autofix) hook the status loop reads.
_FIXED_LABEL = re.compile(r"^fixed(?:-in)?[:\s]\s*v?(.+)$", re.IGNORECASE)


def _fixed_in(issue: dict) -> str | None:
    """Which release fixed this issue, if it's recorded. Priority: a
    `fixed:<ver>` label, else the milestone title (for a closed issue). None
    means closed-but-no-version (shown as just "Resolved") or still open."""
    for label in issue.get("labels", []):
        name = label.get("name", "") if isinstance(label, dict) else str(label)
        m = _FIXED_LABEL.match(name.strip())
        if m:
            return m.group(1).strip()
    milestone = issue.get("milestone")
    if issue.get("state") == "closed" and milestone and milestone.get("title"):
        return milestone["title"].lstrip("vV ").strip() or None
    return None


@app.post("/reports")
def create_report(req: ReportRequest):
    """File a user report as a GitHub issue (Codex's reporter pattern)."""
    repo, token = _report_cfg()
    kind = req.kind if req.kind in ("bug", "idea", "feedback") else "feedback"
    title = req.title.strip()[:200]
    if not title:
        raise HTTPException(422, "title required")
    body = (req.body.strip() or "(no details)")[:8000]
    footer = f"\n\n---\nAudex {req.appVersion or '?'} · filed from the in-app reporter"
    r = httpx.post(
        f"https://api.github.com/repos/{repo}/issues",
        headers=_gh_headers(token),
        json={"title": f"[{kind}] {title}", "body": body + footer,
              "labels": [f"audex-{kind}"]},
        timeout=30,
    )
    if r.status_code != 201:
        log.error("report create failed: %s %s", r.status_code, r.text[:300])
        raise HTTPException(502, "couldn't file the report")
    issue = r.json()
    return {"number": issue["number"], "url": issue["html_url"]}


@app.get("/reports/{number}")
def report_status(number: int):
    """Close the loop the app polls: report an issue's live state back to the
    device that filed it — open vs closed, and (when recorded) the release that
    fixed it. This is the Audex-side analogue of Codex's status-check routine,
    served live from GitHub on each poll rather than via a cron.

    Returns `{number, state, fixedIn, url}`:
      - `state`   — "open" or "closed" (GitHub issue state)
      - `fixedIn` — release version from a `fixed:<ver>` label / milestone, else null
    """
    repo, token = _report_cfg()
    r = httpx.get(
        f"https://api.github.com/repos/{repo}/issues/{number}",
        headers=_gh_headers(token),
        timeout=30,
    )
    if r.status_code == 404:
        raise HTTPException(404, "no such report")
    if r.status_code != 200:
        log.error("report status failed: %s %s", r.status_code, r.text[:300])
        raise HTTPException(502, "couldn't read the report status")
    issue = r.json()
    return {
        "number": issue.get("number", number),
        "state": issue.get("state", "open"),
        "fixedIn": _fixed_in(issue),
        "url": issue.get("html_url", ""),
    }


@app.on_event("startup")
def _resume_batch_on_startup():
    # Resume any registered batch after a restart (CPU→GPU flip, reboot).
    threading.Thread(
        target=lambda: (time.sleep(5), enqueue_batch()), daemon=True
    ).start()


@app.get("/health")
def health():
    return {
        "ok": True,
        "device": DEVICE,
        "model": MODEL,
        "compute": COMPUTE,
        "maps": len(list(MAPS_DIR.glob("*.json"))),
        "jobs": {k: v["state"] for k, v in jobs.items()},
    }


@app.post("/jobs/abs")
def create_abs_job(req: AbsJobRequest):
    """Align a book fetched straight from an Audiobookshelf server."""
    key = book_key(req.serverUrl, req.libraryItemId)
    job_id = uuid.uuid4().hex[:12]
    with jobs_lock:
        jobs[job_id] = {"state": "queued", "bookKey": key, "createdAt": time.time(), "detail": ""}
    executor.submit(_run_abs_job, job_id, req)
    return {"jobId": job_id, "bookKey": key}


@app.post("/jobs/upload")
async def create_upload_job(epub: UploadFile, audio: list[UploadFile], bookKey: str = ""):
    """Align directly uploaded files (testing / non-ABS sources)."""
    key = bookKey or uuid.uuid4().hex[:16]
    workdir = Path(tempfile.mkdtemp(prefix="alignup_"))
    epub_path = workdir / "book.epub"
    epub_path.write_bytes(await epub.read())
    audio_paths: list[str] = []
    for i, f in enumerate(audio):
        suffix = Path(f.filename or f"a{i}.mp3").suffix or ".mp3"
        p = workdir / f"{i:03d}{suffix}"
        p.write_bytes(await f.read())
        audio_paths.append(str(p))
    job_id = uuid.uuid4().hex[:12]
    with jobs_lock:
        jobs[job_id] = {"state": "queued", "bookKey": key, "createdAt": time.time(), "detail": ""}
    executor.submit(_run_align, job_id, key, str(epub_path), audio_paths, workdir)
    return {"jobId": job_id, "bookKey": key}


@app.get("/jobs/{job_id}")
def job_status(job_id: str):
    job = jobs.get(job_id)
    if job is None:
        raise HTTPException(404, "no such job")
    return job


@app.get("/batch")
def batch_status():
    if not BATCH_FILE.exists():
        return {"count": 0, "done": 0, "items": []}
    b = json.loads(BATCH_FILE.read_text())
    items = []
    for it in b.get("items", []):
        key = book_key(b["serverUrl"], it["audioItemId"])
        done = (MAPS_DIR / f"{key}.json").exists()
        state = next((j["state"] for j in jobs.values() if j.get("bookKey") == key), "pending")
        items.append({"audioItemId": it["audioItemId"], "bookKey": key,
                      "done": done, "state": "done" if done else state})
    return {"count": len(items), "done": sum(1 for i in items if i["done"]), "items": items}


@app.post("/batch")
def create_batch(req: BatchRequest):
    """Register a resumable batch and enqueue everything not yet aligned."""
    BATCH_FILE.write_text(json.dumps(req.model_dump()))
    enqueued = enqueue_batch()
    return {"registered": len(req.items), "enqueued": enqueued}


def enqueue_batch() -> int:
    """Submit every manifest book whose map is missing and isn't already queued."""
    if not BATCH_FILE.exists():
        return 0
    b = json.loads(BATCH_FILE.read_text())
    count = 0
    for it in b.get("items", []):
        key = book_key(b["serverUrl"], it["audioItemId"])
        if (MAPS_DIR / f"{key}.json").exists():
            continue
        with jobs_lock:
            busy = any(j.get("bookKey") == key and j["state"] not in ("done", "error")
                       for j in jobs.values())
        if busy:
            continue
        job_id = uuid.uuid4().hex[:12]
        with jobs_lock:
            jobs[job_id] = {"state": "queued", "bookKey": key,
                            "createdAt": time.time(), "detail": "batch"}
        job = AbsJobRequest(serverUrl=b["serverUrl"], token=b["token"],
                            libraryItemId=it["audioItemId"], ebookLibraryItemId=it.get("ebookItemId"))
        executor.submit(_run_abs_job, job_id, job)
        count += 1
    return count


@app.api_route("/maps/{key}", methods=["GET", "HEAD"])
def get_map(key: str):
    path = MAPS_DIR / f"{key}.json"
    if not path.exists():
        raise HTTPException(404, "no map for this book")
    return JSONResponse(json.loads(path.read_text()))


def _set(job_id: str, state: str, detail: str = ""):
    with jobs_lock:
        jobs[job_id]["state"] = state
        jobs[job_id]["detail"] = detail
    log.info("job %s → %s %s", job_id, state, detail)


def _run_abs_job(job_id: str, req: AbsJobRequest):
    # Idempotent: a book already aligned (e.g. after a restart mid-batch) is a
    # no-op, so re-enqueueing the whole manifest is cheap.
    key = jobs.get(job_id, {}).get("bookKey") or book_key(req.serverUrl, req.libraryItemId)
    if (MAPS_DIR / f"{key}.json").exists():
        _set(job_id, "done", "already aligned")
        return
    workdir = Path(tempfile.mkdtemp(prefix="alignabs_"))
    try:
        _set(job_id, "downloading")
        base = req.serverUrl.rstrip("/")
        ebook_item = req.ebookLibraryItemId or req.libraryItemId
        headers = {"Authorization": f"Bearer {req.token}"}
        with httpx.Client(headers=headers, timeout=300, follow_redirects=True) as client:
            item = client.get(f"{base}/api/items/{req.libraryItemId}", params={"expanded": 1})
            item.raise_for_status()
            audio_files = item.json().get("media", {}).get("audioFiles", [])
            if ebook_item == req.libraryItemId:
                ebook = item.json().get("media", {}).get("ebookFile")
            else:
                other = client.get(f"{base}/api/items/{ebook_item}", params={"expanded": 1})
                other.raise_for_status()
                ebook = other.json().get("media", {}).get("ebookFile")
            if not ebook or not audio_files:
                _set(job_id, "error", "need audio files on the audio item and an ebook file on the ebook item")
                return
            epub_path = workdir / "book.epub"
            _download(client, f"{base}/api/items/{ebook_item}/file/{ebook['ino']}", epub_path)
            audio_paths: list[str] = []
            for i, af in enumerate(sorted(audio_files, key=lambda a: a.get("index", 0))):
                p = workdir / f"{i:03d}.audio"
                _download(client, f"{base}/api/items/{req.libraryItemId}/file/{af['ino']}", p)
                audio_paths.append(str(p))
        _run_align(job_id, jobs[job_id]["bookKey"], str(epub_path), audio_paths, workdir)
    except Exception as e:  # noqa: BLE001 — job boundary
        log.exception("abs job failed")
        _set(job_id, "error", str(e))
        shutil.rmtree(workdir, ignore_errors=True)


def _download(client: httpx.Client, url: str, dest: Path):
    with client.stream("GET", url) as r:
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_bytes(1 << 20):
                f.write(chunk)


def _run_align(job_id: str, key: str, epub_path: str, audio_paths: list[str], workdir: Path):
    try:
        _set(job_id, "extracting")
        book = extract_epub_text(epub_path)
        if len(book.text) < 500:
            _set(job_id, "error", "couldn't extract meaningful text from the EPUB")
            return
        _set(job_id, "transcribing", f"{len(audio_paths)} file(s), model={MODEL}, device={DEVICE}")
        words, duration = transcribe(audio_paths, MODEL, DEVICE, COMPUTE)
        # Persist the raw word-level transcript so the map can be rebuilt after an
        # algorithm change WITHOUT paying the multi-hour transcription again.
        try:
            (TRANSCRIPTS_DIR / f"{key}.json").write_text(json.dumps(
                {"durationS": duration, "model": MODEL,
                 "words": [[round(w.start, 3), round(w.end, 3), w.text] for w in words]}
            ))
        except Exception:  # noqa: BLE001 — a transcript-cache failure must not fail the job
            log.exception("failed to cache transcript")
        _set(job_id, "aligning", f"{len(words)} words vs {len(book.text)} chars")
        sync_map = build_map(words, book, duration, MODEL, DEVICE)
        (MAPS_DIR / f"{key}.json").write_text(json.dumps(sync_map))
        n = len(sync_map.get("entries", []))
        with jobs_lock:
            jobs[job_id]["entries"] = n
        _set(job_id, "done", f"{n} anchors over {duration:.0f}s")
    except Exception as e:  # noqa: BLE001 — job boundary
        log.exception("align job failed")
        _set(job_id, "error", str(e))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
