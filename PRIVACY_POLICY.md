# Privacy Policy — YourSnitch

**Effective date:** 12 July 2026
**App:** YourSnitch (`com.privacy.camerawatch`)
**Developer:** Mor Golan · morgolann@gmail.com

## Summary

YourSnitch is a privacy tool that runs **entirely on your own device**. It shows you
**when your camera or microphone are used and which app used them**, and can show **which
countries your device's app connections reach**. It has **no account, no servers, no ads, no
analytics, and no third-party tracking SDKs**. Nothing you see in the app is ever uploaded to
the developer or anyone else.

## What data the app processes (all on-device)

- **Camera / microphone usage events** — the name of the foreground app and the time, when the
  camera or mic turns on. Shown to you and kept locally in a short recent-history list.
- **Connection / DNS data** — the domain names your device looks up and the country each
  resolves to, used to display recent connections. Kept locally in a short recent-history list.
- **Installed app names/icons** — resolved locally only to label the app that used a sensor.

You can clear this history at any time from within the app. Uninstalling the app deletes it.

## What is NOT collected

- We do **not** collect, transmit, or store any of your data on our servers — we have none.
- No personal identifiers, no accounts, no location, no advertising IDs.
- No data is sold or shared with third parties for advertising or analytics.

## Network access (important disclosure)

To detect the country of a connection, the app runs a **local, on-device VPN** that inspects
only **DNS lookups**. Your normal traffic is **not** routed through any external server — it
leaves your device directly as usual. To keep name resolution working, the app forwards those
DNS lookups to public DNS resolvers (**Cloudflare 1.1.1.1, Google 8.8.8.8, Quad9 9.9.9.9**),
the same kind of DNS request your device already makes. Those providers' own privacy policies
apply to the DNS queries they receive. YourSnitch itself receives none of it.

## Permissions and why they are used

- **Usage Access (PACKAGE_USAGE_STATS)** — to identify which app is in the foreground when the
  camera/mic activate. Without it, apps show as "Unknown".
- **VPN (VpnService)** — the local DNS-inspection described above. No traffic is proxied to us.
- **Query all packages** — to look up the name/icon of *any* installed app that uses a sensor.
- **Notifications, foreground service, run at boot, ignore battery optimizations** — to keep
  monitoring running reliably in the background.
- **Internet** — to forward DNS lookups to the public resolvers listed above.

The app **never opens the camera or microphone itself.**

## Children

YourSnitch is not directed to children under 13 and collects no data from anyone.

## Changes

If this policy changes, the updated version will be posted at this URL with a new effective date.

## Contact

Questions: **morgolann@gmail.com**
