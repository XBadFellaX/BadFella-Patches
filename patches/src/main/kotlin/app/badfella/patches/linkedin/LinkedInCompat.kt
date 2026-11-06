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
 * The collection funnel every streaming update page passes through.
 *
 * `CollectionTemplatePagedList.addAll(CollectionTemplate)` ends by calling this
 * `addAll(Collection)` override with the parsed page's element list, so a model removed here never
 * enters the paged list, allocates a presenter or records an impression. This is the LinkedIn
 * equivalent of Hush's `FeedUnitCollectionManager.addNewEdgeToCollection` hook.
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
