package com.lossydragon.native.model

import androidx.compose.runtime.Immutable

/**
 * @see [org.helllabs.libxmp.Xmp.getFrameInfo]
 */
@Immutable
data class FrameInfo(
    val pos: Int = 0,   /* Current position */
    val pattern: Int = 0,   /* Current pattern */
    val row: Int = 0,   /* Current row in pattern */
    val numRows: Int = 0,   /* Number of rows in current pattern */
    val frame: Int = 0, /* Current frame */
    val speed: Int = 0, /* Current replay speed */
    val bpm: Int = 0,   /* Current bpm */
    val time: Int = 0,  /* Current module time in ms */
    val totalTime: Int = 0, /* Estimated replay time in ms*/
    val frameTime: Int = 0, /* Frame replay time in us */
    val bufferSize: Int = 0,    /* Used buffer size */
    val totalSize: Int = 0, /* Total buffer size */
    val volume: Int = 0,    /* Current master volume */
    val loopCount: Int = 0, /* Loop counter */
    val virtChannels: Int = 0,  /* Number of virtual channels */
    val virtUsed: Int = 0,  /* Used virtual channels */
    val sequence: Int = 0,  /* Current sequence */
)
