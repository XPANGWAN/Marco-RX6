# MARCO RX6 8.27

## Navigation and update checks
- Fix Back handling so the main library can close normally, while secondary full-screen pages return to the library.
- Check for stable app updates after the screen settles, with a 15-minute interval between successful automatic checks.
- Keep failed network/API checks retryable on a later foreground visit.
- Preserve the official website download route and Android DownloadManager installation flow.
- No intentional changes to Google Drive, Blogger, authentication, game downloads, or existing site links.

## التنقل وفحص التحديثات
- إصلاح زر الرجوع ليتمكن المستخدم من الخروج من المكتبة الرئيسية، مع العودة إلى المكتبة من الصفحات الفرعية.
- فحص التحديثات المستقرة بعد استقرار الشاشة، بفاصل 15 دقيقة بين عمليات الفحص الناجحة.
- إبقاء محاولات الفحص الفاشلة قابلة لإعادة المحاولة عند العودة إلى التطبيق.
- الحفاظ على رابط التنزيل الرسمي وآلية تنزيل وتثبيت APK في Android.
- لا توجد تغييرات مقصودة على Google Drive أو Blogger أو تسجيل الدخول أو تنزيل الألعاب أو روابط المواقع الحالية.
