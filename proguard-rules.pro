# Сохраняем классы активностей и сервисов, чтобы Android их находил
-keep class com.rutbat.ardmoney.core.MainActivity { *; }
-keep class com.rutbat.ardmoney.core.UpdateInfoActivity { *; }
-keep class com.rutbat.ardmoney.core.NoInternetActivity { *; }
-keep class com.rutbat.ardmoney.splashscreen.SplashActivity { *; }
-keep class com.rutbat.ardmoney.fcm.FCMService { *; }
-keep class com.rutbat.ardmoney.ArdMoneyApp { *; }

# Сохраняем именованный внутренний класс для JavaScript-интерфейса
-keep class com.rutbat.ardmoney.core.MainActivity$AndroidJsInterface { *; }
-keepclassmembers class com.rutbat.ardmoney.core.MainActivity$AndroidJsInterface {
    public *;
}

# Сохраняем атрибуты для корректной работы стека вызовов в логах
-keepattributes SourceFile,LineNumberTable

# Обфусцируем остальные классы и члены, включая AppConfig
-keep class com.rutbat.ardmoney.config.AppConfig {
    public static final *;
}
-dontwarn com.rutbat.ardmoney.config.AppConfig

# Предотвращаем удаление неиспользуемых классов, которые могут быть вызваны через рефлексию
-keep class androidx.** { *; }
-keep class com.google.** { *; }

# Сохраняем класс AnnotatedType и связанные с ним классы рефлексии
-keep class java.lang.reflect.AnnotatedType { *; }
-keep class java.lang.reflect.** { *; }

# Сохраняем все классы Guava, связанные с рефлексией
-keep class com.google.common.reflect.** { *; }
-dontwarn com.google.common.reflect.**

# Сохраняем аннотации и их использование
-keepattributes *Annotation*
-keepattributes Signature

# Уменьшаем логирование в релизной сборке (опционально)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Дополнительные правила для Firebase и других библиотек (при необходимости)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Сохраняем классы, связанные с Kotlin и Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**