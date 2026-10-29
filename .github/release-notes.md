- Fixes a video that wouldn't play again after you returned to the app later. After a long pause the server connection is usually gone, and a player that had hit an error ignored the play button. The player now reconnects at the same spot when you resume after more than 2 minutes paused, or when you press play after an error. This also works from the notification or headphone buttons.
- If loading fails, it retries automatically a few times. If it still can't play, you'll see a "Couldn't play this video" message with a Retry button instead of a play button that does nothing.
- If buffering goes 20 seconds with no data arriving, the connection is reopened automatically.
- A saved position at or past the end of a video now restarts it from the beginning.
- Clears out leftover video data that older versions could leave in the app's cache. This was why clearing the cache used to help.

Installs over v1.1.0 and later directly. If you're still on v1.0.0, uninstall it first; it was signed with a different key.
