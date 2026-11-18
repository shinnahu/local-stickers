# Keep the content provider WhatsApp talks to.
-keep class com.localstickers.app.provider.StickerContentProvider { *; }

# kotlinx.serialization: keep generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.localstickers.app.**$$serializer { *; }
-keepclassmembers class com.localstickers.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.localstickers.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
