# Η γέφυρα που καλεί η JavaScript πρέπει να μείνει ανέπαφη.
-keepclassmembers class gr.mastoremata.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
-dontobfuscate
