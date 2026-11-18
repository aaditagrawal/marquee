# kotlinx.serialization: keep generated serializers for our DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.mav.marquee.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.mav.marquee.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# PlayerActivity is launched by class name from the browse UI.
# Keep the class-name Intent contract and its framework constructor. Let R8
# optimize/shrink private implementation methods rather than retaining all OSD helpers.
-keep,allowoptimization class com.mav.marquee.player.PlayerActivity {
    public <init>();
}
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
