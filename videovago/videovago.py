#!/usr/bin/env python3
"""Videóvágó – lossless, offline video cutter and rotator.

Uses only the Python standard library plus ffmpeg/ffprobe. The video is
never re-encoded: cutting is stream copy (-c copy), rotation is written as
display-matrix metadata. The UI runs in the browser at 127.0.0.1, with no
internet access.

    python3 videovago.py [video file] [--port 8765] [--no-browser]
"""

from __future__ import annotations

import argparse
import http.server
import json
import mimetypes
import os
import re
import secrets
import shutil
import socketserver
import string
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import uuid
import webbrowser
from pathlib import Path

HERE = Path(__file__).resolve().parent
INDEX_HTML = HERE / "index.html"

VIDEO_EXTS = {
    ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".mts", ".m2ts", ".ts",
    ".mpg", ".mpeg", ".wmv", ".flv", ".3gp", ".mxf", ".insv", ".lrv",
}
# Containers whose muxer writes the display matrix (rotation).
ROTATION_CONTAINERS = {".mp4", ".m4v", ".mov", ".3gp"}

TOKEN = secrets.token_urlsafe(24)
FFMPEG = shutil.which("ffmpeg")
FFPROBE = shutil.which("ffprobe")
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
JOBS: dict[str, "Job"] = {}
STARTUP_FILE: str | None = None


# --------------------------------------------------------------------------- ffmpeg helpers

def run(cmd: list[str], timeout: float | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout, creationflags=NO_WINDOW,
    )


def ffmpeg_major() -> int:
    try:
        out = run([FFMPEG, "-hide_banner", "-version"], timeout=10).stdout
    except Exception:
        return 0
    m = re.search(r"ffmpeg version n?(\d+)", out)
    return int(m.group(1)) if m else 99  # git builds: assume new


FFMPEG_MAJOR = ffmpeg_major() if FFMPEG else 0


def parse_rate(s: str | None) -> float:
    if not s or s in ("0/0", "N/A"):
        return 0.0
    if "/" in s:
        a, b = s.split("/", 1)
        return float(a) / float(b) if float(b) else 0.0
    return float(s)


def stream_rotation(st: dict) -> float:
    for sd in st.get("side_data_list") or []:
        if "rotation" in sd:
            try:
                return float(sd["rotation"])
            except (TypeError, ValueError):
                pass
    try:
        # Old ffmpeg versions: "rotate" tag, clockwise.
        return -float((st.get("tags") or {}).get("rotate", 0))
    except ValueError:
        return 0.0


def probe(path: str) -> dict:
    r = run([FFPROBE, "-v", "error", "-print_format", "json",
             "-show_format", "-show_streams", path], timeout=60)
    if r.returncode != 0:
        raise ValueError(r.stderr.strip() or "ffprobe failed")
    data = json.loads(r.stdout)
    fmt = data.get("format", {})
    streams = []
    video = None
    for st in data.get("streams", []):
        kind = st.get("codec_type")
        item = {
            "index": st.get("index"),
            "type": kind,
            "codec": st.get("codec_name"),
            "profile": st.get("profile"),
            "lang": (st.get("tags") or {}).get("language"),
            "title": (st.get("tags") or {}).get("title"),
        }
        if kind == "video" and not (st.get("disposition") or {}).get("attached_pic"):
            fps = parse_rate(st.get("avg_frame_rate")) or parse_rate(st.get("r_frame_rate"))
            item.update({
                "width": st.get("width"), "height": st.get("height"), "fps": fps,
                "pix_fmt": st.get("pix_fmt"), "bit_rate": st.get("bit_rate"),
                "rotation": stream_rotation(st),
            })
            if video is None:
                video = item
        elif kind == "audio":
            item.update({"channels": st.get("channels"),
                         "sample_rate": st.get("sample_rate")})
        streams.append(item)
    if video is None:
        raise ValueError("Nincs videósáv a fájlban.")
    duration = float(fmt.get("duration") or 0)
    return {
        "path": path,
        "name": os.path.basename(path),
        "size": int(fmt.get("size") or os.path.getsize(path)),
        "duration": duration,
        "format": fmt.get("format_name"),
        "bit_rate": fmt.get("bit_rate"),
        "start_time": float(fmt.get("start_time") or 0),
        "video": video,
        "streams": streams,
    }


