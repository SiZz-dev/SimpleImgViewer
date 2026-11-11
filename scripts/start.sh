#!/bin/sh
set -eu
install_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
config_path="$install_root/config/imagegate.yml"
[ -f "$config_path" ] || { echo "Missing config/imagegate.yml" >&2; exit 1; }
set -- "$install_root"/lib/*.jar "$@"
jar=$1
shift
[ -f "$jar" ] || { echo "Missing application JAR" >&2; exit 1; }
# No additional JARs may be packaged in lib/.
for candidate in "$install_root"/lib/*.jar; do
  [ "$candidate" = "$jar" ] || { echo "Expected one application JAR" >&2; exit 1; }
done
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then java_bin="$JAVA_HOME/bin/java"; fi
cd "$install_root"
exec "$java_bin" -jar "$jar" --spring.profiles.active=prod "--spring.config.additional-location=file:$config_path" "$@"
