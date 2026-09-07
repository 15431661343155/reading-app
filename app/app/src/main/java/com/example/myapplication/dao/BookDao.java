package com.example.myapplication.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.example.myapplication.bean.ShelfBook;

import java.util.List;

@Dao
public interface BookDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(ShelfBook book);

    @Update
    void update(ShelfBook book);

    @Delete
    void delete(ShelfBook book);

    @Query("DELETE FROM shelf_books WHERE bookName = :bookName")
    void deleteByBookName(String bookName);

    @Query("SELECT * FROM shelf_books ORDER BY lastReadTime DESC")
    LiveData<List<ShelfBook>> getAllBooks();

    @Query("SELECT * FROM shelf_books ORDER BY lastReadTime DESC")
    List<ShelfBook> getAllBooksSync();

    @Query("SELECT * FROM shelf_books WHERE bookName = :bookName LIMIT 1")
    ShelfBook getBookByName(String bookName);

    @Query("SELECT EXISTS(SELECT 1 FROM shelf_books WHERE bookName = :bookName)")
    boolean isBookInShelf(String bookName);

    @Query("UPDATE shelf_books SET lastReadTime = :time WHERE bookName = :bookName")
    void updateLastReadTime(String bookName, long time);
}