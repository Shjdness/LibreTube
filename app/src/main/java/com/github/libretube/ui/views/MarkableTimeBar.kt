package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.media3.common.util.UnstableApi

/**
 * TimeBar that can be extended to mark additional information on the progress bar.
 */
@UnstableApi
open class MarkableTimeBar(
    context: Context,
    attributeSet: AttributeSet? = null
) : DismissableTimeBar(context, attributeSet)
