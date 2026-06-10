#!/usr/bin/env bash

set -euo pipefail

PROFILE="${1:-dev}"

case "$PROFILE" in
  dev|sit|prod) ;;
  *)
    echo "Usage: stop.sh [dev|sit|prod]"
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
RUN_DIR="${PROJECT_DIR}/script/run"
APP_NAME="app-publish-service"
PID_FILE="${RUN_DIR}/${APP_NAME}-${PROFILE}.pid"

if [[ ! -f "${PID_FILE}" ]]; then
  echo "No PID file found for profile=${PROFILE}."
  exit 0
fi

APP_PID="$(tr -d '[:space:]' < "${PID_FILE}")"
if [[ -z "${APP_PID}" ]]; then
  rm -f "${PID_FILE}"
  echo "PID file is empty and has been removed."
  exit 0
fi

if ! kill -0 "${APP_PID}" 2>/dev/null; then
  rm -f "${PID_FILE}"
  echo "Process ${APP_PID} is not running. Stale PID file removed."
  exit 0
fi

kill "${APP_PID}"

for _ in {1..30}; do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    rm -f "${PID_FILE}"
    echo "Stopped ${APP_NAME} ${PROFILE}. PID=${APP_PID}"
    exit 0
  fi
  sleep 1
done

kill -9 "${APP_PID}" 2>/dev/null || true
rm -f "${PID_FILE}"
echo "Force stopped ${APP_NAME} ${PROFILE}. PID=${APP_PID}"
