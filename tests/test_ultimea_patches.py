"""Unit tests for Ultimea patches and verification."""
import json
from pathlib import Path
import unittest
import zipfile

class UltimeaPatchesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[1]
        cls.catalog = json.loads((cls.root / 'patches-list.json').read_text(encoding='utf-8'))
        cls.ultimea_patches = [
            p for p in cls.catalog['patches']
            if any(c['packageName'] == 'com.dynanic.ultimea' for c in p['compatiblePackages'])
        ]

    def test_catalog_contains_exact_two_ultimea_patches(self):
        self.assertEqual(len(self.ultimea_patches), 2)
        names = {p['name'] for p in self.ultimea_patches}
        self.assertEqual(names, {'Ultimea Black Mode', 'Ultimea Hide Explorer Tab'})

    def test_ultimea_black_mode_metadata(self):
        patch = next(p for p in self.ultimea_patches if p['name'] == 'Ultimea Black Mode')
        compat = next(c for c in patch['compatiblePackages'] if c['packageName'] == 'com.dynanic.ultimea')
        self.assertEqual(compat['name'], 'Ultimea')
        self.assertEqual(len(compat['targets']), 1)
        target = compat['targets'][0]
        self.assertEqual(target['version'], '1.4.4')
        self.assertEqual(target['versionCodes'], {'ARM64_V8A': 495})
        self.assertFalse(target['isExperimental'])

    def test_ultimea_hide_explorer_metadata(self):
        patch = next(p for p in self.ultimea_patches if p['name'] == 'Ultimea Hide Explorer Tab')
        compat = next(c for c in patch['compatiblePackages'] if c['packageName'] == 'com.dynanic.ultimea')
        self.assertEqual(compat['name'], 'Ultimea')
        self.assertEqual(len(compat['targets']), 1)
        target = compat['targets'][0]
        self.assertEqual(target['version'], '1.4.4')
        self.assertEqual(target['versionCodes'], {'ARM64_V8A': 495})
        self.assertFalse(target['isExperimental'])

    def test_no_engine_or_connection_hooks(self):
        source = self.root / 'patches/src/main/kotlin/app/badfella/patches/ultimea'
        text = '\n'.join(p.read_text(encoding='utf-8') for p in source.rglob('*.kt'))
        for symbol in ('libflutter.so', 'FlutterImageDecoder', 'FineEqualizer', 'Xupmix',
                       'spoofSignaturePatch', 'patchesSettingsPatch', 'removeDistractionsPatch',
                       'EqualizerConnectionPatch', 'NativeSettings', 'LoganMethodCall'):
            self.assertNotIn(symbol, text)

    def test_android_bundle(self):
        version = next(line.split('=', 1)[1].strip() for line in
                       (self.root / 'gradle.properties').read_text().splitlines()
                       if line.startswith('version ='))
        bundle = self.root / f'patches/build/libs/patches-{version}.mpp'
        if not bundle.exists():
            self.skipTest('Build the Android patch bundle first')
        with zipfile.ZipFile(bundle) as archive:
            for name in archive.namelist():
                if name.endswith(('.class', '.dex')):
                    for package in (b'javax/imageio/', b'java/awt/', b'javax/swing/'):
                        self.assertNotIn(package, archive.read(name), name)
            self.assertIn('ultimea/theme.bin', archive.namelist())
            for icon in ('hdmi', 'usb', 'optical', 'aux', 'bluetooth'):
                self.assertIn(f'ultimea/images/device_input_{icon}_n.png', archive.namelist())

if __name__ == '__main__':
    unittest.main()
