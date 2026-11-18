# MARCO RX6 8.24

## Update system improvements
- Reduced automatic update check interval from 24 hours to 3 hours.
- Only valid update API responses are recorded as completed checks.
- Network errors, invalid JSON, and malformed update responses remain retryable without waiting for the next interval.
- Preserved device ABI selection for ARM64, ARM32, and Universal APK downloads.

## الإصلاحات
- تقليل مدة الفحص التلقائي للتحديثات من 24 ساعة إلى 3 ساعات.
- تسجيل وقت الفحص فقط بعد استلام بيانات تحديث صحيحة.
- أخطاء الاتصال أو البيانات غير الصالحة لا تمنع إعادة المحاولة.
- الحفاظ على اختيار ملف APK المناسب لمعمارية الجهاز.
