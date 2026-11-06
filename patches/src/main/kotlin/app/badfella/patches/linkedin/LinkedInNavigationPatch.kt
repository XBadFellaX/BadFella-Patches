package app.badfella.patches.linkedin

import app.badfella.patches.linkedin.LINKEDIN
import app.badfella.patches.linkedin.LinkedInBottomBarItemsFingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch

private const val FILTER = "Lapp/badfella/extension/linkedin/LinkedInFilter;"

/**
 * Routes the reviewed HomeTabInfo list through the runtime filter before the bar builds its
 * badges. The helper returns a new list, so a read-only source list is not mutated.
 */
private fun BytecodePatchContext.filterBottomBar(mask: Int) {
    if (mask == 0) return
    val method = LinkedInBottomBarItemsFingerprint.method
    val implementation = checkNotNull(method.implementation) {
        "LinkedIn bottom bar setItems has no body; this build needs review"
    }
    val parameterWords = 1 + method.parameterTypes.sumOf {
        if (it.toString() == "J" || it.toString() == "D") 2 else 1
    }
    check(implementation.registerCount - parameterWords >= 2) {
        "LinkedIn bottom bar setItems has no free locals; this build needs review"
    }
    method.addInstructions(0, """
        const/16 v0, 0x${mask.toString(16)}
        move-object/from16 v1, p1
        invoke-static { v1, v0 }, $FILTER->filterTabs(Ljava/util/List;I)Ljava/util/List;
        move-result-object v1
        move-object/from16 p1, v1
    """)
}

@Suppress("unused")
val linkedInCustomizeNavigationPatch = bytecodePatch(
    name = "Customize navigation bar",
    description = "Takes the bottom navigation buttons you pick off the bar, such as My Network, Post, " +
        "Notifications, Jobs, Video or News. Each page still opens from the Menu and from links. " +
        "Off by default; restart LinkedIn after changing the selection.",
) {
    compatibleWith(LINKEDIN)
    category("Navigation")
    extendWith("extensions/extension.mpe")

    val hideMyNetwork = booleanOption(
        "hide_my_network", false,
        title = "Hide My Network",
        description = "Removes the My Network button from the bottom navigation bar.",
    )
    val hidePost = booleanOption(
        "hide_post", false,
        title = "Hide Post",
        description = "Removes the center Post button from the bottom navigation bar.",
    )
    val hideNotifications = booleanOption(
        "hide_notifications", false,
        title = "Hide Notifications",
        description = "Removes the Notifications button from the bottom navigation bar.",
    )
    val hideJobs = booleanOption(
        "hide_jobs", false,
        title = "Hide Jobs",
        description = "Removes the Jobs button from the bottom navigation bar.",
    )
    val hideVideo = booleanOption(
        "hide_video", false,
        title = "Hide Video",
        description = "Removes the Video button from the bottom navigation bar.",
    )
    val hideNews = booleanOption(
        "hide_news", false,
        title = "Hide News",
        description = "Removes the News button from the bottom navigation bar.",
    )

    execute {
        var mask = 0
        if (hideMyNetwork.value == true) mask = mask or 1
        if (hidePost.value == true) mask = mask or 2
        if (hideNotifications.value == true) mask = mask or 4
        if (hideJobs.value == true) mask = mask or 8
        if (hideVideo.value == true) mask = mask or 16
        if (hideNews.value == true) mask = mask or 32
        filterBottomBar(mask)
    }
}
