package dev.noir.player;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.io.File;
import android.os.ParcelFileDescriptor;
import java.nio.ByteBuffer;

/** Android-only harness: works even when R8 renamed the target's Kotlin runtime. */
public final class ReleaseProbeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            String names = arguments.getString("verifyClasses", "dev.noir.player.UsbAudioOutput");
            for (String name : names.split(",")) {
                result.putString("lastClass", name);
                Class<?> klass;
                try { klass = Class.forName(name, false, context.getClassLoader()); }
                catch (ClassNotFoundException removedMappingEntry) { continue; }
                klass.getDeclaredMethods();
                klass.getDeclaredConstructors();
            }
            String hardwareName = arguments.getString("hardwareClass");
            if (hardwareName != null) {
                Class<?> hardware = Class.forName(hardwareName, true, context.getClassLoader());
                java.lang.reflect.Constructor<?> constructor = hardware.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                try { constructor.newInstance(context); throw new AssertionError("No-device constructor unexpectedly succeeded"); }
                catch (InvocationTargetException error) {
                    if (!(error.getCause() instanceof IllegalStateException)) throw error;
                    result.putString("deviceError", error.getCause().toString());
                }
            }
            if (arguments.getString("nativeSmoke") != null) {
                Class<?> nativeApi = Class.forName("dev.noir.player.NativeUsb", true, context.getClassLoader());
                Object api = nativeApi.getField("INSTANCE").get(null);
                File file = new File(context.getCacheDir(), "release-native-probe");
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);
                long handle = ((Number) nativeApi.getMethod("create", int.class).invoke(api, descriptor.getFd())).longValue();
                try {
                    ByteBuffer bytes = ByteBuffer.allocateDirect(16);
                    Object count = nativeApi.getMethod("feedLive", long.class, ByteBuffer.class, int.class, int.class).invoke(api, handle, bytes, 0, 16);
                    if (!Integer.valueOf(16).equals(count)) throw new AssertionError("Wrong native feed count");
                    Object buffered = nativeApi.getMethod("liveBuffered", long.class).invoke(api, handle);
                    if (!Long.valueOf(16).equals(buffered)) throw new AssertionError("Wrong native buffered size");
                    nativeApi.getMethod("livePosition", long.class).invoke(api, handle);
                    nativeApi.getMethod("finishLive", long.class).invoke(api, handle);
                    nativeApi.getMethod("resetLive", long.class).invoke(api, handle);
                } finally { descriptor.close(); nativeApi.getMethod("destroy", long.class).invoke(api, handle); file.delete(); }
                result.putString("nativeSmoke", "PASS");
            }
            if (arguments.getString("startupSmoke") != null) {
                Class<?> output = Class.forName("dev.noir.player.UsbAudioOutput", true, context.getClassLoader());
                try { output.getConstructor(Context.class).newInstance(context); throw new AssertionError("No-device output unexpectedly succeeded"); }
                catch (InvocationTargetException error) {
                    if (!(error.getCause() instanceof IllegalStateException)) throw error;
                    result.putString("startupSmoke", error.getCause().toString());
                }
            }
            result.putString("result", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("failure", Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
