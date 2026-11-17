package com.studydock.app

import com.studydock.app.data.WorkoutLog
import com.studydock.app.ui.BarbellPlateCalculator
import com.studydock.app.ui.IncrementalReadingEngine
import com.studydock.app.ui.UniversalDomainGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fase4UiIntegrationTest {

    @Test
    fun barbellPlateCalculatorAndPlateauDetector() {
        // 100kg total -> (100 - 20)/2 = 40kg per side -> 25.0 + 15.0
        val plates100 = BarbellPlateCalculator.platesPerSide(100.0, barKg = 20.0)
        assertEquals(listOf(25.0, 15.0), plates100)

        // 20kg bar only -> empty
        assertTrue(BarbellPlateCalculator.platesPerSide(20.0).isEmpty())

        val w1 = WorkoutLog(id = 1L, title = "Treino A", exercises = "Supino · 10 rep · 60s", durationMinutes = 45)
        val w2 = WorkoutLog(id = 2L, title = "Treino A", exercises = "Supino · 10 rep · 60s", durationMinutes = 45)
        val w3 = WorkoutLog(id = 3L, title = "Treino A", exercises = "Supino · 10 rep · 60s", durationMinutes = 45)
        assertTrue(BarbellPlateCalculator.isExerciseInPlateau("Supino", listOf(w1, w2, w3)))

        val wProg = WorkoutLog(id = 4L, title = "Treino A", exercises = "Supino · 12 rep · 60s", durationMinutes = 45)
        assertFalse(BarbellPlateCalculator.isExerciseInPlateau("Supino", listOf(wProg, w2, w3)))
    }

    @Test
    fun incrementalReadingAndUniversalDomainGraph() {
        val (title, body) = IncrementalReadingEngine.buildIncrementalExtract("Análise Real", 42, "Todo conjunto compacto é fechado e limitado.")
        assertTrue(title.contains("Análise Real") && title.contains("p. 42"))
        assertTrue(body.contains("#leitura-incremental"))

        val cluster = UniversalDomainGraph.buildTopicClusterSummary("Desigualdades", 5, 18, 2, 1640)
        assertEquals("Desigualdades · 5 notas · 18 cards · 2 livros · ELO 1640", cluster)
    }
}
