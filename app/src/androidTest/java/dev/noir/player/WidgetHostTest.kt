package dev.noir.player

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class WidgetHostTest {
    @Test fun hostedWidgetShowsCoverAndItsLessButtonCanUndo() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val library=(context.applicationContext as NoirApp).library
        val tracks=runBlocking {withTimeout(10000){library.tracks.first{it.size==3}}}
        val track=tracks[0];val original=library.preferences.value.lessTrack(track.id)
        val cover=runBlocking {library.cover(track)}!!
        val bytes=java.io.ByteArrayOutputStream().also {cover.compress(Bitmap.CompressFormat.JPEG,80,it)}.toByteArray()
        val host=AppWidgetHost(context,901)
        var widgetId=0
        lateinit var view:AppWidgetHostView
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun waitFor(check:()->Boolean) {
            val end=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<end) {var ready=false;main{ready=check()};if(ready)return;Thread.sleep(50)}
            fail("Widget host did not receive the expected update")
        }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {activity ->
                library.setLessTrack(track.id,false)
                widgetId=host.allocateAppWidgetId()
                val manager=AppWidgetManager.getInstance(context)
                assertTrue("Grant bind only on the dedicated test emulator",manager.bindAppWidgetIdIfAllowed(widgetId,ComponentName(context,NoirWidget::class.java)))
                host.startListening()
                view=host.createView(activity,widgetId,manager.getAppWidgetInfo(widgetId))
                activity.addContentView(view,FrameLayout.LayoutParams(-1,(190*activity.resources.displayMetrics.density).toInt(),android.view.Gravity.BOTTOM))
                NoirWidget.update(context,track.toMediaItem().mediaMetadata.buildUpon().setArtworkData(bytes,3).build(),false,track.id)
            }
            waitFor{view.findViewById<TextView>(R.id.widget_title)?.text?.toString()==track.title && view.findViewById<ImageView>(R.id.widget_cover)?.drawable is android.graphics.drawable.BitmapDrawable}
            main {view.findViewById<TextView>(R.id.widget_less).performClick()}
            waitFor{library.preferences.value.lessTrack(track.id) && view.findViewById<TextView>(R.id.widget_less).text.toString().startsWith("Отменить")}
            main {view.findViewById<TextView>(R.id.widget_less).performClick()}
            waitFor{!library.preferences.value.lessTrack(track.id)}
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null),"noir-widget-v090.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
        } finally {
            main {library.setLessTrack(track.id,original);host.deleteHost();host.stopListening()}
            scenario.close()
        }
    }
}
