package com.github.archmap

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class FileChangeListener : BulkFileListener {

    private val log = Logger.getInstance(FileChangeListener::class.java)

    override fun after(events: MutableList<out VFileEvent>) {
        // Will be used for real-time updates in a later step
    }
}
