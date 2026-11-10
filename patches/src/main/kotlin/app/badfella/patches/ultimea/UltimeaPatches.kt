package app.badfella.patches.ultimea

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.w3c.dom.Element
import java.io.File
import java.util.zip.GZIPInputStream

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

private val FONT_VALUES = linkedMapOf(
    "rosemary" to "Rosemary (Samsung)",
    "googlesans" to "Google Sans",
    "opensans" to "Open Sans",
    "segoeui" to "Segoe UI",
    "system" to "System Default",
)

private val FONT_RESOURCES = mapOf(
    "rosemary" to "/fonts/rosemary.ttf.gz",
    "googlesans" to "/fonts/googlesans.ttf.gz",
    "opensans" to "/fonts/opensans.ttf.gz",
    "segoeui" to "/fonts/segoeui.ttf.gz",
)

private const val FONT_ASSET = "assets/flutter_assets/fonts/custom_font.ttf"
private const val FONT_MANIFEST = "assets/flutter_assets/FontManifest.json"
private const val ASSET_MANIFEST = "assets/flutter_assets/AssetManifest.json"

private fun hex(value: String): ByteArray =
    value.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

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
        if (matched) result += index
        index++
    }
    return result
}

private fun ByteArray.replaceUnique(old: ByteArray, new: ByteArray): ByteArray {
    check(old.size == new.size) { "Anchor length changed: ${old.size} to ${new.size}" }
    val matches = occurrences(old)
    check(matches.size == 1) {
        "Expected one ${old.joinToString("") { "%02x".format(it) }} anchor, found ${matches.size}; " +
            "this app needs review"
    }
    return copyOf().also { new.copyInto(it, matches.single()) }
}

private fun ResourcePatchContext.patchSnapshots(transform: (String, ByteArray) -> ByteArray) {
    val entries = listApkEntries("lib/").filter { it.endsWith("/libapp.so") }
    check(entries.isNotEmpty()) {
        "No libapp.so was found; patch the original XAPK with its native libraries"
    }
    check(entries.size <= 4) { "Unexpected libapp.so inventory: $entries" }
    entries.forEach { entry ->
        val file: File = get(entry)
        val original = file.readBytes()
        val patched = transform(entry, original)
        check(!patched.contentEquals(original)) { "Ultimea snapshot was not changed: $entry" }
        file.writeBytes(patched)
    }
}

private val A64_ALLOC_OLD = hex(
    "c2 00 80 d2 52 d1 12 94 e2 03 00 aa a0 03 5d f8 a2 83 1f f8 40 f0 00 b8 " +
        "a0 03 5c f8 40 30 01 b8 a0 83 5d f8 40 70 01 b8"
)
private val A64_ALLOC_NEW = hex(
    "82 00 80 d2 52 d1 12 94 e2 03 00 aa a0 03 5d f8 a2 83 1f f8 40 f0 00 b8 " +
        "a0 83 5d f8 40 30 01 b8 1f 20 03 d5 1f 20 03 d5"
)
private val A64_CONSTRUCTOR_OLD = hex("c0 00 80 d2 20 b0 00 b8 25 c0 0f 94")
private val A64_CONSTRUCTOR_NEW = hex("80 00 80 d2 20 b0 00 b8 25 c0 0f 94")
private val A64_ON_TAP_OLD = hex("a2 0b 40 f9 9f 47 e5 97 40 37 40 f9 00 24 57 f9")
private val A64_ON_TAP_NEW = hex("a2 0b 40 f9 42 08 00 91 9e 47 e5 97 06 00 00 14")

private val A32_ALLOC_OLD = hex(
    "06 10 a0 e3 32 8e 00 eb 00 10 a0 e1 08 00 1b e5 18 10 0b e5 0b 00 81 e5 " +
        "10 00 1b e5 0f 00 81 e5 14 00 1b e5 13 00 81 e5"
)
private val A32_ALLOC_NEW = hex(
    "04 10 a0 e3 32 8e 00 eb 00 10 a0 e1 08 00 1b e5 18 10 0b e5 0b 00 81 e5 " +
        "14 00 1b e5 0f 00 81 e5 00 00 a0 e1 00 00 a0 e1"
)

// The bottom bar builds six entries and maps taps through the list. Reducing the
// allocation to four entries, shrinking the constructor count and shifting the
// tap index by two removes the Explorer/Community entry from the bar.
private fun hideExplorerSnapshot(entry: String, bytes: ByteArray): ByteArray = when {
    entry.contains("/arm64-v8a/") -> bytes
        .replaceUnique(A64_CONSTRUCTOR_OLD, A64_CONSTRUCTOR_NEW)
        .replaceUnique(A64_ALLOC_OLD, A64_ALLOC_NEW)
        .replaceUnique(A64_ON_TAP_OLD, A64_ON_TAP_NEW)

    entry.contains("/armeabi-v7a/") -> bytes.replaceUnique(A32_ALLOC_OLD, A32_ALLOC_NEW)

    else -> throw PatchException("Unsupported Ultimea architecture in $entry; this app needs review")
}

private val EMPTY_SVG = "<svg width=\"0\" height=\"0\"/>"

private fun ResourcePatchContext.blankExplorerIcons() {
    listOf(
        "assets/flutter_assets/assets/images/store_icon.svg",
        "assets/flutter_assets/assets/images/store_icon_dark.svg",
    ).forEach { path ->
        val file = get(path)
        check(file.exists()) { "Ultimea icon is missing: $path" }
        file.writeText(EMPTY_SVG)
    }
}

// The app's reviewed dark palette is string-driven. These four tokens are the
// dark surface, elevated-surface and background tones; text and accent tokens
// are separate and stay intact.
private val DARK_COLOR_TOKENS = listOf("#161616", "#191919", "#323232", "#333333")

