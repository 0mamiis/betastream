package com.lagradost.cloudstream3.ui.result

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.PlayerResize
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import kotlin.math.max

open class ResultTrailerPlayer : ResultFragmentPhone() {

    override var lockRotation = false
    override var isFullScreenPlayer = false
    override var hasPipModeSupport = false

    companion object {
        const val TAG = "RESULT_TRAILER"
        private const val EMBEDDED_TRAILER_CROP_X_FOCUS = 0.5f
        private const val EMBEDDED_TRAILER_CROP_Y_FOCUS = 0.75f
        private const val EMBEDDED_TRAILER_EXTRA_ZOOM = 1.2f
    }

    private var defaultTopHolderHeight: Int? = null

    override fun nextEpisode() {}

    override fun prevEpisode() {}

    override fun playerPositionChanged(position: Long, duration : Long) {}

    override fun nextMirror() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        uiReset()
        fixPlayerSize()
    }

    private fun fixPlayerSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding?.apply {
                if (isFullScreenPlayer) {
                    // Remove listener
                    ViewCompat.setOnApplyWindowInsetsListener(root, null)
                    root.overlay.clear() // Clear the cutout overlay
                    root.setPadding(0, 0, 0, 0) // Reset padding for full screen
                } else {
                    // Reapply padding when not in full screen
                    fixSystemBarsPadding(root)
                    ViewCompat.requestApplyInsets(root)
                }
            }
        }

        val topHolder = resultBinding?.resultTopHolder
        val embeddedHeight = defaultTopHolderHeight
            ?: topHolder?.layoutParams?.height?.takeIf { it > 0 }
            ?: topHolder?.height?.takeIf { it > 0 }

        if (defaultTopHolderHeight == null && embeddedHeight != null) {
            defaultTopHolderHeight = embeddedHeight
        }

        resultBinding?.resultSmallscreenHolder?.isVisible = !isFullScreenPlayer
        binding?.resultFullscreenHolder?.isVisible = isFullScreenPlayer

        resultBinding?.fragmentTrailer?.playerBackground?.apply {
            isVisible = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (!isFullScreenPlayer && embeddedHeight != null) {
            topHolder?.layoutParams?.apply {
                height = embeddedHeight
            }
        }

        playerBinding?.playerIntroPlay?.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (isFullScreenPlayer) FrameLayout.LayoutParams.MATCH_PARENT
            else (embeddedHeight ?: FrameLayout.LayoutParams.MATCH_PARENT)
        )

        applyResizeMode(if (isFullScreenPlayer) {
            PlayerResize.entries.getOrElse(resizeMode) { PlayerResize.Fit }
        } else {
            PlayerResize.Fit
        })
        applyEmbeddedTrailerCrop()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun resetEmbeddedTrailerCrop() {
        playerView?.videoSurfaceView?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
            translationX = 0.0f
            translationY = 0.0f
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyEmbeddedTrailerCrop() {
        if (isFullScreenPlayer) {
            resetEmbeddedTrailerCrop()
            return
        }

        playerView?.post {
            val trailerPlayerView = playerView ?: return@post
            val videoSurface = trailerPlayerView.videoSurfaceView ?: return@post

            val playerWidth = trailerPlayerView.width.toFloat()
            val playerHeight = trailerPlayerView.height.toFloat()
            val videoWidth = videoSurface.width.toFloat()
            val videoHeight = videoSurface.height.toFloat()

            if (playerWidth <= 1.0f || playerHeight <= 1.0f || videoWidth <= 1.0f || videoHeight <= 1.0f) {
                return@post
            }

            val initAspect = (playerHeight * videoWidth) / (playerWidth * videoHeight)
            val zoomScale = max(initAspect, 1.0f / initAspect) * EMBEDDED_TRAILER_EXTRA_ZOOM
            val overflowX = max(0.0f, videoWidth * zoomScale - playerWidth)
            val overflowY = max(0.0f, videoHeight * zoomScale - playerHeight)

            videoSurface.scaleX = zoomScale
            videoSurface.scaleY = zoomScale
            videoSurface.translationX = (0.5f - EMBEDDED_TRAILER_CROP_X_FOCUS) * overflowX
            videoSurface.translationY = (0.5f - EMBEDDED_TRAILER_CROP_Y_FOCUS) * overflowY
        }
    }

    override fun playerDimensionsLoaded(width: Int, height : Int) {
        fixPlayerSize()
    }

    private fun applyResizeMode(resize: PlayerResize) {
        val resizeModeValue = when (resize) {
            PlayerResize.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            PlayerResize.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlayerResize.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        playerView?.resizeMode = resizeModeValue
    }

    override fun resize(resize: PlayerResize, showToast: Boolean) {
        if (isFullScreenPlayer) {
            super.resize(resize, showToast)
        } else {
            applyResizeMode(PlayerResize.Fit)
            applyEmbeddedTrailerCrop()
        }
    }

    override fun showMirrorsDialogue() {}
    override fun showTracksDialogue() {}

    override fun openOnlineSubPicker(
        context: Context,
        loadResponse: LoadResponse?,
        dismissCallback: () -> Unit
    ) {
    }

    override fun subtitlesChanged() {}

    override fun embeddedSubtitlesFetched(subtitles: List<SubtitleData>) {}
    override fun onTracksInfoChanged() {}

    override fun exitedPipMode() {}
    private fun updateFullscreen(fullscreen: Boolean) {
        isFullScreenPlayer = fullscreen
        lockRotation = fullscreen

        playerBinding?.playerFullscreen?.setImageResource(if (fullscreen) R.drawable.baseline_fullscreen_exit_24 else R.drawable.baseline_fullscreen_24)
        if (fullscreen) {
            enterFullscreen()
            binding?.apply {
                resultTopBar.isVisible = false
                resultFullscreenHolder.isVisible = true
                resultMainHolder.isVisible = false
            }

            resultBinding?.fragmentTrailer?.playerBackground?.let { view ->
                (view.parent as ViewGroup?)?.removeView(view)
                binding?.resultFullscreenHolder?.addView(view)
            }

        } else {
            binding?.apply {
                resultTopBar.isVisible = true
                resultFullscreenHolder.isVisible = false
                resultMainHolder.isVisible = true
                resultBinding?.fragmentTrailer?.playerBackground?.let { view ->
                    (view.parent as ViewGroup?)?.removeView(view)
                    resultBinding?.resultSmallscreenHolder?.addView(view)
                }
            }
            exitFullscreen()
        }
        fixPlayerSize()
        uiReset()

        if (isFullScreenPlayer) {
            activity?.attachBackPressedCallback("ResultTrailerPlayer") {
                updateFullscreen(false)
            }
        } else activity?.detachBackPressedCallback("ResultTrailerPlayer")
    }

    override fun updateUIVisibility() {
        super.updateUIVisibility()
        playerBinding?.playerGoBackHolder?.isVisible = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        defaultTopHolderHeight = resultBinding?.resultTopHolder?.layoutParams?.height?.takeIf { it > 0 }
        playerBinding?.playerFullscreen?.setOnClickListener {
            updateFullscreen(!isFullScreenPlayer)
        }
        updateFullscreen(isFullScreenPlayer)
        uiReset()

        playerBinding?.playerIntroPlay?.setOnClickListener {
            playerBinding?.playerIntroPlay?.isGone = true
            player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.UI)
            updateUIVisibility()
            fixPlayerSize()
        }
    }
}
