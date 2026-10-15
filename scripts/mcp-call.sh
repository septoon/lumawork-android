#!/usr/bin/env bash
set -Eeuo pipefail

METHOD="${1:?method required}"
PARAMS="${2-}"
if [[ -z "$PARAMS" ]]; then PARAMS='{}'; fi
CONFIG_PATH="${CODEX_CONFIG_PATH:-$HOME/.codex/config.toml}"
MCP_URL="${LUMASTACK_MCP_URL:-}"

if [[ -z "$MCP_URL" && -f "$CONFIG_PATH" ]]; then
  MCP_URL="$(awk -F'"' '
    /^\[mcp_servers\.lumastack\]$/ { inside = 1; next }
    /^\[/ { inside = 0 }
    inside && /^url = / { print $2; exit }
  ' "$CONFIG_PATH")"
fi

[[ -n "$MCP_URL" ]] || { printf '%s\n' "Lumastack MCP URL не найден." >&2; exit 1; }
jq -e . >/dev/null <<<"$PARAMS"

request_id="$(date +%s)"
payload="$(jq -nc --argjson id "$request_id" --arg method "$METHOD" --argjson params "$PARAMS" '{jsonrpc:"2.0",id:$id,method:"tools/call",params:{name:$method,arguments:$params}}')"
response="$(curl -fsS -H 'Accept: application/json, text/event-stream' -H 'Content-Type: application/json' --data "$payload" "$MCP_URL")"

if [[ "$response" == data:* ]]; then
  response="$(sed -n 's/^data: //p' <<<"$response" | tail -1)"
fi

jq -e --argjson id "$request_id" '.id == $id and (.error == null) and (.result.isError != true)' >/dev/null <<<"$response" || {
  jq -r '.error.message // .result.content[0].text // "Ошибка Lumastack MCP"' <<<"$response" >&2
  exit 1
}
jq -r '.result.content[]? | select(.type == "text") | .text' <<<"$response"
