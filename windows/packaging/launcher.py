"""PyInstaller entry point."""
import multiprocessing

from mvm.app import main

if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(main())
