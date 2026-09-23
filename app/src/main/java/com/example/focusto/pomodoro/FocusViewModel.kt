package com.example.focusto.pomodoro

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Alerta de salud/postura mostrada en el banner no bloqueante. */
data class HealthAlert(val message: String, val isPositive: Boolean)

/**
 * Estado de UI de MainActivity. Se guarda como un único bloque inmutable para que
 * `MainActivity` pueda restaurarlo por completo tras recrearse (p. ej. al rotar la pantalla),
 * sin depender de que el usuario recuerde interactuar de nuevo con la pantalla.
 */
data class FocusUiState(
    val isPomodoroRunning: Boolean = false,
    /** true solo cuando hay una sesión pausada a mitad de camino (no recién reiniciada). */
    val isPaused: Boolean = false,
    val isBreakTime: Boolean = false,
    val studyDurationMs: Long = FocusViewModel.STUDY_DURATION_MS,
    val breakDurationMs: Long = FocusViewModel.BREAK_DURATION_MS,
    val currentSessionDuration: Long = studyDurationMs,
    val timerText: String = "25:00",
    val startButtonText: String = "INICIAR",
    val pdfUriString: String? = null,
    val isPdfLoaded: Boolean = false,
    val currentPageIndex: Int = 0,
    val totalPages: Int = 0,
    val zoomLevel: Float = 2.0f,
    val isNightModeActive: Boolean = false,
    val currentLuxValue: Int = 0,
    val healthAlert: HealthAlert? = null,
    val lockMessage: String? = null,
    val physicalStudyModeNotified: Boolean = false,
    /** true cuando la pausa actual la disparó el modo INACTIVO (no el usuario), para
     *  poder reanudar la sesión automáticamente al detectar movimiento de nuevo. */
    val pausedByInactivity: Boolean = false
)

/**
 * Mantiene el estado de UI que hoy vive disperso en variables de MainActivity.
 * Al sobrevivir a la recreación de la Activity (rotación de pantalla), evita que la UI
 * quede desincronizada del estado real (p. ej. el Pomodoro sigue corriendo en FocusService
 * pero la Activity recién creada mostraría "INICIAR").
 *
 * Deliberadamente NO conoce Views, Context ni Intents: solo expone estado y funciones
 * puras que lo transforman, para que sea testeable con JUnit sin Android framework.
 */
class FocusViewModel : ViewModel() {

    companion object {
        const val STUDY_DURATION_MS = 25 * 60 * 1000L
        const val BREAK_DURATION_MS = 5 * 60 * 1000L
    }

    private val _uiState = MutableStateFlow(FocusUiState())
    val uiState: StateFlow<FocusUiState> = _uiState.asStateFlow()

    /** Lectura síncrona del último estado, útil para decisiones puntuales fuera del collector. */
    val currentState: FocusUiState
        get() = _uiState.value

    /** Arranca una sesión NUEVA (no una que estaba pausada) — usa la duración configurada. */
    fun onPomodoroStarted() {
        val duration = if (currentState.isBreakTime) currentState.breakDurationMs else currentState.studyDurationMs
        _uiState.value = currentState.copy(
            isPomodoroRunning = true,
            isPaused = false,
            physicalStudyModeNotified = false,
            pausedByInactivity = false,
            startButtonText = "PAUSAR",
            currentSessionDuration = duration
        )
    }

    /**
     * Retoma una sesión que estaba pausada, sin tocar `currentSessionDuration` ni `timerText`
     * — el tiempo restante real vive en FocusService y llega solo por el próximo tick.
     */
    fun onPomodoroResumed() {
        _uiState.value = currentState.copy(
            isPomodoroRunning = true,
            isPaused = false,
            pausedByInactivity = false,
            startButtonText = "PAUSAR"
        )
    }

    fun onPomodoroPaused() {
        _uiState.value = currentState.copy(isPomodoroRunning = false, isPaused = true, startButtonText = "CONTINUAR")
    }

    fun onPomodoroReset() {
        _uiState.value = currentState.copy(
            isPomodoroRunning = false,
            isPaused = false,
            isBreakTime = false,
            pausedByInactivity = false,
            startButtonText = "INICIAR",
            timerText = formatMillis(currentState.studyDurationMs),
            currentSessionDuration = currentState.studyDurationMs
        )
    }

    /** Terminó una sesión (`wasBreak = false`) o un descanso (`wasBreak = true`). */
    fun onPomodoroFinished(wasBreak: Boolean) {
        _uiState.value = if (!wasBreak) {
            currentState.copy(
                isBreakTime = true,
                isPomodoroRunning = true,
                isPaused = false,
                startButtonText = "PAUSAR",
                currentSessionDuration = currentState.breakDurationMs,
                timerText = formatMillis(currentState.breakDurationMs)
            )
        } else {
            currentState.copy(
                isBreakTime = false,
                isPomodoroRunning = false,
                isPaused = false,
                startButtonText = "INICIAR",
                timerText = formatMillis(currentState.studyDurationMs),
                currentSessionDuration = currentState.studyDurationMs
            )
        }
    }

    /**
     * Aplica duraciones nuevas (desde la configuración persistida al arrancar, o desde la
     * pantalla de ajustes). Si hay una sesión corriendo, no le cambia el tiempo restante —
     * solo afecta a la próxima sesión/descanso.
     */
    fun onDurationsChanged(studyDurationMs: Long, breakDurationMs: Long) {
        val activeDuration = if (currentState.isBreakTime) breakDurationMs else studyDurationMs
        _uiState.value = currentState.copy(
            studyDurationMs = studyDurationMs,
            breakDurationMs = breakDurationMs,
            currentSessionDuration = activeDuration,
            timerText = if (currentState.isPomodoroRunning) currentState.timerText else formatMillis(activeDuration)
        )
    }

    fun onTimerTick(timerText: String) {
        _uiState.value = currentState.copy(timerText = timerText)
    }

    fun onPdfOpened(uriString: String, totalPages: Int) {
        _uiState.value = currentState.copy(
            pdfUriString = uriString,
            isPdfLoaded = totalPages > 0,
            totalPages = totalPages,
            currentPageIndex = 0
        )
    }

    fun onPdfClosed() {
        _uiState.value = currentState.copy(
            pdfUriString = null,
            isPdfLoaded = false,
            totalPages = 0,
            currentPageIndex = 0
        )
    }

    fun onPageChanged(index: Int) {
        _uiState.value = currentState.copy(currentPageIndex = index)
    }

    fun onZoomChanged(zoom: Float) {
        _uiState.value = currentState.copy(zoomLevel = zoom)
    }

    fun onNightModeChanged(enabled: Boolean) {
        _uiState.value = currentState.copy(isNightModeActive = enabled)
    }

    fun onLuxUpdated(lux: Int) {
        _uiState.value = currentState.copy(currentLuxValue = lux)
    }

    fun setPhysicalStudyModeNotified(notified: Boolean) {
        _uiState.value = currentState.copy(physicalStudyModeNotified = notified)
    }

    fun setPausedByInactivity(paused: Boolean) {
        _uiState.value = currentState.copy(pausedByInactivity = paused)
    }

    fun showHealthAlert(message: String, isPositive: Boolean = false) {
        _uiState.value = currentState.copy(healthAlert = HealthAlert(message, isPositive))
    }

    fun hideHealthAlert() {
        _uiState.value = currentState.copy(healthAlert = null)
    }

    fun showLockOverlay(message: String) {
        _uiState.value = currentState.copy(lockMessage = message)
    }

    fun hideLockOverlay() {
        _uiState.value = currentState.copy(lockMessage = null)
    }

    private fun formatMillis(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
