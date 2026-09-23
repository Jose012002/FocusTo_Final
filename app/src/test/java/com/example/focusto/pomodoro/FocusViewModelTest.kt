package com.example.focusto.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Valida que FocusViewModel preserve correctamente el estado de UI (el problema original:
 * MainActivity perdía este estado al recrearse en una rotación de pantalla).
 */
class FocusViewModelTest {

    private lateinit var viewModel: FocusViewModel

    @Before
    fun setUp() {
        viewModel = FocusViewModel()
    }

    @Test
    fun `estado inicial coincide con los valores por defecto originales de MainActivity`() {
        val state = viewModel.currentState
        assertFalse(state.isPomodoroRunning)
        assertFalse(state.isBreakTime)
        assertEquals(FocusViewModel.STUDY_DURATION_MS, state.currentSessionDuration)
        assertEquals("25:00", state.timerText)
        assertEquals("INICIAR", state.startButtonText)
        assertFalse(state.isPdfLoaded)
        assertNull(state.pdfUriString)
    }

    @Test
    fun `onPomodoroStarted en sesion de estudio activa PAUSAR y duracion de 25 min`() {
        viewModel.onPomodoroStarted()
        val state = viewModel.currentState
        assertTrue(state.isPomodoroRunning)
        assertEquals("PAUSAR", state.startButtonText)
        assertEquals(FocusViewModel.STUDY_DURATION_MS, state.currentSessionDuration)
    }

    @Test
    fun `onPomodoroStarted durante un descanso pausado retoma con duracion de 5 min`() {
        viewModel.onPomodoroFinished(wasBreak = false) // termina estudio -> arranca descanso
        viewModel.onPomodoroPaused()                   // usuario pausa el descanso

        viewModel.onPomodoroStarted()                  // usuario retoma

        val state = viewModel.currentState
        assertTrue(state.isBreakTime)
        assertTrue(state.isPomodoroRunning)
        assertEquals(FocusViewModel.BREAK_DURATION_MS, state.currentSessionDuration)
        assertEquals("PAUSAR", state.startButtonText)
    }

    @Test
    fun `onPomodoroPaused detiene el timer y muestra CONTINUAR`() {
        viewModel.onPomodoroStarted()
        viewModel.onPomodoroPaused()
        val state = viewModel.currentState
        assertFalse(state.isPomodoroRunning)
        assertTrue(state.isPaused)
        assertEquals("CONTINUAR", state.startButtonText)
    }

    @Test
    fun `regresion - onPomodoroResumed NO reinicia la duracion de la sesion`() {
        viewModel.onPomodoroStarted()
        viewModel.onTimerTick("18:42") // el usuario ya avanzo bastante en la sesion
        viewModel.onPomodoroPaused()

        viewModel.onPomodoroResumed()

        val state = viewModel.currentState
        assertTrue(state.isPomodoroRunning)
        assertFalse(state.isPaused)
        assertEquals("PAUSAR", state.startButtonText)
        // el texto del cronometro y la duracion de la sesion no se tocan al resumir:
        // el tiempo restante real lo maneja FocusService y llega con el proximo tick
        assertEquals("18:42", state.timerText)
        assertEquals(state.studyDurationMs, state.currentSessionDuration)
    }

    @Test
    fun `onPomodoroReset y onPomodoroFinished dejan isPaused en false`() {
        viewModel.onPomodoroStarted()
        viewModel.onPomodoroPaused()
        viewModel.onPomodoroReset()
        assertFalse(viewModel.currentState.isPaused)

        viewModel.onPomodoroStarted()
        viewModel.onPomodoroPaused()
        viewModel.onPomodoroFinished(wasBreak = false)
        assertFalse(viewModel.currentState.isPaused)
    }

    @Test
    fun `onPomodoroReset vuelve exactamente al estado inicial`() {
        viewModel.onPomodoroStarted()
        viewModel.onTimerTick("12:34")
        viewModel.onPomodoroReset()

        val state = viewModel.currentState
        assertFalse(state.isPomodoroRunning)
        assertFalse(state.isBreakTime)
        assertEquals("INICIAR", state.startButtonText)
        assertEquals("25:00", state.timerText)
        assertEquals(FocusViewModel.STUDY_DURATION_MS, state.currentSessionDuration)
    }

