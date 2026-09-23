package com.example.focusto.context

import android.os.SystemClock

data class ContextState(
    val rawLux: Float,
    val rawAccel: Float,
    val rawProximity: Float,
    val rawInclination: Float
)

class ContextManager(private val onStateUpdated: (ContextState) -> Unit) {

    private var lastTouchTime: Long = 0
    private var darkStateStartTime: Long = 0
    private val darkConfirmationDelayMs = 1000L

    // Estado confirmado del Modo Estudio Físico (boca abajo)
    private var faceDownStartTime: Long = 0
    private val faceDownConfirmationDelayMs = 1500L
    private var _isPhysicalStudyModeActive: Boolean = false
    val isPhysicalStudyModeActive: Boolean get() = _isPhysicalStudyModeActive

    // Estado confirmado de Inactividad (dispositivo inmóvil, abandono de la sesión).
    // Requiere quietud sostenida por inactivityConfirmationDelayMs; se cancela de
    // inmediato en cuanto se detecta movimiento (sin retardo de salida).
    private var stillStartTime: Long = 0
    private val inactivityConfirmationDelayMs = 30_000L
    // Mismo umbral que el ya validado por el equipo para "sin movimiento" en FACE_ABSENT
    // (ver evaluateConcentration): un valor mas ajustado no tolera el ruido normal del
    // acelerometro y el temporizador nunca llega a acumular los 30s en un dispositivo real.
    private val stillnessAccelThreshold = 0.5f
    private var _isDeviceInactive: Boolean = false
    val isDeviceInactive: Boolean get() = _isDeviceInactive

    // Umbrales calibrados. Arrancan con los valores por defecto de FocusConfig y se
    // actualizan vía updateThresholds() en cuanto se carga (o cambia) la configuración
    // persistida — por eso son 'var' y no 'val'.
    private var lightThresholdLux = 12.0f
    private var shakeThresholdAccel = 15.0f

    // Nota: El sensor de proximidad en la mayoría de celulares reacciona a < 5cm.
    // Usamos un umbral genérico que detecte la activación del sensor.
    private var proximityThreshold = 4.0f
    private var postureThresholdAngle = 45.0f

    /** Reemplaza los umbrales calibrados (p. ej. al cargar o guardar la configuración). */
    fun updateThresholds(
        lightThresholdLux: Float,
        shakeThresholdAccel: Float,
        proximityThreshold: Float,
        postureThresholdAngle: Float
    ) {
        this.lightThresholdLux = lightThresholdLux
        this.shakeThresholdAccel = shakeThresholdAccel
        this.proximityThreshold = proximityThreshold
        this.postureThresholdAngle = postureThresholdAngle
    }

    fun processRawData(lux: Float, accel: Float, proximity: Float, inclination: Float) {
        val currentState = ContextState(
            rawLux = lux,
            rawAccel = accel,
            rawProximity = proximity,
            rawInclination = inclination
        )
        // Actualizar el estado del Modo Físico y de Inactividad antes de notificar
        updatePhysicalStudyMode(inclination)
        updateInactivityState(accel)
        onStateUpdated(currentState)
    }

    /**
     * Detecta con confirmación temporal si el celular está boca abajo (inclinación > 150°).
     * Requiere que permanezca en esa posición por [faceDownConfirmationDelayMs] ms.
     */
    private fun updatePhysicalStudyMode(inclination: Float) {
        val currentTime = SystemClock.elapsedRealtime()
        val isFaceDown = inclination > 150.0f
        if (isFaceDown) {
            if (faceDownStartTime == 0L) {
                faceDownStartTime = currentTime
            }
            _isPhysicalStudyModeActive = (currentTime - faceDownStartTime) >= faceDownConfirmationDelayMs
        } else {
            faceDownStartTime = 0L
            _isPhysicalStudyModeActive = false
        }
    }

    /**
     * Detecta con confirmación temporal si el dispositivo está completamente inmóvil
     * (magnitud de aceleración dinámica por debajo de [stillnessAccelThreshold]).
     * Requiere quietud sostenida durante [inactivityConfirmationDelayMs] ms (30 s) para
     * confirmar abandono; cualquier movimiento reinicia el conteo de inmediato.
     */
    private fun updateInactivityState(accel: Float) {
        val currentTime = SystemClock.elapsedRealtime()
        val isStill = accel < stillnessAccelThreshold
        if (isStill) {
            if (stillStartTime == 0L) {
                stillStartTime = currentTime
            }
            _isDeviceInactive = (currentTime - stillStartTime) >= inactivityConfirmationDelayMs
        } else {
            stillStartTime = 0L
            _isDeviceInactive = false
        }
    }

    fun registerUserTouch() {
        lastTouchTime = SystemClock.elapsedRealtime()
    }

    fun isUserActivelyReading(): Boolean {
        return (SystemClock.elapsedRealtime() - lastTouchTime) < 15000L
    }

    fun isDarkStable(lux: Float): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        if (lux < lightThresholdLux) {
            if (darkStateStartTime == 0L) {
                darkStateStartTime = currentTime
            }
            return (currentTime - darkStateStartTime) >= darkConfirmationDelayMs
        } else {
            darkStateStartTime = 0L
            return false
        }
    }

    fun isShaking(accel: Float): Boolean {
        return accel > shakeThresholdAccel
    }

    fun isTooClose(proximity: Float): Boolean {
        // Alertamos si el sensor detecta que algo está cerca
        return proximity < proximityThreshold
    }

    fun hasBadPosture(inclination: Float): Boolean {
        // Ángulo de inclinación saludable: > 45 grados respecto a la mesa
        return inclination < postureThresholdAngle && inclination > 15.0f
    }
}
