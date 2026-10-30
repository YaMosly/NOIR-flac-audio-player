package dev.noir.player

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/** Also runnable against the signed, minified APK: no references to renamed app APIs. */
class ReleaseUsbVerificationTest {
    @Test fun usbClassesVerifyInInstalledApkAndMissingDeviceIsAHandledError() {
        assertTrue("This hardware-free check is for the isolated emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(UsbManager::class.java)
        assertTrue(manager.deviceList.isEmpty())
        val args = InstrumentationRegistry.getArguments()
        val names = args.getString("verifyClasses") ?: "dev.noir.player.UsbAudioOutput;dev.noir.player.UsbLiveHardware;dev.noir.player.NativeUsb"
        for (name in names.split(';', ',')) {
            // Metadata inspection alone did not force ART to verify consume() in 0.5.0.
            val klass = Class.forName(name, true, context.classLoader)
            try { klass.declaredMethods; klass.declaredConstructors } catch (failure: Throwable) {
                throw AssertionError("Installed class failed ART verification: $name", failure)
            }
        }
        val hardware = args.getString("hardwareClass") ?: "dev.noir.player.UsbLiveHardware"
        val constructor = Class.forName(hardware, true, context.classLoader).getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        try { constructor.newInstance(context); fail("No device must not create a USB transport") }
        catch (failure: InvocationTargetException) {
            assertTrue("Expected a handled device error, got ${failure.cause}", failure.cause is IllegalStateException)
        }
    }
}
