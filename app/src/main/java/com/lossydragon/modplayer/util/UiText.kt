package com.lossydragon.modplayer.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

/** Text a ViewModel can emit without a Context; the UI resolves it. */
@Immutable
sealed interface UiText {
    data class Raw(val text: String) : UiText

    class Res(@param:StringRes val resId: Int, vararg val args: Any) : UiText {
        override fun equals(other: Any?): Boolean =
            other is Res && other.resId == resId && other.args.contentEquals(args)

        override fun hashCode(): Int = 31 * resId + args.contentHashCode()
    }
}

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> context.getString(resId, *args)
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> stringResource(resId, *args)
}

fun Throwable.toUiText(): UiText = UiText.Raw(message ?: javaClass.simpleName)
