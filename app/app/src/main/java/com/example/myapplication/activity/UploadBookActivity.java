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
        // 只允许本地文件：避免网盘类 DocumentsProvider 不支持持久授权或不可 seek
        intent.setType("*/*");
        String[] mimeTypes = {"text/plain", "application/epub+zip"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        // 授权要能在重启后继续用：懒解析的书靠这个 Uri 回源读单章，不留整本副本
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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

            // 把一次性授权转成持久授权（导入的书以后还要按章回源读这个文件）。
            //    个别 DocumentsProvider 不支持持久授权，失败时验证当前能否打开文件：
            //    能打开 → 只是 provider 不支持持久化标记，本次导入仍可用；
            //    打不开 → 彻底阻断，提示用户选本地文件。
            boolean persistOk = true;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                android.util.Log.w("UploadBookActivity", "takePersistableUriPermission 失败: " + e.getMessage());
                persistOk = false;
            }
            // 即使持久授权失败，也立刻验证当前能否访问（防止「导入成功但重启后读不到」）
            try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null || pfd.getStatSize() < 0) {
                    Hint.show(this, "无法访问该文件，请选择设备本地存储中的电子书");
                    selectedFileUri = null;
                    tvFileName.setText("未选择文件");
                    return;
                }
            } catch (Exception e) {
                android.util.Log.w("UploadBookActivity", "源文件访问失败: " + e.getMessage());
                Hint.show(this, "无法访问该文件，请选择设备本地存储中的电子书");
                selectedFileUri = null;
                tvFileName.setText("未选择文件");
                return;
            }
            // 持久授权失败但当前能访问 → 警告用户不要重启应用后再读
            if (!persistOk) {
                android.util.Log.w("UploadBookActivity", "持久授权失败，但当前可访问；重启后可能无法读取该书");
            }

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

        // 查重：同一个文件（源 Uri 相同，或 size+mtime 指纹相同）已在书架就不再重复导入。
        //    拦在解析之前——重复导入时省掉整本解析那几秒开销，也不会往书架多塞一个槽位。
        String dupName = findDuplicateInShelf(fileUri);
        if (dupName != null) {
            Hint.show(this, "《" + dupName + "》已在书架中，无需重复导入");
            return;
        }

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
            long tImport0 = System.currentTimeMillis();
            long tParse = 0, tCache = 0;
            try {
                parsed = LocalBookParser.parse(this, fileUri, fileName, bookId);
                tParse = System.currentTimeMillis();
                String finalName = inputName.isEmpty() ? parsed.title : inputName;
                String finalAuthor = inputAuthor.isEmpty() ? parsed.author : inputAuthor;
                cacheLocalBook(bookId, finalName, finalAuthor, parsed, fileUri);
                tCache = System.currentTimeMillis();
            } catch (Throwable t) {
                errMsg = (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                android.util.Log.e("UploadBookActivity", "本地书导入失败", t);
                // 兜底：解析/缓存中途失败，清掉可能已写入的半成品 HTML 缓存
                LocalBookParser.deleteHtmlCache(this, bookId);
            }

            final LocalBookParser.BookInfo bookInfo = parsed;
            final String err = errMsg;
            if (err == null && bookInfo != null) {
                android.util.Log.d("UploadBookActivity", "IMPORTPERF chapters="
                        + bookInfo.chapters.size()
                        + " parseMs=" + (tParse - tImport0)
                        + " cacheMs=" + (tCache - tParse)
                        + " totalMs=" + (tCache - tImport0));
            }
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

    private void cacheLocalBook(long bookId, String bookName, String author,
                                LocalBookParser.BookInfo bookInfo, Uri sourceUri) {
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
        // 源文件指针：懒解析的书不复制整本进来，读章节时按这个 Uri 回源。
        //    指纹（大小+修改时间）用来识别「文件被换成了另一本」——那种情况必须拒绝按索引读，
        //    否则会按旧条子去新文件里取章，取到什么完全不可预期。
        editor.putString("book_source_uri_" + count, sourceUri == null ? "" : sourceUri.toString());
        editor.putString("book_source_stamp_" + count, sourceStamp(sourceUri));

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
        LocalBookParser.writeBookChapters(this, bookId, bookInfo);

        editor.apply();
    }

    /**
     * 书架里是否已有「同一个文件」：源 Uri 相同，或 size+mtime 指纹相同即算重复。
     * 命中返回该书书名（用于提示），无重复返回 {@code null}。
     * 槽位在删书时会压缩重排为连续的 0..count-1，故直接顺序遍历即可。
     */
    private String findDuplicateInShelf(Uri uri) {
        if (uri == null) return null;
        String uriStr = uri.toString();
        String stamp = sourceStamp(uri);
        boolean stampUsable = isUsableStamp(stamp);
        SharedPreferences sp = getSharedPreferences("local_books", MODE_PRIVATE);
        int count = sp.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            String existUri = sp.getString("book_source_uri_" + i, "");
            if (!existUri.isEmpty() && existUri.equals(uriStr)) {
                return sp.getString("book_name_" + i, "该书");
            }
            // 指纹兜底：同一文件经不同 provider/路径选中时 Uri 可能不同，但 size+mtime 一致。
            //    残缺指纹（size:-1）不参与比较，否则两本读不到信息的书会互相误判为重复。
            if (stampUsable) {
                String existStamp = sp.getString("book_source_stamp_" + i, "");
                if (isUsableStamp(existStamp) && existStamp.equals(stamp)) {
                    return sp.getString("book_name_" + i, "该书");
                }
            }
        }
        return null;
    }

    /** 指纹形如 {@code "size:N,mtime:M"}；size 没读到（-1）视为残缺，不能用于判重。 */
    private static boolean isUsableStamp(String stamp) {
        return stamp != null && !stamp.isEmpty() && !stamp.startsWith("size:-1,");
    }

    /** 源文件指纹「size:字节数,mtime:毫秒」；查不到留空串（无指纹时懒解析一律不信索引） */
    private String sourceStamp(Uri uri) {
        if (uri == null) return "";
        long size = -1, mtime = -1;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                int mtimeIdx = c.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx);
                if (mtimeIdx >= 0 && !c.isNull(mtimeIdx)) mtime = c.getLong(mtimeIdx);
            }
        } catch (Exception e) {
            android.util.Log.w("UploadBookActivity", "源文件指纹读取失败: " + e.getMessage());
        }
        // 查询拿不到 SIZE 时退回打开文件问长度（部分 provider 不暴露列）
        if (size < 0) {
            try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) size = pfd.getStatSize();
            } catch (Exception ignore) { /* 读不到就留空指纹 */ }
        }
        return "size:" + size + ",mtime:" + mtime;
    }
}