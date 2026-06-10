#!/usr/bin/env bash

set -euo pipefail

PROFILE="${1:-dev}"

case "$PROFILE" in
  dev|sit|prod) ;;
  *)
    echo "Usage: start.sh [dev|sit|prod] [jar-path]"
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
LOG_DIR="${PROJECT_DIR}/log"
APP_NAME="app-publish-service"
PID_FILE="${RUN_DIR}/${APP_NAME}-${PROFILE}.pid"
OUT_LOG="${LOG_DIR}/${APP_NAME}-${PROFILE}.out.log"

mkdir -p "${RUN_DIR}" "${LOG_DIR}"

if [[ -f "${PID_FILE}" ]]; then
  EXISTING_PID="$(tr -d '[:space:]' < "${PID_FILE}")"
  if [[ -n "${EXISTING_PID}" ]] && kill -0 "${EXISTING_PID}" 2>/dev/null; then
    echo "${APP_NAME} ${PROFILE} is already running. PID=${EXISTING_PID}"
    exit 0
  fi
  rm -f "${PID_FILE}"
fi

JAR_PATH="${2:-}"
if [[ -z "${JAR_PATH}" ]]; then
  shopt -s nullglob
  jars=("${PROJECT_DIR}/${APP_NAME}-"*"-${PROFILE}.jar")
  if [[ ${#jars[@]} -eq 0 ]]; then
    jars=("${PROJECT_DIR}/target/${APP_NAME}-"*"-${PROFILE}.jar")
  fi
  shopt -u nullglob
  if [[ ${#jars[@]} -eq 0 ]]; then
    echo "No runnable jar found for profile=${PROFILE} under ${PROJECT_DIR} or target/."
    echo "Please package the project first, for example:"
    echo "mvn -s settings.xml clean package -P${PROFILE} -DskipTests"
    exit 1
  fi
  JAR_PATH="${jars[0]}"
fi

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Jar file not found: ${JAR_PATH}"
  exit 1
fi

JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi

echo "Starting ${APP_NAME} with profile=${PROFILE}"
nohup "${JAVA_BIN}" ${JAVA_OPTS:-} -jar "${JAR_PATH}" --spring.profiles.active="${PROFILE}" ${APP_ARGS:-} >> "${OUT_LOG}" 2>&1 &
APP_PID=$!
echo "${APP_PID}" > "${PID_FILE}"

echo "Started. PID=${APP_PID}"
echo "Jar: ${JAR_PATH}"
echo "Log: ${OUT_LOG}"
