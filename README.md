# Now for Redlib

<p align="center">
  <img src="site/screenshot.png" alt="Now for Redlib — feed and comments" width="640">
</p>

A read-only Reddit client for Android, rebuilt in the spirit of the classic
**Now for Reddit** (same layout DNA — card feed, comment threads, subreddit
drawer) with a modern Jetpack Compose / Material 3 dark UI. It uses **no
Reddit code and no Reddit API**: everything is fetched through public
[Redlib](https://github.com/redlib-org/redlib) instances.

## Features

- **Automatic instance discovery** — pulls the live instance list from the
  redlib-instances project at runtime, health-checks, and rotates on failure
  (rate limits, 403s, load balancers)
- **Anubis punch-through** — most public instances sit behind Anubis
  proof-of-work bot checks; Now for Redlib solves them on-device in
  milliseconds and caches the clearance cookies
- **Full-screen media viewer** — pinch-zoom images; videos are downloaded,
  remuxed to a vanilla MP4 (`MediaExtractor` → `MediaMuxer`) and played from
  a local copy, which sidesteps ExoPlayer's issues with reddit's
  DASH-branded HLS
- **Comment threads** with nested replies, scores, OP/MOD coloring, and
  +/− collapse/expand per comment
- **Subreddit search & history** — searchable from the top bar and the
  drawer; recently visited subreddits appear in the drawer with inline
  remove buttons
- **72-hour offline mode** — seen feeds, comment threads, and media are
  cached app-privately and purged after 72h; browsing works with no network
- **Sleek immersive UI** — dark-only Material 3, status bar hidden (swipe to
  reveal), collapsing toolbars, edge-to-edge content

## Download

Grab the latest APK from the
[releases page](https://github.com/levkropp/redlib-now/releases/latest)
(date-versioned, e.g. `v2026.08.27`) and sideload it.

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17 and an Android SDK (API 34).

## How it works

```
UI (Compose/M3) ──> RedlibClient ──> instance health-check + rotation
                       │
                       ├─ Anubis solver (SHA-256 PoW, on-device)
                       ├─ meta-refresh follower (instance load balancers)
                       └─ Jsoup parsers ──> posts / comments
                              │
MediaCache / FeedCache <──────┘  72h app-private cache (media remux, JSON)
```

All network activity is logged under the `NowRedlib` logcat tag:

```bash
adb logcat -s NowRedlib
```

## Notes

- Read-only by design: no login, no voting, no replying — no Reddit API
  keys or accounts involved
- If an instance blocks or throttles you, the client moves to the next one
  automatically; you can also wait for the daily upstream list refresh
