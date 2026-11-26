package com.hsteex.rawviewer

import android.app.Application
import com.hsteex.rawviewer.data.Db
import com.hsteex.rawviewer.data.ImageRepo
import com.hsteex.rawviewer.data.ImportWorker
import com.hsteex.rawviewer.data.Prefs

class App : Application() {
    lateinit var db: Db
        private set
    lateinit var images: ImageRepo
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        db = Db(this)
        images = ImageRepo(this, db)
        prefs = Prefs(this)
        com.hsteex.rawviewer.data.DebugLog.enabled = prefs.debugLog.value
        ImportWorker.createChannel(this)
    }
}
