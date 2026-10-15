#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="${1:-debug}"
if (( $# > 0 )); then shift; fi

usage() {
  printf '%s\n' \
    "Сборка и публикация LumaWork Android" \
    "" \
    "Использование: scripts/build-apk.sh [команда]" \
    "  debug                 — устанавливаемый debug APK (по умолчанию)" \
    "  apk                   — подписанный оптимизированный release APK" \
    "  aab                   — подписанный release AAB для Google Play" \
    "  release               — подписанные release APK и AAB" \
    "  all                   — debug APK, release APK и AAB" \
    "  verify                — unit-тесты, lint и все артефакты" \
    "  publish [описание]    — release APK/AAB и публикация APK на app.lumastack.ru" \
    "  clean                 — удалить только Gradle/.build артефакты" \
    "  help                  — показать эту справку"
}

if [[ "$MODE" == "-h" || "$MODE" == "--help" || "$MODE" == "help" ]]; then
  usage
  exit 0
fi

if [[ "$MODE" == "publish" ]]; then
  exec "$SCRIPT_DIR/publish-android.sh" "$@"
fi

if ! command -v java >/dev/null 2>&1; then
  printf '%s\n' "Ошибка: требуется JDK 17 или новее." >&2
  exit 1
fi

JAVA_MAJOR="$(java -version 2>&1 | awk -F '[\".]' '/version/ { print ($2 == "1" ? $3 : $2); exit }')"
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 17 )); then
  printf '%s\n' "Ошибка: найдена Java ${JAVA_MAJOR:-неизвестной версии}, требуется JDK 17 или новее." >&2
  exit 1
fi

if [[ ! -x "$PROJECT_DIR/gradlew" ]]; then
  printf '%s\n' "Ошибка: Gradle wrapper не найден в $PROJECT_DIR" >&2
  exit 1
fi

needs_release=0
case "$MODE" in
  debug) TASKS=(":app:assembleDebug") ;;
  apk) needs_release=1; TASKS=(":app:assembleRelease") ;;
  aab) needs_release=1; TASKS=(":app:bundleRelease") ;;
  release) needs_release=1; TASKS=(":app:assembleRelease" ":app:bundleRelease") ;;
  all) needs_release=1; TASKS=(":app:assembleDebug" ":app:assembleRelease" ":app:bundleRelease") ;;
  verify) needs_release=1; TASKS=(":app:testDebugUnitTest" ":app:lintDebug" ":app:assembleDebug" ":app:assembleRelease" ":app:bundleRelease") ;;
  clean) TASKS=("clean") ;;
  *)
    printf '%s\n' "Ошибка: неизвестная команда '$MODE'." >&2
    usage >&2
    exit 2
    ;;
esac

if (( needs_release )); then
  if [[ -z "${LUMAWORK_ANDROID_STORE_PASSWORD:-}" ]] && command -v security >/dev/null 2>&1; then
    LUMAWORK_ANDROID_STORE_PASSWORD="$(security find-generic-password -w -s ru.lumastack.lumawork.android.store -a LumaWork 2>/dev/null || true)"
    export LUMAWORK_ANDROID_STORE_PASSWORD
  fi
  if [[ -z "${LUMAWORK_ANDROID_KEY_PASSWORD:-}" ]] && command -v security >/dev/null 2>&1; then
    LUMAWORK_ANDROID_KEY_PASSWORD="$(security find-generic-password -w -s ru.lumastack.lumawork.android.key -a LumaWork 2>/dev/null || true)"
    export LUMAWORK_ANDROID_KEY_PASSWORD
  fi
  if [[ ! -f "$PROJECT_DIR/keystore.properties" || -z "${LUMAWORK_ANDROID_STORE_PASSWORD:-}" || -z "${LUMAWORK_ANDROID_KEY_PASSWORD:-}" ]]; then
    printf '%s\n' "Ошибка: release-подпись не настроена. См. docs/RELEASE_AND_SIGNING.md." >&2
    exit 1
  fi
fi

GRADLE_OPTIONS=(--project-dir "$PROJECT_DIR" --no-daemon)
if [[ -n "${RELEASE_VERSION_NAME:-}" ]]; then
  GRADLE_OPTIONS+=("-PreleaseVersionName=$RELEASE_VERSION_NAME")
fi
if [[ -n "${RELEASE_VERSION_CODE:-}" ]]; then
  GRADLE_OPTIONS+=("-PreleaseVersionCode=$RELEASE_VERSION_CODE")
fi
"$PROJECT_DIR/gradlew" "${GRADLE_OPTIONS[@]}" "${TASKS[@]}"

if [[ "$MODE" == "clean" ]]; then
  if [[ -d "$PROJECT_DIR/.build" ]]; then rm -rf "$PROJECT_DIR/.build"; fi
  printf '%s\n' "Build-артефакты удалены."
  exit 0
fi

if (( needs_release )); then
  sdk_dir="${ANDROID_HOME:-$(awk -F= '/^sdk.dir=/{print substr($0, index($0, "=") + 1); exit}' "$PROJECT_DIR/local.properties" 2>/dev/null || true)}"
  apksigner="$(find "$sdk_dir/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1)"
  release_apk="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
  release_aab="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"
  if [[ -f "$release_apk" ]]; then
    [[ -x "$apksigner" ]] || { printf '%s\n' "Ошибка: apksigner не найден в Android SDK." >&2; exit 1; }
    "$apksigner" verify --verbose --print-certs "$release_apk" >/dev/null
  fi
  if [[ -f "$release_aab" ]]; then
    jarsigner -verify "$release_aab" >/dev/null
  fi
fi

printf '%s\n' "Готовые артефакты:"
case "$MODE" in
  debug) ARTIFACT_DIRS=("$PROJECT_DIR/app/build/outputs/apk/debug") ;;
  apk) ARTIFACT_DIRS=("$PROJECT_DIR/app/build/outputs/apk/release") ;;
  aab) ARTIFACT_DIRS=("$PROJECT_DIR/app/build/outputs/bundle/release") ;;
  *) ARTIFACT_DIRS=("$PROJECT_DIR/app/build/outputs/apk/debug" "$PROJECT_DIR/app/build/outputs/apk/release" "$PROJECT_DIR/app/build/outputs/bundle/release") ;;
esac
find "${ARTIFACT_DIRS[@]}" -type f \( -name '*.apk' -o -name '*.aab' \) -print0 2>/dev/null \
  | sort -z \
  | while IFS= read -r -d '' artifact; do
      size="$(du -h "$artifact" | awk '{print $1}')"
      printf '  %s  %s\n' "$size" "$artifact"
    done
