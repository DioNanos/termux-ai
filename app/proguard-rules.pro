# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# LiteRT-LM: the native library calls back into these classes by name and the AAR ships no consumer rules.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# android-control MCP stdio bridge: started by name through app_process from the termux-ai-mcp wrapper,
# so nothing in the APK references it. The release build must keep the entry point (to be qualified on ART in CI).
-keep class com.termux.app.mcp.McpStdioShim {
    public static void main(java.lang.String[]);
}
