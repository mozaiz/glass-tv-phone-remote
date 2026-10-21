# Injector is started by class name through app_process on the box, so it must keep its name and main().
-keep class com.zmk.tvremote.Injector {
    public static void main(java.lang.String[]);
}
