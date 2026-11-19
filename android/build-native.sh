#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -d "$project_dir/../.tools/jdk/Contents/Home" ]]; then
  export JAVA_HOME="$project_dir/../.tools/jdk/Contents/Home"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$project_dir/../.tools/android" ]]; then
  export ANDROID_HOME="$project_dir/../.tools/android"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$project_dir/../.tools/gradle-home}"
keytool_bin="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if [[ ! -f signing/anitv.keystore ]]; then
  mkdir -p signing
  "$keytool_bin" -genkeypair -keystore signing/anitv.keystore \
    -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname 'CN=AniTV Local Build,O=Local Development'
  chmod 600 signing/anitv.keystore
fi
if [[ $# -eq 0 ]]; then set -- assembleRelease testDebugUnitTest lintRelease; fi
exec ./gradlew "$@" --no-daemon
