#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_DIR="${ROOT_DIR}/target"
APP_TARGET_DIR="${ROOT_DIR}/golemcore-bot-app/target"
EXEC_JAR_PATH="$(find "${APP_TARGET_DIR}" -maxdepth 1 -type f -name 'bot-*-exec.jar' | sort | head -n 1)"
NATIVE_LAUNCHER_JAR_PATH="${TARGET_DIR}/native-dist/build/jpackage-input/golemcore-bot-launcher.jar"

expect_manifest_value() {
  local jar_path="$1"
  local key="$2"
  local expected="$3"
  local actual

  if ! actual="$(unzip -p "${jar_path}" META-INF/MANIFEST.MF \
    | tr -d '\r' \
    | awk -F': ' -v key="${key}" '$1 == key { print $2; found = 1; exit } END { if (!found) exit 1 }')"; then
    echo "Manifest key ${key} not found in ${jar_path}." >&2
    exit 1
  fi

  if [[ "${actual}" != "${expected}" ]]; then
    echo "Unexpected ${key} in ${jar_path}: expected '${expected}', got '${actual}'." >&2
    exit 1
  fi
}

expect_jar_entry() {
  local jar_path="$1"
  local entry="$2"

  if ! jar tf "${jar_path}" | grep -Fxq "${entry}"; then
    echo "Expected jar entry not found in ${jar_path}: ${entry}" >&2
    exit 1
  fi
}

if [[ -z "${EXEC_JAR_PATH}" ]]; then
  echo "Executable runtime jar not found in ${APP_TARGET_DIR}." >&2
  exit 1
fi

expect_manifest_value "${EXEC_JAR_PATH}" "Main-Class" "org.springframework.boot.loader.launch.JarLauncher"
expect_manifest_value "${EXEC_JAR_PATH}" "Start-Class" "me.golemcore.bot.BotApplication"
expect_jar_entry "${EXEC_JAR_PATH}" "BOOT-INF/classes/me/golemcore/bot/launcher/RuntimeLauncher.class"
expect_jar_entry "${EXEC_JAR_PATH}" "BOOT-INF/classes/me/golemcore/bot/launcher/RuntimeCliLauncher.class"
expect_jar_entry "${EXEC_JAR_PATH}" "BOOT-INF/classes/me/golemcore/bot/launcher/RuntimeJarVersionReader.class"

if [[ ! -f "${NATIVE_LAUNCHER_JAR_PATH}" ]]; then
  echo "Native launcher jar not found: ${NATIVE_LAUNCHER_JAR_PATH}" >&2
  exit 1
fi

expect_manifest_value "${NATIVE_LAUNCHER_JAR_PATH}" "Main-Class" "me.golemcore.bot.launcher.RuntimeCliLauncher"
expect_jar_entry "${NATIVE_LAUNCHER_JAR_PATH}" "me/golemcore/bot/launcher/RuntimeLauncher.class"
expect_jar_entry "${NATIVE_LAUNCHER_JAR_PATH}" "me/golemcore/bot/launcher/RuntimeCliLauncher.class"
expect_jar_entry "${NATIVE_LAUNCHER_JAR_PATH}" "me/golemcore/bot/launcher/RuntimeJarVersionReader.class"

echo "Launcher entrypoint packaging contract verified."
