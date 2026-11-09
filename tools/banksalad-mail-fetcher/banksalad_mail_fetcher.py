"""Saves Banksalad exports from Gmail into BANKSALAD_EXPORT_DIR, where the app's folder sync imports them.

Banksalad mails each export as a password-protected zip holding one xlsx. This opens the inbox read-only over IMAP,
never changes the mailbox, unzips each attachment with BANKSALAD_EXPORT_PASSWORD and saves the xlsx once as
<received yyyyMMdd-HHmmss>_<xlsx name>; a file that already exists is skipped, so rerunning is safe.

Usage:
    py -3 tools/banksalad-mail-fetcher/banksalad_mail_fetcher.py
"""

import email
import imaplib
import io
import os
import sys
import zipfile
from datetime import date, timedelta
from email import policy
from email.utils import parsedate_to_datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
IMAP_HOST = "imap.gmail.com"
MAILBOX = "INBOX"
LOOKBACK_DAYS = 7
ARCHIVE_SUFFIX = ".zip"
EXPORT_SUFFIX = ".xlsx"


class MailboxUnavailable(Exception):
    """Nothing can be fetched in this run, e.g. a missing setting or a rejected login."""


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


def required_setting(name):
    """The setting's value; a missing one makes the whole run impossible."""
    value = read_setting(name)
    if not value:
        raise MailboxUnavailable(f"{name} is not set")
    return value


def fetch_recent_exports(user, app_password, sender):
    """Mails from sender received in the last LOOKBACK_DAYS days, so a few days with the PC off lose nothing."""
    since = (date.today() - timedelta(days=LOOKBACK_DAYS)).strftime("%d-%b-%Y")
    try:
        with imaplib.IMAP4_SSL(IMAP_HOST) as imap:
            imap.login(user, app_password)
            imap.select(MAILBOX, readonly=True)
            _, found = imap.search(None, "FROM", f'"{sender}"', "SINCE", since)
            return [fetch_message(imap, number) for number in found[0].split()]
    except (imaplib.IMAP4.error, OSError) as error:
        raise MailboxUnavailable(f"cannot read {MAILBOX} of {user}: {error}") from error


def fetch_message(imap, number):
    """The whole mail; PEEK keeps it unread."""
    _, data = imap.fetch(number, "(BODY.PEEK[])")
    return email.message_from_bytes(data[0][1], policy=policy.default)


def save_exports(message, export_dir, password):
    """Saves the xlsx files inside the message's zip attachments that are not in export_dir yet and returns their paths."""
    received = parsedate_to_datetime(message["Date"])
    saved = []
    for archive in zip_attachments(message):
        for entry in archive.infolist():
            export_name = Path(entry.filename).name
            if not export_name.lower().endswith(EXPORT_SUFFIX):
                continue
            target = export_dir / f"{received:%Y%m%d-%H%M%S}_{export_name}"
            if target.exists():
                continue
            write_atomically(target, archive.read(entry, pwd=password.encode("utf-8")))
            saved.append(target)
    return saved


def zip_attachments(message):
    """Banksalad sends the zip as application/octet-stream, so the attachment is recognized by its name."""
    return [
        zipfile.ZipFile(io.BytesIO(part.get_content()))
        for part in message.iter_attachments()
        if (part.get_filename() or "").lower().endswith(ARCHIVE_SUFFIX)
    ]


def write_atomically(target, data):
    """The app polls export_dir, so it must never see a half-written file."""
    pending = target.with_name(target.name + ".tmp")
    pending.write_bytes(data)
    os.replace(pending, target)


def main():
    """Exit code 0 when every export is saved, 1 when some mail failed, 2 when the mailbox cannot be read."""
    try:
        export_dir = Path(required_setting("BANKSALAD_EXPORT_DIR"))
        password = required_setting("BANKSALAD_EXPORT_PASSWORD")
        messages = fetch_recent_exports(
            required_setting("BANKSALAD_MAIL_IMAP_USER"),
            required_setting("BANKSALAD_MAIL_IMAP_APP_PASSWORD"),
            required_setting("BANKSALAD_MAIL_SENDER"),
        )
    except MailboxUnavailable as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    export_dir.mkdir(parents=True, exist_ok=True)
    failed = 0
    for message in messages:
        try:
            for path in save_exports(message, export_dir, password):
                print(f"saved: {path.name}")
        except (OSError, RuntimeError, zipfile.BadZipFile) as error:  # e.g. a wrong password; other mails still run
            failed += 1
            print(f"error: {message['Subject']}: {error}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
