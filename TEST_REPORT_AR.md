# تقرير فحص DTDC Display 5.4.0

تمت مراجعة منطق الاتصال والتأكد من أن النسخة:

1. تراقب تغير الشبكة عبر ConnectivityManager.NetworkCallback.
2. تحذف last_ws_url عند تغير الشبكة.
3. لا تستخدم IP محفوظاً إذا لم يكن ضمن نفس شبكة الجهاز الحالية.
4. تعود إلى UDP discovery وsubnet scan بعد فشل الاتصال المتكرر.
5. تعرض الشريط العلوي داخل HomeScreen فقط.
6. ترسل `client_hello` بدور `display` ثم `display_ready` للتوافق الرجعي.
7. اسم ملف Artifact في GitHub Actions مطابق للإصدار 5.4.0.

تعذر تنفيذ Gradle build داخل البيئة لأن Gradle Wrapper احتاج تنزيل gradle-8.11.1 من الإنترنت، والبيئة لا توفر اتصالاً خارجياً. يلزم تشغيل GitHub Actions للتأكد النهائي من Kotlin وبناء APK.
