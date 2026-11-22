package com.osudroid.multiplayer.api

import android.util.Log
import com.osudroid.BuildSettings
import com.osudroid.debug.MockRoom
import com.osudroid.multiplayer.api.data.Room
import com.osudroid.multiplayer.api.data.RoomBeatmap
import com.osudroid.multiplayer.api.data.RoomMods
import com.osudroid.multiplayer.api.data.RoomStatus
import com.osudroid.multiplayer.api.data.TeamMode
import com.osudroid.multiplayer.api.data.WinCondition
import com.osudroid.multiplayer.api.data.parseGameplaySettings
import com.reco1l.framework.net.JsonArrayRequest
import com.reco1l.framework.net.JsonObjectRequest
import com.osudroid.online.AttestationState
import com.osudroid.online.HardwareAttestationManager
import ru.nsu.ccfit.zuev.osu.SecurityUtils
import org.json.JSONObject

object LobbyAPI {

    /**
     * The hostname.
     */
    const val HOST = "https://osudroid.kansenindex.dev/api/tournament"

    /**
     * The invite link host.
     */
    const val INVITE_HOST = "https://tournament.odmp"


    /**
     * Endpoint to get a rooms list.
     */
    private const val GET_ROOMS = "/getrooms"

    /**
     * Endpoint to create a room.
     */
    private const val CREATE_ROOM = "/createroom"


    /**
     * Get room list.
     */
    fun getRooms(query: String?, uid: Long, sessionId: String): List<Room> {

        if (BuildSettings.MOCK_MULTIPLAYER) {
            return listOf(
                MockRoom(),
                MockRoom(),
                MockRoom()
            )
        }

        JsonArrayRequest("$HOST$GET_ROOMS").use {
            val sign = SecurityUtils.signRequest("${query ?: ""}_${uid}_${sessionId}")

            it.buildUrl {
                addQueryParameter("query", query ?: "")
                addQueryParameter("uid", uid.toString())
                addQueryParameter("sessionId", sessionId)
                addQueryParameter("sign", sign)
            }

            return List(it.execute().json.length()) { i ->

                val json = it.json.optJSONObject(i)

                return@List try {

                    Room(
                        id = json.getLong("id"),
                        name = json.getString("name"),
                        isLocked = json.getBoolean("isLocked"),
                        maxPlayers = json.getInt("maxPlayers"),
                        mods = RoomMods(json.getJSONArray("mods")),
                        gameplaySettings = parseGameplaySettings(json.getJSONObject("gameplaySettings")),
                        teamMode = TeamMode.fromWire(json.getString("teamMode")) ?: TeamMode.HeadToHead,
                        winCondition = WinCondition.fromWire(json.getString("winCondition")) ?: WinCondition.ScoreV1,
                        playerCount = json.getInt("playerCount"),
                        playerNames = json.optString("playerNames", ""),
                        status = RoomStatus.fromWire(json.getString("status"))
                    )

                } catch (_: Exception) {
                    null
                }

            }.filterNotNull()
        }
    }

    /**
     * Create room and get the ID.
     */
    fun createRoom(name: String, beatmap: RoomBeatmap?, hostUID: Long, sessionId: String, password: String? = null, maxPlayers: Int = 8): Long {

        if (BuildSettings.MOCK_MULTIPLAYER) {
            return 1
        }

        // Blank passwords are not sent, so they must not be signed either.
        val password = password?.takeUnless { it.isBlank() }

        JsonObjectRequest("$HOST$CREATE_ROOM").use { request ->
            var signPayload = "${hostUID}_${name}_${maxPlayers}"
            if (password != null) {
                signPayload += "_${password}"
            }
            signPayload += "_${RoomAPI.API_VERSION}_${sessionId}"
            val sign = SecurityUtils.signRequest(signPayload)

            request.buildRequestBody {

                put("hostUid", hostUID)
                put("name", name)
                put("maxPlayers", maxPlayers)

                if (beatmap != null) {
                    put("beatmap", JSONObject().apply {
                        put("md5", beatmap.md5)
                        put("title", beatmap.title)
                        put("artist", beatmap.artist)
                        put("creator", beatmap.creator)
                        put("version", beatmap.version)
                    })
                }

                if (password != null) {
                    put("password", password)
                }

                put("version", RoomAPI.API_VERSION)
                put("sessionId", sessionId)
                put("sign", sign)

                // --- Hardware Attestation: sign the room creation payload ---
                // Signs "hostUID_name_maxPlayers_sessionId" so the server can verify this
                // createRoom request was issued by the same hardware-attested device session.
                if (AttestationState.sessionAttestationReady) {
                    try {
                        val sigPayload = "${hostUID}_${name}_${maxPlayers}_${sessionId}"
                        val sig = HardwareAttestationManager.signToBase64(sigPayload)
                        if (sig != null) {
                            put("attestationSignature", sig)
                            Log.i("LobbyAPI", "Attestation signature attached to createRoom.")
                        }
                    } catch (e: Exception) {
                        Log.e("LobbyAPI", "Failed to sign createRoom: ${e.message}", e)
                    }
                }
                // -------------------------------------------------------------
            }

            return request.execute().json.getString("id").toLong()
        }
    }
}