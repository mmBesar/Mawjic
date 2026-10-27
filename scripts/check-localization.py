#!/usr/bin/env python3
"""Verify that every literal common-UI translation key has an Arabic entry."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
app = (ROOT / "composeApp/src/commonMain/kotlin/app/mawjic/tv/App.kt").read_text(encoding="utf-8")
strings = (ROOT / "composeApp/src/commonMain/kotlin/app/mawjic/tv/Strings.kt").read_text(encoding="utf-8")

used = set(re.findall(r'tr\("((?:[^"\\]|\\.)*)"\)', app))
pairs = re.findall(r'^\s*"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"\s*,?\s*$', strings, re.MULTILINE)
keys = [key for key, _value in pairs]
provided = set(keys)
duplicates = sorted({key for key in keys if keys.count(key) > 1})
missing = sorted(used - provided)
assert not duplicates, f"Duplicate localization keys: {duplicates}"
assert not missing, f"UI literals without Arabic translation: {missing}"
assert 'supportedLanguages = listOf("English", "Arabic")' in strings
assert '"Arabic" to "العربية"' in strings
assert '"Streaming, your wave." to "البث على موجتك."' in strings
print(f"Localization check passed: {len(used)} literal UI keys have Arabic translations; English/Arabic selector entries exist.")
