package com.lossydragon.modplayer.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lossydragon.modplayer.model.ResultItem
import com.lossydragon.modplayer.model.TypeResult

class ResultPagingSource<T : TypeResult>(
    private val fetch: suspend (page: Int) -> Result<T>,
    private val extract: (T) -> List<ResultItem>
) : PagingSource<Int, ResultItem>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ResultItem> {
        val page = params.key ?: 1
        return fetch(page).fold(
            onSuccess = { result ->
                LoadResult.Page(
                    data = extract(result),
                    prevKey = if (page > 1) page - 1 else null,
                    nextKey = if (page < result.totalpages) page + 1 else null
                )
            },
            onFailure = { LoadResult.Error(it) }
        )
    }

    override fun getRefreshKey(state: PagingState<Int, ResultItem>): Int? = null
}
