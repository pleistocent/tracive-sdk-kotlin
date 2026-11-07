# A host that has kxml2 on its classpath, as androidx.test brings, makes R8 rename its copy of this
# interface. Compose's vector drawables then call the framework's parser through the renamed
# interface, and the app crashes with IncompatibleClassChangeError as soon as a screen opens.
-keep interface org.xmlpull.v1.XmlPullParser { *; }
