# JNI-accessed fields and constructors in libxmp model classes
-keepclassmembers class com.lossydragon.native.model.** {
    <fields>;
    <init>(...);
}

# Native method declarations
-keepclasseswithmembers class com.lossydragon.native.** {
    native <methods>;
}
