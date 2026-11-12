"""Verify the patched ChatGPT clone APK output."""
import argparse
import subprocess
import zipfile
from pathlib import Path


def run(*args):
    result = subprocess.run(list(map(str, args)), text=True, encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        raise RuntimeError(result.stdout)
    return result.stdout


def verify(stock_path: Path, output_path: Path, aapt_path: Path):
    print(f"Verifying patched ChatGPT clone: {output_path}")

    # 1. Archive integrity
    with zipfile.ZipFile(output_path) as zf:
        names = zf.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP entries found"
        assert "AndroidManifest.xml" in names, "Missing AndroidManifest.xml"
        dex_files = [n for n in names if n.startswith("classes") and n.endswith(".dex")]
        assert len(dex_files) >= 1, "Missing classes.dex"
        print(f"PASS: Archive integrity valid. Found {len(dex_files)} DEX files.")

    # 2. AAPT Badging checks
    badging = run(aapt_path, "dump", "badging", output_path)
    assert "package: name='com.openai.chatgpt.clone'" in badging, "Package name is not com.openai.chatgpt.clone"
    assert "application-label:'ChatGPT Clone'" in badging, "Application label is not 'ChatGPT Clone'"
    print("PASS: Package name and application label verified.")

    # 3. AAPT XMLTree checks for manifest transformations
    xmltree = run(aapt_path, "dump", "xmltree", output_path, "AndroidManifest.xml")
    
    # Check permissions
    assert 'android:name(0x01010003)="com.openai.chatgpt.clone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"' in xmltree, \
        "Missing namespaced DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    # Check key provider authorities
    expected_authorities = [
        "com.openai.chatgpt.clone.androidx-startup",
        "com.openai.chatgpt.clone.firebaseinitprovider",
        "com.openai.chatgpt.clone.stickers",
        "com.openai.chatgpt.clone.files",
        "com.openai.chatgpt.clone.glyphs",
        "com.openai.chatgpt.clone.persona.provider",
        "com.openai.chatgpt.clone.valdi.clipboard",
        "com.openai.chatgpt.clone.mlkitinitprovider",
        "com.openai.chatgpt.clone.resources.AndroidContextProvider",
        "com.openai.chatgpt.clone.provider.datadog.rum",
        "com.openai.chatgpt.clone.SentryNdkPreloadProvider",
    ]
    for auth in expected_authorities:
        assert auth in xmltree, f"Authority {auth} not found in manifest"
    print(f"PASS: All {len(expected_authorities)} provider authorities correctly namespaced.")

    # Check OAuth redirect filter
    assert 'android:scheme(0x01010027)="com.openai.chatgpt.clone"' in xmltree, \
        "Clone OAuth redirect scheme com.openai.chatgpt.clone missing"
    assert 'android:pathPrefix(0x0101002b)="/android/com.openai.chatgpt.clone/callback"' in xmltree, \
        "Clone callback path prefix missing"
    assert 'android:scheme(0x01010027)="com.openai.chatgpt"' in xmltree, \
        "Stock OAuth redirect scheme com.openai.chatgpt was inadvertently removed"
    print("PASS: Dual OAuth callback intent-filters verified in WebRedirectActivity.")

    # Check task affinities
    assert 'android:taskAffinity(0x01010012)="com.openai.chatgpt.clone.elicitation"' in xmltree, \
        "Task affinity com.openai.chatgpt.clone.elicitation missing"
    assert 'android:taskAffinity(0x01010012)="com.openai.chatgpt.clone.orbitvoice"' in xmltree, \
        "Task affinity com.openai.chatgpt.clone.orbitvoice missing"
    print("PASS: Task affinities namespaced.")

    print("\nALL ACCEPTANCE CRITERIA VERIFIED SUCCESSFULLY (PASS).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--aapt", type=Path, required=True)
    args = parser.parse_args()
    verify(args.stock, args.output, args.aapt)
