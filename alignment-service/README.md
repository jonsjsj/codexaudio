# audex-align

Forced-alignment service for Audex immersion reading (docs/09 Tier 3): takes a
book's **audio files + EPUB**, transcribes with faster-whisper, anchors the
recognition against the book text, and serves a **sync map** — audio seconds ↔
book char range / progression — that the Audex reader uses for precise (and
eventually word/sentence-level) audio-follow.

## API

| Endpoint | What |
|---|---|
| `GET /health` | device/model + job states |
| `POST /jobs/abs` `{serverUrl, token, libraryItemId}` | fetch book from ABS and align |
| `POST /jobs/upload` (multipart `epub`, `audio[]`) | align uploaded files |
| `GET /jobs/{id}` | job progress (`queued/downloading/extracting/transcribing/aligning/done/error`) |
| `GET /maps/{bookKey}` | the finished sync map |
| `POST /reports` `{kind, title, body, appVersion}` | file an in-app report as a GitHub issue |
| `GET /reports/{number}` | that report's live status `{number, state, fixedIn, url}` |

Map format v1: `{version, durationS, totalChars, entries:[{t0,t1,c0,c1,p,href}]}` —
binary-search `t` → entry → `p` (progression 0..1) / `href` for the reader jump.

## In-app reporting (the closed loop)

Same pattern as Codex's reporter — the app never embeds a GitHub token; this box
holds it and does the talking:

1. **File.** The user submits a bug/idea/feedback in-app → `POST /reports`. This
   box opens a GitHub issue on the Audex repo (labelled `audex-bug|idea|feedback`)
   and returns `{number, url}`. The build + device + screen the user came from are
   already folded into the body by the app. The app remembers the issue number
   locally under "Your reports".
2. **Triage / fix.** A maintainer (or the codex-autofix routine) works the issue.
   When the fix ships, they **close the issue** and record the release either with
   a **`fixed:<version>`** label (e.g. `fixed:0.1.6`, `fixed-in:v0.1.6`) or by
   setting the issue's **milestone** to the version.
3. **Report back.** The app polls `GET /reports/{number}` (on opening the reporter)
   and shows each report's live state: **Open → Fixed in 0.1.6 ✓** (label/milestone
   present) or **Resolved ✓** (closed with no version recorded). Served live from
   GitHub on each poll — no cron, always current.

**Config (one-time, on the box's data volume):** `DATA_DIR/report.json` —
`{"repo": "jonsjsj/codexaudio", "token": "<github PAT with `repo` issues scope>"}`.
Until that file exists, `/reports` returns 503 and the app shows
"Report service isn't set up on the server yet."

## Deploy (ventans, Docker API :2375)

CPU (default — faster-whisper `small`, int8, ~1 GB RAM while running):

```
tar -cf ctx.tar -C alignment-service .
curl -X POST --data-binary @ctx.tar -H 'Content-Type: application/x-tar' \
  'http://192.168.68.212:2375/build?t=audex-align:latest'
# create container: port 8590→8585, volume audex-align-data:/data
```

## GPU (ventans 4070 Ti) — one Windows-side step, then automated

Diagnosed 2026-07-15: ventans is **Ubuntu 22.04 in WSL2**. The Windows NVIDIA
driver is installed (nv_dispi in the driver store, libcuda mounted at
/usr/lib/wsl/lib, /dev/dxg present) **but the WSL VM booted without GPU
paravirtualization** — /sys/class/dxg never registered, `nvidia-smi` says
"GPU access blocked by the operating system", CUDA enumerates 0 devices.
Typical cause: the VM autostarts headless (session 0) under an older WSL.

**One-time fix on the ventans Windows machine (PowerShell as Administrator):**

```powershell
wsl --update
wsl --shutdown     # brief outage: Codex/audex-align/etc restart when WSL returns
wsl -d Ubuntu-22.04 -e /usr/lib/wsl/lib/nvidia-smi   # must list the 4070 Ti
```

If nvidia-smi lists the card, everything else (container toolkit install via
the Docker API, CUDA image build, service flip + benchmark) is automated —
just say the word. If it still says "blocked", the WSL autostart mechanism
needs to launch from an interactive session, or update the NVIDIA driver.

Then build `Dockerfile.cuda` (ready in this directory — CUDA 12.4 + cuDNN base,
`ALIGN_DEVICE=cuda` baked in) and recreate the container with
`"Runtime":"nvidia"` + a gpu DeviceRequest — exact build/run commands are in
the Dockerfile.cuda header. `small` aligns roughly 8-15x faster than CPU;
`medium`/`large-v3` become practical for accuracy.

## Sizing

- CPU `small` int8 on 8 cores: ≈ 0.3-0.5× realtime — a 10 h audiobook ≈ 3-5 h. Queue overnight.
- 4070 Ti `small`/`medium` float16: ≈ 10-30× realtime — the same book in minutes.
