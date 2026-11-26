#!/usr/bin/env bash
# Self-check for failure-issue.sh's three contracts, each of which broke in production because
# nothing executed the agreement between the files that share it:
#   - dedup: the title IS the dedup key, so the same failing set appends instead of re-filing
#     (#1365 — the glob expected `]]`, the title ends with `]`).
#   - attribution: an unattributable failure (no provider named) is filed for a human and never
#     fires an implementation run (#1357) — label `triage/from-agent`, `created=false`.
#   - ownership: the title is read from every open issue in a public repo, so only issues this
#     lane owns may be deduped against, commented on, or closed. Two signals are asserted,
#     because each alone has a hole: allowlisted author identity (a third party can never match,
#     so a foreign same-title issue must not suppress `created=true` — the Fire trigger) and the
#     lane label (humans relabel, so a lane issue that lost its label must still dedup).
# The stub `gh` applies the real `--jq` to a JSON fixture, so the projection is exercised and a
# broken query fails here instead of silently emptying the map in production.
set -euo pipefail
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT
CLKDIR=$STUB/bin
mkdir -p "$CLKDIR"

cat > "$CLKDIR/gh" <<'EOF'
#!/usr/bin/env bash
echo "gh $*" >> "$STUB_CALLS"
case "$1 $2" in
  "issue list")
    jqarg=""
    prev=""
    for a in "$@"; do [[ $prev == --jq ]] && jqarg=$a; prev=$a; done
    if [[ -f $STUB_LIST_FAILS ]]; then echo "gh: HTTP 502 Bad Gateway" >&2; exit 1; fi
    # real projection, real fixture: the query this writer ships is the query under test
    jq -r "$jqarg" < "$STUB_ISSUES_JSON"
    ;;
  "issue create") echo "https://github.com/o/r/issues/9001" ;;
esac
EOF
chmod +x "$CLKDIR/gh"
export PATH="$CLKDIR:$PATH"
export STUB_CALLS="$STUB/calls" STUB_ISSUES_JSON="$STUB/issues.json" STUB_LIST_FAILS="$STUB/list-fails"
export GH_TOKEN=stub GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=1 KIND=load

