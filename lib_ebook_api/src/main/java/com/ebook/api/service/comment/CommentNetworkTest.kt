package com.ebook.api.service.comment

import com.xrn1997.common.dto.RespDTO
import com.ebook.api.entity.Comment
import com.ebook.api.utils.TestAssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentNetworkTest @Inject constructor(
    private val networkJson: Json,
    private val assets: TestAssetManager,
) : CommentDataSource {
    override suspend fun addComment(
        token: String?,
        comment: Comment
    ): RespDTO<Comment> = getDataFromJsonFile(COMMENT_ADD)

    override suspend fun deleteComment(
        token: String?,
        id: Long
    ): RespDTO<Int> = getDataFromJsonFile(COMMENT_DELETE)

    override suspend fun getUserComments(
        token: String?,
        username: String
    ): RespDTO<List<Comment>> = getDataFromJsonFile(USER_COMMENTS)

    override suspend fun getChapterComments(
        token: String?,
        chapterUrl: String?
    ): RespDTO<List<Comment>> = getDataFromJsonFile(CHAPTER_COMMENTS)

    /**
     * Get data from the given JSON [fileName].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getDataFromJsonFile(fileName: String): RespDTO<T> =
        withContext(Dispatchers.IO) {
            assets.open(fileName).use { inputStream ->
                networkJson.decodeFromStream(inputStream)
            }
        }

    companion object {
        private const val COMMENT_ADD = "comment_add.json"
        private const val COMMENT_DELETE = "comment_delete.json"
        private const val USER_COMMENTS = "user_comments.json"
        private const val CHAPTER_COMMENTS = "chapter_comments.json"
    }
}