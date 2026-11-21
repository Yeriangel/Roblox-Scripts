# Hilt, Room, Compose, DataStore and kotlinx.serialization ship their own consumer rules.

# kotlinx.serialization: keep generated serializers of our @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.diagnosepro.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.diagnosepro.app.**$$serializer { *; }
-keepclassmembers class com.diagnosepro.app.** {
    *** Companion;
}

# Enums are stored by name in DataStore / Room JSON (valueOf / enumValues).
-keepclassmembers enum com.diagnosepro.app.** { *; }

# BluetoothDevice.createRfcommSocket(int) is called by reflection (fallback for clone adapters);
# it is a framework method, so nothing in our code needs keeping, but don't warn about it.
-dontwarn android.bluetooth.**
