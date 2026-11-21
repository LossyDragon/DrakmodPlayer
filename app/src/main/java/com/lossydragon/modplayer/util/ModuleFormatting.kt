package com.lossydragon.modplayer.util

import com.lossydragon.modplayer.model.Module

/** Display helpers for the ModArchive [Module] DTO. */

val Module.sizeKb: Int
    get() = bytes / 1024

val Module.formattedComment: String
    get() = comment.lineSequence().joinToString("\n") { it.fromHtml() }

val Module.formattedInstruments: String
    get() = instruments.lineSequence().joinToString("\n") { it.fromHtml() }
