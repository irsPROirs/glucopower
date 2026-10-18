"""Offline tests for bugreport extraction. No phone or ADB is used."""
import pathlib
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile


SHELL = shutil.which("powershell") or shutil.which("pwsh")
SCRIPT = pathlib.Path(__file__).with_name("expand-ble-traces.ps1").resolve()
TRACE = b"btsnoop\0" + struct.pack(">II", 1, 1002) + b"fixture-record"


@unittest.skipUnless(SHELL, "PowerShell is required")
class ExtractTracesTest(unittest.TestCase):
    def run_extractor(self, entries, existing=None):
        fixture_directory = SCRIPT.parent.parent / "diagnostics"
        fixture_directory.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="extraction-test-", dir=fixture_directory)
        root = pathlib.Path(temporary.name)
        self.assertTrue(root.resolve().is_relative_to(fixture_directory.resolve()))
        self.addCleanup(temporary.cleanup)
        archive_path = root / "report.zip"
        output = root / "traces"
        output.mkdir()
        for name, data in (existing or {}).items():
            (output / name).write_bytes(data)
        with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        subprocess.run(
            [SHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT),
             "-BugreportPath", str(archive_path), "-OutputDirectory", str(output)],
            check=True, capture_output=True,
        )
        return root, output

    def test_standard_and_vivo_names_preserve_bytes(self):
        _, output = self.run_extractor({
            "FS/data/misc/bluetooth/logs/btsnoop_hci.log": TRACE,
            "FS/data/misc/bluetooth/logs/bt_hci_20261019_014147_d.cfa": TRACE + b"vivo",
            "FS/data/misc/bluetooth/logs/btsnoop_hci.log.last": TRACE + b"last",
            "FS/data/misc/bluetooth/logs/bt_hci_fake.cfa": b"not a trace" * 3,
            "FS/data/misc/bluetooth/logs/btsnoop_short.log": b"btsnoop\0",
            "FS/data/misc/bluetooth/logs/btsnoop_bad_version.log": b"btsnoop\0" + struct.pack(">II", 2, 1002),
        })
        self.assertEqual(
            [p.read_bytes() for p in sorted(output.glob("hci-*-btsnoop.log"))],
            [TRACE, TRACE + b"vivo", TRACE + b"last"],
        )

    def test_archive_paths_are_never_output_paths(self):
        root, output = self.run_extractor({"../../btsnoop_hci.log": TRACE})
        self.assertEqual((output / "hci-1-btsnoop.log").read_bytes(), TRACE)
        self.assertEqual({p.name for p in root.iterdir()}, {"traces", "report.zip"})
        self.assertEqual({p.name for p in output.iterdir()}, {"hci-1-btsnoop.log", "hci-files.txt"})

    def test_existing_trace_is_preserved(self):
        _, output = self.run_extractor(
            {"btsnoop_hci.log": TRACE}, {"hci-1-btsnoop.log": b"existing"}
        )
        self.assertEqual((output / "hci-1-btsnoop.log").read_bytes(), b"existing")
        self.assertEqual((output / "hci-2-btsnoop.log").read_bytes(), TRACE)


if __name__ == "__main__":
    unittest.main()
