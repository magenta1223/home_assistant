"""Sends one Kakao export to the app's knowledge import API, the same entry point a manual import uses."""

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from enum import Enum

IMPORT_PATH = "/api/knowledge/import/analyze"
# The app stores the new messages before it answers and analyzes them later; a large first export still takes a while to store.
TIMEOUT_SECONDS = 30 * 60
ERROR_EXCERPT_CHARS = 500


@dataclass(frozen=True)
class KakaoSource:
    """How the app stores one chat: the source name and the audience every record of it keeps."""

    name: str
    is_public: bool
    allowed_user_ids: tuple


class ImportOutcome(Enum):
    ACCEPTED = "accepted"
    ALREADY_ANALYZED = "already analyzed"


class ImportFailed(Exception):
    """The app did not accept the export, or could not be reached."""


def send_export(base_url, token, source, text):
    """
    Posts the whole export. The app stores only the messages it does not have yet and answers 202 once their
    analysis is queued, or 409 with alreadyAnalyzedRecordCount when it has analyzed all of them.
    """
    request = urllib.request.Request(
        base_url.rstrip("/") + IMPORT_PATH,
        data=json.dumps(import_body(source, text)).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            if response.status != 202:
                raise ImportFailed(f"HTTP {response.status}: {excerpt(response.read())}")
            return ImportOutcome.ACCEPTED
    except urllib.error.HTTPError as error:
        body = error.read()
        if error.code == 409 and is_already_analyzed(body):
            return ImportOutcome.ALREADY_ANALYZED
        raise ImportFailed(f"HTTP {error.code}: {excerpt(body)}") from error
    except urllib.error.URLError as error:
        raise ImportFailed(f"app is unreachable: {error.reason}") from error


def import_body(source, text):
    return {
        "sourceType": "KAKAO",
        "sourceName": source.name,
        "isPublic": source.is_public,
        "allowedUserIds": list(source.allowed_user_ids),
        "text": text,
    }


def is_already_analyzed(body):
    """A 409 also reports an audience that differs from the stored one; only the duplicate answer is success."""
    try:
        return "alreadyAnalyzedRecordCount" in json.loads(body)
    except ValueError:
        return False


def excerpt(body):
    return body.decode("utf-8", errors="replace")[:ERROR_EXCERPT_CHARS]
