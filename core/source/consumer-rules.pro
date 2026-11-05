# smbj 0.15.0 posts its connection and session events through mbassador 1.3.2, which ships no rules of its own. It
# finds the handlers by scanning for the @Handler annotation (and the defaults of the annotation's elements), then
# builds the invocation of each handler by calling the constructor that takes a SubscriptionContext.
-keepattributes *Annotation*,AnnotationDefault
-keep @interface net.engio.mbassy.listener.*
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keepclassmembers class * implements net.engio.mbassy.dispatch.IHandlerInvocation {
    <init>(net.engio.mbassy.subscription.SubscriptionContext);
}

# mbassador's optional EL filters and smbj's Kerberos authenticator name classes that Android does not have. The
# filters are never used, and the places sign in with a password or as a guest, which is not Kerberos.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
