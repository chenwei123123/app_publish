#!/usr/bin/env bash

set -euo pipefail

DEFAULT_PROFILE="__DEFAULT_PROFILE__"
PROFILE_PLACEHOLDER="__DEFAULT"
PROFILE_PLACEHOLDER+="_PROFILE__"
if [[ "${DEFAULT_PROFILE}" == "${PROFILE_PLACEHOLDER}" ]]; then
  DEFAULT_PROFILE="dev"
fi
PROFILE_ARG="${1:-}"
PROFILE="${PROFILE_ARG:-${DEFAULT_PROFILE}}"

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
echo "Default profile: ${DEFAULT_PROFILE}"
if [[ -n "${PROFILE_ARG}" ]]; then
  echo "Effective profile: ${PROFILE} (from command argument)"
else
  echo "Effective profile: ${PROFILE} (using script default)"
fi
PACKAGED_DIR="${PROJECT_DIR}/target/app_publish"
FALLBACK_TARGET_DIR="${PROJECT_DIR}/target"
RUN_DIR="${PROJECT_DIR}/script/run"
LOG_DIR="${PROJECT_DIR}/log"
APP_NAME="app-publish-service"
PID_FILE="${RUN_DIR}/${APP_NAME}-${PROFILE}.pid"
OUT_LOG="${LOG_DIR}/${APP_NAME}-${PROFILE}.out.log"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-120}"
SUCCESS_PATTERN='Started .* in .* seconds|Started .*Application|Tomcat started on port|Netty started on port'
FAILURE_PATTERN='APPLICATION FAILED TO START|Error starting ApplicationContext|Web server failed to start|APPLICATION FAILED'

mkdir -p "${RUN_DIR}" "${LOG_DIR}"
touch "${OUT_LOG}"

if [[ -f "${PID_FILE}" ]]; then
  EXISTING_PID="$(tr -d '[:space:]' < "${PID_FILE}")"
  if [[ -n "${EXISTING_PID}" ]] && kill -0 "${EXISTING_PID}" 2>/dev/null; then
    echo "${APP_NAME} ${PROFILE} is already running. PID=${EXISTING_PID}"
    if [[ "${NO_TAIL:-0}" != "1" ]]; then
      echo "Following log: ${OUT_LOG}"
      exec "${SCRIPT_DIR}/logs.sh" "${PROFILE}"
    fi
    exit 0
  fi
  rm -f "${PID_FILE}"
fi

JAR_PATH="${2:-}"
if [[ -z "${JAR_PATH}" ]]; then
  JAR_PATH="$(
    for search_dir in "${PROJECT_DIR}" "${PACKAGED_DIR}" "${FALLBACK_TARGET_DIR}"; do
      [[ -d "${search_dir}" ]] || continue
      found="$(
        find "${search_dir}" -maxdepth 1 -type f -name "${APP_NAME}-*-${PROFILE}.jar" ! -name "*.original" -printf '%T@ %p\n' 2>/dev/null \
          | sort -nr \
          | head -n 1 \
          | cut -d' ' -f2- || true
      )"
      if [[ -n "${found}" ]]; then
        echo "${found}"
        break
      fi
    done
  )"
  if [[ -z "${JAR_PATH}" ]]; then
    echo "No runnable jar found for profile=${PROFILE} under ${PROJECT_DIR} or target/."
    echo "Searched directories:"
    echo "  ${PROJECT_DIR}"
    echo "  ${PACKAGED_DIR}"
    echo "  ${PROJECT_DIR}/target"
    echo "Candidate jars found under these directories:"
    CANDIDATES="$(
      find "${PROJECT_DIR}" "${PACKAGED_DIR}" "${PROJECT_DIR}/target" -maxdepth 1 -type f -name "${APP_NAME}-*.jar" ! -name "*.original" -print 2>/dev/null || true
    )"
    if [[ -z "${CANDIDATES}" ]]; then
      echo "  (none)"
    else
      while IFS= read -r candidate; do
        [[ -n "${candidate}" ]] && echo "  ${candidate}"
      done <<< "${CANDIDATES}"
    fi
    echo "Please package the project first, for example:"
    echo "mvn -s settings.xml clean package -P${PROFILE} -DskipTests"
    exit 1
  fi
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
SPRING_CONFIG_ARG=""
ACTIVE_CONFIG_DIR=""
if [[ -d "${PROJECT_DIR}/config" ]]; then
  ACTIVE_CONFIG_DIR="${PROJECT_DIR}/config"
