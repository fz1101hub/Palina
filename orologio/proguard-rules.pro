# Regole per R8 (minificazione dell'APK per l'orologio).

# Il widget si collega al suo servizio leggendo l'annotazione del servizio
# (AssociateWithGlanceWearWidget) e confrontando i nomi delle classi.
-keepattributes RuntimeVisibleAnnotations
-keepnames class * extends androidx.glance.wear.GlanceWearWidget
-keep class * extends androidx.glance.wear.GlanceWearWidgetService { <init>(); }

# Le classi della Wear SDK ci sono solo sugli orologi (useLibrary "wear-sdk").
-dontwarn com.google.wear.services.**
