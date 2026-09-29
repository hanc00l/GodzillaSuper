#!/bin/bash
# cli/tests/compat-golden.sh — CLI contract regression (offline-safe subset of v2 §5.2/§6).
#
# Usage: cli/tests/compat-golden.sh [path-to-gsl5-wrapper-or-jar]
#   default: cli/gsl5 wrapper (staged by build-cli.sh); falls back to java -jar target/gsl5.jar.
# Live-fire tests against a real target are out of scope here (set GSL5_LIVE=1 in a future rev).
#
# Checks:
#   1. version envelope is a single clean JSON line on stdout
#   2. task resolution matrix: default / --task / GSL5_TASK env / flag-beats-env
#   3. exit codes: 2 usage (unknown cmd, missing required flag) / 3 target failure
#   4. tools catalog: non-empty; `tools <name>` lists required flags
#   5. doctor passes
#   6. audit store lands in <task>/logs/operation_audit.db
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
GSL5_TARGET="${1:-$HERE/../gsl5}"
if [ ! -e "$GSL5_TARGET" ]; then
  GSL5_TARGET="java -jar $HERE/../../target/gsl5.jar"
fi
# run everything through the wrapper when possible (exercises chdir/task isolation)
case "$GSL5_TARGET" in
  java\ -jar*) RUN() { $GSL5_TARGET cli "$@"; } ;;
  *) RUN() { "$GSL5_TARGET" "$@"; } ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok  - $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL - $1"; }
need_python() { command -v python3 >/dev/null 2>&1; }

echo "== 1. version envelope =="
OUT="$(RUN --tasks-root "$WORK/tasks" version 2>/dev/null)"
if need_python && echo "$OUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d["ok"] is True and d["task"]=="default"' 2>/dev/null; then
  ok "version JSON single-line envelope (task=default)"
else
  echo "$OUT" | head -1; bad "version envelope"
fi

echo "== 2. task resolution matrix =="
T="$(RUN --tasks-root "$WORK/tasks" --task alpha version 2>/dev/null)"
if echo "$T" | grep -q '"task":"alpha"'; then ok "--task flag"; else bad "--task flag ($T)"; fi
T="$(GSL5_TASK=beta RUN --tasks-root "$WORK/tasks" version 2>/dev/null)"
if echo "$T" | grep -q '"task":"beta"'; then ok "GSL5_TASK env"; else bad "GSL5_TASK env ($T)"; fi
T="$(GSL5_TASK=beta RUN --tasks-root "$WORK/tasks" --task gamma version 2>/dev/null)"
if echo "$T" | grep -q '"task":"gamma"'; then ok "flag beats env"; else bad "flag beats env ($T)"; fi
RUN --tasks-root "$WORK/tasks" --task '../evil' version >/dev/null 2>&1
[ $? -eq 2 ] && [ ! -e "$WORK/evil" ] && ok "task name traversal rejected -> 2" || bad "task name traversal guard"

echo "== 3. exit codes =="
RUN --tasks-root "$WORK/tasks" no_such_command >/dev/null 2>&1
[ $? -eq 2 ] && ok "unknown command -> 2" || bad "unknown command exit code"
RUN --tasks-root "$WORK/tasks" shell_exec --shellId 1 >/dev/null 2>&1
[ $? -eq 2 ] && ok "missing required flag -> 2" || bad "missing required flag exit code"
RUN --tasks-root "$WORK/tasks" call no_such_tool --json '{}' 2>/dev/null | grep -q '"tool":"no_such_tool"'
[ $? -eq 0 ] && ok "unknown tool reports its own name" || bad "call tool-name parsing"
RUN --tasks-root "$WORK/tasks" call no_such_tool --json '{}' >/dev/null 2>&1
[ $? -eq 3 ] && ok "unknown tool -> 3" || bad "unknown tool exit code"
T="$(RUN --tasks-root "$WORK/tasks" call shell_list --json '{}' 2>/dev/null)"
if echo "$T" | grep -q '"ok":true' && echo "$T" | grep -q '"tool":"shell_list"'; then
  ok "call passthrough success (shell_list)"
else
  bad "call passthrough ($T)"
fi
RUN --tasks-root "$WORK/tasks" shell_list >/dev/null 2>&1
[ $? -eq 0 ] && ok "shell_list on empty db -> 0" || bad "shell_list exit code"

echo "== 4. tools catalog =="
N="$(RUN --tasks-root "$WORK/tasks" tools 2>/dev/null | grep -c '^shell_\|^file_\|^db_\|^payload_list\|^c2profile_\|^settings_\|^process_list\|^net_info\|^oplog_query\|^shell_')"
[ "$N" -ge 20 ] && ok "tools catalog lists L2+ tools ($N lines)" || bad "tools catalog ($N lines)"
RUN --tasks-root "$WORK/tasks" tools shell_exec 2>/dev/null | grep -q -- "--shellId (required)" \
  && ok "tools <name> marks required flags" || bad "tools <name> required marks"

echo "== 5. doctor =="
RUN --tasks-root "$WORK/tasks" doctor >/dev/null 2>&1 && ok "doctor exit 0" || bad "doctor"

echo "== 6. audit store in task logs =="
if [ -f "$WORK/tasks/default/logs/operation_audit.db" ]; then
  ok "operation_audit.db under default task logs"
else
  bad "operation_audit.db missing (audit dir -Dgsl5.data.dir not applied?)"
fi

echo
echo "passed=$PASS failed=$FAIL"
[ $FAIL -eq 0 ]
