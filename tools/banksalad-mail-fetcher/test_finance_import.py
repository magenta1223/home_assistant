import base64
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from finance_import import ImportFailed, send_export


class StubFinanceImportApi(BaseHTTPRequestHandler):
    """Answers every import with the status and body the test set, and keeps the last request."""

    status = 200
    body = {}
    received = None

    def do_POST(self):
        length = int(self.headers["Content-Length"])
        StubFinanceImportApi.received = (self.path, self.headers["Authorization"], json.loads(self.rfile.read(length)))
        payload = json.dumps(StubFinanceImportApi.body, ensure_ascii=False).encode("utf-8")
        self.send_response(StubFinanceImportApi.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


class SendExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), StubFinanceImportApi)
        cls.base_url = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def answer(self, status, body):
        StubFinanceImportApi.status = status
        StubFinanceImportApi.body = body

    def test_posts_the_xlsx_as_base64_with_the_members_token(self):
        self.answer(200, {"savedTransactionCount": 111})

        saved = send_export(self.base_url + "/", "token-a", b"sheet")

        path, authorization, body = StubFinanceImportApi.received
        self.assertEqual(111, saved)
        self.assertEqual("/api/finance/import", path)
        self.assertEqual("Bearer token-a", authorization)
        self.assertEqual(b"sheet", base64.b64decode(body["contentBase64"]))

    def test_reports_a_rejected_export_with_the_apps_reason(self):
        self.answer(400, {"error": "가계부 내역 sheet is missing"})

        with self.assertRaisesRegex(ImportFailed, "HTTP 400: .*가계부 내역"):
            send_export(self.base_url, "token-a", b"sheet")

    def test_reports_an_unreachable_app(self):
        with self.assertRaisesRegex(ImportFailed, "unreachable"):
            send_export("http://127.0.0.1:1", "token-a", b"sheet")


if __name__ == "__main__":
    unittest.main()
