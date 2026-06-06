from __future__ import annotations

import argparse
import shutil
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


BASE_URL = "https://github.com/deepinsight/insightface/releases/download/v0.7"
DEFAULT_MODEL_ROOT = Path(__file__).resolve().parents[1] / "model_store"


def main() -> None:
    parser = argparse.ArgumentParser(description="Download InsightFace models for high-quality image face swap.")
    parser.add_argument("--model-root", type=Path, default=DEFAULT_MODEL_ROOT)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    model_root = args.model_root.expanduser().resolve()
    cache_dir = model_root / "cache"
    cache_dir.mkdir(parents=True, exist_ok=True)

    download_detector_pack(model_root, cache_dir, force=args.force)
    download_onnx(
        name="inswapper_128.onnx",
        url=f"{BASE_URL}/inswapper_128.onnx",
        destination=model_root / "models" / "inswapper_128" / "inswapper_128.onnx",
        cache_dir=cache_dir,
        force=args.force,
    )
    print(f"Models ready in {model_root}")


def download_detector_pack(model_root: Path, cache_dir: Path, force: bool) -> None:
    target_dir = model_root / "models" / "buffalo_l"
    if target_dir.exists() and any(target_dir.glob("*.onnx")) and not force:
        print(f"buffalo_l already installed: {target_dir}")
        return

    archive = download_file(
        name="buffalo_l.zip",
        url=f"{BASE_URL}/buffalo_l.zip",
        destination=cache_dir / "buffalo_l.zip",
        force=force,
    )
    if target_dir.exists():
        shutil.rmtree(target_dir)
    target_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(target_dir)
    print(f"buffalo_l installed: {target_dir}")


def download_onnx(name: str, url: str, destination: Path, cache_dir: Path, force: bool) -> None:
    if destination.exists() and destination.stat().st_size > 0 and not force:
        print(f"{name} already installed: {destination}")
        return

    archive = download_file(name=name, url=url, destination=cache_dir / name, force=force)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(archive, destination)
    print(f"{name} installed: {destination}")


def download_file(name: str, url: str, destination: Path, force: bool) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0 and not force:
        print(f"Using cached {name}: {destination}")
        return destination

    partial = destination.with_suffix(destination.suffix + ".part")
    if force and partial.exists():
        partial.unlink()

    last_error: Exception | None = None
    for attempt in range(1, 5):
        existing = partial.stat().st_size if partial.exists() else 0
        headers = {
            "Accept": "application/octet-stream,*/*",
            "User-Agent": "AI-Face-Replacement-Backend/1.0",
        }
        if existing:
            headers["Range"] = f"bytes={existing}-"
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                status = getattr(response, "status", response.getcode())
                if existing and status != 206:
                    existing = 0
                    mode = "wb"
                else:
                    mode = "ab" if existing else "wb"
                content_length = int(response.headers.get("Content-Length") or 0)
                total = content_length + (existing if mode == "ab" else 0)
                downloaded = existing if mode == "ab" else 0
                with partial.open(mode) as handle:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        handle.write(chunk)
                        downloaded += len(chunk)
                        if total:
                            percent = downloaded * 100 / total
                            print(f"\rDownloading {name}: {percent:5.1f}%", end="", flush=True)
            print()
            partial.replace(destination)
            return destination
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
            last_error = exc
            print(f"\nDownload attempt {attempt}/4 failed for {name}: {exc}")
            if attempt < 4:
                time.sleep(min(attempt * 2, 8))
    raise RuntimeError(f"Failed to download {name}: {last_error}")


if __name__ == "__main__":
    main()
