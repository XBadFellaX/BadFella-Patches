package app.badfella.patches.remoteatv

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.resourcePatch
import java.security.MessageDigest

@Suppress("unused")
val remoteAtvDisablePageSwipesPatch = resourcePatch(
    name = "Disable remote page swipes",
    description = "Disables swiping between the D-pad or touchpad and number pad. Keeps the switch button and setup page swipes. Requires Remote ATV ad-free. Supports version 6.1.0, build 45.",
    default = false,
) {
    compatibleWith(Compatibility(
        name = "Remote for Android TV", packageName = "tech.simha.androidtvremote",
        apkFileType = ApkFileType.APKM, appIconColor = 0x127AFF,
        targets = listOf(AppTarget(version = "6.1.0", versionCode = 45,
            description = "Version 6.1.0, build 45.")),
    ))
    dependsOn(remoteAtvAdFreePatch)

    execute {
        val libraries = listApkEntries("lib/").filter { it.endsWith("/libapp.so") }
        check(libraries.isNotEmpty()) { "Remote ATV has no Dart snapshot" }
        libraries.forEach { name ->
            val abi = name.split('/')[1]
            val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/remoteatv/swipes-6.1.0-$abi.tsv")) {
                "Unreviewed Remote ATV swipe architecture: $abi"
            }
            val lines = stream.bufferedReader().use { it.readLines() }
                .filter { it.isNotBlank() && !it.startsWith('#') }
            val hashes = lines.first().split('\t')
            val file = get(name)
            val bytes = file.readBytes()
            fun digest() = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(digest() == hashes[0]) { "Unreviewed Remote ATV swipe snapshot: $abi" }
            lines.drop(1).forEach { line ->
                val (position, expected, replacement) = line.split('\t')
                val offset = position.toInt(16)
                fun decode(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val before = decode(expected)
                val after = decode(replacement)
                check(offset >= 0 && before.size == after.size && offset <= bytes.size - before.size &&
                    bytes.copyOfRange(offset, offset + before.size).contentEquals(before)) {
                    "Remote ATV swipe instructions changed: $abi/$position"
                }
                after.copyInto(bytes, offset)
            }
            check(digest() == hashes[1]) { "Remote ATV swipe output checksum failed: $abi" }
            file.writeBytes(bytes)
        }
    }
}
