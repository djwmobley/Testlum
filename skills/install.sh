#!/bin/sh
# Copies the Testlum skills to Claude Code (~/.claude/skills) and Codex (~/.agents/skills).
#   install.sh                 install the skills
#   install.sh --codex-mcp     also add the testlum server to ~/.codex/config.toml if it is not there
#   install.sh --remove-old    also remove the old single "testlum" skill
set -eu
here=$(cd "$(dirname "$0")" && pwd)
launcher="$here/../mcp/launch/start-testlum-mcp.sh"

for dest in "$HOME/.claude/skills" "$HOME/.agents/skills"; do
  mkdir -p "$dest"
  for s in "$here"/testlum-*; do
    [ -d "$s" ] || continue
    rm -rf "$dest/$(basename "$s")"
    cp -R "$s" "$dest/"
  done
  echo "installed to $dest"
done

for arg in "$@"; do
  case "$arg" in
    --remove-old)
      rm -rf "$HOME/.claude/skills/testlum" "$HOME/.agents/skills/testlum"
      echo "removed the old testlum skill" ;;
    --codex-mcp)
      cfg="$HOME/.codex/config.toml"
      mkdir -p "$HOME/.codex"
      if grep -q '^\[mcp_servers.testlum\]' "$cfg" 2>/dev/null; then
        echo "codex config already has testlum"
      else
        printf '\n[mcp_servers.testlum]\ncommand = "sh"\nargs = ["%s"]\nstartup_timeout_sec = 120\ntool_timeout_sec = 600\n' "$launcher" >> "$cfg"
        echo "added testlum to $cfg"
      fi ;;
  esac
done
