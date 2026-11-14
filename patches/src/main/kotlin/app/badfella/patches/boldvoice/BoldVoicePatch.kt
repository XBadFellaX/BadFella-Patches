package app.badfella.patches.boldvoice

import app.badfella.patches.util.commitAll
import app.badfella.patches.util.loadDexHandles
import app.badfella.patches.util.resolveUniqueClass
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.w3c.dom.Element

private const val ANDROID = "http://schemas.android.com/apk/res/android"

// Keep the native VM and application wrapper: protected lifecycle methods require both.
private const val LICENSE_CLIENT = "Lcom/pairip/licensecheck/LicenseClient;"
private const val LICENSE_STATE = "Lcom/pairip/licensecheck/LicenseClient\$LicenseCheckState;"

private val PAIRIP_VOID_TARGETS: Map<String, Set<Pair<String, String>>> = mapOf(
    "Lcom/pairip/SignatureCheck;" to setOf(
        "verifyIntegrity" to "(Landroid/content/Context;)V",
    ),
    LICENSE_CLIENT to setOf(
        "initializeLicenseCheck" to "()V",
        "startErrorDialogActivity" to "()V",
        "scheduleAppShutdown" to "()V",
    ),
)

/** Minimal method body: single return-void instruction with 1 register (required minimum). */
private val RETURN_VOID_IMPL = ImmutableMethodImplementation(
    1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList(),
)

/** Pinned Hermes 99 gates and PairIP license state; preserve VM-backed lifecycle dispatch. */
@Suppress("unused")
val boldVoiceSubscriptionPatch = resourcePatch(
    name = "BoldVoice subscription features",
    description = "Enables client-side subscriber features and lessons in BoldVoice 5.1.3. Preserves PairIP VM startup and application initialization, completes the local license state, disables licensing retries and Expo OTA bundle replacement. Server-controlled features retain their existing requirements.",
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
        val (_, state) = dexes.resolveUniqueClass(LICENSE_STATE)
        check(state.fields.count { it.name == "FULL_CHECK_OK" && it.type == LICENSE_STATE } == 1) {
            "PairIP successful license state missing; this app needs review"
        }
        val neutralized = mutableMapOf<String, Int>()

        for (dex in dexes) {
            for (cls in dex.classes.toList()) {
                val targets = PAIRIP_VOID_TARGETS[cls.type] ?: continue
                var classEdited = false
                val methods = cls.methods.map { method ->
                    // Build the full method descriptor "(Lparam;...)V" to match against targets.
                    val descriptor = method.parameterTypes.joinToString("", "(", ")") + method.returnType
                    val implementation = when {
                        (method.name to descriptor) in targets -> RETURN_VOID_IMPL
                        cls.type == LICENSE_CLIENT && method.name == "performLocalInstallerCheck" && descriptor == "()Z" ->
                            ImmutableMethodImplementation(1, listOf(
                                ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                                ImmutableInstruction11x(Opcode.RETURN, 0),
                            ), emptyList(), emptyList())
                        cls.type == LICENSE_CLIENT && method.name == "<clinit>" && descriptor == "()V" -> {
                            // A no-op entry point alone leaves CHECK_REQUIRED visible to native VM code.
                            val body = MutableMethodImplementation(checkNotNull(method.implementation))
                            val matches = body.instructions.withIndex().filter { (_, ins) ->
                                val ref = (ins as? ReferenceInstruction)?.reference as? FieldReference
                                ins.opcode == Opcode.SGET_OBJECT && ref?.definingClass == LICENSE_STATE &&
                                    ref.name == "CHECK_REQUIRED" && ref.type == LICENSE_STATE
                            }
                            check(matches.size == 1) { "PairIP initial license state changed; this app needs review" }
                            val (index, ins) = matches.single()
                            body.replaceInstruction(index, BuilderInstruction21c(Opcode.SGET_OBJECT,
                                (ins as OneRegisterInstruction).registerA,
                                ImmutableFieldReference(LICENSE_STATE, "FULL_CHECK_OK", LICENSE_STATE)))
                            body
                        }
                        else -> return@map method
                    }
                    neutralized[cls.type] = (neutralized[cls.type] ?: 0) + 1
                    classEdited = true
                    ImmutableMethod(
                        method.definingClass, method.name, method.parameters, method.returnType,
                        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                        implementation,
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
        val expected = PAIRIP_VOID_TARGETS.mapValues { (type, methods) ->
            methods.size + if (type == LICENSE_CLIENT) 2 else 0
        }
        check(neutralized == expected) {
            "PairIP neutralization mismatch. Expected $expected, got $neutralized; this app needs review"
        }

        dexes.commitAll()

        // --- Manifest: retain the VM context wrapper; remove license UI and stamps ---
        document("AndroidManifest.xml").use { manifest ->
            val app = manifest.getElementsByTagName("application").item(0) as Element
            check(app.getAttributeNS(ANDROID, "name") == "com.pairip.application.Application") {
                "BoldVoice application wrapper changed; this app needs review"
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
