# The Drive SDK's native glue (global.c) resolves Kotlin callback methods by name at runtime with
# GetMethodID on the bindings object's class, and fails fatally when one is missing. The SDK's
# consumer rules keep only the protobuf classes, so its Kotlin classes are kept whole here.
-keep class me.proton.drive.sdk.** { *; }
-keep interface me.proton.drive.sdk.** { *; }
