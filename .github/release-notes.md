- Another fix for ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED. The video file on the server turned out to be fine, so this targets how the app fetches it:
  - Video requests now ask the server for the plain file. Before, the player's first request quietly asked for a compressed copy, which could hand it data it couldn't read even though the file itself was fine.
  - When a video fails like this, the app now drops all its server connections and tries again on fresh ones, the way a force stop did. If that still fails, it tries the HLS version when there is one.
- If it still can't play, the error box shows more detail: the server's reply to the exact request the player makes (status, content type, compression, size, first bytes) and the player's own error message.

Installs over v1.1.0 and later directly. If you're still on v1.0.0, uninstall it first; it was signed with a different key.
