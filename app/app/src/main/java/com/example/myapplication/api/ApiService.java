package com.example.myapplication.api;

import com.example.myapplication.bean.Chapter;
import com.example.myapplication.bean.ChapterDto;
import com.example.myapplication.bean.*;
import java.util.List;

import retrofit2.Call;
import retrofit2.http.*;

import okhttp3.MultipartBody;

public interface ApiService {

    // ========== 用户认证 ==========
    @POST("/api/auth/register")
    Call<ApiResponse<Void>> register(@Body RegisterRequest request);

    @POST("/api/auth/login")
    Call<ApiResponse<LoginResponse>> login(@Body LoginRequest request);

    // 发送邮箱验证码（登录 / 注册通用，匿名可调；复用 SendCodeRequest 的 email 字段）
    @POST("/api/auth/email-code")
    Call<ApiResponse<Void>> sendAuthEmailCode(@Body SendCodeRequest request);

    // 邮箱验证码登录（未注册邮箱首次登录自动创建账号）
    @POST("/api/auth/login-by-code")
    Call<ApiResponse<LoginResponse>> loginByEmailCode(@Body CodeLoginRequest request);

    // 邮箱注册（邮箱 + 验证码 + 用户名 + 密码）
    @POST("/api/auth/register-by-email")
    Call<ApiResponse<LoginResponse>> registerByEmail(@Body EmailRegisterRequest request);

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
            @Query("category") String category,
            @Query("mainCategory") String mainCategory,
            @Query("subCategory") String subCategory,
            @Query("sortBy") String sortBy
    );

    /** 书籍分类树（主分类 + 子分类），「分类」页与后台分类管理的数据源 */
    @GET("/api/books/category-tree")
    Call<ApiResponse<CategoryTree>> getCategoryTree();

    /** 阅读量埋点：打开本站书籍详情时 +1（书城「热门」按它排序）；后端 200/失败都静默处理 */
    @POST("/api/books/{id}/view")
    Call<ApiResponse<Void>> incrementBookView(@Path("id") long id);

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

    // 获取某本书的分卷列表（后台导入 EPUB 时写入；App 端公开只读，用于目录分卷折叠展示）
    @GET("/api/major-chapters/book/{bookId}")
    Call<ApiResponse<List<MajorChapter>>> getMajorChapters(@Path("bookId") long bookId);

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

    // ========== 阅读统计（账号级：连续天数 / 累计时长） ==========
    // 个人中心统计卡：一次拿到连续天数 + 累计时长 + 累计打卡天数
    @GET("/api/user/reading-stat/{userId}")
    Call<ApiResponse<UserReadingStat>> getReadingStat(@Path("userId") long userId);

    // 阅读打卡：记「今天读过」并推进连续天数（幂等，同一天重复调不叠加）
    @POST("/api/user/reading-stat/checkin")
    Call<ApiResponse<UserReadingStat>> checkInReading(@Body ReadingCheckInRequest request);
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

    // ========== 个人资料（自助修改，当前用户由后端从 JWT 取） ==========
    /** 读取当前登录用户资料，用于「个人信息」页回填 */
    @GET("/api/user/profile")
    Call<ApiResponse<java.util.Map<String, Object>>> getProfile();

    /** 修改昵称 / 性别 / 头像（null 字段表示不修改） */
    @PUT("/api/user/profile")
    Call<ApiResponse<java.util.Map<String, Object>>> updateProfile(@Body ProfileUpdateRequest request);

    /** 上传头像；成功后返回相对路径 /avatars/xxx.jpg */
    @Multipart
    @POST("/api/user/avatar")
    Call<ApiResponse<java.util.Map<String, Object>>> uploadAvatar(@Part MultipartBody.Part file);

    /** 注销当前账号：后端级联删除全部私有数据，当前用户由 JWT 决定（不接受请求体 userId） */
    @DELETE("/api/user/account")
    Call<ApiResponse<Void>> cancelAccount();

    // ========== 账号绑定 ==========
    @POST("/api/user/bind/send-email-code")
    Call<ApiResponse<Void>> sendEmailCode(@Body SendCodeRequest request);

    @POST("/api/user/bind/verify-email-code")
    Call<ApiResponse<Void>> verifyEmailCode(@Body BindRequest request);

    @POST("/api/user/bind/email")
    Call<ApiResponse<Void>> bindEmail(@Body BindRequest request);

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
    Call<ApiResponse<Void>> saveExternalProgress(@Header("Authorization") String authorization,
            @Body okhttp3.RequestBody body);

    @GET("/api/external-reading/get")
    Call<ApiResponse<java.util.Map<String, Object>>> getExternalProgress(
            @Query("userId") long userId,
            @Query("sourceType") String sourceType,
            @Query("sourceBookId") String sourceBookId
    );

    // ========== 外站书架同步 ==========
    @POST("/api/external-bookshelf/add")
    @Headers("Content-Type: application/json")
    Call<ApiResponse<Void>> addExternalBookshelf(@Header("Authorization") String authorization,
            @Body okhttp3.RequestBody body);

    @POST("/api/external-bookshelf/remove")
    @Headers("Content-Type: application/json")
    Call<ApiResponse<Void>> removeExternalBookshelf(@Header("Authorization") String authorization,
            @Body okhttp3.RequestBody body);

    @GET("/api/external-bookshelf/list")
    Call<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getExternalBookshelfList(
            @Query("userId") long userId);

    // ========== 外站书签同步 ==========
    @POST("/api/external-bookmarks/add")
    @Headers("Content-Type: application/json")
    Call<ApiResponse<Void>> addExternalBookmark(@Header("Authorization") String authorization,
            @Body okhttp3.RequestBody body);

    @POST("/api/external-bookmarks/delete")
    @Headers("Content-Type: application/json")
    Call<ApiResponse<Void>> deleteExternalBookmark(@Header("Authorization") String authorization,
            @Body okhttp3.RequestBody body);

    @GET("/api/external-bookmarks/list")
    Call<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getExternalBookmarkList(
            @Query("userId") long userId);

    // ========== 外站阅读记录全量拉取（登录时） ==========
    @GET("/api/external-reading/list")
    Call<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getExternalReadingList(
            @Query("userId") long userId);

    // ========== 外站阅读记录删除（阅读记录页删除时同步服务器，避免再次登录被拉回） ==========
    @POST("/api/external-reading/delete")
    @Headers("Content-Type: application/json")
    Call<ApiResponse<Void>> deleteExternalReading(@Header("Authorization") String authorization,
            @Body okhttp3.RequestBody body);
}

