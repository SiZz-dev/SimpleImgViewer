#!/bin/sh
set -eu
install_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
node_bin="$install_root/.tools/runtimes/node-24.22.1/node.exe"
if [ ! -x "$node_bin" ]; then node_bin="$install_root/.tools/runtimes/node-24.22.1/bin/node"; fi
if [ ! -x "$node_bin" ]; then node_bin=$(command -v node); fi
cd "$install_root"
exec "$node_bin" "$install_root/scripts/start.mjs" "$@"
