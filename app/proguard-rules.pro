# Shrink only — obfuscation is off so the in-app crash reporter
# (Settings -> Last crash report) keeps producing readable stack traces.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Entry points referenced from the manifest are kept by AGP's default rules;
# nothing here uses reflection, serialisation, or JNI.
