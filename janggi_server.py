# -*- coding: utf-8 -*-
"""
장기 엔진판 — Fairy-Stockfish 연결 서버
같은 폴더의 Fairy-Stockfish를 이 PC의 CPU 코어 전부로 실행하고,
브라우저의 장기판 화면(장기엔진판.html)과 연결합니다. Python 표준 라이브러리만 사용합니다.
"""
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

APP_DIR = (os.path.dirname(os.path.abspath(sys.executable)) if getattr(sys, "frozen", False) else os.path.dirname(os.path.abspath(__file__)))
HTML_NAME = "장기엔진판.html"
SETTINGS_PATH = os.path.join(APP_DIR, "janggi_settings.json")


def load_settings():
    try:
        with open(SETTINGS_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_settings(d):
    try:
        with open(SETTINGS_PATH, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
    except OSError:
        pass


def engine_rank(name):
    """Fairy-Stockfish 실행 파일 우선순위: 빠른 빌드일수록 앞 (9800X3D = AVX-512 지원)."""
    n = name.lower()
    order = ("avx512", "vnni512", "vnni256", "avx2", "bmi2", "modern")
    for i, k in enumerate(order):
        if k in n:
            return i
    return len(order)


def find_engine():
    """반환: 실행 명령 리스트. --engine nn 이면 신경망(GPU) 엔진, 경로를 주면 그 엔진."""
    if "--engine" in sys.argv:
        spec = sys.argv[sys.argv.index("--engine") + 1]
        if spec == "nn":
            return [sys.executable, os.path.join(APP_DIR, "nn", "nn_uci.py")]
        if spec.endswith(".py"):
            return [sys.executable, spec]
        return [spec]
    if len(sys.argv) > 1 and os.path.isfile(sys.argv[1]):
        return [sys.argv[1]]
    cands = [f for f in os.listdir(APP_DIR) if "stockfish" in f.lower()
             and (f.lower().endswith(".exe") or (os.name != "nt" and os.access(os.path.join(APP_DIR, f), os.X_OK)))]
    cands.sort(key=lambda f: ("fairy" not in f.lower(), engine_rank(f), f))
    return [os.path.join(APP_DIR, cands[0])] if cands else None


def total_ram_mb():
    try:
        if os.name == "nt":
            import ctypes

            class MS(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            m = MS()
            m.dwLength = ctypes.sizeof(MS)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
            return int(m.ullTotalPhys // (1024 * 1024))
        return int(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") // (1024 * 1024))
    except Exception:
        return 4096


def pick_hash(ram):
    # 전체 메모리의 1/4, 2의 거듭제곱, 128MB~16384MB (윈도우와 다른 프로그램 몫은 남겨 둠)
    target = max(128, min(16384, ram // 4))
    h = 128
    while h * 2 <= target:
        h *= 2
    return h


class Engine:
    def __init__(self, cmd, threads, hash_mb, nnue_file=None):
        self.nnue_file = nnue_file
        flags = 0x08000000 if os.name == "nt" else 0
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, bufsize=1, cwd=os.path.dirname(os.path.abspath(cmd[-1])), creationflags=flags,
                                  encoding="utf-8", errors="replace")
        self.q = queue.Queue()
        threading.Thread(target=self._reader, daemon=True).start()
        self.lock = threading.Lock()
        self.idle = threading.Event()
        self.ready_evt = threading.Event()
        self.cfg_lock = threading.Lock()
        self.idle.set()
        self.search = None
        self.sid = 0
        self.name = "Fairy-Stockfish"
        self.options = {}
        try:
            self._init(threads, hash_mb)
        except Exception:
            try:
                self.p.kill()
            except Exception:
                pass
            for f in (self.p.stdin, self.p.stdout):
                try:
                    f.close()
                except Exception:
                    pass
            raise
        threading.Thread(target=self._dispatch, daemon=True).start()

    def _reader(self):
        for line in self.p.stdout:
            self.q.put(line.rstrip("\r\n"))
        self.q.put(None)

    def send(self, s):
        self.p.stdin.write(s + "\n")
        self.p.stdin.flush()

    def _wait(self, prefix, timeout=30):
        end = time.time() + timeout
        out = []
        while time.time() < end:
            try:
                line = self.q.get(timeout=0.5)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("엔진이 시작 중에 종료되었습니다. CPU에 맞지 않는 버전일 수 있습니다.")
            out.append(line)
            if line.startswith(prefix):
                return out
        raise RuntimeError(f"엔진 응답 없음 ({prefix})")

    def _init(self, threads, hash_mb):
        self.send("uci")
        for l in self._wait("uciok"):
            m = re.match(r"id name (.+)", l)
            if m:
                self.name = m.group(1).strip()
            m = re.match(r"option name (.+?) type (\w+)(.*)", l)
            if m:
                self.options[m.group(1)] = m.group(3)
        if "janggi" not in self.options.get("UCI_Variant", "").split():
            raise RuntimeError("이 엔진은 장기를 지원하지 않습니다. 'largeboard' 버전의 Fairy-Stockfish가 필요합니다.")
        vars_ = self.options.get("UCI_Variant", "").split()
        # 카카오 장기와 같은 규칙(빅장 없음, 점수 판정, 동형반복 금지)
        self.variant = "janggimodern" if "janggimodern" in vars_ else "janggi"
        self.send(f"setoption name UCI_Variant value {self.variant}")
        self.send(f"setoption name Threads value {threads}")
        self.send(f"setoption name Hash value {hash_mb}")
        if self.nnue_file and "EvalFile" in self.options:
            self.send(f"setoption name EvalFile value {self.nnue_file}")
            self.send("isready")
            self._wait("readyok", timeout=60)
            # 신경망 형식은 첫 계산 때 검사되므로 짧게 계산시켜 확인 (안 맞으면 엔진이 꺼짐 → 내장 신경망으로)
            self.send("position fen rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1")
            self.send("go depth 1")
            self._wait("bestmove", timeout=30)
        self.send("isready")
        self._wait("readyok", timeout=180)
        self.threads, self.hash = threads, hash_mb
        dev = self.options.get("Device", "")
        self.device = "cuda" if "cuda" in dev else ("cpu-nn" if "cpu" in dev else "cpu")
        # 엔진의 한수쉼 표기 확인 (화면은 궁 제자리 형식 e2e2 를 씀)
        self.pass_token = None
        if self.device == "cpu":
            self.send("position fen rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1")
            self.send("go perft 1")
            for l in self._wait("Nodes searched", timeout=20):
                tk = l.split(":")[0].strip()
                if tk in ("0000", "@@@@", "pass"):
                    self.pass_token = tk

    def _dispatch(self):
        while True:
            line = self.q.get()
            if line is None:
                print("엔진이 종료되었습니다.")
                os._exit(1)
            s = self.search
            if line.startswith("info") and " pv " in line and s:
                info = parse_info(line)
                if info.get("multipv", 1) == 1:
                    with self.lock:
                        info["time_ms"] = int((time.time() - s["t0"]) * 1000)
                        s["info"] = info
            elif line.strip() == "readyok":
                self.ready_evt.set()
            elif line.startswith("bestmove"):
                if s:
                    parts = line.split()
                    with self.lock:
                        s["best"] = parts[1] if len(parts) > 1 else "(none)"
                        s["done"] = True
                self.idle.set()

    def hash_max(self):
        m = re.search(r"max (\d+)", self.options.get("Hash", ""))
        return int(m.group(1)) if m else 33554432

    def configure(self, threads, hash_mb):
        """대국 중에도 해시/스레드 변경. 진행 중인 탐색은 멈춘 뒤 적용."""
        with self.cfg_lock:
            if not self.idle.is_set():
                self.send("stop")
                self.idle.wait(10)
            self.ready_evt.clear()
            self.send(f"setoption name Threads value {threads}")
            self.send(f"setoption name Hash value {hash_mb}")
            self.send("isready")
            ok = self.ready_evt.wait(180)
            if ok:
                self.threads, self.hash = threads, hash_mb
            return ok

    def go(self, req):
        with self.lock:
            busy = not self.idle.is_set()
        if busy:
            self.send("stop")
            self.idle.wait(10)
        self.sid += 1
        s = {"id": self.sid, "info": None, "best": None, "done": False, "t0": time.time()}
        self.idle.clear()
        self.search = s
        skill = int(req.get("skill", 20))
        if "Skill Level" in self.options:
            self.send(f"setoption name Skill Level value {skill}")
        cmd = f"position fen {req['fen']}"
        if req.get("moves"):
            mv = req["moves"]
            if self.pass_token:
                mv = [self.pass_token if (len(m) >= 4 and m[:len(m) // 2] == m[len(m) // 2:]) else m for m in mv]
            cmd += " moves " + " ".join(mv)
        self.send(cmd)
        sm = ""
        if req.get("searchmoves"):     # 동형반복 금지 등으로 둘 수 있는 수가 제한될 때
            ms = req["searchmoves"]
            if self.pass_token:
                ms = [self.pass_token if (len(m) >= 4 and m[:len(m) // 2] == m[len(m) // 2:]) else m for m in ms]
            sm = " searchmoves " + " ".join(ms)
        if req.get("infinite"):
            self.send("go infinite" + sm)
        else:
            self.send(f"go movetime {int(req.get('movetime', 3000))}" + sm)
        return s["id"]

    def stop(self, sid=None):
        with self.lock:
            cur = self.search["id"] if self.search else None
        if not self.idle.is_set() and (sid is None or sid == cur):
            self.send("stop")

    def poll(self, sid):
        with self.lock:
            s = self.search
            if not s or s["id"] != sid:
                return {"id": sid, "stale": True}
            return {"id": sid, "info": s["info"], "best": s["best"], "done": s["done"]}


def parse_info(line):
    t = line.split()
    d = {}
    i = 1
    while i < len(t):
        w = t[i]
        if w in ("depth", "seldepth", "multipv", "nodes", "nps", "time", "hashfull"):
            try:
                d[w] = int(t[i + 1])
            except (IndexError, ValueError):
                pass
            i += 2
        elif w == "score":
            if i + 2 < len(t):
                d["cp" if t[i + 1] == "cp" else "mate"] = int(t[i + 2])
            i += 3
            if i < len(t) and t[i] in ("lowerbound", "upperbound"):
                i += 1
        elif w == "pv":
            d["pv"] = t[i + 1:]
            break
        else:
            i += 1
    return d


ENGINE = None
RAM_MB = 4096


def settings_info():
    cpus = os.cpu_count() or 2
    max_hash = max(128, min(RAM_MB - 4096, ENGINE.hash_max()))
    return {"threads": ENGINE.threads, "hash": ENGINE.hash, "cpus": cpus, "ram_mb": RAM_MB, "max_hash": max_hash}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path in ("/", "/index.html"):
            try:
                with open(os.path.join(APP_DIR, HTML_NAME), "rb") as f:
                    self._send(200, f.read(), "text/html; charset=utf-8")
            except OSError:
                self._send(404, {"error": f"{HTML_NAME} 파일이 없습니다."})
        elif u.path == "/api/hello":
            self._send(200, {"ok": True, "name": ENGINE.name, "threads": ENGINE.threads, "hash": ENGINE.hash,
                             "cpus": os.cpu_count(),
                             "nnue": "nnue" in ENGINE.options.get("EvalFile", "").lower(),
                             "device": ENGINE.device, "variant": getattr(ENGINE, "variant", "janggi")})
        elif u.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
        elif u.path == "/api/settings":
            self._send(200, settings_info())
        elif u.path == "/api/poll":
            sid = int(parse_qs(u.query).get("id", ["0"])[0])
            self._send(200, ENGINE.poll(sid))
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0))
        try:
            req = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            req = {}
        if u.path == "/api/go":
            if not re.fullmatch(r"[0-9A-Za-z/ \-]+", req.get("fen", "")) or \
                    not all(re.fullmatch(r"[a-i]\d{1,2}[a-i]\d{1,2}|0000|@@@@", m)
                            for m in req.get("moves", []) + req.get("searchmoves", [])):
                self._send(400, {"error": "bad position"})
                return
            self._send(200, {"id": ENGINE.go(req)})
        elif u.path == "/api/stop":
            ENGINE.stop(req.get("id"))
            self._send(200, {"ok": True})
        elif u.path == "/api/settings":
            info = settings_info()
            try:
                h = int(req.get("hash", ENGINE.hash))
                t = int(req.get("threads", ENGINE.threads))
            except (TypeError, ValueError):
                self._send(400, {"error": "숫자가 아닙니다."})
                return
            h = max(16, min(h, info["max_hash"]))
            t = max(1, min(t, info["cpus"]))
            print(f"설정 변경: 스레드 {t} · 해시 {h}MB 적용 중...")
            if ENGINE.configure(t, h):
                save_settings({"hash": h, "threads": t})
                print("설정 적용 완료")
                self._send(200, dict(settings_info(), ok=True))
            else:
                self._send(500, {"error": "엔진이 설정을 적용하지 못했습니다(메모리 부족일 수 있음)."})
        else:
            self._send(404, {"error": "not found"})


def main():
    global ENGINE, RAM_MB
    path = find_engine()
    if not path or not os.path.isfile(path[-1]):
        print("Fairy-Stockfish 실행 파일을 이 폴더에 넣어 주세요.")
        input("엔터를 누르면 닫힙니다.")
        return
    threads = os.cpu_count() or 2
    ram = RAM_MB = total_ram_mb()
    hash_mb = pick_hash(ram)
    saved = load_settings()            # 앱에서 바꾼 설정
    if saved.get("hash"):
        hash_mb = max(16, min(int(saved["hash"]), max(128, ram - 4096)))
    if saved.get("threads"):
        threads = max(1, min(int(saved["threads"]), threads))
    if "--hash" in sys.argv:   # 직접 지정: --hash 32768 (MB)
        try:
            want = int(sys.argv[sys.argv.index("--hash") + 1])
            limit = max(128, ram - 4096)          # 최소 4GB는 윈도우용으로 남김
            hash_mb = max(16, min(want, limit))
            if hash_mb < want:
                print(f"해시 {want}MB는 메모리({ram // 1024}GB)에 비해 커서 {hash_mb}MB로 줄였습니다.")
        except (IndexError, ValueError):
            print("--hash 뒤에는 MB 단위 숫자를 넣어 주세요. 예) --hash 16384")
    print(f"엔진: {os.path.basename(path[-1])}")
    nnue = None
    if not path[-1].endswith(".py"):
        names = sorted(f for f in os.listdir(APP_DIR) if f.lower().startswith("janggi") and f.lower().endswith(".nnue"))
        nnue = os.path.join(APP_DIR, names[-1]) if names else None
    try:
        try:
            ENGINE = Engine(path, threads, hash_mb, nnue)
            if nnue:
                print(f"신경망 파일: {os.path.basename(nnue)}")
        except Exception:
            if not nnue:
                raise
            print(f"{os.path.basename(nnue)} 을(를) 이 엔진에서 쓸 수 없어 엔진 내장 신경망으로 실행합니다."
                  " (최신 Fairy-Stockfish largeboard 버전이 필요할 수 있음)")
            ENGINE = Engine(path, threads, hash_mb, None)
    except Exception as e:
        print("엔진을 시작하지 못했습니다:", e)
        input("엔터를 누르면 닫힙니다.")
        return
    print(f"연결됨: {ENGINE.name}" + (" · 카카오 장기 규칙(janggimodern)" if getattr(ENGINE, "variant", "") == "janggimodern" else ""))
    if ENGINE.device == "cpu":
        print(f"CPU {threads}스레드 사용 (논리 코어 {os.cpu_count()}개) · 해시 {hash_mb}MB (메모리 {ram // 1024}GB) · 앱에서 바꿀 수 있음")
    else:
        print("신경망 + MCTS 엔진 · " + ("GPU(CUDA)로 계산" if ENGINE.device == "cuda" else "GPU를 못 찾아 CPU로 계산"))
    srv = None
    for port in range(8765, 8790):
        try:
            srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
            break
        except OSError:
            continue
    url = f"http://127.0.0.1:{srv.server_address[1]}/"
    print(f"장기판 주소: {url}")
    print("이 창을 닫으면 엔진도 종료됩니다.")
    if "--no-browser" not in sys.argv:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            ENGINE.send("quit")
        except Exception:
            pass


if __name__ == "__main__":
    main()
