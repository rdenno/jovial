- Fixes "Couldn't play this video" (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) on a video you'd been watching. This error means the server sent back something that isn't video, usually because the file has moved since the app last loaded the video list. The app now fetches the video's current file location from Ganymede and switches to it at the same spot. If that fails too, it tries the HLS version when the server has one.
- VOD details are always fetched fresh from the server instead of possibly coming from the app's disk cache. Emotes still use the cache.
- If a video still can't play, the error now shows what the server actually returned: the HTTP status, the type of content, and the first few characters. This makes it much easier to see what's wrong on the server side.

Installs over v1.1.0 and later directly. If you're still on v1.0.0, uninstall it first; it was signed with a different key.
