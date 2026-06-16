#!/usr/bin/env bash

set -euo pipefail

DEFAULT_PROFILE="__DEFAULT_PROFILE__"
PROFILE_PLACEHOLDER="__DEFAULT"
PROFILE_PLACEHOLDER+="_PROFILE__"
if [[ "${DEFAULT_PROFILE}" == "${PROFILE_PLACEHOLDER}" ]]; then
  DEFAULT_PROFILE="dev"
fi
PROFILE="${1:-${DEFAULT_PROFILE}}"

case "$PROFILE" in
  dev|sit|prod) ;;
  *)
    echo "Usage: logs.sh [dev|sit|prod]"
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIRECT_PARENT="$(cd "${SCRIPT_DIR}/.." && pwd)"
if [[ -d "${DIRECT_PARENT}/script" ]]; then
  PROJECT_DIR="${DIRECT_PARENT}"
else
  PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
fi

APP_NAME="app-publish-service"
LOG_DIR="${PROJECT_DIR}/log"
OUT_LOG="${LOG_DIR}/${APP_NAME}-${PROFILE}.out.log"

mkdir -p "${LOG_DIR}"
touch "${OUT_LOG}"

echo "Following ${OUT_LOG}"
echo "Press Ctrl+C to exit log following."
exec tail -n 200 -F "${OUT_LOG}"
