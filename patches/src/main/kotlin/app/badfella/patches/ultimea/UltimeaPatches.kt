package app.badfella.patches.ultimea

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private val A64_ALLOC_OLD = byteArrayOf(
    0xc2.toByte(), 0x00, 0x80.toByte(), 0xd2.toByte(), 0x52, 0xd1.toByte(), 0x12, 0x94.toByte(),
    0xe2.toByte(), 0x03, 0x00, 0xaa.toByte(), 0xa0.toByte(), 0x03, 0x5d, 0xf8.toByte(),
    0xa2.toByte(), 0x83.toByte(), 0x1f, 0xf8.toByte(), 0x40, 0xf0.toByte(), 0x00, 0xb8.toByte(),
    0xa0.toByte(), 0x03, 0x5c, 0xf8.toByte(), 0x40, 0x30, 0x01, 0xb8.toByte(),
    0xa0.toByte(), 0x83.toByte(), 0x5d, 0xf8.toByte(), 0x40, 0x70, 0x01, 0xb8.toByte(),
)
private val A64_ALLOC_NEW = byteArrayOf(
    0x82.toByte(), 0x00, 0x80.toByte(), 0xd2.toByte(), 0x52, 0xd1.toByte(), 0x12, 0x94.toByte(),
    0xe2.toByte(), 0x03, 0x00, 0xaa.toByte(), 0xa0.toByte(), 0x03, 0x5d, 0xf8.toByte(),
    0xa2.toByte(), 0x83.toByte(), 0x1f, 0xf8.toByte(), 0x40, 0xf0.toByte(), 0x00, 0xb8.toByte(),
    0xa0.toByte(), 0x83.toByte(), 0x5d, 0xf8.toByte(), 0x40, 0x30, 0x01, 0xb8.toByte(),
    0x1f, 0x20, 0x03, 0xd5.toByte(), 0x1f, 0x20, 0x03, 0xd5.toByte(),
)

private val A64_CONSTRUCTOR_OLD = byteArrayOf(
    0xc0.toByte(), 0x00, 0x80.toByte(), 0xd2.toByte(), 0x20, 0xb0.toByte(), 0x00, 0xb8.toByte(),
    0x25, 0xc0.toByte(), 0x0f, 0x94.toByte(),
)
private val A64_CONSTRUCTOR_NEW = byteArrayOf(
    0x80.toByte(), 0x00, 0x80.toByte(), 0xd2.toByte(), 0x20, 0xb0.toByte(), 0x00, 0xb8.toByte(),
    0x25, 0xc0.toByte(), 0x0f, 0x94.toByte(),
)

private val A64_ON_TAP_OLD = byteArrayOf(
    0xa2.toByte(), 0x0b, 0x40, 0xf9.toByte(), 0x9f.toByte(), 0x47, 0xe5.toByte(), 0x97.toByte(),
    0x40, 0x37, 0x40, 0xf9.toByte(), 0x00, 0x24, 0x57, 0xf9.toByte(),
)
private val A64_ON_TAP_NEW = byteArrayOf(
    0xa2.toByte(), 0x0b, 0x40, 0xf9.toByte(), 0x42, 0x08, 0x00, 0x91.toByte(),
    0x9e.toByte(), 0x47, 0xe5.toByte(), 0x97.toByte(), 0x06, 0x00, 0x00, 0x14,
)

private val A32_ALLOC_OLD = byteArrayOf(
    0x06, 0x10, 0xa0.toByte(), 0xe3.toByte(), 0x32, 0x8e.toByte(), 0x00, 0xeb.toByte(),
    0x00, 0x10, 0xa0.toByte(), 0xe1.toByte(), 0x08, 0x00, 0x1b, 0xe5.toByte(),
    0x18, 0x10, 0x0b, 0xe5.toByte(), 0x0b, 0x00, 0x81.toByte(), 0xe5.toByte(),
    0x10, 0x00, 0x1b, 0xe5.toByte(), 0x0f, 0x00, 0x81.toByte(), 0xe5.toByte(),
    0x14, 0x00, 0x1b, 0xe5.toByte(), 0x13, 0x00, 0x81.toByte(), 0xe5.toByte(),
)
private val A32_ALLOC_NEW = byteArrayOf(
    0x04, 0x10, 0xa0.toByte(), 0xe3.toByte(), 0x32, 0x8e.toByte(), 0x00, 0xeb.toByte(),
    0x00, 0x10, 0xa0.toByte(), 0xe1.toByte(), 0x08, 0x00, 0x1b, 0xe5.toByte(),
    0x18, 0x10, 0x0b, 0xe5.toByte(), 0x0b, 0x00, 0x81.toByte(), 0xe5.toByte(),
    0x14, 0x00, 0x1b, 0xe5.toByte(), 0x0f, 0x00, 0x81.toByte(), 0xe5.toByte(),
    0x00, 0x00, 0xa0.toByte(), 0xe1.toByte(), 0x00, 0x00, 0xa0.toByte(), 0xe1.toByte(),
)

