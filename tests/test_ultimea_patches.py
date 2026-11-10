"""Ultimea 1.4.4 patch anchors stay same-length and version-pinned."""
import re
from pathlib import Path
import unittest


class UltimeaPatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[1]
        cls.source = (
            root / "patches/src/main/kotlin/app/badfella/patches/ultimea/UltimeaPatches.kt"
        ).read_text(encoding="utf-8")

    def test_compatibility_is_version_pinned(self):
        self.assertIn('packageName = "com.dynanic.ultimea"', self.source)
        self.assertIn('version = "1.4.4"', self.source)
        self.assertIn("versionCode = 495", self.source)
        self.assertIn("apkFileType = ApkFileType.XAPK", self.source)

    def test_replacement_pairs_preserve_length(self):
        pairs = re.findall(r'"([^"]+)" to "([^"]+)"', self.source)
        self.assertGreaterEqual(len(pairs), 4)
        for old, new in pairs:
            with self.subTest(old=old):
                self.assertEqual(len(old), len(new))

    def test_named_anchors_preserve_length(self):
        pairs = {
            "community_switch": "badfella_switch_",
            "Roboto": "bdfont",
        }
        for old, new in pairs.items():
            with self.subTest(old=old):
                self.assertIn(f'"{old}"', self.source)
                self.assertEqual(len(old), len(new))

    def test_patch_names_and_categories(self):
        for name, category in (
            ("Pitch black", "Theme"),
            ("Hide Explorer tab", "Navigation"),
            ("Use system font", "UI"),
        ):
            with self.subTest(name=name):
                self.assertIn(f'name = "{name}"', self.source)
                self.assertIn(f'category("{category}")', self.source)


if __name__ == "__main__":
    unittest.main()
