package com.example.myapplication.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.ThemeManager;
import com.example.myapplication.utils.Hint;

//图书上传页
public class UploadBookActivity extends BaseActivity {

    private EditText etBookName, etAuthor;
    private Button btnSelectFile, btnSubmit;
    private TextView tvFileName;

    //权限常量
    private static final int FILE_SELECT_CODE = 2001;

    private Uri selectedFileUri;     //选中文件Uri
    private String selectedFileName; //选中文件名

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload_book);

        initView();

        Toolbar toolbar = findViewById(R.id.toolbar_back);

        // 返回点击事件
        toolbar.setNavigationOnClickListener(v -> finish());

        //选择文件按钮
        //btnSelectFile.setOnClickListener(v -> checkPermissionAndOpenFileChooser());
        btnSelectFile.setOnClickListener(v -> openFileChooser());
        //提交按钮
        btnSubmit.setOnClickListener(v -> submitBookInfo());
    }

    private void initView() {
        etBookName = findViewById(R.id.et_book_name);
        etAuthor = findViewById(R.id.et_author);
        btnSelectFile = findViewById(R.id.btn_select_file);
        btnSubmit = findViewById(R.id.btn_submit);

        tvFileName = findViewById(R.id.tv_file_name);
    }

    @SuppressWarnings("deprecation")
    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"text/plain", "application/epub+zip"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, FILE_SELECT_CODE);
    }

    //===== 4 文件选择回调 =====
    @SuppressLint("SetTextI18n")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == FILE_SELECT_CODE && resultCode == RESULT_OK && data != null){
            Uri uri = data.getData();
            if(uri == null) return;

            selectedFileUri = uri;
            selectedFileName = getFileName(uri);
            tvFileName.setText("已选择文件："+selectedFileName);

            if(!isEbookFile(selectedFileName)){
                Hint.show(this, "请选择 TXT 或 EPUB 电子书");
                selectedFileUri = null;
                tvFileName.setText("未选择文件");
            }
        }
    }

    private String getFileName(Uri uri) {
        String fileName = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("UploadBookActivity", "Error reading file", e);
            }
        }
        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }
        return fileName != null ? fileName : "未知文件";
    }

    private boolean isEbookFile(String name){
        if(name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".epub");
    }

    @SuppressWarnings("deprecation")
    private void submitBookInfo(){
        final String inputName = etBookName.getText().toString().trim();
        final String inputAuthor = etAuthor.getText().toString().trim();

        if(selectedFileUri == null){
            Hint.show(this, "请选择TXT/EPUB电子书文件");
            return;
        }

        final Uri fileUri = selectedFileUri;
        final String fileName = selectedFileName;

        // ========== 解析书籍（后台线程） ==========
        // 大 EPUB 解压 + 图片内联可能耗时数秒，放 UI 线程会卡死甚至 ANR。
        // 注意：本地导入全程不联网，离线状态同样可用。
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在导入，请稍候…");
        pd.setCancelable(false);
        pd.show();
        btnSubmit.setEnabled(false);

        new Thread(() -> {
            final long bookId = System.currentTimeMillis();
            LocalBookParser.BookInfo parsed = null;
            String errMsg = null;
            try {
                parsed = LocalBookParser.parse(this, fileUri, fileName, bookId);
                String finalName = inputName.isEmpty() ? parsed.title : inputName;
                String finalAuthor = inputAuthor.isEmpty() ? parsed.author : inputAuthor;
                cacheLocalBook(bookId, finalName, finalAuthor, parsed);
            } catch (Throwable t) {
                errMsg = (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                android.util.Log.e("UploadBookActivity", "本地书导入失败", t);
                // 兜底：解析/缓存中途失败，清掉可能已写入的半成品 HTML 缓存
                LocalBookParser.deleteHtmlCache(this, bookId);
            }

            final LocalBookParser.BookInfo bookInfo = parsed;
            final String err = errMsg;
            runOnUiThread(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (err != null || bookInfo == null) {
                    btnSubmit.setEnabled(true);
                    Hint.showLong(this, "导入失败：" + (err != null ? err : "未知错误"));
                    return;
                }
                String okName = inputName.isEmpty() ? bookInfo.title : inputName;
                Hint.show(this, "成功导入：" + okName + "（" + bookInfo.chapters.size() + "章）");
                finish();
            });
        }, "local-book-import").start();
    }

    private void cacheLocalBook(long bookId, String bookName, String author, LocalBookParser.BookInfo bookInfo) {
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();

        int count = sp.getInt("count", 0);
        editor.putInt("count", count + 1);

        editor.putLong("book_id_" + count, bookId);
        editor.putString("book_name_" + count, bookName);
        editor.putString("book_author_" + count, author);
        editor.putString("book_cover_" + count, bookInfo.cover != null ? bookInfo.cover : "");
        editor.putString("book_cover_path_" + count, bookInfo.coverPath != null ? bookInfo.coverPath : "");
        // 简介（来自 EPUB 的 dc:description 或「内容简介」章）；很短，放 SP 无压力
        editor.putString("book_intro_" + count, bookInfo.intro != null ? bookInfo.intro : "");
        editor.putInt("chapter_count_" + count, bookInfo.chapters.size());

        for (int i = 0; i < bookInfo.chapters.size(); i++) {
            LocalBookParser.Chapter ch = bookInfo.chapters.get(i);
            editor.putString("chapter_title_" + count + "_" + i, ch.title);
        }

        // 分卷结构（TXT 的「第X卷」/「（第X卷完）」、EPUB 的 NCX 层级）。
        // 只存「每卷起始章 + 目录子项起始章 + 卷标题」这一张紧凑表（卷数量级为个位数），
        // 不为每章各存一个卷号键——SP 是整文件读写，键越多导入越慢。
        // 阅读器目录据此把卷渲染成可折叠分组；无分卷的书这张表为空，目录保持平铺。
        List<LocalBookParser.VolumeInfo> volumes = LocalBookParser.buildVolumeInfos(bookInfo);
        editor.putInt("book_volume_count_" + count, volumes.size());
        for (int v = 0; v < volumes.size(); v++) {
            LocalBookParser.VolumeInfo vi = volumes.get(v);
            editor.putString("book_volume_title_" + count + "_" + v, vi.title != null ? vi.title : "");
            editor.putInt("book_volume_start_" + count + "_" + v, vi.start);
            editor.putInt("book_volume_child_" + count + "_" + v, vi.childStart);
            editor.putInt("book_volume_end_" + count + "_" + v, vi.end);
        }
        // 正文与 HTML 一律落文件缓存，不进 SharedPreferences：
        // SP 是整文件 DOM 读写，1000+ 章的正文序列化出的 XML 有十几 MB，
        // 每次写入都要重建整棵 DOM 并全量落盘，会导致「正在导入」长时间卡住。
        // 且整本书只写**一个**容器文件（按章各写一个文件时，1800 章会产生 3600 个文件，
        // 实测这种规模的光是建文件就要 70 秒以上）。
        LocalBookParser.writeBookChapters(this, bookId, bookInfo.chapters);

        editor.apply();
    }
}