# issue <number> <title> <author-login|""> [label...] — an empty login models a deleted
# account (`author.login` null), which must not collapse into the labels column.
issue() {
  local n=$1 t=$2 a=$3
  shift 3
  local labels='[]'
  (( $# )) && labels=$(printf '%s\n' "$@" | jq -R . | jq -sc .)
  jq -cn --argjson n "$n" --arg t "$t" --arg a "$a" --argjson l "$labels" \
    '{number:$n,title:$t,labels:($l|map({name:.})),
      author:{login:(if $a == "" then null else $a end)}}'
}

# run_writer <plugins-file-content> <issues-json|""> [--unattributable]
run_writer() {
  printf '%s' "$1" > "$STUB/plugins.txt"
  printf '%s' "${2:-[]}" > "$STUB_ISSUES_JSON"
  printf 'log line\n' > "$STUB/log.txt"
  : > "$STUB_CALLS"; : > "$STUB/out"
  shift 2
  GITHUB_OUTPUT="$STUB/out" "$DIR/failure-issue.sh" "$@" \
    --plugins "$STUB/plugins.txt" --log "$STUB/log.txt" >/dev/null
}
fail() { echo "FAIL: $*" >&2; exit 1; }

SAME='Load check (load): [Javseen MissAV]'

# 1. Fresh attributable set → ai-fix + created=true (the Fire trigger).
run_writer 'Javseen
MissAV
' ''
grep -qF -- "--title $SAME" "$STUB_CALLS" || fail "title is not the sorted set"
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
' "[$(issue 4242 "$SAME" app/github-actions ai-fix)]"
grep -q 'issue create' "$STUB_CALLS" && fail "recurring set must not create a second issue (#1365)"
grep -q 'issue comment 4242' "$STUB_CALLS" || fail "recurring set must append evidence to #4242"

# 4. A changed set supersedes the old issue (pointer + close), and never closes a
#    human-owned unattributable issue.
run_writer 'PerverZija
' "[$(issue 4242 "$SAME" app/github-actions ai-fix),$(issue 4243 'Load check (load): [unattributable]' app/github-actions triage/from-agent)]"
grep -q 'issue close 4242' "$STUB_CALLS" || fail "changed set must close the superseded issue"
grep -q 'issue close 4243' "$STUB_CALLS" && fail "the unattributable issue is human-owned, never auto-closed"

# 5. Ownership: a third party can file an issue with the exact dedup title. It must neither
#    swallow the evidence comment nor suppress created=true, or the self-healing dispatch is
#    silenced and a stranger's issue gets written to.
run_writer 'Javseen
MissAV
' "[$(issue 4242 "$SAME" RjBiermann)]"
grep -q 'issue create .*--label ai-fix' "$STUB_CALLS" || fail "foreign same-title issue must not replace a fresh ai-fix issue"
grep -qx 'created=true' "$STUB/out" || fail "foreign same-title issue must not suppress Fire (created=true)"
grep -q 'issue comment 4242' "$STUB_CALLS" && fail "never comment on an issue this lane does not own"

# 6. Ownership on the supersede path: a foreign issue carrying an old set title is not closed.
run_writer 'PerverZija
' "[$(issue 4242 "$SAME" RjBiermann bug)]"
grep -q 'issue close 4242' "$STUB_CALLS" && fail "must not close a foreign superseded-looking issue"

# 7. The relabel hole: this lane created the issue, a human then removed its label. The
#    author signal must still dedup it, or every later run files another copy (#1365's
#    symptom from a different cause) and the issue gains no evidence.
run_writer 'Javseen
MissAV
' "[$(issue 4242 "$SAME" app/github-actions)]"
grep -q 'issue create' "$STUB_CALLS" && fail "a relabelled lane issue must still dedup, not be duplicated"
grep -q 'issue comment 4242' "$STUB_CALLS" || fail "a relabelled lane issue must still receive evidence"

# 8. …but "is a bot" is not "is this lane": a different app's same-title issue is foreign.
run_writer 'Javseen
MissAV
' "[$(issue 4242 "$SAME" app/some-third-party-app)]"
grep -qx 'created=true' "$STUB/out" || fail "an unrelated app must not suppress Fire"
grep -q 'issue comment 4242' "$STUB_CALLS" && fail "never comment on an unrelated app's issue"

# 9. Fail closed: if the listing fails, the writer must abort rather than treat the empty
#    result as "no existing issue" (which re-files and re-fires on every run).
: > "$STUB_LIST_FAILS"; : > "$STUB_CALLS"
if GITHUB_OUTPUT="$STUB/out" "$DIR/failure-issue.sh" --plugins "$STUB/plugins.txt" \
     --log "$STUB/log.txt" >/dev/null 2>&1; then
  fail "a failed issue listing must abort, not file a duplicate"
fi
grep -q 'issue create' "$STUB_CALLS" && fail "must not create an issue when the listing failed"
rm -f "$STUB_LIST_FAILS"

# 10. A null author (deleted account) must not collapse the labels column: with the lane
#     label present the issue is still ours (dedup), and with no label it is foreign. Both
#     directions matter — the first is what an empty middle field would break.
run_writer 'Javseen
MissAV
' "[$(issue 4242 "$SAME" "" ai-fix)]"
grep -q 'issue create' "$STUB_CALLS" && fail "a null-author issue carrying the lane label must still dedup (field collapse)"
grep -q 'issue comment 4242' "$STUB_CALLS" || fail "a null-author lane-labelled issue must still receive evidence"
run_writer 'Javseen
MissAV
' "[$(issue 4242 "$SAME" "")]"
grep -qx 'created=true' "$STUB/out" || fail "a null-author issue without lane labels must not suppress Fire"
grep -q 'issue comment 4242' "$STUB_CALLS" && fail "never comment on an unowned issue"

# 11. A stall names its plugins. The classification stays human-owned (a plugin with no
#     verdict is not a provider verdict, #1357/#1545) and the dedup title stays the class
#     token, but the names must reach the record: `FreePornVideos` stalled on two consecutive
#     runs while 26/27 loaded, and the filed issue said only "unattributable" — the one
#     actionable fact was thrown away (#1608).
run_writer 'FreePornVideos
' '' --unattributable
grep -qF -- '--title Load check (load): [unattributable]' "$STUB_CALLS" \
  || fail "a stalled set must keep the class dedup title"
grep -q -- '--label triage/from-agent' "$STUB_CALLS" || fail "a stall must be triage/from-agent"
grep -qx 'created=false' "$STUB/out" || fail "a stall must not fire (created=false)"
grep -q 'FreePornVideos' "$STUB_CALLS" || fail "the stalled plugin names must reach the filed issue"

# 12. …and the recurring-stall evidence comment names them too, or a repeat is invisible.
run_writer 'FreePornVideos
' "[$(issue 4243 'Load check (load): [unattributable]' app/github-actions triage/from-agent)]" --unattributable
grep -q 'issue comment 4243' "$STUB_CALLS" || fail "a recurring stall must append evidence to the human issue"
grep -q 'FreePornVideos` (no verdict)' "$STUB_CALLS" \
  || fail "recurring-stall evidence must name the stalled plugins, qualified as no-verdict"

# 13. Blank set → nothing to file (the workflow must never pass one: the evidence files are
#     written unconditionally, so "no stall" is a bare newline, not an empty file).
: > "$STUB_CALLS"
printf '\n' > "$STUB/plugins.txt"
printf '[]' > "$STUB_ISSUES_JSON"
printf 'log line\n' > "$STUB/log.txt"
GITHUB_OUTPUT="$STUB/out" "$DIR/failure-issue.sh" --unattributable \
  --plugins "$STUB/plugins.txt" --log "$STUB/log.txt" >/dev/null \
  || fail "a blank plugin set must exit 0, not fail the step"
grep -q 'issue create' "$STUB_CALLS" && fail "a blank plugin set must not create an issue"
grep -q 'issue comment' "$STUB_CALLS" && fail "a blank plugin set must not comment"

# 14. The created body must not let the closing sentence become a lazy continuation of the last
#     stalled bullet — GFM then renders "- FreePornVideos Daily runs append …" (06-review round 3).
run_writer 'FreePornVideos
' '' --unattributable
bullet=$(grep -n '^- `FreePornVideos`' "$STUB_CALLS" | head -1 | cut -d: -f1)
[[ -n $bullet ]] || fail "the created body must list the stalled plugin"
[[ -z $(sed -n "$((bullet + 1))p" "$STUB_CALLS") ]] \
  || fail "the stalled bullet must be followed by a blank line"

# 15. The bare-placeholder path (boot/SDK/install abort) must not point at a no-verdict list it
#     does not print.
run_writer 'unattributable
' '' --unattributable
grep -q 'see the no-verdict list' "$STUB_CALLS" && fail "the placeholder body must not reference an absent list"
grep -q 'Plugins with no verdict' "$STUB_CALLS" && fail "the placeholder body must not invent a no-verdict block"

echo "failure-issue.sh tests PASSED (dedup + attribution + ownership contracts)"
