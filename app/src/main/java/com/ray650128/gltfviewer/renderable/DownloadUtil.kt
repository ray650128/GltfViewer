package com.ray650128.gltfviewer.renderable

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import java.io.*
import java.net.URL
import java.net.URLConnection

class DownloadUtil(private val context: Context) {

    private var mainHandler: Handler? = null
    private var urlStr = ""
    private var filename: String? = null

    private val downloadRunnable = Runnable {
        var count: Int
        try {
            val root: String = context.externalCacheDir.toString()
            println("${Thread.currentThread().name} Downloading")
            val url = URL(urlStr)
            val connection: URLConnection = url.openConnection()
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()
            // getting file length
            val lengthOfFile: Int = connection.contentLength
            Log.e("${Thread.currentThread().name} Download", "Remote File size: $lengthOfFile")

            val file = File("$root/$filename")
            val tmpFile = File("$root/$filename.tmp")
            if(file.exists()) {
                if(tmpFile.exists()) {
                    tmpFile.delete()
                } else {
                    //mainHandler?.sendEmptyMessage(1)
                    val msg = mainHandler?.obtainMessage()?.apply {
                        obj = "$root/$filename"
                    }
                    mainHandler?.sendMessage(msg)
                    Log.e("${Thread.currentThread().name} Download", "Finish form local.${msg?.obj}")
                }
                return@Runnable
            }

            // input stream to read file - with 8k buffer
            val input: InputStream = BufferedInputStream(url.openStream(), 8192)

            // Output stream to write file
            val output: OutputStream = FileOutputStream(tmpFile)
            val data = ByteArray(1024)
            var total: Long = 0
            while (input.read(data).also { count = it } != -1) {
                total += count.toLong()

                // writing data to file
                output.write(data, 0, count)
            }

            // flushing output
            output.flush()

            // closing streams
            output.close()
            input.close()
            tmpFile.renameTo(file)
            //mainHandler?.sendEmptyMessage(1)
            val msg = mainHandler?.obtainMessage()?.apply {
                obj = "$root/$filename"
            }
            mainHandler?.sendMessage(msg)
            Log.e("${Thread.currentThread().name} Download", "Finish.${msg?.obj}")
        } catch (e: Exception) {
            Log.e("${Thread.currentThread().name} Download Error: ", e.message)
        }
    }

    private val handlerThread = HandlerThread("DownloadUtil")
    private var bgHandler: Handler? = null

    init {
        handlerThread.start()
        bgHandler = Handler(handlerThread.looper)
    }

    private fun getFilename(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val slashIndex = url.lastIndexOf('/')
        return url.substring(slashIndex + 1)
    }

    fun execute(handler: Handler, url: String) {
        urlStr = url
        mainHandler = handler
        filename = getFilename(urlStr)

        bgHandler?.post(downloadRunnable)
    }

    fun stop() {
        handlerThread.quitSafely()
        bgHandler?.removeCallbacks(downloadRunnable)
        bgHandler = null
        mainHandler = null
    }
}

data class Result(val localUri: String)