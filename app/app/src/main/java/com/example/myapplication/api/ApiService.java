package com.example.myapplication.api;

import com.example.myapplication.bean.Chapter;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.*;
import java.util.List;

import retrofit2.Call;
import retrofit2.http.*;

public interface ApiService {

    // ========== 用户认证 ==========
    @POST("/api/auth/register")
    Call<ApiResponse<Void>> register(@Body RegisterRequest request);

    @POST("/api/auth/login")
    Call<ApiResponse<LoginResponse>> login(@Body LoginRequest request);

    // ========== 书籍 ==========
    @GET("/api/books")
    Call<ApiResponse<PageResponse<Book>>> getBooks(
            @Query("page") int page,
            @Query("size") int size
    );

    @GET("/api/books/fiction")
    Call<ApiResponse<PageResponse<Book>>> getFictionBooks(
            @Query("page") int page,
            @Query("size") int size,
            @Query("category") String category
    );

    @GET("/api/books/fiction/categories")
    Call<ApiResponse<List<String>>> getFictionCategories();

    @GET("/api/books/{id}")
    Call<ApiResponse<Book>> getBookDetail(@Path("id") long id, @Query("userId") Long userId);

    @GET("/api/books/search")
    Call<ApiResponse<PageResponse<Book>>> searchBooks(
            @Query("keyword") String keyword,
            @Query("page") int page,
            @Query("size") int size
    );

    // ========== 章节 ==========
    @GET("/api/chapters/book/{bookId}")
    Call<ApiResponse<List<ChapterDto>>> getChapters(@Path("bookId") long bookId);

    // 获取最后一章（O(1) 查询，用于"连载至"显示）
    @GET("/api/books/{bookId}/latest-chapter")
    Call<ApiResponse<ChapterDto>> getLatestChapter(@Path("bookId") long bookId);

    @GET("/api/chapters/{id}")
    Call<ApiResponse<Chapter>> getChapterContent(@Path("id") long id);

    // ========== 书架 ==========
    @POST("/api/user/bookshelf/add")
    Call<ApiResponse<Bookshelf>> addToBookshelf(
            @Query("userId") long userId,
            @Query("bookId") long bookId
    );

    @GET("/api/user/bookshelf/{userId}")
    Call<ApiResponse<List<Bookshelf>>> getBookshelf(@Path("userId") long userId);

    @DELETE("/api/user/bookshelf/remove")
    Call<ApiResponse<Void>> removeFromBookshelf(
            @Query("userId") long userId,
            @Query("bookId") long bookId
    );

    @POST("/api/user/readtime/save")
    Call<ApiResponse<Void>> saveReadTime(@Body ReadTimeRequest request);

    @GET("/api/user/readtime/total/{userId}")
    Call<ApiResponse<Long>> getTotalReadTime(@Path("userId") long userId);
    // ========== 阅读进度 ==========
    @POST("/api/user/progress/save")
    Call<ApiResponse<ReadingProgress>> saveProgress(@Body ReadingProgress progress);

    @GET("/api/user/progress/{userId}/{bookId}")
    Call<ApiResponse<ReadingProgress>> getProgress(
            @Path("userId") long userId,
            @Path("bookId") long bookId
    );

    @DELETE("/api/user/progress/{userId}/{bookId}")
    Call<ApiResponse<Void>> deleteProgress(
            @Path("userId") long userId,
            @Path("bookId") long bookId
    );

    // ========== 书签 ==========
    @POST("/api/user/bookmark/add")
    Call<ApiResponse<Bookmark>> addBookmark(@Body Bookmark bookmark);

    @GET("/api/user/bookmark/{userId}/{bookId}")
    Call<ApiResponse<List<Bookmark>>> getBookmarks(
            @Path("userId") long userId,
            @Path("bookId") long bookId
    );

    @DELETE("/api/user/bookmark/{id}")
    Call<ApiResponse<Void>> deleteBookmark(@Path("id") long id);

    // ========== 意见反馈 ==========
    @POST("/api/feedback/submit")
    Call<ApiResponse<Void>> submitFeedback(@Body FeedbackRequest request);

    // ========== 消息中心 ==========
    @GET("/api/user/message/{userId}")
    Call<ApiResponse<PageResponse<Message>>> getMessages(@Path("userId") long userId);

    @GET("/api/user/message/{userId}")
    Call<ApiResponse<PageResponse<Message>>> getMessagesByType(
            @Path("userId") long userId,
            @Query("type") String type);

    @POST("/api/user/message/read/{id}")
    Call<ApiResponse<Void>> markMessageRead(@Path("id") long id);

