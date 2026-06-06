from __future__ import annotations

import multiprocessing
import os
import threading
import time
import webbrowser
from pathlib import Path

import uvicorn

from app.main import app as fastapi_app


def open_console(port: int) -> None:
    time.sleep(2.5)
    webbrowser.open(f"http://127.0.0.1:{port}/web/")


def main() -> None:
    multiprocessing.freeze_support()
    host = os.getenv("AI_FACE_HOST", "0.0.0.0")
    port = int(os.getenv("AI_FACE_PORT", "8000"))
    storage_root = Path(os.getenv("AI_FACE_STORAGE", Path.home() / "AI Face Studio" / "storage"))
    storage_root.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("AI_FACE_STORAGE", str(storage_root))

    print("AI Face Studio backend is starting...")
    print(f"Backend API: http://127.0.0.1:{port}/")
    print(f"Web preview: http://127.0.0.1:{port}/web/")
    print(f"LAN access: http://<this-pc-lan-ip>:{port}/web/")
    print("Close this window to stop the backend.")

    threading.Thread(target=open_console, args=(port,), daemon=True).start()
    uvicorn.run(fastapi_app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
