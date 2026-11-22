package app.fntv.tv.player

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.fntv.tv.data.DanmakuApi
import app.fntv.tv.data.FnApi
import app.fntv.tv.data.FnException
import app.fntv.tv.data.PlayInfo
import app.fntv.tv.data.Prefs
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.FnTvTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 播放页。
 *
 * 职责：
 *  1. 解析 DetailScreen 传来的 episodes / index / series_title，按集播放；
 *  2. playInfo 取 mediaGuid → streamUrl 交给 ExoPlayer（OkHttp 数据源，带鉴权头）；
 *  3. 每 10 秒 / 暂停 / 切集 / 退出回传观看进度；
 *  4. 播放结束按 Prefs.autoNextEpisode 自动连播（复用同一个 player）；
 *  5. 遥控器按键统一在根节点分发，Compose 覆盖层画控制条、轨道选择与错误页。
 */
class PlayerActivity : ComponentActivity() {

    /** 进后台前是否在播：onResume 时据此恢复，避免“回来就停住” */
    private var wasPlaying = false

    /** 播放页内部使用的一集：只依赖 guid / title / number，不碰 Models.Episode。 */
    private data class PlayItem(val guid: String, val title: String, val number: Int)

    /** 所有会被 Compose 观察的页面状态。 */
    private val ui = PlayerUiState()

    /**
     * 独立于 Activity 生命周期的作用域，只用于退出/销毁前的“尽力上报”：
     * lifecycleScope 在 finish 后会被取消，长这样一次网络请求可能来不及发出去。
     */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var player: ExoPlayer? = null
    private var info: PlayInfo? = null

    private var items: List<PlayItem> = emptyList()
    private var startIndex = 0
    private var currentIndex = 0
    private var seriesTitle = ""

    /** play/info 里记录的续播点，等首次 STATE_READY 再 seek（只有 > 3 秒才跳）。 */
    private var pendingSeekMs = 0L

    /** 正在切集，避免连播/重复 STATE_ENDED 触发两次加载。 */
    private var switching = false

    /** 已经在退出流程里，避免返回键 + onBackPressed 双触发。 */
    private var exiting = false

    /** 上次上报进度的时间（elapsedRealtime），用于「每 10 秒一次」。 */
    private var lastReportAt = 0L

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 防御式初始化：万一是被外部直接拉起的，也能读到登录态与播放偏好。
        Prefs.init(applicationContext)
        if (!Repo.isLoggedIn) Repo.initFromPrefs()

