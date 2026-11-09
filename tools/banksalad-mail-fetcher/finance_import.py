"""Sends one Banksalad ledger export to the app's finance import API; the token's user owns the transactions."""

import base64
import json
import urllib.error
import urllib.request

IMPORT_PATH = "/api/finance/import"
# The app parses and stores the whole export before it answers.
TIMEOUT_SECONDS = 5 * 60
ERROR_EXCERPT_CHARS = 500


class ImportFailed(Exception):
    """The app did not accept the export, or could not be reached."""


def send_export(base_url, token, xlsx):
    """
    Posts the xlsx and returns how many transactions the app saved. Sending the same export again is harmless:
    the app keys each transaction by its immutable fields and updates it in place.
    """
    request = urllib.request.Request(
        base_url.rstrip("/") + IMPORT_PATH,
        data=json.dumps({"contentBase64": base64.b64encode(xlsx).decode("ascii")}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            body = response.read()
            if response.status != 200:
                raise ImportFailed(f"HTTP {response.status}: {excerpt(body)}")
            return json.loads(body)["savedTransactionCount"]
    except urllib.error.HTTPError as error:
        raise ImportFailed(f"HTTP {error.code}: {excerpt(error.read())}") from error
    except urllib.error.URLError as error:
        raise ImportFailed(f"app is unreachable: {error.reason}") from error


def excerpt(body):
    return body.decode("utf-8", errors="replace")[:ERROR_EXCERPT_CHARS]
