# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/simon/SDKs/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}


#TODO: this also fixes a crash where a method is obfuscated when it shouldn't be - need to fix
-dontobfuscate # we want useful stack traces, and we're open-source anyway (obfuscating only saves around 80KB)

#--------------------------------------

# FST
#TODO: these do not quite work yet - serialized media does not work when enabling ProGuard
-dontwarn javassist.**
-dontwarn org.nustaq.**
-dontwarn org.orbjenesis.**
-dontwarn sun.misc.Unsafe

#--------------------------------------

# Okhttp
-dontwarn okio.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault

#--------------------------------------

# Fresco
# Keep our interfaces so they can be used by other ProGuard rules.
# See http://sourceforge.net/p/proguard/bugs/466/
-keep,allowobfuscation @interface com.facebook.common.internal.DoNotStrip

# Do not strip any method/class that is annotated with @DoNotStrip
-keep @com.facebook.common.internal.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.common.internal.DoNotStrip *;
}

# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

-dontwarn okio.**
-dontwarn com.squareup.okhttp.**
-dontwarn okhttp3.**
-dontwarn javax.annotation.**
-dontwarn com.android.volley.toolbox.**
-dontwarn com.facebook.infer.**
