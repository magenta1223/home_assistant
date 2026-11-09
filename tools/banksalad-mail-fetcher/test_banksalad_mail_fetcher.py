import tempfile
import unittest
from email.message import EmailMessage
from pathlib import Path

from banksalad_mail_fetcher import BanksaladMember, UnknownMember, import_new_exports

# export.zip was made with 7-Zip's ZipCrypto, the same encryption Banksalad uses; it holds
# 2025-11-09~2026-11-09.xlsx with the content b"sheet".
EXPORT_ZIP = (Path(__file__).parent / "export.zip").read_bytes()
DONGHOON = BanksaladMember("김동훈", "fixture-password", "token-donghoon")
MEMBERS = {DONGHOON.name: DONGHOON}


def export_mail(attachment_name="김동훈님_2025-11-09~2026-11-09.zip", sent="Mon, 09 Nov 2026 01:58:40 +0000"):
    message = EmailMessage()
    message["Date"] = sent
    message.set_content("export")
    message.add_attachment(EXPORT_ZIP, maintype="application", subtype="octet-stream", filename=attachment_name)
    return message


class ImportNewExportsTest(unittest.TestCase):
    def setUp(self):
        self.workdir = tempfile.TemporaryDirectory()
        self.sent_dir = Path(self.workdir.name)
        self.sent = []

    def tearDown(self):
        self.workdir.cleanup()

    def send(self, token, xlsx):
        self.sent.append((token, xlsx))
        return 111

    def failing_send(self, token, xlsx):
        raise ConnectionError("app is down")

    def test_sends_the_unzipped_xlsx_with_the_token_of_the_member_named_in_the_attachment(self):
        imported = import_new_exports(export_mail(), MEMBERS, self.sent_dir, self.send)

        self.assertEqual([("token-donghoon", b"sheet")], self.sent)
        self.assertEqual(
            [("20261109-015840_김동훈님_2025-11-09~2026-11-09.xlsx", 111)],
            [(record.name, count) for record, count in imported],
        )

    def test_does_not_send_an_export_again_once_the_app_accepted_it(self):
        import_new_exports(export_mail(), MEMBERS, self.sent_dir, self.send)

        self.assertEqual([], import_new_exports(export_mail(), MEMBERS, self.sent_dir, self.send))
        self.assertEqual(1, len(self.sent))

    def test_sends_a_later_export_of_the_same_period(self):
        import_new_exports(export_mail(), MEMBERS, self.sent_dir, self.send)
        import_new_exports(export_mail(sent="Tue, 10 Nov 2026 08:00:00 +0000"), MEMBERS, self.sent_dir, self.send)

        self.assertEqual(2, len(self.sent))

    def test_retries_an_export_the_app_did_not_accept(self):
        with self.assertRaises(ConnectionError):
            import_new_exports(export_mail(), MEMBERS, self.sent_dir, self.failing_send)

        self.assertEqual(1, len(import_new_exports(export_mail(), MEMBERS, self.sent_dir, self.send)))

    def test_rejects_an_export_of_someone_not_in_the_member_list(self):
        with self.assertRaises(UnknownMember):
            import_new_exports(export_mail("홍길동님_2025-11-09~2026-11-09.zip"), MEMBERS, self.sent_dir, self.send)

        self.assertEqual([], self.sent)

    def test_rejects_a_wrong_export_password_without_sending(self):
        members = {"김동훈": BanksaladMember("김동훈", "wrong", "token-donghoon")}

        with self.assertRaises(RuntimeError):
            import_new_exports(export_mail(), members, self.sent_dir, self.send)

        self.assertEqual([], self.sent)

    def test_ignores_attachments_that_are_not_exports(self):
        self.assertEqual([], import_new_exports(export_mail("notice.pdf"), MEMBERS, self.sent_dir, self.send))


if __name__ == "__main__":
    unittest.main()
