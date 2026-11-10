"""LinkedIn patch registration and source-anchor checks; no APKs or network required."""
import json
import os
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.linkedin.android"
PATCHES_DIR = ROOT / "patches/src/main/kotlin/app/badfella/patches/linkedin"
EXT_DIR = ROOT / "extensions/extension/src/main/java/app/badfella/extension/linkedin"
CLONE_KT = PATCHES_DIR / "misc/ClonePatch.kt"

EXPECTED_LINKEDIN_PATCHES = {
    "Pitch black",
    "Anonymous profile viewing",
    "Block tracking",
    "Copy text",
    "Customize navigation bar",
    "Disable double-tap like",
    "Download media",
    "Expand posts",
    "Feed filters",
    "Glass navigation bar",
    "GmsCore support",
    "Hide Premium upsells",
    "Hide ads",
    "Hide bottom tabs",
    "Hide promoted jobs",
    "Hide suggested posts",
    "Install next to LinkedIn",
    "Keep feed on reopen",
    "Material You",
    "Messaging",
    "More tab",
    "Navigation bar",
    "Open links directly",
    "Post menu items",
    "Remove gaps and dividers",
    "Sanitize share links",
    "Swipe between feeds",
    "Swipe between tabs",
    "Tap to hide in viewers",
}

NON_DEFAULT_PATCHES = {
    "Pitch black",
    "Glass navigation bar",
    "GmsCore support",
    "Install next to LinkedIn",
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

    def test_all_29_patches_declared(self):
        self.assertEqual(set(self.patches.keys()), EXPECTED_LINKEDIN_PATCHES)
        self.assertEqual(len(self.patches), 29)

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
        expected_dirs = {"ads", "download", "feed", "links", "messaging", "misc", "navigation", "shared", "theme", "tracking"}
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
            "NavigationBar.java",
            "GlassCapsule.java",
            "GlassNavigationBar.java",
            "SwipeTabs.java",
            "MoreMenu.java",
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

    def test_sponsored_tracking_hides_any_view_name(self):
        """A promoted component under any view name is dropped, not only feed-full-update."""
        sdui_src = (EXT_DIR / "SduiComponentFilter.java").read_text(encoding="utf-8")
        self.assertIn("if (Settings.hideAds() && hasSponsoredTracking(viewTracking))", sdui_src)
        # That fast path sits in shouldHide before the view-name switch.
        should_hide = sdui_src.split("public static boolean shouldHide", 1)[1]
        fast_path = should_hide.split("switch (viewName)", 1)[0]
        self.assertIn("hasSponsoredTracking(viewTracking)", fast_path)

    def test_sdui_feed_update_covers_all_sponsored_markers_and_labels(self):
        sdui_src = (EXT_DIR / "SduiComponentFilter.java").read_text(encoding="utf-8")
        for marker in ("urn:li:sponsoredContentV2:", "urn:li:sponsoredContent:",
                       "urn:li:sponsoredCreative:", "urn:li:sponsoredUpdate:",
                       "urn:li:adCreative:", "urn:li:adUpdate:", "urn:li:promotedUpdate:",
                       "sponsoredcontent", "sponsoredcreative", "leadgen"):
            self.assertIn(f'"{marker}"', sdui_src)
        self.assertIn("hasPromotedLabel(scanner.strings)", sdui_src)
        self.assertIn('"Promoted"', sdui_src)
        self.assertIn('"Sponsored"', sdui_src)
        self.assertIn('"Dipromosikan"', sdui_src)
        self.assertIn("containsAny(payload, SPONSORED_MARKERS)", sdui_src)

    def test_legacy_feed_filter_ports_comprehensive_pegasus_checks(self):
        hide_ads_src = (EXT_DIR / "HideAdsPatch.java").read_text(encoding="utf-8")
        for token in ("sponsoredcontent", "sponsoredcreative", "sponsoredupdate",
                      "adcreative", "adupdate", "promotedupdate"):
            self.assertIn(f'"{token}"', hide_ads_src)
        for check in ("hasSponsoredUrn", "hasSponsoredTracking", "hasCallToAction",
                      "hasPromotedLabel", "hasBoostHeader", "boostHeader",
                      "leadGenFormContent", "hasSponsoredCarouselItem",
                      "hasSponsoredAggregatedUpdate", "resharedUpdate",
                      "supplementaryActorInfo", "subDescription",
                      "contextualTitleSupplementaryInfo", "MAX_UPDATE_DEPTH"):
            self.assertIn(check, hide_ads_src)
        # Labels are matched in every relevant locale, like the SDUI path.
        self.assertIn('lower.contains("promoted")', hide_ads_src)
        self.assertIn('lower.contains("sponsored")', hide_ads_src)
        self.assertIn('lower.contains("dipromosikan")', hide_ads_src)

    def test_sdui_component_filter_does_not_serialize_every_component(self):
        """doTransform also runs per viewport sample while scrolling: no path may serialize unconditionally."""
        sdui_src = (EXT_DIR / "SduiComponentFilter.java").read_text(encoding="utf-8")
        # rewrite returns before touching the view name when no feature that edits components is on.
        self.assertIn("if (!expandPosts && !viewer && !postMenu) return component;", sdui_src)
        # Every editing branch is gated on the component's own view name.
        self.assertIn('private static final String COMMENTARY = "feed-commentary";', sdui_src)
        self.assertIn('private static final String VIEWER_CONTAINER = "feed-update-screen-container";', sdui_src)
        self.assertIn('private static final String CONTROL_MENU = "feed-control-menu";', sdui_src)
        self.assertIn("expandPosts && COMMENTARY.equals(viewName)", sdui_src)
        self.assertIn("viewer && VIEWER_CONTAINER.equals(viewName)", sdui_src)
        self.assertIn("postMenu && viewName.startsWith(CONTROL_MENU)", sdui_src)
        # One reflective view name and one serialization per component per thread, shared by rewrite and shouldHide.
        self.assertIn("ThreadLocal<Object[]> MEMO", sdui_src)
        self.assertIn("memo[0] != component", sdui_src)
        self.assertIn("private static String viewName(Object component)", sdui_src)
        self.assertIn("private static byte[] payload(Object component)", sdui_src)
        # The AI check must not allocate a pattern/array for every component.
        self.assertNotIn("viewName.split(", sdui_src)

    def test_post_menu_scans_a_post_once(self):
        postmenu_src = (EXT_DIR / "PostMenu.java").read_text(encoding="utf-8")
        self.assertIn("private static long signature(byte[] payload)", postmenu_src)
        self.assertIn("if (remembered.containsKey(signature)) return;", postmenu_src)
        # The payload is parsed once and shared with the media extraction.
        self.assertIn("ProtoScanner scanner = ProtoScanner.scan(payload);", postmenu_src)
        self.assertIn("MediaItem.extract(scanner, false)", postmenu_src)
        self.assertNotIn("MediaItem.extract(ProtoScanner.scan(payload)", postmenu_src)


class LinkedInCloneTests(unittest.TestCase):
    """The Install next to LinkedIn patch: its catalog entry, its manifest rename and its code rename."""

    def test_catalog_entry_options_and_default(self):
        catalog = json.loads((ROOT / "patches-list.json").read_text(encoding="utf-8"))
        patch = next(patch for patch in catalog["patches"] if patch["name"] == "Install next to LinkedIn")
        self.assertFalse(patch["default"])
        self.assertEqual(patch["category"], "Misc")
        self.assertEqual([compat["packageName"] for compat in patch["compatiblePackages"]], [PACKAGE])
        options = {option["key"]: option for option in patch["options"]}
        self.assertEqual(set(options), {"packageName", "appName"})
        self.assertEqual(options["packageName"]["default"], "com.linkedin.android.clone")
        self.assertEqual(options["appName"]["default"], "LinkedIn Clone")
        self.assertEqual(options["packageName"]["type"], "kotlin.String")
        self.assertEqual(options["appName"]["type"], "kotlin.String")
        self.assertTrue(options["packageName"]["required"] and options["appName"]["required"])

    def test_patch_is_opt_in_and_linkedin_only(self):
        source = CLONE_KT.read_text(encoding="utf-8")
        self.assertIn('name = PATCH_NAME', source)
        self.assertIn('default = false', source)
        self.assertIn('category("Misc")', source)
        self.assertIn("compatibleWith(COMPATIBILITY_LINKEDIN)", source)
        self.assertIn("key = \"packageName\"", source)
        self.assertIn("key = \"appName\"", source)
        self.assertIn("clonePackageProblem(it) == null", source)
        self.assertIn("cloneLabelProblem(it) == null", source)

    def test_manifest_rename_covers_package_permissions_authorities_and_classes(self):
        source = CLONE_KT.read_text(encoding="utf-8")
        for token in (
            'root.setAttribute("package", clone)',
            'private const val AUTHORITIES = "android:authorities"',
            'private const val AFFINITY = "android:taskAffinity"',
            'private const val PROCESS = "android:process"',
            "PERMISSION_DECLARATIONS",
            "PERMISSION_REQUESTS",
            "GUARDS",
            "underClone(name, clone)",
            "cloneAuthority(",
            'private const val TAB_HOST = "cct.$LINKEDIN"',
            'element.setAttribute(HOST, "cct.$clone")',
            "SPOOFED_PACKAGE_NAME",
            "CLONE_LABEL_RESOURCE",
            "CLASS_NAMES",
            'value.startsWith(".")',
            "LINKEDIN + value",
        ):
            self.assertIn(token, source)

    def test_code_rename_handles_authorities_content_uris_and_host(self):
        source = CLONE_KT.read_text(encoding="utf-8")
        for token in (
            "renameCloneStrings",
            "CONST_STRING_JUMBO",
            '"content://"',
            "namesAuthority",
            "cloneAuthority(authority, clone)",
        ):
            self.assertIn(token, source)

    def test_package_and_app_name_validation_refuse_stock_names(self):
        source = CLONE_KT.read_text(encoding="utf-8")
        self.assertIn("private val TAKEN = listOf(", source)
        self.assertIn('"$LINKEDIN.debug"', source)
        self.assertIn('"$LINKEDIN.learning"', source)
        self.assertIn('"$LINKEDIN.salesnavigator"', source)
        self.assertIn("private val PACKAGE_PART = Regex", source)
        self.assertIn("LABEL_MARKS", source)


if __name__ == "__main__":
    unittest.main()
