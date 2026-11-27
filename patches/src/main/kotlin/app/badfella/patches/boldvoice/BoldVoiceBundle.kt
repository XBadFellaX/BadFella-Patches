package app.badfella.patches.boldvoice

import app.badfella.patches.util.hexBytes
import java.security.MessageDigest

private const val MAGIC = 0x1f1903c103bc1fc6L
private const val EXPECTED_VERSION = 99

private data class Gate(
    val offset: Int,
    val size: Int,
    val before: ByteArray,
    val after: ByteArray,
)

private data class BundleProfile(
    val label: String,
    val gates: List<Gate>,
)

// BoldVoice 5.1.3 (build 470)
private val PROFILE_470 = BundleProfile(
    label = "5.1.3",
    gates = listOf(
        Gate(
            0x1a6b1ad, 72,
            hexBytes("8902014503020080b3130003130000b10f0045030201c3cb130103130001b128004505020253619401160105019302b1110144040503e690032c746e020405031301021300017600"),
            hexBytes("9500" + "7600".repeat(35)),
        ),
        Gate(0x1b07808, 16, hexBytes("89000144000000f94500000180b37600"), hexBytes("9500" + "7600".repeat(7))),
        Gate(0x1b1c281, 16, hexBytes("89000144000000f9450000011be67600"), hexBytes("9500" + "7600".repeat(7))),
    ),
)

// RootContainer force-update gate (5.1.3 build 470)
private val FORCE_UPDATE_470 = BundleProfile(
    label = "5.1.3",
    gates = listOf(
        Gate(
            0x147a32c, 96,
            hexBytes("5e080b416e080c00084508081fdc4ab309084508170c0b47b315083b1805176c18180045181820e7f519081803b32f083b1805206c18180044221803854520222144884418170edb3b1705176c17170045171720e7f56f082022181737061a08"),
            // Set the update flag to false and jump from 0x563 to its original store at 0x5bd.
            hexBytes("9608af5a"),
        ),
    ),
)

internal val T1_OFFSET = PROFILE_470.gates[0].offset
internal val T1_SIZE = PROFILE_470.gates[0].size
internal val T1_BEFORE = PROFILE_470.gates[0].before
internal val T1_AFTER = PROFILE_470.gates[0].after

internal val T2_OFFSET = PROFILE_470.gates[1].offset
internal val T2_SIZE = PROFILE_470.gates[1].size
internal val T2_BEFORE = PROFILE_470.gates[1].before
internal val T2_AFTER = PROFILE_470.gates[1].after

internal val T3_OFFSET = PROFILE_470.gates[2].offset
internal val T3_SIZE = PROFILE_470.gates[2].size
internal val T3_BEFORE = PROFILE_470.gates[2].before
internal val T3_AFTER = PROFILE_470.gates[2].after

internal val T4_OFFSET = FORCE_UPDATE_470.gates[0].offset
internal val T4_SIZE = FORCE_UPDATE_470.gates[0].size
internal val T4_BEFORE = FORCE_UPDATE_470.gates[0].before
internal val T4_AFTER = FORCE_UPDATE_470.gates[0].after

private fun selectProfile(stock: ByteArray, profiles: List<BundleProfile>): BundleProfile {
    for (profile in profiles) {
        if (profile.gates.all { gate ->
                stock.copyOfRange(gate.offset, gate.offset + gate.size).contentEquals(gate.before)
            }) {
            return profile
        }
    }
    error("BoldVoice Hermes gates changed; this app needs review")
}

private fun applyBundleProfile(stock: ByteArray, profiles: List<BundleProfile>): ByteArray {
    check(stock.size in 148..(128 * 1024 * 1024)) { "Hermes bundle size is invalid or exceeds 128 MiB" }
    check(longAt(stock, 0) == MAGIC) { "Hermes bytecode magic changed" }
    check(intAt(stock, 8) == EXPECTED_VERSION) { "Hermes bytecode version changed; this app needs review" }
    check(intAt(stock, 32) == stock.size) { "Hermes bundle length is invalid" }

    val footer = stock.size - 20
    check(sha1(stock, 0, footer).contentEquals(stock.copyOfRange(footer, stock.size))) {
        "Hermes bytecode footer checksum is invalid"
    }

    val profile = selectProfile(stock, profiles)
    for (gate in profile.gates) {
        check(gate.offset + gate.size <= footer) { "Hermes gate at ${gate.offset.toString(16)} is out of bounds" }
        check(stock.copyOfRange(gate.offset, gate.offset + gate.size).contentEquals(gate.before)) {
            "BoldVoice Hermes anchor at ${gate.offset.toString(16)} changed (${profile.label})"
        }
    }

    val result = stock.copyOf()
    for (gate in profile.gates) {
        gate.after.copyInto(result, gate.offset)
    }
    sha1(result, 0, footer).copyInto(result, footer)
    return result
}

/**
 * Patches the Hermes 99 subscription gates for BoldVoice 5.1.3 (470).
 */
internal fun patchBoldVoiceBundle(stock: ByteArray): ByteArray =
    applyBundleProfile(stock, listOf(PROFILE_470))

/**
 * Disables the RootContainer force-update screen in BoldVoice 5.1.3 (470).
 */
internal fun patchBoldVoiceForceUpdate(stock: ByteArray): ByteArray =
    applyBundleProfile(stock, listOf(FORCE_UPDATE_470))

private fun intAt(bytes: ByteArray, offset: Int): Int {
    check(offset >= 0 && offset + 4 <= bytes.size) { "Hermes read is out of bounds" }
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
}

private fun longAt(bytes: ByteArray, offset: Int): Long {
    check(offset >= 0 && offset + 8 <= bytes.size) { "Hermes read is out of bounds" }
    return (intAt(bytes, offset).toLong() and 0xffffffffL) or (intAt(bytes, offset + 4).toLong() shl 32)
}

private fun sha1(bytes: ByteArray, offset: Int, length: Int): ByteArray =
    MessageDigest.getInstance("SHA-1").apply { update(bytes, offset, length) }.digest()
