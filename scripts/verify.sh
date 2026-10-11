#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
# Uses caller JAVA_HOME / ANDROID_HOME. No secrets, release signing or installation.
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug "$@"
