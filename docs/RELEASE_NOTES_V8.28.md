# MARCO RX6 8.28

## Download links
- Recognize Rootz share pages using the /d/{id} route as browser landing pages.
- Avoid probing Rootz share links with a preliminary HTTP GET before the normal WebView flow, preserving temporary redirects and session state.
- Keep existing download URL validation, ad blocking, and secure redirect checks unchanged.

## روابط التنزيل
- التعرّف على صفحات المشاركة في Rootz التي تستخدم المسار /d/{id}.
- تجنّب إرسال طلب GET استباقي إلى رابط Rootz قبل فتحه في المتصفح الداخلي، للحفاظ على التحويلات المؤقتة وجلسة الموقع.
- الإبقاء على التحقق من روابط الملفات وحظر الإعلانات وفحوصات التحويل الآمن كما هي.
