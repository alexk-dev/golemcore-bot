#!/usr/bin/env bash
set -euo pipefail

# Builds a local native distribution bundle around RuntimeLauncher and the
# executable bot jar produced by Maven. The resulting archive mirrors the
# layout shipped by GitHub Releases.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_DIR="${ROOT_DIR}/target"
NATIVE_DIST_DIR="${TARGET_DIR}/native-dist"
BUILD_DIR="${NATIVE_DIST_DIR}/build"
JPACKAGE_INPUT_DIR="${BUILD_DIR}/jpackage-input"
APP_IMAGE_OUTPUT_DIR="${BUILD_DIR}/app-image"
APP_NAME="golemcore-bot"
LAUNCHER_MAIN_CLASS="me.golemcore.bot.launcher.RuntimeLauncher"

# The native bundle relies on jpackage because it produces an app-image with
# platform-specific launchers while still allowing us to ship the real runtime jar.
if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage is required to build native distributions." >&2
  exit 1
fi

# The release jar is the actual Spring Boot runtime that RuntimeLauncher should
# restart into after an update.
RUNTIME_JAR_PATH="$(find "${TARGET_DIR}" -maxdepth 1 -type f -name 'bot-*-exec.jar' | sort | head -n 1)"
if [[ -z "${RUNTIME_JAR_PATH}" ]]; then
  echo "Executable runtime jar not found in target/. Run ./mvnw clean package first." >&2
  exit 1
fi

# The launcher classes are packaged separately so jpackage can bootstrap the
# app-image with the lightweight restart-aware entry point.
if [[ ! -d "${TARGET_DIR}/classes/me/golemcore/bot/launcher" ]]; then
  echo "RuntimeLauncher classes not found in target/classes. Run ./mvnw clean package first." >&2
  exit 1
fi

RUNTIME_JAR_NAME="$(basename "${RUNTIME_JAR_PATH}")"
VERSION="${RUNTIME_JAR_NAME#bot-}"
VERSION="${VERSION%-exec.jar}"
APP_VERSION="$(printf '%s' "${VERSION}" | sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*/\1/')"
if [[ -z "${APP_VERSION}" ]]; then
  APP_VERSION="0.0.0"
fi

# jpackage app-image layouts differ between Linux and macOS, so capture the
# platform-specific root and app directories once up front.
UNAME_S="$(uname -s)"
UNAME_M="$(uname -m)"
case "${UNAME_S}" in
  Linux)
    PLATFORM="linux"
    APP_IMAGE_ROOT_NAME="${APP_NAME}"
    APP_IMAGE_APP_DIR="${APP_NAME}"
    ;;
  Darwin)
    PLATFORM="macos"
    APP_IMAGE_ROOT_NAME="${APP_NAME}.app"
    APP_IMAGE_APP_DIR="${APP_NAME}.app/Contents/app"
    ;;
  *)
    echo "Unsupported operating system for native distribution: ${UNAME_S}" >&2
    exit 1
    ;;
esac

case "${UNAME_M}" in
  x86_64|amd64)
    ARCH="x64"
    ;;
  arm64|aarch64)
    ARCH="arm64"
    ;;
  *)
    echo "Unsupported CPU architecture for native distribution: ${UNAME_M}" >&2
    exit 1
    ;;
esac

ASSET_BASENAME="${APP_NAME}-${VERSION}-${PLATFORM}-${ARCH}"
ARCHIVE_PATH="${NATIVE_DIST_DIR}/${ASSET_BASENAME}.tar.gz"
LAUNCHER_JAR_PATH="${JPACKAGE_INPUT_DIR}/${APP_NAME}-launcher.jar"

rm -rf "${BUILD_DIR}"
rm -f "${ARCHIVE_PATH}"
mkdir -p "${JPACKAGE_INPUT_DIR}" "${APP_IMAGE_OUTPUT_DIR}" "${NATIVE_DIST_DIR}"

# Package only the launcher classes into a tiny bootstrap jar for jpackage.
jar --create \
  --file "${LAUNCHER_JAR_PATH}" \
  --main-class "${LAUNCHER_MAIN_CLASS}" \
  -C "${TARGET_DIR}/classes" me/golemcore/bot/launcher

# Point the launcher at the bundled runtime jar inside the app-image so the
# local native build behaves like the released bundle.
jpackage \
  --type app-image \
  --name "${APP_NAME}" \
  --dest "${APP_IMAGE_OUTPUT_DIR}" \
  --input "${JPACKAGE_INPUT_DIR}" \
  --main-jar "$(basename "${LAUNCHER_JAR_PATH}")" \
  --main-class "${LAUNCHER_MAIN_CLASS}" \
  --app-version "${APP_VERSION}" \
  --vendor "GolemCore" \
  --description "GolemCore Bot local launcher" \
  --java-options '-Dfile.encoding=UTF-8' \
  --java-options "-Dgolemcore.launcher.bundled-jar=\$APPDIR/lib/runtime/${RUNTIME_JAR_NAME}"

APP_IMAGE_DIR="${APP_IMAGE_OUTPUT_DIR}/${APP_IMAGE_APP_DIR}"
if [[ ! -d "${APP_IMAGE_DIR}" ]]; then
  echo "Expected app-image directory not found: ${APP_IMAGE_DIR}" >&2
  exit 1
fi

# Ship the real executable runtime jar alongside the launcher inside a dedicated
# runtime directory so restarts can jump into the jar directly.
RUNTIME_DIR="${APP_IMAGE_DIR}/lib/runtime"
mkdir -p "${RUNTIME_DIR}"
cp "${RUNTIME_JAR_PATH}" "${RUNTIME_DIR}/${RUNTIME_JAR_NAME}"

# Archive the platform-specific app-image as a release asset.
tar -czf "${ARCHIVE_PATH}" -C "${APP_IMAGE_OUTPUT_DIR}" "${APP_IMAGE_ROOT_NAME}"

echo "Created native distribution: ${ARCHIVE_PATH}"
