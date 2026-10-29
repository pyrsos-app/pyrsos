# SQLCipher's native code calls back into these classes by name.
-keep class net.zetetic.database.** { *; }
-keep class androidx.sqlite.** { *; }

# Release builds write nothing to the system log: it outlives the app and a forensic copy of the
# phone can read it (when two phones met, how many envelopes, what failed).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
