# -*- coding: utf-8 -*-
"""Windows EXE entry point for Janggi Engine Board."""
import os
import sys
import importlib

# Ensure both the PyInstaller bundle directory and the EXE directory are searchable.
if getattr(sys, "frozen", False):
    bundle_dir = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(sys.executable)))
    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    for path in (bundle_dir, exe_dir):
        if path not in sys.path:
            sys.path.insert(0, path)
    os.chdir(exe_dir)
else:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)

try:
    janggi_server = importlib.import_module("janggi_server")
except Exception as exc:
    message = (
        "장기 엔진판 서버 모듈(janggi_server)을 불러오지 못했습니다.\n\n"
        f"오류: {type(exc).__name__}: {exc}\n\n"
        "프로그램 폴더 전체를 압축 해제했는지 확인해 주세요."
    )
    try:
        import tkinter.messagebox as mb
        mb.showerror("장기 엔진판 시작 오류", message)
    except Exception:
        print(message, file=sys.stderr)
    raise

if __name__ == "__main__":
    janggi_server.main()
