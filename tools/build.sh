#!/bin/sh
# Builds the launcher inside the resume-android-sdk image (JDK 21 + Android SDK 36; built from
# ~/github/resume-android/tools/Dockerfile):
#   tools/build.sh                    debug APK
#   tools/build.sh assembleRelease    signed release APK -> release/x7-home.apk
#   tools/build.sh <gradle tasks>
# Signing: /docker/phone/secrets/signing.env + the keystore next to it (mounted read-only at /secrets).
set -e
cd "$(dirname "$0")/.."
TASKS=${*:-assembleDebug}
SECRETS=${X7_SECRETS:-/docker/phone/secrets}
mkdir -p .gradle-home release
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/work/.gradle-home/home -e GRADLE_USER_HOME=/work/.gradle-home \
  -e X7_SIGNING_ENV=/secrets/signing.env -v "$SECRETS":/secrets:ro \
  -v "$PWD":/work -w /work resume-android-sdk \
  sh -c "echo sdk.dir=/opt/android-sdk > local.properties && ./gradlew --no-daemon $TASKS"
case "$TASKS" in
  *assembleRelease*) cp app/build/outputs/apk/release/app-release.apk release/x7-home.apk && ls -l release/x7-home.apk ;;
esac
