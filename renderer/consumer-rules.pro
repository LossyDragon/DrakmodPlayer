# JNI-accessed fields and constructors in libxmp model classes
-keepclassmembers class org.helllabs.libxmp.model.** {
    <fields>;
    <init>(...);
}

# Native method declarations
-keepclasseswithmembers class org.helllabs.libxmp.** {
    native <methods>;
}