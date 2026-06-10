package com.lossydragon.modplayer.player

enum class RenderingBackend(val id: Int) {
    OPENMPT(0),
    LIBXMP(1);

    companion object {
        fun fromId(id: Int): RenderingBackend =
            entries.firstOrNull { it.id == id } ?: OPENMPT
    }
}
