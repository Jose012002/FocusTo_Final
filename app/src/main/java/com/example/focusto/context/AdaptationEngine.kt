package com.example.focusto.context

enum class ConcentrationMode {
    NORMAL,
    DEEP_FOCUS, // Celular boca abajo (Premio)
    COOL_DOWN_LOCK, // Agitación (Castigo)
    BAD_POSTURE,
    TOO_CLOSE,
    FACE_ABSENT, // Celular boca arriba quieto (Pausa)
    INACTIVE // Dispositivo completamente inmóvil > 30s (Abandono de la sesión)
}

class AdaptationEngine {

    fun evaluateConcentration(state: ContextState, manager: ContextManager): ConcentrationMode {
        // 1. Agitación violenta
        if (manager.isShaking(state.rawAccel)) {
            return ConcentrationMode.COOL_DOWN_LOCK
        }

        // 2. Estudio Profundo (Boca abajo - Ángulo > 150 grados)
        if (state.rawInclination > 150.0f) {
            return ConcentrationMode.DEEP_FOCUS
        }

        // 3. Abandono de la sesión (Inmovilidad total sostenida > 30s).
        // Se evalúa después de DEEP_FOCUS para no confundir el gesto deliberado de
        // "boca abajo" (premio, ya cubierto arriba) con el abandono del estudio.
        if (manager.isDeviceInactive) {
            return ConcentrationMode.INACTIVE
        }

        // 4. Ausencia (Boca arriba sobre mesa - Ángulo < 15 grados y sin movimiento)
        if (state.rawInclination < 15.0f && state.rawAccel < 0.5f) {
            return ConcentrationMode.FACE_ABSENT
        }

        // 5. Salud Visual (Distancia - Sensor de proximidad)
        if (manager.isTooClose(state.rawProximity)) {
            return ConcentrationMode.TOO_CLOSE
        }

        // 6. Ergonomía (Postura - Menos de 45 grados mirando hacia abajo)
        if (manager.hasBadPosture(state.rawInclination)) {
            return ConcentrationMode.BAD_POSTURE
        }

        return ConcentrationMode.NORMAL
    }

    fun shouldEnableNightMode(state: ContextState, isReading: Boolean, manager: ContextManager): Boolean {
        // El modo nocturno ahora se evalúa por separado para que siempre funcione según la luz
        return manager.isDarkStable(state.rawLux) || (isReading && state.rawLux < 15.0f)
    }
}