    @POST("/api/user/message/read-all/{userId}")
    Call<ApiResponse<Void>> markAllMessagesRead(@Path("userId") long userId);

    @GET("/api/user/message/{userId}/unread-count")
    Call<ApiResponse<UnreadCountResponse>> getUnreadCount(@Path("userId") long userId);

    // ========== 字体 ==========
    @GET("/api/fonts")
    Call<ApiResponse<List<FontItem>>> getFonts();

    // ========== 账号绑定 ==========
    // 发送手机验证码
    @POST("/api/user/bind/send-sms-code")
    Call<ApiResponse<Void>> sendSmsCode(@Body SendCodeRequest request);

    // 发送邮箱验证码
    @POST("/api/user/bind/send-email-code")
    Call<ApiResponse<Void>> sendEmailCode(@Body SendCodeRequest request);

    // 验证手机验证码
    @POST("/api/user/bind/verify-sms-code")
    Call<ApiResponse<Void>> verifySmsCode(@Body BindRequest request);

    // 验证邮箱验证码
    @POST("/api/user/bind/verify-email-code")
    Call<ApiResponse<Void>> verifyEmailCode(@Body BindRequest request);

    // 绑定手机号
    @POST("/api/user/bind/phone")
    Call<ApiResponse<Void>> bindPhone(@Body BindRequest request);

    // 绑定邮箱
    @POST("/api/user/bind/email")
    Call<ApiResponse<Void>> bindEmail(@Body BindRequest request);

    // 解绑手机号
    @POST("/api/user/bind/unbind-phone")
    Call<ApiResponse<Void>> unbindPhone(@Body BindRequest request);

    // 解绑邮箱
    @POST("/api/user/bind/unbind-email")
    Call<ApiResponse<Void>> unbindEmail(@Body BindRequest request);

    // 修改密码
    @POST("/api/user/bind/change-password")
    Call<ApiResponse<Void>> changePassword(@Body BindRequest request);

    // ========== APP更新检查 ==========
    @GET("/api/app/update-check")
    Call<ApiResponse<ApkPush>> checkUpdate(@Query("clientVersion") String clientVersion);

    // ========== 在线书源 ==========
    @GET("/api/admin/online-source/sources")
    Call<ApiResponse<List<SourceInfo>>> getOnlineSources();

    @GET("/api/admin/online-source/search")
    Call<ApiResponse<List<Book>>> searchOnlineBooks(
            @Query("keyword") String keyword,
            @Query("sourceType") String sourceType,
            @Query("page") int page,
            @Query("size") int size
    );

    @GET("/api/admin/online-source/explore")
    Call<ApiResponse<List<Book>>> exploreOnlineBooks(
            @Query("sourceType") String sourceType,
            @Query("page") int page,
            @Query("size") int size,
            @Query("category") String category
    );

    /** 发现页分类列表 */
    @GET("/api/admin/online-source/explore-categories")
    Call<ApiResponse<List<String[]>>> getExploreCategories(
            @Query("sourceType") String sourceType
    );

    @GET("/api/admin/online-source/book")
    Call<ApiResponse<Book>> getOnlineBookDetail(
            @Query("sourceType") String sourceType,
            @Query("sourceBookId") String sourceBookId
    );

    @GET("/api/admin/online-source/chapters")
    Call<ApiResponse<List<String[]>>> getOnlineChapterList(
            @Query("sourceType") String sourceType,
            @Query("sourceBookId") String sourceBookId
    );

    @GET("/api/admin/online-source/content")
    Call<ApiResponse<String>> getOnlineChapterContent(
            @Query("sourceType") String sourceType,
            @Query("sourceBookId") String sourceBookId,
            @Query("chapterUrl") String chapterUrl
    );

    @POST("/api/admin/online-source/import")
    @FormUrlEncoded
    Call<ApiResponse<Book>> importOnlineBook(
            @Field("sourceType") String sourceType,
            @Field("sourceBookId") String sourceBookId
    );

    // ========== 网络导入书源 ==========
    @POST("/api/admin/book-source/import-url")
    Call<ApiResponse<ImportResult>> importBookSourceFromUrl(@Body java.util.Map<String, String> body);

    // ========== 外站书籍阅读进度 ==========
    @POST("/api/external-reading/save")
    @Headers("Content-Type: application/json")
    Call<ApiResponse<Void>> saveExternalProgress(@Body okhttp3.RequestBody body);

    @GET("/api/external-reading/get")
    Call<ApiResponse<java.util.Map<String, Object>>> getExternalProgress(
            @Query("userId") long userId,
            @Query("sourceType") String sourceType,
            @Query("sourceBookId") String sourceBookId
    );
}

