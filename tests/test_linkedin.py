"""LinkedIn patch registration and source-anchor checks; no APKs or network required."""
import json
import os
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.linkedin.android"
PATCHES_DIR = ROOT / "patches/src/main/kotlin/app/badfella/patches/linkedin"
EXT_DIR = ROOT / "extensions/extension/src/main/java/app/badfella/extension/linkedin"

EXPECTED_LINKEDIN_PATCHES = {
    "AMOLED black bars",
    "Anonymous profile viewing",
    "Block tracking",
    "Copy text",
    "Disable double-tap like",
    "Download media",
    "Feed filters",
    "GmsCore support",
    "Hide Premium upsells",
    "Hide ads",
    "Hide bottom tabs",
    "Hide promoted jobs",
    "Hide suggested posts",
    "Keep feed on reopen",
    "Material You",
    "Messaging",
    "Open links directly",
    "Post menu items",
    "Remove gaps and dividers",
    "Sanitize share links",
    "Swipe between feeds",
    "Tap to hide in viewers",
}

NON_DEFAULT_PATCHES = {
    "AMOLED black bars",
    "GmsCore support",
    "Material You",
    "Remove gaps and dividers",
}


class LinkedInCatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "patches-list.json").read_text(encoding="utf-8"))
        self.patches = {
            patch["name"]: patch for patch in self.catalog["patches"]
            if any((compat or {}).get("packageName") == PACKAGE
                   for compat in patch["compatiblePackages"] or [])
        }

    def test_all_22_patches_declared(self):
        self.assertEqual(set(self.patches.keys()), EXPECTED_LINKEDIN_PATCHES)
        self.assertEqual(len(self.patches), 22)

    def test_reviewed_targets_and_version_agnosticism(self):
        for name, patch in self.patches.items():
            targets = patch["compatiblePackages"][0]["targets"]
            self.assertEqual(len(targets), 3, f"{name} should have 3 targets")
            self.assertEqual(targets[0]["version"], "4.1.1267")
            self.assertEqual(targets[1]["version"], "4.1.1271")
            self.assertEqual(set(targets[1]["versionCodes"].values()), {217900})
            self.assertFalse(targets[1]["isExperimental"])
            # Third target is experimental wildcard for future version support
            self.assertIsNone(targets[2]["version"])
            self.assertTrue(targets[2]["isExperimental"])

    def test_default_patches(self):
        for name, patch in self.patches.items():
            is_default = patch.get("default", True)
            if name in NON_DEFAULT_PATCHES:
                self.assertFalse(is_default, f"{name} should be default=False")
            else:
                self.assertTrue(is_default, f"{name} should be default=True")


class LinkedInSourceTests(unittest.TestCase):
    def test_subpackages_structure(self):
        subdirs = {p.name for p in PATCHES_DIR.iterdir() if p.is_dir()}
        expected_dirs = {"ads", "download", "feed", "links", "messaging", "misc", "shared", "theme", "tracking"}
        self.assertTrue(expected_dirs.issubset(subdirs))

    def test_constants_and_compatibility(self):
        constants_kt = (PATCHES_DIR / "shared/Constants.kt").read_text(encoding="utf-8")
        self.assertIn('"com.linkedin.android"', constants_kt)
        self.assertIn('"4.1.1271"', constants_kt)
        self.assertIn('versionCode = 217900', constants_kt)
        self.assertIn('isExperimental = true', constants_kt)
        self.assertIn('EXTENSION_PACKAGE = "Lapp/badfella/extension/linkedin"', constants_kt)

    def test_shared_sdui_component_filter_patch(self):
        shared_kt = (PATCHES_DIR / "shared/SharedPatches.kt").read_text(encoding="utf-8")
        self.assertIn("sduiComponentFilterPatch", shared_kt)
        self.assertIn("settingsPatch", shared_kt)
        self.assertIn("SduiComponentTransformFingerprint", shared_kt)
        self.assertIn("Lcom/linkedin/sdui/transformer/impl/ComponentTransformer;", shared_kt)
        self.assertIn("MainActivityOnCreateFingerprint", shared_kt)
        self.assertIn("NavPanelTransformFingerprint", shared_kt)
        self.assertIn("->shouldHide(Ljava/lang/Object;)Z", shared_kt)
        self.assertIn("SDUI_FILTER_CLASS", shared_kt)

    def test_hide_ads_patch_and_legacy_feed(self):
        hide_ads_kt = (PATCHES_DIR / "ads/HideAdsPatch.kt").read_text(encoding="utf-8")
        self.assertIn("hideAdsPatch", hide_ads_kt)
        self.assertIn("sduiComponentFilterPatch", hide_ads_kt)
        self.assertIn("SponsoredDuplicatesFilterFingerprint", hide_ads_kt)
        self.assertIn("copyWithoutSponsored", hide_ads_kt)
        self.assertIn("CollectionTemplate", hide_ads_kt)

    def test_extension_classes_exist(self):
        expected_classes = [
            "SduiComponentFilter.java",
            "Settings.java",
            "SettingsActivity.java",
            "SduiMediaDownload.java",
            "PostMenu.java",
            "DocumentPdf.java",
            "DownloadMediaPatch.java",
            "FeedSwipe.java",
            "HideAdsPatch.java",
            "LinkCleaner.java",
            "MessagingPatch.java",
            "MaterialYou.java",
            "NavPanelPatch.java",
            "OpenLinksDirectlyPatch.java",
            "Proto.java",
            "ProtoEdit.java",
            "ProtoScanner.java",
            "Reflect.java",
            "ShareLinkPatch.java",
            "ShortLinkResolver.java",
            "TrackingPatch.java",
            "ViewerChrome.java",
            "WhatsNew.java",
        ]
        for cls in expected_classes:
            self.assertTrue((EXT_DIR / cls).is_file(), f"Missing extension class: {cls}")

    def test_sdui_component_filter_has_essential_hooks(self):
        sdui_src = (EXT_DIR / "SduiComponentFilter.java").read_text(encoding="utf-8")
        self.assertIn("public static boolean shouldHide(Object component)", sdui_src)
        self.assertIn("public static Object rewrite(Object component)", sdui_src)
        self.assertIn("isAiView", sdui_src)
        self.assertIn("isGameView", sdui_src)
        self.assertIn("hideFeedUpdate", sdui_src)
        self.assertIn("hidePromotedJob", sdui_src)
        self.assertIn("hasViewTracking", sdui_src)


if __name__ == "__main__":
    unittest.main()
