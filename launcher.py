# -*- coding: utf-8 -*-
"""Windows EXE entry point for Janggi Engine Board."""
import os
import sys
import runpy

# PyInstaller onedir 환경에서 모듈 검색에 의존하지 않고 서버 파일을 직접 실행한다.
if getattr(sys, "frozen", False):
    bundle_dir = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(sys.executable)))
    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    server_path = os.path.join(bundle_dir, "janggi_server.py")
    os.chdir(exe_dir)
else:
    bundle_dir = os.path.dirname(os.path.abspath(__file__))
    server_path = os.path.join(bundle_dir, "janggi_server.py")
    if bundle_dir not in sys.path:
        sys.path.insert(0, bundle_dir)

if not os.path.isfile(server_path):
    message = "서버 파일 janggi_server.py를 찾을 수 없습니다. 프로그램 폴더를 다시 압축 해제해 주세요.\n\n경로: " + server_path
    try:
        import tkinter.messagebox as mb
        mb.showerror("장기 엔진판 시작 오류", message)
    except Exception:
        print(message, file=sys.stderr)
    raise SystemExit(1)

# runpy 실행은 importlib/PYZ 모듈 탐색 문제를 우회한다.
runpy.run_path(server_path, run_name="__main__")
