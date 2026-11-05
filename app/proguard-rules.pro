# Rules that only the app needs. Libraries keep what their native code looks up in their own consumer-rules.pro.

# Navigation rebuilds the enum arguments of a route (FolderRoute.groupKind, SettingsRoute.opening) from the serial
# name, which is the class name, with Class.forName. R8 renames the enums otherwise.
-keepnames enum io.github.yuubiz.hirari.**
