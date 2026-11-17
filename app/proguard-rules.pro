# Stacktraces legíveis em builds release com R8
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Room (@Database, @Dao, @Entity, @Embedded, *Database_Impl, construtores e campos de entidades)
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.RoomOpenHelper$Delegate { *; }
-keep class **.*Database_Impl { *; }
-keep class **.*Dao_Impl { *; }
-keep class com.studydock.app.data.StudyDatabase { *; }
-keep class com.studydock.app.data.*_Impl { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Entity class * {
    <init>(...);
    <fields>;
}
-keepclassmembers @androidx.room.Entity class * {
    <init>(...);
    <fields>;
    <methods>;
}
-keepclassmembers class * {
    @androidx.room.Embedded <fields>;
    @androidx.room.Relation <fields>;
    @androidx.room.TypeConverter <methods>;
}
-keep class com.studydock.app.data.CardWithRoundEntry { *; }
-keep class com.studydock.app.data.RoundCard { *; }
-keep class com.studydock.app.data.NoteLinkHeader { *; }
-keep class com.studydock.app.data.GradedCardResult { *; }
-keep class com.studydock.app.data.ReviewOutcome { *; }
-keep class com.studydock.app.data.FsrsCalibrationResult { *; }
-dontwarn androidx.room.paging.**

# Kotlin & Coroutines
-keep class kotlin.Metadata { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.flow.**
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn java.lang.instrument.Instrumentation
-dontwarn sun.misc.Signal
-dontwarn sun.misc.SignalHandler

# ViewModel & Lifecycle
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# AppWidgets (AppWidgetProvider - o projeto não usa androidx.glance)
-keep class * extends android.appwidget.AppWidgetProvider {
    public <init>();
    public void onUpdate(...);
    public void onReceive(...);
    public void onEnabled(...);
    public void onDisabled(...);
    public void onDeleted(...);
}
-keep class com.studydock.app.ui.ReviewWidget { *; }
-keep class com.studydock.app.ui.ReviewTrendWidget { *; }
-keep class com.studydock.app.ui.StudyTimerWidget { *; }

# Activity, FileProvider, Services & Receivers declarados no Manifest
-keep public class com.studydock.app.MainActivity { *; }
-keep class androidx.core.content.FileProvider { *; }
-keep class * extends android.app.Service {
    public <init>();
}
-keep class com.studydock.app.audio.FocusAudioService { *; }
-keep class com.studydock.app.timer.StudySessionService { *; }
-keep class com.studydock.app.timer.StudyTimerTileService { *; }
-keep class com.studydock.app.data.AutoBackupJobService { *; }
-keep class * extends android.content.BroadcastReceiver {
    public <init>();
    public void onReceive(...);
}
-keep class com.studydock.app.habits.HabitReminderReceiver { *; }
-keep class com.studydock.app.habits.BootReceiver { *; }

# SecretStore & Android KeyStore
-keep class com.studydock.app.data.SecretStore { *; }
-keep class com.studydock.app.data.SecretStore$** { *; }
-keep class com.studydock.app.data.SecretStatus { *; }
-keep class com.studydock.app.data.SecretReadResult { *; }
-keep class android.security.keystore.** { *; }
-keep class javax.crypto.** { *; }
