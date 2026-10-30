package dev.noir.player

import android.app.Application

class NoirApp : Application() {
    lateinit var library: LibraryStore
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        DirectUsbSettings.initialize(this)
        library = LibraryStore(this)
    }
}