def keyframes(path: str) -> list[float]:
    """Keyframe times of the first video stream, read from packet flags (no decoding)."""
    r = run([FFPROBE, "-v", "error", "-select_streams", "v:0",
             "-show_entries", "packet=pts_time,dts_time,flags",
             "-of", "csv=p=0", path])
    times = []
    for line in r.stdout.splitlines():
        parts = line.strip().split(",")
        if len(parts) < 3 or "K" not in parts[2]:
            continue
        for v in parts[:2]:
            try:
                times.append(float(v))
                break
            except ValueError:
                continue
    return sorted(set(times))


def frame_jpeg(path: str, t: float, width: int = 1280) -> bytes:
    r = subprocess.run(
        [FFMPEG, "-v", "error", "-ss", f"{max(t, 0):.3f}", "-i", path, "-frames:v", "1",
         "-vf", f"scale='min({width},iw)':-2", "-q:v", "4", "-f", "image2pipe",
         "-vcodec", "mjpeg", "pipe:1"],
        capture_output=True, timeout=60, creationflags=NO_WINDOW,
    )
    if r.returncode != 0 or not r.stdout:
        raise ValueError(r.stderr.decode("utf-8", "replace").strip() or "No frame")
    return r.stdout


# --------------------------------------------------------------------------- export

class Job:
    def __init__(self, params: dict):
        self.id = uuid.uuid4().hex[:12]
        self.params = params
        self.state = "running"   # running | done | error | cancelled
        self.progress = 0.0
        self.message = "Indul…"
        self.outputs: list[str] = []
        self.log: list[str] = []
        self.proc: subprocess.Popen | None = None
        self.cancelled = False
        self.started = time.time()

    def to_json(self) -> dict:
        return {"id": self.id, "state": self.state, "progress": round(self.progress, 4),
                "message": self.message, "outputs": self.outputs,
                "log": "\n".join(self.log[-30:]),
                "elapsed": round(time.time() - self.started, 1)}


def ff_run(job: Job, cmd: list[str], total: float, base: float, span: float) -> None:
    """Run ffmpeg with -progress; map its progress into [base, base+span]."""
    job.log.append("$ " + " ".join(_q(c) for c in cmd))
    job.proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        encoding="utf-8", errors="replace", creationflags=NO_WINDOW,
    )
    err_lines: list[str] = []

    def read_err():
        for line in job.proc.stderr:
            err_lines.append(line.rstrip())
    t = threading.Thread(target=read_err, daemon=True)
    t.start()
    for line in job.proc.stdout:
        k, _, v = line.strip().partition("=")
        if k in ("out_time_us", "out_time_ms") and total > 0:
            try:
                sec = int(v) / 1e6
            except ValueError:
                continue
            job.progress = base + span * min(sec / total, 1.0)
    job.proc.wait()
    t.join(timeout=2)
    job.log.extend(err_lines[-15:])
    if job.cancelled:
        raise InterruptedError()
    if job.proc.returncode != 0:
        raise RuntimeError("\n".join(err_lines[-8:]) or f"ffmpeg exit {job.proc.returncode}")


def _q(s: str) -> str:
    return s if re.fullmatch(r"[\w./:=+,-]+", s) else '"' + s.replace('"', '\\"') + '"'


def rotation_args(src_rot: float, extra_cw: int, ext: str) -> tuple[list[str], list[str]]:
    """(input options, output options) to set the final rotation."""
    if extra_cw % 360 == 0:
        return [], []
    total_ccw = (src_rot - extra_cw) % 360
    if total_ccw > 180:
        total_ccw -= 360
    if FFMPEG_MAJOR >= 6:
        return ["-display_rotation:v:0", f"{total_ccw:g}"], []
    # Old ffmpeg: the "rotate" tag (clockwise) on the mov/mp4 muxer.
    return [], ["-metadata:s:v:0", f"rotate={int(-total_ccw) % 360}"]


