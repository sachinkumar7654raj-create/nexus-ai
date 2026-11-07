#!/bin/sh
# Minimal Gradle wrapper launcher (POSIX sh). Works on Linux, macOS and Termux.
app_path=$0
while [ -h "$app_path" ]; do
  ls=$(ls -ld "$app_path"); link=${ls#*' -> '}
  case $link in /*) app_path=$link ;; *) app_path=$(dirname "$app_path")/$link ;; esac
done
APP_HOME=$(cd "$(dirname "$app_path")" > /dev/null && pwd -P) || exit
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVACMD=$JAVA_HOME/bin/java; else JAVACMD=java; fi
if ! command -v "$JAVACMD" > /dev/null 2>&1; then
  echo "ERROR: Java not found. Install JDK 17 (Termux: pkg install openjdk-17)." >&2; exit 1
fi
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVACMD" ${JAVA_OPTS} ${GRADLE_OPTS} "-Dorg.gradle.appname=gradlew" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
