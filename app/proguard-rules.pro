# Reflection on the hidden Bluetooth battery getter is guarded and optional.
-dontwarn android.bluetooth.**
# Keep the app's services and receivers resolvable by the manifest.
-keep class com.lattice.app.** extends android.app.Service { *; }
-keep class com.lattice.app.** extends android.content.BroadcastReceiver { *; }
# JTransforms (jlargearrays) references a JDK-internal class that does not exist on Android; never reached.
-dontwarn sun.misc.Cleaner
-dontwarn pl.edu.icm.jlargearrays.**
