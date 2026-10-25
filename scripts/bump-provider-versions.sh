#!/usr/bin/env bash
#
# Provider version bumper — affected provider list → incremented `version` lines
# (issue #921, epic #910; PR-time automation, see .github/workflows/version-bump.yml).
#
# CONTRACT (one stable output the workflow consumes):
#
#   input : provider names, one per line, on stdin — exactly the `subset` vocabulary
#           of scripts/affected-set.sh — or the single line `ALL` (every provider) or
#           `none` (no provider). `ALL` is the classifier's `full` verdict: `shared/**`
#           and the root Gradle files splice into every provider (ADR-0002).
#   output: repo-relative paths of the build files this run changed, one per line,
#           sorted. Nothing when no version moved (`none`, or every file already
#           carries this run's note).
#   exit  : 0 once bumped, 2 on a usage error (unknown provider, missing `--note`,
#            missing build file), 3 when a build file has no `version = <int>` line.
#
# The bump is deliberately idempotent: the note doubles as the bump marker, so a
# file that already carries it is left untouched. That is the second half of the
# loop guard — a re-triggered run (App-authenticated push) cannot double-bump even
# if the commit-message marker is lost. Re-running on a bumped tree is a no-op.
#
# Section 1 is the pure text transform, section 2 the filesystem adapters, section 3
# the CLI. No git here: the workflow owns diffing, committing and pushing.

set -uo pipefail

# ── 1. Pure text transform ────────────────────────────────────────────────────────
# bump_stream <note>
# Reads a provider build.gradle.kts on stdin, writes the bumped file to stdout.
# Touches the FIRST `version = <int>` line only, and only when the line does not
# already carry <note>: `version = 18 // #1318: ...` → `version = 19 // <note>`,
# matching the repo's `// #<issue>: <what changed>` style (AGENTS.md). Every other
# byte — leading comments, CRLF endings, unrelated `version` mentions — is preserved.
# Returns 3 when the stream carries no `version = <int>` line at all.
bump_stream() {
    local note=$1 line cr prefix number bumped=0
    while IFS= read -r line || [[ -n $line ]]; do
        cr=''
        [[ $line == *$'\r' ]] && cr=$'\r'
        if (( ! bumped )) && [[ $line =~ ^([[:space:]]*version[[:space:]]*=[[:space:]]*)([0-9]+)(.*)$ ]]; then
            bumped=1
            if [[ $line == *"$note"* ]]; then
                printf '%s\n' "$line"                      # already bumped by this run
            else
                prefix=${BASH_REMATCH[1]}
                # 10# : never let a leading zero turn the increment octal
                number=$(( 10#${BASH_REMATCH[2]} + 1 ))
                printf '%s%s // %s%s\n' "$prefix" "$number" "$note" "$cr"
            fi
            continue
        fi
        printf '%s\n' "$line"
    done
    (( bumped )) || return 3
    return 0
}

# ── 2. Thin adapters ─────────────────────────────────────────────────────────────
# Provider set = top-level directories carrying a build.gradle.kts (same rule as
# scripts/affected-set.sh and settings.gradle.kts — never a hard-coded list).
discover_providers() {
    local root=$1 dir
    for dir in "$root"/*/; do
        [[ -f $dir/build.gradle.kts ]] || continue
        dir=${dir%/}
        printf '%s\n' "${dir##*/}"
    done
}

# bump_provider <root> <provider> <note> <dry-run>
# Rewrites <root>/<provider>/build.gradle.kts in place (unless dry-run) and prints
# the path when the version actually moved.
bump_provider() {
    local root=$1 provider=$2 note=$3 dry_run=$4
    local file=$root/$provider/build.gradle.kts tmp rc=0

    [[ -f $file ]] || { printf 'bump-provider-versions: no such provider: %s\n' "$provider" >&2; return 2; }

    tmp=$(mktemp) || return 1
    bump_stream "$note" < "$file" > "$tmp"
    rc=$?
    if (( rc != 0 )); then
        rm -f "$tmp"
        printf 'bump-provider-versions: %s has no version = <int> line\n' "$provider/build.gradle.kts" >&2
        return "$rc"
    fi
    if cmp -s "$file" "$tmp"; then
        rm -f "$tmp"
        return 0
    fi
    if (( dry_run )); then
        rm -f "$tmp"
    else
        mv "$tmp" "$file"
    fi
    printf '%s/build.gradle.kts\n' "$provider"
}

# ── 3. CLI ───────────────────────────────────────────────────────────────────────
usage() {
    cat <<'EOF'
Usage: scripts/affected-set.sh ... | scripts/bump-provider-versions.sh --note '#123: version bump'
                                        [--repo-root DIR] [--dry-run]

Increments the integer `version = N` line of every listed provider's build.gradle.kts,
rewriting its trailing `// <issue>: <what changed>` comment to --note. Prints the
changed build files, one per line. Idempotent: a file already carrying --note is
left alone, so re-running never double-bumps.

stdin accepts the scripts/affected-set.sh vocabulary: provider names (one per line),
`ALL` (every provider — the classifier's `full` verdict), or `none` (no provider).

  --repo-root DIR   repository the providers live in (default: this script's parent)
  --note TEXT       comment appended to the bumped line, e.g. '#1331: version bump'
                    (required)
  --dry-run         report the files that would change without writing them
EOF
}

usage_error() {
    printf 'bump-provider-versions: %s\n' "$1" >&2
    usage >&2
    exit 2
}

main() {
    local script_dir default_root root note='' dry_run=0
    script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd) || exit 2
    default_root=$(dirname -- "$script_dir")
    root=$default_root

    while (( $# )); do
        case $1 in
            --repo-root) [[ $# -ge 2 ]] || usage_error "--repo-root needs a directory"
                         root=$2; shift 2 ;;
            --note)      [[ $# -ge 2 ]] || usage_error "--note needs a comment"
                         note=$2; shift 2 ;;
            --dry-run)   dry_run=1; shift ;;
            -h | --help) usage; exit 0 ;;
            *)           usage_error "unknown option: $1" ;;
        esac
    done

    [[ -n $note ]] || usage_error "--note is required (the bumped line must say why)"
    [[ -d $root ]] || usage_error "not a directory: $root"

    local -a requested=()
    local line
    while IFS= read -r line || [[ -n $line ]]; do
        line=${line%$'\r'}
        [[ -z ${line//[[:space:]]/} ]] && continue
        requested+=("$line")
    done

    # No stack-redirect games: a provider that disappears between discovery and the
    # write is a hard error, never a silently skipped bump.
    local -a providers=()
    if (( ${#requested[@]} == 0 )) || [[ ${requested[0]} == none ]]; then
        exit 0
    elif [[ ${requested[0]} == ALL ]]; then
        mapfile -t providers < <(discover_providers "$root")
    else
        providers=("${requested[@]}")
    fi

    local provider changed=() rc
    for provider in "${providers[@]}"; do
        local out
        out=$(bump_provider "$root" "$provider" "$note" "$dry_run" 2>&1)
        rc=$?
        if (( rc == 0 )); then
            [[ -n $out ]] && changed+=("$out")
        elif (( rc == 2 )); then
            printf '%s\n' "$out" >&2
            exit 2
        else
            printf '%s\n' "$out" >&2
            exit "$rc"
        fi
    done

    if (( ${#changed[@]} )); then
        printf '%s\n' "${changed[@]}" | LC_ALL=C sort
    fi
}

main "$@"
