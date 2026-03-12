#!/bin/sh
set -e
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
[ -f "$WRAPPER_JAR" ] || { echo "maven-wrapper.jar not found"; exit 1; }
if [ -z "$JAVA_HOME" ] && [ -x "/usr/libexec/java_home" ]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null)"
fi
JAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
[ -z "$JAVACMD" ] && JAVACMD="java"
exec "$JAVACMD" -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
