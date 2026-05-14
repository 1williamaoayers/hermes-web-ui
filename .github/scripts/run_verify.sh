#!/usr/bin/env bash
set -u
cd "$GITHUB_WORKSPACE"

mkdir -p verify-artifacts
export ARTIFACTS_DIR="$GITHUB_WORKSPACE/verify-artifacts"
export TEST_URL="${TEST_URL:-http://10.0.2.2:8648/?autotest=1}"
export MOCK_LOG="${MOCK_LOG:-$GITHUB_WORKSPACE/mock-hermes.log}"

adb logcat -c || true

APK_FILE=$(ls -1 "$GITHUB_WORKSPACE"/HermesApp-v*-x86_64.apk 2>/dev/null | head -1)
if [ -z "$APK_FILE" ]; then
  APK_FILE=$(ls -1 "$GITHUB_WORKSPACE"/HermesApp-v*.apk 2>/dev/null | head -1)
fi
echo "Using APK: $APK_FILE"

if [ -z "$APK_FILE" ] || [ ! -f "$APK_FILE" ]; then
  echo "::error::APK file not found"
  echo "apk-missing" > "$GITHUB_WORKSPACE/verify-status"
  exit 0
fi

python3 .github/scripts/verify.py "$APK_FILE"
RC=$?
if [ "$RC" != "0" ]; then
  echo "verify-failed-rc=$RC" > "$GITHUB_WORKSPACE/verify-status"
fi
exit 0
