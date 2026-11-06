package app.badfella.patches.linkedin

import app.badfella.patches.linkedin.LINKEDIN
import app.badfella.patches.linkedin.LinkedInSduiPagedListFingerprint
import app.badfella.patches.linkedin.LinkedInStreamingListFingerprint
import app.badfella.patches.linkedin.LinkedInStreamingTemplateFingerprint
import app.badfella.patches.linkedin.LinkedInTemplatePagedListFingerprint
import app.badfella.patches.linkedin.LinkedInUpdatePresenterFingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
 * instead of leaving a placeholder. Each selection inserts its own block with a predicate-scoped
 * label, so enabling several selections at once cannot duplicate a label or jump into the wrong
 * patch's code.
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
    val keep = "keep_$predicate"
    method.addInstructionsWithLabels(0, """
        move-object/from16 v1, p1
        invoke-static { v1 }, $FILTER->$predicate(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :$keep
        const/4 v0, 0x0
        return-object v0
    """, ExternalLabel(keep, method.getInstruction(0)))
}

/**
 * Filters the parsed page's element list before the paged list reads it.
 *
 * `addAll(CollectionTemplate)` reads `template.elements` at instruction 0, so replacing or
 * trimming that list here means the worker that adds the page, its metadata store and every
 * presenter mapped from it only ever see the retained updates. Each selection enables its own
 * predicate and then runs the shared funnel, so enabling several selections cannot duplicate a
 * label or filter with another selection's rules.
 */
private fun BytecodePatchContext.filterCollectionTemplateWhen(enable: String) {
    for (fingerprint in listOf(
        LinkedInStreamingTemplateFingerprint,
        LinkedInTemplatePagedListFingerprint,
    )) {
        val method: MutableMethod = fingerprint.method
        val implementation = checkNotNull(method.implementation) {
            "LinkedIn collection-template addAll has no body; this build needs review"
        }
        val parameterWords = 1 + method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
        check(implementation.registerCount - parameterWords >= 1) {
            "LinkedIn collection-template addAll has no free local; this build needs review"
        }
        method.addInstructions(0, """
            move-object/from16 v0, p1
            invoke-static {}, $FILTER->$enable()V
            invoke-static { v0 }, $FILTER->filterCollectionTemplate(Ljava/lang/Object;)V
        """)
    }
}

/**
 * Strips matching models from an incoming page before the paged list accepts them.
 *
 * `addAll(Collection)` is the dedupe funnel that `CollectionTemplatePagedList.addAll` reaches with
 * the parsed element list, so removing an entry here means no adapter slot, presenter or
 * impression is ever allocated for it. The helper returns a new list only when something matched;
 * otherwise the original collection is handed back untouched.
 */
private fun BytecodePatchContext.filterIngestedWhen(selection: String) {
    val method: MutableMethod = LinkedInStreamingListFingerprint.method
    val implementation = checkNotNull(method.implementation) {
        "LinkedIn streaming list addAll has no body; this build needs review"
    }
    val parameterWords = 1 + method.parameterTypes.sumOf {
        if (it.toString() == "J" || it.toString() == "D") 2 else 1
    }
    check(implementation.registerCount - parameterWords >= 1) {
        "LinkedIn streaming list addAll has no free local; this build needs review"
    }
    method.addInstructions(0, """
        move-object/from16 v0, p1
        invoke-static { v0 }, $FILTER->$selection(Ljava/util/Collection;)Ljava/util/List;
        move-result-object v0
        move-object/from16 p1, v0
    """)
}

/**
 * Filters the SDUI component collection before SduiPagedList accepts it.
 *
 * SduiPagedList.addAll(Collection) receives the parsed SDUI components for each feed page.
 * Replacing the parameter collection with the filtered result strips sponsored components,
 * promoted jobs, and suggested carousels before Compose LazyList state or duplicate detection
 * is allocated.
 */
