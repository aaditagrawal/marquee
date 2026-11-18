# Release builds only (not the local `profile` build): remove verbose/debug/info logging. These calls
# carry playback/startup timing markers and item identifiers used for on-device diagnostics; warnings
# and errors (which never include tokens or full stream URLs) are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}