    @Test
    fun `al terminar una sesion de estudio arranca el descanso automaticamente`() {
        viewModel.onPomodoroFinished(wasBreak = false)
        val state = viewModel.currentState
        assertTrue(state.isBreakTime)
        assertTrue(state.isPomodoroRunning)
        assertEquals(FocusViewModel.BREAK_DURATION_MS, state.currentSessionDuration)
        assertEquals("05:00", state.timerText)
        assertEquals("PAUSAR", state.startButtonText)
    }

    @Test
    fun `al terminar el descanso vuelve al estado inicial listo para otra sesion`() {
        viewModel.onPomodoroFinished(wasBreak = false)
        viewModel.onPomodoroFinished(wasBreak = true)

        val state = viewModel.currentState
        assertFalse(state.isBreakTime)
        assertFalse(state.isPomodoroRunning)
        assertEquals("INICIAR", state.startButtonText)
        assertEquals("25:00", state.timerText)
        assertEquals(FocusViewModel.STUDY_DURATION_MS, state.currentSessionDuration)
    }

    @Test
    fun `onTimerTick solo actualiza el texto del cronometro`() {
        viewModel.onPomodoroStarted()
        viewModel.onTimerTick("24:59")
        assertEquals("24:59", viewModel.currentState.timerText)
        assertTrue(viewModel.currentState.isPomodoroRunning) // el resto del estado no se toca
    }

    @Test
    fun `onPdfOpened con paginas marca isPdfLoaded y resetea la pagina a 0`() {
        viewModel.onPageChanged(5) // el usuario venia navegando un PDF anterior
        viewModel.onPdfOpened("content://fake/doc.pdf", totalPages = 10)

        val state = viewModel.currentState
        assertTrue(state.isPdfLoaded)
        assertEquals("content://fake/doc.pdf", state.pdfUriString)
        assertEquals(10, state.totalPages)
        assertEquals(0, state.currentPageIndex)
    }

    @Test
    fun `onPdfOpened con 0 paginas no marca isPdfLoaded`() {
        viewModel.onPdfOpened("content://fake/empty.pdf", totalPages = 0)
        assertFalse(viewModel.currentState.isPdfLoaded)
    }

    @Test
    fun `onPdfClosed limpia todo el estado del PDF`() {
        viewModel.onPdfOpened("content://fake/doc.pdf", totalPages = 10)
        viewModel.onPageChanged(3)

        viewModel.onPdfClosed()

        val state = viewModel.currentState
        assertNull(state.pdfUriString)
        assertFalse(state.isPdfLoaded)
        assertEquals(0, state.totalPages)
        assertEquals(0, state.currentPageIndex)
    }

    @Test
    fun `onPageChanged y onZoomChanged actualizan solo su propio campo`() {
        viewModel.onPdfOpened("content://fake/doc.pdf", totalPages = 10)
        viewModel.onPageChanged(4)
        viewModel.onZoomChanged(3.5f)

        val state = viewModel.currentState
        assertEquals(4, state.currentPageIndex)
        assertEquals(3.5f, state.zoomLevel)
        assertEquals(10, state.totalPages) // no se pierde por los otros cambios
    }

    @Test
    fun `onNightModeChanged alterna el flag de modo nocturno`() {
        assertFalse(viewModel.currentState.isNightModeActive)
        viewModel.onNightModeChanged(true)
        assertTrue(viewModel.currentState.isNightModeActive)
        viewModel.onNightModeChanged(false)
        assertFalse(viewModel.currentState.isNightModeActive)
    }

    @Test
    fun `onLuxUpdated guarda el ultimo valor de luz`() {
        viewModel.onLuxUpdated(42)
        assertEquals(42, viewModel.currentState.currentLuxValue)
    }

    @Test
    fun `showHealthAlert y hideHealthAlert controlan el banner de salud`() {
        viewModel.showHealthAlert("¡MUY CERCA!", isPositive = false)
        var alert = viewModel.currentState.healthAlert
        assertEquals("¡MUY CERCA!", alert?.message)
        assertFalse(alert!!.isPositive)

        viewModel.hideHealthAlert()
        assertNull(viewModel.currentState.healthAlert)
    }

    @Test
    fun `showLockOverlay y hideLockOverlay controlan el mensaje de bloqueo`() {
        viewModel.showLockOverlay("¡CONCÉNTRATE!")
        assertEquals("¡CONCÉNTRATE!", viewModel.currentState.lockMessage)

        viewModel.hideLockOverlay()
        assertNull(viewModel.currentState.lockMessage)
    }

