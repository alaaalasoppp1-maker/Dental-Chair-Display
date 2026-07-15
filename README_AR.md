# بناء APK سحابيًا عبر GitHub Actions

انسخ مجلد `.github` الموجود في هذه الحزمة إلى جذر مشروع Android، بجانب:

- app
- gradle
- gradlew
- gradlew.bat
- settings.gradle.kts

## تعديلان ضروريان قبل الرفع

### 1) gradle/libs.versions.toml

استخدم:

```toml
agp = "9.2.0"
```

### 2) gradle/wrapper/gradle-wrapper.properties

يجب ألا يحتوي على مسار C:\Gradle المحلي.

اجعل السطر:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.0-bin.zip
```

## الرفع إلى GitHub

1. أنشئ Repository جديدًا Private.
2. اختر Add file ثم Upload files.
3. ارفع جميع محتويات مشروع Android، بما فيها مجلد `.github`.
4. افتح تبويب Actions.
5. افتح `Build Dental Chair APK`.
6. اضغط Run workflow.

بعد النجاح:

1. افتح آخر عملية بناء.
2. انزل إلى قسم Artifacts.
3. نزّل `Dental-Chair-Display-APK`.
4. فك الضغط لتحصل على `app-debug.apk`.

لا تحتاج المزامنة الناجحة على جهازك لكي يبني GitHub المشروع.
