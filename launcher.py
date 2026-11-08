# -*- coding: utf-8 -*-
"""Windows EXE entry point for Janggi Engine Board."""
import os
import sys

if getattr(sys, "frozen", False):
    # Keep mutable settings beside the EXE; bundled assets are copied there by PyInstaller.
    os.chdir(os.path.dirname(os.path.abspath(sys.executable)))

import janggi_server

if __name__ == "__main__":
    janggi_server.main()
