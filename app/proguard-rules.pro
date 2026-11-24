# Rhino JS engine reflection & JS bridge classes
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.w3c.dom.**
-dontwarn org.xml.sax.**

# Engine JS API & VBook runtime classes exposed to Rhino scripts
-keep class dev.trinhhg.vapp.engine.js.** { *; }
-keep class dev.trinhhg.vapp.engine.api.** { *; }
-keep class dev.trinhhg.vapp.engine.vbook.** { *; }
-keep class dev.trinhhg.vapp.engine.net.** { *; }
-keep class dev.trinhhg.vapp.engine.EngineException { *; }
-keep class dev.trinhhg.vapp.engine.EngineErrorCode { *; }

# WebView @JavascriptInterface bridges (WebViewBrowserBridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Ksoup HTML parser & Kotlinx Serialization
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fleeksoft.ksoup.**
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.trinhhg.vapp.**$$serializer { *; }
-keepclasseswithmembers class dev.trinhhg.vapp.** {
    *** Companion;
}

# Room Database, Entities, DAOs, and TypeConverters
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class dev.trinhhg.vapp.downloader.db.** { *; }
-dontwarn androidx.room.**

# OkHttp, Okio, and Conscrypt/BouncyCastle optional JVM TLS classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Media3 & Foreground Services
-keep class androidx.media3.** { *; }
-keep class dev.trinhhg.vapp.tts.** { *; }

# WorkManager Workers & Koin
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class dev.trinhhg.vapp.downloader.worker.** { *; }
-dontwarn org.koin.**
