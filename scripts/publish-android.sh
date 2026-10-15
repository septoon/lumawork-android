#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SSH_HOST="${SSH_HOST:-lumastack-mcp}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://app.lumastack.ru}"
REMOTE_ROOT="${REMOTE_ROOT:-/var/www/app.lumastack.ru}"
REMOTE_STAGE_ROOT="${REMOTE_STAGE_ROOT:-/home/deploy/app-lumastack-android-release}"
ALLOW_BOOTSTRAP="${ALLOW_BOOTSTRAP:-0}"
RELEASE_VERSION="${RELEASE_VERSION:-}"
RELEASE_NOTES="${*:-Обновление LumaWork для Android}"
BUILD_ROOT="${BUILD_ROOT:-$PROJECT_DIR/.build/android-release}"
METADATA_URL="$PUBLIC_BASE_URL/android-release.json"

for command_name in curl jq shasum; do
  command -v "$command_name" >/dev/null || { printf 'Ошибка: не найдена команда %s.\n' "$command_name" >&2; exit 1; }
done

project_version="$(sed -n 's/.*releaseVersionName.*?: "\([0-9][0-9.]*\)".*/\1/p' "$PROJECT_DIR/app/build.gradle.kts" | head -1)"
project_code="$(sed -n 's/.*releaseVersionCode.*?: \([0-9][0-9]*\).*/\1/p' "$PROJECT_DIR/app/build.gradle.kts" | head -1)"

if remote_metadata="$(curl -fsS "$METADATA_URL" 2>/dev/null)"; then
  remote_version="$(jq -r '.version // empty' <<<"$remote_metadata")"
  remote_code="$(jq -r '.versionCode // empty' <<<"$remote_metadata")"
elif [[ "$ALLOW_BOOTSTRAP" == "1" ]]; then
  remote_version=""
  remote_code=""
else
  printf '%s\n' "Ошибка: $METADATA_URL недоступен. Для самой первой публикации задайте ALLOW_BOOTSTRAP=1." >&2
  exit 1
fi

if [[ -n "$RELEASE_VERSION" ]]; then
  version="$RELEASE_VERSION"
