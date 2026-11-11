"""
Gaz Live Mixer for OBS Studio (script edition)
Gaz Media Production - "Everything Multimedia"

Turns any phone, tablet or PC on your network into a video-mixer panel for OBS:
scene thumbnails, preview/program tally, double-tap to take, CUT / AUTO,
transition picker, plus hotkeys for keypads, Stream Decks and MIDI-to-key tools.

No WebSocket setup: this script controls OBS from the inside and serves the
panel over plain HTTP. Load it in OBS: Tools > Scripts > + > gaz_live_mixer.py
"""

import obspython as obs

import ctypes
import json
import os
import queue
import socket
import struct
import sys
import threading
import time
import webbrowser
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = "1.0.0"
DEFAULT_PORT = 4466
DISCOVERY_PORT = 4467
DISCOVERY_MAGIC = b"GAZMIXER_DISCOVER"
HOTKEY_SLOTS = 12


def _script_dir():
    try:
        return script_path()  # injected by OBS  # noqa: F821
    except Exception:
        return os.path.dirname(os.path.abspath(__file__))


SCRIPT_DIR = _script_dir()
sys.path.insert(0, SCRIPT_DIR)
try:
    import gaz_qrcodegen as qrgen
except Exception:  # QR is a convenience only
    qrgen = None


def log(msg):
    print("[Gaz Live Mixer] " + str(msg))


# --------------------------------------------------------------------------
# Shared state between OBS (main/tick thread) and the web server threads
# --------------------------------------------------------------------------
class Shared:
    def __init__(self):
        self.cond = threading.Condition()
        self.state = {}
        self.rev = 0
        self.commands = queue.Queue()
        self.thumbs = {}        # scene -> (bytes, mime, timestamp)
        self.wanted = {}        # scene -> last time a client asked for it
        self.thumb_width = 320
        self.thumbs_enabled = True
        self.thumbs_ok = True
        self.port = DEFAULT_PORT

    def publish(self, st):
        with self.cond:
            if st != self.state:
                self.state = st
                self.rev += 1
                self.cond.notify_all()

    def snapshot(self):
        with self.cond:
            d = dict(self.state)
            d["rev"] = self.rev
            return d

    def wait_change(self, since, timeout):
        with self.cond:
            if since == self.rev:
                self.cond.wait_for(lambda: self.rev != since, timeout=timeout)
            d = dict(self.state)
            d["rev"] = self.rev
            return d


SH = Shared()


# --------------------------------------------------------------------------
# Network helpers
# --------------------------------------------------------------------------
def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips or ["127.0.0.1"]


def panel_urls():
    return ["http://%s:%d/" % (ip, SH.port) for ip in local_ips()]


def qr_svg(text):
    if not qrgen:
        return None
    qr = qrgen.QrCode.encode_text(text, qrgen.QrCode.Ecc.MEDIUM)
    n, border = qr.get_size(), 2
    parts = []
    for y in range(n):
        for x in range(n):
            if qr.get_module(x, y):
                parts.append("M%d,%dh1v1h-1z" % (x + border, y + border))
    size = n + border * 2
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {0} {0}" shape-rendering="crispEdges">'
            '<rect width="100%" height="100%" fill="#fff"/><path d="{1}" fill="#000"/></svg>').format(size, "".join(parts)).encode()


# --------------------------------------------------------------------------
# Scene thumbnails: render scenes off-screen through libobs (ctypes)
# --------------------------------------------------------------------------
GS_RGBA, GS_ZS_NONE, GS_CLEAR_COLOR, GS_BLEND_ZERO, GS_BLEND_ONE = 3, 0, 1, 0, 1


def png_from_rgba(raw, w, h, stride):
    row_bytes = w * 4
    if stride != row_bytes:
        raw = b"".join(raw[y * stride:y * stride + row_bytes] for y in range(h))
    else:
        raw = raw[:row_bytes * h]
    rgb = bytearray(w * h * 3)
    rgb[0::3] = raw[0::4]
    rgb[1::3] = raw[1::4]
    rgb[2::3] = raw[2::4]
    line = w * 3
    rows = b"".join(b"\x00" + bytes(rgb[y * line:(y + 1) * line]) for y in range(h))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 3)) + chunk(b"IEND", b""))


