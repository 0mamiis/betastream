package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.preference.PreferenceManager
import coil3.dispose
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.databinding.ResultEpisodeBinding
import com.lagradost.cloudstream3.databinding.ResultEpisodeLargeBinding
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.secondsToReadable
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_DOWNLOAD
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_LONG_CLICK
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.txt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ids >= 1000 are reserved for VideoClickActions
 * @see VideoClickActionHolder
 */
const val ACTION_PLAY_EPISODE_IN_PLAYER = 1
const val ACTION_CHROME_CAST_EPISODE = 4
const val ACTION_CHROME_CAST_MIRROR = 5

const val ACTION_DOWNLOAD_EPISODE = 6
const val ACTION_DOWNLOAD_MIRROR = 7

const val ACTION_RELOAD_EPISODE = 8

const val ACTION_SHOW_OPTIONS = 10

const val ACTION_CLICK_DEFAULT = 11
const val ACTION_SHOW_TOAST = 12
const val ACTION_SHOW_DESCRIPTION = 15

const val ACTION_DOWNLOAD_EPISODE_SUBTITLE_MIRROR = 14

const val ACTION_MARK_AS_WATCHED = 18

// 400dp -> +10% ~= 440dp
const val TV_EP_SIZE = 440
const val ACTION_MARK_WATCHED_UP_TO_THIS_EPISODE = 19

data class EpisodeClickEvent(val position: Int?, val action: Int, val data: ResultEpisode) {
    constructor(action: Int, data: ResultEpisode) : this(null, action, data)
}

