# Yogi Launcher: everything Android starts from the manifest is kept automatically.
# Keep our classes' names readable in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Hidden framework APIs we call through reflection are not ours, nothing to keep.
-dontwarn org.xmlpull.v1.**
