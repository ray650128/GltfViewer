package com.ray650128.gltfviewer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.filament.utils.KtxLoader
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.ray650128.gltfviewer.renderable.DownloadUtil
import com.ray650128.gltfviewer.renderable.Result
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLConnection
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    companion object {
        // Load the library for the utility layer, which in turn loads gltfio and the Filament core.
        init { Utils.init() }

        private const val LOAD_EXTERNAL_STORAGE = 0x101
        private const val PERMISSION_FOR_READ_LOCAL_FILE = 0x201
        private const val PERMISSION_FOR_DOWNLOAD_FILE = 0x202
    }

    private lateinit var choreographer: Choreographer
    private val frameScheduler = FrameCallback()
    private lateinit var modelViewer: ModelViewer
    private val doubleTapListener = DoubleTapListener()
    private lateinit var doubleTapDetector: GestureDetector


    private lateinit var modelLoadHandler: ModelLoadHandler

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelLoadHandler = ModelLoadHandler(this)

        choreographer = Choreographer.getInstance()

        doubleTapDetector = GestureDetector(applicationContext, doubleTapListener)

        modelViewer = ModelViewer(surfaceView)

        surfaceView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)
            doubleTapDetector.onTouchEvent(event)
            true
        }

        btnLoad.setOnClickListener {
            checkPermission(PERMISSION_FOR_READ_LOCAL_FILE)
        }

        btnLoadUrl.setOnClickListener {
            checkPermission(PERMISSION_FOR_DOWNLOAD_FILE)
        }

        createIndirectLight()

        val dynamicResolutionOptions = modelViewer.view.dynamicResolutionOptions
        dynamicResolutionOptions.enabled = true
        modelViewer.view.dynamicResolutionOptions = dynamicResolutionOptions

        val ssaoOptions = modelViewer.view.ambientOcclusionOptions
        ssaoOptions.enabled = true
        modelViewer.view.ambientOcclusionOptions = ssaoOptions

        val bloomOptions = modelViewer.view.bloomOptions
        bloomOptions.enabled = true
        modelViewer.view.bloomOptions = bloomOptions
    }

    private fun createRenderables(uri: Uri?) {
        if(uri == null) return

        when(uri.scheme) {
            "content" -> try {
                    val buffer = contentResolver.openInputStream(uri).use { input ->
                        val bytes = ByteArray(input!!.available())
                        input.read(bytes)
                        ByteBuffer.wrap(bytes)
                    }

                    modelViewer.loadModelGltfAsync(buffer) { readCompressedAsset("models/$it") }
                    modelViewer.transformToUnitCube()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            "file" -> try {
                val file = File(uri.path)
                val buffer = FileInputStream(file).use { input ->
                    val bytes = ByteArray(input.available())
                    input.read(bytes)
                    ByteBuffer.wrap(bytes)
                }

                modelViewer.loadModelGltfAsync(buffer) { readCompressedAsset("models/$it") }
                modelViewer.transformToUnitCube()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun createIndirectLight() {
        val engine = modelViewer.engine
        val scene = modelViewer.scene
        val ibl = "default_env"
        readCompressedAsset("envs/$ibl/${ibl}_ibl.ktx").let {
            scene.indirectLight = KtxLoader.createIndirectLight(engine, it)
            scene.indirectLight!!.intensity = 30_000.0f
        }
        readCompressedAsset("envs/$ibl/${ibl}_skybox.ktx").let {
            scene.skybox = KtxLoader.createSkybox(engine, it)
        }
    }

    private fun readCompressedAsset(assetName: String): ByteBuffer {
        val input = assets.open(assetName)
        val bytes = ByteArray(input.available())
        input.read(bytes)
        return ByteBuffer.wrap(bytes)
    }

    override fun onResume() {
        super.onResume()
        choreographer.postFrameCallback(frameScheduler)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameScheduler)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameScheduler)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            LOAD_EXTERNAL_STORAGE -> {      // 存取結果
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.data?.let {
                        createRenderables(it)
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,  permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_FOR_READ_LOCAL_FILE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadModelFromLocal()
                }
                return
            }
            PERMISSION_FOR_DOWNLOAD_FILE-> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadModelFromUrl()
                }
                return
            }
        }
    }

    private fun checkPermission(requestCode: Int) {
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("我真的沒有要做壞事, 給我權限吧?")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(this@MainActivity,
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            requestCode)
                    }
                    .setNegativeButton("No") { _, _ -> finish() }
                    .show()
            } else {
                ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    requestCode)
            }
        } else {
            when(requestCode) {
                PERMISSION_FOR_READ_LOCAL_FILE -> loadModelFromLocal()
                PERMISSION_FOR_DOWNLOAD_FILE -> loadModelFromUrl()
            }
        }
    }

    private fun loadModelFromLocal() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        startActivityForResult(Intent.createChooser(intent, null), LOAD_EXTERNAL_STORAGE)
    }

    private fun loadModelFromUrl() {
        val url = "https://storage.googleapis.com/ar-answers-in-search-models/static/Tiger/model.glb"
        DownloadUtil(this).execute(modelLoadHandler, url)
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        private val startTime = System.nanoTime()
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)

            modelViewer.animator?.apply {
                if (animationCount > 0) {
                    val elapsedTimeSeconds = (frameTimeNanos - startTime).toDouble() / 1_000_000_000
                    applyAnimation(0, elapsedTimeSeconds.toFloat())
                }
                updateBoneMatrices()
            }

            modelViewer.render(frameTimeNanos)
        }
    }

    // Just for testing purposes, this releases the model and reloads it.
    inner class DoubleTapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent?): Boolean {
            modelViewer.destroyModel()
            //createRenderables()
            return super.onDoubleTap(e)
        }
    }

    internal class ModelLoadHandler(activity: Activity) : Handler() {
        private val activity: WeakReference<Activity> = WeakReference(activity)
        override fun handleMessage(msg: Message) {
            val mActivity = activity.get() as MainActivity
            mActivity.handleMessage(msg)
        }
    }

    private fun handleMessage(msg: Message) {
        val obj = "file://${msg.obj}"
        createRenderables(Uri.parse(obj))
    }
}