else
  JAR_DIR="$(cd "$(dirname "${JAR_PATH}")" && pwd)"
  if [[ -d "${JAR_DIR}/config" ]]; then
    ACTIVE_CONFIG_DIR="${JAR_DIR}/config"
  elif [[ -d "${PACKAGED_DIR}/config" ]]; then
    ACTIVE_CONFIG_DIR="${PACKAGED_DIR}/config"
  fi
fi
if [[ -n "${ACTIVE_CONFIG_DIR}" ]]; then
  SPRING_CONFIG_ARG="--spring.config.additional-location=optional:file:${ACTIVE_CONFIG_DIR}/"
fi
echo "Using jar: ${JAR_PATH}"
if [[ -n "${ACTIVE_CONFIG_DIR}" ]]; then
  echo "Config directory: ${ACTIVE_CONFIG_DIR}"
  [[ -f "${ACTIVE_CONFIG_DIR}/application.yml" ]] && echo "  Base config: ${ACTIVE_CONFIG_DIR}/application.yml"
  if [[ -f "${ACTIVE_CONFIG_DIR}/application-${PROFILE}.yml" ]]; then
    echo "  Profile config: ${ACTIVE_CONFIG_DIR}/application-${PROFILE}.yml"
  else
    echo "  Profile config: ${ACTIVE_CONFIG_DIR}/application-${PROFILE}.yml (not found)"
  fi
else
  echo "Config directory: (not set, Spring will use jar-internal configuration)"
fi
nohup "${JAVA_BIN}" ${JAVA_OPTS:-} -jar "${JAR_PATH}" --spring.profiles.active="${PROFILE}" ${SPRING_CONFIG_ARG} ${APP_ARGS:-} >> "${OUT_LOG}" 2>&1 &
APP_PID=$!
echo "${APP_PID}" > "${PID_FILE}"

echo "Started. PID=${APP_PID}"
echo "Log: ${OUT_LOG}"
echo "Waiting up to ${STARTUP_TIMEOUT}s for startup result..."

TAIL_PID=""
cleanup_tail() {
  if [[ -n "${TAIL_PID}" ]]; then
    kill "${TAIL_PID}" 2>/dev/null || true
    wait "${TAIL_PID}" 2>/dev/null || true
  fi
}
trap 'cleanup_tail; exit 0' INT TERM

if [[ "${NO_TAIL:-0}" != "1" ]]; then
  echo "Press Ctrl+C to stop log following without stopping the service."
  tail -n 0 -F "${OUT_LOG}" &
  TAIL_PID=$!
fi

STARTED=0
for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
  if ! kill -0 "${APP_PID}" 2>/dev/null; then
    cleanup_tail
    echo "Startup failed. Process exited. Check log: ${OUT_LOG}"
    exit 1
  fi

  if grep -Eq "${FAILURE_PATTERN}" "${OUT_LOG}"; then
    cleanup_tail
    echo "Startup failed. Failure markers detected in log: ${OUT_LOG}"
    exit 1
  fi

  if grep -Eq "${SUCCESS_PATTERN}" "${OUT_LOG}"; then
    STARTED=1
    break
  fi

  sleep 1
done

if [[ "${STARTED}" == "1" ]]; then
  echo "Startup succeeded. Success markers detected in log."
else
  echo "Startup succeeded. Process is still running after ${STARTUP_TIMEOUT}s."
fi

if [[ -n "${TAIL_PID}" ]]; then
  wait "${TAIL_PID}" || true
fi
