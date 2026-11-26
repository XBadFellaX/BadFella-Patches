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

// BoldVoice 5.3.6 (build 499): subscription gates + RootContainer force-update bypass
private val PROFILE_499 = BundleProfile(
    label = "5.3.6",
    gates = listOf(
        Gate(
            0x1ba5747, 72,
            hexBytes("890201450302009f8d130003130000b10f00450302019593130103130001b128004505020298649401160105019302b1110144040503e5900393776e020405031301021300017600"),
            hexBytes("9500" + "7600".repeat(35)),
        ),
        Gate(0x1c3901c, 16, hexBytes("89000144000000f9450000019f8d7600"), hexBytes("9500" + "7600".repeat(7))),
        Gate(0x1c49532, 16, hexBytes("89000144000000f94500000159f07600"), hexBytes("9500" + "7600".repeat(7))),
        Gate(
            0x1567368, 92,
            hexBytes("5e080b416e080c000845080821c351b309084508180e6a49b315083b1905176c19190045191922fb7919081903b32f083b1905206c191900442319037d452123233d8c44191810da3b1805176c18180045181822fb796f0821231918"),
            hexBytes("96084b58" + "7600".repeat(44)),
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

internal val T4_OFFSET = PROFILE_499.gates[3].offset
internal val T4_SIZE = PROFILE_499.gates[3].size
internal val T4_BEFORE = PROFILE_499.gates[3].before
internal val T4_AFTER = PROFILE_499.gates[3].after

private fun selectProfile(stock: ByteArray): BundleProfile {
  for (profile in listOf(PROFILE_499, PROFILE_470)) {
        if (profile.gates.all { gate ->
                stock.copyOfRange(gate.offset, gate.offset + gate.size).contentEquals(gate.before)
            }) {
            return profile
        }
    }
    error("BoldVoice Hermes gates changed; this app needs review")
}

/**
 * Patches the Hermes 99 bundle for BoldVoice 5.1.3 (470) or 5.3.6 (499).
 * 5.3.6 adds a RootContainer gate bypass so the client force-update screen is not shown.
 */
internal fun patchBoldVoiceBundle(stock: ByteArray): ByteArray {
    check(stock.size in 148..(128 * 1024 * 1024)) { "Hermes bundle size is invalid or exceeds 128 MiB" }
    check(longAt(stock, 0) == MAGIC) { "Hermes bytecode magic changed" }
    check(intAt(stock, 8) == EXPECTED_VERSION) { "Hermes bytecode version changed; this app needs review" }
    check(intAt(stock, 32) == stock.size) { "Hermes bundle length is invalid" }

    val footer = stock.size - 20
    check(sha1(stock, 0, footer).contentEquals(stock.copyOfRange(footer, stock.size))) {
        "Hermes bytecode footer checksum is invalid"
    }

    val profile = selectProfile(stock)
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