def stream_maps(info: dict, keep_audio: bool, keep_subs: bool, ext: str) -> list[str]:
    maps = ["-map", "0:v"]
    if keep_audio:
        maps += ["-map", "0:a?"]
    if keep_subs and ext in (".mkv", ".mov", ".mp4", ".m4v"):
        sub_codecs = {s["codec"] for s in info["streams"] if s["type"] == "subtitle"}
        mp4_ok = {"mov_text"}
        if ext == ".mkv" or sub_codecs <= mp4_ok:
            maps += ["-map", "0:s?"]
    # Drop cover art / thumbnail "video" streams, they break concat.
    for s in info["streams"]:
        if s["type"] == "video" and s is not info["video"] and s["codec"] in ("mjpeg", "png", "bmp"):
            maps += ["-map", f"-0:{s['index']}"]
    return maps


def container_args(ext: str) -> list[str]:
    if ext in (".mp4", ".m4v", ".mov"):
        return ["-movflags", "+faststart"]
    return []


def unique_path(p: Path) -> Path:
    if not p.exists():
        return p
    for i in range(2, 10000):
        c = p.with_name(f"{p.stem} ({i}){p.suffix}")
        if not c.exists():
            return c
    raise RuntimeError("Nem található szabad fájlnév")


def export_worker(job: Job) -> None:
    p = job.params
    try:
        src = p["path"]
        info = probe(src)
        segs = [(float(a), float(b)) for a, b in p["segments"] if float(b) - float(a) > 0.001]
        if not segs:
            raise ValueError("Nincs kijelölt szakasz.")
        out = Path(p["output"]).expanduser()
        ext = out.suffix.lower() or Path(src).suffix.lower()
        if not out.suffix:
            out = out.with_suffix(ext)
        out.parent.mkdir(parents=True, exist_ok=True)
        rot = int(p.get("rotate", 0)) % 360
        if rot and ext not in ROTATION_CONTAINERS:
            raise ValueError(f"A {ext} formátum nem tárol forgatást. Válassz MP4-et vagy MOV-ot.")
        in_rot, out_rot = rotation_args(info["video"]["rotation"], rot, ext)
        maps = stream_maps(info, p.get("audio", True), p.get("subs", True), ext)
        common_out = ["-c", "copy", "-avoid_negative_ts", "make_zero",
                      "-map_metadata", "0", "-map_chapters", "-1", *container_args(ext)]
        total = sum(b - a for a, b in segs)
        base_cmd = [FFMPEG, "-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats"]

        def cut_cmd(a: float, b: float, dst: Path, rotate: bool) -> list[str]:
            full = a <= 0.0005 and b >= info["duration"] - 0.0005
            seek = [] if full else ["-ss", f"{a:.6f}", "-t", f"{b - a:.6f}"]
            return [*base_cmd, *seek, *(in_rot if rotate else []), "-i", src,
                    *maps, *common_out, *(out_rot if rotate else []), str(dst)]

        if p.get("mode") == "separate" or len(segs) == 1:
            done = 0.0
            for i, (a, b) in enumerate(segs):
                dst = out if len(segs) == 1 else out.with_name(f"{out.stem}_{i + 1:02d}{ext}")
                dst = unique_path(dst) if not p.get("overwrite") else dst
                job.message = f"Vágás {i + 1}/{len(segs)}: {dst.name}"
                ff_run(job, cut_cmd(a, b, dst, True), b - a, done / total, (b - a) / total)
                done += b - a
                job.outputs.append(str(dst))
        else:
            out = unique_path(out) if not p.get("overwrite") else out
            tmpdir = Path(tempfile.mkdtemp(prefix=".videovago_", dir=out.parent))
            try:
                parts = []
                done = 0.0
                for i, (a, b) in enumerate(segs):
                    part = tmpdir / f"part{i:03d}{ext}"
                    job.message = f"Szakasz {i + 1}/{len(segs)} kivágása"
                    ff_run(job, cut_cmd(a, b, part, False), b - a, 0.9 * done / total, 0.9 * (b - a) / total)
                    done += b - a
                    parts.append(part)
                lst = tmpdir / "list.txt"
                lst.write_text("".join("file '" + x.as_posix().replace("'", "'\\''") + "'\n"
                                      for x in parts), encoding="utf-8")
                job.message = "Szakaszok összefűzése"
                cmd = [*base_cmd, "-f", "concat", "-safe", "0", "-auto_convert", "0", *in_rot, "-i", str(lst),
                       "-map", "0", "-c", "copy", *container_args(ext), *out_rot, str(out)]
                ff_run(job, cmd, total, 0.9, 0.1)
                job.outputs.append(str(out))
            finally:
                shutil.rmtree(tmpdir, ignore_errors=True)
        job.progress = 1.0
        job.state = "done"
        job.message = "Kész – újrakódolás nélkül."
    except InterruptedError:
        job.state = "cancelled"
        job.message = "Megszakítva."
    except Exception as e:  # noqa: BLE001
        job.state = "error"
        job.message = str(e)


