# Keep model/data classes used in JSON (de)serialization for ASR/LLM providers.
-keep class com.wangpan.videohelper.data.remote.** { *; }

# Room entities / enums referenced reflectively by generated Room code and TypeConverters.
-keep class com.wangpan.videohelper.data.db.** { *; }

# Kotlin enum values()/valueOf() are used by the StageStatus TypeConverter.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
