package app.fntv.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** 飞牛接口字段兼容读取（snake_case / camelCase / 字符串数字 都见过）。 */
fun JSONObject.str(vararg keys: String): String {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)
        val s = when (v) {
            is String -> v
            is Number -> v.toString()
            else -> ""
        }.trim()
        if (s.isNotEmpty() && s != "null") return s
    }
    return ""
}

fun JSONObject.num(vararg keys: String): Int {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        when (val v = opt(k)) {
            is Int -> return v
            is Long -> return v.toInt()
            is Double -> return v.toInt()
            is String -> v.trim().toIntOrNull()?.let { return it }
            is Boolean -> return if (v) 1 else 0
        }
    }
    return 0
}

fun JSONObject.dbl(vararg keys: String): Double {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        when (val v = opt(k)) {
            is Double -> return v
            is Int -> return v.toDouble()
            is String -> v.trim().toDoubleOrNull()?.let { return it }
        }
    }
    return 0.0
}

/** posters/backdrops 可能是字符串、字符串数组或对象数组，取面积最大的一张。 */
fun JSONObject.imagePath(vararg keys: String): String? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        when (val v = opt(k)) {
            is String -> if (v.isNotBlank()) return v
            is JSONArray -> {
                var best: String? = null
                var bestArea = -1
                for (i in 0 until v.length()) {
                    when (val it = v.opt(i)) {
                        is String -> if (it.isNotBlank() && bestArea < 0) {
                            best = it; bestArea = 0
                        }
                        is JSONObject -> {
                            val p = it.optString("path").ifEmpty { it.optString("url") }
                            if (p.isBlank()) continue
                            val area = it.optInt("width") * it.optInt("height")
                            if (area > bestArea) { best = p; bestArea = area }
                        }
                    }
                }
                if (best != null) return best
            }
        }
    }
    return null
}

data class Lib(
    val guid: String,
    val title: String,
    val category: String,
    val poster: String?,
) {
    companion object {
        fun from(o: JSONObject): Lib = Lib(
            guid = o.str("guid"),
            title = o.str("title", "name", "mdb_name").ifEmpty { "媒体库" },
            category = o.str("category", "mdb_category"),
            poster = o.imagePath("posters", "poster", "backdrops", "backdrop"),
        )
    }
}

data class MediaItem(
    val guid: String,
    val title: String,
    val tvTitle: String,
    val type: String,
    val poster: String?,
    val overview: String,
    val rating: Double,
    val year: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val watched: Boolean,
    val ts: Int,
    val duration: Int,
    val episodes: Int,
    val seasons: Int,
    val raw: JSONObject? = null,
) {
    /** 卡片上的一行小字：S1E5 / 12 集 / 2024。 */
    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (seasonNumber > 0 || episodeNumber > 0) {
                parts += buildString {
                    if (seasonNumber > 0) append("S${seasonNumber}")
                    if (episodeNumber > 0) append("E${episodeNumber}")
                }
            }
            if (episodes > 0) parts += "$episodes 集"
            else if (seasons > 1) parts += "$seasons 季"
            if (year.isNotEmpty()) parts += year
            return parts.joinToString(" · ")
        }

    val progress: Float
        get() = if (duration > 0 && ts > 0) (ts.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val isFolder: Boolean get() = type == "Directory" || type == "Folder"
    val isPlayableSingle: Boolean get() = type == "Movie" || type == "Video" || type == "LiveChannel"

    companion object {
        fun from(o: JSONObject): MediaItem {
            val title = o.str("title", "name", "tv_title").ifEmpty { "未命名" }
            val tvTitle = o.str("tv_title", "parent_title")
            val type = o.str("type")
            val duration = o.num("duration")
            val ts = o.num("ts", "watched_ts")
            val watchedFlag = o.num("watched", "is_watched")
            return MediaItem(
                guid = o.str("guid"),
                title = title,
                tvTitle = tvTitle,
                type = type,
                poster = o.imagePath("poster", "posters", "still_path", "backdrop"),
                overview = o.str("overview", "plot", "description"),
                rating = o.dbl("vote_average", "rating"),
                year = yearOf(o.str("release_date", "air_date", "premiere_date")),
                seasonNumber = o.num("season_number", "seasonNumber"),
                episodeNumber = o.num("episode_number", "episodeNumber"),
                watched = watchedFlag >= 1 || (duration > 0 && ts >= duration - 30 && ts > 0),
                ts = ts,
                duration = duration,
                episodes = o.num("local_number_of_episodes").takeIf { it > 0 }
                    ?: o.num("number_of_episodes"),
                seasons = o.num("local_number_of_seasons").takeIf { it > 0 }
                    ?: o.num("number_of_seasons"),
                raw = o,
            )
        }

        private fun yearOf(date: String): String {
            if (date.length >= 4 && date.take(4).all { it.isDigit() }) return date.take(4)
            return ""
        }
    }
}

data class Season(
    val guid: String,
    val title: String,
    val number: Int,
    val poster: String?,
) {
    companion object {
        fun from(o: JSONObject): Season = Season(
            guid = o.str("guid"),
            title = o.str("title", "name").ifEmpty { "第 ${o.num("season_number")} 季" },
            number = o.num("season_number", "seasonNumber"),
            poster = o.imagePath("poster", "posters"),
        )
    }
}

data class Episode(
    val guid: String,
    val title: String,
    val number: Int,
    val watched: Boolean,
    val ts: Int,
    val duration: Int,
    val poster: String?,
) {
    companion object {
        fun from(o: JSONObject): Episode {
            val duration = o.num("duration")
            val ts = o.num("ts", "watched_ts")
            return Episode(
                guid = o.str("guid"),
                title = o.str("title", "name").ifEmpty { "第 ${o.num("episode_number")} 集" },
                number = o.num("episode_number", "episodeNumber"),
                watched = o.num("watched", "is_watched") >= 1 || (duration > 0 && ts >= duration - 30 && ts > 0),
                ts = ts,
                duration = duration,
                poster = o.imagePath("poster", "still_path", "posters"),
            )
        }
    }
}

data class PlayRequest(
    val seriesTitle: String,
    val episodes: List<Episode>,
    val startIndex: Int,
)

data class PlayInfo(
    val guid: String,
    val mediaGuid: String,
    val videoGuid: String,
    val audioGuid: String,
    val subtitleGuid: String,
    val positionSec: Int,
    val durationSec: Int,
    val title: String,
    val type: String,
    val tvTitle: String,
    val season: Int,
    val episode: Int,
) {
    val displayTitle: String
        get() {
            if (tvTitle.isNotEmpty() && tvTitle != title) {
                return if (episode > 0) "$tvTitle S${season}E${episode} · $title" else "$tvTitle · $title"
            }
            return title
        }

    companion object {
        fun from(info: JSONObject, fallbackTitle: String): PlayInfo {
            val item = info.optJSONObject("item") ?: JSONObject()
            val tvTitle = item.str("tv_title", "parent_title")
            val title = item.str("title", "name").ifEmpty { fallbackTitle }
            val duration = item.num("duration")
            val ts = item.num("ts").takeIf { it > 0 } ?: info.num("ts")
            return PlayInfo(
                guid = info.str("guid"),
                mediaGuid = info.str("media_guid"),
                videoGuid = info.str("video_guid"),
                audioGuid = info.str("audio_guid"),
                subtitleGuid = info.str("subtitle_guid"),
                positionSec = ts,
                durationSec = duration,
                title = title,
                type = info.str("type"),
                tvTitle = tvTitle,
                season = item.num("season_number"),
                episode = item.num("episode_number"),
            )
        }
    }
}