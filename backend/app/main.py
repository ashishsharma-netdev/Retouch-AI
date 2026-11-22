from __future__ import annotations

import json
import os
import re
import shutil
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from threading import Lock, Semaphore

from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .models import ContentReportRequest, FaceDetectionResponse, MediaType, ProcessingJobRequest, ProcessingJobResponse, SourceFacePreview
from .processor import FaceReplacementMapping, ImageFaceProcessor, ProcessingCanceled, ProcessingOptions


MAX_IMAGE_BYTES = 20 * 1024 * 1024
MAX_GIF_BYTES = 100 * 1024 * 1024
MAX_VIDEO_BYTES = 500 * 1024 * 1024
MAX_REPLACEMENT_FACES = 12
BACKEND_ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
DEFAULT_STORAGE_ROOT = (
    Path.home() / "AI Face Studio" / "storage"
    if getattr(sys, "_MEIPASS", None)
    else Path(__file__).resolve().parents[1] / "storage"
)
STORAGE_ROOT = Path(os.getenv("AI_FACE_STORAGE", DEFAULT_STORAGE_ROOT))
WEB_ROOT = Path(os.getenv("AI_FACE_WEB_ROOT", BACKEND_ROOT / "web"))

app = FastAPI(title="AI Face Replacement Backend", version="1.0.0")
cors_origins = [origin.strip() for origin in os.getenv("AI_FACE_CORS_ORIGINS", "").split(",") if origin.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins,
    allow_methods=["GET", "POST"],
    allow_headers=["Authorization", "Content-Type"],
)
processor = ImageFaceProcessor()
jobs_lock = Lock()
MAX_CONCURRENT_JOBS = max(1, int(os.getenv('AI_FACE_MAX_CONCURRENT_JOBS', '1')))
MAX_QUEUED_JOBS = max(MAX_CONCURRENT_JOBS, int(os.getenv('AI_FACE_MAX_QUEUED_JOBS', '3')))
processing_slots = Semaphore(MAX_CONCURRENT_JOBS)


@dataclass
class JobState:
    project_id: str
    status: str
    progress: int
    status_message: str
    result_path: Path | None = None
    error: str | None = None


jobs: dict[str, JobState] = {}


def verify_auth(authorization: str | None = Header(default=None)) -> None:
    expected_token = os.getenv("AI_FACE_BACKEND_TOKEN")
    if expected_token and authorization != f"Bearer {expected_token}":
        raise HTTPException(status_code=401, detail="Invalid backend token.")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "processor": processor.engine_status()}


@app.post("/v1/projects", response_model=ProcessingJobResponse, dependencies=[Depends(verify_auth)])
async def create_processing_job(
    http_request: Request,
    background_tasks: BackgroundTasks,
    source: UploadFile = File(...),
    face: UploadFile = File(...),
    faces: list[UploadFile] = File(default=[]),
    request_json: str = Form(..., alias="request"),
) -> ProcessingJobResponse:
    try:
        payload = ProcessingJobRequest.model_validate_json(request_json)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Invalid request JSON: {exc}") from exc

    if not payload.userOwnershipDeclared or not payload.termsAccepted:
        raise HTTPException(status_code=400, detail="Ownership declaration and terms acceptance are required.")
    with jobs_lock:
        pending_jobs = sum(1 for job in jobs.values() if job.status in {'queued', 'processing'})
    if pending_jobs >= MAX_QUEUED_JOBS:
        raise HTTPException(status_code=503, detail='The processing queue is full. Please try again shortly.')

    project_id = sanitize_project_id(payload.projectId)
    with jobs_lock:
        if project_id in jobs:
            raise HTTPException(status_code=409, detail="Project id already exists.")
    project_dir = STORAGE_ROOT / "projects" / project_id
    if project_dir.exists():
        raise HTTPException(status_code=409, detail="Project id already exists.")
    project_dir.mkdir(parents=True, exist_ok=False)

    source_path = await save_upload(
        source,
        project_dir / safe_filename(source.filename or default_source_name(payload.mediaType)),
        max_bytes=max_source_bytes(payload.mediaType),
        expected_kind=payload.mediaType.value,
    )
    face_path = await save_upload(
        face,
        project_dir / safe_filename(face.filename or "face.jpg"),
        max_bytes=MAX_IMAGE_BYTES,
        expected_kind="IMAGE",
    )
    face_paths = [face_path]
    for index, extra_face in enumerate(faces[: MAX_REPLACEMENT_FACES - 1], start=1):
        face_paths.append(
            await save_upload(
                extra_face,
                project_dir / f"face_{index}_{safe_filename(extra_face.filename or 'face.jpg')}",
                max_bytes=MAX_IMAGE_BYTES,
                expected_kind="IMAGE",
            )
        )

    output_path = project_dir / output_filename(payload.mediaType)
    state = JobState(project_id=project_id, status="queued", progress=10, status_message="Waiting for available processing slot")
    with jobs_lock:
        jobs[project_id] = state

    background_tasks.add_task(run_processing_job, project_id, source_path, face_paths, output_path, payload)
    return response_for(state, http_request)


