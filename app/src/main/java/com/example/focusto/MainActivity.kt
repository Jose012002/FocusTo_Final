package com.example.focusto

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.focusto.context.AdaptationEngine
import com.example.focusto.context.ConcentrationMode
import com.example.focusto.context.ContextManager
import com.example.focusto.context.SensorService
import com.example.focusto.config.FocusConfigRepository
import com.example.focusto.config.SettingsActivity
import com.example.focusto.pdf.PdfViewer
import com.example.focusto.pomodoro.FocusPermissions
import com.example.focusto.pomodoro.FocusService
import com.example.focusto.pomodoro.FocusUiState
import com.example.focusto.pomodoro.FocusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: RelativeLayout
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDesc: TextView
    private lateinit var tvSensorInfo: TextView
    private lateinit var tvHealthAlert: TextView
    private lateinit var btnOpenPdf: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var pdfImageView: ImageView
    private lateinit var warmFilterOverlay: View
    private lateinit var verticalScroll: ScrollView
    private lateinit var horizontalScroll: HorizontalScrollView
    private lateinit var ivFocusMotivator: ImageView

    // Pomodoro y Bloqueo
    private lateinit var tvPomodoroTimer: TextView
    private lateinit var btnStartPomodoro: Button
    private lateinit var btnResetPomodoro: Button
    private lateinit var btnSettings: Button
    private lateinit var layoutLockOverlay: RelativeLayout
    private lateinit var tvLockMessage: TextView
    private lateinit var btnUnlock: Button

    // El timer YA NO vive aquí — vive en FocusService (sobrevive pantalla apagada).
    // El estado de UI (running/break/duración/PDF/zoom/alertas) vive en FocusViewModel,
    // que sobrevive a la recreación de la Activity (p. ej. al rotar la pantalla) y evita
    // que la UI quede desincronizada del FocusService real.
    private lateinit var viewModel: FocusViewModel

    /**
     * Receiver unificado: escucha los 3 eventos que emite FocusService.
     *  - TIMER_TICK      → actualiza cronómetro en pantalla + motivador
     *  - TIMER_FINISH    → maneja fin de sesión / inicio de descanso
     *  - PROGRESS_UPDATE → actualiza motivador cada 5 min (respaldo)
     */
    private val focusServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FocusService.ACTION_TIMER_TICK -> {
                    val left  = intent.getLongExtra(FocusService.EXTRA_MILLIS_LEFT, 0L)
                    val total = intent.getLongExtra(FocusService.EXTRA_TOTAL_MILLIS, viewModel.currentState.currentSessionDuration)
                    val min   = (left / 1000) / 60
                    val sec   = (left / 1000) % 60
                    viewModel.onTimerTick(String.format("%02d:%02d", min, sec))
                    updateMotivator(left, total)
                }
                FocusService.ACTION_TIMER_FINISH -> {
                    val wasBreak = intent.getBooleanExtra(FocusService.EXTRA_IS_BREAK, false)
                    onPomodoroFinished(wasBreak)
                }
                FocusService.ACTION_PROGRESS_UPDATE -> {
                    val elapsed = intent.getLongExtra(FocusService.EXTRA_MILLIS_ELAPSED, 0L)
                    val total   = intent.getLongExtra(FocusService.EXTRA_TOTAL_MILLIS, viewModel.currentState.currentSessionDuration)
                    updateMotivator((total - elapsed).coerceAtLeast(0L), total)
                }
            }
        }
    }

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var currentZoomLevel: Float = 2.0f
    private val minZoomLevel = 1.0f
    private val maxZoomLevel = 5.0f

    private val pdfViewer = PdfViewer(this)

    private lateinit var sensorService: SensorService
    private lateinit var contextManager: ContextManager
    private val adaptationEngine = AdaptationEngine()
    private val configRepository by lazy { FocusConfigRepository(applicationContext) }

    private val selectPdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { openPdfFromUri(it) }
    }

    // Si se niega, las notificaciones de alerta del Pomodoro simplemente no se muestran
    // (FocusService ya verifica el permiso antes de intentar publicarlas).
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(FocusViewModel::class.java)
        try {
            setContentView(R.layout.activity_main)
            initViews()
            setupConcentrationSystems()
            setupListeners()
            currentZoomLevel = viewModel.currentState.zoomLevel
            observeUiState()
            restorePdfIfNeeded()
            checkIntent(intent)
            requestNotificationPermissionIfNeeded()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al iniciar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Carga la configuración persistida (duraciones, umbrales de sensores) y la aplica a
     * ContextManager y al ViewModel. Se llama al crear la Activity y cada vez que vuelve a
     * primer plano, para recoger cambios hechos en SettingsActivity sin reiniciar la app.
     */
    private fun loadAndApplyConfig() {
        lifecycleScope.launch {
            val config = configRepository.currentConfig()
            contextManager.updateThresholds(
                lightThresholdLux = config.lightThresholdLux,
                shakeThresholdAccel = config.shakeThresholdAccel,
                proximityThreshold = config.proximityThreshold,
                postureThresholdAngle = config.postureThresholdAngle
            )
            viewModel.onDurationsChanged(config.studyDurationMs, config.breakDurationMs)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("SHOW_BLOCK_OVERLAY", false) == true) {
            val messageKey = intent.getStringExtra("BLOCK_MESSAGE_KEY")
            val message = if (messageKey == "APP_BLOCKED") {
                getString(R.string.lock_message_app_blocked)
            } else {
                "¡REGRESA AL ESTUDIO!\nTu sesión sigue activa."
            }
            showLockOverlay(message)
        }
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.rootLayout)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDesc = findViewById(R.id.tvStatusDesc)
        tvSensorInfo = findViewById(R.id.tvSensorInfo)
        tvHealthAlert = findViewById(R.id.tvHealthAlert)
        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        pdfImageView = findViewById(R.id.pdfImageView)
        warmFilterOverlay = findViewById(R.id.warmFilterOverlay)
        verticalScroll = findViewById(R.id.verticalScrollPdf)
        horizontalScroll = findViewById(R.id.horizontalScrollPdf)

        tvPomodoroTimer = findViewById(R.id.tvPomodoroTimer)
        btnStartPomodoro = findViewById(R.id.btnStartPomodoro)
        btnResetPomodoro = findViewById(R.id.btnResetPomodoro)
        btnSettings = findViewById(R.id.btnSettings)
        layoutLockOverlay = findViewById(R.id.layoutLockOverlay)
        tvLockMessage = findViewById(R.id.tvLockMessage)
        btnUnlock = findViewById(R.id.btnUnlock)
        ivFocusMotivator = findViewById(R.id.ivFocusMotivator)

        pdfImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        setupZoomGestures()
    }

    // ─── Estado de UI (FocusViewModel) ──────────────────────────────────────

    /**
     * Suscripción continua al estado: aplica textos/colores/visibilidad de las vistas
     * "ligeras" (timer, botón, alertas, overlay de bloqueo, texto de sensor/página).
     * Deliberadamente NO dispara el renderizado del PDF (operación pesada) — eso solo
     * ocurre en los puntos explícitos (navegación, gesto de zoom, cambio de modo nocturno).
     */
    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: FocusUiState) {
        tvPomodoroTimer.text = state.timerText
        btnStartPomodoro.text = state.startButtonText
        // No tiene sentido "reiniciar" si todavía no se inició ni hay nada pausado.
        btnResetPomodoro.isEnabled = state.isPomodoroRunning || state.isPaused

        val alert = state.healthAlert
        if (alert != null) {
            tvHealthAlert.text = alert.message
            tvHealthAlert.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (alert.isPositive) android.R.color.holo_green_dark else android.R.color.holo_red_dark
                )
            )
            tvHealthAlert.visibility = View.VISIBLE
        } else {
            tvHealthAlert.visibility = View.GONE
        }

        if (state.lockMessage != null) {
            tvLockMessage.text = state.lockMessage
            layoutLockOverlay.visibility = View.VISIBLE
        } else {
            layoutLockOverlay.visibility = View.GONE
        }

        applyNightModeColors(state.isNightModeActive)
        updateSensorAndPageText(state)
    }

    /** Restaura el PDF abierto antes de que la Activity se recreara (p. ej. por rotación). */
    private fun restorePdfIfNeeded() {
        val state = viewModel.currentState
        val uriString = state.pdfUriString ?: return
        try {
            openPdfFromUri(Uri.parse(uriString))
            if (state.currentPageIndex in 0 until state.totalPages) {
                viewModel.onPageChanged(state.currentPageIndex)
                renderPage(state.currentPageIndex)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupConcentrationSystems() {
        contextManager = ContextManager { state ->
            runOnUiThread {
                viewModel.onLuxUpdated(state.rawLux.toInt())

                // 1. Adaptación de Luz (Independiente)
                val nightNeeded = adaptationEngine.shouldEnableNightMode(state, contextManager.isUserActivelyReading(), contextManager)
                val nightActive = viewModel.currentState.isNightModeActive
                if (nightNeeded && !nightActive) toggleNightMode(true)
                else if (!nightNeeded && nightActive && state.rawLux >= 15.0f) toggleNightMode(false)

                // 2. Concentración y Salud
                val vmState = viewModel.currentState
                if (vmState.isPomodoroRunning && !vmState.isBreakTime) {
                    val mode = adaptationEngine.evaluateConcentration(state, contextManager)
                    handleConcentrationMode(mode)

                    // --- INTUICIÓN: Detectar Modo Estudio Físico sin PDF ---
                    // Si el Pomodoro corre, no hay PDF y el celular está confirmado boca abajo,
                    // activamos el aviso de Modo Físico con vibración (una sola vez por sesión)
                    if (!vmState.isPdfLoaded && contextManager.isPhysicalStudyModeActive && !vmState.physicalStudyModeNotified) {
                        viewModel.setPhysicalStudyModeNotified(true)
                        onPhysicalStudyModeActivated(state.rawLux)
                    } else if (!contextManager.isPhysicalStudyModeActive) {
                        // Resetear para que pueda notificar de nuevo si baja y sube
                        viewModel.setPhysicalStudyModeNotified(false)
                    }
                } else if (vmState.isPaused && vmState.pausedByInactivity && !contextManager.isDeviceInactive) {
                    // --- Recuperación automática desde INACTIVO ---
                    // El pomodoro está pausado porque el dispositivo estuvo inmóvil >30s.
                    // Al volver a detectar movimiento, salimos solos de INACTIVO y
                    // retomamos la sesión: el próximo tick de evaluateConcentration()
                    // recalculará el modo que corresponda al contexto actual.
                    resumeFromInactivityAutomatically()
                } else {
                    hideHealthAlert()
                    viewModel.setPhysicalStudyModeNotified(false)
                }
            }
        }

        sensorService = SensorService(this) { lux, accel, proximity, inclination ->
            contextManager.processRawData(lux, accel, proximity, inclination)
        }
    }

    /**
     * Callback de Intuición: Se llama cuando se confirma que el usuario estudia
     * sin PDF (Modo Físico / Estudio Externo). Vibra suavemente y muestra un hint.
     */
    private fun onPhysicalStudyModeActivated(currentLux: Float) {
        // Vibración sutil de confirmación (patrón corto-largo)
        vibrate(longArrayOf(0, 80, 60, 200))

        // Mostrar hint en el banner de salud (color verde como premio)
        val message = if (currentLux < 12.0f) {
            getString(R.string.physical_study_light_alert)
        } else {
            getString(R.string.physical_study_no_pdf_hint)
        }
        showHealthAlert(message, isPositive = true)

        Toast.makeText(this, getString(R.string.physical_study_started), Toast.LENGTH_SHORT).show()
    }

    /**
     * Vibra con el patrón indicado, compatible con API 26+ y versiones anteriores.
     * @param pattern array en formato [delay, on, off, on, ...] en ms
     */
    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }
    }

    private fun handleConcentrationMode(mode: ConcentrationMode) {
        when (mode) {
            ConcentrationMode.DEEP_FOCUS -> {
                // Si hay PDF cargado, mostrar el banner de enfoque profundo normal
                // Si no hay PDF, la intuición se encarga (onPhysicalStudyModeActivated)
                if (viewModel.currentState.isPdfLoaded) {
                    showHealthAlert("¡MODO ENFOQUE PROFUNDO ACTIVO!", isPositive = true)
                }
            }
            ConcentrationMode.COOL_DOWN_LOCK -> {
                showLockOverlay(getString(R.string.lock_message))
            }
            ConcentrationMode.FACE_ABSENT -> {
                pausePomodoro()
                showLockOverlay(getString(R.string.alert_face_absent))
            }
            ConcentrationMode.INACTIVE -> {
                // Adaptación observable: se pausa el conteo del Pomodoro (no se acredita
                // como tiempo de enfoque) y se muestra un overlay distinto al de FACE_ABSENT
                // para que quede claro que la sesión se retomará sola al detectar movimiento.
                viewModel.setPausedByInactivity(true)
                showLockOverlay(getString(R.string.alert_inactive))
            }
            ConcentrationMode.TOO_CLOSE -> {
                showHealthAlert(getString(R.string.alert_too_close))
            }
            ConcentrationMode.BAD_POSTURE -> {
                showHealthAlert(getString(R.string.alert_bad_posture))
            }
            ConcentrationMode.NORMAL -> {
                hideHealthAlert()
            }
        }
    }

    private fun showLockOverlay(message: String) {
        viewModel.showLockOverlay(message)
        pausePomodoro()
    }

    private fun showHealthAlert(message: String, isPositive: Boolean = false) {
        viewModel.showHealthAlert(message, isPositive)
    }

    private fun hideHealthAlert() {
        viewModel.hideHealthAlert()
    }

    // Estos OnTouchListener son para detectar gestos (zoom, "el usuario está tocando la
    // pantalla"), no para representar un click — performClick() no aplicaría acá.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                pdfImageView.scaleX *= scaleFactor
                pdfImageView.scaleY *= scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                currentZoomLevel *= pdfImageView.scaleX
                currentZoomLevel = currentZoomLevel.coerceIn(minZoomLevel, maxZoomLevel)
                viewModel.onZoomChanged(currentZoomLevel)
                if (viewModel.currentState.totalPages > 0) renderPage(viewModel.currentState.currentPageIndex)
            }
        })

        val touchListener = View.OnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.pointerCount > 1) v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        verticalScroll.setOnTouchListener(touchListener)
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) contextManager.registerUserTouch()
            false
        }
    }

    override fun onResume() {
        super.onResume()
        sensorService.startListening()
        loadAndApplyConfig()
        // Registrar el receiver unificado: tick + finish + motivador
        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_TIMER_TICK)
            addAction(FocusService.ACTION_TIMER_FINISH)
            addAction(FocusService.ACTION_PROGRESS_UPDATE)
        }
        ContextCompat.registerReceiver(this, focusServiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        sensorService.stopListening()
        try { unregisterReceiver(focusServiceReceiver) } catch (_: IllegalArgumentException) {}
    }

    private fun setupListeners() {
        btnOpenPdf.setOnClickListener { selectPdfLauncher.launch("application/pdf") }
        btnNextPage.setOnClickListener {
            val state = viewModel.currentState
            if (state.totalPages > 0 && state.currentPageIndex < state.totalPages - 1) {
                val newIndex = state.currentPageIndex + 1
                viewModel.onPageChanged(newIndex)
                renderPage(newIndex)
            }
        }
        btnPrevPage.setOnClickListener {
            val state = viewModel.currentState
            if (state.totalPages > 0 && state.currentPageIndex > 0) {
                val newIndex = state.currentPageIndex - 1
                viewModel.onPageChanged(newIndex)
                renderPage(newIndex)
            }
        }
        btnStartPomodoro.setOnClickListener {
            val state = viewModel.currentState
            when {
                state.isPomodoroRunning -> pausePomodoro()
                state.isPaused -> resumePomodoro()
                else -> startPomodoro()
            }
        }
        btnResetPomodoro.setOnClickListener { resetPomodoro() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnUnlock.setOnClickListener {
            viewModel.hideLockOverlay()
        }
    }

    private fun startPomodoro() {
        if (!checkPermissions()) return
        viewModel.onPomodoroStarted()
        setSilentMode(true)

        val state = viewModel.currentState
        sendToFocusService(FocusService.ACTION_START_FOCUS, state.currentSessionDuration, state.isBreakTime)
    }

    /**
     * Retoma una sesión pausada. A diferencia de [startPomodoro], NO manda ACTION_START_FOCUS
     * (eso reiniciaría el tiempo desde el total) — manda ACTION_RESUME_FOCUS, que FocusService
     * ya sabe atender retomando desde el tiempo restante que tenía guardado al pausar.
     */
    private fun resumePomodoro() {
        if (!checkPermissions()) return
        viewModel.onPomodoroResumed()
        setSilentMode(true)
        sendToFocusService(FocusService.ACTION_RESUME_FOCUS)
    }

    /**
     * Reanuda la sesión automáticamente al salir de INACTIVO por movimiento detectado.
     * A diferencia de [resumePomodoro] (la del botón "CONTINUAR"), NO llama a
     * checkPermissions(): esa función puede lanzar una pantalla de Configuración de
     * Android si detecta un permiso faltante, algo apropiado para una acción manual del
     * usuario pero no para una recuperación silenciosa en segundo plano — el usuario no
     * debe salir sorpresivamente de la app solo por mover el teléfono. Los permisos ya
     * se validaron al iniciar la sesión (startPomodoro), así que no hace falta repetirlos.
     */
    private fun resumeFromInactivityAutomatically() {
        viewModel.setPausedByInactivity(false)
        viewModel.hideLockOverlay()
        viewModel.onPomodoroResumed()
        setSilentMode(true)
        sendToFocusService(FocusService.ACTION_RESUME_FOCUS)
    }

    /**
     * Llamado cuando el FocusService emite ACTION_TIMER_FINISH.
     * Si terminó una sesión de estudio → inicia descanso de 5 min automáticamente.
     * Si terminó un descanso → vuelve al estado inicial listo para otra sesión.
     */
    private fun onPomodoroFinished(wasBreak: Boolean) {
        setSilentMode(false)
        viewModel.onPomodoroFinished(wasBreak)
        val state = viewModel.currentState

        if (!wasBreak) {
            // Terminó sesión de estudio → inicia descanso automáticamente
            Toast.makeText(this, "✅ ¡Pomodoro completado! Descansa 5 minutos.", Toast.LENGTH_LONG).show()
            updateMotivator(0L, state.studyDurationMs)   // motivador en estado máximo (flor/constelación)
            setSilentMode(false)
            sendToFocusService(FocusService.ACTION_START_FOCUS, state.currentSessionDuration, true)
        } else {
            // Terminó descanso → volver a estado inicial
            Toast.makeText(this, "☕ ¡Descanso terminado! Listo para otra sesión.", Toast.LENGTH_LONG).show()
            updateMotivator(state.studyDurationMs, state.studyDurationMs)   // motivador al inicio (semilla)
        }
    }

    private fun pausePomodoro() {
        viewModel.onPomodoroPaused()
        setSilentMode(false)
        sendToFocusService(FocusService.ACTION_PAUSE_FOCUS)
    }

    private fun resetPomodoro() {
        viewModel.onPomodoroReset()
        setSilentMode(false)
        sendToFocusService(FocusService.ACTION_STOP_FOCUS)
        updateMotivator(viewModel.currentState.studyDurationMs, viewModel.currentState.studyDurationMs)
    }

    // ─── Helpers de comunicación con FocusService ───────────────────────────

    /** Envía cualquier acción al FocusService, opcionalmente con duración y tipo de sesión */
    private fun sendToFocusService(
        action: String,
        durationMs: Long = viewModel.currentState.currentSessionDuration,
        isBreak: Boolean = viewModel.currentState.isBreakTime
    ) {
        val intent = Intent(this, FocusService::class.java).apply {
            this.action = action
            putExtra(FocusService.EXTRA_TOTAL_MILLIS, durationMs)
            putExtra(FocusService.EXTRA_IS_BREAK, isBreak)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    // ─── Permisos ────────────────────────────────────────────────────────────

    private fun checkPermissions(): Boolean {
        if (!FocusPermissions.isUsageStatsGranted(this)) {
            Toast.makeText(this, "Permite el acceso a datos de uso para el bloqueo", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return false
        }
        if (!FocusPermissions.isOverlayGranted(this)) {
            Toast.makeText(this, "Permite mostrar sobre otras apps para el bloqueo", Toast.LENGTH_LONG).show()
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }
        return true
    }

    // ─── Motivador visual ────────────────────────────────────────────────────

    private fun updateMotivator(millisLeft: Long, totalTime: Long) {
        val progress = 1.0f - (millisLeft.toFloat() / totalTime.coerceAtLeast(1L))
        val stage = (progress * 4).toInt().coerceIn(0, 3)
        val resId = if (viewModel.currentState.isNightModeActive) {
            when (stage) {
                0    -> R.drawable.ic_focus_star1
                1    -> R.drawable.ic_focus_star1
                2    -> R.drawable.ic_focus_star2
                else -> R.drawable.ic_focus_constellation
            }
        } else {
            when (stage) {
                0    -> R.drawable.ic_focus_seed
                1    -> R.drawable.ic_focus_sprout
                2    -> R.drawable.ic_focus_leaf
                else -> R.drawable.ic_focus_flower
            }
        }
        ivFocusMotivator.setImageResource(resId)
        ivFocusMotivator.visibility = View.VISIBLE
    }

    private fun setSilentMode(enable: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }


    private fun openPdfFromUri(uri: Uri) {
        try {
            closePdfRenderer()
            val pageCount = pdfViewer.open(uri)
            if (pageCount > 0) {
                viewModel.onPdfOpened(uri.toString(), pageCount) // ← El usuario tiene material digital; desactiva la intuición física
                renderPage(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al abrir el PDF", Toast.LENGTH_SHORT).show()
        }
    }


    private fun renderPage(index: Int) {
        if (index < 0 || index >= pdfViewer.pageCount) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = pdfViewer.renderPage(index, currentZoomLevel, viewModel.currentState.isNightModeActive) ?: return@launch
                withContext(Dispatchers.Main) {
                    pdfImageView.scaleX = 1.0f
                    pdfImageView.scaleY = 1.0f
                    pdfImageView.setImageBitmap(bitmap)
                    updateSensorAndPageText()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun updateSensorAndPageText(state: FocusUiState = viewModel.currentState) {
        val luxString = getString(R.string.sensor_lux_format, state.currentLuxValue)
        tvSensorInfo.text = if (state.totalPages > 0) getString(R.string.page_info_format, luxString, state.currentPageIndex + 1, state.totalPages) else luxString
    }

    /** Aplica solo los colores/textos derivados del modo nocturno (operación liviana). */
    private fun applyNightModeColors(enable: Boolean) {
        val bgColor = ContextCompat.getColor(this, if (enable) R.color.bg_night else R.color.bg_normal)
        val titleColor = ContextCompat.getColor(this, if (enable) R.color.text_title_night else R.color.text_title_normal)
        val descColor = ContextCompat.getColor(this, if (enable) R.color.text_desc_night else R.color.text_desc_normal)
        val sensorColor = ContextCompat.getColor(this, if (enable) R.color.sensor_text_night else R.color.sensor_text_normal)
        rootLayout.setBackgroundColor(bgColor)
        tvStatusTitle.setTextColor(titleColor)
        tvStatusDesc.setTextColor(descColor)
        tvSensorInfo.setTextColor(sensorColor)
        btnPrevPage.setTextColor(titleColor)
        btnNextPage.setTextColor(titleColor)
        tvStatusTitle.text = getString(if (enable) R.string.status_night_title else R.string.status_title_normal)
        tvStatusDesc.text = getString(if (enable) R.string.status_night_desc else R.string.status_desc_normal)
    }

    /** Cambia el modo nocturno y re-renderiza el PDF (única operación pesada disparada por este evento). */
    private fun toggleNightMode(enable: Boolean) {
        viewModel.onNightModeChanged(enable)
        if (viewModel.currentState.totalPages > 0) renderPage(viewModel.currentState.currentPageIndex)
    }

    private fun closePdfRenderer() {
        try {
            pdfViewer.close()
            viewModel.onPdfClosed()
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
    }
}
