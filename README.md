# BD Bluetooth TV Remote

Flutter Android app that turns a compatible Android phone into a Bluetooth HID keyboard-style remote for Android TV / Google TV.

## First version
- Bluetooth HID Device registration through Android's native Bluetooth API.
- D-pad navigation, Enter, Back, Home and Menu keyboard usages.
- Consumer Control usages for volume, mute and media playback.
- Dark remote-control interface.
- GitHub Actions release APK workflow.

## Compatibility notes
- Bluetooth HID Device API requires Android 9 (API 28) or newer.
- The phone must expose the Bluetooth HID Device profile. Not every Android phone supports this.
- The TV must accept Bluetooth HID keyboards/accessories. Android TV Bluetooth support alone does not guarantee keyboard compatibility.
- Power, voice assistant, app shortcuts and other manufacturer-specific functions are not guaranteed by generic HID.

## Pairing
1. Install and open the app; tap START BLUETOOTH REMOTE.
2. Grant Bluetooth permissions and allow the phone to become discoverable.
3. On the TV open Settings > Remotes & Accessories > Add accessory (menu names vary).
4. Select the phone and approve pairing.
5. Test navigation and media keys.

## Build
Push to main or run the Android APK workflow manually from GitHub Actions. Download the bd-bluetooth-tv-remote artifact from the successful run.

The workflow creates the Flutter Android host project in CI, adds the app sources and native HID bridge, then builds a release APK.
