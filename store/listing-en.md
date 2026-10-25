# LanDrop — store listing (English)

Target store: Google Play. The same fields work for other Android catalogues.
Limits are Google Play limits.

## App name (max 30 characters)

LanDrop — files over Wi-Fi

## Short description (max 80 characters)

Move files from your computer to your phone over Wi-Fi. No cloud, no cable.

## Full description (max 4000 characters)

LanDrop turns your Android phone into a shared folder on your Wi-Fi network. The app starts a small HTTP server, and you move files from your computer using any browser — no cable, cloud account, sign-up or internet connection.

Open the address shown in the app, such as http://192.168.1.42:8080, drop files onto the page, and they land on your phone. The same page opens your photo and video library, lets you download originals back to the computer, and removes files you no longer need.

**How it works**

1. Connect your computer and phone to the same Wi-Fi network.
2. Turn the server on in LanDrop.
3. Open the address from the app's home screen in a browser.
4. Drag files onto the upload page.

**Features**

• Browser uploads: drag and drop or pick files, with no desktop client to install.
• Up to three transfers at once, with speed, time left, and cancel for a single file or the whole queue.
• Thumbnails in the upload queue and received-files list, plus video frames in the gallery.
• Phone photo and video gallery: source tabs, lazy thumbnails, search, and a viewer with swipe, arrow-key and keyboard navigation.
• Original downloads and HTTP Range support for video.
• Light and dark themes on every page; your choice is remembered, and the system theme is used until you pick one.
• Received-files list inside the app: search, open in another app, delete.
• HTTP API and a simple binary protocol for scripts and automation.

**No cloud, no subscription**

Files stay on your local network. LanDrop does not send data to the internet, does not include third-party SDKs, and requires no account. The server runs while it is switched on and is reachable only by devices on the same Wi-Fi network.

**Requirements**

• Android 7.0 or newer.
• Phone and computer on the same Wi-Fi network.
• On Android 13+, the gallery needs photo and video permission. Without it, the rest of the app and the received-files list still work.

**Good to know**

• If the computer cannot open the address, check that the network does not isolate clients (guest Wi-Fi) and that the firewall allows port 8080.
• The server has no authentication: do not expose it to the internet, and use it on a trusted network.
• Some video audio tracks (DTS/AC3 in MKV) do not play in browsers; download the file and open it in a media player.

## What's new (max 500 characters)

First LanDrop release: browser uploads from your computer, a transfer queue with progress and cancel, a photo and video gallery, thumbnails, light and dark themes, an HTTP API and a binary protocol.

## Category and tags

- Google Play category: Tools.
- Tags: file transfer, Wi-Fi, local network, file manager, gallery.

## ASO keywords

file transfer over Wi-Fi, send files from phone to computer, local network, HTTP server, phone gallery, no cloud, LAN transfer

> Google Play has no separate keyword field; these phrases belong in the name, short description and full description. Apple's keywords field is limited to 100 characters.

## Store fields

- Privacy policy: required by Google Play; link a page explaining that the app collects and transmits no data.
- Developer contact: add your email and website in Play Console.
- Screenshots: `screenshots/en/01-app-main.png`, `02-web-upload.png`, `03-gallery.png`, `04-light-viewer.png` (1080 × 1980).
- Icon: `screenshots/icon-512.png` (the icon is language-neutral).
- Feature graphic: `screenshots/en/feature-graphic.png` (1024 × 500).

## Language note

The app and its web pages currently ship in Russian. The English screenshots are
localized mockups prepared for an English listing; localize the interface before
publishing this card. See `store/README.md`.
