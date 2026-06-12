package com.aibyjohannes.alfred.ui.home

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Manages distinct orb animations for each VoiceModeState, so HomeFragment stays clean.
 */
class VoiceOrAnimator(private val orbView: View) {

    private var currentAnimator: AnimatorSet? = null

    fun playListening() {
        cancel()
        // Pulse red: scale up/down quickly, high alpha
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.35f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.35f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0.85f)

        val pulse = ObjectAnimator.ofPropertyValuesHolder(orbView, scaleX, scaleY, alpha).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        currentAnimator = AnimatorSet().apply {
            play(pulse)
            start()
        }
    }

    fun playThinking() {
        cancel()
        // Gentle shimmer: slow scale, lower alpha
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.25f, 0.55f)

        val shimmer = ObjectAnimator.ofPropertyValuesHolder(orbView, scaleX, scaleY, alpha).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        currentAnimator = AnimatorSet().apply {
            play(shimmer)
            start()
        }
    }

    fun playSpeaking() {
        cancel()
        // Wave-like: faster rotation-free oscillation + alpha
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.05f, 1.3f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.05f, 1.3f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0.75f)

        val wave = ObjectAnimator.ofPropertyValuesHolder(orbView, scaleX, scaleY, alpha).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = LinearInterpolator()
        }

        currentAnimator = AnimatorSet().apply {
            play(wave)
            start()
        }
    }

    fun cancel() {
        currentAnimator?.cancel()
        currentAnimator = null
    }
}