class Thumbnailer:
    def __init__(self):
        self.lib = None
        self.tex = None
        self.stage = None
        self.stage_size = (0, 0)
        self.error = None
        try:
            self.lib = self._load()
            self._prototypes()
        except Exception as e:  # thumbnails are optional
            self.lib = None
            self.error = str(e)
        # 16-byte aligned vec4 (black, opaque) for gs_clear
        self._vbuf = (ctypes.c_float * 8)()
        off = (-ctypes.addressof(self._vbuf)) % 16
        self._vec = (ctypes.c_float * 4).from_address(ctypes.addressof(self._vbuf) + off)
        self._vec[:] = [0.0, 0.0, 0.0, 1.0]

    @property
    def ok(self):
        return self.lib is not None

    @staticmethod
    def _load():
        cands = []
        if sys.platform == "win32":
            cands = ["obs.dll", "obs"]
        else:
            cands = [None, "libobs.so.0", "libobs.so", "libobs.framework/libobs"]
        for c in cands:
            try:
                lib = ctypes.CDLL(c)
                getattr(lib, "gs_texrender_create")
                return lib
            except Exception:
                continue
        raise OSError("libobs not found for thumbnails")

    def _prototypes(self):
        L, vp, u32, f = self.lib, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_float
        sig = {
            "obs_get_source_by_name": (vp, [ctypes.c_char_p]),
            "obs_source_release": (None, [vp]),
            "obs_source_get_base_width": (u32, [vp]),
            "obs_source_get_base_height": (u32, [vp]),
            "obs_source_inc_showing": (None, [vp]),
            "obs_source_dec_showing": (None, [vp]),
            "obs_source_video_render": (None, [vp]),
            "obs_enter_graphics": (None, []),
            "obs_leave_graphics": (None, []),
            "gs_texrender_create": (vp, [ctypes.c_int, ctypes.c_int]),
            "gs_texrender_destroy": (None, [vp]),
            "gs_texrender_reset": (None, [vp]),
            "gs_texrender_begin": (ctypes.c_bool, [vp, u32, u32]),
            "gs_texrender_end": (None, [vp]),
            "gs_texrender_get_texture": (vp, [vp]),
            "gs_stagesurface_create": (vp, [u32, u32, ctypes.c_int]),
            "gs_stagesurface_destroy": (None, [vp]),
            "gs_stage_texture": (None, [vp, vp]),
            "gs_stagesurface_map": (ctypes.c_bool, [vp, ctypes.POINTER(ctypes.POINTER(ctypes.c_uint8)), ctypes.POINTER(u32)]),
            "gs_stagesurface_unmap": (None, [vp]),
            "gs_clear": (None, [u32, vp, f, ctypes.c_uint8]),
            "gs_ortho": (None, [f, f, f, f, f, f]),
            "gs_blend_state_push": (None, []),
            "gs_blend_state_pop": (None, []),
            "gs_blend_function": (None, [ctypes.c_int, ctypes.c_int]),
        }
        for name, (res, args) in sig.items():
            fn = getattr(L, name)
            fn.restype = res
            fn.argtypes = args

    def render(self, scene_name, width):
        L = self.lib
        src = L.obs_get_source_by_name(scene_name.encode("utf-8"))
        if not src:
            return None
        raw = None
        try:
            bw, bh = L.obs_source_get_base_width(src), L.obs_source_get_base_height(src)
            if not bw or not bh:
                return None
            w = max(16, int(width)) & ~1
            h = max(16, int(round(w * bh / float(bw)))) & ~1
            L.obs_enter_graphics()
            try:
                if not self.tex:
                    self.tex = L.gs_texrender_create(GS_RGBA, GS_ZS_NONE)
                if self.stage_size != (w, h):
                    if self.stage:
                        L.gs_stagesurface_destroy(self.stage)
                    self.stage = L.gs_stagesurface_create(w, h, GS_RGBA)
                    self.stage_size = (w, h)
                L.gs_texrender_reset(self.tex)
                if L.gs_texrender_begin(self.tex, w, h):
                    L.gs_clear(GS_CLEAR_COLOR, ctypes.addressof(self._vec), 0.0, 0)
                    L.gs_ortho(0.0, float(bw), 0.0, float(bh), -100.0, 100.0)
                    L.gs_blend_state_push()
                    L.gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO)
                    L.obs_source_inc_showing(src)
                    L.obs_source_video_render(src)
                    L.obs_source_dec_showing(src)
                    L.gs_blend_state_pop()
                    L.gs_texrender_end(self.tex)
                    L.gs_stage_texture(self.stage, L.gs_texrender_get_texture(self.tex))
                    data = ctypes.POINTER(ctypes.c_uint8)()
                    stride = ctypes.c_uint32(0)
                    if L.gs_stagesurface_map(self.stage, ctypes.byref(data), ctypes.byref(stride)):
                        raw = ctypes.string_at(data, stride.value * h)
                        L.gs_stagesurface_unmap(self.stage)
            finally:
                L.obs_leave_graphics()
        finally:
            L.obs_source_release(src)
        if raw is None:
            return None
        return png_from_rgba(raw, w, h, stride.value)

    def destroy(self):
        if not self.lib:
            return
        L = self.lib
        L.obs_enter_graphics()
        try:
            if self.stage:
                L.gs_stagesurface_destroy(self.stage)
            if self.tex:
                L.gs_texrender_destroy(self.tex)
        finally:
            L.obs_leave_graphics()
        self.stage = self.tex = None


