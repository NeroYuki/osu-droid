package com.osudroid.beatmaps.parser

/**
 * Thrown when a `.osu` file declares a game mode that is not osu!standard.
 *
 * Callers that tolerate non-standard difficulties (such as archive imports) can catch this specific
 * type and skip the file, while still treating genuinely malformed standard files as errors.
 */
class UnsupportedBeatmapModeException : IllegalArgumentException("Not an osu!standard beatmap")
