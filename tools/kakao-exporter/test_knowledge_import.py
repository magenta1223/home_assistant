import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from knowledge_import import ImportFailed, ImportOutcome, KakaoSource, send_export

SOURCE = KakaoSource(name="hongseungmin", is_public=False, allowed_user_ids=("member-a", "member-b"))


class StubImportApi(BaseHTTPRequestHandler):
    """Answers every import with the status and body the test set, and keeps the last request."""

    status = 200
    body = {}
    received = None

    def do_POST(self):
        length = int(self.headers["Content-Length"])
        StubImportApi.received = (self.path, self.headers["Authorization"], json.loads(self.rfile.read(length)))
        payload = json.dumps(StubImportApi.body).encode("utf-8")
        self.send_response(StubImportApi.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class SendExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), StubImportApi)
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def answer(self, status, body):
        StubImportApi.status = status
        StubImportApi.body = body

    def test_posts_the_whole_export_as_a_kakao_source_with_its_audience(self):
        self.answer(200, {"memories": []})

        send_export(self.base_url, "secret", SOURCE, "exported text")

        path, authorization, body = StubImportApi.received
        self.assertEqual("/api/knowledge/import/analyze", path)
        self.assertEqual("Bearer secret", authorization)
        self.assertEqual(
            {
                "sourceType": "KAKAO",
                "sourceName": "hongseungmin",
                "isPublic": False,
                "allowedUserIds": ["member-a", "member-b"],
                "text": "exported text",
            },
            body,
        )

    def test_ok_means_new_messages_were_analyzed(self):
        self.answer(200, {"memories": []})

        self.assertEqual(ImportOutcome.ANALYZED, send_export(self.base_url, "secret", SOURCE, "text"))

    def test_duplicate_conflict_means_nothing_was_new(self):
        self.answer(409, {"error": "all source records have already been analyzed", "alreadyAnalyzedRecordCount": 3})

        self.assertEqual(ImportOutcome.ALREADY_ANALYZED, send_export(self.base_url, "secret", SOURCE, "text"))

    def test_audience_conflict_fails(self):
        self.answer(409, {"error": "source hongseungmin already has another audience"})

        with self.assertRaisesRegex(ImportFailed, "HTTP 409"):
            send_export(self.base_url, "secret", SOURCE, "text")

    def test_rejected_import_fails_with_the_app_error(self):
        self.answer(401, {"error": "authentication required"})

        with self.assertRaisesRegex(ImportFailed, "HTTP 401: .*authentication required"):
            send_export(self.base_url, "secret", SOURCE, "text")

    def test_unreachable_app_fails(self):
        with self.assertRaisesRegex(ImportFailed, "unreachable"):
            send_export("http://127.0.0.1:1", "secret", SOURCE, "text")


if __name__ == "__main__":
    unittest.main()
