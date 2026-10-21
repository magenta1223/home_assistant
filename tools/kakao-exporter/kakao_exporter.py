"""Exports allowlisted KakaoTalk PC chats with KakaoTalk's own "save chat" into <exportDir>/<chatKey>.txt.

It drives the KakaoTalk window only: it never reads KakaoTalk's local DB files or process memory.
The allowlist is the app's own KAKAO_LOCAL_DB_CHATS_JSON, so the exporter and the app agree on chatKey and roomName.

Usage (Windows, unlocked desktop, KakaoTalk signed in):
    py -3 tools/kakao-exporter/kakao_exporter.py [--only <chatKey>]
"""

import argparse
import ctypes
import json
import os
import sys
import time
import warnings
from pathlib import Path

from pywinauto import Application, Desktop
from pywinauto.keyboard import send_keys

# KakaoTalk is a 32-bit process; the win32 messages used here work from 64-bit Python too.
warnings.filterwarnings("ignore", message="32-bit application should be automated")

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_EXPORT_DIR = "runtime/kakao/exports"
MAIN_TITLE = "카카오톡"
WINDOW_CLASS = "EVA_Window_Dblclk"
SAVE_TIMEOUT_SECONDS = 60


class ExportError(Exception):
    """One room could not be exported; the other rooms still run."""


class DesktopUnavailable(Exception):
    """Nothing can be exported in this run, e.g. the screen or KakaoTalk is locked."""


def read_setting(name):
    """Process environment first, then the repository .env, the same order the app's dotenv uses."""
    if os.environ.get(name):
        return os.environ[name]
    env_file = REPO_ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() == name:
                return value.strip()
    return None


def load_chats():
    raw = read_setting("KAKAO_LOCAL_DB_CHATS_JSON")
    if not raw:
        raise SystemExit("KAKAO_LOCAL_DB_CHATS_JSON is not set; nothing is allowlisted")
    return [(chat["chatKey"].strip(), chat["roomName"].strip()) for chat in json.loads(raw)]


def export_dir():
    path = Path(read_setting("KAKAO_EXPORT_DIR") or DEFAULT_EXPORT_DIR)
    return path if path.is_absolute() else REPO_ROOT / path


def require_unlocked_desktop():
    """UI automation cannot reach windows behind the Windows lock screen, so a locked session fails up front."""
    user32 = ctypes.windll.user32
    desktop = user32.OpenInputDesktop(0, False, 0x0100)  # DESKTOP_SWITCHDESKTOP
    if not desktop:
        raise DesktopUnavailable("Windows session is locked; unlock it before exporting")
    try:
        if not user32.SwitchDesktop(desktop):
            raise DesktopUnavailable("Windows session is locked; unlock it before exporting")
    finally:
        user32.CloseDesktop(desktop)


def kakao_main_window():
    try:
        app = Application(backend="win32").connect(title=MAIN_TITLE, class_name=WINDOW_CLASS, timeout=5)
    except Exception as error:
        raise DesktopUnavailable("KakaoTalk main window is not open; start KakaoTalk and sign in") from error
    main = app.window(title=MAIN_TITLE, class_name=WINDOW_CLASS)
    lock_view = main.child_window(title_re="LockModeView.*")
    if lock_view.exists(timeout=0) and lock_view.is_visible():
        raise DesktopUnavailable("KakaoTalk is locked with its passcode; unlock it before exporting")
    return app, main


def open_room(app, main, room_name):
    """
    Searches the chat list and opens the top result. KakaoTalk draws the results itself, so they cannot be read;
    the opened window's title must equal room_name exactly, and exactly one such window must be open.
    Room names are assumed unique and stable, which the allowlist owner guarantees.
    """
    main.set_focus()
    send_keys("^f")
    search = main.child_window(title_re="ChatRoomListView.*").child_window(class_name="Edit", control_id=100)
    search.wait("visible", timeout=5)
    search.set_edit_text(room_name)
    time.sleep(1.0)
    before = room_windows(main.process_id())
    send_keys("{ENTER}")
    time.sleep(1.5)
    opened = [w for w in room_windows(main.process_id()) if w.handle not in before]
    exact = [w for w in room_windows(main.process_id()) if w.window_text() == room_name]
    for wrong in (w for w in opened if w.window_text() != room_name):
        wrong.close()
    if len(exact) != 1:
        main.set_focus()
        send_keys("{ESC}")
        raise ExportError(f"expected exactly one chat window titled as the allowlisted room, found {len(exact)}")
    return exact[0]


def room_windows(process_id):
    """Visible KakaoTalk top-level windows other than the main window."""
    return [
        w for w in Desktop(backend="win32").windows(process=process_id, class_name=WINDOW_CLASS, visible_only=True)
        if w.window_text() not in ("", MAIN_TITLE)
    ]


def save_room(app, room, pending):
    """KakaoTalk's "save chat" (Ctrl+S) opens a standard Save As dialog; the file appears once it finishes writing."""
    if pending.exists():
        pending.unlink()
    room.set_focus()
    send_keys("^s")
    dialog = app.window(class_name="#32770")
    dialog.wait("visible", timeout=10)
    dialog.child_window(class_name="Edit", found_index=0).set_edit_text(str(pending))
    send_keys("{ENTER}")
    wait_for_complete_file(pending)


def wait_for_complete_file(path):
    deadline = time.monotonic() + SAVE_TIMEOUT_SECONDS
    last_size = -1
    while time.monotonic() < deadline:
        if path.exists():
            size = path.stat().st_size
            if size > 0 and size == last_size:
                return
            last_size = size
        time.sleep(0.5)
    raise ExportError("KakaoTalk did not finish saving the chat in time")


def normalize_to_utf8(path):
    """The app reads exports as UTF-8; an export in the legacy Korean code page is rewritten once."""
    data = path.read_bytes()
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        path.write_bytes(data.decode("cp949").encode("utf-8"))


def export_chat(app, main, target_dir, chat_key, room_name):
    pending = target_dir / f"{chat_key}.tmp.txt"
    room = open_room(app, main, room_name)
    try:
        save_room(app, room, pending)
    finally:
        room.close()
    normalize_to_utf8(pending)
    os.replace(pending, target_dir / f"{chat_key}.txt")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="export only this chatKey")
    args = parser.parse_args()

    chats = [chat for chat in load_chats() if args.only in (None, chat[0])]
    if not chats:
        raise SystemExit(f"chatKey {args.only} is not allowlisted")
    target_dir = export_dir()
    target_dir.mkdir(parents=True, exist_ok=True)
    try:
        require_unlocked_desktop()
        app, main_window = kakao_main_window()
    except DesktopUnavailable as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    failed = 0
    for chat_key, room_name in chats:
        try:
            export_chat(app, main_window, target_dir, chat_key, room_name)
            print(f"exported {chat_key}")
        except Exception as error:  # one room failing must not stop the others
            failed += 1
            print(f"error: {chat_key}: {error}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