private fun amoledSnapshot(@Suppress("UNUSED_PARAMETER") entry: String, bytes: ByteArray): ByteArray =
    DARK_COLOR_TOKENS.fold(bytes) { snapshot, token ->
        snapshot.replaceUnique(token.toByteArray(Charsets.ISO_8859_1), "#000000".toByteArray(Charsets.ISO_8859_1))
    }

private val NIGHT_COLORS = setOf("colorBackground", "colorElevated", "default_color_background")
private val NIGHT_WINDOW_STYLES = setOf("NormalTheme", "LaunchTheme")

// The Flutter view is transparent between frames, so the native night window
// background and the elevated-surface color are made pure black as well.
private fun ResourcePatchContext.amoledResources() {
    val nightFiles = decode(patterns = arrayOf("res/values-night*/*.xml"))
    check(nightFiles.isNotEmpty()) { "Ultimea night resources were not found; this app needs review" }
    var edited = 0
    for (path in nightFiles) {
        when {
            path.endsWith("/colors.xml") -> document(path).use { document ->
                val colors = document.getElementsByTagName("color")
                for (index in 0 until colors.length) {
                    val color = colors.item(index) as Element
                    if (color.getAttribute("name") !in NIGHT_COLORS) continue
                    color.textContent = "#FF000000"
                    edited++
                }
            }

            path.endsWith("/styles.xml") -> document(path).use { document ->
                val styles = document.getElementsByTagName("style")
                for (index in 0 until styles.length) {
                    val style = styles.item(index) as Element
                    if (style.getAttribute("name") !in NIGHT_WINDOW_STYLES) continue
                    val items = style.getElementsByTagName("item")
                    var found = false
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex) as Element
                        if (item.getAttribute("name").removePrefix("android:") != "windowBackground") continue
                        item.textContent = "#FF000000"
                        found = true
                        edited++
                    }
                    if (!found) {
                        style.appendChild(document.createElement("item").also {
                            it.setAttribute("name", "android:windowBackground")
                            it.textContent = "#FF000000"
                        })
                        edited++
                    }
                }
            }
        }
    }
    check(edited > 0) { "Ultimea night theme backgrounds were not found; this app needs review" }
}

private fun readBundledFont(resource: String): ByteArray {
    val stream = object {}.javaClass.getResourceAsStream(resource)
        ?: throw PatchException("Bundled font is missing: $resource")
    return GZIPInputStream(stream).use { it.readBytes() }
}

private fun ResourcePatchContext.installFont(font: String) {
    val resource = FONT_RESOURCES[font] ?: throw PatchException("Unknown Ultimea font: $font")
    val fontFile = get(FONT_ASSET)
    fontFile.parentFile.mkdirs()
    fontFile.writeBytes(readBundledFont(resource))

    // Flutter resolves the default Material text style through the Roboto family,
    // so declaring it in FontManifest makes Skia load the bundled font globally.
    val manifest = get(FONT_MANIFEST)
    check(manifest.exists()) { "Ultimea FontManifest.json is missing" }
    val original = manifest.readText()
    check(original.contains("fonts/MaterialIcons-Regular.otf")) {
        "Unexpected Ultimea FontManifest.json: $original"
    }
    manifest.writeText(
        "[{\"family\":\"MaterialIcons\",\"fonts\":[{\"asset\":\"fonts/MaterialIcons-Regular.otf\"}]}," +
            "{\"family\":\"Roboto\",\"fonts\":[{\"asset\":\"fonts/custom_font.ttf\"}]}]",
    )

    val assets = get(ASSET_MANIFEST)
    check(assets.exists()) { "Ultimea AssetManifest.json is missing" }
    val text = assets.readText().trimEnd()
    check(text.endsWith("}")) { "Unexpected Ultimea AssetManifest.json: $text" }
    if (!text.contains("\"fonts/custom_font.ttf\"")) {
        val body = text.dropLast(1).trimEnd()
        val separator = if (body.length <= 1) "" else ","
        assets.writeText(
            "$body$separator\"fonts/custom_font.ttf\":[\"fonts/custom_font.ttf\"]}",
        )
    }
}

@Suppress("unused")
val ultimeaPitchBlackPatch = resourcePatch(
    name = "Pitch black",
    description = "Replaces Ultimea's dark background, surface and elevated-surface colors and the night " +
        "window background with pure black.",
    default = true,
) {
    compatibleWith(ULTIMEA)
    category("Theme")
    execute {
        patchSnapshots(::amoledSnapshot)
        amoledResources()
    }
}

@Suppress("unused")
val ultimeaHideExplorerPatch = resourcePatch(
    name = "Hide Explorer tab",
    description = "Removes the Explorer/Community entry from Ultimea's bottom navigation bar.",
    default = true,
) {
    compatibleWith(ULTIMEA)
    category("Navigation")
    execute {
        patchSnapshots(::hideExplorerSnapshot)
        blankExplorerIcons()
    }
}

@Suppress("unused")
val ultimeaCustomFontPatch = resourcePatch(
    name = "Custom font",
    description = "Replaces Ultimea's Roboto text style with a font family you choose, so the whole app " +
        "renders with Rosemary, Google Sans, Open Sans or Segoe UI. Choose System Default to keep the " +
        "app's own font.",
    default = true,
) {
    compatibleWith(ULTIMEA)
    category("UI")

    val fontOption = stringOption(
        key = "font",
        default = "rosemary",
        values = FONT_VALUES,
        title = "Custom Font",
        description = "Choose a font family to apply across the app.",
    )

    execute {
        val font = fontOption.value ?: "rosemary"
        if (font == "system") return@execute
        installFont(font)
    }
}