# --------------------------------------------------------------------------- file browser

def list_dir(path: str | None) -> dict:
    if not path:
        if STARTUP_FILE:
            path = os.path.dirname(STARTUP_FILE)
        else:
            path = str(Path.home())
    if path == "::drives" and os.name == "nt":
        drives = [f"{d}:\\" for d in string.ascii_uppercase if os.path.exists(f"{d}:\\")]
        return {"path": "", "parent": None,
                "entries": [{"name": d, "path": d, "dir": True} for d in drives]}
    p = Path(path).expanduser().resolve()
    if p.is_file():
        p = p.parent
    entries = []
    try:
        for e in sorted(os.scandir(p), key=lambda e: (not e.is_dir(), e.name.lower())):
            if e.name.startswith("."):
                continue
            try:
                if e.is_dir():
                    entries.append({"name": e.name, "path": e.path, "dir": True})
                elif Path(e.name).suffix.lower() in VIDEO_EXTS:
                    entries.append({"name": e.name, "path": e.path, "dir": False,
                                    "size": e.stat().st_size})
            except OSError:
                continue
    except OSError as e:
        raise ValueError(str(e))
    parent = str(p.parent) if p.parent != p else ("::drives" if os.name == "nt" else None)
    return {"path": str(p), "parent": parent, "entries": entries}


