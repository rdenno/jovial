- Fixes buffering during playback. The video stream went through the same disk cache the app uses for emotes. That meant every byte of video was also written to storage while playing, which could stall playback even on a fast connection.
- The player now buffers 2-3 minutes ahead, up from under a minute, so short network drops shouldn't interrupt playback. The buffer has a memory cap, so very high bitrates buffer less.
- After a stall, playback waits for a few seconds of video before resuming, so it doesn't stop again straight away.
- A dropped connection now keeps retrying for about a minute and a half before giving up.

Installs over v1.1.0 and later directly. If you're still on v1.0.0, uninstall it first; it was signed with a different key.
