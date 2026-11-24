# JNI-accessed fields and constructors in libxmp model classes
-keepclassmembers class com.lossydragon.renderer.model.** {
    <fields>;
    <init>(...);
}

# Native method declarations
-keepclasseswithmembers class com.lossydragon.renderer.** {
    native <methods>;
}
