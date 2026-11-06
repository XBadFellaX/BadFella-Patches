package app.badfella.patches.linkedin

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val LINKEDIN = Compatibility(
    name = "LinkedIn",
    packageName = "com.linkedin.android",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x0A66C2,
    targets = listOf(
        AppTarget(
            version = "4.1.1271",
            versionCode = 217900,
            description = "Version 4.1.1271, build 217900.",
        ),
    ),
)

/** The one per-update presenter entry the reviewed feed renders through. */
internal object LinkedInUpdatePresenterFingerprint : Fingerprint(
    definingClass = "Lcom/linkedin/android/feed/framework/presentercreator/update/FeedUpdateTransformer;",
    name = "createUpdatePresenter",
    returnType = "Lcom/linkedin/android/feed/framework/presenter/update/UpdatePresenter;",
    parameters = listOf(
        "Lcom/linkedin/android/pegasus/dash/gen/voyager/dash/feed/Update;",
        "Lcom/linkedin/android/feed/framework/update/UpdateCollapseViewData;",
        "Lcom/linkedin/android/feed/framework/core/FeedRenderContext;",
        "Lcom/linkedin/android/feed/framework/transformer/update/UpdateTransformationConfig;",
    ),
)

/**
 * The parsed-page entry the streaming feed hands every network page and cache hit to.
 *
 * The body reads `CollectionTemplate.elements` at instruction 0 and schedules the superclass
 * `addAll(CollectionTemplate)` on its worker executor, so filtering the template before that read
 * removes a model before the paged list, the presenter mapping or impression accounting sees it.
 */
internal object LinkedInStreamingTemplateFingerprint : Fingerprint(
    definingClass = "Lcom/linkedin/android/infra/paging/" +
        "BaseStreamingPagedResource\$DataManagerBackedStreamingPagedList;",
    name = "addAll",
    returnType = "V",
    parameters = listOf("Lcom/linkedin/android/pegasus/gen/collection/CollectionTemplate;"),
)

/**
 * The superclass every template-backed paged list reaches with the same parsed page.
 *
 * `DataManagerBackedStreamingPagedList` reaches it from its worker lambda, and
 * `DataManagerBackedPagedResourceBase$CachedPagedList`/`NonCachedPagedList` call it directly, so
 * this is the funnel that also covers cached and non-streaming update lists.
 */
internal object LinkedInTemplatePagedListFingerprint : Fingerprint(
    definingClass = "Lcom/linkedin/android/infra/paging/CollectionTemplatePagedList;",
    name = "addAll",
    returnType = "V",
    parameters = listOf("Lcom/linkedin/android/pegasus/gen/collection/CollectionTemplate;"),
)

/**
 * The dedupe funnel a parsed page's element list passes through after the template hook.
 *
 * This is the LinkedIn equivalent of Hush's `FeedUnitCollectionManager.addNewEdgeToCollection`
 * hook and stays as a second layer for lists that reach `DefaultObservableList.addAll(Collection)`
 * without a `CollectionTemplate`.
 */
internal object LinkedInStreamingListFingerprint : Fingerprint(
    definingClass = "Lcom/linkedin/android/infra/paging/" +
        "BaseStreamingPagedResource\$DataManagerBackedStreamingPagedList;",
    name = "addAll",
    returnType = "Z",
    parameters = listOf("Ljava/util/Collection;"),
)

/** The slim bottom bar applies its reviewed HomeTabInfo list here. */
internal object LinkedInBottomBarItemsFingerprint : Fingerprint(
    definingClass = "Lcom/linkedin/android/home/bottomnav/SlimBottomBar;",
    name = "setItems",
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
)
