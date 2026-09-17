#!/usr/bin/env sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar_path="$project_root/lib/flowtrail.jar"
if [ ! -f "$jar_path" ]; then
  jar_path="$project_root/target/flowtrail.jar"
fi
if [ ! -f "$jar_path" ]; then
  echo 'Missing flowtrail.jar. Run mvn clean verify from the project directory first.' >&2
  exit 1
fi
java_command=java
if [ -n "${JAVA_HOME:-}" ]; then
  java_command="$JAVA_HOME/bin/java"
fi
exec "$java_command" -Dfile.encoding=UTF-8 -jar "$jar_path" "$@"
