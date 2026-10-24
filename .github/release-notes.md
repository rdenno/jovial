- Chat now stays in sync after starting or seeking a video. Upcoming messages are fetched ahead and shown as playback reaches them, instead of dumping the next 15–25 seconds at once and then going quiet.
- 7TV / BTTV / FFZ emotes load in parallel with Ganymede's embedded emote catalog instead of waiting for it, so they appear within a few seconds even on slow connections.
- Emote catalogs are cached on the device (Ganymede's for 7 days, third-party for 1 day), so reopening a VOD doesn't re-download them.

**Upgrading from v1.0.0:** this build is signed with a new release key, so Android will refuse to install it over v1.0.0. Uninstall v1.0.0 first (this clears the saved server URL and watch progress). Future releases will update in place.
