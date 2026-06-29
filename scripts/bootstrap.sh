#!/usr/bin/env bash
# Run once after cloning to generate gradle/wrapper/gradle-wrapper.jar
# (the binary jar is excluded from git).  Requires Java 17+ and internet.
set -e
GRADLE_VERSION=8.7
TMP=$(mktemp -d)
echo "Downloading Gradle ${GRADLE_VERSION}..."
curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "${TMP}/gradle.zip"
unzip -q "${TMP}/gradle.zip" -d "${TMP}"
"${TMP}/gradle-${GRADLE_VERSION}/bin/gradle" wrapper --gradle-version "${GRADLE_VERSION}"
chmod +x gradlew
rm -rf "${TMP}"
echo "Done. You can now use ./gradlew to build."
