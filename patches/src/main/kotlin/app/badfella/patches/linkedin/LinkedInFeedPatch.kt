package app.badfella.patches.linkedin

import app.badfella.patches.linkedin.LINKEDIN
import app.badfella.patches.linkedin.LinkedInUpdatePresenterFingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel

private const val FILTER = "Lapp/badfella/extension/linkedin/LinkedInFilter;"

/**
 * Drops the current update from the feed by returning a null presenter.
 *
 * `createUpdatePresenter` already returns null when a collapsed update produces no presenters,
 * and the feed pipeline logs and skips that update, so the same null return removes the row
 * instead of leaving a placeholder.
 */
private fun BytecodePatchContext.dropUpdateWhen(predicate: String) {
    val method: MutableMethod = LinkedInUpdatePresenterFingerprint.method
    val implementation = checkNotNull(method.implementation) {
        "LinkedIn feed presenter has no body; this build needs review"
    }
    val parameterWords = method.parameterTypes.sumOf {
        if (it.toString() == "J" || it.toString() == "D") 2 else 1
    }
    check(implementation.registerCount - parameterWords - 1 >= 2) {
        "LinkedIn feed presenter has no free locals; this build needs review"
    }
    method.addInstructionsWithLabels(0, """
        move-object/from16 v1, p1
        invoke-static { v1 }, $FILTER->$predicate(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :keep
        const/4 v0, 0x0
        return-object v0
    """, ExternalLabel("keep", method.getInstruction(0)))
}

@Suppress("unused")
val linkedInHideSponsoredPostsPatch = bytecodePatch(
    name = "Hide sponsored posts",
    description = "Removes sponsored and promoted posts from the home feed instead of leaving an empty card. " +
        "Promoted jobs and sponsored carousels stay with their own selections. Sponsored updates that the " +
        "server does not mark with sponsored metadata are not affected.",
) {
    compatibleWith(LINKEDIN)
    category("Ads")
    extendWith("extensions/extension.mpe")
    execute { dropUpdateWhen("isSponsoredPost") }
}

@Suppress("unused")
val linkedInHidePromotedJobsPatch = bytecodePatch(
    name = "Hide promoted jobs",
    description = "Removes sponsored and promoted job cards and job recommendations from the feed. " +
        "Organic job posts from people you follow are kept.",
) {
    compatibleWith(LINKEDIN)
    category("Ads")
    extendWith("extensions/extension.mpe")
    execute { dropUpdateWhen("isPromotedJob") }
}

@Suppress("unused")
val linkedInHideSuggestedCarouselsPatch = bytecodePatch(
    name = "Hide suggested and follow carousels",
    description = "Removes recommended-follow prompts and sponsored discovery, entity and carousel rows " +
        "from the feed. Organic posts by people and pages you follow are kept.",
) {
    compatibleWith(LINKEDIN)
    category("Feed")
    extendWith("extensions/extension.mpe")
    execute { dropUpdateWhen("isSuggestedCarousel") }
}

@Suppress("unused")
val linkedInHideFeedVideosPatch = bytecodePatch(
    name = "Hide feed videos",
    description = "Removes video posts from the home feed, including native, large single and external video " +
        "updates. Videos opened from a profile or a link are not part of the feed pipeline.",
) {
    compatibleWith(LINKEDIN)
    category("Feed")
    extendWith("extensions/extension.mpe")
    execute { dropUpdateWhen("isVideoPost") }
}
