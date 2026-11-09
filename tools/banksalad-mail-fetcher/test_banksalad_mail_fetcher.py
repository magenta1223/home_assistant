import tempfile
import unittest
from email.message import EmailMessage
from pathlib import Path

from banksalad_mail_fetcher import save_exports

# export.zip was made with 7-Zip's ZipCrypto, the same encryption Banksalad uses; it holds
# 2025-11-09~2026-11-09.xlsx with the content b"sheet".
EXPORT_ZIP = (Path(__file__).parent / "export.zip").read_bytes()
PASSWORD = "fixture-password"


def export_mail(attachment_name="김동훈님_2025-11-09~2026-11-09.zip", data=EXPORT_ZIP,
                sent="Mon, 09 Nov 2026 01:58:40 +0000"):
    message = EmailMessage()
    message["Date"] = sent
    message.set_content("export")
    message.add_attachment(data, maintype="application", subtype="octet-stream", filename=attachment_name)
    return message


class SaveExportsTest(unittest.TestCase):
    def setUp(self):
        self.workdir = tempfile.TemporaryDirectory()
        self.export_dir = Path(self.workdir.name)

    def tearDown(self):
        self.workdir.cleanup()

    def test_saves_the_unzipped_xlsx_named_by_received_time_and_xlsx_name(self):
        saved = save_exports(export_mail(), self.export_dir, PASSWORD)

        self.assertEqual(["20261109-015840_2025-11-09~2026-11-09.xlsx"], [path.name for path in saved])
        self.assertEqual(b"sheet", saved[0].read_bytes())
        self.assertEqual([saved[0]], list(self.export_dir.iterdir()))

    def test_skips_an_export_already_saved(self):
        mail = export_mail()
        save_exports(mail, self.export_dir, PASSWORD)

        self.assertEqual([], save_exports(mail, self.export_dir, PASSWORD))

    def test_keeps_two_exports_of_the_same_period_from_different_mails(self):
        save_exports(export_mail(), self.export_dir, PASSWORD)
        save_exports(export_mail(sent="Tue, 10 Nov 2026 08:00:00 +0000"), self.export_dir, PASSWORD)

        self.assertEqual(2, len(list(self.export_dir.iterdir())))

    def test_ignores_attachments_that_are_not_zip(self):
        self.assertEqual([], save_exports(export_mail("notice.pdf", b"pdf"), self.export_dir, PASSWORD))

    def test_rejects_a_wrong_password_without_leaving_a_file(self):
        with self.assertRaises(RuntimeError):
            save_exports(export_mail(), self.export_dir, "wrong")

        self.assertEqual([], list(self.export_dir.iterdir()))


if __name__ == "__main__":
    unittest.main()