@app.post("/v1/faces/detect", response_model=FaceDetectionResponse, dependencies=[Depends(verify_auth)])
async def detect_source_faces(
    source: UploadFile = File(...),
    media_type: MediaType = Form(..., alias="mediaType"),
    video_trim_start_ms: int = Form(0, alias="videoTrimStartMs"),
) -> FaceDetectionResponse:
    tmp_dir = STORAGE_ROOT / "tmp" / sanitize_project_id(str(uuid.uuid4()))
    tmp_dir.mkdir(parents=True, exist_ok=True)
    source_path = await save_upload(
        source,
        tmp_dir / safe_filename(source.filename or default_source_name(media_type)),
        max_bytes=max_source_bytes(media_type),
        expected_kind=media_type.value,
    )
    try:
        faces = processor.detect_source_faces(
            source_path=source_path,
            media_type=media_type.value,
            video_trim_start_ms=video_trim_start_ms,
        )
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
    return FaceDetectionResponse(
        mediaType=media_type,
        faces=[
            SourceFacePreview(
                sourceFaceIndex=item.source_face_index,
                bbox=list(item.bbox),
                confidence=item.confidence,
                previewDataUrl=item.preview_data_url,
            )
            for item in faces
        ],
    )


@app.get("/v1/projects/{project_id}", response_model=ProcessingJobResponse, dependencies=[Depends(verify_auth)])
def get_processing_job(project_id: str, http_request: Request) -> ProcessingJobResponse:
    state = get_state(project_id)
    return response_for(state, http_request)


@app.post("/v1/projects/{project_id}/cancel", dependencies=[Depends(verify_auth)])
def cancel_processing_job(project_id: str) -> dict[str, str]:
    state = get_state(project_id)
    update_job(state.project_id, status="canceled", progress=state.progress, status_message="Canceled")
    return {"status": "canceled"}


@app.get("/v1/projects/{project_id}/result", name="get_result", dependencies=[Depends(verify_auth)])
def get_result(project_id: str) -> FileResponse:
    state = get_state(project_id)
    if state.status != "completed" or state.result_path is None or not state.result_path.exists():
        raise HTTPException(status_code=404, detail="Result is not available.")
    return FileResponse(
        state.result_path,
        media_type=result_media_type(state.result_path),
        filename=f"ai_generated_{state.project_id}{state.result_path.suffix}",
    )


@app.post("/v1/reports", dependencies=[Depends(verify_auth)])
def report_content(report: ContentReportRequest) -> dict[str, str]:
    reports_dir = STORAGE_ROOT / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)
    record = report.model_dump() | {"reportId": str(uuid.uuid4()), "createdAt": int(time.time() * 1000)}
    with (reports_dir / "reports.jsonl").open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=True) + "\n")
    return {"status": "received"}


