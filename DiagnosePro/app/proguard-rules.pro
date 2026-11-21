# Debug build is not minified. Keep serialization metadata if minify is enabled later.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.diagnosepro.app.**$$serializer { *; }
