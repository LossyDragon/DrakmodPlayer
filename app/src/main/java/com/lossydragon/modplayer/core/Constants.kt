package com.lossydragon.modplayer.core

object Constants {
    // On-disk identifier: renaming it orphans every user's playlists unless the file is migrated.
    const val ROOM_DATABASE_NAME: String = "xmp_database"

    /** Subfolder of Downloads/ for crash reports and exported logs. */
    const val DOWNLOADS_FOLDER: String = "Drakmod"
    const val CRASH_FILE_PREFIX: String = "drakmod_crash_"
    const val HTTP_BASE_URL: String = "https://api.modarchive.org"
    const val TMA_BASE_URL: String = "https://modarchive.org/"

    /** Folder name used for downloads whose artist The Mod Archive does not know. */
    const val UNKNOWN_ARTIST: String = "unknown"
    val UNSUPPORTED_EXTENSIONS = setOf("ahx", "hvl", "mo3")
    val SKIP_EXTENSIONS = setOf(
        "txt", "pdf", "doc", "docx", "rtf", "nfo", "diz", "me",
        "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "zip", "rar", "7z", "gz", "tar",
        "mp3", "flac", "ogg", "wav", "mp4", "avi",
        "xml", "json", "html", "htm", "css", "js",
    )
}
