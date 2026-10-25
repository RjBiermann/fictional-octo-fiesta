#!/usr/bin/env bash
#
# Affected-set classifier — changed files → none | subset | full (issue #920, epic #910).
#
# CONTRACT (one stable output both downstream stories consume — build gating #919 and
# version bump #921; changing the vocabulary breaks both, so change them together):
#
#   input : repository-relative changed paths, one per line, on stdin
#   output: exactly one of
#             none          no change can affect a .cs3 artifact → skip the build
#             <Provider>    one provider name per line, sorted + deduplicated → build
#                           only those
#             ALL           every provider → full rebuild
#   exit  : 0 once classified, 2 on a usage error
#
# `full` wins over any subset match, and anything not positively recognized is `full`:
# the gate may over-trigger (a wasted build) but must never under-trigger (a stale or
# missing .cs3). Rejected paths are the ones proven not to reach an artifact.
#
# Section 1 is the pure classification — it reads nothing but stdin and the provider
# list handed to it. Section 2 holds the thin adapters (provider discovery from the
# tree, `git diff --name-only`), section 3 the CLI.

set -uo pipefail

# ── 1. Pure classification ────────────────────────────────────────────────────────
# Reverses into every provider (ADR-0002 shared-splice coupling) or is a build input
# for all of them: the Gradle root, wrapper and catalogs, the vendored plugin/jars,
# the registry, and the build workflow itself.
FULL_TOP_LEVEL=(shared gradle gradlelibs vendor)
FULL_ROOT_FILES=(
    build.gradle.kts   # also the bootstrapCloudstream task + plugin wiring
    settings.gradle.kts
    gradle.properties
    repo.json          # makePluginsJson input
    gradlew
    gradlew.bat
)
FULL_PATHS=(".github/workflows/build.yml")

# Version bumps (issue #921) pass --ignore-shared-tests: test sources and fixtures
# cannot reach a .cs3, so a test-only PR must not move every provider's version. The
# build gate never passes it, keeping its fail-closed `shared/** → ALL` verdict.
IGNORE_SHARED_TESTS=0

# Proven not to reach a built plugin: prose, audits, tooling, the pipeline's own docs.
IGNORED_TOP_LEVEL=(.agents .github .pi audits docs scripts skills)
IGNORED_ROOT_FILES=(.gitattributes .gitignore LICENSE skills-lock.json)

# in_providers <name> <provider>...
in_providers() {
    local needle=$1 name
    shift
    for name in "$@"; do
        [[ $name == "$needle" ]] && return 0
    done
    return 1
}

# classify_stream <provider>...
# Reads changed paths, prints the contract above. Pure: no git, no filesystem reads.
classify_stream() {
    local -a providers=("$@") affected=()
    local full=0 path top base candidate

    while IFS= read -r path || [[ -n $path ]]; do
        path=${path%$'\r'}                                # tolerate CRLF input
        [[ -z ${path//[[:space:]]/} ]] && continue        # blank line
        path=${path#./}
        if (( IGNORE_SHARED_TESTS )) && [[ $path == shared/src/test/* ]]; then
            continue                                       # test-only: no artifact
        fi
        top=${path%%/*}
        base=${path##*/}

        for candidate in "${FULL_PATHS[@]}"; do
            [[ $path == "$candidate" ]] && { full=1; continue 2; }
        done
        for candidate in "${FULL_TOP_LEVEL[@]}"; do
            [[ $top == "$candidate" ]] && { full=1; continue 2; }
        done
        if [[ $path != */* ]]; then
            for candidate in "${FULL_ROOT_FILES[@]}"; do
                [[ $path == "$candidate" ]] && { full=1; continue 2; }
            done
        fi

        case $base in *.md) continue ;; esac              # any *.md anywhere is prose
        for candidate in "${IGNORED_TOP_LEVEL[@]}"; do
            [[ $top == "$candidate" ]] && continue 2
        done
        if [[ $path != */* ]]; then
            for candidate in "${IGNORED_ROOT_FILES[@]}"; do
                [[ $path == "$candidate" ]] && continue 2
            done
        fi

        if [[ $path == */* ]] && in_providers "$top" "${providers[@]}"; then
            affected+=("$top")
            continue
        fi

        full=1                                            # unknown → fail closed
    done

    if (( full )); then
        printf 'ALL\n'
    elif (( ${#affected[@]} == 0 )); then
        printf 'none\n'
    else
        printf '%s\n' "${affected[@]}" | LC_ALL=C sort -u
    fi
}

# ── 2. Thin adapters ─────────────────────────────────────────────────────────────
# Provider set = top-level directories carrying a build.gradle.kts. settings.gradle.kts
# auto-includes exactly those, so the classifier never hard-codes a provider list.
discover_providers() {
    local root=$1 dir
    for dir in "$root"/*/; do
        [[ -f $dir/build.gradle.kts ]] || continue
        dir=${dir%/}
        printf '%s\n' "${dir##*/}"
    done
}

git_changed_paths() {
    git -C "$1" diff --name-only "$2"
}

# ── 3. CLI ───────────────────────────────────────────────────────────────────────
usage() {
    cat <<'EOF'
Usage: git diff --name-only BASE..HEAD | scripts/affected-set.sh [--repo-root DIR]
       scripts/affected-set.sh [--repo-root DIR] --diff REVISION_RANGE

Prints none | provider names (one per line) | ALL for the changed paths.

  --repo-root DIR   repository to classify against (default: this script's parent)
  --diff RANGE      read the changed paths from `git diff --name-only RANGE`
                    instead of stdin (e.g. origin/main...HEAD, BEFORE..SHA)
  --ignore-shared-tests
                    treat shared/src/test/** as not affecting an artifact (the
                    version-bump consumer only; the build gate never passes it)
EOF
}

usage_error() {
    printf 'affected-set: %s\n' "$1" >&2
    usage >&2
    exit 2
}

main() {
    local script_dir default_root root diff_range='' changed=''
    script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd) || exit 2
    default_root=$(dirname -- "$script_dir")
    root=$default_root

    while (( $# )); do
        case $1 in
            --repo-root) [[ $# -ge 2 ]] || usage_error "--repo-root needs a directory"
                         root=$2; shift 2 ;;
            --diff)      [[ $# -ge 2 ]] || usage_error "--diff needs a git revision range"
                         diff_range=$2; shift 2 ;;
            --ignore-shared-tests) IGNORE_SHARED_TESTS=1; shift ;;
            -h | --help) usage; exit 0 ;;
            *)           usage_error "unknown option: $1" ;;
        esac
    done

    [[ -d $root ]] || usage_error "not a directory: $root"

    local -a providers=()
    mapfile -t providers < <(discover_providers "$root")

    if [[ -n $diff_range ]]; then
        changed=$(git_changed_paths "$root" "$diff_range") ||
            usage_error "git diff --name-only $diff_range failed in $root"
        printf '%s\n' "$changed" | classify_stream "${providers[@]}"
    else
        classify_stream "${providers[@]}"
    fi
}

main "$@"
