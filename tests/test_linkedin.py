"""LinkedIn patch registration and source-anchor checks; no APKs or network required."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.linkedin.android"
FILTER = (ROOT / "extensions/extension/src/main/java/app/badfella/extension/linkedin/"
                 "LinkedInFilter.java")

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
        source = FILTER.read_text(encoding="utf-8")
        for method in ["isSponsoredPost", "isPromotedJob", "isSuggestedCarousel", "isVideoPost"]:
            self.assertIn(f"public static boolean {method}(", source)
        for method in ["filterSponsoredPosts", "filterPromotedJobs",
                       "filterSuggestedCarousels", "filterVideos"]:
            self.assertIn(f"public static List {method}(Collection items)", source)
        for method in ["filterCollectionTemplate", "shouldDropUpdate"]:
            self.assertIn(f"public static ", source)
            self.assertIn(f"{method}(", source)
        for enable in ["enableHideSponsoredPosts", "enableHidePromotedJobs",
                       "enableHideSuggestedCarousels", "enableHideFeedVideos"]:
            self.assertIn(f"public static void {enable}()", source)
        self.assertIn("public static List filterTabs(", source)
        self.assertIn("sponsoredTracking", source)
        self.assertIn("hasLinkedInVideoComponentValue", source)
        self.assertIn("RELATIONSHIPS", source)
        self.assertIn("SUGGESTED_CAROUSEL_TYPES", source)

    def test_fingerprints_use_reviewed_anchors(self):
        compat = (ROOT / "patches/src/main/kotlin/app/badfella/patches/linkedin/"
                          "LinkedInCompat.kt").read_text(encoding="utf-8")
        self.assertIn("packageName = \"com.linkedin.android\"", compat)
        self.assertIn("version = \"4.1.1271\"", compat)
        self.assertIn("versionCode = 217900", compat)
        self.assertIn("Lcom/linkedin/android/feed/framework/presentercreator/update/"
                      "FeedUpdateTransformer;", compat)
        self.assertIn('name = "createUpdatePresenter"', compat)
        self.assertIn("BaseStreamingPagedResource\\$DataManagerBackedStreamingPagedList;", compat)
        self.assertIn("Lcom/linkedin/android/infra/paging/CollectionTemplatePagedList;", compat)
        self.assertIn('parameters = listOf("Lcom/linkedin/android/pegasus/gen/collection/'
                      'CollectionTemplate;")', compat)
        self.assertIn('returnType = "V"', compat)
        self.assertIn('name = "addAll"', compat)
        self.assertIn('parameters = listOf("Ljava/util/Collection;")', compat)
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
        for selection in ["filterSponsoredPosts", "filterPromotedJobs",
                          "filterSuggestedCarousels", "filterVideos"]:
            self.assertIn(f'"{selection}"', feed)
        for enable in ["enableHideSponsoredPosts", "enableHidePromotedJobs",
                       "enableHideSuggestedCarousels", "enableHideFeedVideos"]:
            self.assertIn(f'"{enable}"', feed)
        self.assertIn("LinkedInStreamingTemplateFingerprint,", feed)
        self.assertIn("LinkedInTemplatePagedListFingerprint,", feed)
        self.assertIn("LinkedInSduiPagedListFingerprint.method", feed)
        self.assertIn("LinkedInStreamingListFingerprint.method", feed)
        self.assertIn("filterCollectionTemplate(Ljava/lang/Object;)V", feed)
        self.assertIn("filterSduiCollection(Ljava/util/Collection;)Ljava/util/Collection;", feed)
        self.assertIn("move-object/from16 p1, v0", feed)

    def test_runtime_filter_uses_structural_ad_and_video_anchors(self):
        source = FILTER.read_text(encoding="utf-8")
        for anchor in ["leadGenFormContent", "sponsoredTracking", "subDescription",
                       "subDescriptionButton", "carouselContent", "hasVideoCarouselItemComponentValue",
                       "hasEntityComponentValue", "hasFollowPromptComponentValue", "accessibilityText",
                       "supplementaryActorInfo", "hasSponsoredTracking", "hasBoostHeader",
                       "boostHeader", "resharedUpdate", "callToActionComponentValue",
                       "detailHeader", "contextualHeader", "contextualDescription",
                       "entityUrn", "preDashEntityUrn", "rawUrnString", "adUrn",
                       "aggregatedContent", "serverComponentName", "semanticKey", "filterSduiCollection"]:
            self.assertIn(anchor, source)
        for token in ["sponsoredcontent", "sponsoredcreative", "sponsoredupdate",
                      "adcreative", "adupdate", "promotedupdate"]:
            self.assertIn(f'"{token}"', source)
        self.assertIn('lower.contains("promoted")', source)
        self.assertIn('lower.contains("sponsored")', source)
        self.assertIn("name.contains(\"VIDEO\")", source)

    def test_runtime_filter_does_not_treat_general_lego_token_as_sponsored(self):
        source = FILTER.read_text(encoding="utf-8")
        self.assertNotIn("legoTrackingToken", source)


def _jdk_tool(name):
    suffix = ".exe" if os.name == "nt" else ""
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / f"{name}{suffix}"
        if candidate.is_file():
            return str(candidate)
    return shutil.which(name)


BEHAVIOR_HARNESS = '''
import app.badfella.extension.linkedin.LinkedInFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LinkedInFilterBehaviorCheck {
    enum Type { UNKNOWN, MIXED_UPDATES, DISCOVER_VIDEO, VIDEO, SMALL_THEMED_VIDEOS }

    static final class Sponsored {
        String adUrn;
        String adServingUrn;
        String sponsoredCampaignUrn;
        String adTrackingCode;
        String leadTrackingCode;
    }
    static final class Tracking {
        Sponsored sponsoredTracking;
        boolean hasSponsoredTracking;
        String adUrn;
    }
    static final class Metadata { Tracking trackingData; String legoTrackingToken; }
    static final class CallToAction { String legoTrackingToken; }
    static final class Content {
        boolean hasLinkedInVideoComponentValue;
        boolean hasLargeSingleVideoComponentValue;
        boolean hasExternalVideoComponentValue;
        boolean hasVideoCarouselItemComponentValue;
        boolean hasJobComponentValue;
        boolean hasFollowPromptComponentValue;
        boolean hasEntityComponentValue;
        boolean hasFeedDiscoveryEntityComponentValue;
        boolean hasFeedDiscoveryGridComponentValue;
        boolean hasCallToActionComponentValue;
        CallToAction callToActionComponentValue;
        Actor actorComponentValue;
    }
    static final class Item { Tracking trackingData; Update update; Content content; }
    static final class Carousel {
        Type type;
        List<Item> items = new ArrayList<>();
        Carousel(Type type) { this.type = type; }
    }
    static final class Attachment { Content content; Carousel carouselContent; Update update; Urn entityUrn; }
    static final class Text {
        String text;
        String accessibilityText;
        Text(String text) { this.text = text; }
        Text(String text, String accessibilityText) {
            this.text = text;
            this.accessibilityText = accessibilityText;
        }
    }
    static final class Button { String text; Button(String text) { this.text = text; } }
    static final class Actor {
        Text subDescription;
        Button subDescriptionButton;
        Text supplementaryActorInfo;
        Text description;
    }
    static final class Header { Text text; }
    static final class ContextualHeader {
        Text title;
        Text subtitle;
        Text contextualTitle;
        Text titleSupplementaryInfo;
        Text contextualTitleSupplementaryInfo;
    }
    static final class ContextualDescription { Text text; }
    static final class Boost { String boostStatus; }
    static final class LeadGenFormContent { Carousel carouselContent; Content content; }
    static final class AggregatedContent { List<Update> updates = new ArrayList<>(); }
    static final class CollectionTemplate { List elements = new ArrayList(); }
    static final class Urn {
        String entityType;
        String rawUrnString;
        Urn(String entityType, String rawUrnString) {
            this.entityType = entityType;
            this.rawUrnString = rawUrnString;
        }
    }
    static final class Update {
        Metadata metadata = new Metadata();
        Content content = new Content();
        Attachment attachment;
        Actor actor;
        Carousel carouselContent;
        LeadGenFormContent leadGenFormContent;
        AggregatedContent aggregatedContent;
        Urn entityUrn;
        Urn preDashEntityUrn;
        boolean hasLeadGenFormContent;
        boolean hasBoostHeader;
        Boost boostHeader;
        Update resharedUpdate;
        Header header;
        Header detailHeader;
        ContextualHeader contextualHeader;
        ContextualDescription contextualDescription;
    }

    static final class SduiA11y { SduiText label; }
    static final class SduiText { String text; }
    static final class SduiProps {
        String serverComponentName;
        String semanticKey;
        String key;
        String typeId;
        SduiA11y accessibility;
    }
    static final class SduiComponent {
        SduiProps properties;
        SduiComponent childScreen;
        List<SduiComponent> columnItems;
        public SduiProps getProperties() { return properties; }
    }
    static final class ShareCarouselComponent {
        SduiProps properties;
    }
    static final class VideoSingleComponent {
        SduiProps properties;
    }

    static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
    }

    static Update organic() {
        Update organic = new Update();
        organic.actor = new Actor();
        organic.actor.subDescription = new Text("2,725,195 followers");
        organic.header = new Header();
        organic.header.text = new Text("Atlassian");
        organic.content.hasEntityComponentValue = true;
        organic.metadata.legoTrackingToken = "lego-token-present-on-organic-updates";
        organic.entityUrn = new Urn("update", "urn:li:update:(urn:li:activity:1,MAIN_FEED)");
        organic.preDashEntityUrn = new Urn("activity", "urn:li:activity:1");
        return organic;
    }

    public static void main(String[] args) {
        Update leadGen = new Update();
        leadGen.leadGenFormContent = new LeadGenFormContent();
        leadGen.hasLeadGenFormContent = true;
        leadGen.carouselContent = new Carousel(Type.VIDEO);
        leadGen.content.hasEntityComponentValue = true;
        check("lead-gen ad is sponsored", LinkedInFilter.isSponsoredPost(leadGen));
        check("lead-gen ad is not a job", !LinkedInFilter.isPromotedJob(leadGen));

        Update promotedVideo = new Update();
        promotedVideo.metadata.trackingData = new Tracking();
        promotedVideo.metadata.trackingData.sponsoredTracking = new Sponsored();
        promotedVideo.content.hasLinkedInVideoComponentValue = true;
        promotedVideo.content.hasEntityComponentValue = true;
        promotedVideo.carouselContent = new Carousel(Type.VIDEO);
        check("promoted video with entity and carousel is sponsored",
                LinkedInFilter.isSponsoredPost(promotedVideo));

        Update labelled = new Update();
        labelled.actor = new Actor();
        labelled.actor.subDescription = new Text("Promoted");
        labelled.content.hasLargeSingleVideoComponentValue = true;
        check("Promoted actor label is sponsored", LinkedInFilter.isSponsoredPost(labelled));

        Update labelledButton = new Update();
        labelledButton.actor = new Actor();
        labelledButton.actor.subDescriptionButton = new Button("Sponsored");
        check("Sponsored actor button is sponsored", LinkedInFilter.isSponsoredPost(labelledButton));

        Update accessibleOnly = new Update();
        accessibleOnly.actor = new Actor();
        accessibleOnly.actor.subDescription = new Text(null, "Promoted");
        check("Promoted accessibilityText is sponsored", LinkedInFilter.isSponsoredPost(accessibleOnly));

        Update separated = new Update();
        separated.actor = new Actor();
        separated.actor.subDescription = new Text("\\u00A02,725,195 followers \\u2022 Promoted\\u00A0");
        check("label joined with separators is sponsored", LinkedInFilter.isSponsoredPost(separated));

        Update supplementary = new Update();
        supplementary.actor = new Actor();
        supplementary.actor.subDescription = new Text("2,725,195 followers");
        supplementary.actor.supplementaryActorInfo = new Text("Promoted");
        check("supplementaryActorInfo label is sponsored", LinkedInFilter.isSponsoredPost(supplementary));

        Update described = new Update();
        described.actor = new Actor();
        described.actor.description = new Text("Promoted");
        check("actor description label is sponsored", LinkedInFilter.isSponsoredPost(described));

        Update headerLabel = new Update();
        headerLabel.header = new Header();
        headerLabel.header.text = new Text("Promoted");
        check("header label is sponsored", LinkedInFilter.isSponsoredPost(headerLabel));

        Update detailHeaderLabel = new Update();
        detailHeaderLabel.detailHeader = new Header();
        detailHeaderLabel.detailHeader.text = new Text("Sponsored");
        check("detail header label is sponsored", LinkedInFilter.isSponsoredPost(detailHeaderLabel));

        Update contextualLabel = new Update();
        contextualLabel.contextualHeader = new ContextualHeader();
        contextualLabel.contextualHeader.subtitle = new Text("Promoted");
        check("contextual header label is sponsored", LinkedInFilter.isSponsoredPost(contextualLabel));

        Update contextualDescriptionLabel = new Update();
        contextualDescriptionLabel.contextualDescription = new ContextualDescription();
        contextualDescriptionLabel.contextualDescription.text = new Text("Sponsored");
        check("contextual description label is sponsored",
                LinkedInFilter.isSponsoredPost(contextualDescriptionLabel));

        Update contentActor = new Update();
        contentActor.content.actorComponentValue = new Actor();
        contentActor.content.actorComponentValue.subDescription = new Text("Promoted");
        check("content actor label is sponsored", LinkedInFilter.isSponsoredPost(contentActor));

        Update tracked = new Update();
        tracked.metadata.trackingData = new Tracking();
        tracked.metadata.trackingData.hasSponsoredTracking = true;
        check("hasSponsoredTracking is sponsored", LinkedInFilter.isSponsoredPost(tracked));

        Update adUrnLeaf = new Update();
        adUrnLeaf.metadata.trackingData = new Tracking();
        adUrnLeaf.metadata.trackingData.sponsoredTracking = new Sponsored();
        adUrnLeaf.metadata.trackingData.sponsoredTracking.adUrn = "urn:li:adCreative:1";
        check("SponsoredMetadata adUrn is sponsored", LinkedInFilter.isSponsoredPost(adUrnLeaf));

        Update directAdUrn = new Update();
        directAdUrn.metadata.trackingData = new Tracking();
        directAdUrn.metadata.trackingData.adUrn = "urn:li:adCreative:2";
        check("trackingData adUrn is sponsored", LinkedInFilter.isSponsoredPost(directAdUrn));

        Update sponsoredTypeUrn = new Update();
        sponsoredTypeUrn.entityUrn = new Urn("sponsoredContent", "urn:li:sponsoredContent:1");
        check("sponsored entity type URN is sponsored", LinkedInFilter.isSponsoredPost(sponsoredTypeUrn));

        Update sponsoredRawUrn = new Update();
        sponsoredRawUrn.preDashEntityUrn =
                new Urn("update", "urn:li:sponsoredUpdate:(urn:li:activity:1)");
        check("sponsored raw URN is sponsored", LinkedInFilter.isSponsoredPost(sponsoredRawUrn));

        Update boosted = new Update();
        boosted.hasBoostHeader = true;
        check("boost header flag is sponsored", LinkedInFilter.isSponsoredPost(boosted));

        Update boostedComponent = new Update();
        boostedComponent.boostHeader = new Boost();
        check("boost header component is sponsored", LinkedInFilter.isSponsoredPost(boostedComponent));

        Update reshared = new Update();
        reshared.resharedUpdate = new Update();
        reshared.resharedUpdate.actor = new Actor();
        reshared.resharedUpdate.actor.subDescription = new Text("Sponsored");
        check("reshared promoted update is sponsored", LinkedInFilter.isSponsoredPost(reshared));

        Update aggregated = new Update();
        aggregated.aggregatedContent = new AggregatedContent();
        Update aggregatedAd = new Update();
        aggregatedAd.actor = new Actor();
        aggregatedAd.actor.subDescription = new Text("Promoted");
        aggregated.aggregatedContent.updates.add(aggregatedAd);
        check("aggregated promoted update is sponsored", LinkedInFilter.isSponsoredPost(aggregated));

        Update attachmentCta = new Update();
        attachmentCta.attachment = new Attachment();
        attachmentCta.attachment.content = new Content();
        attachmentCta.attachment.content.callToActionComponentValue = new CallToAction();
        check("attachment call-to-action card is sponsored", LinkedInFilter.isSponsoredPost(attachmentCta));

        Update contentCta = new Update();
        contentCta.content.callToActionComponentValue = new CallToAction();
        check("content call-to-action card is sponsored", LinkedInFilter.isSponsoredPost(contentCta));

        Update atlassian = new Update();
        atlassian.actor = new Actor();
        atlassian.actor.subDescription = new Text("2,725,195 followers");
        atlassian.actor.supplementaryActorInfo = new Text("Promoted");
        atlassian.content.callToActionComponentValue = new CallToAction();
        check("reported Atlassian promoted ad is sponsored", LinkedInFilter.isSponsoredPost(atlassian));

        Update organic = organic();
        check("organic update is kept", !LinkedInFilter.isSponsoredPost(organic));
        check("organic update is not a job", !LinkedInFilter.isPromotedJob(organic));
        check("organic update is not suggested", !LinkedInFilter.isSuggestedCarousel(organic));
        check("organic update is not a video", !LinkedInFilter.isVideoPost(organic));

        Update organicVideo = new Update();
        organicVideo.content.hasLinkedInVideoComponentValue = true;
        organicVideo.metadata.legoTrackingToken = "lego-token-present-on-organic-updates";
        check("organic video is not sponsored", !LinkedInFilter.isSponsoredPost(organicVideo));
        check("organic video is a video post", LinkedInFilter.isVideoPost(organicVideo));

        Update videoCarousel = new Update();
        videoCarousel.carouselContent = new Carousel(Type.DISCOVER_VIDEO);
        videoCarousel.carouselContent.items.add(new Item());
        check("video carousel is a video post", LinkedInFilter.isVideoPost(videoCarousel));
        check("video carousel is suggested", LinkedInFilter.isSuggestedCarousel(videoCarousel));
        check("organic video carousel is not a sponsored post",
                !LinkedInFilter.isSponsoredPost(videoCarousel));

        Update mixedCarousel = new Update();
        mixedCarousel.carouselContent = new Carousel(Type.MIXED_UPDATES);
        mixedCarousel.carouselContent.items.add(new Item());
        check("mixed-update carousel is suggested", LinkedInFilter.isSuggestedCarousel(mixedCarousel));
        check("mixed-update carousel is not a sponsored post",
                !LinkedInFilter.isSponsoredPost(mixedCarousel));
        check("mixed-update carousel is not a video post", !LinkedInFilter.isVideoPost(mixedCarousel));

        Update videoCarouselItems = new Update();
        videoCarouselItems.carouselContent = new Carousel(Type.MIXED_UPDATES);
        Item videoItem = new Item();
        videoItem.update = new Update();
        videoItem.update.content.hasVideoCarouselItemComponentValue = true;
        videoCarouselItems.carouselContent.items.add(videoItem);
        check("carousel with video item is a video post", LinkedInFilter.isVideoPost(videoCarouselItems));

        Update sponsoredCarousel = new Update();
        sponsoredCarousel.carouselContent = new Carousel(Type.MIXED_UPDATES);
        Item sponsoredItem = new Item();
        sponsoredItem.trackingData = new Tracking();
        sponsoredItem.trackingData.sponsoredTracking = new Sponsored();
        sponsoredCarousel.carouselContent.items.add(sponsoredItem);
        check("carousel with sponsored item is sponsored", LinkedInFilter.isSponsoredPost(sponsoredCarousel));
        check("carousel with sponsored item is suggested", LinkedInFilter.isSuggestedCarousel(sponsoredCarousel));

        Update labelledCarouselItem = new Update();
        labelledCarouselItem.carouselContent = new Carousel(Type.MIXED_UPDATES);
        Item labelledItem = new Item();
        labelledItem.update = new Update();
        labelledItem.update.actor = new Actor();
        labelledItem.update.actor.supplementaryActorInfo = new Text("Promoted");
        labelledCarouselItem.carouselContent.items.add(labelledItem);
        check("carousel item with promoted label is sponsored",
                LinkedInFilter.isSponsoredPost(labelledCarouselItem));

        Update organicEntityCarousel = new Update();
        organicEntityCarousel.carouselContent = new Carousel(Type.MIXED_UPDATES);
        organicEntityCarousel.content.hasEntityComponentValue = true;
        check("organic entity carousel is not sponsored", !LinkedInFilter.isSponsoredPost(organicEntityCarousel));
        check("organic entity carousel is still a suggestion",
                LinkedInFilter.isSuggestedCarousel(organicEntityCarousel));

        Update entityOnly = new Update();
        entityOnly.content.hasEntityComponentValue = true;
        check("entity content alone is not suggested", !LinkedInFilter.isSuggestedCarousel(entityOnly));

        Update promotedJob = new Update();
        promotedJob.metadata.trackingData = new Tracking();
        promotedJob.metadata.trackingData.sponsoredTracking = new Sponsored();
        promotedJob.content.hasJobComponentValue = true;
        check("promoted job is a job", LinkedInFilter.isPromotedJob(promotedJob));
        check("promoted job is not a sponsored post", !LinkedInFilter.isSponsoredPost(promotedJob));

        Update followPrompt = new Update();
        followPrompt.content.hasFollowPromptComponentValue = true;
        check("follow prompt is suggested", LinkedInFilter.isSuggestedCarousel(followPrompt));

        Update plain = new Update();
        check("plain update is kept", !LinkedInFilter.isSponsoredPost(plain)
                && !LinkedInFilter.isPromotedJob(plain)
                && !LinkedInFilter.isSuggestedCarousel(plain)
                && !LinkedInFilter.isVideoPost(plain));

        ArrayList organicOnly = new ArrayList();
        organicOnly.add(organic);
        check("sponsored collection filter keeps an unchanged list",
                LinkedInFilter.filterSponsoredPosts(organicOnly) == organicOnly);
        check("video collection filter keeps an unchanged list",
                LinkedInFilter.filterVideos(organicOnly) == organicOnly);

        ArrayList sponsoredItems = new ArrayList();
        sponsoredItems.add(organic);
        sponsoredItems.add(sponsoredTypeUrn);
        sponsoredItems.add(promotedJob);
        List sponsoredFiltered = LinkedInFilter.filterSponsoredPosts(sponsoredItems);
        check("sponsored collection filter removes the ad", sponsoredFiltered.size() == 2
                && sponsoredFiltered.contains(organic) && sponsoredFiltered.contains(promotedJob)
                && !sponsoredFiltered.contains(sponsoredTypeUrn));
        check("sponsored collection filter keeps the source list intact", sponsoredItems.size() == 3);

        List jobsFiltered = LinkedInFilter.filterPromotedJobs(sponsoredItems);
        check("job collection filter removes only the promoted job", jobsFiltered.size() == 2
                && jobsFiltered.contains(organic) && jobsFiltered.contains(sponsoredTypeUrn)
                && !jobsFiltered.contains(promotedJob));

        ArrayList videoItems = new ArrayList();
        videoItems.add(organic);
        videoItems.add(organicVideo);
        videoItems.add(videoCarousel);
        List videosFiltered = LinkedInFilter.filterVideos(videoItems);
        check("video collection filter removes every video", videosFiltered.size() == 1
                && videosFiltered.contains(organic));

        ArrayList suggestedItems = new ArrayList();
        suggestedItems.add(organic);
        suggestedItems.add(followPrompt);
        suggestedItems.add(videoCarousel);
        suggestedItems.add(organicEntityCarousel);
        List suggestedFiltered = LinkedInFilter.filterSuggestedCarousels(suggestedItems);
        check("suggested collection filter removes prompts and carousels",
                suggestedFiltered.size() == 1
                        && suggestedFiltered.contains(organic));

        check("nothing is dropped before a selection is enabled",
                !LinkedInFilter.shouldDropUpdate(sponsoredTypeUrn)
                        && !LinkedInFilter.shouldDropUpdate(organicVideo));

        CollectionTemplate page = new CollectionTemplate();
        page.elements.add(organic);
        page.elements.add(sponsoredTypeUrn);
        page.elements.add(promotedJob);
        LinkedInFilter.filterCollectionTemplate(page);
        check("page filter leaves an unchanged page alone before a selection is enabled",
                page.elements.size() == 3 && page.elements.contains(sponsoredTypeUrn));

        LinkedInFilter.enableHideSponsoredPosts();
        LinkedInFilter.filterCollectionTemplate(page);
        check("page filter drops the sponsored post", page.elements.size() == 2
                && page.elements.contains(organic) && page.elements.contains(promotedJob)
                && !page.elements.contains(sponsoredTypeUrn));
        check("page filter keeps the template list when nothing more matches",
                LinkedInFilter.shouldDropUpdate(sponsoredTypeUrn)
                        && !LinkedInFilter.shouldDropUpdate(organic));

        LinkedInFilter.enableHidePromotedJobs();
        LinkedInFilter.filterCollectionTemplate(page);
        check("page filter drops the promoted job", page.elements.size() == 1
                && page.elements.contains(organic));

        CollectionTemplate videoPage = new CollectionTemplate();
        videoPage.elements.add(organic);
        videoPage.elements.add(organicVideo);
        videoPage.elements.add(videoCarousel);
        LinkedInFilter.enableHideFeedVideos();
        LinkedInFilter.filterCollectionTemplate(videoPage);
        check("page filter drops every video", videoPage.elements.size() == 1
                && videoPage.elements.contains(organic));

        CollectionTemplate suggestedPage = new CollectionTemplate();
        suggestedPage.elements.add(organic);
        suggestedPage.elements.add(followPrompt);
        suggestedPage.elements.add(videoCarousel);
        LinkedInFilter.enableHideSuggestedCarousels();
        LinkedInFilter.filterCollectionTemplate(suggestedPage);
        check("page filter drops suggested carousels", suggestedPage.elements.size() == 1
                && suggestedPage.elements.contains(organic));

        CollectionTemplate immutablePage = new CollectionTemplate();
        immutablePage.elements = Collections.unmodifiableList(
                new ArrayList(Arrays.asList(organic, sponsoredTypeUrn)));
        LinkedInFilter.filterCollectionTemplate(immutablePage);
        check("page filter replaces an immutable element list", immutablePage.elements.size() == 1
                && immutablePage.elements.contains(organic));

        LinkedInFilter.filterCollectionTemplate(null);
        LinkedInFilter.filterCollectionTemplate(new Object());
        check("page filter ignores a missing template or element list", true);

        // SDUI component filtering checks
        SduiComponent sduiOrganic = new SduiComponent();
        sduiOrganic.properties = new SduiProps();
        sduiOrganic.properties.serverComponentName = "organic_feed_update";
        check("organic SDUI component is kept", !LinkedInFilter.shouldDropSduiComponent(sduiOrganic));

        SduiComponent sduiAd = new SduiComponent();
        sduiAd.properties = new SduiProps();
        sduiAd.properties.serverComponentName = "sponsored_feed_update";
        check("sponsored SDUI component by name is dropped", LinkedInFilter.shouldDropSduiComponent(sduiAd));

        SduiComponent sduiPromotedA11y = new SduiComponent();
        sduiPromotedA11y.properties = new SduiProps();
        sduiPromotedA11y.properties.accessibility = new SduiA11y();
        sduiPromotedA11y.properties.accessibility.label = new SduiText();
        sduiPromotedA11y.properties.accessibility.label.text = "Promoted";
        check("SDUI component with Promoted accessibility text is dropped",
                LinkedInFilter.shouldDropSduiComponent(sduiPromotedA11y));

        ShareCarouselComponent sduiCarousel = new ShareCarouselComponent();
        check("SDUI carousel component is dropped", LinkedInFilter.shouldDropSduiComponent(sduiCarousel));

        VideoSingleComponent sduiVideo = new VideoSingleComponent();
        check("SDUI video component is dropped", LinkedInFilter.shouldDropSduiComponent(sduiVideo));

        SduiComponent sduiRoot = new SduiComponent();
        sduiRoot.childScreen = sduiAd;
        check("SDUI root container with sponsored child is dropped",
                LinkedInFilter.shouldDropSduiComponent(sduiRoot));

        ArrayList sduiList = new ArrayList();
        sduiList.add(sduiOrganic);
        sduiList.add(sduiAd);
        sduiList.add(sduiPromotedA11y);
        sduiList.add(sduiCarousel);
        sduiList.add(sduiVideo);
        LinkedInFilter.filterSduiCollection(sduiList);
        check("filterSduiCollection retains only organic items", sduiList.size() == 1
                && sduiList.contains(sduiOrganic));

        System.out.println("PASS: LinkedIn filter behavior checks");
    }
}
'''


class LinkedInFilterBehaviorTests(unittest.TestCase):
    def test_predicates_cover_reported_ads_and_video_carousels(self):
        javac, java = _jdk_tool("javac"), _jdk_tool("java")
        if not javac or not java:
            self.skipTest("JDK javac/java not available")
        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            harness = root / "LinkedInFilterBehaviorCheck.java"
            harness.write_text(BEHAVIOR_HARNESS, encoding="utf-8")
            classes = root / "classes"
            subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes), str(FILTER), str(harness)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run([java, "-cp", str(classes), "LinkedInFilterBehaviorCheck"],
                                    check=True, capture_output=True, text=True)
        self.assertIn("PASS: LinkedIn filter behavior checks", result.stdout)


if __name__ == "__main__":
    unittest.main()
