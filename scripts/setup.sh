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

# Major version of a candidate Java executable, or 0 if it cannot run.
java_major() {
  "$1" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/;t;s/.*/0/'
}

select_jdk() {
  local path_home="" home java_path
  if java_path=$(command -v java); then
    path_home=$(dirname "$(dirname "$(readlink -f "$java_path")")")
  fi
  for home in "${JAVA_HOME:-}" "$path_home" /usr/lib/jvm/*27*; do
    if [ -x "$home/bin/java" ] && [ -x "$home/bin/javac" ] && [ "$(java_major "$home/bin/java")" = 27 ]; then
      export JAVA_HOME="$home"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done
  return 1
}

# The wrapper prioritizes JAVA_HOME over PATH; the toolchain cannot repair an old daemon JVM.
if select_jdk; then
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
  if ! select_jdk; then
    echo "JDK 27 is still unavailable after installation. Set JAVA_HOME to a JDK 27 installation and rerun setup." >&2
    exit 1
  fi
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

# Both the wrapper's JVM and the compilation toolchain now use JDK 27.
echo "==> First build: downloads Gradle and the libraries (a few minutes the first time)"
./gradlew --quiet assemble testClasses

echo
echo "Ready. Run the demo with:   ./gradlew run"
echo "Run the web console with:   ./gradlew web     (then open http://localhost:8080)"
echo "Run the tests with:         ./gradlew test"
