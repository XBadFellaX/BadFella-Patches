"""Check the actual Morphe output for the LinkedIn patch suite against stock APK."""
import argparse
import json
import pathlib
import re
import struct
import subprocess
import tempfile
import zipfile
from collections import Counter

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("result", type=pathlib.Path)
parser.add_argument("build_tools", type=pathlib.Path)
args = parser.parse_args()
APK = args.apk
SDK = args.build_tools

SDUI_FILTER = "Lapp/badfella/extension/linkedin/SduiComponentFilter;"
COMPONENT_TRANSFORMER = "Lcom/linkedin/sdui/transformer/impl/ComponentTransformer;"
SETTINGS = "Lapp/badfella/extension/linkedin/Settings;"
SETTINGS_ACTIVITY = "Lapp/badfella/extension/linkedin/SettingsActivity;"
MAIN_ACTIVITY = "Lcom/linkedin/android/infra/navigation/MainActivity;"

result = json.loads(args.result.read_text())
assert result["success"] and not result["failedPatches"], result
applied = set(result["appliedPatches"])
assert len(applied) >= 18, f"Expected at least 18 default patches applied, got {len(applied)}: {applied}"

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
                if descriptor in (SDUI_FILTER, COMPONENT_TRANSFORMER, SETTINGS, SETTINGS_ACTIVITY, MAIN_ACTIVITY):
                    targets.setdefault(descriptor, name)

    assert classes[SDUI_FILTER] == 1, "LinkedIn SDUI filter is missing or duplicated"
    assert classes[SETTINGS] == 1, "LinkedIn Settings is missing or duplicated"
    assert classes[COMPONENT_TRANSFORMER] == 1, "ComponentTransformer missing or duplicated"
    assert classes[MAIN_ACTIVITY] == 1, "MainActivity missing or duplicated"
    assert all(count == 1 for count in classes.values()), "Duplicate DEX classes"

    def disassemble(name):
        return subprocess.check_output(
            [str(SDK / "dexdump.exe"), "-d", str(root / name)],
            text=True, encoding="utf-8", errors="replace")

    transformer = disassemble(targets[COMPONENT_TRANSFORMER])
    assert "SduiComponentFilter.shouldHide:" in transformer, "SDUI shouldHide hook not injected into ComponentTransformer"
    assert "SduiComponentFilter.rewrite:" in transformer, "SDUI rewrite hook not injected into ComponentTransformer"

    main_act = disassemble(targets[MAIN_ACTIVITY])
    assert "SettingsShortcut.onMainActivityCreated:" in main_act, "SettingsShortcut hook not injected into MainActivity"

subprocess.run([str(SDK / "zipalign.exe"), "-c", "4", str(APK)], check=True)
print(f"PASS: LinkedIn 4.1.1271, {len(applied)} patches, SDUI transformer hooks, "
      f"SettingsShortcut hook, {len(classes)} unique classes, ZIP alignment")
