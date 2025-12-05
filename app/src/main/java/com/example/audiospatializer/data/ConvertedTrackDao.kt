package com.example.audiospatializer.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 変換済みトラックのData Access Object (DAO)
 * 
 * Roomデータベースへのトラック情報のCRUD操作を定義。
 * FlowによるリアクティブなデータObserveをサポート。
 * 
 * @see ConvertedTrack エンティティクラス
 */
@Dao
interface ConvertedTrackDao {
    
    /**
     * 全トラックをFlowとして取得
     * 
     * データベースの変更を自動的に検知し、新しいリストを発行する。
     * 作成日時の降順でソート（新しいものが上）。
     * 
     * @return 全トラックのFlow
     */
    @Query("SELECT * FROM converted_tracks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConvertedTrack>>

    /**
     * 新規トラックを挿入
     * 
     * 同じIDのトラックが存在する場合は置き換え。
     * 
     * @param track 挿入するトラック
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: ConvertedTrack)

    /**
     * トラック情報を更新
     * 
     * @param track 更新するトラック（idで特定）
     */
    @Update
    suspend fun update(track: ConvertedTrack)

    /**
     * トラックを削除
     * 
     * @param track 削除するトラック
     */
    @Delete
    suspend fun delete(track: ConvertedTrack)

    /**
     * IDを指定してトラックを削除
     * 
     * @param id 削除するトラックのID
     */
    @Query("DELETE FROM converted_tracks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
