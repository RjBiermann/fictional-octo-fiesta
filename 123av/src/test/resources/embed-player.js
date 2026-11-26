//#region js/embed/app.js
var Plyr = window.Plyr;
var Hls = window.Hls;
var PLAYER_CONTROLS = [
	"play-large",
	"rewind",
	"play",
	"fast-forward",
	"progress",
	"current-time",
	"duration",
	"mute",
	"volume",
	"captions",
	"settings",
	"pip",
	"airplay",
	"fullscreen"
];
function getHashId() {
	const match = (/* @__PURE__ */ new RegExp("/e/([a-z0-9_]+)", "i")).exec(location.pathname);
	return match ? match[1] : null;
}
function createVideoElement(media) {
	const poster = new URLSearchParams(location.search).get("poster");
	const video = document.createElement("video");
	video.setAttribute("controls", "");
	video.setAttribute("crossorigin", "");
	video.setAttribute("playsinline", "");
	if (poster) video.setAttribute("poster", poster);
	return video;
}
function setupPlayer(media) {
	const playerEl = document.querySelector("#player");
	const video = createVideoElement(media);
	const basePlyrOptions = {
		controls: PLAYER_CONTROLS,
		fullscreen: {
			enabled: true,
			fallback: true,
			iosNative: true,
			container: null
		},
		speed: {
			selected: 1,
			options: [
				.25,
				.5,
				1,
				1.25,
				1.5,
				2
			]
		},
		previewThumbnails: {
			enabled: Boolean(media.vtt),
			src: media.vtt
		}
	};
	if (Hls.isSupported()) {
		const hls = new Hls({
			autoStartLoad: true,
			maxBufferLength: 5,
			maxBufferSize: 1 * 1e3 * 1e3
		});
		hls.loadSource(media.stream);
		hls.attachMedia(video);
		const setHlsDefaultLevel = () => {
			hls.currentLevel = hls.levels.findIndex((level, levelIndex) => {
				return level.width + 20 > window.innerWidth || levelIndex === hls.levels.length - 1;
			});
		};
		hls.on(Hls.Events.MANIFEST_PARSED, () => {
			playerEl.appendChild(video);
			const player = new Plyr(video, {
				...basePlyrOptions,
				i18n: { qualityLabel: { 0: "Auto" } },
				quality: {
					forced: true,
					default: 0,
					options: [...hls.levels.map((level) => level.height).reverse(), 0],
					onChange: (height) => {
						if (!height) setHlsDefaultLevel();
						else hls.levels.forEach((level, levelIndex) => {
							if (level.height === height) hls.currentLevel = levelIndex;
						});
					}
				}
			});
			window.addEventListener("resize", setHlsDefaultLevel);
			window.addEventListener("orientationchange", setHlsDefaultLevel);
			player.on("enterfullscreen", () => {
				screen.orientation.lock("landscape").catch(() => {});
				setHlsDefaultLevel();
			});
			player.on("exitfullscreen", () => {
				screen.orientation.lock("portrait").catch(() => {});
				setHlsDefaultLevel();
			});
		});
		return;
	}
	video.src = media.stream;
	playerEl.appendChild(video);
	const player = new Plyr(video, basePlyrOptions);
	player.on("enterfullscreen", () => {
		screen.orientation.lock("landscape").catch(() => {});
	});
	player.on("exitfullscreen", () => {
		screen.orientation.lock("portrait").catch(() => {});
	});
}
function buildStreamUrl(hashId) {
	const url = new URL("/stream", location.origin);
	new URLSearchParams(location.search).forEach((value, key) => {
		url.searchParams.set(key, value);
	});
	url.searchParams.set("id", hashId);
	return url.toString();
}
(function() {
	const hashId = getHashId();
	if (!hashId) return;
	fetch(buildStreamUrl(hashId)).then((resp) => resp.json()).then((resp) => {
		setupPlayer(resp.media);
	});
})();
(() => {
	if (/^https:\/\/123av.com/.test(document.referrer)) return;
	const storageName = "__pu2";
	const interval = 1e4;
	const popItems = [];
	let lastPopAt = 0;
	for (let i = 0; i < 3; i++) popItems.push({
		url: "https://attacheatableoverlabor.com/2134859",
		last: 0,
		expires: 30 * 6e4
	});
	try {
		JSON.parse(localStorage.getItem(storageName) || "[]").forEach((lastPop, index) => {
			popItems[index].last = lastPop;
			lastPopAt = Math.max(lastPopAt, lastPop);
		});
	} catch (err) {}
	let anchorElement = null;
	const run = () => {
		const now = Date.now();
		if (lastPopAt + interval > now) return;
		const index = popItems.findIndex((item) => item.last + item.expires < now);
		if (index < 0) return;
		const isMobile = window.innerWidth <= 768;
		if (!anchorElement) {
			anchorElement = document.createElement("a");
			anchorElement.setAttribute("target", "_blank");
			anchorElement.setAttribute("href", isMobile ? popItems[index].url : "#");
			anchorElement.setAttribute("style", [
				"position: fixed",
				"top: 0",
				"left: 0",
				"width: 100%",
				"height: 100%",
				"display: block",
				"z-index: 9999999",
				"cursor: auto"
			].join(";"));
			anchorElement.onclick = (e) => {
				anchorElement.remove();
				anchorElement = null;
				lastPopAt = Date.now();
				popItems[index].last = lastPopAt;
				localStorage.setItem(storageName, JSON.stringify(popItems.map((item) => item.last)));
				if (!isMobile) {
					e.preventDefault();
					window.open(popItems[index].url, "_blank");
				}
			};
			document.body.appendChild(anchorElement);
		}
	};
	run();
	setInterval(run, 3e3);
})();
//#endregion
