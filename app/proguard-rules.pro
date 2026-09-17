# DynamicONE Driver release rules.
# kotlinx.serialization, Retrofit, OkHttp, Room and Hilt ship their own consumer rules.

# Keep @Serializable navigation routes and DTO serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.dynamictransport.driver.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