private val DARK_COLOR_TOKENS = listOf("#161616", "#191919", "#323232", "#333333")
private const val PURE_BLACK_TOKEN = "#000000"

private fun replaceExactPattern(bytes: ByteArray, oldPattern: ByteArray, newPattern: ByteArray, name: String): ByteArray {
    var matchIndex = -1
    var count = 0
    outer@ for (i in 0..bytes.size - oldPattern.size) {
        for (j in oldPattern.indices) {
            if (bytes[i + j] != oldPattern[j]) continue@outer
        }
        matchIndex = i
        count++
    }
    if (count == 0) {
        throw PatchException("Pattern $name not found in libapp.so")
    }
    if (count > 1) {
        throw PatchException("Pattern $name matched multiple times ($count) in libapp.so")
    }
    val result = bytes.copyOf()
    newPattern.copyInto(result, matchIndex)
    return result
}

private fun replaceExactString(bytes: ByteArray, oldStr: String, newStr: String): ByteArray {
    val oldBytes = oldStr.toByteArray(Charsets.ISO_8859_1)
    val newBytes = newStr.toByteArray(Charsets.ISO_8859_1)
    require(oldBytes.size == newBytes.size) { "String lengths must match: $oldStr vs $newStr" }
    return replaceExactPattern(bytes, oldBytes, newBytes, oldStr)
}

@Suppress("unused")
val ultimeaBlackModePatch = resourcePatch(
    name = "Ultimea Black Mode",
    description = "Enables pure black AMOLED theme with light text and surfaces. Patches Dart AOT dark palette tokens directly in libapp.so, updates night window and splash styling, and tints navigation and device icons.",
) {
    compatibleWith(
        Compatibility(
            name = "Ultimea",
            packageName = "com.dynanic.ultimea",
            targets = listOf(
                AppTarget(
                    version = "1.4.4",
                    versionCodes = mapOf(
                        SupportedAbi.ARM64_V8A to 495,
                        SupportedAbi.ARMEABI_V7A to 495,
                    ),
                    description = "Version 1.4.4, build 495.",
                )
            ),
        ),
    )

    execute {
        listOf("values", "values-night").forEach { qualifier ->
            val stylesFile = get("res/$qualifier/styles.xml")
            if (stylesFile.exists()) {
                document("res/$qualifier/styles.xml").use { doc ->
                    val styles = doc.getElementsByTagName("style")
                    for (i in 0 until styles.length) {
                        val style = styles.item(i) as Element
                        val name = style.getAttribute("name")
                        if (name == "NormalTheme" || name == "LaunchTheme" || name.contains("Theme")) {
                            val items = style.getElementsByTagName("item")
                            for (j in 0 until items.length) {
                                val item = items.item(j) as Element
                                when (item.getAttribute("name")) {
                                    "android:windowBackground" -> item.textContent = "@android:color/black"
                                    "android:statusBarColor", "android:navigationBarColor" -> item.textContent = "@android:color/black"
                                    "android:windowLightStatusBar" -> item.textContent = "false"
                                }
                            }
                        }
                    }
                }
            }
        }

        val colorsFile = get("res/values/colors.xml")
        if (colorsFile.exists()) {
            document("res/values/colors.xml").use { doc ->
                val colors = doc.getElementsByTagName("color")
                for (i in 0 until colors.length) {
                    val color = colors.item(i) as Element
                    if (color.getAttribute("name") == "splash_bg_color") {
                        color.textContent = "#ff000000"
                    }
                }
            }
        }

        listOf("res/values-night/colors.xml", "res/values-night-v8/colors.xml").forEach { path ->
            val file = get(path)
            if (file.exists()) {
                document(path).use { doc ->
                    val colors = doc.getElementsByTagName("color")
                    for (i in 0 until colors.length) {
                        val color = colors.item(i) as Element
                        val colorName = color.getAttribute("name")
                        if (colorName in setOf("colorBackground", "colorElevated", "default_color_background")) {
                            color.textContent = "#ff000000"
                        }
                    }
                }
            }
        }

        for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
            val lib = get("lib/$abi/libapp.so")
            if (lib.exists()) {
                var bytes = lib.readBytes()
                for (token in DARK_COLOR_TOKENS) {
                    bytes = replaceExactString(bytes, token, PURE_BLACK_TOKEN)
                }
                lib.writeBytes(bytes)
            }
        }

        val iconsDir = get("assets/flutter_assets/assets/icons")
        if (iconsDir.exists()) {
            val logoDark = iconsDir.resolve("login_logo_dark.svg")
            val logo = iconsDir.resolve("login_logo.svg")
            if (logoDark.exists()) {
                logoDark.copyTo(logo, overwrite = true)
            }

            for (name in listOf("device_nomsgchat", "msg_center_noready", "msg_center_ready")) {
                val nightIcon = iconsDir.resolve("${name}_night.svg")
                val targetIcon = iconsDir.resolve("$name.svg")
                if (nightIcon.exists()) {
                    nightIcon.copyTo(targetIcon, overwrite = true)
                }
            }

            for (name in listOf("default_user_icon.svg", "default_new_userIcon.svg")) {
                val userIcon = iconsDir.resolve(name)
                if (userIcon.exists()) {
                    userIcon.writeText(
                        userIcon.readText()
                            .replace("#E9E9E9", "#24242A")
                            .replace("fill=\"white\"", "fill=\"#B8BCC2\"")
                    )
                }
            }

            for (name in listOf("ble_play_help", "eq_help", "jerry_can_help", "device_usb_icon", "right_into", "right_arrow", "device_arrow_connected")) {
                val icon = iconsDir.resolve("$name.svg")
                if (icon.exists()) {
                    icon.writeText(
                        icon.readText()
                            .replace("#646464", "#B8BCC2")
                            .replace("#161616", "#B8BCC2")
                            .replace("#E9E9E9", "#24242A")
                            .replace("stroke=\"black\"", "stroke=\"#B8BCC2\"")
                    )
                }
            }

            val guide = iconsDir.resolve("guide.svg")
            if (guide.exists()) {
                guide.writeText(
                    guide.readText()
                        .replace("\"white\"", "\"#24242A\"")
                        .replace("#AAB0B5", "#B8BCC2")
                )
            }
        }

        val imagesDir = get("assets/flutter_assets/assets/images")
        if (imagesDir.exists()) {
            listOf(
                "device_input_hdmi_n.png",
                "device_input_usb_n.png",
                "device_input_optical_n.png",
                "device_input_aux_n.png",
                "device_input_bluetooth_n.png",
                "mine_user_icon.png",
            ).forEach { name ->
                val resource = object {}.javaClass.getResourceAsStream("/ultimea/images/$name")
                if (resource != null) {
                    val target = imagesDir.resolve(name)
                    resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                }
            }

            for (name in listOf("store_icon", "store_icon_select", "home_icon", "home_icon_select", "mine_icon", "mine_icon_select")) {
                val icon = imagesDir.resolve("$name.svg")
                if (icon.exists()) {
                    icon.writeText(
                        icon.readText()
                            .replace("#646464", "#B8BCC2")
                            .replace("#541597", "#C499FF")
                            .replace("\"white\"", "\"#24242A\"")
                    )
                }
            }
        }
    }
}

