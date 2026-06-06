# AI Face Replacement Backend MVP

FastAPI service for the Android client processing contract.

This MVP provides an image, video, and GIF processing pipeline:

- `POST /v1/projects` accepts `source`, primary `face`, optional repeated `faces`, and JSON `request` multipart fields.
- `POST /v1/faces/detect` returns ordered source-face thumbnails for multi-face mapping.
- `GET /v1/projects/{projectId}` returns queue/progress/result state.
- `GET /v1/projects/{projectId}/result` streams the generated image, MP4 video, or GIF.
- `POST /v1/projects/{projectId}/cancel` cancels a queued/running job.
- `POST /v1/reports` records content reports.
- `/web/` serves a browser preview console for image, video, and GIF jobs.

The engine uses a neural InsightFace/ONNX face-swap model when model files are installed. Image jobs can fall back to an enhanced OpenCV compositor. Video and GIF jobs require the neural model and render frame-by-frame. Optional multi-face mode maps each ordered source face to its matching replacement face and tracks those identities across video/GIF frames to reduce mismatches. Video jobs can also be trimmed with start/end timestamps before rendering. For production/commercial use, verify the model license and keep consent/audit controls enabled.

Local MP4 output is currently visual-only unless `ffmpeg` is added and audio muxing is implemented.

## Run Locally

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-neural.txt
.\.venv\Scripts\python.exe scripts\download_models.py
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

For a USB-connected Android device, forward the phone's localhost to this machine:

```powershell
adb reverse tcp:8000 tcp:8000
```

Debug Android builds should use your PC LAN URL, for example `http://192.168.x.x:8000/`. Release builds should use an HTTPS production URL.

Check model status:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

Open the web preview from the PC or a same-Wi-Fi device:

```text
http://192.168.x.x:8000/web/
```

## Windows One-Click Bundle

Build the Windows backend/web bundle:

```powershell
powershell -ExecutionPolicy Bypass -File backend\build_windows_bundle.ps1
```

The output is:

```text
dist\windows\AI-Face-Studio-Backend\AI-Face-Studio-Backend.exe
```

Double-click the EXE to start the backend and open the web preview. Close the console window to stop processing.

## Optional Auth

Set `AI_FACE_BACKEND_TOKEN` to require `Authorization: Bearer <token>`.
