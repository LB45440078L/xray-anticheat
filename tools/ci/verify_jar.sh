#!/usr/bin/env bash
# =====================================================================================
#  Verify the packaged plugin jar
# =====================================================================================
#  Run after `mvn package` (as `bash tools/ci/verify_jar.sh` — the executable bit is not
#  tracked). It checks the things that are easy to get wrong and invisible to the unit
#  tests, because those run against unshaded module output:
#
#    1. the jar is inside its size budget — the whole point of the runtime-libraries
#       design, and the thing that silently regresses if a `provided` scope is dropped;
#    2. all three of OUR modules are merged in, and all five EXTERNAL libraries are not.
#       This pair is the important one: our two sibling modules are not on Maven Central,
#       so `libraries:` cannot fetch them and they must be shaded; everything external must
#       be fetched and must not be shaded;
#    3. every class we compile is Java 25 bytecode (major 69);
#    4. the five configuration resources are inside the jar;
#    5. plugin.yml still declares the `libraries:` entries, and their versions match the
#       properties in the root pom.xml. Drift here produces a plugin that compiles cleanly
#       and then dies at runtime, which is the worst possible failure mode.
#
#  Usage: bash tools/ci/verify_jar.sh [path-to-jar] [max-bytes]
# =====================================================================================
set -euo pipefail

jar="${1:-}"
if [ -z "$jar" ]; then
  jar=$(ls xray-paper/target/xray-anticheat-*.jar 2>/dev/null | grep -v '\-original' | head -1 || true)
fi
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "FAIL: no jar found (pass one as an argument, or run mvn package first)" >&2
  exit 1
fi

budget="${2:-4194304}"   # 4 MiB, the agreed ceiling
echo "Verifying $jar ($(stat -c%s "$jar") bytes, budget $budget)"
python3 - "$jar" "$budget" <<'PY'
import os
import re
import struct
import sys
import zipfile
from collections import Counter
from pathlib import Path

path, budget = sys.argv[1], int(sys.argv[2])
failures = []

with zipfile.ZipFile(path) as jar:
    names = jar.namelist()
    # The real on-disk size is what a user downloads, so that is what the budget applies to.
    size = os.path.getsize(path)

    # 1. Size budget.
    print(f"  size           : {size:,} bytes = {size/1024:.0f} KB "
          f"({size/1024/1024:.2f} MB), budget {budget/1024/1024:.0f} MB")
    if size > budget:
        failures.append(
            f"jar is {size:,} bytes, over the {budget:,}-byte budget. The usual cause is a "
            f"dependency that lost its `provided` scope in pom.xml, so shade bundled it again.")

    # 2a. Our three modules must all be merged into the jar.
    for prefix, module in (("io/xrayac/core/", "xray-core"),
                           ("io/xrayac/persistence/", "xray-persistence"),
                           ("io/xrayac/paper/", "xray-paper")):
        found = sum(1 for n in names if n.startswith(prefix) and n.endswith(".class"))
        print(f"  {module:<15}: {found:>4} classes")
        if not found:
            failures.append(
                f"{module} is MISSING from the jar. It is our own code and is not on Maven "
                f"Central, so `libraries:` cannot fetch it: it must be shaded in.")

    # 2b. External libraries must NOT be merged — they arrive via plugin.yml `libraries:`.
    for prefix, lib in (("org/sqlite/", "sqlite-jdbc"),
                        ("org/postgresql/", "postgresql"),
                        ("org/mariadb/", "mariadb-java-client"),
                        ("com/zaxxer/", "HikariCP"),
                        ("org/slf4j/", "slf4j-api"),
                        ("io/xrayac/libs/", "relocated third-party classes")):
        found = sum(1 for n in names if n.startswith(prefix))
        if found:
            failures.append(
                f"{lib} is bundled in the jar ({found} entries). It must be fetched at "
                f"runtime instead: check its scope in the root pom.xml is `provided`.")
    print("  externals      : all 5 absent from jar (fetched at runtime)")

    # 3. Our bytecode version.
    ours = [n for n in names if n.startswith("io/xrayac/") and n.endswith(".class")]
    if not ours:
        failures.append("no plugin classes found under io/xrayac/ (is this the right jar?)")
    else:
        majors = Counter(struct.unpack(">H", jar.read(n)[6:8])[0] for n in ours)
        if set(majors) != {69}:
            failures.append(
                f"expected every plugin class to be major 69 (Java 25), got {dict(majors)}")

    # 4. Resources needed to write defaults on first run.
    for resource in ("config.yml", "database.yml", "messages.yml", "gui.yml", "plugin.yml"):
        if resource not in names:
            failures.append(f"missing resource in jar: {resource}")
    print("  resources      : checked 5")

    # 5. plugin.yml declares the runtime libraries, at the versions the pom pins.
    plugin_yml = jar.read("plugin.yml").decode("utf-8", "replace")
    declared = re.findall(r"^\s*-\s*([\w.\-]+:[\w.\-]+:[\S]+)\s*$", plugin_yml, re.M)
    declared = {c.split(":")[0] + ":" + c.split(":")[1]: c.split(":")[2] for c in declared}

    pom = Path("pom.xml")
    if not pom.is_file():
        failures.append("pom.xml not found: run this from the repository root, it cross-checks "
                        "the plugin.yml library versions against the pom")
    else:
        pom_text = pom.read_text(encoding="utf-8")
        props = dict(re.findall(r"<([\w.\-]+\.version)>([^<]+)</\1>", pom_text))
        expected = {
            "com.zaxxer:HikariCP": props.get("hikari.version"),
            "org.xerial:sqlite-jdbc": props.get("sqlite.version"),
            "org.mariadb.jdbc:mariadb-java-client": props.get("mariadb.version"),
            "org.postgresql:postgresql": props.get("postgresql.version"),
        }
        for coord, want in expected.items():
            got = declared.get(coord)
            if got is None:
                failures.append(
                    f"plugin.yml does not declare `{coord}` under `libraries:`. Without it the "
                    f"server will not fetch the library and the plugin fails to load.")
            elif want and got != want:
                failures.append(
                    f"{coord} version drift: plugin.yml says {got}, pom.xml pins {want}. "
                    f"Update both together or the plugin breaks at runtime only.")
        print(f"  libraries      : {len(declared)} declared in plugin.yml, versions match pom")

    if any("slf4j" in coord for coord in declared):
        failures.append(
            "plugin.yml declares slf4j under `libraries:`: the server already provides "
            "slf4j-api, so fetching a second copy risks a provider mismatch. Remove it.")
    print("  slf4j-api      : correctly not declared (server provides it)")

if failures:
    print("\nFAILURES:", file=sys.stderr)
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    sys.exit(1)

print("\nAll jar checks passed.")
PY
