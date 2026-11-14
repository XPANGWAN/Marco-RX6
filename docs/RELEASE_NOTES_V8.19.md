# MARCO RX6 — V8.19

> Release draft. Publish this text with the V8.19 GitHub Release after attaching the final APK files.

## 🇸🇦 العربية

### أبرز التحديثات

- تحسين مدير التنزيلات وإجراءات الإيقاف المؤقت والاستئناف والإلغاء من الإشعارات.
- فتح التطبيق عند الضغط على إشعار التنزيل.
- تحسين معالجة مرحلة إنهاء التنزيل وتجهيز الملف بعد وصول التقدم إلى 100%.
- إنشاء مجلد الحفظ الافتراضي `Download/MARCO RX6` على إصدارات Android المدعومة.
- السماح للمستخدم باختيار مجلد حفظ آخر، مع احترام المجلد الذي يحدده.
- تحديث رقم الإصدار إلى **8.19** ورمز الإصدار إلى **36**.

### ملفات APK

أرفق الملفات الثلاثة التالية في قسم **Assets** من هذا الإصدار:

| الملف | مناسب لـ |
|---|---|
| `app-arm64-v8a-release.apk` | معظم هواتف Android الحديثة بمعمارية 64-bit |
| `app-armeabi-v7a-release.apk` | أجهزة Android بمعمارية 32-bit |
| `app-universal-release.apk` | خيار شامل للأجهزة المتوافقة |

### طريقة التثبيت

1. نزّل ملف APK المناسب لمعمارية جهازك.
2. افتح الملف من مجلد التنزيلات.
3. اسمح بالتثبيت من هذا المصدر إذا طلب Android ذلك.
4. أكمل التثبيت وافتح MARCO RX6.

**الحد الأدنى:** Android 8.0 (API 26).

---

## 🇬🇧 English

### Highlights

- Improved download manager actions, including pause, resume, and cancel from notifications.
- Tapping a download notification opens the app.
- Improved download finalization and file-preparation handling after progress reaches 100%.
- Creates the default `Download/MARCO RX6` folder on supported Android versions.
- Allows users to choose a different download folder and respects their selected folder.
- Updates the app to **version 8.19**, version code **36**.

### APK files

Attach these three files to the **Assets** section of this GitHub Release:

| File | Recommended for |
|---|---|
| `app-arm64-v8a-release.apk` | Most modern 64-bit Android phones |
| `app-armeabi-v7a-release.apk` | 32-bit Android devices |
| `app-universal-release.apk` | A universal option for compatible devices |

### Installation

1. Download the APK that matches your device architecture.
2. Open the downloaded APK.
3. Allow installation from this source if Android asks.
4. Finish installation and launch MARCO RX6.

**Minimum requirement:** Android 8.0 (API 26).

---

## Build verification

The GitHub Actions build for commit `c7acd8875e2d85b411d487bd383f824a8f2fcc57` completed successfully, including the build job and its configured checks.

Build run: https://github.com/XPANGWAN/Marco-RX6/actions/runs/43642751693

**Important:** GitHub Actions artifacts are temporary. Upload the final APK files to this GitHub Release before sharing the release link.
