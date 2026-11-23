package com.lossydragon.modplayer.util

import com.lossydragon.modplayer.model.Module

/** Display helpers for the ModArchive [Module] DTO. */

val Module.sizeKb: Int
    get() = bytes / 1024

/** Parses HTML per line; remember the result when reading it in composition. */
val Module.formattedComment: String
    get() = comment.htmlLines()

val Module.formattedInstruments: String
    get() = instruments.htmlLines()

private fun String.htmlLines(): String = lineSequence().joinToString("\n") { it.fromHtml() }
