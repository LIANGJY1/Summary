#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/jbr-selection.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make_jbr() {
  local root="$1"
  mkdir -p "$root/bin" "$root/lib"
  touch "$root/bin/java" "$root/lib/libjli.so"
  chmod +x "$root/bin/java"
  printf 'spotLocation\n' > "$root/lib/libawt_xawt.so"
}

assert_equals() {
  local expected="$1" actual="$2" label="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: %s: expected <%s>, got <%s>\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

home="$tmp/home"
installed="$tmp/platform/jbr"
fallback="$home/.jdks/jbr-21.0.11"
make_jbr "$fallback"

selected="$(atlas_resolve_jbr "" "$home" "$installed")"
assert_equals "$fallback" "$selected" "find patched user JBR when platform path is absent"

override="$tmp/custom-jbr"
make_jbr "$override"
selected="$(atlas_resolve_jbr "$override" "$home" "$installed")"
assert_equals "$override" "$selected" "honor explicit ATLAS_JBR"

if atlas_resolve_jbr "$tmp/missing-jbr" "$home" "$installed" >/dev/null 2>&1; then
  printf 'FAIL: invalid explicit ATLAS_JBR must not silently fall back\n' >&2
  exit 1
fi

corretto_home="$tmp/corretto-only"
mkdir -p "$corretto_home/.jdks/corretto-21.0.12.1"
make_jbr "$corretto_home/.jdks/corretto-21.0.12.1"
if atlas_resolve_jbr "" "$corretto_home" "$tmp/no-platform-jbr" >/dev/null 2>&1; then
  printf 'FAIL: Corretto without a JBR name must not be selected\n' >&2
  exit 1
fi

printf 'PASS: JBR selection and override behavior\n'
