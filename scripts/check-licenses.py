#!/usr/bin/env python3
"""Check declared direct Gradle dependency pins against the human-reviewed inventory."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
build = (root / "composeApp/build.gradle.kts").read_text(encoding="utf-8")
inventory_text = (root / "THIRD_PARTY.md").read_text(encoding="utf-8")
rows = [line for line in inventory_text.splitlines() if line.startswith("|") and not re.match(r"\|[-| ]+\|?$", line)]
coords = sorted(set(re.findall(r'implementation\("([^" ]+:[^" ]+:[^" ]+)"\)', build)))

# Map every direct implementation family to the specific row that documents it.
def inventory_row(coordinate: str) -> str | None:
    group, artifact, _version = coordinate.split(":", 2)
    if group == "androidx.media3":
        keyword = "androidx media3"
    elif group == "androidx.activity":
        keyword = "androidx activity compose"
    elif group == "io.ktor":
        keyword = "ktor client"
    elif group == "io.coil-kt.coil3":
        keyword = "coil 3"
    elif group == "org.jetbrains.kotlinx" and artifact.startswith("kotlinx-serialization-"):
        keyword = "kotlin multiplatform / serialization"
    elif group == "org.jetbrains.kotlinx" and artifact.startswith("kotlinx-coroutines-"):
        keyword = "kotlinx.coroutines"
    elif group == "net.java.dev.jna" and artifact == "jna":
        keyword = "jna"
    elif group == "org.jetbrains.compose.material3":
        keyword = "compose material 3 multiplatform"
    elif group.startswith("org.jetbrains.compose."):
        keyword = "compose multiplatform"
    else:
        keyword = artifact.lower()
    return next((row for row in rows if keyword in row.lower().split("|")[1]), None)

missing = []
for coordinate in coords:
    _group, _artifact, version = coordinate.split(":", 2)
    packaging = "@aar" if version.endswith("@aar") else ""
    version = version.removesuffix("@aar")
    row = inventory_row(coordinate)
    if row is None:
        missing.append(f"{coordinate}: no inventory row")
    elif not re.search(rf"(?<![0-9A-Za-z.]){re.escape(version)}(?![0-9A-Za-z.])", row):
        missing.append(f"{coordinate}: inventory row does not mention exact version {version}")
    elif packaging == "@aar" and "android aar" not in row.lower():
        missing.append(f"{coordinate}: Android AAR packaging is not documented")
if missing:
    print("Dependency inventory mismatch:", *missing, sep="\n  ", file=sys.stderr)
    raise SystemExit(1)
if "licence" not in inventory_text.lower() and "license" not in inventory_text.lower():
    raise SystemExit("THIRD_PARTY.md lacks licence statements")
print(f"Dependency inventory pins match exact versions for {len(coords)} explicit implementation coordinates; transitive licences still require a resolved Gradle report.")
