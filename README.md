# AI Face Replacement Studio

Android 10+ Kotlin application for consent-based AI face replacement workflows across:

- Image + face image -> image
- Video + face image -> video
- GIF + face image -> GIF

The app implements the complete mobile client flow: onboarding, upload, validation, preview, processing progress, foreground notification updates, result preview, save/share, history, profile, subscription state, audit/export history, and compliance confirmation.

## Safety and Processing

The mobile app now tries the local/cloud backend first for image and video jobs. Image jobs fall back to an on-device demo compositor if the backend is unavailable. GIF jobs keep the mock completion path until a GIF frame pipeline is added.

Compliance surfaces are built in:

- Single-face validation with ML Kit
- Ownership and consent confirmation before processing
- Visible AI-generated label indicator
- Metadata watermark flags in project/export history
- Audit logs for draft creation, processing, duplication, and export
- Content reporting entry point

## Tech Stack

- Kotlin + Jetpack Compose
- MVVM-style state with Hilt ViewModels
- Room database
- WorkManager foreground processing
- Retrofit/OkHttp/Moshi API contracts
- Coil for image/GIF preview
- Media3 ExoPlayer for video preview
- ML Kit Face Detection
- Google Play Billing dependency and subscription state scaffold

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Backend Integration Points

- Debug builds use `http://127.0.0.1:8000/` and can reach the PC backend through `adb reverse tcp:8000 tcp:8000`.
- Release builds still require HTTPS; set the production `API_BASE_URL` in [app/build.gradle.kts](app/build.gradle.kts).
- [ProcessingWorker.kt](app/src/main/java/com/aifacestudio/worker/ProcessingWorker.kt) uploads source/face media through [ProcessingApi.kt](app/src/main/java/com/aifacestudio/data/remote/ProcessingApi.kt), polls the job, downloads the generated result, and stores it as a local content URI.
- Store signed result URLs in `ProjectEntity.outputUri`.
- Keep consent, AI label, and metadata watermark values in the request payload.

## Local Backend

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

The backend lives in [backend](backend). Its image and video processors use InsightFace/ONNX when models are installed; video renders frame-by-frame to MP4. The local video path is visual-only until ffmpeg-based audio passthrough is added.


## Render Free Plan Notes

The backend is designed to run as a single lightweight Render Free web service. Render Free services sleep after inactivity and their local filesystem is ephemeral. Therefore, remote job state and uploaded/result files are not guaranteed to survive a restart, redeploy, or spin-down. Avoid treating the backend's local storage as permanent storage.

- Expect a cold-start delay when the service wakes.
- Avoid deploying a separate worker or Redis requirement on the Free plan.
- Keep processing requests modest; large video jobs may exceed free-instance memory or CPU limits.
- Set `AI_FACE_BACKEND_TOKEN` in Render environment variables to enable API token validation.
- Use a real HTTPS Render URL in the release build configuration before publishing.

### Backend queue and resource controls

- Processing concurrency defaults to **1 job at a time** (`AI_FACE_MAX_CONCURRENT_JOBS=1`) to reduce peak memory/CPU usage on Render Free.
- The queue defaults to a maximum of **3 active or queued jobs** (`AI_FACE_MAX_QUEUED_JOBS=3`). Excess submissions receive HTTP 503 and can retry later.
- Jobs waiting for a processing slot remain cancellable.
- Uploaded source and donor-face files are removed after processing finishes or fails. Generated result files remain available until the instance restarts or storage is otherwise cleared.
- Failed jobs return a generic client-facing error instead of exposing internal exception details.