elif [[ "$remote_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  version="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"
elif [[ "$ALLOW_BOOTSTRAP" == "1" && "$project_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  version="$project_version"
else
  printf '%s\n' "Ошибка: не удалось определить следующую semantic version." >&2
  exit 1
fi

if [[ "$remote_code" =~ ^[0-9]+$ ]]; then
  version_code="$((remote_code + 1))"
elif [[ "$ALLOW_BOOTSTRAP" == "1" && "$project_code" =~ ^[0-9]+$ ]]; then
  version_code="$project_code"
else
  printf '%s\n' "Ошибка: не удалось определить следующий versionCode." >&2
  exit 1
fi

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf '%s\n' "Ошибка: RELEASE_VERSION должен иметь формат X.Y.Z." >&2; exit 1; }

RELEASE_VERSION_NAME="$version" RELEASE_VERSION_CODE="$version_code" "$SCRIPT_DIR/build-apk.sh" release

apk_source="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
aab_source="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"
[[ -f "$apk_source" && -f "$aab_source" ]] || { printf '%s\n' "Ошибка: release APK/AAB не найдены." >&2; exit 1; }

mkdir -p "$BUILD_ROOT"
apk_name="LumaWork-Android-$version-$version_code.apk"
apk_path="$BUILD_ROOT/$apk_name"
metadata_path="$BUILD_ROOT/android-release.json"
cp "$apk_source" "$apk_path"
apk_size="$(stat -f '%z' "$apk_path")"
apk_sha256="$(shasum -a 256 "$apk_path" | awk '{print $1}')"
release_date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
download_url="$PUBLIC_BASE_URL/$apk_name"
latest_url="$PUBLIC_BASE_URL/LumaWork-Android-latest.apk"
sdk_dir="${ANDROID_HOME:-$(awk -F= '/^sdk.dir=/{print substr($0, index($0, "=") + 1); exit}' "$PROJECT_DIR/local.properties" 2>/dev/null || true)}"
apksigner="$(find "$sdk_dir/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1)"
certificate_sha256="$($apksigner verify --print-certs "$apk_path" | awk -F': ' '/SHA-256 digest/{print $2; exit}')"

jq -n \
  --arg version "$version" \
  --argjson versionCode "$version_code" \
  --arg date "$release_date" \
  --arg notes "$RELEASE_NOTES" \
  --arg url "$download_url" \
  --arg latestURL "$latest_url" \
  --argjson size "$apk_size" \
  --arg sha256 "$apk_sha256" \
  --arg certificateSHA256 "$certificate_sha256" \
  '{schemaVersion: 1, applicationId: "ru.lumastack.lumawork", version: $version, versionCode: $versionCode, releaseDate: $date, localizedDescription: $notes, minAndroidVersion: "8.0", minSdk: 26, downloadURL: $url, latestDownloadURL: $latestURL, size: $size, sha256: $sha256, signingCertificateSHA256: $certificateSHA256}' \
  > "$metadata_path"
jq -e . "$metadata_path" >/dev/null

remote_stage="$REMOTE_STAGE_ROOT/$version_code"
install_command="set -e; site_stage='/home/deploy/app-lumastack-deploy/dist'; mkdir -p \"\$site_stage\"; find \"\$site_stage\" -mindepth 1 -delete; rsync -a --exclude='/releases/' '$REMOTE_ROOT/' \"\$site_stage/\"; install -m 644 '$remote_stage/$apk_name' \"\$site_stage/$apk_name\"; install -m 644 '$remote_stage/$apk_name' \"\$site_stage/LumaWork-Android-latest.apk\"; install -m 644 '$remote_stage/android-release.json' \"\$site_stage/android-release.json\"; sudo -n /usr/local/sbin/deploy-app-lumastack; find '$remote_stage' -type f -delete; rmdir '$remote_stage'"

if command -v ssh >/dev/null 2>&1 && command -v scp >/dev/null 2>&1 && ssh -o BatchMode=yes -o ConnectTimeout=5 "$SSH_HOST" true 2>/dev/null; then
  ssh "$SSH_HOST" "mkdir -p '$remote_stage' && find '$remote_stage' -type f -delete"
  scp -q "$apk_path" "$metadata_path" "$SSH_HOST:$remote_stage/"
  ssh "$SSH_HOST" "$install_command"
else
  mcp_call="$SCRIPT_DIR/mcp-call.sh"
  [[ -x "$mcp_call" ]] || { printf '%s\n' "Ошибка: SSH недоступен и MCP helper не найден." >&2; exit 1; }
  "$mcp_call" run_command "$(jq -nc --arg command "mkdir -p '$remote_stage' && find '$remote_stage' -type f -delete" '{command:$command}')" >/dev/null

  upload_with_mcp() {
    local source_path="$1"
    local destination_path="$2"
    # Lumastack MCP ограничивает shell-команду 4000 символами; base64 chunk должен быть меньше лимита.
    local chunk_size=2400
    local chunk_index=0
    local chunk=""
    "$mcp_call" run_command "$(jq -nc --arg command ": > '$destination_path'" '{command:$command}')" >/dev/null
    while chunk="$(dd if="$source_path" bs="$chunk_size" skip="$chunk_index" count=1 2>/dev/null | base64 | tr -d '\n')" && [[ -n "$chunk" ]]; do
      "$mcp_call" run_command "$(jq -nc --arg command "printf '%s' '$chunk' | base64 -d >> '$destination_path'" '{command:$command}')" >/dev/null
      chunk_index="$((chunk_index + 1))"
    done
    expected_size="$(stat -f '%z' "$source_path")"
    expected_sha256="$(shasum -a 256 "$source_path" | awk '{print $1}')"
    "$mcp_call" run_command "$(jq -nc --arg command "test \"\$(stat -c '%s' '$destination_path')\" = '$expected_size' && test \"\$(sha256sum '$destination_path' | awk '{print \$1}')\" = '$expected_sha256'" '{command:$command}')" >/dev/null
  }

  upload_with_mcp "$apk_path" "$remote_stage/$apk_name"
  upload_with_mcp "$metadata_path" "$remote_stage/android-release.json"
  "$mcp_call" run_command "$(jq -nc --arg command "$install_command" '{command:$command}')" >/dev/null
fi

curl -fsS "$METADATA_URL" | jq -e --arg version "$version" --argjson code "$version_code" --arg sha "$apk_sha256" \
  '.version == $version and .versionCode == $code and .sha256 == $sha' >/dev/null
downloaded_apk="$(mktemp -t lumawork-android.XXXXXX.apk)"
trap 'rm -f "$downloaded_apk"' EXIT
curl -fsS "$latest_url" -o "$downloaded_apk"
downloaded_sha256="$(shasum -a 256 "$downloaded_apk" | awk '{print $1}')"
[[ "$downloaded_sha256" == "$apk_sha256" ]] || { printf '%s\n' "Ошибка: SHA-256 загруженного APK не совпадает." >&2; exit 1; }

printf 'Опубликован LumaWork Android %s (%s)\n' "$version" "$version_code"
printf 'APK: %s\n' "$latest_url"
printf 'Метаданные: %s\n' "$METADATA_URL"
printf 'AAB для Play Console сохранён локально: %s\n' "$aab_source"
