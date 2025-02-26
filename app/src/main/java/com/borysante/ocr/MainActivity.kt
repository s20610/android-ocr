package com.borysante.ocr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.borysante.ocr.ocr.ImageTextReader
import com.borysante.ocr.utils.Constants
import com.borysante.ocr.utils.Language
import com.borysante.ocr.utils.SpUtil
import com.borysante.ocr.utils.Utils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.googlecode.tesseract.android.TessBaseAPI.ProgressNotifier
import com.googlecode.tesseract.android.TessBaseAPI.ProgressValues
import com.theartofdev.edmodo.cropper.CropImage
import com.theartofdev.edmodo.cropper.CropImageView
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.stream.Collectors

class MainActivity : AppCompatActivity(), ProgressNotifier {
    private var allLanguageName: ArrayList<String>? = null
    private var dirBest: File? = null
    private var dirStandard: File? = null
    private var dirFast: File? = null
    private var currentDirectory: File? = null
    private var mImageTextReader: ImageTextReader? = null

    /**
     * TrainingDataType: i.e Best, Standard, Fast
     */
    private var mTrainingDataType: String? = null
    private var mPageSegMode = 0
    private var parameters: Map<String, String>? = null

    /**
     * AlertDialog for showing when language data doesn't exists
     */
    private var dialog: AlertDialog? = null
    private var mImageView: ImageView? = null
    private var mProgressIndicator: LinearProgressIndicator? = null
    private var mSwipeRefreshLayout: SwipeRefreshLayout? = null
    private var mFloatingActionButton: FloatingActionButton? = null
    private var mDownloadLayout: LinearLayout? = null

    /**
     * Language name to be displayed
     */
    private var mLanguageName: TextView? = null
    private var executorService: ExecutorService? = null
    private var handler: Handler? = null
    private var mProgressBar: LinearProgressIndicator? = null
    private var mProgressMessage: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SpUtil.getInstance().init(this)

        allLanguageName =
            ArrayList(Arrays.asList(*resources.getStringArray(R.array.ocr_engine_language)))

        mImageView = findViewById(R.id.source_image)
        mProgressIndicator = findViewById(R.id.progress_indicator)
        mSwipeRefreshLayout = findViewById(R.id.swipe_to_refresh)
        mFloatingActionButton = findViewById(R.id.btn_scan)
        mLanguageName = findViewById(R.id.language_name1)

        mProgressBar = findViewById(R.id.progress_bar)
        mProgressMessage = findViewById(R.id.progress_message)
        mDownloadLayout = findViewById(R.id.download_layout)

        executorService = Executors.newFixedThreadPool(1)
        handler = Handler(Looper.getMainLooper())