class ThumbWorker(threading.Thread):
    """Renders the scenes that panels are currently looking at."""

    def __init__(self):
        super().__init__(daemon=True, name="GazMixerThumbs")
        self.stop_evt = threading.Event()
        self.thumb = Thumbnailer()
        if not self.thumb.ok:
            log("Thumbnails disabled: %s" % self.thumb.error)
        SH.thumbs_ok = self.thumb.ok

    def run(self):
        if not self.thumb.ok:
            return
        try:
            while not self.stop_evt.wait(0.04):
                if not SH.thumbs_enabled:
                    continue
                now = time.time()
                with SH.cond:
                    wanted = [n for n, t in SH.wanted.items() if now - t < 4.0]
                    ages = {n: SH.thumbs.get(n, (None, None, 0))[2] for n in wanted}
                if not wanted:
                    continue
                name = min(wanted, key=lambda n: ages[n])
                if now - ages[name] < 0.35:
                    continue
                try:
                    png = self.thumb.render(name, SH.thumb_width)
                except Exception as e:
                    log("Thumbnail error: %s" % e)
                    png = None
                with SH.cond:
                    if png:
                        SH.thumbs[name] = (png, "image/png", time.time())
                    else:
                        old = SH.thumbs.get(name)
                        SH.thumbs[name] = (old[0] if old else None, "image/png", time.time())
        finally:
            try:
                self.thumb.destroy()
            except Exception:
                pass


# --------------------------------------------------------------------------
# Web server (panel + simple HTTP API) and LAN discovery
# --------------------------------------------------------------------------
def read_file(name):
    try:
        with open(os.path.join(SCRIPT_DIR, name), "rb") as f:
            return f.read()
    except Exception:
        return None


class Handler(BaseHTTPRequestHandler):
    server_version = "GazLiveMixer/" + VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        self.do_GET()

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[-1] for k, v in parse_qs(u.query).items()}
        path = u.path.rstrip("/") or "/"
        try:
            route = ROUTES.get(path)
            if route:
                return route(self, q)
            return self._send(404, {"error": "Not found"})
        except BrokenPipeError:
            pass
        except Exception as e:
            try:
                self._send(500, {"error": str(e)})
            except Exception:
                pass


