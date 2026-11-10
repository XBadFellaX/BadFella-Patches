package app.badfella.patches.ultimea

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import java.io.File

// Ultimea 1.4.4 (build 495) keeps its theme, navigation and text defaults in the
// bundled Dart AOT snapshot. The reviewed inputs expose each anchor exactly once,
// so a missing or duplicated anchor fails closed instead of writing a guess.
private val ULTIMEA = Compatibility(
    name = "Ultimea",
    packageName = "com.dynanic.ultimea",
    apkFileType = ApkFileType.XAPK,
    appIconColor = 0x008847,
    targets = listOf(
        AppTarget(
            version = "1.4.4",
            versionCode = 495,
            description = "Version 1.4.4, build 495.",
        ),
    ),
)

private fun ByteArray.occurrences(needle: ByteArray): List<Int> {
    val result = mutableListOf<Int>()
    var index = 0
    while (index <= size - needle.size) {
        var matched = true
        for (offset in needle.indices) {
            if (this[index + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) {
            result += index
            index += needle.size
        } else {
            index++
        }
    }
    return result
}

private fun ByteArray.replaceAnchor(old: String, new: String): ByteArray {
    check(old.length == new.length) { "Anchor length changed: $old" }
    val needle = old.toByteArray(Charsets.ISO_8859_1)
    val matches = occurrences(needle)
    check(matches.size == 1) { "Expected one $old anchor, found ${matches.size}; this app needs review" }
    return copyOf().also { new.toByteArray(Charsets.ISO_8859_1).copyInto(it, matches.single()) }
}

private fun ResourcePatchContext.patchSnapshots(transform: (ByteArray) -> ByteArray) {
    val entries = listApkEntries("lib/").filter { it.endsWith("/libapp.so") }
    check(entries.isNotEmpty()) {
        "No libapp.so was found; patch the original XAPK with its native libraries"
    }
    check(entries.size <= 4) { "Unexpected libapp.so inventory: $entries" }
    entries.forEach { entry ->
        val file: File = get(entry)
        val original = file.readBytes()
        val patched = transform(original)
        check(!patched.contentEquals(original)) { "Ultimea snapshot was not changed: $entry" }
        file.writeBytes(patched)
    }
}

// The reviewed dark palette is string-driven. Replacing the background tokens with
// black leaves text and accent tokens untouched.
private fun amoledSnapshot(bytes: ByteArray): ByteArray =
    listOf(
        "#161616" to "#000000",
        "#191919" to "#000000",
        "#323232" to "#000000",
        "#333333" to "#000000",
    ).fold(bytes) { snapshot, (old, new) -> snapshot.replaceAnchor(old, new) }

// AppConfigModel reads communitySwitch from the community_switch JSON key. An
// unreadable key yields the reviewed null fallback, which is false.
private fun hideExplorerSnapshot(bytes: ByteArray): ByteArray =
    bytes.replaceAnchor("community_switch", "badfella_switch_")

// The only standalone Roboto string is the framework's default font family.
// "Roboto Mono" is a separate longer string and must remain intact.
private fun systemFontSnapshot(bytes: ByteArray): ByteArray {
    val family = "Roboto".toByteArray(Charsets.ISO_8859_1)
    val mono = "Roboto Mono".toByteArray(Charsets.ISO_8859_1)
    val matches = bytes.occurrences(family).filter { offset ->
        offset + mono.size > bytes.size ||
            mono.indices.any { bytes[offset + it] != mono[it] }
    }
    check(matches.size == 1) { "Expected one Roboto font-family anchor, found ${matches.size}; this app needs review" }
    return bytes.copyOf().also { "bdfont".toByteArray(Charsets.ISO_8859_1).copyInto(it, matches.single()) }
}

@Suppress("unused")
val ultimeaPitchBlackPatch = resourcePatch(
    name = "Pitch black",
    description = "Replaces Ultimea's dark palette background and elevated-surface colors with pure black.",
    default = false,
) {
    compatibleWith(ULTIMEA)
    category("Theme")
    execute { patchSnapshots(::amoledSnapshot) }
}

@Suppress("unused")
val ultimeaHideExplorerPatch = resourcePatch(
    name = "Hide Explorer tab",
    description = "Removes the Explorer/Community tab by disabling the server-side community switch " +
        "the app reads at startup.",
    default = true,
) {
    compatibleWith(ULTIMEA)
    category("Navigation")
    execute { patchSnapshots(::hideExplorerSnapshot) }
}

@Suppress("unused")
val ultimeaSystemFontPatch = resourcePatch(
    name = "Use system font",
    description = "Renders text with the Android system font instead of the app's Roboto text style, " +
        "so Ultimea follows the device font.",
    default = true,
) {
    compatibleWith(ULTIMEA)
    category("UI")
    execute { patchSnapshots(::systemFontSnapshot) }
}