private fun BytecodePatchContext.filterSduiCollectionWhen(enable: String) {
    val method: MutableMethod = LinkedInSduiPagedListFingerprint.method
    val implementation = checkNotNull(method.implementation) {
        "LinkedIn SDUI paged list addAll has no body; this build needs review"
    }
    val parameterWords = 1 + method.parameterTypes.sumOf {
        if (it.toString() == "J" || it.toString() == "D") 2 else 1
    }
    check(implementation.registerCount - parameterWords >= 1) {
        "LinkedIn SDUI paged list addAll has no free local; this build needs review"
    }
    method.addInstructions(0, """
        invoke-static {}, $FILTER->$enable()V
        move-object/from16 v0, p1
        invoke-static { v0 }, $FILTER->filterSduiCollection(Ljava/util/Collection;)Ljava/util/Collection;
        move-result-object v0
        move-object/from16 p1, v0
    """)
}

@Suppress("unused")
val linkedInHideSponsoredPostsPatch = bytecodePatch(
    name = "Hide sponsored posts",
    description = "Removes sponsored and promoted posts from the home feed instead of leaving an empty card. " +
        "Filters each parsed page before the feed's paged list reads it, so an ad never takes a list slot, " +
        "and keeps the presenter entry as a second guard for other screens. Matches sponsored tracking, " +
        "sponsored URNs, boosted posts, call-to-action and lead-gen cards, and the feed's Promoted or " +
        "Sponsored labels, including labels in accessibility text, supplementary actor info and headers. " +
        "Promoted jobs keep their own selection.",
) {
    compatibleWith(LINKEDIN)
    category("Ads")
    extendWith("extensions/extension.mpe")
    execute {
        filterSduiCollectionWhen("enableHideSponsoredPosts")
        filterCollectionTemplateWhen("enableHideSponsoredPosts")
        dropUpdateWhen("isSponsoredPost")
        filterIngestedWhen("filterSponsoredPosts")
    }
}

@Suppress("unused")
val linkedInHidePromotedJobsPatch = bytecodePatch(
    name = "Hide promoted jobs",
    description = "Removes sponsored and promoted job cards and job recommendations from the feed. " +
        "Filters each parsed page before the feed's paged list reads it. Organic job posts from people " +
        "you follow are kept.",
) {
    compatibleWith(LINKEDIN)
    category("Ads")
    extendWith("extensions/extension.mpe")
    execute {
        filterSduiCollectionWhen("enableHidePromotedJobs")
        filterCollectionTemplateWhen("enableHidePromotedJobs")
        dropUpdateWhen("isPromotedJob")
        filterIngestedWhen("filterPromotedJobs")
    }
}

@Suppress("unused")
val linkedInHideSuggestedCarouselsPatch = bytecodePatch(
    name = "Hide suggested and follow carousels",
    description = "Removes recommended-follow prompts and sponsored discovery, entity and carousel rows " +
        "from the feed, including discovery, mixed-update, creative-card and announcement carousels such " +
        "as Videos for you. Filters each parsed page before the feed's paged list reads it. Organic posts " +
        "by people and pages you follow are kept.",
) {
    compatibleWith(LINKEDIN)
    category("Feed")
    extendWith("extensions/extension.mpe")
    execute {
        filterSduiCollectionWhen("enableHideSuggestedCarousels")
        filterCollectionTemplateWhen("enableHideSuggestedCarousels")
        dropUpdateWhen("isSuggestedCarousel")
        filterIngestedWhen("filterSuggestedCarousels")
    }
}

@Suppress("unused")
val linkedInHideFeedVideosPatch = bytecodePatch(
    name = "Hide feed videos",
    description = "Removes video posts from the home feed, including native, large single and external video " +
        "updates, and video carousels such as Videos for you. Filters each parsed page before the feed's " +
        "paged list reads it. Videos opened from a profile or a link are not part of the feed pipeline.",
) {
    compatibleWith(LINKEDIN)
    category("Feed")
    extendWith("extensions/extension.mpe")
    execute {
        filterSduiCollectionWhen("enableHideFeedVideos")
        filterCollectionTemplateWhen("enableHideFeedVideos")
        dropUpdateWhen("isVideoPost")
        filterIngestedWhen("filterVideos")
    }
}
