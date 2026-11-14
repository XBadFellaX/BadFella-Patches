package app.badfella.patches.boldvoice

import app.badfella.patches.util.commitAll
import app.badfella.patches.util.loadDexHandles
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.w3c.dom.Element

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/**
 * PairIP DRM void methods that must be replaced with return-void.
 *
 * Maps each class descriptor to the set of (methodName, descriptor) pairs to neutralize.
 * - StartupLauncher.launch(): invoked from MainApplication.<clinit> and CoreComponentFactory.<clinit>;
 *   triggers VMRunner.invoke("yWNWpAe3JlRXk98k") which executes libpairipcore.so DRM checks.
 * - SignatureCheck.verifyIntegrity(Context): compares the apk signature against the Google Play
 *   certificate hash and throws SignatureTamperedException when signed with any other key.
 * - LicenseClient.checkLicense(Context): contacts com.android.vending.licensing.ILicensingService;
 *   on failure (sideloaded install) calls startErrorDialogActivity().
 * - LicenseClient.startErrorDialogActivity(): opens LicenseActivity / sends a Play Store intent
 *   displaying "Get this app from Play Store".
 */
private val PAIRIP_VOID_TARGETS: Map<String, Set<Pair<String, String>>> = mapOf(
    "Lcom/pairip/StartupLauncher;" to setOf(
        "launch" to "()V",
    ),
    "Lcom/pairip/SignatureCheck;" to setOf(
        "verifyIntegrity" to "(Landroid/content/Context;)V",
    ),
    "Lcom/pairip/licensecheck/LicenseClient;" to setOf(
        "checkLicense" to "(Landroid/content/Context;)V",
        "startErrorDialogActivity" to "()V",
    ),
)

/** Minimal method body: single return-void instruction with 1 register (required minimum). */
private val RETURN_VOID_IMPL = ImmutableMethodImplementation(
    1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList(),
)

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
 * DEX adjustments:
 * - Stock builds include the PairIP DRM library. On startup, StartupLauncher.launch() is called
 *   from MainApplication.<clinit> and CoreComponentFactory.<clinit>; it invokes VMRunner which
 *   executes the libpairipcore.so native VM program. That program calls SignatureCheck.verifyIntegrity()
 *   (throws SignatureTamperedException when the APK is not signed by Google Play) and
 *   LicenseClient.checkLicense() (which shows "Get this app from Play Store" via
 *   LicenseClient.startErrorDialogActivity() when the app is sideloaded). All four methods are
 *   replaced with return-void to neutralize the entire DRM chain.
 *
 * Manifest adjustments:
 * - Stock builds use com.pairip.application.Application as the application class. The patch
 *   restores com.wellocution.android.MainApplication and removes the PairIP licensing activity
 *   and com.android.stamp.* metadata entries.
 * - Expo OTA updates are disabled (expo.modules.updates.ENABLED = false, EXPO_UPDATE_URL = "")
 *   so the app does not silently download and replace the patched bundle on launch.
 *
 * Preserves authentication, microphone recording, audio playback, speech scoring services,
 * and user progress.
 */
@Suppress("unused")
val boldVoiceSubscriptionPatch = resourcePatch(
    name = "BoldVoice subscription features",
    description = "Enables client-side subscriber features, lessons, and paywall unlock checks by patching Hermes 99 bytecode targets (hasClientPremiumAccess, selectIsSubscriber, selectIsProSubscriber). Neutralizes PairIP DRM (StartupLauncher.launch, SignatureCheck.verifyIntegrity, LicenseClient.checkLicense, LicenseClient.startErrorDialogActivity) with return-void so the app runs outside Play Store. Disables Expo OTA updates to prevent overwriting the patched bundle.",
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
        // --- Hermes bundle: unlock subscription gates ---
        val bundle = get("assets/index.android.bundle")
        bundle.writeBytes(patchBoldVoiceBundle(bundle.readBytes()))

        // --- DEX: neutralize PairIP DRM chain ---
        val dexes = loadDexHandles(this)
        val neutralized = mutableMapOf<String, Int>()

        for (dex in dexes) {
            for (cls in dex.classes.toList()) {
                val targets = PAIRIP_VOID_TARGETS[cls.type] ?: continue
                var classEdited = false
                val methods = cls.methods.map { method ->
                    // Build the full method descriptor "(Lparam;...)V" to match against targets.
                    val descriptor = method.parameterTypes.joinToString("", "(", ")") + method.returnType
                    if ((method.name to descriptor) !in targets) return@map method
                    check(method.returnType == "V") {
                        "PairIP target ${cls.type}::${method.name} has unexpected return type ${method.returnType}"
                    }
                    neutralized[cls.type] = (neutralized[cls.type] ?: 0) + 1
                    classEdited = true
                    ImmutableMethod(
                        method.definingClass, method.name, method.parameters, method.returnType,
                        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                        RETURN_VOID_IMPL,
                    )
                }
                if (classEdited) {
                    dex.replaceClass(
                        cls,
                        ImmutableClassDef(
                            cls.type, cls.accessFlags, cls.superclass, cls.interfaces,
                            cls.sourceFile, cls.annotations, cls.fields, methods,
                        ),
                    )
                }
            }
        }

        // Verify every expected target was found and neutralized.
        val expected = PAIRIP_VOID_TARGETS.mapValues { it.value.size }
        check(neutralized == expected) {
            "PairIP neutralization mismatch. Expected $expected, got $neutralized; this app needs review"
        }

        dexes.commitAll()

        // --- Manifest: restore application class and remove PairIP entries ---
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