def run_processing_job(
    project_id: str,
    source_path: Path,
    face_paths: list[Path],
    output_path: Path,
    payload: ProcessingJobRequest,
) -> None:
    acquired = False
    try:
        update_job(project_id, status="queued", progress=10, status_message="Waiting for available processing slot")
        while not processing_slots.acquire(timeout=1):
            if is_job_canceled(project_id):
                raise ProcessingCanceled("Processing canceled.")
        acquired = True
        if is_job_canceled(project_id):
            raise ProcessingCanceled("Processing canceled.")
        update_job(project_id, status="processing", progress=20, status_message="Preparing files")
        update_job(project_id, progress=28, status_message="Preparing AI model")
        options = ProcessingOptions(
            visible_watermark=payload.visibleWatermark,
            metadata_watermark=payload.metadataWatermark,
            jpeg_quality=quality_for(payload.quality.value),
            video_resolution=payload.videoResolution.value,
            fps=payload.fps,
            multi_face=payload.multiFace,
            face_mappings=tuple(
                FaceReplacementMapping(
                    source_face_index=mapping.sourceFaceIndex,
                    replacement_index=mapping.replacementIndex,
                )
                for mapping in payload.faceMappings
            ),
            video_trim_start_ms=payload.videoTrimStartMs,
            video_trim_end_ms=payload.videoTrimEndMs,
        )
        if payload.mediaType == MediaType.VIDEO:
            processor.process_video(
                source_path=source_path,
                face_path=face_paths[0],
                output_path=output_path,
                options=options,
                on_progress=lambda progress, message: update_job(project_id, progress=progress, status_message=message),
                should_cancel=lambda: is_job_canceled(project_id),
                face_paths=face_paths,
            )
        elif payload.mediaType == MediaType.GIF:
            processor.process_gif(
                source_path=source_path,
                face_path=face_paths[0],
                output_path=output_path,
                options=options,
                on_progress=lambda progress, message: update_job(project_id, progress=progress, status_message=message),
                should_cancel=lambda: is_job_canceled(project_id),
                face_paths=face_paths,
            )
        else:
            processor.process(
                source_path=source_path,
                face_path=face_paths[0],
                output_path=output_path,
                options=options,
                on_progress=lambda progress, message: update_job(project_id, progress=progress, status_message=message),
                should_cancel=lambda: is_job_canceled(project_id),
                face_paths=face_paths,
            )
        update_job(project_id, status="completed", progress=100, status_message="Completed", result_path=output_path)
    except ProcessingCanceled:
        update_job(project_id, status="canceled", status_message="Canceled")
    except Exception as exc:
        update_job(project_id, status="failed", progress=0, status_message="Processing failed", error="Processing failed. Please retry.")
    finally:
        for path in [source_path, *face_paths]:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        if acquired:
            processing_slots.release()


def response_for(state: JobState, http_request: Request) -> ProcessingJobResponse:
    result_url = None
    if state.status == "completed" and state.result_path is not None:
        result_url = str(http_request.url_for("get_result", project_id=state.project_id))
    return ProcessingJobResponse(
        projectId=state.project_id,
        status=state.status,
        progress=state.progress,
        statusMessage=state.error or state.status_message,
        resultUrl=result_url,
        signedDownloadUrl=result_url,
    )


def get_state(project_id: str) -> JobState:
    with jobs_lock:
        state = jobs.get(sanitize_project_id(project_id))
    if state is None:
        raise HTTPException(status_code=404, detail="Project not found.")
    return state


def is_job_canceled(project_id: str) -> bool:
    with jobs_lock:
        state = jobs.get(project_id)
        return state is not None and state.status == "canceled"


