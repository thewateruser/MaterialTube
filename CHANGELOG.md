# Changelog

## 0.7.1
- Home has no Shorts any more (they weren't personal). Shorts are still in search, channels
  and subscriptions.

## 0.7.0
- **Home Shorts from your account**: Shorts rows now come from your own recommendations (the
  Shorts in your web home feed, then YouTube's personal Shorts sequence). Searches are only a
  fallback when neither is available.
- Home sections left with a single video (after duplicates were removed) no longer show a
  heading over one video; they join the neighbouring section.
- Comments on Shorts and community posts use the same panel as videos and never go past
  60% / 65% of the screen.
- Settings → Copy diagnostics also reports the Shorts sources.

## 0.6.3
- Comments on the watch page never cover the video: the panel can grow, at most, to the area
  below it.

## 0.6.2
- **Comments you can scroll without going full screen**: the comments open as a panel over
  the lower part of the screen (below the video on the watch page), and the list scrolls at that
  height while the video keeps playing. Drag the panel up for full screen, down to close.
- **Stubborn Shorts**: a Short that errors or shows nothing for 7s is retried automatically
  (fresh streams, then 720p) before a Retry button appears. The player pool is now previous,
  current and next, which needs fewer hardware decoders.

## 0.6.1
- **Shorts play instantly**: the current Short, the previous one and the next two each get their
  own player, prepared and paused on the first frame, so swiping (forwards or back) just
  switches players instead of loading from scratch.
- Shorts show their thumbnail until the video's first frame, never a black screen; a failed
  Short shows Retry.
- Channel Shorts grids show their thumbnails again.

## 0.6.0
- **Faster Shorts**: the next Shorts are resolved in the background while you watch, so swiping
  starts the next one straight away; playback starts after ~1s of buffer instead of 2.5s.
- **Sharper Shorts**: vertical videos now get the quality you picked (1080p meant the long side
  before, so Shorts dropped to ~608p).
- **Comment sorting** shows Top / Newest immediately; the options load together with the
  comments, and picking Newest early applies as soon as they're ready.
- **Community posts**: read comments and replies, and like posts (signed in).

## 0.5.0
- **Channel Posts and About**: community posts (text, images, polls, linked videos) and the
  About panel (description, subscriber/video/view counts, join date, country, links).
- **Pinch the mini player** with two fingers to resize it; size and position are remembered.
- **Pinch in fullscreen**: pinch out to zoom the video to fill the screen, pinch in to see the
  whole frame again.
- Channel avatars are plain circles everywhere.

## 0.4.2
- **Fullscreen** really stays in landscape now. The player echoed the fullscreen state back as
  a button press, which the app treated as a toggle and flipped straight back to portrait.
- **Your channels**: the "All" tab is no longer shown as a channel; subscribed channels are
  loaded from the TV page, the TV/web channel lists or the web sidebar, whichever has them.
- Settings → Copy diagnostics, for reporting subscription problems.

## 0.4.1
- **Fullscreen**: the button now simply locks landscape and stays there until you press it
  again, drag down or go back. Turning the phone no longer changes anything.
- **Shorts on home**: Shorts rows show up again (every ~8 videos), with fallback sources when
  the signed-in search returns none.
- **Subscriptions**: the "Your channels" list works again.

## 0.4.0
- **Mini player**: keep watching in a floating player while you browse. Drag it anywhere,
  tap to reopen, play/pause or close it.
- **Drag down** on the video to minimise the watch page (or leave fullscreen).
- **Landscape fix**: rotating after using the fullscreen button no longer snaps back to portrait.
- **Channel page** rebuilt: banner, avatar, subscriber line, subscribe button, and
  Home / Videos / Shorts tabs (Shorts as a grid).
- **Shorts balance**: a Shorts row about every 8 videos on home, including further down.
- One consistent springy press animation on every button, card and tab.
- Smoother fade-through between bottom tabs and slides for pages.
- Plain circle avatars on the watch page.

## 0.3.1
- Every build is now signed with the same key, so new versions install as updates
  (uninstall once more when coming from 0.3.0 or older).

## 0.3.0
- **Shorts**: Shorts appear as a horizontal row in feeds and search; home gets a Shorts row
  when YouTube's feed has none. Tap one for a full-screen, swipeable Shorts player
  (loops, like, comments, share).
- **Subscriptions**: "Your channels" avatar row above the uploads.
- **Search**: matching channels are shown above the video results.
- **Comments**: sort by Top or Newest.

## 0.2.0
- Renamed to **MaterialTube** (package `dev.materialtube`; installs as a new app).
- **Material 3 Expressive** UI: expressive motion, dynamic color, loading indicators,
  toggle buttons, cookie-shaped avatars, emphasized type, short navigation bar,
  floating mini-player.
- **Comments**: preview card on the watch page, full sheet with paging and replies.
- **Sign-in on restrictive networks**: DNS-over-HTTPS fallback when youtube.com can't be
  resolved, plus fallback to oauth2.googleapis.com / youtubei.googleapis.com.

## 0.1.0
- First release: MicroG-free Google sign-in (TV device flow), personalized home feed,
  subscriptions, history, likes, search, SponsorBlock, background play, PiP.
