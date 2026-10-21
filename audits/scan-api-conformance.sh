#!/usr/bin/env bash
# API-conformance scan instrument (#804 round-3 audit, S0 baseline #809).
#
# The grep/AST pass consumed by the per-provider audit sub-issues (S1-S26).
# Two modes:
#
#   scan-api-conformance.sh --signatures           # jar-side reference surface (javap ground truth)
#   scan-api-conformance.sh <ProviderDir> [...]    # provider-side grep pass (also: `shared`)
#
# Provider-side output line format (stable for quoting into issue comments):
#   [PASS|INFO|FINDING|WARN] <check-id> <file>:<line>: <detail>
# then a `SUMMARY <dir>: <pass> pass, <info> info, <finding> finding, <warn> warn` line.
#
# Semantics:
#   PASS     mechanical contract holds (quoted as evidence of conformance)
#   INFO     inventory item for the audit verdict (presence/absence to judge)
#   FINDING  mechanically-detected contract breach candidate (needs file:line evidence quote)
#   WARN     risky pattern; verdict is the auditor's, script only flags
#
# The script is grep-tier only; the AST tier is the javap reference surface
# (--signatures) from the bootstrapCloudstream classes.jar in mavenLocal.
# Bytecode ground truth is FINDINGS-767 + FINDINGS-804: the pinned jar sha
# 16278fc26dc9b6205b43d20cd3a0bbf5a51eb39c614a395354f484343115ccda.
#
# Usage contract per the S0 spec: run against `shared/` first (splice coupling,
# ADR-0002), then per provider; per-provider verdicts land on their own
# sub-issues, shared/ root-cause findings propose a shared/ fix with the
# affecting-provider list.

set -u

JAR="${HOME}/.m2/repository/com/lagradost/cloudstream3/pre-release/cloudstream3-pre-release.jar"

pass()  { printf '[PASS]    %s %s:%s: %s\n' "$1" "$2" "${3:-?}" "$4"; }
info()  { printf '[INFO]    %s %s:%s: %s\n' "$1" "$2" "${3:-?}" "$4"; }
findng(){ printf '[FINDING] %s %s:%s: %s\n' "$1" "$2" "${3:-?}" "$4"; }
warn()  { printf '[WARN]    %s %s:%s: %s\n' "$1" "$2" "${3:-?}" "$4"; }

# ---- jar-side reference surface (the "AST pass") ----
if [ "${1:-}" = "--signatures" ]; then
    [ -f "$JAR" ] || { echo "reference jar missing: $JAR (run ./gradlew bootstrapCloudstream)" >&2; exit 2; }
    echo "# Reference API surface from $(sha256sum "$JAR" | cut -d' ' -f1)"
    echo "## MainAPI override surface (fun-level)"
    javap -classpath "$JAR" com.lagradost.cloudstream3.MainAPI \
        | grep -E "getMainPage|search|load\(|loadLinks" | sed 's/^/  /'
    echo "## LoadResponse.Companion.add* (completeness surface)"
    javap -classpath "$JAR" 'com.lagradost.cloudstream3.LoadResponse$Companion' \
        | grep -E "add(Actors|Duration|Poster|Score|Trailer)|recommendations" | sed 's/^/  /'
    echo "## ExtractorLink / quality ground truth (FINDINGS-767)"
    javap -p -classpath "$JAR" com.lagradost.cloudstream3.utils.ExtractorLink \
        | grep -E "quality|class " | sed 's/^/  /'
    javap -p -classpath "$JAR" com.lagradost.cloudstream3.utils.Qualities \
        | grep -E "public static final|value" | sed 's/^/  /'
    javap -classpath "$JAR" com.lagradost.cloudstream3.utils.ExtractorApiKt \
        | grep -E "getQualityFromName" | sed 's/^/  /'
    echo "## DSL builders"
    unzip -l "$JAR" | grep -oE "com/lagradost/cloudstream3/[A-Za-z/]*(new[A-Za-z]+)\.class" \
        | grep -v '\$' | sort -u | sed 's/^/  /'
    exit 0
fi

