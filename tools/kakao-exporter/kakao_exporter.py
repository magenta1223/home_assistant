"""Exports allowlisted KakaoTalk PC chats with KakaoTalk's own "save chat" and sends each to the app's import API.

It drives the KakaoTalk window only: it never reads KakaoTalk's local DB files or process episode.
Each export is kept as runtime/kakao/exports/<chatKey>.txt and posted whole; the app analyzes only new messages.

Usage (Windows, unlocked desktop, KakaoTalk signed in):
    py -3 tools/kakao-exporter/kakao_exporter.py [--only <chatKey>]
"""

import argparse
import ctypes
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from pywinauto import Desktop
from pywinauto.keyboard import send_keys

from knowledge_import import KakaoSource, send_export

REPO_ROOT = Path(__file__).resolve().parents[2]
EXPORT_DIR = REPO_ROOT / "runtime" / "kakao" / "exports"
DEFAULT_IMPORT_URL = "http://127.0.0.1:8080"
CHAT_KEY = re.compile(r"[A-Za-z0-9_-]+")
MAIN_TITLE = "카카오톡"
MAIN_WINDOW_CLASS = r"MainWindow_QMLTYPE_\d+"
CHAT_WINDOW_CLASS = re.compile(r"\w*ChatWindow_QMLTYPE_\d+")
MAIN_TABS_TITLE = "메인"
CHAT_TAB_TITLE = "채팅"
CHAT_SEARCH_CLASS = r"TextField_QMLTYPE_\d+"
ROOM_OPEN_TIMEOUT_SECONDS = 10
SAVE_TIMEOUT_SECONDS = 60


class ExportError(Exception):
    """One room could not be exported; the other rooms still run."""


class DesktopUnavailable(Exception):
    """Nothing can be exported in this run, e.g. the screen or KakaoTalk is locked."""


@dataclass(frozen=True)
class KakaoChat:
    """One allowlisted chat: the KakaoTalk room room_name, saved as <chat_key>.txt and imported as source."""

    chat_key: str
    room_name: str
    source: KakaoSource


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
    raw = read_setting("KAKAO_EXPORTER_CHATS_JSON")
    if not raw:
        raise SystemExit("KAKAO_EXPORTER_CHATS_JSON is not set; nothing is allowlisted")
    return [chat_from_config(chat) for chat in json.loads(raw)]


def chat_from_config(chat):
    chat_key = chat["chatKey"].strip()
    if not CHAT_KEY.fullmatch(chat_key):
        raise SystemExit(f"chatKey must match {CHAT_KEY.pattern}, as it names the export file: {chat_key}")
    source = KakaoSource(
        name=chat["sourceName"].strip(),
        is_public=chat["isPublic"],
        allowed_user_ids=tuple(chat.get("allowedUserIds", ())),
    )
    return KakaoChat(chat_key, chat["roomName"].strip(), source)


def import_api_token():
    token = read_setting("KAKAO_EXPORTER_API_TOKEN")
    if not token:
        raise SystemExit("KAKAO_EXPORTER_API_TOKEN is not set; the app would reject the import")
    return token


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
    """The signed-in KakaoTalk PC (Qt client) main window, reached through UI Automation."""
    main = Desktop(backend="uia").window(title=MAIN_TITLE, class_name_re=MAIN_WINDOW_CLASS)
    if not main.exists(timeout=5):
        raise DesktopUnavailable("KakaoTalk main window is not open; start KakaoTalk and sign in")
    return main


def open_room(main, room_name):
    """
    Searches the chat list and opens the top result.
    The opened window's title must equal room_name exactly, and exactly one such window must be open.
    Room names are assumed unique and stable, which the allowlist owner guarantees.
    """
    process_id = main.wrapper_object().process_id()
    main.set_focus()
    main_tabs = main.child_window(title=MAIN_TABS_TITLE, control_type="Tab")
    main_tabs.child_window(title=CHAT_TAB_TITLE, control_type="TabItem").select()
    send_keys("^f")
    main.child_window(class_name_re=CHAT_SEARCH_CLASS, control_type="Edit").wait("visible", timeout=5)
    send_keys("^a")
    send_keys(room_name, with_spaces=True)
    time.sleep(1.0)
    before = {w.handle for w in chat_windows(process_id)}
    send_keys("{ENTER}")
    exact = wait_for_room_windows(process_id, room_name)
    for wrong in (w for w in chat_windows(process_id) if w.handle not in before and w.window_text() != room_name):
        wrong.close()
    if len(exact) != 1:
        main.set_focus()
        send_keys("{ESC}")
        raise ExportError(f"expected exactly one chat window titled as the allowlisted room, found {len(exact)}")
    return exact[0]


def wait_for_room_windows(process_id, room_name):
    """Chat windows titled room_name, once at least one has opened or the wait runs out."""
    deadline = time.monotonic() + ROOM_OPEN_TIMEOUT_SECONDS
    while True:
        exact = [w for w in chat_windows(process_id) if w.window_text() == room_name]
        if exact or time.monotonic() >= deadline:
            return exact
        time.sleep(0.5)


def chat_windows(process_id):
    """Top-level KakaoTalk chat room windows."""
    return [
        w for w in Desktop(backend="uia").windows(process=process_id)
        if CHAT_WINDOW_CLASS.fullmatch(w.element_info.class_name)
    ]


def save_room(room, pending):
    """KakaoTalk's "save chat" (Ctrl+S) opens a standard Save As dialog; the file appears once it finishes writing."""
    if pending.exists():
        pending.unlink()
    room.set_focus()
    send_keys("^s")
    dialog = Desktop(backend="uia").window(handle=room.handle).child_window(class_name="#32770", control_type="Window")
    dialog.wait("visible", timeout=10)
    dialog.child_window(class_name="Edit", control_type="Edit").set_edit_text(str(pending))
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


def export_chat(main, chat):
    """Saves the room and returns the path of its complete UTF-8 export."""
    pending = EXPORT_DIR / f"{chat.chat_key}.tmp.txt"
    room = open_room(main, chat.room_name)
    try:
        save_room(room, pending)
    finally:
        room.close()
    normalize_to_utf8(pending)
    export = EXPORT_DIR / f"{chat.chat_key}.txt"
    os.replace(pending, export)
    return export


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", help="export only this chatKey")
    args = parser.parse_args()

    chats = [chat for chat in load_chats() if args.only in (None, chat.chat_key)]
    if not chats:
        raise SystemExit(f"chatKey {args.only} is not allowlisted")
    import_url = read_setting("KAKAO_IMPORT_URL") or DEFAULT_IMPORT_URL
    token = import_api_token()
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)
    try:
        require_unlocked_desktop()
        main_window = kakao_main_window()
    except DesktopUnavailable as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    failed = 0
    for chat in chats:
        try:
            export = export_chat(main_window, chat)
            outcome = send_export(import_url, token, chat.source, export.read_text(encoding="utf-8"))
            print(f"{chat.chat_key}: {outcome.value}")
        except Exception as error:  # one room failing must not stop the others
            failed += 1
            print(f"error: {chat.chat_key}: {error}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
