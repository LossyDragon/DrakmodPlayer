package com.lossydragon.modplayer.player

import org.helllabs.libxmp.Xmp

enum class ResamplerMode(val id: Int) {
    NEAREST(Xmp.XMP_INTERP_NEAREST),
    LINEAR(Xmp.XMP_INTERP_LINEAR),
    CUBIC(Xmp.XMP_INTERP_SPLINE),
    OPENMPT_AMIGA_A500(100),
    OPENMPT_AMIGA_A1200(101);

    companion object {
        fun fromId(id: Int): ResamplerMode =
            entries.firstOrNull { it.id == id } ?: LINEAR
    }
}
