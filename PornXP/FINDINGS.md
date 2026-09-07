# FINDINGS — PornXP (pxp.news)

## Engine fingerprint
Custom static HTML server with jQuery and basic JavaScript. No major adult video CMS detected (not WordPress, KVS, or similar). Uses nginx server.

## Search
Pattern: `?q={query}` - confirmed working
Example: `https://pxp.news/?q=test` returns search results in same `.item_cont` structure as homepage
```bash
curl -s "https://pxp.news/?q=test" | grep -A 3 "item_cont" | head -15
```

## Video pages — ≥5, varied

Probed pages from different listings:
1. **Recent listing**: `/videos/307502831335` - "But He's Older! It's Gross!" - His Stepdaughter's Pussy Saved Him
2. **Search results**: `/videos/83992638893` - Cat, Queen of Clams Is a Scorpio and Always Horny  
3. **Related videos**: Multiple videos from related sections on video pages

Structure for each page:
- Title: `<h1>` tag
- Description: `<div id="desc">` 
- Tags: `.tags` section with `<a>` links
- Duration: Available in listing page, not on video page itself
- Upload date: In description section "Uploaded: YYYY.MM.DD"
- Poster image: `/30750283961335.jpg` pattern (video ID + image suffix)

## Related videos
Same `.item_cont` structure as homepage/listings, found at bottom of video pages:
```html
<div class="item_cont">
  <div class="item preview" data-id="36633186" data-preview="//t.pornxp.sh/366331863230.mp4">
    <a href="/videos/366331861700">
      <div class="item_width">
        <div class="item_height">
          <div class="item_thumb">
            <img class="item_img" src="/36633186641700.jpg">
            <div class="item_dur">33:26</div>
          </div>
        </div>
        <div class="item_title">Serena Sterling Finally Gets Stepdaddy BBC In Her Kitty</div>
      </div>
    </a>
  </div>
  <div class="item_tags">
    <a href="/tags/FamilyXXX">FamilyXXX</a>
    <a href="/tags/Serena%20Sterling">Serena Sterling</a>
  </div>
</div>
```

## Stream sources (per video page)
Each video page contains direct MP4 streams with multiple qualities:
```html
<video id="player" poster="/30750283961335.jpg">
  <source src="//sd.pornxp.sh/9kBWlV9DifxP8Xr5u8t34gYwg/3075028399/360.mp4" title="360p" type="video/mp4">
  <source src="//sd.pornxp.sh/k0j5JX99vdKL8CyZ_8iKxkhgQ/30750283817/1080.mp4" title="1080p" type="video/mp4">
  <source src="//sd.pornxp.sh/HVB2v_92Zki38yUo98y00lvxg/30750283256/720.mp4" title="720p" type="video/mp4">
</video>
```

Pattern: `//sd.pornxp.sh/{random_hash}/{video_id}/{quality}.mp4`

## Headers / referer
No special headers required for stream access. Direct HTTP 200 response for video URLs:
```bash
curl -I "https://sd.pornxp.sh/9kBWlV9DifxP8Xr5u8t34gYwg/3075028399/360.mp4"
HTTP/2 200
content-type: video/mp4
content-length: 102067999
```

## Pagination
Pattern: `/?page=N` confirmed working
Example: `https://pxp.news/?page=2` returns different results than page 1
```html
<div id="pages">
  <span><a href="/" class="chosen">1</a></span>
  <span><a href="/?page=2" class="">2</a></span>
  <span><a href="/?page=3" class="">3</a></span>
  <span><a href="/?page=2" class="">&gt;</a></span>
</div>
```

## Risks / blockers
- No Cloudflare protection detected
- No IP blocks or geoblocking detected  
- No age walls or access restrictions
- Static HTML site, no JS rendering needed
- Backup domain available: porn-xp.eu