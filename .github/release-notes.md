- Fixes long live-archive videos that wouldn't play when you resumed partway through (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, "Skipping atom with length > 2147483647").
  - These recordings have two indexes the player uses to jump to a point in the video: one for audio and one for video. In long recordings, the audio index is wrong past the 4 GB mark, about 3 hours into a typical stream.
  - The player always used whichever index came first, so resuming late in the video jumped to the wrong spot in the file.
  - The app now reads all the indexes and uses the most complete one.

Installs over v1.1.0 and later directly. If you're still on v1.0.0, uninstall it first; it was signed with a different key.
