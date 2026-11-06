# Started on the TV with app_process, so R8 can't see it being used.
-keep class dev.daniel.tvhub.tv.KeyServer { public static void main(java.lang.String[]); }
-keep class dev.daniel.tvhub.tv.** { *; }

# dadb (adb client) and its dependencies.
-keep class dadb.** { *; }
-dontwarn org.junit.**
-dontwarn org.opentest4j.**
-dontwarn org.apiguardian.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
