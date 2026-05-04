# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep JavaScript interface classes and methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep JsBridge class and all its methods (used for WebView JavaScript interface)
-keep class com.avis.srs.app.MainActivity$JsBridge {
    *;
}

# Keep all classes in the app package (to be safe)
-keep class com.avis.srs.app.** { *; }

# Keep Capacitor classes
-keep class com.getcapacitor.** { *; }
-dontwarn com.getcapacitor.**

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Keep Firebase Messaging
-keep class com.google.firebase.messaging.** { *; }
-keep class com.google.firebase.iid.** { *; }

# Keep WorkManager classes
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Keep JSON classes (used for parsing)
-keep class org.json.** { *; }

# Keep WebView related classes
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep R class
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep annotation default values
-keepattributes AnnotationDefault

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep custom exceptions
-keep public class * extends java.lang.Exception

# Keep BroadcastReceiver classes
-keep public class * extends android.content.BroadcastReceiver

# Keep Service classes
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Keep Activity classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep reflection-based code
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