        readExtras()
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 遥控器返回键兜底：按键事件之外还有系统返回通道。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitWithReport()
            }
        })

        if (items.isEmpty()) {
            toast("没有可播放的剧集")
            finish()
            return
        }
        if (!Repo.isLoggedIn) {
            toast("登录已过期，请重新登录")
            Repo.logout()
            setResult(RESULT_SESSION_EXPIRED)
            finish()
            return
        }

        val exo = ensurePlayer()
        setContent {
            FnTvTheme {
                PlayerScreen(
                    player = exo,
                    ui = ui,
                    onKeyEvent = ::handleKeyEvent,
                    onAction = ::onAction,
                    onSelectTrack = ::selectTrack,
                    onCloseOverlay = {
                        ui.overlay = PlayerOverlay.NONE
                        revealControls()
                    },
                    onErrorChoice = { choice ->
                        ui.errorChoice = choice
                        if (choice == 0) retryCurrent() else exitWithReport()
                    },
                )
            }
        }

        startTicker()
        lifecycleScope.launch { playAt(startIndex) }
    }

    override fun onPause() {
        super.onPause()
        // 进后台/被系统弹窗打断：先暂停，并回传一次进度。
        wasPlaying = player?.isPlaying == true
        player?.pause()
        reportProgress()
    }

    override fun onResume() {
        super.onResume()
        // 从后台/系统弹窗回来：刚才在播就继续播
        if (wasPlaying) {
            wasPlaying = false
            player?.play()
        }
    }

    override fun onDestroy() {
        // release 之前再尽力上报一次。
        reportProgress(bestEffort = true)
        player?.let { exo ->
            exo.removeListener(playerListener)
            exo.release()
        }
        player = null
        super.onDestroy()
    }

    // ───────────────────────── Intent extras ─────────────────────────

    private fun readExtras() {
        seriesTitle = intent.getStringExtra("series_title").orEmpty().trim()
        ui.seriesTitle = seriesTitle

        items = parseEpisodes(intent.getStringExtra("episodes"))
        val rawIndex = intent.getIntExtra("index", 0)
        startIndex = if (items.isEmpty()) 0 else rawIndex.coerceIn(0, items.size - 1)
        currentIndex = startIndex
        ui.episodeIndex = startIndex
        ui.episodeCount = items.size
        ui.hasNext = startIndex + 1 < items.size
        ui.episodeTitle = items.getOrNull(startIndex)?.title.orEmpty()
    }

    /** episodes 是 [{"guid":"..","title":"..","number":1}] 的 JSON 字符串。 */
    private fun parseEpisodes(raw: String?): List<PlayItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val guid = o.optString("guid").trim()
                if (guid.isEmpty()) return@mapNotNull null
                val number = o.optInt("number", i + 1)
                val title = o.optString("title").trim().ifEmpty { "第 $number 集" }
                PlayItem(guid, title, number)
            }
        }.getOrDefault(emptyList())
    }

    // ───────────────────────── 播放器构建 ─────────────────────────

    private fun ensurePlayer(): ExoPlayer = player ?: buildPlayer().also { player = it }

    private fun buildPlayer(): ExoPlayer {
        // OkHttp 自带重定向跟随（含跨协议跳转），等价于 DefaultHttpDataSource 的
        // setAllowCrossProtocolRedirects(true)；这里直接显式打开，避免 NAS 反代跳端口时断流。
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                // 和 FnApi.call 同样的鉴权头，否则 /media/range 会返回 401/网页。
                val token = Repo.token
                val request = chain.request().newBuilder()
                    .header("Authorization", token)
                    .header("Cookie", "mode=relay; Trim-MC-token=$token")
                    .header("User-Agent", FnApi.UA)
                    .build()
                chain.proceed(request)
            }
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exo.setAudioAttributes(audioAttributes, true)
        exo.addListener(playerListener)
        return exo
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    ui.loading = false
                    val seekMs = pendingSeekMs
                    if (seekMs > 0L) {
                        pendingSeekMs = 0L
                        player?.seekTo(seekMs)
                    }
                }

                Player.STATE_ENDED -> handleEnded()

                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.isPlaying = isPlaying
        }

        override fun onTracksChanged(tracks: Tracks) {
            ui.subtitleOn = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && group.isSelected
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            showError(friendlyPlaybackError(error))
        }
    }

    private fun friendlyPlaybackError(error: PlaybackException): String {
        val detail = error.message.orEmpty()
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "无法连接 NAS，请检查网络或服务器地址\n$detail"

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "服务器拒绝了这个播放请求（可能是登录已过期）\n$detail"

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "播放地址不存在，文件可能已被移除\n$detail"

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "这台设备解不了这个编码，试试换个版本的文件\n$detail"

            else -> detail.ifEmpty { "播放出错，请重试" }
        }
    }

    // ───────────────────────── 起播 / 切集 ─────────────────────────

    /**
     * 播放第 index 集。
     * @param reportBefore 切集时先把当前集的进度回传，再换片。
     */
    private suspend fun playAt(index: Int, reportBefore: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val item = items.getOrNull(index) ?: return
        if (reportBefore) reportProgress()

        ui.loading = true
        ui.error = null
        ui.errorChoice = 0
        ui.overlay = PlayerOverlay.NONE
        ui.positionMs = 0L
        ui.durationMs = 0L

        try {
            val playInfo = Repo.playInfo(item.guid, item.title)
            info = playInfo
            currentIndex = index
            ui.episodeIndex = index
            ui.episodeCount = items.size
            ui.hasNext = index + 1 < items.size
            ui.episodeTitle = item.title.ifEmpty { playInfo.title }
            if (ui.seriesTitle.isBlank()) ui.seriesTitle = playInfo.tvTitle.ifBlank { seriesTitle }

            val url = Repo.streamUrl(playInfo.mediaGuid)
            if (url.isBlank() || playInfo.mediaGuid.isEmpty()) {
                showError("服务器没有返回可用的播放地址")
                return
            }

            val exo = ensurePlayer()
            // play/info 的 ts 是上次看到的位置：只有超过 3 秒才续播，避免开头误跳。
            pendingSeekMs = if (playInfo.positionSec > 3) playInfo.positionSec * 1000L else 0L
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.setPlaybackSpeed(ui.speed)
            exo.prepare()
            exo.playWhenReady = true
            lastReportAt = SystemClock.elapsedRealtime()

            // 弹幕与播放地址相互独立：另起协程拉，晚到/失败都不影响已经在播的视频
            loadDanmaku(playInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FnException) {
            if (e.code == -2) {
                // 登录过期：清掉本机登录态，并告知上层回登录页
                ui.loading = false
                Repo.logout()
                toast("登录已过期，请重新登录")
                setResult(RESULT_SESSION_EXPIRED)
                finish()
                return
            }
            showError(e.message ?: "获取播放信息失败")
        } catch (e: Exception) {
            showError(e.message ?: "播放失败")
        }
    }

    // ───────────────────────── 弹幕 ─────────────────────────

    /**
     * 拉本集弹幕。
     *
     * 约定：
     *  - 开关关 / 没配弹幕服务 / 拿不到剧名 → 清空并静默返回，控制条上什么也不显示；
     *  - 请求成功但没弹幕 → `danmakuHint = "未找到弹幕"`，只作一行提示，不弹错误；
     *  - 用 currentIndex 防串集：切集后旧请求回来直接丢弃，避免上一集的弹幕盖在新片上。
     */
    private fun loadDanmaku(playInfo: PlayInfo) {
        val title = playInfo.tvTitle.ifBlank { playInfo.title }
        if (!ui.danmakuOn || Prefs.danmuApiBase.isBlank() || title.isBlank()) {
            ui.danmaku = emptyList()
            ui.danmakuLoading = false
            ui.danmakuHint = ""
            return
        }

        val requestIndex = currentIndex
        ui.danmaku = emptyList()
        ui.danmakuHint = ""
        ui.danmakuLoading = true

        lifecycleScope.launch {
            val list = try {
                DanmakuApi.fetch(title, playInfo.season, playInfo.episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // DanmakuApi 内部已经兜过异常，这层只是保险：弹幕永远不能把播放页搞崩
                emptyList()
            }
            if (isFinishing || isDestroyed) return@launch
            if (currentIndex != requestIndex) return@launch

            ui.danmaku = list
            ui.danmakuLoading = false
            ui.danmakuHint = if (list.isEmpty()) "未找到弹幕" else ""
        }
    }

    /** 控制条上的「弹幕 开/关」：改内存状态并写回偏好；关掉时立刻停画。 */
    private fun toggleDanmaku() {
        ui.danmakuOn = !ui.danmakuOn
        Prefs.danmakuEnabled = ui.danmakuOn

        if (!ui.danmakuOn) {
            ui.danmakuLoading = false
            ui.danmakuHint = ""
            toast("弹幕已关闭")
            return
        }

        if (Prefs.danmuApiBase.isBlank()) {
            // 开关本身记住，但得先告诉用户去哪里填地址，否则「打开了却没反应」
            toast("请先在设置里填写弹幕 API 地址")
            return
        }

        toast("弹幕已开启")
        // 起播时开关是关的，所以本集可能根本没拉过弹幕：打开后补拉一次
        if (ui.danmaku.isEmpty() && !ui.danmakuLoading) {
            info?.let { loadDanmaku(it) }
        }
    }

    private fun showError(message: String) {
        ui.loading = false
        ui.error = message
        ui.errorChoice = 0
        ui.overlay = PlayerOverlay.NONE
        ui.controlsVisible = false
    }

    private fun retryCurrent() {
        ui.error = null
        ui.errorChoice = 0
        ui.loading = true
        lifecycleScope.launch { playAt(currentIndex) }
    }

    private fun nextEpisode() {
        if (currentIndex + 1 >= items.size) {
            toast("已经是最后一集了")
            return
        }
        lifecycleScope.launch { playAt(currentIndex + 1, reportBefore = true) }
    }

    /** 播放结束：按偏好自动连播，否则停在控制条上。 */
    private fun handleEnded() {
        ui.isPlaying = false
        if (switching) return
        reportProgress()

        if (Prefs.autoNextEpisode && currentIndex + 1 < items.size) {
            switching = true
            lifecycleScope.launch {
                try {
                    playAt(currentIndex + 1, reportBefore = false)
                } finally {
                    switching = false
                }
            }
        } else {
            ui.selection = PlayerAction.PLAY_PAUSE
            revealControls()
        }
    }

    // ───────────────────────── 进度回传 ─────────────────────────

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                player?.let { exo ->
                    ui.positionMs = exo.currentPosition.coerceAtLeast(0L)
                    val duration = exo.duration
                    if (duration > 0L) ui.durationMs = duration
                    ui.isPlaying = exo.isPlaying
                }
                maybeReportProgress()
                delay(500L)
            }
        }
    }

    private fun maybeReportProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportAt >= REPORT_INTERVAL_MS) {
            lastReportAt = now
            reportProgress()
        }
    }

    /**
     * 把当前位置回传给飞牛（秒，Int）。失败静默。
     * @param bestEffort 退出/销毁时用不随生命周期取消的 reportScope，尽量把请求发出去。
     */
    private fun reportProgress(bestEffort: Boolean = false) {
        val playInfo = info ?: return
        val exo = player

        val durationSec = when {
            exo != null && exo.duration > 0L -> (exo.duration / 1000L).toInt()
            playInfo.durationSec > 0 -> playInfo.durationSec
            else -> 0
        }
        val positionSec = ((exo?.currentPosition ?: 0L).coerceAtLeast(0L) / 1000L).toInt()
        if (durationSec <= 0 || positionSec <= 0) return

        val server = Repo.server
        val token = Repo.token
        if (server.isEmpty() || token.isEmpty()) return

        val itemGuid = playInfo.guid.ifEmpty { items.getOrNull(currentIndex)?.guid.orEmpty() }
        if (itemGuid.isEmpty() || playInfo.mediaGuid.isEmpty()) return

        val send: suspend () -> Unit = {
            runCatching {
                FnApi.recordProgress(
                    server = server,
                    token = token,
                    itemGuid = itemGuid,
                    mediaGuid = playInfo.mediaGuid,
                    videoGuid = playInfo.videoGuid,
                    audioGuid = playInfo.audioGuid,
                    subtitleGuid = playInfo.subtitleGuid,
                    positionSec = positionSec,
                    durationSec = durationSec,
                )
            }
            Unit
        }

        if (bestEffort) reportScope.launch { send() } else lifecycleScope.launch(Dispatchers.IO) { send() }
    }

    // ───────────────────────── 按键分发 ─────────────────────────

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val key = event.key
        if (!isHandledKey(key)) return false
        // 抬起事件一并吃掉，避免同一次按键触发两遍。
        if (event.type != KeyEventType.KeyDown) return true
        return handleKeyDown(key)
    }

    private fun isHandledKey(key: Key): Boolean =
        key == Key.DirectionCenter || key == Key.Enter ||
            key == Key.DirectionUp || key == Key.DirectionDown ||
            key == Key.DirectionLeft || key == Key.DirectionRight ||
            key == Key.Back ||
            key == Key.MediaPlayPause || key == Key.MediaNext || key == Key.MediaPrevious

    private fun handleKeyDown(key: Key): Boolean {
        // 1) 音轨 / 字幕弹层优先
        if (ui.overlay != PlayerOverlay.NONE) {
            when (key) {
                Key.DirectionUp, Key.DirectionLeft -> moveTrackSelection(-1)
                Key.DirectionDown, Key.DirectionRight -> moveTrackSelection(1)
                Key.DirectionCenter, Key.Enter -> selectTrack(ui.trackSelected)
                Key.Back -> {
                    ui.overlay = PlayerOverlay.NONE
                    revealControls()
                }

                else -> return false
            }
            ui.tick++
            return true
        }

        // 2) 错误弹层
        if (ui.error != null) {
            when (key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    ui.errorChoice = if (ui.errorChoice == 0) 1 else 0
                    revealControls()
                }

                Key.DirectionCenter, Key.Enter -> {
                    if (ui.errorChoice == 0) retryCurrent() else exitWithReport()
                    revealControls()
                }

                Key.Back -> exitWithReport()

                else -> return false
            }
            return true
        }

        // 3) 正常播放态：任何按键都会把控制条唤出来（按键本身的功能同时生效）
        when (key) {
            Key.Back -> {
                exitWithReport()
                return true
            }

            Key.DirectionUp, Key.DirectionDown -> {
                if (!ui.controlsVisible) {
                    // 第一次按只唤出控件，避免一次按键跳两格焦点
                    revealControls()
                } else {
                    moveSelection(if (key == Key.DirectionUp) -1 else 1)
                }
                return true
            }

            Key.DirectionLeft -> {
                seekBy(-SEEK_STEP_MS)
                revealControls()
                return true
            }

            Key.DirectionRight -> {
                seekBy(SEEK_STEP_MS)
                revealControls()
                return true
            }

            Key.DirectionCenter, Key.Enter -> {
                if (ui.controlsVisible) {
                    onAction(ui.selection)
                } else {
                    togglePlayPause()
                    revealControls()
                }
                return true
            }

            Key.MediaPlayPause -> {
                togglePlayPause()
                revealControls()
                return true
            }

            Key.MediaNext -> {
                nextEpisode()
                revealControls()
                return true
            }

            Key.MediaPrevious -> {
                seekBy(-SEEK_STEP_MS)
                revealControls()
                return true
            }
        }
        return false
    }

    private fun moveSelection(delta: Int) {
        val order = PlayerActionOrder
        val current = order.indexOf(ui.selection).coerceAtLeast(0)
        ui.selection = order[(current + delta + order.size) % order.size]
        revealControls()
    }

    private fun revealControls() {
        ui.controlsVisible = true
        ui.tick++
    }

    // ───────────────────────── 播放控制 ─────────────────────────

    private fun onAction(action: PlayerAction) {
        if (action == PlayerAction.BACK || action == PlayerAction.EXIT) {
            exitWithReport()
            return
        }
        when (action) {
            PlayerAction.BACK, PlayerAction.EXIT -> Unit
            PlayerAction.PLAY_PAUSE -> togglePlayPause()
            PlayerAction.REWIND -> seekBy(-SEEK_STEP_MS)
            PlayerAction.FORWARD -> seekBy(SEEK_STEP_MS)
            PlayerAction.NEXT -> nextEpisode()
            PlayerAction.AUDIO -> openTrackOverlay(PlayerOverlay.AUDIO)
            PlayerAction.SUBTITLE -> openTrackOverlay(PlayerOverlay.SUBTITLE)
            PlayerAction.DANMAKU -> toggleDanmaku()
            PlayerAction.SPEED -> cycleSpeed()
        }
        revealControls()
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            reportProgress()
        } else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0L)
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
        }
        ui.isPlaying = exo.isPlaying
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) return
        val duration = exo.duration
        val raw = exo.currentPosition + deltaMs
        val target = if (duration > 0L) raw.coerceIn(0L, duration) else raw.coerceAtLeast(0L)
        exo.seekTo(target)
        ui.positionMs = target
    }

    private fun cycleSpeed() {
        val current = SPEEDS.indexOfFirst { it == ui.speed }
        val next = SPEEDS[(if (current >= 0) current + 1 else 0) % SPEEDS.size]
        ui.speed = next
        player?.setPlaybackSpeed(next)
        toast("倍速 ${formatSpeed(next)}")
    }

    // ───────────────────────── 音轨 / 字幕 ─────────────────────────

    private fun openTrackOverlay(kind: PlayerOverlay) {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val wantAudio = kind == PlayerOverlay.AUDIO
        val trackType = if (wantAudio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT

        val options = ArrayList<TrackOption>()
        if (!wantAudio) {
            val textSelected = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            options += TrackOption(
                label = "关闭字幕",
                detail = "",
                selected = !textSelected,
                group = null,
                trackIndex = -1,
                disableType = true,
            )
        }

        for (group in tracks.groups) {
            if (group.type != trackType || group.length <= 0) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val language = format.language
                val fallbackLabel = if (wantAudio) "音轨 ${options.size + 1}" else "字幕 ${options.size}"
                val label = format.label?.toString()?.takeIf { it.isNotBlank() }
                    ?: languageLabel(language)
                    ?: fallbackLabel

                val detail = buildList {
                    language?.takeIf { it.isNotBlank() }?.let { add(languageLabel(it) ?: it) }
                    format.codecs?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (format.channelCount > 2) add("${format.channelCount} 声道")
                }.distinct().joinToString(" · ")

                options += TrackOption(
                    label = label,
                    detail = detail,
                    selected = group.isTrackSelected(i),
                    group = group.mediaTrackGroup,
                    trackIndex = i,
                    disableType = false,
                )
            }
        }

        if (wantAudio) {
            if (options.isEmpty()) {
                toast("这个视频没有可切换的音轨")
                return
            }
        } else if (options.size <= 1) {
            // 只有「关闭字幕」这一项，说明片源本身没有字幕轨。
            toast("这个视频没有字幕")
            return
        }

        val selectedIndex = options.indexOfFirst { it.selected }
        ui.trackOptions = options
        ui.trackSelected = if (selectedIndex >= 0) selectedIndex else 0
        ui.overlay = kind
        ui.controlsVisible = true
        ui.tick++
    }

    private fun moveTrackSelection(delta: Int) {
        val size = ui.trackOptions.size
        if (size == 0) return
        ui.trackSelected = ((ui.trackSelected + delta) % size + size) % size
    }

    private fun selectTrack(index: Int) {
        val exo = player ?: return
        val option = ui.trackOptions.getOrNull(index) ?: return
        val kind = ui.overlay
        val builder = exo.trackSelectionParameters.buildUpon()

        if (option.disableType) {
            // 关闭字幕：禁用整类轨道，客户端会立刻隐藏字幕。
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val trackType = if (kind == PlayerOverlay.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            val group = option.group
            if (group != null) {
                builder.setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
                builder.setTrackTypeDisabled(trackType, false)
            }
        }
        exo.trackSelectionParameters = builder.build()

        ui.overlay = PlayerOverlay.NONE
        revealControls()
        toast(
            when {
                option.disableType -> "已关闭字幕"
                kind == PlayerOverlay.AUDIO -> "已切换音轨：${option.label}"
                else -> "已切换字幕：${option.label}"
            }
        )
    }

    private fun languageLabel(code: String?): String? {
        if (code.isNullOrBlank()) return null
        return when (code.lowercase()) {
            "zh", "chi", "zho", "chs", "cht" -> "中文"
            "en", "eng" -> "英语"
            "ja", "jpn" -> "日语"
            "ko", "kor" -> "韩语"
            "fr", "fra", "fre" -> "法语"
            "de", "deu", "ger" -> "德语"
            "es", "spa" -> "西班牙语"
            "ru", "rus" -> "俄语"
            else -> null
        }
    }

    // ───────────────────────── 退出 / 系统 UI ─────────────────────────

    private fun exitWithReport() {
        if (exiting) return
        exiting = true
        setResult(RESULT_OK)
        reportProgress(bestEffort = true)
        finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 返回给 MainActivity：会话过期，需要回登录页（普通退出用 RESULT_OK）。 */
        const val RESULT_SESSION_EXPIRED = 1001

        /** 快进/快退步长（毫秒）。 */
        private const val SEEK_STEP_MS = 10_000L

        /** 进度回传周期。 */
        private const val REPORT_INTERVAL_MS = 10_000L

        /** 倍速循环。 */
        private val SPEEDS = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    }
}