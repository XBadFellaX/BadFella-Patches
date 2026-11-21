package app.badfella.patches.ultimateusb

import app.badfella.patches.util.commitAll
import app.badfella.patches.util.loadDexHandles
import app.badfella.patches.util.resolveUniqueClass
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import java.security.MessageDigest

@Suppress("unused")
val ultimateUsbProPatch = resourcePatch(
    name = "Ultimate USB local Pro",
    description = "Enables local Pro checks and skips the cached-purchase validator. Retains operation confirmations, USB/filesystem code and native libraries. Reviewed on 12.5.0 build 1205000; changed bytecode requires review. Does not create a Google Play purchase.",
) {
    compatibleWith(Compatibility(
        name = "Ultimate USB", packageName = "com.mixapplications.ultimateusb",
        apkFileType = ApkFileType.APKS,
        targets = listOf(AppTarget(version = "12.5.0",
            versionCodes = mapOf(SupportedAbi.ARM64_V8A to 1205000))),
    ))
    execute {
        val actual = MessageDigest.getInstance("SHA-256").digest(get("classes5.dex").readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == "fb443ec0fbf1e2966779ea507b26ff158d2159810762d259e738edce135ea436") { "Ultimate USB classes5.dex changed; this APK needs review" }
        val dexes = loadDexHandles(this)
        val (handle, owner) = dexes.resolveUniqueClass("Lc81;")
        var changed = 0
        val methods = owner.methods.map { method ->
            val implementation = when {
                method.name == "d" && method.parameterTypes.isEmpty() && method.returnType == "Z" -> {
                    check(method.accessFlags == 9)
                    ImmutableMethodImplementation(1, listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction11x(Opcode.RETURN, 0)), emptyList(), emptyList())
                }
                method.name == "f" && method.parameterTypes.map { it.toString() } == listOf("Lfm1;") &&
                    method.returnType == "Ljava/lang/Object;" -> {
                    check(method.accessFlags == 17)
                    // The validator precedes the Pro predicate; retain the caller's operation confirmations.
                    ImmutableMethodImplementation(3, listOf(
                        ImmutableInstruction21c(Opcode.SGET_OBJECT, 0,
                            ImmutableFieldReference("Lkotlin/Unit;", "a", "Lkotlin/Unit;")),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), emptyList(), emptyList())
                }
                else -> return@map method
            }
            checkNotNull(method.implementation)
            changed++
            ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions, implementation)
        }
        check(changed == 2) { "Expected two Ultimate USB entitlement methods, found $changed" }
        handle.replaceClass(owner, ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass,
            owner.interfaces, owner.sourceFile, owner.annotations, owner.fields, methods))
        dexes.commitAll()
    }
}
