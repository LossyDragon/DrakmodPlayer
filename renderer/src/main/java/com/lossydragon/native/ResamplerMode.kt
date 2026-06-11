package com.lossydragon.native

enum class ResamplerMode(val id: Int) {
    NEAREST(Player.XMP_INTERP_NEAREST),
    LINEAR(Player.XMP_INTERP_LINEAR),
    CUBIC(Player.XMP_INTERP_SPLINE),
    OPENMPT_AMIGA_A500(100),
    OPENMPT_AMIGA_A1200(101);

    companion object {
        fun fromId(id: Int): ResamplerMode =
            entries.firstOrNull { it.id == id } ?: LINEAR
    }
}