    @Test
    fun `setPhysicalStudyModeNotified evita notificar mas de una vez por sesion`() {
        assertFalse(viewModel.currentState.physicalStudyModeNotified)
        viewModel.setPhysicalStudyModeNotified(true)
        assertTrue(viewModel.currentState.physicalStudyModeNotified)
        viewModel.setPhysicalStudyModeNotified(false)
        assertFalse(viewModel.currentState.physicalStudyModeNotified)
    }

    @Test
    fun `setPausedByInactivity marca y limpia el flag de pausa por INACTIVO`() {
        assertFalse(viewModel.currentState.pausedByInactivity)
        viewModel.setPausedByInactivity(true)
        assertTrue(viewModel.currentState.pausedByInactivity)
        viewModel.setPausedByInactivity(false)
        assertFalse(viewModel.currentState.pausedByInactivity)
    }

    @Test
    fun `onPomodoroResumed limpia pausedByInactivity al recuperar el modo tras INACTIVO`() {
        viewModel.onPomodoroStarted()
        viewModel.onPomodoroPaused()
        viewModel.setPausedByInactivity(true) // simula que MainActivity detecto INACTIVE

        viewModel.onPomodoroResumed() // simula que se detecto movimiento de nuevo

        assertFalse(viewModel.currentState.pausedByInactivity)
        assertTrue(viewModel.currentState.isPomodoroRunning)
    }

    @Test
    fun `onPomodoroStarted y onPomodoroReset tambien limpian pausedByInactivity`() {
        viewModel.setPausedByInactivity(true)
        viewModel.onPomodoroReset()
        assertFalse(viewModel.currentState.pausedByInactivity)

        viewModel.setPausedByInactivity(true)
        viewModel.onPomodoroStarted()
        assertFalse(viewModel.currentState.pausedByInactivity)
    }

    @Test
    fun `onDurationsChanged aplica la nueva duracion cuando no hay sesion corriendo`() {
        viewModel.onDurationsChanged(studyDurationMs = 50 * 60 * 1000L, breakDurationMs = 10 * 60 * 1000L)

        val state = viewModel.currentState
        assertEquals(50 * 60 * 1000L, state.studyDurationMs)
        assertEquals(10 * 60 * 1000L, state.breakDurationMs)
        assertEquals(50 * 60 * 1000L, state.currentSessionDuration)
        assertEquals("50:00", state.timerText)
    }

    @Test
    fun `onDurationsChanged no cambia el texto del cronometro si hay una sesion corriendo`() {
        viewModel.onPomodoroStarted()
        viewModel.onTimerTick("12:34")

        viewModel.onDurationsChanged(studyDurationMs = 50 * 60 * 1000L, breakDurationMs = 10 * 60 * 1000L)

        val state = viewModel.currentState
        assertEquals("12:34", state.timerText) // no se pisa el cronometro en curso
        assertEquals(50 * 60 * 1000L, state.studyDurationMs) // pero la config nueva ya quedo guardada
    }

    @Test
    fun `onPomodoroStarted usa la duracion de descanso configurada, no la de estudio`() {
        viewModel.onDurationsChanged(studyDurationMs = 50 * 60 * 1000L, breakDurationMs = 10 * 60 * 1000L)
        viewModel.onPomodoroFinished(wasBreak = false) // pasa a modo descanso
        viewModel.onPomodoroPaused()

        viewModel.onPomodoroStarted() // retoma el descanso

        assertEquals(10 * 60 * 1000L, viewModel.currentState.currentSessionDuration)
    }

    @Test
    fun `regresion C1 - una rotacion simulada no pierde el estado de una sesion activa`() {
        // Simula lo que MainActivity haría antes de rotar: PDF abierto, en la página 3,
        // con zoom y Pomodoro corriendo.
        viewModel.onPdfOpened("content://fake/doc.pdf", totalPages = 20)
        viewModel.onPageChanged(3)
        viewModel.onZoomChanged(2.5f)
        viewModel.onPomodoroStarted()
        viewModel.onTimerTick("18:42")

        // "Rotación": en el bug original, MainActivity se recreaba con variables locales
        // reiniciadas a sus valores por defecto. Aquí simplemente volvemos a leer el mismo
        // ViewModel (como haría ViewModelProvider tras la recreación) y verificamos que
        // nada se perdió.
        val restored = viewModel.currentState
        assertTrue(restored.isPomodoroRunning)
        assertEquals("18:42", restored.timerText)
        assertEquals("content://fake/doc.pdf", restored.pdfUriString)
        assertEquals(3, restored.currentPageIndex)
        assertEquals(2.5f, restored.zoomLevel)
        assertEquals(20, restored.totalPages)
    }
}
