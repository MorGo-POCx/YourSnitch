# YourSnitch — Google Play publishing checklist (mandatory items only)

Everything here is **required** by Google Play policy, the Play Console, or law. Optional polish
is deliberately excluded.

---

## 1. Signed release build (DONE in-repo)

- Upload keystore: `upload-keystore.jks` (kept out of git). Credentials in `keystore.properties`.
- **BACK UP `upload-keystore.jks` + its password somewhere safe.** If you enroll in Play App
  Signing (default, recommended), a lost upload key can be reset — but keep it anyway.
- Build the upload artifact:
  ```
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  ./gradlew bundleRelease
  # -> watch/build/outputs/bundle/release/watch-release.aab   <-- upload THIS
  ```
- Enroll in **Play App Signing** when prompted (Google holds the real signing key).

## 2. Privacy policy (REQUIRED — hosted at a public URL)

- Text is in `PRIVACY_POLICY.md`. Play needs it at a **public HTTPS URL** (not a file).
- Free hosting: push the repo to GitHub → enable **GitHub Pages**, or paste into a public
  GitHub Gist / your own page. Put that URL in **Play Console → App content → Privacy policy**.

## 3. Data Safety form (REQUIRED — Play Console → App content → Data safety)

Answers that match how the app actually behaves:

- **Does your app collect or share any of the required user data types?** → **No.**
  (All processing is on-device; nothing is sent to you or any analytics/ads SDK.)
- **Is data encrypted in transit?** → N/A (no data leaves to your servers).
- **Do you provide a way to request data deletion?** → Data is on-device only; user clears
  history in-app / by uninstalling. State that.
- **Note for reviewers (put in the form's context if asked):** the app runs a *local* VPN that
  inspects only DNS and forwards those lookups to public resolvers (Cloudflare/Google/Quad9) for
  name resolution. This is functional, on-device processing — no user data is collected by the
  developer. Disclosed in the privacy policy under "Network access".

## 4. Sensitive / restricted permission declarations (REQUIRED)

The Play Console will force a declaration form for each of these. Prepared justifications:

- **VpnService (`BIND_VPN_SERVICE`)** — *"The app uses a local, on-device VpnService solely to
  inspect DNS lookups so it can show the user which countries their device's connections reach.
  It does not route, proxy, or collect user traffic to any external server; normal traffic exits
  the device directly."*
- **`QUERY_ALL_PACKAGES`** (HIGHEST rejection risk) — *"A privacy monitor must identify which of
  the user's own installed apps accessed the camera/microphone. Because any installed app can be
  the one accessing a sensor, the app needs visibility of all packages to resolve the correct
  app name/icon for the user. No package list is transmitted off the device."*
  - **Fallback if rejected:** drop `QUERY_ALL_PACKAGES`, add a limited `<queries>` block, and
    show the raw package name (instead of a friendly label) for apps not visible. Core feature
    degrades but the app still ships.
- **Usage Access (`PACKAGE_USAGE_STATS`)** — *"Core functionality: used to determine which app
  is in the foreground at the moment the camera/microphone activate, so the event can be
  attributed to the correct app. Not used for analytics or profiling."*
- **specialUse foreground services (×2)** — Console asks why a standard FGS type doesn't fit:
  - WatchService: *"Passively monitors camera/mic usage of OTHER apps; it does not itself use the
    camera or microphone, so the `camera`/`microphone` FGS types are inappropriate."*
  - SniffVpnService: *"Local DNS-inspection VpnService; no standard FGS type describes a
    connection/DNS privacy monitor."*

## 5. Other mandatory Play Console forms

- **Content rating questionnaire** — utility app, no objectionable content → will rate Everyone.
- **Target audience & content** — set to adults / not designed for children (collects no data).
- **Ads** — declare **No ads**.
- **App category** — Tools (or Productivity).
- **Store listing** — title, short + full description, at least **2 screenshots**, a 512×512
  icon, and a 1024×500 feature graphic (all required to submit).
- **Target API level** — Play requires 35+ for new apps; the project is targetSdk 35. ✅

## 6. Legal / licensing (DONE)

- Code license: **MIT** (`LICENSE`).
- Bundled IP→country data (`geoip.bin`): **CC0-1.0 / public domain** — no attribution required.

---

### Realistic expectation
The VpnService + `QUERY_ALL_PACKAGES` + Usage Access + specialUse combination makes this a
**high-scrutiny review**. Expect questions and possibly one rejection round. If Play refuses
`QUERY_ALL_PACKAGES`, use the fallback above, or distribute the signed APK directly / via F-Droid.
