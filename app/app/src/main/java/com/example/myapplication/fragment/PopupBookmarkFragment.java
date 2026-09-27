//书签fragment
package com.example.myapplication.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.R;
import com.example.myapplication.activity.ReadActivity;
import com.example.myapplication.adapter.BookmarkAdapter;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.Bookmark;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.Hint;
import android.app.AlertDialog;
import android.widget.EditText;
import android.text.format.DateFormat;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PopupBookmarkFragment extends Fragment {

    private List<Bookmark> bookmarkList = new ArrayList<>();
    private BookmarkAdapter adapter;
    private OnBookmarkSelectedListener listener;
    private long bookId;
    private long userId;
    private TextView tvEmpty;
    private RecyclerView rvBookmarks;
    private boolean isLocalBook = false;
    private String sourceType;
    private String sourceBookId;

    public interface OnBookmarkSelectedListener {
        void onBookmarkSelected(Bookmark bookmark);
    }

    public PopupBookmarkFragment() {
        // 无参构造，配合 Fragment 生命周期
    }

    public void setBookId(long bookId) {
        this.bookId = bookId;
    }

    public void setIsLocalBook(boolean isLocalBook) {
        this.isLocalBook = isLocalBook;
    }

    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public void setSourceBookId(String sourceBookId) { this.sourceBookId = sourceBookId; }

    private boolean isExternal() {
        return sourceType != null && !sourceType.isEmpty()
                && sourceBookId != null && !sourceBookId.isEmpty();
    }

    public void setOnBookmarkSelectedListener(OnBookmarkSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            View view = inflater.inflate(R.layout.fragment_popup_bookmark, container, false);

            tvEmpty = view.findViewById(R.id.tv_empty_bookmark);
            rvBookmarks = view.findViewById(R.id.rv_popup_bookmarks);
            rvBookmarks.setLayoutManager(new LinearLayoutManager(getContext()));

            adapter = new BookmarkAdapter(bookmarkList);
            adapter.setFollowReaderTheme(true);   // 仅阅读器书签页跟随背景派生配色
            adapter.setOnBookmarkClickListener(bookmark -> {
                if (listener != null) {
                    try { listener.onBookmarkSelected(bookmark); }
                    catch (Throwable t) {
                        android.util.Log.e("PopupBookmark", "点击书签回调异常", t);
                        Hint.show(getContext(), "书签打开失败：" + t.getMessage());
                    }
                }
            });
            adapter.setOnBookmarkLongClickListener((bookmark, position) -> {
                try { showBookmarkDetailDialog(bookmark); }
                catch (Throwable t) {
                    android.util.Log.e("PopupBookmark", "长按书签崩溃", t);
                    Hint.show(getContext(), "书签详情打开失败");
                }
            });
            rvBookmarks.setAdapter(adapter);

            // 获取 userId
            SharedPreferences sp = getActivity().getSharedPreferences("user_info", Context.MODE_PRIVATE);
            String uid = sp.getString("userId", "");
            try {
                userId = uid.isEmpty() ? 0 : Long.parseLong(uid);
            } catch (NumberFormatException nfe) {
                userId = 0;
            }

            loadBookmarks();

            // ✅ 书签 Fragment 根视图跟随日/夜间；列表项由 BookmarkAdapter 在 bind 时着色
            ReadActivity.themeViewTree(view);
            return view;
        } catch (Throwable t) {
            android.util.Log.e("PopupBookmark", "onCreateView 崩溃", t);
            // Fragment 创建失败返回空 View，防止宿主 Activity 白屏→finish
            View fallback = new View(container != null ? container.getContext() : inflater.getContext());
            fallback.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            try {
                Hint.show(inflater.getContext(), "书签加载失败：" + t.getMessage());
            } catch (Throwable ignored) {}
            return fallback;
        }
    }

    private void loadBookmarks() {
        try {
            if (isLocalBook) {
                loadLocalBookmarks();
            } else {
                loadNetworkBookmarks();
            }
        } catch (Throwable t) {
            android.util.Log.e("PopupBookmark", "loadBookmarks 崩溃", t);
        }
    }

    private void loadLocalBookmarks() {
        if (bookId == 0) return;

        SharedPreferences sp = getActivity().getSharedPreferences("local_bookmarks_" + bookId, Context.MODE_PRIVATE);
        int count = sp.getInt("bookmark_count", 0);

        bookmarkList.clear();

        for (int i = 0; i < count; i++) {
            Bookmark bookmark = new Bookmark();
            bookmark.setChapterIndex(sp.getInt("bookmark_chapterIndex_" + i, 0));
            bookmark.setChapterTitle(sp.getString("bookmark_chapterTitle_" + i, ""));
            bookmark.setScrollPosition(sp.getInt("bookmark_page_" + i, 1));
            bookmark.setPreviewText(sp.getString("bookmark_preview_" + i, ""));

            long time = sp.getLong("bookmark_time_" + i, 0);
            bookmark.setId(time);
            bookmark.setCreatedAt(formatTimestamp(time));

            bookmarkList.add(bookmark);
        }

        // 按时间倒序；getId() 为 null 时放到末尾，避免 Long.compare 自动拆箱 NPE
        bookmarkList.sort((a, b) -> {
            Long idA = a.getId();
            Long idB = b.getId();
            long va = (idA == null ? 0L : idA);
            long vb = (idB == null ? 0L : idB);
            return Long.compare(vb, va);
        });

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                try {
                    adapter.notifyDataSetChanged();
                    if (bookmarkList.isEmpty()) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        rvBookmarks.setVisibility(View.GONE);
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        rvBookmarks.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    private void loadNetworkBookmarks() {
        // 外站书（无服务端书 ID）直接跳过，避免 userId/bookId==0 时无效请求
        if (userId == 0 || bookId == 0) return;

        RetrofitClient.getApiService().getBookmarks(userId, bookId)
                .enqueue(new Callback<ApiResponse<List<Bookmark>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<Bookmark>>> call, Response<ApiResponse<List<Bookmark>>> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                            List<Bookmark> list = response.body().getData();
                            bookmarkList.clear();
                            if (list != null) {
                                bookmarkList.addAll(list);
                            }
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    try {
                                        adapter.notifyDataSetChanged();
                                        if (bookmarkList.isEmpty()) {
                                            tvEmpty.setVisibility(View.VISIBLE);
                                            rvBookmarks.setVisibility(View.GONE);
                                        } else {
                                            tvEmpty.setVisibility(View.GONE);
                                            rvBookmarks.setVisibility(View.VISIBLE);
                                        }
                                    } catch (Throwable ignored) {}
                                });
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<List<Bookmark>>> call, Throwable t) {
                        try { Hint.show(getContext(), "加载书签失败"); }
                        catch (Throwable ignored) {}
                    }
                });
    }

    private void loadExternalBookmarks() {
        if (getActivity() == null) return;
        SharedPreferences sp = getActivity().getSharedPreferences(
                ExternalPrefs.bookmarkName(getActivity()), Context.MODE_PRIVATE);
        int count = sp.getInt("bookmark_count", 0);
        bookmarkList.clear();
        for (int i = 0; i < count; i++) {
            String p = "bm_" + i + "_";
            Bookmark bookmark = new Bookmark();
            bookmark.setChapterIndex(sp.getInt(p + "chapterIndex", 0));
            bookmark.setChapterTitle(sp.getString(p + "chapterTitle", ""));
            bookmark.setScrollPosition(sp.getInt(p + "scrollPosition", 1));
            bookmark.setPreviewText(sp.getString(p + "preview", ""));
            bookmark.setNote(sp.getString(p + "note", ""));
            long time = sp.getLong(p + "time", 0);
            bookmark.setId(time);
            bookmark.setCreatedAt(formatTimestamp(time));
            bookmarkList.add(bookmark);
        }
        bookmarkList.sort((a, b) -> {
            long va = (a.getId() == null ? 0L : a.getId());
            long vb = (b.getId() == null ? 0L : b.getId());
            return Long.compare(vb, va);
        });
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                try {
                    adapter.notifyDataSetChanged();
                    if (bookmarkList.isEmpty()) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        rvBookmarks.setVisibility(View.GONE);
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        rvBookmarks.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp == 0) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }

    private void showBookmarkDetailDialog(Bookmark bookmark) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_bookmark_detail, null);
        // 书签详情弹窗同样跟随阅读器当前背景色（与列表条目配色一致）
        ReadActivity.themeViewTree(dialogView);

        TextView tvChapter = dialogView.findViewById(R.id.tv_detail_chapter);
        TextView tvPreview = dialogView.findViewById(R.id.tv_detail_preview);
        TextView tvTime = dialogView.findViewById(R.id.tv_detail_time);
        EditText etNote = dialogView.findViewById(R.id.et_note);

        tvChapter.setText(bookmark != null && bookmark.getChapterTitle() != null ? bookmark.getChapterTitle() : "");
        tvPreview.setText(bookmark != null && bookmark.getPreviewText() != null ? bookmark.getPreviewText() : "");
        tvTime.setText(formatTime(bookmark != null ? bookmark.getCreatedAt() : null));
        etNote.setText(bookmark != null && bookmark.getNote() != null ? bookmark.getNote() : "");

        AlertDialog dialog = new AlertDialog.Builder(getContext(), R.style.RoundedDialog)
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.tv_delete_bookmark).setOnClickListener(v -> {
            try { deleteBookmark(bookmark); }
            catch (Throwable t) { android.util.Log.e("PopupBookmark", "删除书签异常", t); }
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.tv_cancel).setOnClickListener(v -> dialog.dismiss());

        dialogView.findViewById(R.id.tv_confirm).setOnClickListener(v -> {
            if (bookmark != null) {
                bookmark.setNote(etNote.getText() != null ? etNote.getText().toString() : "");
                try { updateBookmarkNote(bookmark); }
                catch (Throwable t) { android.util.Log.e("PopupBookmark", "保存备注异常", t); }
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void deleteBookmark(Bookmark bookmark) {
        if (isLocalBook) {
            deleteLocalBookmark(bookmark);
        } else {
            deleteNetworkBookmark(bookmark);
        }
    }

    private void deleteLocalBookmark(Bookmark bookmark) {
        if (bookId == 0 || bookmark == null || bookmark.getId() == null) return;

        SharedPreferences sp = getActivity().getSharedPreferences("local_bookmarks_" + bookId, Context.MODE_PRIVATE);
        int count = sp.getInt("bookmark_count", 0);

        int deleteIndex = -1;
        // Long→long 前先 null 安全夹取
        long targetTime = bookmark.getId() == null ? 0L : bookmark.getId();

        for (int i = 0; i < count; i++) {
            long time = sp.getLong("bookmark_time_" + i, 0);
            if (time == targetTime) {
                deleteIndex = i;
                break;
            }
        }

        if (deleteIndex == -1) return;

        SharedPreferences.Editor editor = sp.edit();

        for (int i = deleteIndex; i < count - 1; i++) {
            editor.putInt("bookmark_chapterIndex_" + i, sp.getInt("bookmark_chapterIndex_" + (i + 1), 0));
            editor.putString("bookmark_chapterTitle_" + i, sp.getString("bookmark_chapterTitle_" + (i + 1), ""));
            editor.putInt("bookmark_page_" + i, sp.getInt("bookmark_page_" + (i + 1), 1));
            editor.putString("bookmark_preview_" + i, sp.getString("bookmark_preview_" + (i + 1), ""));
            editor.putLong("bookmark_time_" + i, sp.getLong("bookmark_time_" + (i + 1), 0));
        }

        editor.remove("bookmark_chapterIndex_" + (count - 1));
        editor.remove("bookmark_chapterTitle_" + (count - 1));
        editor.remove("bookmark_page_" + (count - 1));
        editor.remove("bookmark_preview_" + (count - 1));
        editor.remove("bookmark_time_" + (count - 1));

        editor.putInt("bookmark_count", count - 1);
        editor.apply();

        bookmarkList.remove(bookmark);
        adapter.notifyDataSetChanged();
        Hint.show(getContext(), "书签已删除");
    }

    private void deleteNetworkBookmark(Bookmark bookmark) {
        if (bookmark == null || bookmark.getId() == null) {
            if (bookmark != null) {
                bookmarkList.remove(bookmark);
                adapter.notifyDataSetChanged();
            }
            return;
        }
        RetrofitClient.getApiService().deleteBookmark(bookmark.getId()).enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    bookmarkList.remove(bookmark);
                    adapter.notifyDataSetChanged();
                    Hint.show(getContext(), "书签已删除");
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {}
        });
    }

    private void updateBookmarkNote(Bookmark bookmark) {
        if (bookmark == null) return;
        RetrofitClient.getApiService().addBookmark(bookmark).enqueue(new Callback<ApiResponse<Bookmark>>() {
            @Override
            public void onResponse(Call<ApiResponse<Bookmark>> call, Response<ApiResponse<Bookmark>> response) {
                adapter.notifyDataSetChanged();
                Hint.show(getContext(), "备注已保存");
            }
            @Override
            public void onFailure(Call<ApiResponse<Bookmark>> call, Throwable t) {
                Hint.show(getContext(), "保存失败");
            }
        });
    }

    private String formatTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return "";
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
            SimpleDateFormat output = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
            Date date = input.parse(timeStr);
            return output.format(date);
        } catch (Exception e) {
            return timeStr.replace("T", " ");
        }
    }
}
