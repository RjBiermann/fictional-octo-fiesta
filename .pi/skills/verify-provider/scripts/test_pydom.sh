#!/usr/bin/env bash
# Offline parser fixtures — issue #509 (FINDINGS-509.md).
# Proves the two verify.sh false-FAIL mechanisms stay fixed without touching the network:
#   1. a KVS card whose inner HTML has nested <div>s + trailing <p class="inf"> must NOT
#      clip at the quality-icon div — the DEFAULT title (fixture 1, '-' title selector =
#      inner_text over the whole balanced card) contains the real title, and the CHAIN
#      title read (fixture 2, `p.inf a` as the provider's Kotlin reads it) equals it, and
#   2. duplicate detection must split within-page repeats (FAIL) from cross-page repeats
#      (--cross-page-dups-note bucket).
# Run with no args: bash test_pydom.sh. Exit 0 = all fixtures pass.
set -euo pipefail
cd "$(dirname "$0")"

PYDOM=$(mktemp /tmp/verify_pydom_XXXXXX.py)
awk '/^cat > "\$PYDOM" <<.PY.$/{f=1;next} f && /^PY$/{exit} f' verify.sh > "$PYDOM"
py() { python3 "$PYDOM" "$@"; }

# KVS card fixture: card div contains nested divs (quality icon) + trailing p.inf with real title
FIX=$(mktemp /tmp/kvs_fixture_XXXX.html)
cat > "$FIX" <<'HTML'
<div class="wall">
<div class="video-preview-screen video-item" data-item-id="776123">
  <a href="https://www.porntrex.com/video/776123/evil-angel-slut" class="thumb">
    <img class="cover lazyload" data-src="//ptx.cdntrex.com/contents/videos_screenshots/776000/776123/300x168/1.jpg" alt="Evil Angel Slut"/>
    <div class="quality-icons"><div class="hd-text-icon">1080p HD</div></div>
  </a>
  <p class="inf"><a href="https://www.porntrex.com/video/776123/evil-angel-slut">Evil Angel Slut Pounded Rough</a></p>
</div>
<div class="video-preview-screen video-item" data-item-id="776456">
  <a href="https://www.porntrex.com/video/776456/banged-tiny-teen" class="thumb">
    <img class="cover lazyload" data-src="//ptx.cdntrex.com/contents/videos_screenshots/776000/776456/300x168/2.jpg" alt="Banged Tiny Teen"/>
    <div class="quality-icons"><div class="hd-text-icon">720p</div></div>
  </a>
  <p class="inf"><a href="https://www.porntrex.com/video/776456/banged-tiny-teen">Banged Tiny Teen</a></p>
</div>
</div>
HTML

# 1. default title (inner_text over the whole balanced card) contains the real title,
#    not just the quality label — the old clip produced "1080p HD" only.
t=$(py cards 'div.video-item' - - "$FIX" | head -1 | cut -f2)
[[ "$t" == *"Evil Angel Slut"* ]] || { echo "FAIL: card title clipped: '$t'" >&2; exit 1; }

# 2. chain title selector `p.inf a` resolves to the real title (the old code reduced
#    chains to their last part and matched the thumb <a>, printing "1080p HD").
t=$(py cards 'div.video-item' 'p.inf a' - "$FIX" | head -1 | cut -f2)
[[ "$t" == "Evil Angel Slut Pounded Rough" ]] || { echo "FAIL: chain tsel broken: '$t'" >&2; exit 1; }

# 3. poster fallback still resolves through the lazyload img data-src.
t=$(py cards 'div.video-item' - - "$FIX" | head -1 | cut -f3)
[[ "$t" == *"776123/300x168/1.jpg"* ]] || { echo "FAIL: poster lost: '$t'" >&2; exit 1; }

# 4. nested video-item divs shall match once per card (count = opens matching attrs).
n=$(py count 'div.video-item' "$FIX")
[[ "$n" == 2 ]] || { echo "FAIL: card count $n" >&2; exit 1; }

# 5. duplicate partition: same href twice on the same page = 'within'; same href on
#    different pages = 'cross' (--cross-page-dups-note bucket). /dd and /ff are
#    within-only, /ee and /aa cross-only — the partitions must stay disjoint with a
#    known size, so a dropped or inverted mode arg fails this suite.
RAW=$(mktemp /tmp/kvs_raw_XXXX.tsv)
printf 'page0\t/aa\tt1\npage1\t/aa\tt1\npage0\t/bb\tt2\npage1\t/cc\tt3\npage0\t/dd\tt4\npage0\t/dd\tt4\npage1\t/ee\tt5\npage2\t/ee\tt5\npage1\t/ff\tt6\npage1\t/ff\tt6\n' > "$RAW"
w=$(py dups href within < "$RAW" | wc -l);  [[ "$w" == 2 ]] || { echo "FAIL: within dup count $w" >&2; exit 1; }
x=$(py dups href cross  < "$RAW" | wc -l);  [[ "$x" == 2 ]] || { echo "FAIL: cross dup count $x" >&2; exit 1; }
# the partition partitions: no value may appear in both buckets
py dups href within < "$RAW" | sort > /tmp/kvs_within.$$.txt
py dups href cross  < "$RAW" | sort > /tmp/kvs_cross.$$.txt
common=$(comm -12 /tmp/kvs_within.$$.txt /tmp/kvs_cross.$$.txt | wc -l)
rm -f /tmp/kvs_within.$$.txt /tmp/kvs_cross.$$.txt
[[ "$common" == 0 ]] || { echo "FAIL: within/cross partition overlaps" >&2; exit 1; }

rm -f "$FIX" "$RAW" "$PYDOM"
echo "test_pydom.sh: all fixtures pass"
