#!/usr/bin/env bash
# Self-check for failure-issue.sh's two contracts, both of which broke in production because
# nothing executed them:
#   - dedup: the title IS the dedup key, so the same failing set appends instead of re-filing
#     (#1365 — the glob expected `]]`, the title ends with `]`).
#   - attribution: an unattributable failure (no provider named) is filed for a human and never
#     fires an implementation run (#1357) — label `triage/from-agent`, `created=false`.
#   - ownership: the title is read from every open issue in a public repo, so only issues this
#     lane created may be deduped against, commented on, or closed; a foreign same-title issue
#     must not suppress `created=true` (the Fire trigger).
# Stub `gh` on PATH; no network, no token.
set -euo pipefail
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
STUB=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$STUB" "$LOG"' EXIT
CLKDIR=$STUB/bin
mkdir -p "$CLKDIR"

cat > "$CLKDIR/gh" <<'EOF'
#!/usr/bin/env bash
# Records every call; `issue list` answers from the fixture the test staged.
echo "gh $*" >> "$STUB_CALLS"
case "$1 $2" in
  "issue list") cat "$STUB_OPEN" ;;
  "issue create") echo "https://github.com/o/r/issues/9001" ;;
esac
EOF
chmod +x "$CLKDIR/gh"
export PATH="$CLKDIR:$PATH" STUB_CALLS="$STUB/calls" STUB_OPEN="$STUB/open.tsv"
export GH_TOKEN=stub GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=1 KIND=load

# run_writer <plugins-file-content> <open-issues-tsv> [--unattributable]
run_writer() {
  printf '%s' "$1" > "$STUB/plugins.txt"
  printf '%s' "$2" > "$STUB_OPEN"
  printf 'log line\n' > "$STUB/log.txt"
  : > "$STUB_CALLS"; : > "$STUB/out"
  shift 2
  GITHUB_OUTPUT="$STUB/out" "$DIR/failure-issue.sh" "$@" \
    --plugins "$STUB/plugins.txt" --log "$STUB/log.txt" >/dev/null
}
fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. Fresh attributable set → ai-fix + created=true (the Fire trigger).
run_writer 'Javseen
MissAV
' ''
grep -q -- '--title Load check (load): \[Javseen MissAV\]' "$STUB_CALLS" || fail "title is not the sorted set"
grep -q -- '--label ai-fix' "$STUB_CALLS" || fail "attributable set must carry ai-fix"
grep -qx 'created=true' "$STUB/out" || fail "attributable set must fire (created=true)"

# 2. Unattributable → human-owned: no ai-fix, no Fire trigger.
run_writer 'unattributable
' '' --unattributable
grep -q -- '--label triage/from-agent' "$STUB_CALLS" || fail "unattributable must be triage/from-agent"
grep -q -- '--label ai-fix' "$STUB_CALLS" && fail "unattributable must never carry ai-fix"
grep -qx 'created=false' "$STUB/out" || fail "unattributable must not fire (created=false)"

# 3. #1365 regression: the SAME set already open → comment on it, never create a second issue.
run_writer 'Javseen
MissAV
' '4242	Load check (load): [Javseen MissAV]	ai-fix
'
grep -q 'issue create' "$STUB_CALLS" && fail "recurring set must not create a second issue (#1365)"
grep -q 'issue comment 4242' "$STUB_CALLS" || fail "recurring set must append evidence to #4242"

# 4. A changed set supersedes the old issue (pointer + close), and never closes a
#    human-owned unattributable issue.
run_writer 'PerverZija
' '4242	Load check (load): [Javseen MissAV]	ai-fix
4243	Load check (load): [unattributable]	triage/from-agent
'
grep -q 'issue close 4242' "$STUB_CALLS" || fail "changed set must close the superseded issue"
grep -q 'issue close 4243' "$STUB_CALLS" && fail "the unattributable issue is human-owned, never auto-closed"

# 5. Ownership: a third party can file an issue with the exact dedup title (labels are
#    maintainer-applied, and this lane always adds `ai-fix`/`triage/from-agent`). Such an
#    issue must neither swallow the evidence comment nor suppress created=true, or the
#    self-healing dispatch is silently silenced and a stranger's issue gets written to.
run_writer 'Javseen
MissAV
' '4242	Load check (load): [Javseen MissAV]	
'
grep -q 'issue create .*--label ai-fix' "$STUB_CALLS" || fail "foreign same-title issue must not replace a fresh ai-fix issue"
grep -qx 'created=true' "$STUB/out" || fail "foreign same-title issue must not suppress Fire (created=true)"
grep -q 'issue comment 4242' "$STUB_CALLS" && fail "never comment on an issue this lane does not own"
grep -q 'issue close 4242' "$STUB_CALLS" && fail "never close an issue this lane does not own"

# 6. Ownership on the supersede path: a foreign issue carrying an old set title is not
#    closed when the lane files the new set.
run_writer 'PerverZija
' '4242	Load check (load): [Javseen MissAV]	bug
'
grep -q 'issue close 4242' "$STUB_CALLS" && fail "must not close a foreign superseded-looking issue"

echo "failure-issue.sh tests PASSED (dedup + attribution + ownership contracts)"
