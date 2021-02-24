package it.unige.hidedroid.animators

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import me.zhanghai.android.materialprogressbar.MaterialProgressBar

object Animators {

    fun makeDeterminateCircularPrimaryProgressAnimator(
            progressBars: MutableList<MaterialProgressBar>): ValueAnimator {
        val animator = ValueAnimator.ofInt(0, 150)
        animator.duration = 6000
        animator.interpolator = LinearInterpolator()
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { animator ->
            val value = animator.animatedValue as Int
            for (progressBar in progressBars) {
                progressBar.progress = value
            }
        }
        return animator
    }

    fun makeDeterminateCircularPrimaryAndSecondaryProgressAnimator(
            progressBars: MutableList<MaterialProgressBar>): ValueAnimator {
        val animator = makeDeterminateCircularPrimaryProgressAnimator(progressBars)
        animator.addUpdateListener { animator ->
            val value = Math.round(1.25f * animator.animatedValue as Int)
            for (progressBar in progressBars) {
                progressBar.secondaryProgress = value
            }
        }
        return animator
    }
}