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
#    2. all of OUR modules are merged in, and none of the EXTERNAL libraries is. This pair
#       is the important one: our sibling modules are not on Maven Central, so `libraries:`
#       cannot fetch them and they must be shaded; everything external must be fetched and
#       must not be shaded;
#    3. every class we compile is Java 25 bytecode (major 69);
#    4. the configuration resources are inside the jar;
#    5. plugin.yml declares the `libraries:` entries — including BOTH SLF4J entries — and
#       their versions match the properties in the root pom.xml;
#    6. the source references no Paper or Adventure API, and plugin.yml carries no
#       Paper-only keys. This project targets the Spigot API only.
#
#  Usage: bash tools/ci/verify_jar.sh [path-to-jar] [max-bytes]
# =====================================================================================
set -euo pipefail

jar="${1:-}"
if [ -z "$jar" ]; then
  jar=$(ls xray-spigot/target/xray-anticheat-*.jar 2>/dev/null | grep -v '\-original' | head -1 || true)
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

    # 2a. Our modules must all be merged into the jar.
    for prefix, module in (("io/xrayac/core/", "xray-core"),
                           ("io/xrayac/persistence/", "xray-persistence"),
                           ("io/xrayac/web/", "xray-web"),
                           ("io/xrayac/spigot/", "xray-spigot")):
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
                        ("org/slf4j/", "slf4j"),
                        ("io/xrayac/libs/", "relocated third-party classes")):
        found = sum(1 for n in names if n.startswith(prefix))
        if found:
            failures.append(
                f"{lib} is bundled in the jar ({found} entries). It must be fetched at "
                f"runtime instead: check its scope in the root pom.xml is `provided`.")
    print("  externals      : none bundled (all fetched at runtime)")

    # 3. Our bytecode version.
    ours = [n for n in names if n.startswith("io/xrayac/") and n.endswith(".class")]
    if not ours:
        failures.append("no plugin classes found under io/xrayac/ (is this the right jar?)")
    else:
        majors = Counter(struct.unpack(">H", jar.read(n)[6:8])[0] for n in ours)
        if set(majors) != {69}:
            failures.append(
                f"expected every plugin class to be major 69 (Java 25), got {dict(majors)}")

    # 4. Resources: the configs needed to write defaults on first run, and the panel's assets.
    #    The assets matter as much as the configs and are easier to lose - they live in a different
    #    module's resources, so a build change that stops merging them would ship a panel that serves
    #    unstyled HTML and an unscripted page, which looks like a broken install and is invisible to
    #    every test that renders HTML as a string.
    configs = ("config.yml", "database.yml", "messages.yml", "gui.yml", "plugin.yml")
    for resource in configs:
        if resource not in names:
            failures.append(f"missing resource in jar: {resource}")
    for asset in ("web/app.css", "web/app.js"):
        if asset not in names:
            failures.append(
                f"missing panel asset in jar: {asset}. The panel would serve an unstyled, "
                f"unscripted page - it would still work, which is what makes it easy to miss.")
    print(f"  resources      : {len(configs)} configs + 2 panel assets")

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
            "org.slf4j:slf4j-api": props.get("slf4j.version"),
            "org.slf4j:slf4j-jdk14": props.get("slf4j.version"),
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
        print(f"  libraries      : {len(declared)} declared, versions match pom")

    # 5b. SLF4J must be declared. This INVERTS the old Paper-build check, deliberately.
    #     Spigot provides no SLF4J: spigot-api does not depend on slf4j-api (spigot-api did)
    #     and Spigot logs through java.util.logging. Missing the API means the plugin throws
    #     NoClassDefFoundError while loading; missing the binding means SLF4J silently
    #     discards every message. Both are near-invisible in testing.
    for required in ("org.slf4j:slf4j-api", "org.slf4j:slf4j-jdk14"):
        if required not in declared:
            failures.append(
                f"{required} is missing from `libraries:`. Spigot does not provide SLF4J, so "
                f"without the API the plugin fails to load and without a binding every log "
                f"line is silently discarded.")
    print("  slf4j          : api + jdk14 binding both declared (required on Spigot)")

    # 6a. No Paper-only keys in the shipped plugin.yml.
    for key in ("folia-supported", "paper-skip-libraries", "bootstrapper"):
        if re.search(rf"^{re.escape(key)}\s*:", plugin_yml, re.M):
            failures.append(
                f"plugin.yml declares `{key}`, which is Paper-only. This plugin targets the "
                f"Spigot API only; Spigot does not read it.")
    print("  plugin.yml     : no Paper-only keys")

# 6b. No Paper or Adventure API referenced anywhere in the source.
banned = []
for java in Path(".").rglob("*.java"):
    if "target" in java.parts:
        continue
    text = java.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r"^import\s+(io\.papermc|net\.kyori)[\w.]*", text, re.M):
        banned.append(f"{java}: {m.group(0)}")
if banned:
    failures.append(
        "Paper/Adventure API referenced in source: " + "; ".join(banned[:5]) +
        ". This project must compile against the Spigot API only.")
print("  source         : no io.papermc / net.kyori references")

if failures:
    print("\nFAILURES:", file=sys.stderr)
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    sys.exit(1)

print("\nAll jar checks passed.")
PY
