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

from .align import align_segments, build_map, transcribe
from .epub_text import extract_epub_text

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("audex-align")

DEVICE = os.environ.get("ALIGN_DEVICE", "cpu")
MODEL = os.environ.get("ALIGN_MODEL", "small")
COMPUTE = os.environ.get("ALIGN_COMPUTE", "float16" if DEVICE == "cuda" else "int8")
DATA_DIR = Path(os.environ.get("ALIGN_DATA", "/data"))
MAPS_DIR = DATA_DIR / "maps"
MAPS_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="audex-align", version="0.1.0")
executor = ThreadPoolExecutor(max_workers=1)
jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()


def book_key(server_url: str, item_id: str) -> str:
    return hashlib.sha1(f"{server_url}|{item_id}".encode()).hexdigest()[:16]


class AbsJobRequest(BaseModel):
    serverUrl: str
    token: str
    libraryItemId: str


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


@app.get("/maps/{key}")
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
    workdir = Path(tempfile.mkdtemp(prefix="alignabs_"))
    try:
        _set(job_id, "downloading")
        base = req.serverUrl.rstrip("/")
        headers = {"Authorization": f"Bearer {req.token}"}
        with httpx.Client(headers=headers, timeout=300, follow_redirects=True) as client:
            item = client.get(f"{base}/api/items/{req.libraryItemId}", params={"expanded": 1})
            item.raise_for_status()
            media = item.json().get("media", {})
            ebook = media.get("ebookFile")
            audio_files = media.get("audioFiles", [])
            if not ebook or not audio_files:
                _set(job_id, "error", "book needs BOTH an ebook file and audio files on ABS")
                return
            epub_path = workdir / "book.epub"
            _download(client, f"{base}/api/items/{req.libraryItemId}/file/{ebook['ino']}", epub_path)
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
        segments, duration = transcribe(audio_paths, MODEL, DEVICE, COMPUTE)
        _set(job_id, "aligning", f"{len(segments)} segments vs {len(book.text)} chars")
        entries = align_segments(segments, book)
        sync_map = build_map(entries, book, duration, MODEL, DEVICE)
        (MAPS_DIR / f"{key}.json").write_text(json.dumps(sync_map))
        with jobs_lock:
            jobs[job_id]["entries"] = len(entries)
        _set(job_id, "done", f"{len(entries)} anchors over {duration:.0f}s")
    except Exception as e:  # noqa: BLE001 — job boundary
        log.exception("align job failed")
        _set(job_id, "error", str(e))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