def _page(name, fallback):
    def h(req, q):
        body = read_file(name)
        req._send(200, body if body else fallback, "text/html; charset=utf-8")
    return h


def r_state(req, q):
    try:
        since = int(q.get("since", "-1"))
    except ValueError:
        since = -1
    wait = min(max(float(q.get("wait", "0") or 0), 0), 25)
    st = SH.wait_change(since, wait) if wait else SH.snapshot()
    req._send(200, st)


def r_info(req, q):
    urls = panel_urls()
    req._send(200, {"app": "gaz-live-mixer", "version": VERSION, "edition": "script",
                    "name": socket.gethostname(), "port": SH.port, "url": urls[0], "urls": urls})


def r_qr(req, q):
    text = q.get("u") or panel_urls()[0]
    svg = qr_svg(text[:300])
    if not svg:
        return req._send(404, {"error": "QR generator missing"})
    req._send(200, svg, "image/svg+xml")


def r_thumb(req, q):
    name = q.get("scene", "")
    with SH.cond:
        SH.wanted[name] = time.time()
        t = SH.thumbs.get(name)
    if not t or not t[0]:
        return req._send(404, {"error": "No thumbnail yet"})
    req._send(200, t[0], t[1])


def scene_exists(name):
    return name in (SH.snapshot().get("scenes") or [])


def cmd_route(cmd, needs_scene=False, param=None):
    def h(req, q):
        args = {}
        if needs_scene:
            name = q.get("scene", "")
            if not scene_exists(name):
                return req._send(404, {"error": "Scene not found: " + name})
            args["scene"] = name
        if param:
            args.update({k: q.get(k) for k in param})
        if "mode" in q:
            args["mode"] = q["mode"]
        SH.commands.put((cmd, args))
        req._send(200, {"ok": True})
    return h


ROUTES = {
    "/": _page("panel.html", "<h1>panel.html is missing next to gaz_live_mixer.py</h1>"),
    "/index.html": _page("panel.html", "<h1>panel.html missing</h1>"),
    "/connect": _page("connect.html", "<h1>connect.html missing</h1>"),
    "/api/state": r_state,
    "/api/info": r_info,
    "/qr.svg": r_qr,
    "/api/thumb": r_thumb,
    "/api/take": cmd_route("take", needs_scene=True),
    "/api/preview": cmd_route("preview", needs_scene=True),
    "/api/auto": cmd_route("auto"),
    "/api/cut": cmd_route("cut"),
    "/api/transition": cmd_route("transition", param=["name"]),
    "/api/duration": cmd_route("duration", param=["ms"]),
    "/api/studio": cmd_route("studio", param=["on"]),
    "/api/next": cmd_route("next"),
    "/api/prev": cmd_route("prev"),
}


