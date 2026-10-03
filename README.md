# مكتبة الملفات — تطبيق أندرويد / File Library — Android app

يعرض مكتبة ملفات المدرسة على الهاتف، ويفتح كل ملف مباشرة في تطبيقه (العروض في WPS Office، والصوت في مشغّل الصوت…).

Shows the school files library on the phone and opens each file directly in its app (presentations in WPS Office, audio in the music player…).

## تثبيت التطبيق / Install

1. On the phone, open **Releases** on this page and tap **school-library.apk** in the latest release.
2. Open the downloaded file and tap **Install** (allow installing from this source; if Play Protect warns, choose **Install anyway**).
3. Open **مكتبة الملفات**, tap **اختيار المجلد**, pick the school files folder, then **Use this folder → Allow**.

## التحديث / Updating

- **New or replaced files:** copy them into the folder on the phone. Nothing else.
- **New look for the library:** upload the new `index.html` here (GitHub builds a new app to install). If you also put it on your **Media** site, the app picks it up online without reinstalling.
- **New app version:** any change here makes GitHub build a new `.apk` under Releases. Install it over the old one; the folder and settings stay.

## Files

| File | What it is |
|---|---|
| `.github/workflows/build.yml` | Tells GitHub how to build the app |
| `app/src/main/java/org/schoollibrary/app/MainActivity.java` | The app |
| `app/src/main/AndroidManifest.xml` | App name, icon, permissions |
| `app/src/main/res/values/strings.xml` | App name and the library page address |
| `app/src/main/res/drawable…` | The app icon |
| `index.html` | The library page; the app keeps a copy inside for use without internet |
| `app/library-keystore.txt` | The app's signing key as text, so new versions install over old ones |
| `settings.gradle`, `build.gradle`, `gradle.properties`, `app/build.gradle` | Build settings |
