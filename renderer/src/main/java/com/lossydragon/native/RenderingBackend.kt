package com.lossydragon.native

enum class RenderingBackend(val id: Int) {
    INVALID(-1),
    OPENMPT(0),
    LIBXMP(1),
    ;

    companion object {
        fun fromId(id: Int): RenderingBackend =
            entries.firstOrNull { it.id == id } ?: OPENMPT
    }
}
