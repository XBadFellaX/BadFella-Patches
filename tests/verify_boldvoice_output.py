"""Verify BoldVoice APKS output: reviewed HBC99 edits, footer, PairIP DEX neutralization, OTA guard, and untouched payloads."""
import argparse
import hashlib
import io
import os
import tempfile
from pathlib import Path
import re
import subprocess
import zipfile

MAGIC = 0x1F1903C103BC1FC6
EXPECTED_VERSION = 99

GATE_TRUE_RET = bytes.fromhex("9500" + "7600" * 7)
GATE_TRUE_RET_LONG = bytes.fromhex("9500" + "7600" * 35)

PROFILE_470 = (
    (0x1A6B1AD, 72, bytes.fromhex("8902014503020080b3130003130000b10f0045030201c3cb130103130001b128004505020253619401160105019302b1110144040503e690032c746e020405031301021300017600"), GATE_TRUE_RET_LONG),
    (0x1B07808, 16, bytes.fromhex("89000144000000f94500000180b37600"), GATE_TRUE_RET),
    (0x1B1C281, 16, bytes.fromhex("89000144000000f9450000011be67600"), GATE_TRUE_RET),
)

PROFILE_499 = (
    (0x1BA5747, 72, bytes.fromhex("890201450302009f8d130003130000b10f00450302019593130103130001b128004505020298649401160105019302b1110144040503e5900393776e020405031301021300017600"), GATE_TRUE_RET_LONG),
    (0x1C3901C, 16, bytes.fromhex("89000144000000f9450000019f8d7600"), GATE_TRUE_RET),
    (0x1C49532, 16, bytes.fromhex("89000144000000f94500000159f07600"), GATE_TRUE_RET),
    (0x1567368, 92, bytes.fromhex("5e080b416e080c000845080821c351b309084508180e6a49b315083b1905176c19190045191922fb7919081903b32f083b1905206c191900442319037d452123233d8c44191810da3b1805176c18180045181822fb796f0821231918"), bytes.fromhex("96084b58" + "7600" * 44)),
)

def sha1(data):
    return hashlib.sha1(data).digest()


def expected_bundle(stock):
    assert len(stock) >= 148
    magic = int.from_bytes(stock[0:8], "little")
    assert magic == MAGIC, "Not Hermes bytecode"
    version = int.from_bytes(stock[8:12], "little")
    assert version == EXPECTED_VERSION, f"Unexpected Hermes bytecode version: {version}"
    length = int.from_bytes(stock[32:36], "little")
    assert length == len(stock), "Hermes bundle length mismatch"
    assert sha1(stock[:-20]) == stock[-20:], "Stock Hermes footer invalid"

    profile = None
    for candidate in (PROFILE_499, PROFILE_470):
        if all(stock[offset : offset + size] == before for offset, size, before, _after in candidate):
            profile = candidate
            break
    assert profile is not None, "BoldVoice Hermes gate anchors changed"

    expected = bytearray(stock)
    for offset, size, _before, after in profile:
        assert stock[offset : offset + size] == _before
        expected[offset : offset + size] = after
    expected[-20:] = sha1(bytes(expected[:-20]))
    return bytes(expected)


