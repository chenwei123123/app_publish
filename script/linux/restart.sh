#!/usr/bin/env bash

set -euo pipefail

DEFAULT_PROFILE="__DEFAULT_PROFILE__"
PROFILE_PLACEHOLDER="__DEFAULT"
PROFILE_PLACEHOLDER+="_PROFILE__"
if [[ "${DEFAULT_PROFILE}" == "${PROFILE_PLACEHOLDER}" ]]; then
  DEFAULT_PROFILE="dev"
fi
PROFILE="${1:-${DEFAULT_PROFILE}}"
JAR_PATH="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${SCRIPT_DIR}/stop.sh" "${PROFILE}" || true
sleep 1
exec "${SCRIPT_DIR}/start.sh" "${PROFILE}" "${JAR_PATH}"
