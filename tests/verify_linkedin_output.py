"""Check the actual Morphe output for the LinkedIn patch, rather than a mocked context."""
import argparse
import json
import pathlib
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("result", type=pathlib.Path)
parser.add_argument("build_tools", type=pathlib.Path)
parser.add_argument("--tabs-mask", type=lambda value: int(value, 0), default=0xA,
                    help="Bottom-bar mask expected in setItems (0 means the option was not selected)")
args = parser.parse_args()
APK = args.apk
SDK = args.build_tools
FILTER = "Lapp/badfella/extension/linkedin/LinkedInFilter;"
PRESENTER = "Lcom/linkedin/android/feed/framework/presentercreator/update/FeedUpdateTransformer;"
BOTTOM_BAR = "Lcom/linkedin/android/home/bottomnav/SlimBottomBar;"
EXPECTED_PATCHES = {
    "Hide sponsored posts",
    "Hide promoted jobs",
    "Hide suggested and follow carousels",
    "Hide feed videos",
    "Customize navigation bar",
}

result = json.loads(args.result.read_text())
assert result["success"] and not result["failedPatches"], result
applied = set(result["appliedPatches"])
assert applied == EXPECTED_PATCHES, applied

manifest = subprocess.check_output(
    [str(SDK / "aapt2.exe"), "dump", "badging", str(APK)],
    text=True, encoding="utf-8", errors="replace")
assert "package: name='com.linkedin.android' versionCode='217900' versionName='4.1.1271'" in manifest


def class_descriptors(data):
    assert data.startswith(b"dex\n")
    _, string_offset = struct.unpack_from("<II", data, 56)
    _, type_offset = struct.unpack_from("<II", data, 64)
    class_count, class_offset = struct.unpack_from("<II", data, 96)

    def string(index):
        offset = struct.unpack_from("<I", data, string_offset + 4 * index)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        return data[offset:data.index(0, offset)].decode("utf-8")

    for index in range(class_count):
        type_index = struct.unpack_from("<I", data, class_offset + index * 32)[0]
        string_index = struct.unpack_from("<I", data, type_offset + 4 * type_index)[0]
        yield string(string_index)


with tempfile.TemporaryDirectory() as workspace:
    root = pathlib.Path(workspace)
    classes = Counter()
    targets = {}
    with zipfile.ZipFile(APK) as archive:
        dex_names = sorted(name for name in archive.namelist()
                           if re.fullmatch(r"classes\d*\.dex", name))
        assert dex_names, "Patched APK has no DEX files"
        for name in dex_names:
            data = archive.read(name)
            file = root / name
            file.write_bytes(data)
            for descriptor in class_descriptors(data):
                classes[descriptor] += 1
                if descriptor in (FILTER, PRESENTER, BOTTOM_BAR):
                    targets.setdefault(descriptor, name)

    assert classes[FILTER] == 1, "LinkedIn runtime filter is missing or duplicated"
    assert classes[PRESENTER] == 1 and classes[BOTTOM_BAR] == 1
    assert all(count == 1 for count in classes.values()), "Duplicate DEX classes"

    def disassemble(name):
        return subprocess.check_output(
            [str(SDK / "dexdump.exe"), "-d", str(root / name)],
            text=True, encoding="utf-8", errors="replace")

    feed = disassemble(targets[PRESENTER])
    for predicate in ("isSponsoredPost", "isPromotedJob", "isSuggestedCarousel", "isVideoPost"):
        assert f"{FILTER}.{predicate}:" in feed, f"Feed predicate not injected: {predicate}"

    nav = disassemble(targets[BOTTOM_BAR])
    if args.tabs_mask:
        assert f"{FILTER}.filterTabs:" in nav, "Bottom-bar filter not injected"
        assert f"const/16 v0, #int {args.tabs_mask}" in nav, \
            f"Bottom-bar mask {args.tabs_mask:#x} not injected"
    else:
        assert f"{FILTER}.filterTabs:" not in nav, "Bottom-bar filter injected without a selection"

subprocess.run([str(SDK / "zipalign.exe"), "-c", "4", str(APK)], check=True)
print(f"PASS: LinkedIn 4.1.1271, {len(applied)} patches, feed predicates and bottom-bar filter, "
      f"{len(classes)} unique classes, ZIP alignment")
