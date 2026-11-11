package com.example.webviewapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Android WebView Scaffold - MainActivity
 * Terkonfigurasi untuk: SIPANDI - Sistem Presensi Pendidikan Digital
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PERBAIKAN v2 — IZIN LOKASI (GEOLOCATION) DI WEBVIEW
 * ═══════════════════════════════════════════════════════════════════════════
 * Geolocation API di WebView TIDAK otomatis diberikan oleh WebView.
 * WebView memanggil WebChromeClient.onGeolocationPermissionsShowPrompt(),
 * dan APLIKASI wajib menjawabnya lewat GeolocationPermissions.Callback.
 *
 * IMPORAN:
 *   - Tanpa override  → default WebChromeClient diam → PERMISSION_DENIED
 *                       (user melihat "Izin lokasi ditolak" walaupun Android
 *                        sudah mengizinkan ACCESS_FINE_LOCATION).
 *   - Tanpa setGeolocationEnabled(true) → API lokasi tidak tersedia di WebView.
 *   - Callback WAJIB dibalas (granted maupun denied), kalau tidak WebView
 *     akan menggantung / meminta izin terus-menerus.
 *
 * Perbaikan yang diterapkan:
 *   [1] webView.settings.setGeolocationEnabled(true)
 *   [2] onGeolocationPermissionsShowPrompt → jawab allow=true, retain=true
 *   [3] onRequestPermissionsResult → jawab callback bila izin runtime diminta
 *   [4] onPermissionRequest → HANYA kamera, HANYA origin aplikasi ini
 *   [5] Rekaman suara (RECORD_AUDIO) tidak lagi diminta / dilepas
 *   [6] onGeolocationPermissionsHidePrompt → lepas callback agar tak menggantung
 *
 * CATATAN BUILD:
 *   - setGeolocationEnabled(true) memakai METODE, bukan properti
 *     ("geolocationEnabled = true") karena WebSettings tidak menyediakan
 *     getter-nya → sintaks properti tidak bisa dikompilasi.
 *   - Tidak memakai BuildConfig.DEBUG (kelasnya bisa tidak dibuat pada
 *     AGP 8+ dengan buildConfig nonaktif) → memakai ApplicationInfo.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val TAG = "WebViewApp"

    // URL Target disesuaikan menuju sistem SIPANDI
    private val TARGET_URL = "https://sipandi.gtkbaturraden.web.id/"
    private val PERMISSION_REQUEST_CODE = 100
    private val FILE_CHOOSER_RESULT_CODE = 101
    private val LOCATION_PERMISSION_REQUEST_CODE = 102

    /**
     * Origin aplikasi sendiri — satu-satunya origin yang boleh memakai kamera.
     * Mencegah situs pihak ketiga (iklan/redirect) memakai kamera HP.
     */
    private val ALLOWED_ORIGIN = "https://sipandi.gtkbaturraden.web.id"

    /**
     * Callback izin lokasi WebView yang sedang menunggu jawaban.
     * Disimpan agar bisa dijawab setelah user memberi izin runtime.
     */
    private var geoPermissionCallback: GeolocationPermissions.Callback? = null
    private var geoPermissionOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        // Remote debugging via chrome://inspect (hanya pada build debug) —
        // memudahkan pemeriksaan konsol WebView saat menelusuri izin lokasi.
        // (Tidak memakai BuildConfig karena fitur buildConfig bisa nonaktif
        //  pada AGP 8+ → kelas BuildConfig tidak dibuat.)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Meminta izin akses perangkat (Kamera, Penyimpanan, Lokasi)
        checkAndRequestPermissions()

        // Setup setelan WebView
        setupWebView()

        // Setup fitur pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        // Pengunci swipe refresh: hanya aktif jika posisi koordinat vertikal web di paling atas (scrollY == 0)
        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefreshLayout.isEnabled = webView.scrollY == 0
        }

        // Memuat URL target secara aman
        if (savedInstanceState == null) {
            Log.d(TAG, "Loading URL: $TARGET_URL")
            webView.loadUrl(TARGET_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")   // setGeolocationEnabled & startActivityForResult (deprecated, tetap dipakai)
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // ══════════════════════════════════════════════════════════
            // [1] WAJIB: mengaktifkan Geolocation API di WebView.
            //     Tanpa baris ini navigator.geolocation tidak tersedia
            //     dan halaman tidak pernah bisa meminta lokasi.
            //
            //     CATATAN: harus setGeolocationEnabled(true) (bukan properti
            //     "geolocationEnabled = true") karena WebSettings tidak
            //     menyediakan getter-nya → sintaks properti TIDAK kompilasi.
            // ══════════════════════════════════════════════════════════
            setGeolocationEnabled(true)

            // FAKE USER-AGENT: Memaksa Google mengenali APK ini sebagai Google Chrome Mobile biasa
            // Berfungsi menyelamatkan fitur "Login dengan Google" agar tidak diblokir oleh error 403 disallowed_useragent
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true

            // Situs SIPANDI seluruhnya HTTPS → tidak perlu konten campuran.
            // MIXED_CONTENT_NEVER_ALLOW mencegah aset http:// dimuat diam-diam.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = true
        }

        // Sinkronisasi Cookie aplikasi
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                return when {
                    url.startsWith("tel:") -> {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                        true
                    }
                    url.startsWith("mailto:") -> {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                        true
                    }
                    else -> false // Nilai false memastikan navigasi situs dan otentikasi akun tetap bertahan di dalam APK
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "Error: ${error?.description}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // Membuka file manager perangkat saat tombol unggah berkas (Upload) di SIPANDI ditekan
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                openFileChooser()
                return true
            }

            // ══════════════════════════════════════════════════════════
            // [2] IZIN LOKASI — inti perbaikan ini.
            //     WebView memanggil method ini saat halaman meminta lokasi.
            //     Aplikasi WAJIB menjawab lewat callback.
            //     retain = true → keputusan DISIMPAN untuk origin tersebut,
            //     sehingga tidak pernah ditanyakan lagi (tidak ada dialog
            //     izin yang muncul berulang setiap navigate).
            // ══════════════════════════════════════════════════════════
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val cb = callback ?: return
                val org = origin ?: ""
                runOnUiThread {
                    val sudahDiizinkan = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (sudahDiizinkan) {
                        Log.i(TAG, "[GEO] izin lokasi diberikan untuk origin=$org (retain=true)")
                        cb.invoke(org, true, true)
                    } else {
                        // Izin runtime Android belum ada → minta dulu, jawab
                        // callback-nya di onRequestPermissionsResult.
                        Log.i(TAG, "[GEO] meminta izin runtime lokasi untuk origin=$org")
                        geoPermissionOrigin = org
                        geoPermissionCallback = cb
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                            LOCATION_PERMISSION_REQUEST_CODE
                        )
                    }
                }
            }

            // [6] Permintaan izin lokasi dibatalkan oleh halaman (halaman
            // ditutup / navigasi). Tidak ada UI dialog buatan aplikasi, jadi
            // cukup dilepas agar WebView tidak menggantung.
            override fun onGeolocationPermissionsHidePrompt() {
                super.onGeolocationPermissionsHidePrompt()
                Log.i(TAG, "[GEO] permintaan izin lokasi dibatalkan halaman")
                geoPermissionCallback = null
                geoPermissionOrigin = null
            }

            // ══════════════════════════════════════════════════════════
            // [4] KAMERA — hanya untuk origin aplikasi ini sendiri.
            //     Versi lama: request.grant(request.resources) → melepas
            //     SEMUA resource (termasuk mikrofon) ke origin APUN.
            // ══════════════════════════════════════════════════════════
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    val req = request ?: return@runOnUiThread
                    val origin = req.origin?.toString() ?: ""
                    val bolehKamera = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (origin.startsWith(ALLOWED_ORIGIN) && bolehKamera) {
                        // Hanya video — mikrofon tidak pernah dipakai SIPANDI.
                        val granted = req.resources.filter {
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        }
                        if (granted.isNotEmpty()) {
                            Log.i(TAG, "[CAM] kamera diizinkan untuk origin=$origin")
                            req.grant(granted.toTypedArray())
                        } else {
                            req.deny()
                        }
                    } else {
                        Log.w(TAG, "[CAM] kamera DITOLAK untuk origin=$origin")
                        req.deny()
                    }
                }
            }
        }

        // Berfungsi menangani proses download berkas dari server ke folder Download HP pengguna
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", userAgent)
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Downloading file...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(Intent.createChooser(intent, "Select File"), FILE_CHOOSER_RESULT_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            val results = if (data?.data != null) arrayOf(data.data!!) else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    /**
     * ══════════════════════════════════════════════════════════════════
     * [3] Jawaban callback izin lokasi WebView setelah user memberi izin
     *     runtime Android.
     *
     * WAJIB membalas callback di SINI juga saat izin DITOLAK — kalau tidak,
     * WebView akan terus meminta izin (loop dialog) dan lokasi tidak pernah
     * datang / error menumpuk.
     * ══════════════════════════════════════════════════════════════════
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                          grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "[GEO] hasil izin runtime lokasi: granted=$granted")

            geoPermissionCallback?.invoke(geoPermissionOrigin, granted, true)
            geoPermissionCallback = null
            geoPermissionOrigin = null
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
            // RECORD_AUDIO sengaja TIDAK diminta lagi:
            // SIPANDI hanya memakai kamera (foto verifikasi wajah),
            // dan mikrofon tidak pernah dilepas ke halaman web.
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    // Mengaktifkan fungsi tombol back bawaan HP agar kembali ke halaman web sebelumnya, bukan langsung menutup aplikasi
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }
}
