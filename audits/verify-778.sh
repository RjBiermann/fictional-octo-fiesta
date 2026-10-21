#!/usr/bin/env bash
# Live verification of the XvideosParse contract (issue #778) — plain HTTP only.
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
REF="https://www.xvideos.com/"
fail=0

resolve() { # $1 = embed id
  local id="$1" body
  body=$(curl -sL -A "$UA" -H "Referer: $REF" --max-time 60 "https://www.xvideos.com/embedframe/$id")
  if echo "$body" | grep -q "This video has been deleted"; then echo "DELETED"; return; fi
  echo "$body" | grep -oP "setVideoUrl(High|Low)\('\K[^']+" | while read u; do echo "MP4 $u"; done
  echo "$body" | grep -oP "setVideoHLS\('\K[^']+" | while read u; do echo "HLS $u"; done
}

echo "== live target 61306335 =="
mapfile -t links < <(resolve 61306335)
printf '%s\n' "${links[@]}" | sed 's/^/  parsed: /'
[ ${#links[@]} -eq 3 ] || { echo "  FAIL: expected 3 parsed streams"; fail=1; }
for l in "${links[@]}"; do
  url="${l#MP4 }"; url="${url#HLS }"
  code=$(curl -sL -o /dev/null -A "$UA" -H "Referer: $REF" -r 0-0 --max-time 60 -w "%{http_code} %{content_type}" "$url")
  echo "  $url -> $code"
  echo "$code" | grep -qE "^(200|206)" || { echo "  FAIL: stream not served"; fail=1; }
done

echo "== deleted target 61306334 =="
d=$(resolve 61306334)
[ "$d" == "DELETED" ] && echo "  OK: deleted shell detected, no streams emitted" || { echo "  FAIL: $d"; fail=1; }

[ $fail -eq 0 ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit $fail
