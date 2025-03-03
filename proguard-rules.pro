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

# Уменьшаем логирование в релизной сборке (опционально)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}