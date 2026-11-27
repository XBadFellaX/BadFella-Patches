package app.badfella.patches.ticktick.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.returnEarly
import app.badfella.patches.ticktick.shared.Constants.COMPATIBILITY_TICKTICK
import com.android.tools.smali.dexlib2.AccessFlags

// R8 renames the preferences class between builds. The confirmation key and
// boolean signature still identify the gate used by process startup and alerts.
object CheckPrivacyPolicyConfirmedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("agree_service_and_privacy_policy"),
)

// Reminder scheduler anti-mod consistency guard. It builds a blank User and
// SignUserInfo and compares the Pro predicates. The diagnostic copy in
// LogCollector.isPiracyUser has the same calls but is private and not static.
private object ReminderPremiumConsistencyFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = "Lcom/ticktick/task/data/User;", name = "<init>"),
        methodCall(definingClass = "Lcom/ticktick/task/data/User;", name = "isPro"),
        methodCall(definingClass = "Lcom/ticktick/task/data/User;", name = "getProType"),
        methodCall(definingClass = "Lcom/ticktick/task/network/sync/common/model/SignUserInfo;", name = "<init>"),
        methodCall(definingClass = "Lcom/ticktick/task/network/sync/common/model/SignUserInfo;", name = "isPro"),
        methodCall(definingClass = "Lcom/ticktick/task/data/User;", name = "isLocalMode"),
        methodCall(definingClass = "Lcom/ticktick/task/data/User;", name = "getProTypeForFake"),
    ),
)

@Suppress("unused")
val restoreRemindersPatch = bytecodePatch(
    name = "Restore reminder alarms",
    description = "Restores task/reminder alarm scheduling on re-signed apps.",
) {
    compatibleWith(COMPATIBILITY_TICKTICK)

    execute {
        // A blank User now reports pro, so the reminder-specific consistency check rejects every reminder.
        ReminderPremiumConsistencyFingerprint.method.returnEarly(false)
        // The scheduler only runs while the service/privacy confirmation gate is true.
        CheckPrivacyPolicyConfirmedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x1
            return v0
        """)
    }
}