class Server:
    def __init__(self):
        self.httpd = None
        self.http_thread = None
        self.udp = None
        self.udp_thread = None
        self.thumbs = None
        self.running = False

    def start(self, port):
        self.stop()
        SH.port = port
        self.running = True
        self.http_thread = threading.Thread(target=self._serve, args=(port,), daemon=True, name="GazMixerHTTP")
        self.http_thread.start()
        return True

    def _serve(self, port):
        # Retry for a while: after restarting OBS the old port can stay busy for a few seconds.
        for attempt in range(30):
            if not self.running:
                return
            try:
                self.httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
                self.httpd.daemon_threads = True
                break
            except OSError as e:
                if attempt == 0:
                    log("Port %d busy (%s), retrying..." % (port, e))
                time.sleep(2)
        else:
            log("Could not start web server on port %d. Change the port in the script settings." % port)
            self.running = False
            return
        if not self.running:
            self.httpd.server_close()
            return
        self.udp_thread = threading.Thread(target=self._discovery, daemon=True, name="GazMixerUDP")
        self.udp_thread.start()
        self.thumbs = ThumbWorker()
        self.thumbs.start()
        log("Mixer panel running at " + ", ".join(panel_urls()))
        self.httpd.timeout = 0.3
        while self.running:
            self.httpd.handle_request()
        self.httpd.server_close()

    def _discovery(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", DISCOVERY_PORT))
            s.settimeout(0.5)
            self.udp = s
        except OSError as e:
            log("LAN discovery unavailable: %s" % e)
            return
        while self.running:
            try:
                data, addr = s.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                break
            if data.startswith(DISCOVERY_MAGIC):
                reply = json.dumps({"app": "gaz-live-mixer", "name": socket.gethostname(),
                                    "port": SH.port, "version": VERSION}).encode()
                try:
                    s.sendto(reply, addr)
                except OSError:
                    pass
        try:
            s.close()
        except OSError:
            pass

    def stop(self):
        self.running = False
        with SH.cond:  # wake long-polls so their threads finish
            SH.rev += 1
            SH.cond.notify_all()
        if self.http_thread:
            self.http_thread.join(3)
            self.http_thread = None
        self.httpd = None
        if self.thumbs:
            self.thumbs.stop_evt.set()
            self.thumbs.join(2)
            self.thumbs = None
        if self.udp_thread:
            self.udp_thread.join(1.5)
            self.udp_thread = None


SERVER = Server()


# --------------------------------------------------------------------------
# OBS side: build state, run commands (always on OBS's own thread)
# --------------------------------------------------------------------------
def _name(src):
    return obs.obs_source_get_name(src) if src else None


def build_state():
    names = list(obs.obs_frontend_get_scene_names() or [])
    cur = obs.obs_frontend_get_current_scene()
    program = _name(cur)
    if cur:
        obs.obs_source_release(cur)
    studio = bool(obs.obs_frontend_preview_program_mode_active())
    preview = None
    if studio:
        pv = obs.obs_frontend_get_current_preview_scene()
        preview = _name(pv)
        if pv:
            obs.obs_source_release(pv)
    trs = obs.obs_frontend_get_transitions()
    transitions = [{"name": _name(t), "kind": obs.obs_source_get_id(t), "fixed": bool(obs.obs_transition_fixed(t))}
                   for t in trs]
    obs.source_list_release(trs)
    ct = obs.obs_frontend_get_current_transition()
    transition = _name(ct)
    if ct:
        obs.obs_source_release(ct)
    return {
        "app": "gaz-live-mixer", "version": VERSION, "host": socket.gethostname(),
        "scenes": names, "program": program, "preview": preview, "studio": studio,
        "transitions": transitions, "transition": transition,
        "duration": int(obs.obs_frontend_get_transition_duration()),
        "streaming": bool(obs.obs_frontend_streaming_active()),
        "recording": bool(obs.obs_frontend_recording_active()),
        "thumbnails": bool(SH.thumbs_enabled and SH.thumbs_ok),
    }


_restore = {"at": 0.0, "name": None}


def _find_transition(pred):
    trs = obs.obs_frontend_get_transitions()
    found = None
    for t in trs:
        if pred(t):
            found = _name(t)
            break
    obs.source_list_release(trs)
    return found


def set_transition(name):
    trs = obs.obs_frontend_get_transitions()
    for t in trs:
        if _name(t) == name:
            obs.obs_frontend_set_current_transition(t)
            break
    obs.source_list_release(trs)


def with_cut(fn):
    """Run fn using a hard cut, then put the operator's transition back."""
    cut = _find_transition(lambda t: obs.obs_source_get_id(t) == "cut_transition")
    ct = obs.obs_frontend_get_current_transition()
    current = _name(ct)
    if ct:
        obs.obs_source_release(ct)
    if not cut or cut == current:
        fn()
        return
    if _restore["name"] is None:
        _restore["name"] = current
    set_transition(cut)
    fn()
    _restore["at"] = time.time() + 0.3


def take_scene(name, mode):
    src = obs.obs_get_source_by_name(name)
    if not src:
        return

    def go():
        if obs.obs_frontend_preview_program_mode_active():
            obs.obs_frontend_set_current_preview_scene(src)
            obs.obs_frontend_preview_program_trigger_transition()
        else:
            obs.obs_frontend_set_current_scene(src)

    try:
        with_cut(go) if mode == "cut" else go()
    finally:
        obs.obs_source_release(src)


def step_scene(delta):
    names = list(obs.obs_frontend_get_scene_names() or [])
    if not names:
        return
    studio = obs.obs_frontend_preview_program_mode_active()
    cur = obs.obs_frontend_get_current_preview_scene() if studio else obs.obs_frontend_get_current_scene()
    nm = _name(cur)
    if cur:
        obs.obs_source_release(cur)
    i = names.index(nm) if nm in names else -1
    nxt = names[(i + delta) % len(names)]
    if studio:
        src = obs.obs_get_source_by_name(nxt)
        obs.obs_frontend_set_current_preview_scene(src)
        obs.obs_source_release(src)
    else:
        take_scene(nxt, "auto")


def run_command(cmd, a):
    studio = obs.obs_frontend_preview_program_mode_active()
    if cmd == "take":
        take_scene(a["scene"], a.get("mode", "auto"))
    elif cmd == "preview":
        if studio:
            src = obs.obs_get_source_by_name(a["scene"])
            if src:
                obs.obs_frontend_set_current_preview_scene(src)
                obs.obs_source_release(src)
    elif cmd in ("auto", "cut"):
        if studio:
            go = obs.obs_frontend_preview_program_trigger_transition
            with_cut(go) if cmd == "cut" else go()
    elif cmd == "transition" and a.get("name"):
        set_transition(a["name"])
        _restore["name"] = None
    elif cmd == "duration" and a.get("ms"):
        obs.obs_frontend_set_transition_duration(max(50, min(20000, int(float(a["ms"])))))
    elif cmd == "studio":
        on = a.get("on")
        val = (not studio) if on in (None, "", "toggle") else on in ("1", "true", "on")
        obs.obs_frontend_set_preview_program_mode(val)
    elif cmd == "next":
        step_scene(1)
    elif cmd == "prev":
        step_scene(-1)


_tick_n = [0]


def tick():
    did = False
    while True:
        try:
            cmd, args = SH.commands.get_nowait()
        except queue.Empty:
            break
        try:
            run_command(cmd, args)
        except Exception as e:
            log("Command %s failed: %s" % (cmd, e))
        did = True
    if _restore["name"] and _restore["at"] and time.time() >= _restore["at"]:
        set_transition(_restore["name"])
        _restore["name"], _restore["at"] = None, 0.0
        did = True
    _tick_n[0] += 1
    if did or _tick_n[0] % 2 == 0:
        try:
            SH.publish(build_state())
        except Exception as e:
            log("State error: %s" % e)


# --------------------------------------------------------------------------
# Hotkeys (keyboard, USB keypads, Stream Deck hotkeys, MIDI-to-key tools)
# --------------------------------------------------------------------------
HOTKEYS = {}  # key -> id


def _hk(key, desc, cmd, args=None):
    def cb(pressed):
        if pressed:
            SH.commands.put((cmd, dict(args or {})))
    return key, desc, cb


def _slot_cb(i, cmd):
    def cb(pressed):
        if not pressed:
            return
        names = list(obs.obs_frontend_get_scene_names() or [])
        if i < len(names):
            SH.commands.put((cmd, {"scene": names[i], "mode": "auto"}))
    return cb


def hotkey_defs():
    defs = [
        _hk("gazmixer.auto", "Gaz Mixer: AUTO (transition preview to program)", "auto"),
        _hk("gazmixer.cut", "Gaz Mixer: CUT (hard cut preview to program)", "cut"),
        _hk("gazmixer.next", "Gaz Mixer: Next scene", "next"),
        _hk("gazmixer.prev", "Gaz Mixer: Previous scene", "prev"),
        _hk("gazmixer.studio", "Gaz Mixer: Toggle studio mode", "studio"),
    ]
    for i in range(HOTKEY_SLOTS):
        defs.append(("gazmixer.take%d" % (i + 1), "Gaz Mixer: Take scene %d live" % (i + 1), _slot_cb(i, "take")))
        defs.append(("gazmixer.pvw%d" % (i + 1), "Gaz Mixer: Preview scene %d" % (i + 1), _slot_cb(i, "preview")))
    return defs


def register_hotkeys(settings):
    for key, desc, cb in hotkey_defs():
        hid = obs.obs_hotkey_register_frontend(key, desc, cb)
        HOTKEYS[key] = hid
        arr = obs.obs_data_get_array(settings, key)
        obs.obs_hotkey_load(hid, arr)
        obs.obs_data_array_release(arr)


# --------------------------------------------------------------------------
# OBS script interface
# --------------------------------------------------------------------------
def script_description():
    urls = panel_urls()
    return ("<h3>Gaz Live Mixer %s</h3>"
            "<p>Control OBS like a video mixer from your phone or another PC on the same network.</p>"
            "<p><b>Open on your phone:</b> <a href='%s'>%s</a><br>"
            "Click <b>Show phone QR code</b> below and scan it.</p>"
            "<p>Keypad / MIDI shortcuts: Settings &gt; Hotkeys &gt; search \"Gaz Mixer\".</p>"
            "<p><small>Gaz Media Production &middot; Everything Multimedia</small></p>") % (VERSION, urls[0], urls[0])


def script_defaults(settings):
    obs.obs_data_set_default_int(settings, "port", DEFAULT_PORT)
    obs.obs_data_set_default_bool(settings, "thumbs", True)
    obs.obs_data_set_default_int(settings, "thumb_width", 320)


def _open(path):
    def cb(props, prop):
        webbrowser.open("http://127.0.0.1:%d%s" % (SH.port, path))
        return False
    return cb


def _restart(props, prop):
    SERVER.start(SH.port)
    return False


def script_properties():
    p = obs.obs_properties_create()
    obs.obs_properties_add_button(p, "qr", "Show phone QR code", _open("/connect"))
    obs.obs_properties_add_button(p, "open", "Open mixer on this PC", _open("/"))
    obs.obs_properties_add_int(p, "port", "Panel port", 1024, 65535, 1)
    obs.obs_properties_add_bool(p, "thumbs", "Live scene thumbnails")
    lst = obs.obs_properties_add_list(p, "thumb_width", "Thumbnail quality",
                                      obs.OBS_COMBO_TYPE_LIST, obs.OBS_COMBO_FORMAT_INT)
    for label, val in (("Low (240 px) - weak Wi-Fi", 240), ("Normal (320 px)", 320), ("High (480 px)", 480)):
        obs.obs_property_list_add_int(lst, label, val)
    obs.obs_properties_add_button(p, "restart", "Restart panel server", _restart)
    return p


def script_update(settings):
    SH.thumbs_enabled = obs.obs_data_get_bool(settings, "thumbs")
    SH.thumb_width = obs.obs_data_get_int(settings, "thumb_width") or 320
    port = obs.obs_data_get_int(settings, "port") or DEFAULT_PORT
    if port != SH.port or not SERVER.running:
        SERVER.start(port)


def script_load(settings):
    register_hotkeys(settings)
    SH.port = obs.obs_data_get_int(settings, "port") or DEFAULT_PORT
    obs.timer_add(tick, 50)
    if not SERVER.running:
        SERVER.start(SH.port)


def script_save(settings):
    for key, hid in HOTKEYS.items():
        arr = obs.obs_hotkey_save(hid)
        obs.obs_data_set_array(settings, key, arr)
        obs.obs_data_array_release(arr)


def script_unload():
    obs.timer_remove(tick)
    SERVER.stop()
