#!/bin/sh
# Gradle Wrapper launcher (bootstraps gradle if gradle-wrapper.jar is absent)
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -Xmx1024m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "ERROR: Neither gradle/wrapper/gradle-wrapper.jar nor system gradle found." >&2
    echo "Run 'gradle wrapper --gradle-version 8.11.1' or build via GitHub Actions." >&2
    exit 1
fi
