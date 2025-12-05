package com.example.audiospatializer.data

import java.io.File

/**
 * 変換済みトラックのリポジトリ
 * 
 * データベース操作とファイルシステム操作を連携させる。
 * トラック情報の保存・更新・削除に加え、実ファイルの削除やリネームも行う。
 * 
 * 責務:
 * - トラック情報のCRUD操作
 * - ファイル削除時のデータベース同期
 * - ファイルリネーム時のパス更新
 * 
 * @param dao データアクセスオブジェクト
 */
class ConvertedRepository(private val dao: ConvertedTrackDao) {
    
    /**
     * 全トラックのFlowを取得
     * 
     * データベースの変更を自動的に検知し、UIに反映可能。
     * 作成日時の降順でソート。
     */
    val tracks = dao.observeAll()

    /**
     * 新規トラックを保存
     * 
     * @param track 保存するトラック情報
     */
    suspend fun save(track: ConvertedTrack) {
        dao.insert(track)
    }

    /**
     * トラック情報を更新
     * 
     * @param track 更新するトラック情報（idで特定）
     */
    suspend fun update(track: ConvertedTrack) {
        dao.update(track)
    }

    /**
     * トラック情報を削除（ファイルは削除しない）
     * 
     * @param track 削除するトラック情報
     */
    suspend fun delete(track: ConvertedTrack) {
        dao.delete(track)
    }

    /**
     * ファイルを削除し、DBからも削除
     * 
     * ファイルシステムとデータベースを連携して削除する。
     * ファイルが存在しない場合もDB削除は実行。
     * 
     * @param track 削除するトラック
     * @return ファイル削除が成功した場合true
     */
    suspend fun deleteWithFile(track: ConvertedTrack): Boolean {
        val file = File(track.filePath)
        // ファイルが存在しないか、削除に成功した場合
        val fileDeleted = !file.exists() || file.delete()
        if (fileDeleted) {
            dao.delete(track)
        }
        return fileDeleted
    }

    /**
     * ファイルをリネームし、DBも更新
     * 
     * ファイル名を変更し、データベースのdisplayNameとfilePathを更新する。
     * 拡張子は元のファイルから維持。
     * ファイル名に使用できない文字は'_'に置換。
     * 
     * @param track リネーム対象のトラック
     * @param newDisplayName 新しい表示名
     * @return 成功した場合は更新後のトラック、失敗した場合はnull
     */
    suspend fun renameFile(track: ConvertedTrack, newDisplayName: String): ConvertedTrack? {
        val oldFile = File(track.filePath)
        if (!oldFile.exists()) return null
        
        // 新しいファイル名を生成（拡張子を維持）
        val extension = oldFile.extension
        // ファイル名に使えない文字を置換
        val sanitizedName = newDisplayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val newFileName = if (sanitizedName.endsWith(".$extension")) {
            sanitizedName
        } else {
            "$sanitizedName.$extension"
        }
        
        val newFile = File(oldFile.parent, newFileName)
        
        // 同名ファイルが既に存在する場合は失敗（自分自身は除く）
        if (newFile.exists() && newFile != oldFile) return null
        
        return if (oldFile.renameTo(newFile)) {
            // リネーム成功、DBを更新
            val updatedTrack = track.copy(
                displayName = sanitizedName,
                filePath = newFile.absolutePath,
                fileSizeBytes = newFile.length()
            )
            dao.update(updatedTrack)
            updatedTrack
        } else {
            null
        }
    }
}
