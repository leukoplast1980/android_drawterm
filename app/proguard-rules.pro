# The native library registers methods on Native by name and calls back into
# NativeCallbacks by name, so neither class may be renamed or stripped.
-keep class com.claude.drawterm.session.Native { *; }
-keep class com.claude.drawterm.session.NativeCallbacks { *; }