        initDirectories()
        /*
         * check if this was initiated by shared menu if yes then get the image uri and get the text
         * language will be preselected by user in settings
         */
        initIntent()
        initializeOCR()
        initViews()
    }

    private fun initViews() {
        mFloatingActionButton!!.setOnClickListener { v: View? ->
            if (isNoLanguagesDataMissingFromSet) {
                if (mImageTextReader != null) {
                    selectImage()
                } else {
                    initializeOCR()
                }
            } else {
                downloadLanguageData()
            }
        }
        mSwipeRefreshLayout!!.setOnRefreshListener {
            if (isNoLanguagesDataMissingFromSet) {
                if (mImageTextReader != null) {
                    val drawable = mImageView!!.drawable
                    if (drawable != null) {
                        val bitmap = (drawable as BitmapDrawable).bitmap
                        if (bitmap != null) {
                            isRefresh = true
                            executorService!!.submit(ConvertImageToText(bitmap))
                        }
                    }
                } else {
                    initializeOCR()
                }
            } else {
                downloadLanguageData()
            }
            mSwipeRefreshLayout!!.isRefreshing = false
        }
        if (Utils.isPersistData()) {
            val bitmap = loadBitmapFromStorage()
            if (bitmap != null) {
                mImageView!!.setImageBitmap(bitmap)
            }
        }
    }

    private fun initIntent() {
        val intent = intent
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("image/")) {
                val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (imageUri != null) {
                    mImageView!!.setImageURI(imageUri)
                    showLanguageSelectionDialog(imageUri)
                }
            }
        } else if (action != null && action == "screenshot") {
            // uri
        }
    }

    private fun showLanguageSelectionDialog(imageUri: Uri) {
        // if (this.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
        val frag = ShareFragment.newInstance(imageUri, allLanguageName)
        frag.show(supportFragmentManager, "shareFrag")

        // }
    }

    override fun onResume() {
        super.onResume()
        mLanguageName!!.text =
            Utils.getTrainingDataLanguages(this).stream().map { obj: Language -> obj.name }.collect(
                Collectors.joining(", ")
            )
    }

    /* public void startOCRFromShareMenu(Uri imageUri, Set<Language> languages) {
        initializeOCR(languages);
        Utils.setLastUsedLanguage(this, languages);
        // Log.d("radio", "showLanguageSelectionDialog: " + mLanguage);
        mImageView.setImageURI(imageUri);
        if (isNoLanguagesDataMissingFromSet(mTrainingDataType, languages)) {
            CropImage.activity(imageUri).start(MainActivity.this);
        }
    }*/
    private fun initDirectories() {
        val dirNames = arrayOf("best", "fast", "standard")
        for (dirName in dirNames) {
            val dir = File(getExternalFilesDir(dirName), "tessdata")
            if (dir.mkdirs() || dir.isDirectory) {
                when (dirName) {
                    "best" -> dirBest = dir.parentFile
                    "fast" -> dirFast = dir.parentFile
                    "standard" -> dirStandard = dir.parentFile
                }
            }
        }
        // Set currentDirectory to the last initialized directory (standard)
        currentDirectory = File(dirStandard, "tessdata")
    }


    /**
     * initialize the OCR i.e tesseract api
     * if there is no training data in directory than it will ask for download
     */
    private fun initializeOCR() {
        val languages = Utils.getTrainingDataLanguages(this)
        val cf: File?
        mTrainingDataType = Utils.getTrainingDataType()
        mPageSegMode = Utils.getPageSegMode()
        parameters = Utils.getAllParameters()

        when (mTrainingDataType) {
            "best" -> {
                currentDirectory = File(dirBest, "tessdata")
                cf = dirBest
            }

            "standard" -> {
                cf = dirStandard
                currentDirectory = File(dirStandard, "tessdata")
            }

            else -> {
                cf = dirFast
                currentDirectory = File(dirFast, "tessdata")
            }
        }

        if (isNoLanguagesDataMissingFromSet) {
            startImageTextReaderThread(cf!!, languages)
        } else {
            downloadLanguageData()
        }
    }

    private fun startImageTextReaderThread(cf: File, languages: Set<Language>) {
        Thread {
            try {
                if (mImageTextReader != null) {
                    mImageTextReader!!.tearDownEverything()
                }
                mImageTextReader = ImageTextReader.getInstance(
                    cf.absolutePath,
                    languages,
                    mPageSegMode,
                    parameters,
                    Utils.isExtraParameterSet(),
                    this@MainActivity
                )
                if (mImageTextReader != null && !mImageTextReader!!.isSuccess) {
                    handleReaderException(languages)
                }
            } catch (e: Exception) {
                handleReaderException(languages)
            }
        }.start()
    }

    private fun handleReaderException(languages: Set<Language>) {
        val destFile = File(currentDirectory, String.format(Constants.LANGUAGE_CODE, languages))
        destFile.delete()
        mImageTextReader = null
    }

    private fun downloadLanguageData() {
        val missingLanguage: MutableSet<Language> = HashSet()
        val languages = Utils.getTrainingDataLanguages(this)
        if (!Utils.isNetworkAvailable(application)) {
            Toast.makeText(
                this,
                getString(R.string.you_are_not_connected_to_internet),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        for (l in languages) {
            if (isLanguageDataMissing(mTrainingDataType!!, l)) {
                missingLanguage.add(l)
            }
        }
        val missingLangName = missingLanguage.stream().map { obj: Language -> obj.name }.collect(
            Collectors.joining(", ")
        )
        val msg = String.format(getString(R.string.download_description), missingLangName)
        dialog =
            AlertDialog.Builder(this).setTitle(R.string.training_data_missing).setCancelable(false)
                .setMessage(msg).setPositiveButton(R.string.yes) { dialog, which ->
                    dialog.cancel()
                    executorService!!.submit(DownloadTraining(mTrainingDataType, missingLanguage))
                }.setNegativeButton(R.string.no) { dialog, which -> dialog.cancel() }.create()
        dialog!!.show()
    }

    private val isNoLanguagesDataMissingFromSet: Boolean
        get() {
            val dataType = mTrainingDataType
            val languages = Utils.getTrainingDataLanguages(this)
            for (language in languages) {
                if (isLanguageDataMissing(dataType!!, language)) return false
            }
            return true
        }

    private fun isLanguageDataMissing(dataType: String, language: Language): Boolean {
        currentDirectory = when (dataType) {
            "best" -> File(dirBest, "tessdata")
            "standard" -> File(dirStandard, "tessdata")
            else -> File(dirFast, "tessdata")

        }
        return !File(
            currentDirectory,
            String.format(Constants.LANGUAGE_CODE, language.code)
        ).exists()
    }

    private fun selectImage() {
        CropImage.activity().setGuidelines(CropImageView.Guidelines.ON).start(this)
    }

    private fun convertImageToText(imageUri: Uri) {
        var bitmap: Bitmap? = null
        try {
            bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
        } catch (e: IOException) {
            Log.e(TAG, "convertImageToText: " + e.localizedMessage)
        }
        mImageView!!.setImageURI(imageUri)
        executorService!!.submit(ConvertImageToText(bitmap))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SETTINGS) {
            initializeOCR()
        }
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
                if (isNoLanguagesDataMissingFromSet) {
                    val result = CropImage.getActivityResult(data)
                    if (result != null) {
                        convertImageToText(result.uri)
                    }
                } else {
                    initializeOCR()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService!!.shutdownNow()
        if (dialog != null) {
            dialog!!.dismiss()
            dialog = null
        }
        if (mImageTextReader != null) mImageTextReader!!.tearDownEverything()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val showHistoryItem = menu.findItem(R.id.action_history)
        showHistoryItem.setVisible(Utils.isPersistData())
        return true
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.action_settings) {
            startActivityForResult(
                Intent(this, SettingsActivity::class.java),
                REQUEST_CODE_SETTINGS
            )
        } else if (id == R.id.action_history) {
            showOCRResult(Utils.getLastUsedText())
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onProgressValues(progressValues: ProgressValues) {
        runOnUiThread { mProgressIndicator!!.progress = (progressValues.percent * 1.46).toInt() }
    }

    fun saveBitmapToStorage(bitmap: Bitmap) {
        val fileOutputStream: FileOutputStream
        try {
            fileOutputStream = openFileOutput("last_file.jpeg", Context.MODE_PRIVATE)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 30, fileOutputStream)
            fileOutputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "loadBitmapFromStorage: " + e.localizedMessage)
        }
    }

    fun loadBitmapFromStorage(): Bitmap? {
        var bitmap: Bitmap? = null
        val fileInputStream: FileInputStream
        try {
            fileInputStream = openFileInput("last_file.jpeg")
            bitmap = BitmapFactory.decodeStream(fileInputStream)
            fileInputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "loadBitmapFromStorage: " + e.localizedMessage)
        }
        return bitmap
    }

    fun showOCRResult(text: String?) {
        if (this.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val bottomSheetResultsFragment = BottomSheetResultsFragment.newInstance(text)
            bottomSheetResultsFragment.show(supportFragmentManager, "bottomSheetResultsFragment")
        }
    }

    private inner class ConvertImageToText(bitmap: Bitmap?) : Runnable {
        private var bitmap: Bitmap

        init {
            this.bitmap = bitmap!!
        }

        override fun run() {
            // Pre-execute on UI thread
            handler!!.post {
                mProgressIndicator!!.progress = 0
                mProgressIndicator!!.visibility = View.VISIBLE
                animateImageViewAlpha(0.2f)
            }

            // Background execution
            if (!isRefresh && Utils.isPreProcessImage()) {
                bitmap = Utils.preProcessBitmap(bitmap)
            }
            isRefresh = false
            saveBitmapToStorage(bitmap)
            val text = mImageTextReader!!.getTextFromBitmap(bitmap)

            // Post-execution on UI thread
            handler!!.post {
                mProgressIndicator!!.visibility = View.GONE
                animateImageViewAlpha(1f)
                val cleanText = Html.fromHtml(text).toString().trim { it <= ' ' }
                showOCRResult(cleanText)
                Toast.makeText(
                    this@MainActivity,
                    "With Confidence: " + mImageTextReader!!.accuracy + "%",
                    Toast.LENGTH_SHORT
                ).show()
                Utils.putLastUsedText(cleanText)
                updateImageView()
            }
        }

        fun animateImageViewAlpha(alpha: Float) {
            mImageView!!.animate().alpha(alpha).setDuration(450).start()
        }

        fun updateImageView() {
            val bitmap = loadBitmapFromStorage()
            if (bitmap != null) {
                mImageView!!.setImageBitmap(bitmap)
            }
        }
    }

    private inner class DownloadTraining(dataType: String?, langs: Set<Language>) :
        Runnable {
        private val dataType = dataType!!
        private val languages = langs
        private var size: String? = null

        override fun run() {
            handler!!.post {
                mProgressMessage!!.text = getString(R.string.downloading_language)
                mDownloadLayout!!.visibility = View.VISIBLE
                mProgressBar!!.visibility = View.GONE
            }

            val success = booleanArrayOf(true)
            for (lang in languages) {
                success[0] = success[0] && downloadTrainingData(dataType, lang.code)
            }
            handler!!.post {
                mDownloadLayout!!.visibility = View.GONE
                if (success[0]) {
                    initializeOCR()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @SuppressLint("DefaultLocale")
        fun downloadTrainingData(dataType: String, lang: String): Boolean {
            var downloadURL = getDownloadUrl(dataType, lang) ?: return false
            try {
                val url = URL(downloadURL)
                var conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                downloadURL = followRedirects(conn, downloadURL)
                conn = URL(downloadURL).openConnection() as HttpURLConnection
                conn.connect()
                val totalContentSize = conn.contentLength
                if (totalContentSize <= 0) {
                    return false
                }
                size = Utils.getSize(totalContentSize)

                // Switch from indeterminate to determinate progress bar
                handler!!.post {
                    mProgressBar!!.visibility = View.VISIBLE
                    mProgressMessage!!.text = String.format(
                        "0%s%s",
                        getString(R.string.percentage_downloaded),
                        size
                    )
                    mProgressBar!!.progress = 0 // Reset progress bar to 0
                }

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(
                        File(
                            currentDirectory, String.format(
                                Constants.LANGUAGE_CODE, lang
                            )
                        )
                    ).use { output ->
                        val data = ByteArray(6 * 1024)
                        var downloaded = 0
                        var count: Int

                        while ((input.read(data).also { count = it }) != -1) {
                            output.write(data, 0, count)
                            downloaded += count
                            val percentage = (downloaded * 100) / totalContentSize
                            handler!!.post {
                                mProgressBar!!.progress = percentage
                                mProgressMessage!!.text = String.format(
                                    "%d%s%s.",
                                    percentage,
                                    getString(R.string.percentage_downloaded),
                                    size
                                )
                            }
                        }
                        output.flush()
                    }
                }
                return true
            } catch (e: IOException) {
                Log.e(TAG, "Download failed: " + e.localizedMessage)
                return false
            }
        }

        fun getDownloadUrl(dataType: String, lang: String): String {
            return when (dataType) {
                "best" -> if (lang == "akk") Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_BEST else if (lang == "eqo") Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU else String.format(
                    Constants.TESSERACT_DATA_DOWNLOAD_URL_BEST, lang
                )

                "standard" -> if (lang == "akk") Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_STANDARD else if (lang == "eqo") Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU else String.format(
                    Constants.TESSERACT_DATA_DOWNLOAD_URL_STANDARD, lang
                )

                else -> if (lang == "akk") Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_FAST else if (lang == "eqo") Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU else String.format(
                    Constants.TESSERACT_DATA_DOWNLOAD_URL_FAST, lang
                )
            }
        }

        @Throws(IOException::class)
        fun followRedirects(conn: HttpURLConnection, downloadURL: String): String {
            var conn = conn
            var downloadURL = downloadURL
            while (true) {
                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    val location = conn.getHeaderField("Location")
                    val base = URL(downloadURL)
                    downloadURL = URL(base, location).toExternalForm() // Handle relative URLs
                    conn =
                        URL(downloadURL).openConnection() as HttpURLConnection // Re-open connection
                } else {
                    break // No more redirects
                }
            }
            return downloadURL
        }
    }

    companion object {
        const val TAG: String = "MainActivity"
        private const val REQUEST_CODE_SETTINGS = 797
        private var isRefresh = false
    }
}