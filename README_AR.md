# Dental Chair Display — Final Stable 2.0

مستودع Android TV كامل لتطبيق شاشة كرسي Dental Chain.

## الميزات الموجودة
- تصميم Dental Chair Display UI 2.0.
- الوضع الداكن والفاتح من الكونترولر.
- اكتشاف الكونترولر تلقائيًا عبر UDP ثم فحص الشبكة المحلية.
- شاشة الترحيب الرئيسية.
- استقبال اسم المريض واسم الطبيب تلقائيًا.
- التنقل بالريموت يمينًا ويسارًا بين Home وPatient وServices وQR وGame.
- لعبة سباق سيارات تعمل بأسهم الريموت.
- عرض SOPRO والصور والبانوراما.
- تكبير وتصغير وتحريك وتدوير الصورة 20 درجة.
- GIF علاجي مع حفظ محلي بعد أول تحميل.
- فيديو وPDF.
- QR الموعد مع تنبيه قبل 24 ساعة.
- أيقونة تطبيق Android TV.

## البناء
GitHub Actions لا يعتمد على `gradle-wrapper.jar`.
يقوم بتثبيت Gradle 8.11.1 وAndroid SDK 35 ثم يبني من الصفر.

اسم Artifact المتوقع:
`Dental-Chair-Display-FINAL-STABLE-2.0`

داخل التطبيق سيظهر:
`FINAL STABLE • DISPLAY 2.0`

## الرفع
ارفع محتويات هذا المجلد مباشرة إلى جذر مستودع:
`Dental-Chair-Display`

يجب أن يظهر في الجذر مباشرة:
- app/
- gradle/
- .github/
- build.gradle.kts
- settings.gradle.kts
- gradle.properties
