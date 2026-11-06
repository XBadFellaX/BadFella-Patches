"""LinkedIn patch registration and source-anchor checks; no APKs or network required."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.linkedin.android"

LINKEDIN_PATCHES = {
    "Hide sponsored posts": "Ads",
    "Hide promoted jobs": "Ads",
    "Hide suggested and follow carousels": "Feed",
    "Hide feed videos": "Feed",
    "Customize navigation bar": "Navigation",
}
NAVIGATION_OPTIONS = [
    "hide_my_network",
    "hide_post",
    "hide_notifications",
    "hide_jobs",
    "hide_video",
    "hide_news",
]


class LinkedInCatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "patches-list.json").read_text(encoding="utf-8"))
        self.patches = {
            patch["name"]: patch for patch in self.catalog["patches"]
            if any((compat or {}).get("packageName") == PACKAGE
                   for compat in patch["compatiblePackages"] or [])
        }

    def test_patch_names_and_categories_are_separate_selections(self):
        self.assertEqual({name: patch["category"] for name, patch in self.patches.items()},
                         LINKEDIN_PATCHES)

    def test_reviewed_target_is_declared(self):
        for patch in self.patches.values():
            targets = patch["compatiblePackages"][0]["targets"]
            self.assertEqual(len(targets), 1)
            self.assertEqual(targets[0]["version"], "4.1.1271")
            self.assertEqual(set(targets[0]["versionCodes"].values()), {217900})
            self.assertFalse(targets[0]["isExperimental"])

    def test_only_navigation_patch_exposes_options(self):
        for name, patch in self.patches.items():
            keys = [option["key"] for option in patch["options"]]
            if name == "Customize navigation bar":
                self.assertEqual(keys, NAVIGATION_OPTIONS)
                self.assertTrue(all(option["default"] is False for option in patch["options"]))
                self.assertTrue(all(option["type"] == "kotlin.Boolean" for option in patch["options"]))
            else:
                self.assertEqual(keys, [])


class LinkedInSourceTests(unittest.TestCase):
    def test_runtime_filter_covers_every_injected_predicate(self):
        source = (ROOT / "extensions/extension/src/main/java/app/badfella/extension/linkedin/"
                         "LinkedInFilter.java").read_text(encoding="utf-8")
        for method in ["isSponsoredPost", "isPromotedJob", "isSuggestedCarousel", "isVideoPost", "filterTabs"]:
            self.assertIn(f"public static boolean {method}(" if method != "filterTabs"
                          else "public static List filterTabs(", source)
        self.assertIn("sponsoredTracking", source)
        self.assertIn("hasLinkedInVideoComponentValue", source)
        self.assertIn("RELATIONSHIPS", source)

    def test_fingerprints_use_reviewed_anchors(self):
        compat = (ROOT / "patches/src/main/kotlin/app/badfella/patches/linkedin/"
                          "LinkedInCompat.kt").read_text(encoding="utf-8")
        self.assertIn("packageName = \"com.linkedin.android\"", compat)
        self.assertIn("version = \"4.1.1271\"", compat)
        self.assertIn("versionCode = 217900", compat)
        self.assertIn("Lcom/linkedin/android/feed/framework/presentercreator/update/"
                      "FeedUpdateTransformer;", compat)
        self.assertIn('name = "createUpdatePresenter"', compat)
        self.assertIn("Lcom/linkedin/android/home/bottomnav/SlimBottomBar;", compat)
        self.assertIn('name = "setItems"', compat)

    def test_patches_inject_only_the_extension_helper(self):
        feed = (ROOT / "patches/src/main/kotlin/app/badfella/patches/linkedin/"
                        "LinkedInFeedPatch.kt").read_text(encoding="utf-8")
        navigation = (ROOT / "patches/src/main/kotlin/app/badfella/patches/linkedin/"
                              "LinkedInNavigationPatch.kt").read_text(encoding="utf-8")
        for source in (feed, navigation):
            self.assertIn('extendWith("extensions/extension.mpe")', source)
            self.assertIn("Lapp/badfella/extension/linkedin/LinkedInFilter;", source)
        for predicate in ["isSponsoredPost", "isPromotedJob", "isSuggestedCarousel", "isVideoPost"]:
            self.assertIn(f'"{predicate}"', feed)


if __name__ == "__main__":
    unittest.main()
