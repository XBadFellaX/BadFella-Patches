"""Ultimea 1.4.4 patch anchors stay same-length and version-pinned."""
import gzip
import re
import struct
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = ROOT / "patches/src/main/kotlin/app/badfella/patches/ultimea/UltimeaPatches.kt"


class UltimeaPatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE_PATH.read_text(encoding="utf-8")

    def test_compatibility_is_version_pinned(self):
        self.assertIn('packageName = "com.dynanic.ultimea"', self.source)
        self.assertIn('version = "1.4.4"', self.source)
        self.assertIn("versionCode = 495", self.source)
        self.assertIn("apkFileType = ApkFileType.XAPK", self.source)

    def _anchor(self, name):
        match = re.search(rf"private val {name} = hex\(\s*(.*?)\s*\)", self.source, re.S)
        self.assertIsNotNone(match, name)
        tokens = re.findall(r"[0-9a-fA-F]{2}", match.group(1))
        return bytes(int(token, 16) for token in tokens)

    def test_binary_anchors_preserve_length(self):
        for old, new in (
            ("A64_ALLOC_OLD", "A64_ALLOC_NEW"),
            ("A64_CONSTRUCTOR_OLD", "A64_CONSTRUCTOR_NEW"),
            ("A64_ON_TAP_OLD", "A64_ON_TAP_NEW"),
            ("A32_ALLOC_OLD", "A32_ALLOC_NEW"),
        ):
            with self.subTest(old=old):
                self.assertEqual(len(self._anchor(old)), len(self._anchor(new)))

    def test_explorer_anchors_match_reviewed_instructions(self):
        self.assertEqual(self._anchor("A64_ALLOC_OLD")[:4], bytes.fromhex("c20080d2"))
        self.assertEqual(self._anchor("A64_ALLOC_NEW")[:4], bytes.fromhex("820080d2"))
        self.assertEqual(self._anchor("A64_CONSTRUCTOR_OLD")[:4], bytes.fromhex("c00080d2"))
        self.assertEqual(self._anchor("A64_CONSTRUCTOR_NEW")[:4], bytes.fromhex("800080d2"))
        self.assertEqual(self._anchor("A64_ON_TAP_OLD"), bytes.fromhex("a20b40f99f47e597403740f9002457f9"))
        self.assertEqual(self._anchor("A64_ON_TAP_NEW"), bytes.fromhex("a20b40f9 42080091 9e47e597 06000014".replace(" ", "")))
        self.assertEqual(self._anchor("A32_ALLOC_OLD")[:4], bytes.fromhex("0610a0e3"))
        self.assertEqual(self._anchor("A32_ALLOC_NEW")[:4], bytes.fromhex("0410a0e3"))

    def test_old_bogus_anchors_are_gone(self):
        for anchor in ('"community_switch"', 'badfella_switch_', '"bdfont"'):
            with self.subTest(anchor=anchor):
                self.assertNotIn(anchor, self.source)

    def test_font_option_and_resources(self):
        self.assertIn('key = "font"', self.source)
        self.assertIn('default = "rosemary"', self.source)
        for key, label in (
            ("rosemary", "Rosemary (Samsung)"),
            ("googlesans", "Google Sans"),
            ("opensans", "Open Sans"),
            ("segoeui", "Segoe UI"),
            ("system", "System Default"),
        ):
            with self.subTest(key=key):
                self.assertIn(f'"{key}" to "{label}"', self.source)
                if key != "system":
                    self.assertIn(f'"{key}" to "/fonts/{key}.ttf.gz"', self.source)

    def test_bundled_fonts_are_valid_gzipped_truetype(self):
        fonts = ROOT / "patches/src/main/resources/fonts"
        signatures = {b"\x00\x01\x00\x00", b"OTTO", b"true", b"ttcf"}
        for name in ("rosemary", "googlesans", "opensans", "segoeui"):
            with self.subTest(font=name):
                path = fonts / f"{name}.ttf.gz"
                self.assertTrue(path.is_file(), path)
                data = gzip.decompress(path.read_bytes())
                self.assertIn(data[:4], signatures)

    def test_font_injection_assets(self):
        self.assertIn('"assets/flutter_assets/fonts/custom_font.ttf"', self.source)
        self.assertIn('"assets/flutter_assets/FontManifest.json"', self.source)
        self.assertIn('"assets/flutter_assets/AssetManifest.json"', self.source)
        self.assertIn('{\\"family\\":\\"Roboto\\"', self.source)
        self.assertIn('fonts/custom_font.ttf', self.source)

    def test_dark_tokens_and_night_resources(self):
        for token in ("#161616", "#191919", "#323232", "#333333"):
            with self.subTest(token=token):
                self.assertIn(f'"{token}"', self.source)
        self.assertIn('decode(patterns = arrayOf("res/values-night*/*.xml"))', self.source)
        self.assertIn('"colorElevated"', self.source)
        self.assertIn('"android:windowBackground"', self.source)

    def test_explorer_icons_are_blanked(self):
        for path in ("store_icon.svg", "store_icon_dark.svg"):
            with self.subTest(path=path):
                self.assertIn(f"assets/images/{path}", self.source)
        self.assertIn('<svg width=\\"0\\" height=\\"0\\"/>', self.source)

    def test_patch_names_and_categories(self):
        for name, category in (
            ("Pitch black", "Theme"),
            ("Hide Explorer tab", "Navigation"),
            ("Custom font", "UI"),
        ):
            with self.subTest(name=name):
                self.assertIn(f'name = "{name}"', self.source)
                self.assertIn(f'category("{category}")', self.source)


if __name__ == "__main__":
    unittest.main()