[ $# -ge 1 ] || { echo "usage: $0 <ProviderDir> [...] | --signatures" >&2; exit 2; }

for dir in "$@"; do
    [ -d "$dir" ] || { echo "not a directory: $dir" >&2; continue; }
    gradle="${dir}/build.gradle.kts"
    name="$(basename "$dir")"

    # locate the main kotlin sources (provider root or shared layout)
    if [ -d "${dir}/src/main/kotlin" ]; then
        srcdir="${dir}/src/main/kotlin"
    else
        srcdir="$dir"   # shared/ — the kotlin tree IS the dir
    fi

    echo "===== SCAN ${name} (src=${srcdir}) ====="

    # --- G1 build metadata (#812 checklist item 6) ---
    if [ -f "$gradle" ]; then
        for key in version language status description iconUrl; do
            if grep -qE "^\s*${key}\s*=" "$gradle"; then
                pass  G1 "$gradle" 0 "build.gradle.kts: '${key}=' present"
            else
                findng G1 "$gradle" 0 "build.gradle.kts: '${key}=' MISSING"
            fi
        done
        if grep -qE 'tvTypes\s*=\s*listOf\("NSFW"\)' "$gradle"; then
            pass G1 "$gradle" 0 "tvTypes = listOf(\"NSFW\")"
        else
            findng G1 "$gradle" 0 "tvTypes is not exactly listOf(\"NSFW\")"
        fi
    else
        info G1 "$dir" 0 "no build.gradle.kts (shared/ splice source — G1 n/a)"
    fi

    # --- R1 registration (#812 checklist item 5) ---
    plugins=$(grep -rlE "BasePlugin\(\)" "$srcdir" 2>/dev/null | sort)
    nplugins=$(printf '%s' "$plugins" | grep -c . || true)
    if [ "$nplugins" -eq 0 ]; then
        info R1 "$srcdir" 0 "no plugin class here (shared/ splice source or registered elsewhere)"
    else
        for p in $plugins; do
            pass R1 "$p" "$(grep -nE 'BasePlugin\(\)' "$p" | head -1 | cut -d: -f1)" "plugin class"
            if grep -qE 'registerMainAPI\(' "$p"; then
                pass R1 "$p" "$(grep -nE 'registerMainAPI\(' "$p" | head -1 | cut -d: -f1)" "registerMainAPI(...) called"
            else
                findng R1 "$p" 0 "registerMainAPI(...) MISSING"
            fi
            if grep -qE 'registerHostExtractors\(' "$p"; then
                pass R1 "$p" "$(grep -nE 'registerHostExtractors\(' "$p" | head -1 | cut -d: -f1)" "registerHostExtractors() called (shared HostRegistry, ADR-0002)"
            else
                findng R1 "$p" 0 "registerHostExtractors() MISSING"
            fi
            if grep -qE 'registerExtractorAPI\(' "$p"; then
                warn R1 "$p" "$(grep -nE 'registerExtractorAPI\(' "$p" | head -1 | cut -d: -f1)" "registerExtractorAPI(...) — extractor must be a shared/ adapter, not a copied file"
            fi
        done
        # copied framework extractor files outside shared/
        copies=$(grep -rlnE ":\s*ExtractorApi\(\)" "$srcdir" 2>/dev/null || true)
        for c in $copies; do
            case "$c" in shared/*) ;; *) warn R1 "$c" "$(grep -nE ':\s*ExtractorApi\(\)' "$c" | head -1 | cut -d: -f1)" "local ExtractorApi subclass — must live in shared/ HostRegistry (ADR-0002)";; esac
        done
    fi

    # --- M1 MainAPI override surface (#812 checklist item 1) ---
    mains=$(grep -rlE ':\s*MainAPI\(\)' "$srcdir" 2>/dev/null || true)
    nmains=$(printf '%s' "$mains" | grep -c . || true)
    if [ "$nmains" -eq 0 ]; then
        info M1 "$srcdir" 0 "no MainAPI subclass (shared/ splice source)"
    else
        for m in $mains; do
            base="$(grep -nE ':\s*MainAPI\(\)' "$m" | head -1 | cut -d: -f1)"
            pass M1 "$m" "$base" "MainAPI subclass"
            for fn in search load loadLinks getMainPage; do
                if grep -qE "override\s+(suspend\s+)?fun\s+${fn}\b" "$m"; then
                    pass M1 "$m" "$(grep -nE "override\s+(suspend\s+)?fun\s+${fn}\b" "$m" | head -1 | cut -d: -f1)" "overrides ${fn}"
                else
                    info M1 "$m" 0 "no override of ${fn} (framework default applies)"
                fi
            done
        done
    fi

    # --- H1 homepage construction (#812 checklist item 2) ---
    hpr=$(grep -rnE 'HomePageResponse\(' "$srcdir" 2>/dev/null | grep -vE 'newHomePageResponse|HomePageResponse\b.*:|fun ' || true)
    if [ -n "$hpr" ]; then
        while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            warn H1 "$f" "$l" "raw HomePageResponse(...) constructor — prefer newHomePageResponse DSL (raw ctor bypasses hasNext handling)"
        done <<< "$hpr"
    else
        if grep -rqE 'newHomePageResponse|HomePageList\(' "$srcdir" 2>/dev/null; then
            pass H1 "$srcdir" 0 "homepage built via newHomePageResponse/HomePageList DSL"
        else
            info H1 "$srcdir" 0 "no homepage construction in this source set"
        fi
    fi

    # --- C1 constructor style (#812 checklist item 4) ---
    rawlink=$(grep -rnE '(^|[^w.])ExtractorLink\(' "$srcdir" 2>/dev/null | grep -vE 'newExtractorLink|List<ExtractorLink|extends|\bclass\b' || true)
    if [ -n "$rawlink" ]; then
        while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            warn C1 "$f" "$l" "raw ExtractorLink(...) constructor — repo norm is the newExtractorLink { } DSL"
        done <<< "$rawlink"
    fi
    rawep=$(grep -rnE '(^|[^w.])Episode\(' "$srcdir" 2>/dev/null | grep -vE 'newEpisode|List<Episode|extends|data class' || true)
    if [ -n "$rawep" ]; then
        while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            info C1 "$f" "$l" "raw Episode(...) constructor — DSL is newEpisode { } (NSFW providers commonly use raw; verdict is the auditor's)"
        done <<< "$rawep"
    fi

    # --- L1 LoadResponse completeness inventory (#812 checklist item 3) ---
    # import lines never count as usage; extension fns are called unqualified
    # inside load(), e.g. addActors(actresses) — match call-form, not just .fn
    usage_of() { grep -nE "(^|[^.[:alnum:]_])${1}" "$2" | grep -vE '^[0-9]+:\s*import' | head -1; }
    if [ "$nmains" -gt 0 ]; then
        for m in $mains; do
            for fn in addPoster addDuration addActors addScore addTrailer; do
                hit=$(usage_of "${fn}\s*\(" "$m")
                if [ -n "$hit" ]; then
                    info L1 "$m" "$(echo "$hit" | cut -d: -f1)" "${fn} used"
                else
                    info L1 "$m" 0 "${fn} NOT used (check: does the site provably expose it?)"
                fi
            done
            hit=$(grep -nE 'recommendations\s*=' "$m" | grep -vE '^[0-9]+:\s*import' | head -1)
            if [ -n "$hit" ]; then
                info L1 "$m" "$(echo "$hit" | cut -d: -f1)" "recommendations used"
            else
                info L1 "$m" 0 "recommendations NOT used (check: does the site provably expose it?)"
            fi
        done
    fi

    # --- Q1 quality mapping (FINDINGS-767 standard, #812 checklist item 7) ---
    enum=$(grep -rnE 'Qualities\.P[0-9]+\.value' "$srcdir" 2>/dev/null || true)
    if [ -n "$enum" ]; then
        printf '%s\n' "$enum" | while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            pass Q1 "$f" "$l" "enum-Int quality ($(echo "$hit" | grep -oE 'Qualities\.P[0-9]+\.value' | head -1))"
        done
    fi
    gqfn=$(grep -rnE 'getQualityFromName\(' "$srcdir" 2>/dev/null || true)
    if [ -n "$gqfn" ]; then
        printf '%s\n' "$gqfn" | while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            info Q1 "$f" "$l" "getQualityFromName call — VERIFY the label source is whole-number/whole-'4k' (suffix labels like '720p HD'/'1080p FHD' degrade to Qualities.Unknown=400, FINDINGS-767)"
        done
    fi
    rawq=$(grep -rnE '\.quality\s*=\s*[0-9]+' "$srcdir" 2>/dev/null || true)
    if [ -n "$rawq" ]; then
        printf '%s\n' "$rawq" | while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            n="$(echo "$hit" | grep -oE '=\s*[0-9]+' | grep -oE '[0-9]+' | head -1)"
            case "$n" in
                0|144|240|360|480|720|1080|1440|2160) info Q1 "$f" "$l" "raw int quality=${n} (valid resolution Int — app renders the tag)";;
                *) warn Q1 "$f" "$l" "raw int quality=${n} — 400 renders an EMPTY tag, 0 renders 'Auto' (FINDINGS-767); pass the enum Int";;
            esac
        done
    fi
    strq=$(grep -rnE '\.quality\s*=\s*[a-zA-Z_(]' "$srcdir" 2>/dev/null | grep -vE 'getQualityFromName|Qualities\.|\.value|\bthis\.quality = quality\b|toInt' || true)
    if [ -n "$strq" ]; then
        printf '%s\n' "$strq" | while IFS= read -r hit; do
            f="$(echo "$hit" | cut -d: -f1)"; l="$(echo "$hit" | cut -d: -f2)"
            if echo "$hit" | grep -qE 'qualityOf\s*\('; then
                pass Q1 "$f" "$l" "Parse-decided quality mapping (qualityOf — the #759/S1 decided-table idiom)"
            else
                warn Q1 "$f" "$l" "quality assigned from non-enum expression — trace the string; unconverted labels degrade to Unknown=400 (FINDINGS-767)"
            fi
        done
    fi
    if [ -z "$enum" ] && [ -z "$gqfn" ] && [ -z "$rawq" ] && [ -z "$strq" ]; then
        info Q1 "$srcdir" 0 "NO quality set anywhere — links default to quality=0 (app renders 'Auto', FINDINGS-767); verdict is the auditor's"
    fi

    echo "SUMMARY ${name}: see line counts above (grep-tier; verdicts are the auditor's)"
    echo
done
