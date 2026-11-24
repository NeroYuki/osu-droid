package com.osudroid.multiplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.scoring.StatisticV2

@RunWith(RobolectricTestRunner::class)
class MultiplayerSliderStatisticsTest {
    @Before
    fun setUp() {
        Config.setOnlineUsername("Player")
    }

    @Test
    fun `final score JSON includes actual slider counts and preserves them on receipt`() {
        val forcedScore = Int.MAX_VALUE.toLong() + 1
        val statistic = StatisticV2().apply {
            playerName = "Player"
            sliderHeadHits = 3
            sliderTickHits = 4
            sliderRepeatHits = 5
            sliderEndHits = 6
            setForcedScore(forcedScore)
        }
        val json = statistic.toJson()
        assertEquals(3, json.getInt("sliderHeadHit"))
        assertEquals(4, json.getInt("sliderTickHit"))
        assertEquals(5, json.getInt("sliderRepeatHit"))
        assertEquals(6, json.getInt("sliderEndHit"))
        assertEquals(forcedScore, json.getLong("score"))

        val restored = jsonToStatistic(json)
        assertEquals(3, restored.sliderHeadHits)
        assertEquals(4, restored.sliderTickHits)
        assertEquals(5, restored.sliderRepeatHits)
        assertEquals(6, restored.sliderEndHits)
        assertEquals(forcedScore, restored.getTotalScoreWithMultiplier())
    }

    @Test
    fun `zero counts are serialized and old result payloads still parse`() {
        val json = StatisticV2().apply { playerName = "Player" }.toJson()
        for (field in listOf("sliderHeadHit", "sliderTickHit", "sliderRepeatHit", "sliderEndHit")) {
            assertEquals(0, json.getInt(field))
            json.remove(field)
        }
        val restored = jsonToStatistic(json)
        assertEquals(0, restored.sliderHeadHits)
        assertEquals(0, restored.sliderTickHits)
        assertEquals(0, restored.sliderRepeatHits)
        assertEquals(0, restored.sliderEndHits)
    }

    @Test
    fun `unknown slider counts remain unknown across JSON and score info`() {
        val statistic = StatisticV2().apply {
            playerName = "Player"
            sliderHeadHits = -1
            sliderTickHits = -1
            sliderRepeatHits = -1
            sliderEndHits = -1
        }
        val json = statistic.toJson()
        for (field in listOf("sliderHeadHit", "sliderTickHit", "sliderRepeatHit", "sliderEndHit")) {
            assertEquals(-1, json.getInt(field))
        }

        val restored = jsonToStatistic(json)
        assertEquals(-1, restored.sliderHeadHits)
        assertEquals(-1, restored.sliderTickHits)
        assertEquals(-1, restored.sliderRepeatHits)
        assertEquals(-1, restored.sliderEndHits)

        restored.mark = "A"
        val scoreInfo = restored.toScoreInfo()
        assertNull(scoreInfo.sliderHeadHits)
        assertNull(scoreInfo.sliderTickHits)
        assertNull(scoreInfo.sliderRepeatHits)
        assertNull(scoreInfo.sliderEndHits)
    }
}
