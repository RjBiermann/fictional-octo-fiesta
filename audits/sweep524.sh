#!/usr/bin/env bash
# FINDINGS-524 sweep — home + search status per provider (run: issue 524)
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
probe() { # name tier url
  local name="$1" tier="$2" url="$3"
  local code
  if [ "$tier" = tls ]; then
    code=$(python3 - "$url" <<'EOF' 2>/dev/null || echo 000
import sys
from curl_cffi import requests
try: print(requests.get(sys.argv[1], impersonate="chrome", timeout=25).status_code)
except Exception: print(000)
EOF
)
  else
    code=$(curl -sL -o /tmp/sweep_$name.html -A "$UA" --max-time 25 -w "%{http_code}" "$url" 2>/dev/null || echo 000)
  fi
  printf "%s\t%s\t%s\t%s\n" "$name" "$tier" "$code" "$url"
}
probe AllClassicPorn plain https://allclassic.porn/ &
probe AllClassicPornS plain "https://allclassic.porn/search/sex/" &
probe Cat3Film plain https://cat3film.com/ &
probe Cat3FilmS plain "https://cat3film.com/_ajax/search?q=sex" &
probe Cat3Movie plain https://cat3movie.org/ &
probe Cat3MovieS plain "https://cat3movie.org/search/sex" &
probe EPorner plain https://www.eporner.com/ &
probe EPornerS plain "https://www.eporner.com/search/sex/" &
probe Eroticmv plain https://eroticmv.com/ &
probe EroticmvS plain "https://eroticmv.com/?s=sex" &
probe Film1k tls https://www.film1k.com/ &
probe Film1kS tls "https://www.film1k.com/?s=sex" &
probe FreePornVideos tls https://www.freepornvideos.xxx/ &
probe FreePornVideosS tls "https://www.freepornvideos.xxx/search/sex/1/" &
probe FullPorner tls https://fullporner.com/ &
probe FullPornerS tls "https://fullporner.com/search?q=sex" &
probe HQPorner plain https://hqporner.com/ &
probe HQPornerS plain "https://hqporner.com/?q=sex&p=1" &
probe JavGuru plain https://jav.guru/ &
probe JavGuruS plain "https://jav.guru/?s=sex" &
probe Javbangers plain https://www.javbangers.com/ &
probe JavbangersS plain "https://www.javbangers.com/search/sex/1/" &
probe Javmost plain https://www.javmost.ws/ &
probe JavmostS plain "https://www.javmost.ws/showlist2/all/1/" &
probe Javseen plain https://javseen.tv/ &
probe JavseenS plain "https://javseen.tv/search/video/?ajax=search_results&s=sex&o=recent" &
probe Javtiful plain https://javtiful.com/ &
probe JavtifulS plain "https://javtiful.com/search?q=sex" &
probe Mangoporn plain https://mangoporn.net/ &
probe MissAV plain https://missav.live/ &
probe MissAVS plain "https://missav.live/en/search/sex" &
probe Neporn plain https://neporn.com/ &
probe NepornS plain "https://neporn.com/search/sex/" &
probe PandaMovies plain https://pandamovies.pw/ &
probe PandaMoviesS plain "https://pandamovies.org/search/sex" &
probe PerverZija plain https://tube.perverzija.com/ &
probe PerverZijaS plain "https://tube.perverzija.com/?s=sex" &
probe PornXP plain https://pxp.news/ &
probe PornXPS plain "https://pxp.news/tags/sex" &
probe Porntrex plain https://www.porntrex.com/ &
probe PorntrexS plain "https://www.porntrex.com/search/sex/" &
probe Sexfilm plain "https://en.sex-film.biz/index.php?do=search&subaction=search&story=sex" &
probe WatchPorn plain https://watchporn.to/ &
probe WatchPornS plain "https://watchporn.to/search/?q=sex&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&category_ids=&sort_by=&from_videos=1" &
probe XMoviesForYou tls https://xmoviesforyou.com/ &
probe XMoviesForYouS tls "https://xmoviesforyou.com/search?q=sex" &
probe Xhamster plain https://xhamster.com/ &
probe XhamsterS plain "https://xhamster.com/search/sex" &
probe ixiporn plain https://ixiporn.org/ &
probe ixipornS plain "https://ixiporn.live/search/sex" &
wait
