package app.badfella.patches.boldvoice

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/**
 * BoldVoice (com.wellocution.androidapp) is a React Native app using Hermes 99 bytecode.
 *
 * Feature gates:
 * 1. hasClientPremiumAccess in assets/index.android.bundle resolves whether the current user has
 *    full client premium access by checking user.isSubscriber, user.unlockAll, or user.role.startsWith('admin').
 *    Patching this function to return constant true enables lessons, video coaching materials,
 *    and removes locked icons and paywall triggers.
 * 2. Redux selectors selectIsSubscriber and selectIsProSubscriber read state.userState.isSubscriber
 *    and state.userState.isProSubscriber. Patching their function bodies to return constant true
 *    informs React components across the UI that subscriber and pro subscriber privileges are active.
 *
 * Manifest adjustments:
 * - Stock builds include Google Play's PairIP license check wrapper
 *   (com.pairip.application.Application and com.pairip.licensecheck.LicenseActivity), which displays
 *   a "Get this app from Play Store" blocker dialog when installed outside Google Play. The patch
 *   restores the real application class (com.wellocution.android.MainApplication), and removes
 *   PairIP activity and stamp metadata entries.
 * - Expo OTA updates are disabled (expo.modules.updates.ENABLED = false, EXPO_UPDATE_URL = "") so
 *   the app does not silently download and replace the patched bundle on launch.
 *
 * Preserves authentication, microphone recording, audio playback, speech scoring services,
 * and user progress.
 */
@Suppress("unused")
val boldVoiceSubscriptionPatch = resourcePatch(
    name = "BoldVoice subscription features",
    description = "Enables client-side subscriber features, lessons, and paywall unlock checks by patching Hermes bytecode targets (hasClientPremiumAccess, selectIsSubscriber, selectIsProSubscriber). Neutralizes the PairIP installation check so the app runs when installed outside Play Store. Disables Expo OTA updates to prevent overwriting the patched bundle.",
) {
    compatibleWith(Compatibility(
        name = "BoldVoice",
        packageName = "com.wellocution.androidapp",
        apkFileType = ApkFileType.APKS,
        targets = listOf(
            AppTarget(
                version = "5.1.3",
                versionCodes = mapOf(SupportedAbi.ARM64_V8A to 470),
                description = "Version 5.1.3, build 470 (ARM64 split).",
            ),
        ),
    ))
    execute {
        val bundle = get("assets/index.android.bundle")
        bundle.writeBytes(patchBoldVoiceBundle(bundle.readBytes()))

        document("AndroidManifest.xml").use { manifest ->
            val app = manifest.getElementsByTagName("application").item(0) as Element
            if (app.getAttributeNS(ANDROID, "name") == "com.pairip.application.Application") {
                app.setAttributeNS(ANDROID, "android:name", "com.wellocution.android.MainApplication")
            }

            val removeTags = setOf("activity", "meta-data")
            val nodes = manifest.getElementsByTagName("*")
            for (i in nodes.length - 1 downTo 0) {
                val node = nodes.item(i) as Element
                val name = node.getAttributeNS(ANDROID, "name")
                if (node.tagName in removeTags) {
                    if (name.startsWith("com.pairip.") || name.startsWith("com.android.stamp.")) {
                        node.parentNode.removeChild(node)
                    }
                }
            }

            val metaNodes = manifest.getElementsByTagName("meta-data")
            val metadata = (0 until metaNodes.length).mapNotNull { metaNodes.item(it) as? Element }
            val enabled = metadata.firstOrNull { it.getAttributeNS(ANDROID, "name") == "expo.modules.updates.ENABLED" }
            if (enabled != null) {
                enabled.setAttributeNS(ANDROID, "android:value", "false")
            }
            val updateUrl = metadata.firstOrNull { it.getAttributeNS(ANDROID, "name") == "expo.modules.updates.EXPO_UPDATE_URL" }
            if (updateUrl != null) {
                updateUrl.setAttributeNS(ANDROID, "android:value", "")
            }
        }
    }
}