@Suppress("unused")
val ultimeaHideExplorerPatch = resourcePatch(
    name = "Ultimea Hide Explorer Tab",
    description = "Hides the Explorer/Community tab from the bottom navigation bar and collapses its store icons.",
) {
    compatibleWith(
        Compatibility(
            name = "Ultimea",
            packageName = "com.dynanic.ultimea",
            targets = listOf(
                AppTarget(
                    version = "1.4.4",
                    versionCodes = mapOf(
                        SupportedAbi.ARM64_V8A to 495,
                        SupportedAbi.ARMEABI_V7A to 495,
                    ),
                    description = "Version 1.4.4, build 495.",
                )
            ),
        ),
    )

    execute {
        var modified = false
        val arm64Lib = get("lib/arm64-v8a/libapp.so")
        if (arm64Lib.exists()) {
            var bytes = arm64Lib.readBytes()
            bytes = replaceExactPattern(bytes, A64_ALLOC_OLD, A64_ALLOC_NEW, "A64_ALLOC")
            bytes = replaceExactPattern(bytes, A64_CONSTRUCTOR_OLD, A64_CONSTRUCTOR_NEW, "A64_CONSTRUCTOR")
            bytes = replaceExactPattern(bytes, A64_ON_TAP_OLD, A64_ON_TAP_NEW, "A64_ON_TAP")
            arm64Lib.writeBytes(bytes)
            modified = true
        }

        val arm32Lib = get("lib/armeabi-v7a/libapp.so")
        if (arm32Lib.exists()) {
            var bytes = arm32Lib.readBytes()
            bytes = replaceExactPattern(bytes, A32_ALLOC_OLD, A32_ALLOC_NEW, "A32_ALLOC")
            arm32Lib.writeBytes(bytes)
            modified = true
        }

        for (iconName in listOf("store_icon.svg", "store_icon_dark.svg", "store_icon_select.svg")) {
            val file = get("assets/flutter_assets/assets/images/$iconName")
            if (file.exists()) {
                file.writeText("""<svg width="0" height="0"/>""")
            }
        }
    }
}