# --------------------------------------------------------------------------- HTTP

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Videovago"

    def log_message(self, fmt, *args):  # quiet
        pass

    # -- guards
    def _host_ok(self) -> bool:
        host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]")
        return host in ("127.0.0.1", "localhost", "::1")

    def _authed(self, qs: dict) -> bool:
        cookie = self.headers.get("Cookie") or ""
        if f"vv_token={TOKEN}" in cookie.replace(" ", "").split(";"):
            return True
        return qs.get("token", [""])[0] == TOKEN

    def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj, code: int = 200):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def _err(self, msg: str, code: int = 400):
        self._json({"error": msg}, code)

    def _route(self):
        u = urllib.parse.urlparse(self.path)
        return u.path, urllib.parse.parse_qs(u.query)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path, qs = self._route()
        if not self._host_ok():
            return self._err("Forbidden host", 403)
        if path == "/":
            if not self._authed(qs):
                return self._send(403, "Nyisd meg a konzolban kiírt linket (token hiányzik).".encode(),
                                  "text/plain; charset=utf-8")
            body = INDEX_HTML.read_bytes()
            return self._send(200, body, "text/html; charset=utf-8", {
                "Set-Cookie": f"vv_token={TOKEN}; Path=/; HttpOnly; SameSite=Strict"})
        if not self._authed(qs):
            return self._err("Unauthorized", 401)
        arg = lambda k, d=None: qs.get(k, [d])[0]  # noqa: E731
        try:
            if path == "/api/config":
                return self._json({"ffmpeg": FFMPEG, "ffprobe": FFPROBE, "ffmpeg_major": FFMPEG_MAJOR,
                                   "startup_file": STARTUP_FILE, "sep": os.sep,
                                   "rotation_containers": sorted(ROTATION_CONTAINERS)})
            if path == "/api/browse":
                return self._json(list_dir(arg("path")))
            if path == "/api/probe":
                return self._json(probe(self._file(arg("path"))))
            if path == "/api/keyframes":
                return self._json({"keyframes": keyframes(self._file(arg("path")))})
            if path == "/api/frame":
                img = frame_jpeg(self._file(arg("path")), float(arg("t", "0")), int(arg("w", "1280")))
                return self._send(200, img, "image/jpeg")
            if path.startswith("/api/job/"):
                job = JOBS.get(path.rsplit("/", 1)[1])
                return self._json(job.to_json()) if job else self._err("No such job", 404)
            if path == "/media":
                return self._media(self._file(arg("path")))
        except (ValueError, OSError, subprocess.TimeoutExpired) as e:
            return self._err(str(e))
        self._err("Not found", 404)

    def do_POST(self):
        path, qs = self._route()
        if not self._host_ok() or not self._authed(qs):
            return self._err("Unauthorized", 401)
        if (self.headers.get("Content-Type") or "").split(";")[0] != "application/json":
            return self._err("JSON expected", 415)
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except json.JSONDecodeError:
            return self._err("Bad JSON")
        if path == "/api/export":
            try:
                body["path"] = self._file(body.get("path"))
                if not body.get("output"):
                    raise ValueError("Nincs kimeneti fájl megadva.")
                if Path(body["output"]).expanduser().resolve() == Path(body["path"]).resolve():
                    raise ValueError("A kimenet nem lehet azonos a forrásfájllal.")
            except ValueError as e:
                return self._err(str(e))
            job = Job(body)
            JOBS[job.id] = job
            threading.Thread(target=export_worker, args=(job,), daemon=True).start()
            return self._json({"id": job.id})
        m = re.fullmatch(r"/api/job/(\w+)/cancel", path)
        if m and m.group(1) in JOBS:
            job = JOBS[m.group(1)]
            job.cancelled = True
            if job.proc and job.proc.poll() is None:
                job.proc.terminate()
            return self._json({"ok": True})
        if path == "/api/reveal":
            try:
                reveal(body.get("path", ""))
            except Exception as e:  # noqa: BLE001
                return self._err(str(e))
            return self._json({"ok": True})
        self._err("Not found", 404)

    @staticmethod
    def _file(p: str | None) -> str:
        if not p:
            raise ValueError("Hiányzó fájl")
        p = os.path.abspath(os.path.expanduser(p))
        if not os.path.isfile(p):
            raise ValueError(f"Nem található: {p}")
        return p

    def _media(self, p: str):
        size = os.path.getsize(p)
        ctype = mimetypes.guess_type(p)[0] or "video/mp4"
        if p.lower().endswith((".mkv", ".webm")):
            ctype = "video/webm"  # lets Chromium try the Matroska demuxer
        elif p.lower().endswith((".mov", ".m4v")):
            ctype = "video/mp4"
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        code = 200
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(size - int(m.group(2)), 0)
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                code = 206
        length = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(p, "rb") as f:
            f.seek(start)
            left = length
            try:
                while left > 0:
                    chunk = f.read(min(1 << 20, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass


def reveal(p: str) -> None:
    p = os.path.abspath(p)
    if sys.platform == "win32":
        subprocess.Popen(["explorer", "/select,", p])
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", p])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(p)])


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    global STARTUP_FILE
    ap = argparse.ArgumentParser(description="Lossless offline video cutter")
    ap.add_argument("file", nargs="?", help="video file to open")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()

    if not FFMPEG or not FFPROBE:
        print("HIBA: nem található az ffmpeg és/vagy az ffprobe.\n"
              "Telepítsd (https://ffmpeg.org/download.html), és tedd a PATH-ba,\n"
              "vagy másold az ffmpeg és ffprobe fájlt a videovago.py mellé.")
        sys.exit(1)
    if args.file:
        STARTUP_FILE = os.path.abspath(args.file)

    srv = None
    for port in range(args.port, args.port + 20):
        try:
            srv = Server(("127.0.0.1", port), Handler)
            break
        except OSError:
            continue
    if srv is None:
        print("Nem sikerült szabad portot találni.")
        sys.exit(1)
    url = f"http://127.0.0.1:{srv.server_address[1]}/?token={TOKEN}"
    print(f"Videóvágó fut: {url}\nffmpeg: {FFMPEG} (v{FFMPEG_MAJOR})\nLeállítás: Ctrl+C")
    if not args.no_browser:
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nLeállítva.")
        for job in JOBS.values():
            if job.proc and job.proc.poll() is None:
                job.proc.terminate()


if __name__ == "__main__":
    # Prefer a local ffmpeg next to the script (portable Windows setup).
    for name in ("ffmpeg", "ffprobe"):
        local = HERE / (name + (".exe" if os.name == "nt" else ""))
        if local.is_file():
            globals()[name.upper()] = str(local)
    if FFMPEG:
        FFMPEG_MAJOR = ffmpeg_major()
    main()