def verify(args):
    with zipfile.ZipFile(args.stock) as stock, zipfile.ZipFile(args.output) as output:
        assert len(output.namelist()) == len(set(output.namelist())), "Duplicate ZIP entry in output"

        # The morphe output APKS is a flat archive; stock APKS wraps a base.apk.
        output_names = set(output.namelist())

        with zipfile.ZipFile(io.BytesIO(stock.read("base.apk"))) as base:
            # 1. Verify Hermes bundle was patched correctly.
            before_bundle = base.read("assets/index.android.bundle")
            after_bundle = output.read("assets/index.android.bundle")
            assert len(before_bundle) == len(after_bundle), "Bundle size changed"
            expected = expected_bundle(before_bundle)
            assert after_bundle == expected, "Patched bundle does not match expected reviewed edits"

            with tempfile.TemporaryDirectory(prefix="boldvoice-check-") as directory:
                work = Path(directory)
                stock_apk = work / "stock.apk"
                stock_apk.write_bytes(stock.read("base.apk"))
                root = Path(__file__).resolve().parents[1]
                javac = args.java.with_name("javac.exe" if args.java.suffix == ".exe" else "javac")
                subprocess.run([str(javac), "-cp", str(args.dexlib_jar), "-d", str(work),
                                str(root / "tools/DexInventory.java"), str(root / "tests/RemoteAtvDexCheck.java"),
                                str(root / "tests/BoldVoiceDexCheck.java")], check=True)
                subprocess.run([str(args.java), "-Xmx3g", "-cp", str(work) + os.pathsep + str(args.dexlib_jar),
                                "BoldVoiceDexCheck", str(stock_apk), str(args.output)], check=True)

            # 2. DEX inventory: same set of DEX files (contents changed by PairIP neutralization).
            dex = {n for n in base.namelist() if re.fullmatch(r"classes\d*\.dex", n)}
            assert dex == {n for n in output_names if re.fullmatch(r"classes\d*\.dex", n)}, "DEX inventory changed"

            # 3. Non-DEX assets other than the bundle must be unchanged.
            for name in base.namelist():
                if name in dex:
                    continue  # DEX patched intentionally
                if name.startswith("assets/"):
                    if name == "assets/index.android.bundle":
                        continue  # already checked above
                    assert name in output_names, f"Asset missing: {name}"
                    assert base.read(name) == output.read(name), f"Asset changed: {name}"
                    continue
                # Skip entries not present in flat output (morphe may omit some base APK internals)
                if name not in output_names:
                    continue
                # Skip manifest and resources (modified by morphe resource rebuilder); verified via aapt2
                if name in ("AndroidManifest.xml", "resources.arsc"):
                    continue
                assert base.read(name) == output.read(name), f"Original payload changed: {name}"

        # 5. Native libraries from splits must be preserved exactly, except the three
        # PairIP-hooked libraries the patch restores to pristine builds; their content is
        # pinned independently by the patch's BFP after-hash checks and re-verified here.
        natives = {}
        for name in stock.namelist():
            if not name.endswith(".apk"):
                continue
            with zipfile.ZipFile(io.BytesIO(stock.read(name))) as split:
                for item in split.infolist():
                    if item.filename.startswith("lib/") and not item.is_dir():
                        natives[item.filename] = (split.read(item), item.compress_type)
        assert set(natives) == {n for n in output_names if n.startswith("lib/") and not n.endswith("/")}, "Native libs mismatch"
        restored_native_hashes = {
            "lib/arm64-v8a/libreactnative.so": "0a108cd36ab33974758ff4a1391cb278ceb8e1ed3710abb0a873271120fff52b",
            "lib/arm64-v8a/libonnxruntime.so": "4d2318b3849abb8862133d3068fc7e807ed8b2671cc6d83657fff2fcb9e1caad",
            "lib/arm64-v8a/libavformat.so": "205043c0bf7e70ab05ba562aea6d0f813a79c624fea37b2e119c94f667d49429",
        }
        for name, (data, compression) in natives.items():
            if name in restored_native_hashes:
                assert hashlib.sha256(output.read(name)).hexdigest() == restored_native_hashes[name], f"Restored native payload changed: {name}"
                continue
            assert output.read(name) == data and output.getinfo(name).compress_type == compression, f"Native payload changed: {name}"

    # 6. Manifest checks via aapt2.
    manifest = subprocess.check_output(
        [str(args.aapt2), "dump", "xmltree", str(args.output), "--file", "AndroidManifest.xml"],
        text=True, encoding="utf-8"
    )

    # The wrapper establishes VMRunner's context and must remain available.
    assert re.search(r'android:name\(0x01010003\)="com\.pairip\.application\.Application"', manifest), "VM context wrapper missing"
    assert "com.pairip.licensecheck.LicenseActivity" not in manifest, "License UI remains in manifest"
    assert "com.android.stamp." not in manifest, "Play stamp metadata remains in manifest"

    # Verify Expo OTA updates disabled.
    assert re.search(r'expo\.modules\.updates\.ENABLED[^\n]*\n[^\n]*=false', manifest), "Expo OTA updates still enabled"
    assert re.search(r'expo\.modules\.updates\.EXPO_UPDATE_URL[^\n]*\n[^\n]*=""', manifest), "Expo update URL still present"

    print("PASS: BoldVoice Hermes99 edits/footer, PairIP license edits, "
          "unrelated DEX and assets retained, pristine native libraries, "
          "VM application wrapper retained, OTA disabled")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--dexlib-jar", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    verify(parser.parse_args())
