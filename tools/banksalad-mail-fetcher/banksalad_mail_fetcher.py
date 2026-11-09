"""Imports the Banksalad exports that family members mail to one Gmail inbox into the app, each as its member's own.

Banksalad mails each export as a password-protected zip named <name>님_<period>.zip holding one xlsx. This opens the
inbox read-only over IMAP and never changes the mailbox, finds the member by the name in the attachment, unzips it
with that member's export password and posts the xlsx to the finance import API with that member's token.
Each export the app accepted is kept under runtime/banksalad/sent/ and never sent again; a failed one is retried
on the next run.

Usage:
    py -3 tools/banksalad-mail-fetcher/banksalad_mail_fetcher.py
"""

import email
import imaplib
import io
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass
from datetime import date, timedelta
from email import policy
from email.utils import parsedate_to_datetime
from pathlib import Path

from finance_import import ImportFailed, send_export

REPO_ROOT = Path(__file__).resolve().parents[2]
SENT_DIR = REPO_ROOT / "runtime" / "banksalad" / "sent"
DEFAULT_IMPORT_URL = "http://127.0.0.1:8080"
IMAP_HOST = "imap.gmail.com"
MAILBOX = "INBOX"
LOOKBACK_DAYS = 7
EXPORT_ARCHIVE = re.compile(r"(?P<name>.+?)님_.*\.zip", re.IGNORECASE)
EXPORT_SUFFIX = ".xlsx"


class MailboxUnavailable(Exception):
    """Nothing can be fetched in this run, e.g. a missing setting or a rejected login."""


class UnknownMember(Exception):
    """The export belongs to a name that is not in the member list, so nobody can own it."""


@dataclass(frozen=True)
class BanksaladMember:
    """One family member: the name Banksalad puts in the attachment, the zip password and the app token."""

    name: str
    export_password: str
    api_token: str


@dataclass(frozen=True)
class BanksaladExport:
    """One xlsx from a mail; record_name identifies it among everything already sent."""

    member: BanksaladMember
    record_name: str
    xlsx: bytes


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


def load_members():
    """Members keyed by their Banksalad name, from BANKSALAD_FETCHER_MEMBERS_JSON."""
    members = [
        BanksaladMember(member["name"].strip(), member["exportPassword"], member["apiToken"])
        for member in json.loads(required_setting("BANKSALAD_FETCHER_MEMBERS_JSON"))
    ]
    return {member.name: member for member in members}


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


def import_new_exports(message, members, sent_dir, send):
    """
    Sends each export of the message that is not in sent_dir yet with send(token, xlsx) -> saved count,
    records it once the app accepted it, and returns the record paths with their saved counts.
    """
    imported = []
    for export in exports_in(message, members):
        record = sent_dir / export.record_name
        if record.exists():
            continue
        saved_count = send(export.member.api_token, export.xlsx)
        write_atomically(record, export.xlsx)
        imported.append((record, saved_count))
    return imported


def exports_in(message, members):
    """The xlsx files inside the message's export zips, each unzipped with the password of the member it names."""
    received = parsedate_to_datetime(message["Date"])
    exports = []
    for part in message.iter_attachments():
        archive_name = Path(part.get_filename() or "").name
        matched = EXPORT_ARCHIVE.fullmatch(archive_name)
        if not matched:
            continue
        member = members.get(matched["name"])
        if member is None:
            raise UnknownMember(f"{archive_name} names {matched['name']}, who is not in BANKSALAD_FETCHER_MEMBERS_JSON")
        archive = zipfile.ZipFile(io.BytesIO(part.get_content()))
        exports += [
            BanksaladExport(
                member,
                f"{received:%Y%m%d-%H%M%S}_{Path(archive_name).stem}{EXPORT_SUFFIX}",
                archive.read(entry, pwd=member.export_password.encode("utf-8")),
            )
            for entry in archive.infolist()
            if entry.filename.lower().endswith(EXPORT_SUFFIX)
        ]
    return exports


def write_atomically(target, data):
    """A run stopped mid-write must not leave a record that marks the export as sent."""
    pending = target.with_name(target.name + ".tmp")
    pending.write_bytes(data)
    os.replace(pending, target)


def main():
    """Exit code 0 when every export is imported, 1 when some mail failed, 2 when the run cannot start."""
    try:
        members = load_members()
        import_url = read_setting("BANKSALAD_IMPORT_URL") or DEFAULT_IMPORT_URL
        messages = fetch_recent_exports(
            required_setting("BANKSALAD_MAIL_IMAP_USER"),
            required_setting("BANKSALAD_MAIL_IMAP_APP_PASSWORD"),
            required_setting("BANKSALAD_MAIL_SENDER"),
        )
    except MailboxUnavailable as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    SENT_DIR.mkdir(parents=True, exist_ok=True)
    failed = 0
    for message in messages:
        try:
            imported = import_new_exports(
                message, members, SENT_DIR, lambda token, xlsx: send_export(import_url, token, xlsx)
            )
            for record, saved_count in imported:
                print(f"imported: {record.name} ({saved_count} transactions)")
        except UnknownMember as error:
            print(f"warning: {error}", file=sys.stderr)
        except (ImportFailed, OSError, RuntimeError, zipfile.BadZipFile) as error:  # other mails still run
            failed += 1
            print(f"error: {message['Subject']}: {error}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