class EpisodeAdapter(
    private val hasDownloadSupport: Boolean,
    private val clickCallback: (EpisodeClickEvent) -> Unit,
    private val downloadClickCallback: (DownloadClickEvent) -> Unit,
) : NoStateAdapter<ResultEpisode>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.id == b.id
}, contentSame = { a, b ->
    a == b
})) {
    companion object {
        const val HAS_POSTER: Int = 0
        const val HAS_NO_POSTER: Int = 1
        fun getPlayerAction(context: Context): Int {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
            val playerPref =
                settingsManager.getString(context.getString(R.string.player_default_key), "")

            return VideoClickActionHolder.uniqueIdToId(playerPref) ?: ACTION_PLAY_EPISODE_IN_PLAYER
        }

        val sharedPool =
            newSharedPool {
                setMaxRecycledViews(HAS_POSTER or CONTENT, 10)
                setMaxRecycledViews(HAS_NO_POSTER or CONTENT, 10)
            }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        if (holder.itemView.hasFocus()) {
            holder.itemView.clearFocus()
        }

        when (val binding = holder.view) {
            is ResultEpisodeLargeBinding -> {
                clearImage(binding.episodePoster)
            }
        }
        super.onClearView(holder)
    }

    override fun customContentViewType(item: ResultEpisode): Int =
        if (item.poster.isNullOrBlank() && item.description.isNullOrBlank()) HAS_NO_POSTER else HAS_POSTER

    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return when (viewType) {
            HAS_NO_POSTER -> {
                ViewHolderState(
                    ResultEpisodeBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                )
            }

            HAS_POSTER -> {
                ViewHolderState(
                    ResultEpisodeLargeBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                )
            }

            else -> throw NotImplementedError()
        }
    }

    private fun getRuntimeText(item: ResultEpisode): String {
        // Prefer explicit episode runtime (MainAPI.Episode.runTime) - documented as seconds.
        val runtimeMinutesFromRunTime = item.runTime?.takeIf { it > 0 }?.let { runtimeSeconds ->
            // runTime is seconds
            (runtimeSeconds + 59) / 60
        }

        // Fallback to playback duration (stored from player), in ms.
        val runtimeMinutesFromDurationMs = item.duration.takeIf { it > 0L }?.let { durationMs ->
            ((durationMs + 59_999L) / 60_000L).toInt()
        }

        // Final fallback as requested.
        val runtimeMinutes = runtimeMinutesFromRunTime
            ?: runtimeMinutesFromDurationMs
            ?: 0

        val hours = runtimeMinutes / 60
        val mins = runtimeMinutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m" // includes 0m
        }
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ResultEpisode, position: Int) {
        val itemView = holder.itemView
        when (val binding = holder.view) {
            is ResultEpisodeLargeBinding -> {
                val setWidth =
                    if (isLayout(TV or EMULATOR)) TV_EP_SIZE.toPx else ViewGroup.LayoutParams.MATCH_PARENT

                binding.apply {
                    episodeLinHolder.layoutParams.width = setWidth
                    episodeHolderLarge.layoutParams.width = setWidth
                    episodeHolder.layoutParams.width = setWidth

                    if (isLayout(PHONE or EMULATOR) && CommonActivity.appliedTheme == R.style.AmoledMode) {
                        episodeHolderLarge.radius = 0.0f
                        episodeHolder.setPadding(0)
                    }

                    downloadButton.isVisible = hasDownloadSupport
                    downloadButton.setDefaultClickListener(
                        DownloadObjects.DownloadEpisodeCached(
                            name = item.name,
                            poster = item.poster,
                            episode = item.episode,
                            season = item.season,
                            id = item.id,
                            parentId = item.parentId,
                            score = item.score,
                            description = item.description,
                            cacheTime = System.currentTimeMillis(),
                        ), null
                    ) {
                        when (it.action) {
                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                clickCallback.invoke(
                                    EpisodeClickEvent(
                                        position,
                                        ACTION_DOWNLOAD_EPISODE,
                                        item
                                    )
                                )
                            }

                            DOWNLOAD_ACTION_LONG_CLICK -> {
                                clickCallback.invoke(
                                    EpisodeClickEvent(
                                        position,
                                        ACTION_DOWNLOAD_MIRROR,
                                        item
                                    )
                                )
                            }

                            else -> {
                                downloadClickCallback.invoke(it)
                            }
                        }
                    }

                    val status = VideoDownloadManager.downloadStatus[item.id]
                    downloadButton.resetView()
                    downloadButton.setPersistentId(item.id)
                    downloadButton.setStatus(status)

                    val name =
                        if (item.name == null) "${episodeText.context.getString(R.string.episode)} ${item.episode}" else "${item.episode}. ${item.name}"
                    val displayName = if (name.length > 15) "${name.take(13)}.." else name
                    episodeFiller.isVisible = item.isFiller == true
                    episodeText.text = displayName
                    episodeText.isSelected = true

                    val badgeText = when {
                        item.season != null && item.episode != null -> "S${item.season}B${item.episode}"
                        item.episode != null -> "B${item.episode}"
                        else -> null
                    }
                    episodeSeasonBadge.text = badgeText.orEmpty()
                    episodeSeasonBadge.isVisible = !badgeText.isNullOrBlank()

                    if (item.videoWatchState == VideoWatchState.Watched) {
                        episodePlayIcon.setImageResource(R.drawable.ic_baseline_check_24)
                        episodeProgress.isVisible = false
                    } else {
                        val displayPos = item.getDisplayPosition()

                        if (displayPos >= item.duration && displayPos > 0) {
                            episodePlayIcon.setImageResource(R.drawable.ic_baseline_check_24)
                            episodeProgress.isVisible = false
                        } else {
                            episodePlayIcon.setImageResource(R.drawable.netflix_play)
                            episodeProgress.apply {
                                max = (item.duration / 1000).toInt()
                                progress = (displayPos / 1000).toInt()
                                isVisible = displayPos > 0L
                            }
                        }
                    }

                    val posterVisible = !item.poster.isNullOrBlank()
                    if (posterVisible) {
                        val isUpcoming = item.airDate != null && unixTimeMS < item.airDate
                        episodePoster.loadImage(item.poster) {
                            if (isUpcoming) {
                                error {
                                    main { episodeUpcomingIcon.isVisible = true }
                                    null
                                }
                            }
                        }
                    } else {
                        episodePoster.dispose()
                    }
                    episodePoster.isVisible = posterVisible

                    val rating10p = item.score?.toFloat(10)
                    if (rating10p != null && rating10p > 0.1) {
                        episodeRating.text = episodeRating.context?.getString(R.string.rated_format)
                            ?.format(rating10p)
                    } else {
                        episodeRating.text = ""
                    }
                    episodeRating.isGone = episodeRating.text.isNullOrBlank()

                    // Runtime: always show (fallback 0m)
                    val runtimeText = getRuntimeText(item)
                    episodeRuntime.setText(txt(runtimeText))
                    episodeRuntime.isVisible = true
                    episodeTimeIcon.isVisible = true

                    val descriptionText = item.description?.let { description ->
                        if (description.length > 60) {
                            description.take(57) + "..."
                        } else {
                            description
                        }
                    }

                    episodeDescript.apply {
                        text = descriptionText.html()
                        isGone = text.isNullOrBlank()

                        var isExpanded = false
                        setOnClickListener {
                            if (isLayout(TV)) {
                                clickCallback.invoke(
                                    EpisodeClickEvent(
                                        position,
                                        ACTION_SHOW_DESCRIPTION,
                                        item
                                    )
                                )
                            } else {
                                isExpanded = !isExpanded
                                maxLines = if (isExpanded) Integer.MAX_VALUE else 4
                            }
                        }
                    }

                    // Date: show if available
                    if (item.airDate != null) {
                        episodeDate.isVisible = true
                        val isUpcoming = unixTimeMS < item.airDate

                        if (isUpcoming) {
                            episodeProgress.isVisible = false
                            episodePlayIcon.isVisible = false
                            episodeUpcomingIcon.isVisible = !posterVisible
                            episodeDate.setText(
                                txt(
                                    R.string.episode_upcoming_format,
                                    secondsToReadable(
                                        item.airDate.minus(unixTimeMS).div(1000).toInt(),
                                        ""
                                    )
                                )
                            )
                        } else {
                            episodePlayIcon.isVisible = true
                            episodeUpcomingIcon.isVisible = false

                            val formattedAirDate = SimpleDateFormat(
                                "dd MMM yyyy",
                                Locale.getDefault()
                            ).format(Date(item.airDate))

                            episodeDate.setText(txt(formattedAirDate))
                        }
                    } else {
                        episodeUpcomingIcon.isVisible = false
                        episodePlayIcon.isVisible = true
                        episodeDate.setText(txt(""))
                        episodeDate.isVisible = false
                    }

                    episodeTimeRow.isGone = false

                    if (isLayout(EMULATOR or PHONE)) {
                        episodePoster.setOnClickListener {
                            clickCallback.invoke(
                                EpisodeClickEvent(
                                    position,
                                    ACTION_CLICK_DEFAULT,
                                    item
                                )
                            )
                        }

                        episodePoster.setOnLongClickListener {
                            clickCallback.invoke(
                                EpisodeClickEvent(
                                    position,
                                    ACTION_SHOW_TOAST,
                                    item
                                )
                            )
                            return@setOnLongClickListener true
                        }
                    }
                }

                itemView.setOnClickListener {
                    clickCallback.invoke(EpisodeClickEvent(position, ACTION_CLICK_DEFAULT, item))
                }

                if (isLayout(TV)) {
                    itemView.isFocusable = true
                    itemView.isFocusableInTouchMode = true
                }

                itemView.setOnLongClickListener {
                    clickCallback.invoke(EpisodeClickEvent(position, ACTION_SHOW_OPTIONS, item))
                    return@setOnLongClickListener true
                }
            }

            is ResultEpisodeBinding -> {
                binding.episodeHolder.layoutParams.apply {
                    width =
                        if (isLayout(TV or EMULATOR)) TV_EP_SIZE.toPx else ViewGroup.LayoutParams.MATCH_PARENT
                }

                binding.apply {
                    downloadButton.isVisible = hasDownloadSupport
                    downloadButton.setDefaultClickListener(
                        DownloadObjects.DownloadEpisodeCached(
                            name = item.name,
                            poster = item.poster,
                            episode = item.episode,
                            season = item.season,
                            id = item.id,
                            parentId = item.parentId,
                            score = item.score,
                            description = item.description,
                            cacheTime = System.currentTimeMillis(),
                        ), null
                    ) {
                        when (it.action) {
                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                clickCallback.invoke(
                                    EpisodeClickEvent(
                                        position,
                                        ACTION_DOWNLOAD_EPISODE,
                                        item
                                    )
                                )
                            }

                            DOWNLOAD_ACTION_LONG_CLICK -> {
                                clickCallback.invoke(
                                    EpisodeClickEvent(
                                        position,
                                        ACTION_DOWNLOAD_MIRROR,
                                        item
                                    )
                                )
                            }

                            else -> {
                                downloadClickCallback.invoke(it)
                            }
                        }
                    }

                    val status = VideoDownloadManager.downloadStatus[item.id]
                    downloadButton.resetView()
                    downloadButton.setPersistentId(item.id)
                    downloadButton.setStatus(status)

                    val name =
                        if (item.name == null) "${episodeText.context.getString(R.string.episode)} ${item.episode}" else "${item.episode}. ${item.name}"
                    val displayName = if (name.length > 15) "${name.take(13)}.." else name
                    episodeFiller.isVisible = item.isFiller == true
                    episodeText.text = displayName
                    episodeText.isSelected = true

                    if (item.videoWatchState == VideoWatchState.Watched) {
                        episodePlayIcon.setImageResource(R.drawable.ic_baseline_check_24)
                        episodeProgress.isVisible = false
                    } else {
                        val displayPos = item.getDisplayPosition()

                        if (displayPos >= item.duration && displayPos > 0) {
                            episodePlayIcon.setImageResource(R.drawable.ic_baseline_check_24)
                            episodeProgress.isVisible = false
                        } else {
                            episodePlayIcon.setImageResource(R.drawable.play_button_transparent)
                            episodeProgress.apply {
                                max = (item.duration / 1000).toInt()
                                progress = (displayPos / 1000).toInt()
                                isVisible = displayPos > 0L
                            }
                        }
                    }

                    // Runtime: always show (fallback 0m)
                    val runtimeText = getRuntimeText(item)
                    episodeRuntime.setText(txt(runtimeText))
                    episodeRuntime.isVisible = true
                    episodeTimeIcon.isVisible = true

                    // Date: show if available
                    if (item.airDate != null) {
                        episodeDate.isVisible = true
                        val isUpcoming = unixTimeMS < item.airDate
                        if (isUpcoming) {
                            episodeDate.setText(
                                txt(
                                    R.string.episode_upcoming_format,
                                    secondsToReadable(
                                        item.airDate.minus(unixTimeMS).div(1000).toInt(),
                                        ""
                                    )
                                )
                            )
                        } else {
                            val formattedAirDate = SimpleDateFormat(
                                "dd MMM yyyy",
                                Locale.getDefault()
                            ).format(Date(item.airDate))
                            episodeDate.setText(txt(formattedAirDate))
                        }
                    } else {
                        episodeDate.setText(txt(""))
                        episodeDate.isVisible = false
                    }

                    episodeTimeRow.isGone = false

                    itemView.setOnClickListener {
                        clickCallback.invoke(
                            EpisodeClickEvent(
                                position,
                                ACTION_CLICK_DEFAULT,
                                item
                            )
                        )
                    }

                    if (isLayout(TV)) {
                        itemView.isFocusable = true
                        itemView.isFocusableInTouchMode = true
                    }

                    itemView.setOnLongClickListener {
                        clickCallback.invoke(EpisodeClickEvent(position, ACTION_SHOW_OPTIONS, item))
                        return@setOnLongClickListener true
                    }
                }
            }
        }
    }
}
