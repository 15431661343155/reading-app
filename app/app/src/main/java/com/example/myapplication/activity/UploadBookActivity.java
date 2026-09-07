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
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.example.myapplication.R;
import com.example.myapplication.utils.LocalBookParser;
import com.example.myapplication.utils.ThemeManager;

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
        int currentTheme = ThemeManager.getCurrentTheme(this);
        setTheme(ThemeManager.getThemeRes(currentTheme));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload_book);

        initView();

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing
        // 海滨主题 → 黑色箭头
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        }
        // 默认主题 → 白色箭头
        else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }
        // 海滨主题工具栏为浅色，状态栏图标用深色
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);

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
            //获取文件名
            selectedFileName = getFileName(uri);
            tvFileName.setText("已选择文件："+selectedFileName);

            //校验后缀
            if(!isEbookFile(selectedFileName)){
                Toast.makeText(this,"请选择 TXT 或 EPUB 电子书",Toast.LENGTH_SHORT).show();
                selectedFileUri = null;
                tvFileName.setText("未选择文件");
            }
        }
    }

    //获取文件名
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

    //判断是否是txt/epub
    private boolean isEbookFile(String name){
        if(name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".epub");
    }

    private void submitBookInfo(){
        String bookName = etBookName.getText().toString().trim();
        String author = etAuthor.getText().toString().trim();

        if(selectedFileUri == null){
            Toast.makeText(this,"请选择TXT/EPUB电子书文件",Toast.LENGTH_SHORT).show();
            return;
        }

        // ========== 解析书籍 ==========
        long bookId = System.currentTimeMillis();

        LocalBookParser.BookInfo bookInfo = LocalBookParser.parse(this, selectedFileUri, selectedFileName, bookId);

        if(bookName.isEmpty()) bookName = bookInfo.title;
        if(author.isEmpty()) author = bookInfo.author;

        // 缓存到本地
        cacheLocalBook(bookId, bookName, author, bookInfo);

        Toast.makeText(this,"成功导入：" + bookName + "（" + bookInfo.chapters.size() + "章）",Toast.LENGTH_SHORT).show();
        finish();
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
        editor.putInt("chapter_count_" + count, bookInfo.chapters.size());

        for (int i = 0; i < bookInfo.chapters.size(); i++) {
            editor.putString("chapter_title_" + count + "_" + i, bookInfo.chapters.get(i).title);
            editor.putString("chapter_content_" + count + "_" + i, bookInfo.chapters.get(i).content);
        }

        editor.apply();
    }
}