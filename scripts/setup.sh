#!/usr/bin/env bash
# One-command setup on Ubuntu (22.04 or newer):
#
#   ./scripts/setup.sh                    # everything needed to build, test and run the demo
#   ./scripts/setup.sh --with-guide-tools # also Python and Node.js, for editing the guide
#
# Installs JDK 27 if it is missing, then runs a first build through the Gradle
# wrapper, which downloads Gradle, the Kotlin compiler and the test libraries.
# Safe to run again: anything already installed is left alone.
set -euo pipefail

cd "$(dirname "$0")/.."

with_guide_tools=false
for arg in "$@"; do
  case "$arg" in
    --with-guide-tools) with_guide_tools=true ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# Major version of the java on PATH, or 0 if there is none.
java_major() {
  command -v java >/dev/null 2>&1 || { echo 0; return; }
  java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/;t;s/.*/0/'
}

# Any JDK 27 counts, even if it isn't the default java, because the build selects
# JDK 27 itself through its Java toolchain setting.
if [ "$(java_major)" -ge 27 ] || compgen -G "/usr/lib/jvm/*27*" >/dev/null; then
  echo "==> JDK 27 already installed"
else
  echo "==> Installing JDK 27 (Eclipse Temurin; needs sudo)"
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends ca-certificates wget gpg
  sudo install -d -m 0755 /etc/apt/keyrings
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public |
    gpg --dearmor | sudo tee /etc/apt/keyrings/adoptium.gpg >/dev/null
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" |
    sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
  sudo apt-get update
  sudo apt-get install -y temurin-27-jdk
fi

if [ "$with_guide_tools" = true ]; then
  echo "==> Installing Python 3 and Node.js for the guide tools"
  sudo apt-get install -y python3 nodejs
  node_major=$(node --version 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/' || echo 0)
  if [ "${node_major:-0}" -lt 18 ]; then
    echo "WARNING: Node.js $node_major is too old for scripts/check-guide.mjs (needs 18+)."
    echo "         On Ubuntu 22.04, install a newer Node.js with nvm: https://github.com/nvm-sh/nvm"
  fi
fi

# First build. The wrapper finds JDK 27 itself (via the build's Java toolchain
# setting), even if an older JDK is the default.
echo "==> First build: downloads Gradle and the libraries (a few minutes the first time)"
./gradlew --quiet assemble testClasses

echo
echo "Ready. Run the demo with:   ./gradlew run"
echo "Run the web console with:   ./gradlew web     (then open http://localhost:8080)"
echo "Run the tests with:         ./gradlew test"
