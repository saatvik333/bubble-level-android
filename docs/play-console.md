---
title: Bubble Level – Publishing on Google Play
---

# Publishing on Google Play

Everything Play Console asks for, with the answer to give. The store text and graphics live in
`fastlane/metadata/android/en-US/` (the layout fastlane `supply`, F-Droid and IzzyOnDroid read).

## 1. Build the upload

    ./build.sh                                  # build/Level.aab
    VERSION_CODE=5 VERSION_NAME=1.3 ./build.sh  # every later upload needs a higher VERSION_CODE

The bundle is signed with the **upload key** at `~/.local/share/bubble-level-android/upload.jks`
(alias `upload`, password in the desktop keyring under `service bubble-level-android key upload-keystore`;
`build.sh` reads it from there). Back up the keystore file and the password together. If they are
lost, Play support can reset the upload key; the app itself keeps its Play signing key.

Upload certificate SHA-256: `89:49:B9:EB:41:73:15:81:F0:09:C2:91:67:48:52:A1:B0:BF:F8:AD:46:F3:2C:EB:4F:A3:FA:D5:E2:25:0C:F3`

## 2. Create the app

**Home → Create app**

| Field | Value |
| --- | --- |
| App name | `Bubble Level – Spot On` |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free |
| Declarations | Accept Developer Program Policies and US export laws |

## 3. App content (**Policy and programs → App content**)

| Section | Answer |
| --- | --- |
| Privacy policy | `https://saatvik333.github.io/bubble-level-android/privacy` |
| App access | All functionality is available without any access restrictions |
| Ads | No, my app does not contain ads |
| Content rating | Email: your contact email. Category: **All other app types**. Answer **No** to every question (no violence, sexuality, language, controlled substances, gambling, user interaction, sharing location, digital purchases). Expected result: Everyone / PEGI 3 / IARC 3+ |
| Target audience | Ages **13–15, 16–17, 18 and over**. Choosing under-13 groups puts the app under the Families policy, which a general tool doesn't need. "Could the store listing unintentionally appeal to children?" → No |
| News app | No |
| Data safety | Does your app collect or share any of the required user data types? → **No**. (No internet permission, no SDKs; settings and calibration stay in app-private storage.) Result: "No data collected", "No data shared" |
| Advertising ID | No (the app has no `AD_ID` permission) |
| Government app | No |
| Financial features | My app doesn't provide any financial features |
| Health | My app does not have any health features |

## 4. Store listing (**Grow users → Store presence → Main store listing**)

| Field | Source |
| --- | --- |
| App name | `title.txt` (22/30) |
| Short description | `short_description.txt` (71/80) |
| Full description | `full_description.txt` (`<b>` is supported by Play) |
| App icon | `images/icon.png` (512×512) |
| Feature graphic | `images/featureGraphic.png` (1024×500) |
| Phone screenshots | `images/phoneScreenshots/1–6.png` (1080×2160) |
| 7-inch tablet screenshots | `images/sevenInchScreenshots/1–3.png` (1200×1920) |
| 10-inch tablet screenshots | `images/tenInchScreenshots/1–3.png` (2560×1600) |

**Store settings:** category **Tools**; contact email `saatvik333sharma@gmail.com`; website
`https://github.com/saatvik333/bubble-level-android`.

Screenshots are reproducible: start an emulator, install the build, run `tools/store_screenshots.sh`.

## 5. Closed test, then production

Personal developer accounts created after 13 November 2023 must run a closed test with **at least
12 testers opted in continuously for 14 days** before Production unlocks
([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465), read 7 November 2026).

1. **Test and release → Testing → Closed testing → Create track.** Countries: all. Testers: an email
   list or a Google Group with 12 or more people (aim for 15–20; anyone who opts out restarts their 14 days).
2. **Create release.** App signing: keep **Google-generated app signing key**. Upload
   `build/Level.aab`. Release notes: `changelogs/<version code>.txt`. Review → roll out.
3. Share the opt-in link. Testers must accept it and install from Play, and stay opted in.
4. Collect feedback (Play's **Testing feedback** page, or email) and ship at least one update to the
   closed track based on it; the production application asks what changed.
5. After 14 days: **Dashboard → Apply for production.** It asks about recruiting, tester engagement,
   feedback, the target audience, the value to users, expected installs in the first year, and what
   changed during testing. Review usually takes up to seven days.
6. Production → Create release → promote the tested release.

## Signing and GitHub releases

Play installs are signed with Google's app signing key; APKs on GitHub Releases are signed with the
upload key. Android refuses to update across different signers, so switching between the two
sources means uninstalling first (calibration and settings are lost).
