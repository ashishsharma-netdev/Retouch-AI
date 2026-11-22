from __future__ import annotations

import base64
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Callable

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps, ImageSequence


ProgressCallback = Callable[[int, str], None]
CancelCallback = Callable[[], bool]


class ProcessingCanceled(RuntimeError):
    pass


@dataclass(frozen=True)
class FaceReplacementMapping:
    source_face_index: int
    replacement_index: int


@dataclass(frozen=True)
class ProcessingOptions:
    visible_watermark: bool = True
    metadata_watermark: bool = True
    jpeg_quality: int = 92
    video_resolution: str = "P1080"
    fps: int = 30
    multi_face: bool = False
    face_mappings: tuple[FaceReplacementMapping, ...] = ()
    video_trim_start_ms: int = 0
    video_trim_end_ms: int = 0


@dataclass(frozen=True)
class DetectedSourceFace:
    source_face_index: int
    bbox: tuple[int, int, int, int]
    confidence: float
    preview_data_url: str


class ImageFaceProcessor:
    """Image engine used by the backend.

    If an InsightFace/ONNX swapper model is installed, this class uses the
    neural path. Otherwise it falls back to an OpenCV/Pillow compositor so the
    API stays usable while model files are being provisioned.
    """

    def __init__(self) -> None:
        self._neural_lock = Lock()
        self._neural_processor: NeuralFaceSwapProcessor | None = None
        self._neural_model_path: Path | None = None

    def process(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
        should_cancel: CancelCallback | None = None,
        face_paths: list[Path] | None = None,
    ) -> None:
        check_cancel(should_cancel)
        resolved_face_paths = face_paths or [face_path]
        neural = self._get_neural_processor()
        if neural is not None:
            try:
                neural.process(source_path, face_path, output_path, options, on_progress, should_cancel, resolved_face_paths)
                return
            except ProcessingCanceled:
                raise
            except Exception as exc:
                on_progress(34, f"Neural swap failed; running enhanced blend ({exc})")

        check_cancel(should_cancel)
        self._process_enhanced_composite(source_path, face_path, output_path, options, on_progress)

    def process_video(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
        should_cancel: CancelCallback | None = None,
        face_paths: list[Path] | None = None,
    ) -> None:
        neural = self._get_neural_processor()
        if neural is None:
            raise ValueError("Video face replacement requires the neural ONNX swapper model.")
        neural.process_video(source_path, face_path, output_path, options, on_progress, should_cancel, face_paths or [face_path])

    def process_gif(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
        should_cancel: CancelCallback | None = None,
        face_paths: list[Path] | None = None,
    ) -> None:
        neural = self._get_neural_processor()
        if neural is None:
            raise ValueError("GIF face replacement requires the neural ONNX swapper model.")
        neural.process_gif(source_path, face_path, output_path, options, on_progress, should_cancel, face_paths or [face_path])

    def detect_source_faces(
        self,
        source_path: Path,
        media_type: str,
        video_trim_start_ms: int = 0,
    ) -> list[DetectedSourceFace]:
        neural = self._get_neural_processor()
        if neural is not None:
            return neural.detect_source_faces(source_path, media_type, video_trim_start_ms)
        if media_type != "IMAGE":
            return []
        return detect_source_faces_with_haar(source_path)

    def engine_status(self) -> dict[str, str | bool]:
        model_path = resolve_swapper_model_path()
        return {
            "neuralAvailable": model_path is not None,
            "swapperModel": str(model_path) if model_path else "",
            "detectorPack": os.getenv("AI_FACE_DETECTOR_PACK", "buffalo_l"),
            "modelRoot": str(model_root()),
        }

    def _get_neural_processor(self) -> "NeuralFaceSwapProcessor | None":
        if os.getenv("AI_FACE_USE_NEURAL", "auto").lower() in {"0", "false", "off", "no"}:
            return None
        model_path = resolve_swapper_model_path()
        if model_path is None:
            return None
        with self._neural_lock:
            if self._neural_processor is None or self._neural_model_path != model_path:
                self._neural_processor = NeuralFaceSwapProcessor(model_path=model_path)
                self._neural_model_path = model_path
            return self._neural_processor

    def _process_enhanced_composite(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
    ) -> None:
        if self._process_with_opencv(source_path, face_path, output_path, options, on_progress):
            return
        self._process_with_pillow(source_path, face_path, output_path, options, on_progress)

    def _process_with_opencv(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
    ) -> bool:
        try:
            import cv2  # type: ignore
            import numpy as np  # type: ignore
        except Exception:
            return False

        source = cv2.imread(str(source_path), cv2.IMREAD_COLOR)
        replacement = cv2.imread(str(face_path), cv2.IMREAD_COLOR)
        if source is None or replacement is None:
            return False

        on_progress(36, "Analyzing faces")
        source = limit_bgr_edge(source, 2048)
        replacement = limit_bgr_edge(replacement, 1024)
        source_box = self._detect_face_box_bgr(source)
        replacement_box = self._detect_face_box_bgr(replacement)

        on_progress(62, "Matching pose and lighting")
        face_crop = replacement[replacement_box[1]:replacement_box[3], replacement_box[0]:replacement_box[2]]
        target_width = max(1, source_box[2] - source_box[0])
        target_height = max(1, source_box[3] - source_box[1])
        interpolation = cv2.INTER_CUBIC if face_crop.shape[1] < target_width else cv2.INTER_AREA
        face_layer = cv2.resize(face_crop, (target_width, target_height), interpolation=interpolation)
        target_roi = source[source_box[1]:source_box[3], source_box[0]:source_box[2]]

        mask = np.zeros((target_height, target_width), dtype=np.uint8)
        inset_x = max(2, round(target_width * 0.035))
        inset_y = max(2, round(target_height * 0.04))
        cv2.ellipse(
            mask,
            (target_width // 2, target_height // 2),
            (max(1, target_width // 2 - inset_x), max(1, target_height // 2 - inset_y)),
            0,
            0,
            360,
            255,
            -1,
        )
        face_layer = match_color_statistics(face_layer, target_roi, mask)

        on_progress(76, "Seamlessly blending face")
        center = (source_box[0] + target_width // 2, source_box[1] + target_height // 2)
        try:
            result = cv2.seamlessClone(face_layer, source, mask, center, cv2.MIXED_CLONE)
        except Exception:
            feather = cv2.GaussianBlur(mask, (0, 0), sigmaX=max(2, min(target_width, target_height) * 0.035))
            alpha = (feather.astype(np.float32) / 255.0)[..., None]
            result = source.copy()
            blended = face_layer.astype(np.float32) * alpha + target_roi.astype(np.float32) * (1.0 - alpha)
            result[source_box[1]:source_box[3], source_box[0]:source_box[2]] = np.clip(blended, 0, 255).astype(np.uint8)

        result = sharpen_bgr(result)
        save_bgr_output(result, output_path, options, processor_name="opencv-seamless-compositor")
        return True

    def _process_with_pillow(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
    ) -> None:
        on_progress(35, "Decoding images")
        source = Image.open(source_path).convert("RGBA")
        replacement = Image.open(face_path).convert("RGBA")

        source = self._limit_edge(source, 2048)
        replacement = self._limit_edge(replacement, 1024)

        on_progress(50, "Analyzing faces")
        source_box = self._detect_face_box(source)
        replacement_box = self._detect_face_box(replacement)

        on_progress(68, "Rendering output")
        face_crop = replacement.crop(replacement_box)
        target_size = (max(1, source_box[2] - source_box[0]), max(1, source_box[3] - source_box[1]))
        face_layer = ImageOps.fit(face_crop, target_size, method=Image.Resampling.LANCZOS)

        mask = Image.new("L", target_size, 0)
        mask_draw = ImageDraw.Draw(mask)
        inset_x = max(2, round(target_size[0] * 0.03))
        inset_y = max(2, round(target_size[1] * 0.03))
        mask_draw.ellipse(
            (inset_x, inset_y, target_size[0] - inset_x, target_size[1] - inset_y),
            fill=230,
        )
        mask = mask.filter(ImageFilter.GaussianBlur(radius=max(2, round(min(target_size) * 0.035))))

        result = source.copy()
        result.paste(face_layer, (source_box[0], source_box[1]), mask)

        if options.visible_watermark:
            draw_watermark(result)

        on_progress(86, "Writing AI generated output")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        save_kwargs = {"quality": options.jpeg_quality, "optimize": True}
        if options.metadata_watermark:
            save_kwargs["comment"] = b"AI_GENERATED=true; processor=pillow-compositor"
        result.convert("RGB").save(output_path, format="JPEG", **save_kwargs)

    def _limit_edge(self, image: Image.Image, max_edge: int) -> Image.Image:
        width, height = image.size
        long_edge = max(width, height)
        if long_edge <= max_edge:
            return image
        scale = max_edge / long_edge
        size = (max(1, round(width * scale)), max(1, round(height * scale)))
        return image.resize(size, Image.Resampling.LANCZOS)

    def _detect_face_box(self, image: Image.Image) -> tuple[int, int, int, int]:
        detected = self._detect_face_box_with_opencv(image)
        if detected is not None:
            return self._expand_box(detected, image.size, width_scale=1.22, height_scale=1.32)
        return self._center_face_box(image.size)

    def _detect_face_box_bgr(self, image) -> tuple[int, int, int, int]:
        import cv2  # type: ignore

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        gray = cv2.equalizeHist(gray)
        cascade_path = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        detector = cv2.CascadeClassifier(cascade_path)
        faces = detector.detectMultiScale(gray, scaleFactor=1.06, minNeighbors=5, minSize=(40, 40))
        if len(faces) == 0:
            return self._center_face_box((image.shape[1], image.shape[0]))
        x, y, width, height = max(faces, key=lambda item: item[2] * item[3])
        return self._expand_box(
            (int(x), int(y), int(x + width), int(y + height)),
            (image.shape[1], image.shape[0]),
            width_scale=1.18,
            height_scale=1.26,
        )

    def _detect_face_box_with_opencv(self, image: Image.Image) -> tuple[int, int, int, int] | None:
        try:
            import cv2  # type: ignore
            import numpy as np  # type: ignore
        except Exception:
            return None

        rgb = image.convert("RGB")
        gray = cv2.cvtColor(np.array(rgb), cv2.COLOR_RGB2GRAY)
        cascade_path = cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        detector = cv2.CascadeClassifier(cascade_path)
        faces = detector.detectMultiScale(gray, scaleFactor=1.08, minNeighbors=5, minSize=(48, 48))
        if len(faces) == 0:
            return None
        x, y, width, height = max(faces, key=lambda item: item[2] * item[3])
        return int(x), int(y), int(x + width), int(y + height)

    def _center_face_box(self, size: tuple[int, int]) -> tuple[int, int, int, int]:
        width, height = size
        box_width = round(width * 0.38)
        box_height = round(height * 0.46)
        left = round((width - box_width) / 2)
        top = round(height * 0.18)
        return self._clamp_box((left, top, left + box_width, top + box_height), size)

    def _expand_box(
        self,
        box: tuple[int, int, int, int],
        size: tuple[int, int],
        width_scale: float,
        height_scale: float,
    ) -> tuple[int, int, int, int]:
        left, top, right, bottom = box
        center_x = (left + right) / 2
        center_y = (top + bottom) / 2
        new_width = max(1, round((right - left) * width_scale))
        new_height = max(1, round((bottom - top) * height_scale))
        expanded = (
            round(center_x - new_width / 2),
            round(center_y - new_height / 2),
            round(center_x + new_width / 2),
            round(center_y + new_height / 2),
        )
        return self._clamp_box(expanded, size)

    def _clamp_box(self, box: tuple[int, int, int, int], size: tuple[int, int]) -> tuple[int, int, int, int]:
        width, height = size
        left = min(max(0, box[0]), width - 1)
        top = min(max(0, box[1]), height - 1)
        right = min(max(left + 1, box[2]), width)
        bottom = min(max(top + 1, box[3]), height)
        return left, top, right, bottom


class NeuralFaceSwapProcessor:
    def __init__(self, model_path: Path) -> None:
        self.model_path = model_path
        self._lock = Lock()
        self._face_analyser = None
        self._swapper = None

    def process(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
        should_cancel: CancelCallback | None = None,
        face_paths: list[Path] | None = None,
    ) -> None:
        import cv2  # type: ignore

        on_progress(34, "Loading neural face model")
        check_cancel(should_cancel)
        face_analyser, swapper = self._models()
        source = cv2.imread(str(source_path), cv2.IMREAD_COLOR)
        if source is None:
            raise ValueError("Image could not be decoded for neural processing.")

        source = limit_bgr_edge(source, 2048)

        on_progress(48, "Detecting face geometry")
        check_cancel(should_cancel)
        target_faces = face_analyser.get(source)
        donor_faces = self._resolve_donor_faces(face_analyser, face_paths or [face_path])
        if not target_faces:
            raise ValueError("No face detected in source image.")
        if not donor_faces:
            raise ValueError("No face detected in face image.")

        target_faces = (
            ordered_image_faces(filter_target_faces(target_faces, source.shape) or target_faces)
            if options.multi_face
            else [largest_insightface_face(target_faces)]
        )
        assignments = image_face_assignments(target_faces, donor_faces, options)
        if not assignments:
            raise ValueError("No source face has an assigned replacement face.")
        on_progress(68, "Running neural multi-face swap" if options.multi_face else "Running neural face swap")
        result = source
        swapped = 0
        for target_face, donor_face in assignments:
            check_cancel(should_cancel)
            result = swapper.get(result, target_face, donor_face, paste_back=True)
            swapped += 1
        result = sharpen_bgr(result)

        on_progress(88, "Writing neural output")
        save_bgr_output(
            result,
            output_path,
            options,
            processor_name="insightface-inswapper-multiface" if options.multi_face else "insightface-inswapper",
        )

    def process_video(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
        should_cancel: CancelCallback | None = None,
        face_paths: list[Path] | None = None,
    ) -> None:
        import cv2  # type: ignore

        on_progress(32, "Loading neural video model")
        check_cancel(should_cancel)
        face_analyser, swapper = self._models()
        donor_faces = self._resolve_donor_faces(face_analyser, face_paths or [face_path])

        capture = cv2.VideoCapture(str(source_path))
        if not capture.isOpened():
            raise ValueError("Source video could not be opened.")

        input_fps = capture.get(cv2.CAP_PROP_FPS)
        if input_fps <= 1 or input_fps > 120:
            input_fps = float(options.fps or 30)
        frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        max_video_seconds = max(1, int(os.getenv("AI_FACE_MAX_VIDEO_SECONDS", "120")))
        if frame_count > 0 and frame_count / input_fps > max_video_seconds:
            capture.release()
            raise ValueError(f"Video exceeds the {max_video_seconds}-second processing limit. Trim the video and retry.")
        start_frame = frame_index_for_ms(options.video_trim_start_ms, input_fps)
        end_frame = frame_index_for_ms(options.video_trim_end_ms, input_fps) if options.video_trim_end_ms > 0 else frame_count
        if frame_count > 0:
            start_frame = min(start_frame, max(0, frame_count - 1))
            end_frame = min(end_frame, frame_count) if end_frame > 0 else frame_count
        if end_frame > 0 and end_frame <= start_frame:
            raise ValueError("Video trim end time must be after the start time.")
        if start_frame > 0:
            capture.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
            on_progress(34, f"Seeking video to {format_ms(options.video_trim_start_ms)}")
        trimmed_frame_count = max(0, end_frame - start_frame) if end_frame > 0 else frame_count

        success, frame = capture.read()
        if not success or frame is None:
            capture.release()
            raise ValueError("Source video does not contain readable frames.")

        frame = resize_frame_for_resolution(frame, options.video_resolution)
        output_size = (frame.shape[1], frame.shape[0])
        output_path.parent.mkdir(parents=True, exist_ok=True)
        temp_output = output_path.with_suffix(".video.tmp.mp4")
        writer = open_video_writer(temp_output, input_fps, output_size)

        processed = 0
        swapped = 0
        previous_face = None
        tracker = MultiFaceTracker() if options.multi_face else None
        last_progress = -1
        try:
            while True:
                check_cancel(should_cancel)
                if end_frame > 0 and processed >= trimmed_frame_count:
                    break
                if processed > 0:
                    success, frame = capture.read()
                    if not success or frame is None:
                        break
                    if options.video_trim_end_ms > 0 and frame_count <= 0 and capture.get(cv2.CAP_PROP_POS_MSEC) > options.video_trim_end_ms:
                        break
                    frame = resize_frame_for_resolution(frame, options.video_resolution)

                output_frame = frame
                target_faces = face_analyser.get(frame)
                if target_faces:
                    if tracker is not None:
                        target_faces = filter_target_faces(target_faces, frame.shape) or target_faces
                        selected_faces = tracker.assign(target_faces)
                    else:
                        selected_face = select_video_target_face(target_faces, previous_face)
                        previous_face = selected_face
                        selected_faces = [(0, selected_face)]
                    frame_swapped = 0
                    for source_face_index, target_face in selected_faces:
                        check_cancel(should_cancel)
                        donor_index = donor_index_for_source_index(source_face_index, donor_faces, options)
                        if donor_index is None:
                            continue
                        try:
                            output_frame = swapper.get(output_frame, target_face, donor_faces[donor_index], paste_back=True)
                            frame_swapped += 1
                        except Exception:
                            continue
                    if frame_swapped > 0:
                        output_frame = sharpen_bgr(output_frame)
                        swapped += frame_swapped

                if options.visible_watermark:
                    draw_watermark_bgr(output_frame)
                writer.write(output_frame)
                processed += 1

                if trimmed_frame_count > 0:
                    progress = 38 + int(min(processed, trimmed_frame_count) * 52 / trimmed_frame_count)
                    if progress != last_progress and (processed == 1 or processed % 5 == 0 or progress >= 90):
                        last_progress = progress
                        on_progress(progress, f"Rendering video frames {processed}/{trimmed_frame_count}")
                elif processed % 10 == 0:
                    on_progress(62, f"Rendering video frames {processed}")

            if processed == 0:
                raise ValueError("No frames were processed.")
        finally:
            capture.release()
            writer.release()

        if not temp_output.exists() or temp_output.stat().st_size == 0:
            raise ValueError("Video encoder did not produce an output file.")
        temp_output.replace(output_path)
        if options.metadata_watermark:
            write_video_sidecar(
                output_path,
                processor_name="insightface-inswapper-multiface" if options.multi_face else "insightface-inswapper",
                frames=processed,
                swapped=swapped,
            )
        face_label = "face swaps" if options.multi_face else "frames swapped"
        on_progress(92, f"Finalized video ({swapped}/{processed} {face_label})")

    def process_gif(
        self,
        source_path: Path,
        face_path: Path,
        output_path: Path,
        options: ProcessingOptions,
        on_progress: ProgressCallback,
        should_cancel: CancelCallback | None = None,
        face_paths: list[Path] | None = None,
    ) -> None:
        on_progress(32, "Loading neural GIF model")
        check_cancel(should_cancel)
        face_analyser, swapper = self._models()

        donor_faces = self._resolve_donor_faces(face_analyser, face_paths or [face_path])

        try:
            source_image = Image.open(source_path)
        except Exception as exc:
            raise ValueError("Source GIF could not be opened.") from exc

        frame_count = int(getattr(source_image, "n_frames", 1) or 1)
        max_gif_frames = max(1, int(os.getenv("AI_FACE_MAX_GIF_FRAMES", "120")))
        if frame_count > max_gif_frames:
            source_image.close()
            raise ValueError(f"GIF exceeds the {max_gif_frames}-frame processing limit. Shorten the GIF and retry.")
        loop = int(source_image.info.get("loop", 0) or 0)
        default_duration = int(source_image.info.get("duration", 80) or 80)
        processed_frames: list[Image.Image] = []
        durations: list[int] = []
        processed = 0
        swapped = 0
        previous_face = None
        tracker = MultiFaceTracker() if options.multi_face else None
        last_progress = -1

        try:
            for index, frame in enumerate(ImageSequence.Iterator(source_image)):
                check_cancel(should_cancel)
                duration = int(frame.info.get("duration", default_duration) or default_duration)
                frame_bgr = pil_frame_to_bgr(frame)
                output_frame = frame_bgr

                target_faces = face_analyser.get(frame_bgr)
                if target_faces:
                    if tracker is not None:
                        target_faces = filter_target_faces(target_faces, frame_bgr.shape) or target_faces
                        selected_faces = tracker.assign(target_faces)
                    else:
                        selected_face = select_video_target_face(target_faces, previous_face)
                        previous_face = selected_face
                        selected_faces = [(0, selected_face)]
                    frame_swapped = 0
                    for source_face_index, target_face in selected_faces:
                        check_cancel(should_cancel)
                        donor_index = donor_index_for_source_index(source_face_index, donor_faces, options)
                        if donor_index is None:
                            continue
                        try:
                            output_frame = swapper.get(output_frame, target_face, donor_faces[donor_index], paste_back=True)
                            frame_swapped += 1
                        except Exception:
                            continue
                    if frame_swapped > 0:
                        output_frame = sharpen_bgr(output_frame)
                        swapped += frame_swapped

                if options.visible_watermark:
                    draw_watermark_bgr(output_frame)

                processed_frames.append(bgr_to_palette_gif_frame(output_frame))
                durations.append(max(20, duration))
                processed += 1

                progress = 38 + int(min(index + 1, frame_count) * 52 / frame_count)
                if progress != last_progress and (index == 0 or (index + 1) % 3 == 0 or progress >= 90):
                    last_progress = progress
                    on_progress(progress, f"Rendering GIF frames {index + 1}/{frame_count}")
        finally:
            source_image.close()

        if not processed_frames:
            raise ValueError("Source GIF does not contain readable frames.")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        temp_output = output_path.with_suffix(".gif.tmp.gif")
        save_kwargs = {
            "save_all": True,
            "append_images": processed_frames[1:],
            "duration": durations,
            "loop": loop,
            "optimize": True,
            "disposal": 2,
        }
        if options.metadata_watermark:
            save_kwargs["comment"] = b"AI_GENERATED=true; processor=insightface-inswapper"
        processed_frames[0].save(temp_output, format="GIF", **save_kwargs)
        temp_output.replace(output_path)
        if options.metadata_watermark:
            write_video_sidecar(
                output_path,
                processor_name="insightface-inswapper-multiface" if options.multi_face else "insightface-inswapper",
                frames=processed,
                swapped=swapped,
            )
        face_label = "face swaps" if options.multi_face else "frames swapped"
        on_progress(92, f"Finalized GIF ({swapped}/{processed} {face_label})")

    def detect_source_faces(
        self,
        source_path: Path,
        media_type: str,
        video_trim_start_ms: int = 0,
    ) -> list[DetectedSourceFace]:
        face_analyser, _ = self._models()
        frame = self._best_detection_frame(face_analyser, source_path, media_type, video_trim_start_ms)
        if frame is None:
            return []
        raw_faces = face_analyser.get(frame)
        faces = filter_target_faces(raw_faces, frame.shape) or raw_faces
        return detected_faces_from_frame(frame, faces)

    def _models(self):
        with self._lock:
            if self._face_analyser is None:
                from insightface.app import FaceAnalysis  # type: ignore
                from insightface.model_zoo import get_model  # type: ignore

                providers = onnx_providers()
                analyser = FaceAnalysis(
                    name=os.getenv("AI_FACE_DETECTOR_PACK", "buffalo_l"),
                    root=str(model_root()),
                    allowed_modules=["detection", "recognition"],
                    providers=providers,
                )
                det_size = max(320, min(1024, int(os.getenv("AI_FACE_DETECT_SIZE", "640"))))
                analyser.prepare(ctx_id=-1, det_thresh=0.35, det_size=(det_size, det_size))
                swapper = get_model(str(self.model_path), providers=providers)
                if swapper is None:
                    raise ValueError(f"Face swap model could not be recognized: {self.model_path}")
                self._face_analyser = analyser
                self._swapper = swapper
            return self._face_analyser, self._swapper

    def _best_detection_frame(self, face_analyser, source_path: Path, media_type: str, video_trim_start_ms: int):
        import cv2  # type: ignore

        candidates = []
        if media_type == "IMAGE":
            frame = cv2.imread(str(source_path), cv2.IMREAD_COLOR)
            if frame is not None:
                candidates.append(limit_bgr_edge(frame, 1600))
        elif media_type == "VIDEO":
            capture = cv2.VideoCapture(str(source_path))
            if capture.isOpened():
                fps = capture.get(cv2.CAP_PROP_FPS)
                if fps <= 1 or fps > 120:
                    fps = 30
                start_frame = frame_index_for_ms(video_trim_start_ms, fps)
                if start_frame > 0:
                    capture.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
                sample_stride = max(1, int(fps // 3))
                for index in range(24):
                    success, frame = capture.read()
                    if not success or frame is None:
                        break
                    if index % sample_stride == 0 or index < 3:
                        candidates.append(resize_frame_for_resolution(frame, "P1080"))
                capture.release()
        elif media_type == "GIF":
            try:
                source_image = Image.open(source_path)
                for index, frame in enumerate(ImageSequence.Iterator(source_image)):
                    if index >= 18:
                        break
                    candidates.append(pil_frame_to_bgr(frame))
                source_image.close()
            except Exception:
                return None

        best_frame = None
        best_score = -1.0
        for frame in candidates:
            raw_faces = face_analyser.get(frame)
            faces = filter_target_faces(raw_faces, frame.shape) or raw_faces
            score = float(len(faces) * 100000) + sum(face_quality(face) for face in faces)
            if faces and score > best_score:
                best_score = score
                best_frame = frame
        return best_frame

    def _resolve_donor_faces(self, face_analyser, face_paths: list[Path]):
        import cv2  # type: ignore

        donor_faces = []
        for index, face_path in enumerate(face_paths[:12]):
            donor = cv2.imread(str(face_path), cv2.IMREAD_COLOR)
            if donor is None:
                raise ValueError(f"Replacement face {index + 1} could not be decoded.")
            donor = limit_bgr_edge(donor, 1024)
            donor_faces.append(self._resolve_donor_face(face_analyser, donor))
        if not donor_faces:
            raise ValueError("At least one replacement face image is required.")
        return donor_faces

    def _resolve_donor_face(self, face_analyser, donor):
        donor_faces = face_analyser.get(donor)
        if not donor_faces:
            donor_faces = face_analyser.get(upscale_for_detection(donor))
        if not donor_faces:
            return donor_face_from_center_crop(face_analyser, donor)
        return largest_insightface_face(donor_faces)


def model_root() -> Path:
    default_root = runtime_backend_root() / "model_store"
    return Path(os.getenv("AI_FACE_MODEL_ROOT", default_root)).expanduser()


def resolve_swapper_model_path() -> Path | None:
    configured = os.getenv("AI_FACE_SWAPPER_MODEL")
    candidates = []
    if configured:
        candidates.append(Path(configured).expanduser())
    root = model_root()
    backend_root = Path(__file__).resolve().parents[1]
    candidates.extend(
        [
            root / "models" / "inswapper_128" / "inswapper_128.onnx",
            root / "models" / "inswapper_128.onnx",
            backend_root / "models" / "inswapper_128.onnx",
        ]
    )
    for candidate in candidates:
        if candidate.exists() and candidate.is_file():
            return candidate
    return None


def runtime_backend_root() -> Path:
    bundled_root = getattr(sys, "_MEIPASS", None)
    if bundled_root:
        return Path(bundled_root)
    return Path(__file__).resolve().parents[1]


def onnx_providers() -> list[str]:
    configured = os.getenv("AI_FACE_ONNX_PROVIDERS")
    if configured:
        return [item.strip() for item in configured.split(",") if item.strip()]
    try:
        import onnxruntime  # type: ignore

        available = onnxruntime.get_available_providers()
        if "CUDAExecutionProvider" in available:
            return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    except Exception:
        pass
    return ["CPUExecutionProvider"]


def largest_insightface_face(faces):
    def area(face) -> float:
        left, top, right, bottom = face.bbox
        return float(max(0, right - left) * max(0, bottom - top))

    return max(faces, key=area)


def ordered_image_faces(faces):
    return sorted(faces, key=lambda face: (face_center(face)[0], face_center(face)[1]))


def explicit_mapping(options: ProcessingOptions, donor_count: int) -> dict[int, int]:
    mapping: dict[int, int] = {}
    for item in options.face_mappings:
        if 0 <= item.replacement_index < donor_count:
            mapping[item.source_face_index] = item.replacement_index
    return mapping


def donor_index_for_source_index(source_face_index: int, donor_faces: list, options: ProcessingOptions) -> int | None:
    donor_count = len(donor_faces)
    mapping = explicit_mapping(options, donor_count)
    if mapping:
        return mapping.get(source_face_index)
    if not options.multi_face:
        return 0 if source_face_index == 0 and donor_count > 0 else None
    if donor_count == 1:
        return 0
    return source_face_index if source_face_index < donor_count else None


def image_face_assignments(target_faces: list, donor_faces: list, options: ProcessingOptions) -> list[tuple[object, object]]:
    assignments = []
    for source_face_index, target_face in enumerate(target_faces):
        donor_index = donor_index_for_source_index(source_face_index, donor_faces, options)
        if donor_index is not None:
            assignments.append((target_face, donor_faces[donor_index]))
    return assignments


def filter_target_faces(faces, image_shape) -> list:
    height, width = image_shape[:2]
    frame_area = max(1.0, float(width * height))
    min_area = max(900.0, frame_area * 0.012)
    filtered = []
    for face in faces:
        left, top, right, bottom = face.bbox
        area = max(0.0, float(right - left)) * max(0.0, float(bottom - top))
        score = float(getattr(face, "det_score", 1.0) or 0.0)
        if score >= 0.55 and area >= min_area:
            filtered.append(face)
    return suppress_overlapping_faces(filtered)


def suppress_overlapping_faces(faces) -> list:
    selected = []
    for face in sorted(faces, key=lambda item: face_quality(item), reverse=True):
        if all(face_iou(face, existing) < 0.42 for existing in selected):
            selected.append(face)
    return ordered_image_faces(selected)


def face_quality(face) -> float:
    left, top, right, bottom = face.bbox
    area = max(0.0, float(right - left)) * max(0.0, float(bottom - top))
    return area * float(getattr(face, "det_score", 1.0) or 0.0)


def face_iou(left_face, right_face) -> float:
    left_a, top_a, right_a, bottom_a = [float(value) for value in left_face.bbox]
    left_b, top_b, right_b, bottom_b = [float(value) for value in right_face.bbox]
    inter_left = max(left_a, left_b)
    inter_top = max(top_a, top_b)
    inter_right = min(right_a, right_b)
    inter_bottom = min(bottom_a, bottom_b)
    inter_area = max(0.0, inter_right - inter_left) * max(0.0, inter_bottom - inter_top)
    area_a = max(0.0, right_a - left_a) * max(0.0, bottom_a - top_a)
    area_b = max(0.0, right_b - left_b) * max(0.0, bottom_b - top_b)
    denominator = area_a + area_b - inter_area
    return inter_area / denominator if denominator > 0 else 0.0


def select_video_target_face(faces, previous_face):
    if previous_face is None:
        return largest_insightface_face(faces)

    previous_center = face_center(previous_face)
    return min(
        faces,
        key=lambda face: (
            (face_center(face)[0] - previous_center[0]) ** 2
            + (face_center(face)[1] - previous_center[1]) ** 2
        ),
    )


def face_center(face) -> tuple[float, float]:
    left, top, right, bottom = face.bbox
    return float((left + right) / 2), float((top + bottom) / 2)


def face_diagonal(face) -> float:
    left, top, right, bottom = face.bbox
    width = max(1.0, float(right - left))
    height = max(1.0, float(bottom - top))
    return (width * width + height * height) ** 0.5


def face_embedding(face):
    embedding = getattr(face, "normed_embedding", None)
    if embedding is None:
        embedding = getattr(face, "embedding", None)
    return embedding


def embedding_distance(left_face, right_face) -> float:
    import numpy as np  # type: ignore

    left = face_embedding(left_face)
    right = face_embedding(right_face)
    if left is None or right is None:
        return 1.0
    left_arr = np.asarray(left, dtype=np.float32)
    right_arr = np.asarray(right, dtype=np.float32)
    denominator = float(np.linalg.norm(left_arr) * np.linalg.norm(right_arr))
    if denominator <= 1e-6:
        return 1.0
    similarity = float(np.dot(left_arr, right_arr) / denominator)
    return 1.0 - max(-1.0, min(1.0, similarity))


@dataclass
class FaceTrack:
    track_id: int
    face: object


class MultiFaceTracker:
    def __init__(self) -> None:
        self._next_id = 0
        self._tracks: list[FaceTrack] = []

    def assign(self, faces) -> list[tuple[int, object]]:
        if not faces:
            return []
        input_faces = ordered_image_faces(faces) if not self._tracks else sorted(faces, key=lambda item: face_diagonal(item), reverse=True)
        assignments: list[tuple[int, object]] = []
        used_tracks: set[int] = set()
        for face in input_faces:
            track = self._match(face, used_tracks)
            if track is None:
                track = FaceTrack(track_id=self._next_id, face=face)
                self._next_id += 1
            else:
                track.face = face
            used_tracks.add(track.track_id)
            assignments.append((track.track_id, face))

        self._tracks = [FaceTrack(track_id=track_id, face=face) for track_id, face in assignments]
        return sorted(assignments, key=lambda item: item[0])

    def order(self, faces) -> list:
        return [face for _, face in self.assign(faces)]

    def _match(self, face, used_tracks: set[int]) -> FaceTrack | None:
        center = face_center(face)
        diagonal = face_diagonal(face)
        best: tuple[float, FaceTrack] | None = None
        for track in self._tracks:
            if track.track_id in used_tracks:
                continue
            track_center = face_center(track.face)
            center_score = (
                (center[0] - track_center[0]) ** 2
                + (center[1] - track_center[1]) ** 2
            ) ** 0.5 / max(diagonal, face_diagonal(track.face), 1.0)
            score = center_score + embedding_distance(face, track.face) * 0.45
            if best is None or score < best[0]:
                best = (score, track)
        if best is None or best[0] > 1.35:
            return None
        return best[1]


def detect_source_faces_with_haar(source_path: Path) -> list[DetectedSourceFace]:
    try:
        import cv2  # type: ignore
    except Exception:
        return []

    image = cv2.imread(str(source_path), cv2.IMREAD_COLOR)
    if image is None:
        return []
    image = limit_bgr_edge(image, 1600)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.equalizeHist(gray)
    detector = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    faces = detector.detectMultiScale(gray, scaleFactor=1.06, minNeighbors=5, minSize=(40, 40))
    boxes = sorted(
        [(int(x), int(y), int(x + width), int(y + height)) for x, y, width, height in faces],
        key=lambda box: ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2),
    )
    return [
        DetectedSourceFace(
            source_face_index=index,
            bbox=box,
            confidence=1.0,
            preview_data_url=preview_data_url_from_box(image, box),
        )
        for index, box in enumerate(boxes[:12])
    ]


def detected_faces_from_frame(frame, faces) -> list[DetectedSourceFace]:
    detected = []
    for index, face in enumerate(ordered_image_faces(faces)[:12]):
        box = clamp_bbox(face.bbox, frame.shape)
        detected.append(
            DetectedSourceFace(
                source_face_index=index,
                bbox=box,
                confidence=float(getattr(face, "det_score", 1.0) or 0.0),
                preview_data_url=preview_data_url_from_box(frame, box),
            )
        )
    return detected


def preview_data_url_from_box(frame, box: tuple[int, int, int, int]) -> str:
    import cv2  # type: ignore

    crop_box = expand_bbox(box, frame.shape, scale=1.65)
    left, top, right, bottom = crop_box
    crop = frame[top:bottom, left:right]
    if crop.size == 0:
        crop = frame
    crop = limit_bgr_edge(crop, 320)
    success, buffer = cv2.imencode(".jpg", crop, [int(cv2.IMWRITE_JPEG_QUALITY), 88])
    if not success:
        return ""
    encoded = base64.b64encode(buffer.tobytes()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


def clamp_bbox(bbox, image_shape) -> tuple[int, int, int, int]:
    height, width = image_shape[:2]
    left, top, right, bottom = [round(float(value)) for value in bbox]
    left = min(max(0, left), width - 1)
    top = min(max(0, top), height - 1)
    right = min(max(left + 1, right), width)
    bottom = min(max(top + 1, bottom), height)
    return left, top, right, bottom


def expand_bbox(box: tuple[int, int, int, int], image_shape, scale: float) -> tuple[int, int, int, int]:
    left, top, right, bottom = box
    center_x = (left + right) / 2
    center_y = (top + bottom) / 2
    width = max(1, right - left) * scale
    height = max(1, bottom - top) * scale
    return clamp_bbox(
        (
            center_x - width / 2,
            center_y - height / 2,
            center_x + width / 2,
            center_y + height / 2,
        ),
        image_shape,
    )


def check_cancel(should_cancel: CancelCallback | None) -> None:
    if should_cancel is not None and should_cancel():
        raise ProcessingCanceled("Processing canceled.")


def limit_bgr_edge(image, max_edge: int):
    import cv2  # type: ignore

    height, width = image.shape[:2]
    long_edge = max(width, height)
    if long_edge <= max_edge:
        return image
    scale = max_edge / long_edge
    size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return cv2.resize(image, size, interpolation=cv2.INTER_AREA)


def resize_frame_for_resolution(frame, video_resolution: str):
    import cv2  # type: ignore

    max_long_edge = {
        "P720": 1280,
        "P1080": 1920,
        "P4K": 3840,
    }.get(video_resolution, 1920)
    height, width = frame.shape[:2]
    long_edge = max(width, height)
    if long_edge <= max_long_edge:
        return frame
    scale = max_long_edge / long_edge
    size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return cv2.resize(frame, size, interpolation=cv2.INTER_AREA)


def frame_index_for_ms(position_ms: int, fps: float) -> int:
    if position_ms <= 0 or fps <= 0:
        return 0
    return max(0, int(position_ms * fps / 1000.0))


def format_ms(position_ms: int) -> str:
    total_seconds = max(0, int(position_ms / 1000))
    minutes = total_seconds // 60
    seconds = total_seconds % 60
    return f"{minutes}:{seconds:02d}"


def open_video_writer(output_path: Path, fps: float, size: tuple[int, int]):
    import cv2  # type: ignore

    for codec in ("mp4v", "avc1", "H264"):
        writer = cv2.VideoWriter(str(output_path), cv2.VideoWriter_fourcc(*codec), fps, size)
        if writer.isOpened():
            return writer
        writer.release()
    raise ValueError("MP4 video encoder is not available in OpenCV.")


def upscale_for_detection(image):
    import cv2  # type: ignore

    height, width = image.shape[:2]
    long_edge = max(width, height)
    if long_edge >= 1024:
        return image
    scale = min(3.0, 1024 / max(1, long_edge))
    size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return cv2.resize(image, size, interpolation=cv2.INTER_CUBIC)


def pil_frame_to_bgr(frame: Image.Image):
    import cv2  # type: ignore
    import numpy as np  # type: ignore

    rgba = frame.convert("RGBA")
    background = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
    composed = Image.alpha_composite(background, rgba).convert("RGB")
    return cv2.cvtColor(np.array(composed), cv2.COLOR_RGB2BGR)


def bgr_to_palette_gif_frame(frame) -> Image.Image:
    import cv2  # type: ignore

    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    image = Image.fromarray(rgb).convert("RGB")
    palette = getattr(getattr(Image, "Palette", object), "ADAPTIVE", getattr(Image, "ADAPTIVE", None))
    dither = getattr(getattr(Image, "Dither", object), "FLOYDSTEINBERG", getattr(Image, "FLOYDSTEINBERG", None))
    return image.convert("P", palette=palette, colors=256, dither=dither)


def donor_face_from_center_crop(face_analyser, image):
    import cv2  # type: ignore
    import numpy as np  # type: ignore
    from insightface.app.common import Face  # type: ignore

    recognition = face_analyser.models.get("recognition")
    if recognition is None:
        raise ValueError("Recognition model is not loaded.")

    height, width = image.shape[:2]
    side = min(width, height)
    left = max(0, (width - side) // 2)
    top = max(0, (height - side) // 2)
    crop = image[top:top + side, left:left + side]
    crop = cv2.resize(crop, (112, 112), interpolation=cv2.INTER_CUBIC)
    embedding = recognition.get_feat(crop).flatten()
    return Face(
        bbox=np.array([left, top, left + side, top + side], dtype=np.float32),
        kps=None,
        det_score=1.0,
        embedding=embedding,
    )


def match_color_statistics(source, target, mask):
    import cv2  # type: ignore
    import numpy as np  # type: ignore

    adjusted = source.astype(np.float32)
    masked = mask > 0
    if not masked.any():
        return source
    for channel in range(3):
        src_values = adjusted[:, :, channel][masked]
        dst_values = target[:, :, channel][masked]
        src_mean = float(src_values.mean())
        src_std = float(src_values.std() + 1e-6)
        dst_mean = float(dst_values.mean())
        dst_std = float(dst_values.std() + 1e-6)
        adjusted[:, :, channel] = (adjusted[:, :, channel] - src_mean) * (dst_std / src_std) + dst_mean
    adjusted = np.clip(adjusted, 0, 255).astype(np.uint8)
    return cv2.bilateralFilter(adjusted, d=5, sigmaColor=24, sigmaSpace=24)


def sharpen_bgr(image):
    import cv2  # type: ignore

    blurred = cv2.GaussianBlur(image, (0, 0), sigmaX=1.2)
    return cv2.addWeighted(image, 1.08, blurred, -0.08, 0)


def save_bgr_output(image, output_path: Path, options: ProcessingOptions, processor_name: str) -> None:
    import cv2  # type: ignore

    output_path.parent.mkdir(parents=True, exist_ok=True)
    rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    result = Image.fromarray(rgb).convert("RGB")
    if options.visible_watermark:
        draw_watermark(result)
    save_kwargs = {"quality": options.jpeg_quality, "optimize": True}
    if options.metadata_watermark:
        save_kwargs["comment"] = f"AI_GENERATED=true; processor={processor_name}".encode("utf-8")
    result.save(output_path, format="JPEG", **save_kwargs)


def draw_watermark_bgr(frame) -> None:
    import cv2  # type: ignore

    label = "AI GENERATED"
    height, width = frame.shape[:2]
    font_scale = max(0.55, width / 1280 * 0.72)
    thickness = max(1, round(width / 900))
    (text_width, text_height), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, thickness)
    padding = max(8, round(width * 0.012))
    margin = max(10, round(width * 0.014))
    right = width - margin
    bottom = height - margin
    left = right - text_width - padding * 2
    top = bottom - text_height - baseline - padding * 2
    overlay = frame.copy()
    cv2.rectangle(overlay, (left, top), (right, bottom), (0, 0, 0), -1)
    cv2.addWeighted(overlay, 0.62, frame, 0.38, 0, frame)
    cv2.putText(
        frame,
        label,
        (left + padding, bottom - padding - baseline),
        cv2.FONT_HERSHEY_SIMPLEX,
        font_scale,
        (255, 255, 255),
        thickness,
        cv2.LINE_AA,
    )


def write_video_sidecar(output_path: Path, processor_name: str, frames: int, swapped: int) -> None:
    import json

    sidecar = output_path.with_suffix(output_path.suffix + ".metadata.json")
    sidecar.write_text(
        json.dumps(
            {
                "aiGenerated": True,
                "processor": processor_name,
                "frames": frames,
                "swappedFrames": swapped,
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def draw_watermark(image: Image.Image) -> None:
    label = "AI GENERATED"
    draw = ImageDraw.Draw(image)
    width, height = image.size
    font_size = max(22, round(width * 0.026))
    try:
        font = ImageFont.truetype("arial.ttf", font_size)
    except OSError:
        font = ImageFont.load_default()

    text_box = draw.textbbox((0, 0), label, font=font)
    text_width = text_box[2] - text_box[0]
    text_height = text_box[3] - text_box[1]
    padding = max(10, round(width * 0.012))
    margin = max(12, round(width * 0.014))
    right = width - margin
    bottom = height - margin
    left = right - text_width - padding * 2
    top = bottom - text_height - padding * 2

    draw.rounded_rectangle((left, top, right, bottom), radius=10, fill=(0, 0, 0, 176))
    draw.text((left + padding, top + padding), label, fill=(255, 255, 255, 255), font=font)
