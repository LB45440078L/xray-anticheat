#!/usr/bin/env bash
# =====================================================================================
#  Verify the packaged plugin jar
# =====================================================================================
#  Run after `mvn package`. Checks the things that are easy to get wrong and invisible to
#  the unit tests, because those run against the unshaded module output:
#
#    1. every class we compile is Java 25 bytecode (major 69) — proves the compiler
#       release actually took effect;
#    2. the five configuration resources are inside the jar, so the plugin can write its
#       defaults on first run;
#    3. each JDBC driver is still registered as a service — if the shade merge dropped a
#       registration, one of the three database backends would fail at runtime only;
#    4. sqlite-jdbc is NOT relocated — relocating a JNI library breaks its native symbol
#       names, and the failure appears only on a real server.
#
#  Usage: tools/ci/verify_jar.sh [path-to-jar]
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

echo "Verifying $jar ($(stat -c%s "$jar") bytes)"
python3 - "$jar" <<'PY'
import struct
import sys
import zipfile
from collections import Counter

path = sys.argv[1]
failures = []

with zipfile.ZipFile(path) as jar:
    names = jar.namelist()

    # 1. Our own bytecode, excluding relocated third-party libraries (which are at their
    #    own original versions and are not ours to police).
    ours = [n for n in names
            if n.startswith("io/xrayac/") and "/libs/" not in n and n.endswith(".class")]
    if not ours:
        failures.append("no plugin classes found under io/xrayac/ (is this the right jar?)")
    else:
        majors = Counter(struct.unpack(">H", jar.read(n)[6:8])[0] for n in ours)
        print(f"  plugin classes : {len(ours)}, major versions {dict(majors)}")
        if set(majors) != {69}:
            failures.append(f"expected every plugin class to be major 69 (Java 25), got {dict(majors)}")

    # 2. Resources the plugin needs to save its defaults.
    for resource in ("config.yml", "database.yml", "messages.yml", "gui.yml", "plugin.yml"):
        if resource not in names:
            failures.append(f"missing resource in jar: {resource}")
    print("  resources      : checked 5")

    # 3. Driver service registrations, merged rather than overwritten.
    driver_service = "META-INF/services/java.sql.Driver"
    if driver_service not in names:
        failures.append(f"{driver_service} is absent: no JDBC driver would be discoverable")
    else:
        registered = [line.strip() for line in
                      jar.read(driver_service).decode("utf-8", "replace").splitlines()
                      if line.strip()]
        print(f"  sql.Driver     : {registered}")
        for expected in ("org.sqlite.JDBC", "org.mariadb.jdbc.Driver", "org.postgresql.Driver"):
            if expected not in registered:
                failures.append(
                    f"{expected} is not registered in {driver_service}: the shade merge dropped it")

    # 4. sqlite-jdbc must be present and unrelocated.
    relocated_sqlite = [n for n in names if n.startswith("io/xrayac/libs/sqlite/")]
    if relocated_sqlite:
        failures.append(
            f"sqlite-jdbc appears relocated to io/xrayac/libs/sqlite ({len(relocated_sqlite)} entries); "
            "that breaks its JNI native symbol names")
    if not any(n.startswith("org/sqlite/") for n in names):
        failures.append("org/sqlite/ is absent: sqlite-jdbc is not in the jar")
    print(f"  sqlite-jdbc    : {'unrelocated OK' if not relocated_sqlite else 'RELOCATED (bad)'}")

if failures:
    print("\nFAILURES:", file=sys.stderr)
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    sys.exit(1)

print("\nAll jar checks passed.")
PY
