package com.retrobox.tv

import android.app.Application
import com.retrobox.tv.game.AppRomRepository
import com.retrobox.tv.input.ControllerMonitor
import com.retrobox.tv.library.LibraryService
import com.retrobox.tv.library.SqlLibraryStore
import com.retrobox.tv.util.ErrorLog

class RetroBoxApp : Application() {
    /** Manual wiring: no DI framework (see ARCHITECTURE.md §4). */
    lateinit var controllerMonitor: ControllerMonitor
        private set

    /** The ROM library (folders, scan jobs, game list). Created on first use, which also starts the first scan. */
    val library: LibraryService by lazy { LibraryService(this, SqlLibraryStore(this), AppRomRepository(this).roots) }

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        controllerMonitor = ControllerMonitor(this).also { it.start() }
    }
}
