package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.github.libretube.databinding.AppIconHeaderBinding

class AppIconHeader(context: Context, attributeSet: AttributeSet? = null) :
    LinearLayout(context, attributeSet) {
        val binding = AppIconHeaderBinding.inflate(LayoutInflater.from(context), this, true)
}
