#!/usr/bin/env bash

atlas_jbr_is_usable() {
  local root="$1"
  [[ -x "$root/bin/java" && -f "$root/lib/libjli.so" && -f "$root/lib/libawt_xawt.so" ]] &&
    grep -qsF spotLocation "$root/lib/libawt_xawt.so"
}

# Prefer an explicit override, then Android Studio's platform JBR, then installed
# user JBRs. Corretto/OpenJDK are intentionally excluded because they lack XIM patch.
atlas_resolve_jbr() {
  local override="${1:-}"
  local home_dir="${2:-$HOME}"
  local platform_jbr="${3:-/opt/android-studio-for-platform/jbr}"
  local candidate

  if [[ -n "$override" ]]; then
    atlas_jbr_is_usable "$override" || return 1
    printf '%s\n' "$override"
    return 0
  fi

  local candidates=("$platform_jbr")
  shopt -s nullglob
  local user_jbrs=("$home_dir"/.jdks/jbr-*)
  shopt -u nullglob
  candidates+=("${user_jbrs[@]}")

  for candidate in "${candidates[@]}"; do
    if atlas_jbr_is_usable "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}