def update_job(
    project_id: str,
    status: str | None = None,
    progress: int | None = None,
    status_message: str | None = None,
    result_path: Path | None = None,
    error: str | None = None,
) -> None:
    with jobs_lock:
        state = jobs[project_id]
        if state.status == "canceled":
            return
        if status is not None:
            state.status = status
        if progress is not None:
            state.progress = max(0, min(100, progress))
        if status_message is not None:
            state.status_message = status_message
        if result_path is not None:
            state.result_path = result_path
        if error is not None:
            state.error = error


async def save_upload(upload: UploadFile, destination: Path, max_bytes: int, expected_kind: str) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    try:
        with destination.open("xb") as output:
            while True:
                chunk = await upload.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise HTTPException(status_code=413, detail="Upload exceeds the supported size limit.")
                output.write(chunk)
        validate_upload_content(destination, expected_kind)
        return destination
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def validate_upload_content(path: Path, expected_kind: str) -> None:
    """Validate actual file contents, not just the client-supplied filename/MIME."""
    if expected_kind in {"IMAGE", "GIF"}:
        try:
            from PIL import Image
            with Image.open(path) as image:
                detected = image.format
                allowed = {"JPEG", "PNG", "WEBP"} if expected_kind == "IMAGE" else {"GIF"}
                if detected not in allowed:
                    raise HTTPException(status_code=415, detail="Unsupported image file content.")
                if image.width * image.height > 40_000_000:
                    raise HTTPException(status_code=413, detail="Image dimensions exceed the supported limit.")
                image.verify()
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=415, detail="Invalid or unsupported image file.") from exc
        return

    if expected_kind == "VIDEO":
        with path.open("rb") as handle:
            header = handle.read(64)
        if len(header) < 12 or header[4:8] != b"ftyp":
            raise HTTPException(status_code=415, detail="Unsupported video file content.")
        return

    raise HTTPException(status_code=400, detail="Unsupported upload type.")


def max_source_bytes(media_type: MediaType) -> int:
    if media_type == MediaType.VIDEO:
        return MAX_VIDEO_BYTES
    if media_type == MediaType.GIF:
        return MAX_GIF_BYTES
    return MAX_IMAGE_BYTES


def default_source_name(media_type: MediaType) -> str:
    if media_type == MediaType.VIDEO:
        return "source.mp4"
    if media_type == MediaType.GIF:
        return "source.gif"
    return "source.jpg"


def output_filename(media_type: MediaType) -> str:
    if media_type == MediaType.VIDEO:
        return "result.mp4"
    if media_type == MediaType.GIF:
        return "result.gif"
    return "result.jpg"


def result_media_type(path: Path) -> str:
    if path.suffix.lower() == ".mp4":
        return "video/mp4"
    if path.suffix.lower() == ".gif":
        return "image/gif"
    return "image/jpeg"


def quality_for(quality: str) -> int:
    return {
        "STANDARD": 86,
        "HIGH": 92,
        "ULTRA": 96,
    }.get(quality, 92)


def sanitize_project_id(project_id: str) -> str:
    clean = re.sub(r"[^A-Za-z0-9_.-]", "_", project_id)
    if not clean:
        raise HTTPException(status_code=400, detail="Invalid project id.")
    return clean[:128]


def safe_filename(filename: str) -> str:
    clean = re.sub(r"[^A-Za-z0-9_.-]", "_", filename)
    return clean[:120] or "upload.bin"


@app.on_event("startup")
def ensure_storage() -> None:
    STORAGE_ROOT.mkdir(parents=True, exist_ok=True)


@app.on_event("shutdown")
def close_uploads() -> None:
    shutil.rmtree(STORAGE_ROOT / "tmp", ignore_errors=True)


if WEB_ROOT.exists():
    app.mount("/web", StaticFiles(directory=WEB_ROOT, html=True), name="web")


@app.get("/", include_in_schema=False)
def web_preview() -> FileResponse:
    index_path = WEB_ROOT / "index.html"
    if not index_path.exists():
        raise HTTPException(status_code=404, detail="Web preview is not installed.")
    return FileResponse(index_path)
