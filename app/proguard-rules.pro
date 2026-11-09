# Native code calls into these by name (JNI), so R8 mustn't rename or drop them
-keep class org.libtorrent4j.** { *; }
-keep class dev.jdtech.mpv.** { *; }
-keep class com.dokar.quickjs.** { *; }
# Watch Together's page calls Room's bridge methods by name
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
