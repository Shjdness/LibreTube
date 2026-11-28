package com.github.libretube.helpers

/** Concrete provider avoids device-specific framework instantiation issues. */
class AudioShareFileProvider : androidx.core.content.FileProvider()
