# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class net.snakepit.sidewinder.**$$serializer { *; }
-keepclassmembers class net.snakepit.sidewinder.** { *** Companion; }
-keepclasseswithmembers class net.snakepit.sidewinder.** { kotlinx.serialization.KSerializer serializer(...); }
