package com.example.myapplication.activity;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import androidx.annotation.NonNull;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.bean.ApiResponse;
import com.example.myapplication.bean.ProfileUpdateRequest;
import com.example.myapplication.utils.AvatarCache;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.Hint;
import com.example.myapplication.utils.LoginHelper;
import com.example.myapplication.utils.ProfileSync;
import com.example.myapplication.utils.ThemeManager;

import java.io.File;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ProfileActivity extends BaseActivity {

    private ImageView ivAvatar;
    private TextView tvUsername;
    private TextView tvGender;
    private TextView tvUid;
    private TextView tvEmail;
    private TextView tvPwdHint;

    private SharedPreferences sp;
    private int currentGender = 0; // 0=保密, 1=男, 2=女
    private String avatarPath;
    private String nicknameInput = "";

    /** 进入页面时的头像路径，用于判断本次是否换了头像（只有换了才需要重新上传） */
    private String originalAvatarPath = "";

    /** 保存按钮（导航栏「完成」触发） */
    private TextView btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        Toolbar toolbar = findViewById(R.id.toolbar_back);

        int currentTheme = ThemeManager.getCurrentTheme(this);
        if (currentTheme == ThemeManager.THEME_SEASIDE) {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF000000);
        } else {
            toolbar.setNavigationIcon(R.drawable.ic_back_black);
            toolbar.setTitleTextColor(0xFF1D1D1F);
        }
        setLightStatusBar(currentTheme == ThemeManager.THEME_SEASIDE);
        toolbar.setNavigationOnClickListener(v -> finish());

        // 导航栏右侧「完成」（蓝色，保存资料）
        btnSave = new TextView(this);
        btnSave.setText(R.string.done);
        btnSave.setTextSize(17);
        btnSave.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSave.setTextColor(0xFF007AFF);
        btnSave.setPadding(16, 0, 16, 0);
        Toolbar.LayoutParams lp = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT, Gravity.END);
        // 完成按钮从屏幕右缘内移一些（向右留出 8dp 间距），不再贴边
        lp.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
        btnSave.setLayoutParams(lp);
        btnSave.setOnClickListener(v -> saveProfile());
        toolbar.addView(btnSave);

        sp = getSharedPreferences("user_info", MODE_PRIVATE);

        ivAvatar = findViewById(R.id.iv_avatar);
        tvUsername = findViewById(R.id.tv_username);
        tvGender = findViewById(R.id.tv_gender);
        tvUid = findViewById(R.id.tv_uid);
        tvEmail = findViewById(R.id.tv_email);
        tvPwdHint = findViewById(R.id.tv_pwd_hint);

        loadUserData();

        // 云端资料对齐：Web 端改了头像/昵称/性别等，进入本页即拉取最新，无需重登。
        // 若用户已有未保存的本地改动（换过头像 / 改过昵称或性别），跳过回填以免覆盖草稿
        ProfileSync.sync(this, changed -> {
            if (!changed || isFinishing()) return;
            boolean avatarPending = !avatarPath.equals(originalAvatarPath);
            boolean nicknamePending = !nicknameInput.equals(sp.getString("nickname", ""));
            boolean genderPending = currentGender != sp.getInt("gender", 0);
            if (!avatarPending && !nicknamePending && !genderPending) loadUserData();
        });

        // 头像行：点击更换头像
        findViewById(R.id.btn_avatar).setOnClickListener(v -> showAvatarSheet());
        // 用户名行：点击编辑昵称
        findViewById(R.id.btn_username).setOnClickListener(v -> showEditNicknameDialog());
        // 性别行
        findViewById(R.id.btn_gender).setOnClickListener(v -> showGenderDialog());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copyUid());
        // 邮箱行：进入邮箱绑定
        findViewById(R.id.btn_email).setOnClickListener(v ->
                startActivity(new Intent(ProfileActivity.this, EmailBindingActivity.class)));
        // 修改密码
        findViewById(R.id.btn_change_password).setOnClickListener(v ->
                startActivity(new Intent(ProfileActivity.this, ChangePasswordActivity.class)));
        // 退出登录
        findViewById(R.id.btn_logout).setOnClickListener(v -> doLogout());
        // 注销账号
        findViewById(R.id.btn_cancel_account).setOnClickListener(v -> showCancelAccountDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从邮箱绑定 / 修改密码返回时，刷新邮箱与密码修改时间
        updateEmailDisplay();
        updatePwdHint();
    }

    private void loadUserData() {
        nicknameInput = sp.getString("nickname", "");
        currentGender = sp.getInt("gender", 0);
        String userIdStr = sp.getString("userId", "");
        avatarPath = sp.getString("avatar", "");
        originalAvatarPath = avatarPath;

        tvUsername.setText(nicknameInput);
        updateGenderDisplay();
        tvUid.setText(userIdStr.isEmpty() ? "未登录" : userIdStr);
        updateEmailDisplay();
        updatePwdHint();
        loadAvatar();
    }

    private void updateEmailDisplay() {
        String email = sp.getString("email", "");
        tvEmail.setText(email.isEmpty() ? getString(R.string.unbound_tip) : maskEmail(email));
        tvEmail.setTextColor(email.isEmpty() ? 0xFF8E8E93 : 0xFF1D1D1F); // 次文字 / 主文字
    }

    private void updatePwdHint() {
        long ts = sp.getLong("lastPwdChangeTs", 0);
        if (ts <= 0) {
            tvPwdHint.setVisibility(View.GONE);
            return;
        }
        long diff = System.currentTimeMillis() - ts;
        long days = diff / (24L * 3600 * 1000);
        String text;
        if (days <= 0) {
            text = "上次修改今天";
        } else if (days < 30) {
            text = "上次修改 " + days + " 天前";
        } else if (days < 365) {
            text = "上次修改 " + (days / 30) + " 个月前";
        } else {
            text = "上次修改 " + (days / 365) + " 年前";
        }
        tvPwdHint.setText(text);
        tvPwdHint.setVisibility(View.VISIBLE);
    }

    private String maskEmail(String email) {
        if (email.contains("@")) {
            String[] parts = email.split("@");
            String name = parts[0];
            if (name.length() > 3) {
                return name.substring(0, 3) + "***@" + parts[1];
            }
        }
        return email;
    }

    /**
     * 将 Bitmap 设置为圆形头像
     */
    private void setCircularAvatar(Bitmap bitmap) {
        if (bitmap == null) return;
        RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(getResources(), bitmap);
        rounded.setCircular(true);
        ivAvatar.setImageDrawable(rounded);
    }

    /**
     * 将 Drawable 资源解码为 Bitmap
     */
    private Bitmap decodeDrawableToBitmap(int resId) {
        Drawable drawable = getResources().getDrawable(resId, null);
        if (drawable == null) return null;
        int w = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 200;
        int h = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 200;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private void loadAvatar() {
        if (!avatarPath.isEmpty()) {
            File avatarFile = new File(avatarPath);
            if (avatarFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(avatarPath);
                setCircularAvatar(bitmap);
                return;
            }
        }
        setCircularAvatar(decodeDrawableToBitmap(R.drawable.default_avatar));
    }

    private void updateGenderDisplay() {
        String genderText;
        switch (currentGender) {
            case 1:
                genderText = "男";
                break;
            case 2:
                genderText = "女";
                break;
            default:
                genderText = "保密";
                break;
        }
        tvGender.setText(genderText);
    }

    private void showAvatarSheet() {
        Dialog sheet = new Dialog(this, R.style.AvatarSheetStyle);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_avatar_options, null);
        sheet.setContentView(view);
        sheet.setCancelable(true);
        sheet.setCanceledOnTouchOutside(true);
        Window window = sheet.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        view.findViewById(R.id.action_camera).setOnClickListener(v -> {
            sheet.dismiss();
            takePhoto();
        });
        view.findViewById(R.id.action_album).setOnClickListener(v -> {
            sheet.dismiss();
            pickImageFromGallery();
        });
        view.findViewById(R.id.action_default).setOnClickListener(v -> {
            sheet.dismiss();
            useDefaultAvatar();
        });
        view.findViewById(R.id.action_cancel).setOnClickListener(v -> sheet.dismiss());

        sheet.setOnDismissListener(dialog -> clearPageRecede());
        sheet.show();
        // 弹窗出现后，让底层那一页模糊 + 轻微缩小，真正"退到后面去"
        applyPageRecede();
    }

    /**
     * 让底层页面"退到后面"：模糊 + 轻微缩小。
     * 模糊依赖 RenderEffect（API 31+），低版本仅做缩小 + 遮罩压暗降级。
     */
    private void applyPageRecede() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        root.animate()
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            root.setRenderEffect(RenderEffect.createBlurEffect(12f, 12f, Shader.TileMode.CLAMP));
        }
    }

    private void clearPageRecede() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        root.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            root.setRenderEffect(null);
        }
    }

    private void useDefaultAvatar() {
        setCircularAvatar(decodeDrawableToBitmap(R.drawable.default_avatar));
        avatarPath = "";
    }

    /**
     * 国风弹窗的公共窗口参数 —— 与 utils/LoginHelper 的登录提示弹窗同族
     * （窗口 312dp 宽 = 卡片 280dp + 左右各 16dp 投影，见 dialog_edit_nickname.xml 头部注释）。
     * 改弹窗外观只改布局与 drawable，这里不动。
     */
    private Dialog createGuofengDialog(View root) {
        Dialog dialog = new Dialog(this, R.style.LoginPromptDialogStyle);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.CENTER);
            float density = getResources().getDisplayMetrics().density;
            window.setLayout((int) (312 * density), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    /**
     * 修改用户名弹窗（国风版）：宣纸卡 + 描金输入框 + 字数计数，空值时「确定」不可点。
     * 布局见 layout/dialog_edit_nickname.xml。
     */
    private void showEditNicknameDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_edit_nickname, null);
        Dialog dialog = createGuofengDialog(root);

        EditText input = root.findViewById(R.id.nick_input);
        TextView counter = root.findViewById(R.id.nick_count);
        TextView clear = root.findViewById(R.id.nick_clear);
        View field = root.findViewById(R.id.nick_field);
        View btnOk = root.findViewById(R.id.btn_nick_ok);

        // 上限 12（与效果图一致，且远小于服务端 50 的上限）；
        // 历史昵称更长时按现有长度放宽，避免一打开就被过滤器截断。
        final int maxLen = Math.max(NICKNAME_MAX_LENGTH,
                nicknameInput == null ? 0 : nicknameInput.length());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        input.setText(nicknameInput);
        input.setSelection(input.getText().length());

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String value = input.getText() == null ? "" : input.getText().toString();
            counter.setText(value.length() + " / " + maxLen);
            counter.setTextColor(ContextCompat.getColor(this,
                    value.length() >= maxLen ? R.color.gf_seal : R.color.gf_muted));
            boolean valid = !value.trim().isEmpty();
            btnOk.setEnabled(valid);
            btnOk.setAlpha(valid ? 1f : 0.45f);
            clear.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        };

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (refresh[0] != null) refresh[0].run();
            }
        });
        // 聚焦时整块换描边色（底图挂在 nick_field 上，不是 EditText 上）
        input.setOnFocusChangeListener((v, hasFocus) -> field.setBackgroundResource(
                hasFocus ? R.drawable.bg_gf_field_focus : R.drawable.bg_gf_field));

        clear.setOnClickListener(v -> {
            input.setText("");
            input.requestFocus();
        });
        root.findViewById(R.id.btn_nick_cancel).setOnClickListener(v -> dialog.dismiss());
        btnOk.setOnClickListener(v -> {
            nicknameInput = input.getText().toString().trim();
            tvUsername.setText(nicknameInput);
            dialog.dismiss();
        });

        refresh[0].run();
        dialog.show();
        // 编辑框场景：弹窗一出就聚焦并弹出键盘
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private void copyUid() {
        String uid = tvUid.getText().toString().trim();
        if (uid.isEmpty() || "未登录".equals(uid)) {
            Hint.show(this, "暂无 UID 可复制");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("UID", uid));
        }
        Hint.show(this, getString(R.string.copied));
    }

    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        launchCamera();
    }

    private void launchCamera() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) {
            Hint.show(this, "无法访问存储，请从相册选择");
            pickImageFromGallery();
            return;
        }
        File photoFile = new File(dir, "avatar_" + System.currentTimeMillis() + ".jpg");
        pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Hint.show(this, "未检测到相机，请从相册选择");
            pendingCameraUri = null;
            pickImageFromGallery();
            return;
        }
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_CROP_AVATAR = 1002;
    private static final int REQUEST_CAPTURE = 1003;
    private static final int REQUEST_CAMERA_PERMISSION = 1004;

    /** 昵称在弹窗里的输入上限（与效果图一致；服务端上限 50，取更小值以避免超长昵称）。 */
    private static final int NICKNAME_MAX_LENGTH = 12;

    private Uri pendingImageUri;
    private Uri pendingCameraUri;

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                Hint.show(this, "需要相机权限才能拍照");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK) {
            // 拍照完成，照片已写入 pendingCameraUri 指向的文件，跳转到裁剪页
            Uri uri = pendingCameraUri;
            pendingCameraUri = null;
            if (uri != null) {
                Intent cropIntent = new Intent(this, CropAvatarActivity.class);
                cropIntent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, uri);
                startActivityForResult(cropIntent, REQUEST_CROP_AVATAR);
            } else {
                pickImageFromGallery();
            }
            return;
        }

        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            // 选图成功，跳转到裁剪页
            Uri imageUri = data.getData();
            if (imageUri != null) {
                Intent cropIntent = new Intent(this, CropAvatarActivity.class);
                cropIntent.putExtra(CropAvatarActivity.EXTRA_IMAGE_URI, imageUri);
                startActivityForResult(cropIntent, REQUEST_CROP_AVATAR);
            }
        }

        if (requestCode == REQUEST_CROP_AVATAR && resultCode == RESULT_OK && data != null) {
            // 裁剪完成，获取裁剪后的头像路径
            avatarPath = data.getStringExtra(CropAvatarActivity.EXTRA_AVATAR_PATH);
            if (avatarPath != null && !avatarPath.isEmpty()) {
                Bitmap bitmap = BitmapFactory.decodeFile(avatarPath);
                setCircularAvatar(bitmap);
            }
        }
    }

    /**
     * 选择性别弹窗（国风版）：「隐 / 乾 / 坤」印章选项 + 卡片选中态，
     * 与用户名弹窗同构 —— 选中后需点「确定」才生效，点「取消」放弃本次选择。
     * 布局见 layout/dialog_choose_gender.xml。
     */
    private void showGenderDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_choose_gender, null);
        Dialog dialog = createGuofengDialog(root);

        final int[] optIds = {R.id.gender_opt_0, R.id.gender_opt_1, R.id.gender_opt_2};
        final int[] stampIds = {R.id.gender_stamp_0, R.id.gender_stamp_1, R.id.gender_stamp_2};
        final int[] flagIds = {R.id.gender_flag_0, R.id.gender_flag_1, R.id.gender_flag_2};
        final int[] radioIds = {R.id.gender_radio_0, R.id.gender_radio_1, R.id.gender_radio_2};

        // 弹窗内的临时选择：确认后才写回 currentGender，取消即丢弃
        final int[] selected = {currentGender};

        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            for (int i = 0; i < optIds.length; i++) {
                boolean on = selected[0] == i;
                root.findViewById(optIds[i]).setBackgroundResource(
                        on ? R.drawable.bg_gf_option_selected : R.drawable.bg_gf_option);
                TextView stamp = root.findViewById(stampIds[i]);
                stamp.setBackgroundResource(on ? R.drawable.bg_gf_stamp_selected : R.drawable.bg_gf_stamp);
                stamp.setTextColor(ContextCompat.getColor(this,
                        on ? R.color.gf_teal : R.color.gf_gold));
                // 「当前」角标跟随弹窗内的选择，不只靠颜色区分状态
                root.findViewById(flagIds[i]).setVisibility(on ? View.VISIBLE : View.GONE);
                ((ImageView) root.findViewById(radioIds[i])).setImageResource(
                        on ? R.drawable.bg_gf_radio_on : R.drawable.bg_gf_radio_off);
            }
        };

        for (int i = 0; i < optIds.length; i++) {
            final int index = i;
            root.findViewById(optIds[i]).setOnClickListener(v -> {
                selected[0] = index;
                if (paint[0] != null) paint[0].run();
            });
        }

        root.findViewById(R.id.btn_gender_cancel).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_gender_ok).setOnClickListener(v -> {
            currentGender = selected[0];
            updateGenderDisplay();
            dialog.dismiss();
        });

        paint[0].run();
        dialog.show();
    }

    /**
     * 导航栏「完成」：保存资料（昵称 / 性别 / 头像），并同步到后端（已登录时）。
     */
    private void saveProfile() {
        if (btnSave != null) btnSave.setEnabled(false);
        if (nicknameInput.length() > 50) {
            Hint.show(this, "昵称最长 50 个字符");
            if (btnSave != null) btnSave.setEnabled(true);
            return;
        }

        // 1) 先落本地：改完立刻回显，不依赖网络是否通畅
        sp.edit()
                .putString("nickname", nicknameInput)
                .putInt("gender", currentGender)
                .putString("avatar", avatarPath)
                .apply();

        // 2) 未登录：维持历史行为，只存本机
        if (!LoginHelper.isLoggedIn(this)) {
            Hint.show(this, "已保存到本机，登录后可同步到账号");
            if (btnSave != null) btnSave.setEnabled(true);
            return;
        }

        syncProfileToServer(nicknameInput);
    }

    /**
     * 把资料同步到后端（{@code PUT /api/user/profile}）。
     */
    private void syncProfileToServer(String nickname) {
        boolean avatarChanged = !avatarPath.equals(originalAvatarPath);
        if (avatarChanged && !avatarPath.isEmpty()) {
            uploadAvatarThenSave(nickname);
        } else {
            // 没换头像 / 清空了头像（空串表示恢复默认）
            submitProfilePatch(nickname, avatarChanged ? "" : null);
        }
    }

    private void uploadAvatarThenSave(String nickname) {
        File avatarFile = new File(avatarPath);
        if (!avatarFile.exists()) {
            // 本地文件已丢失，就别把它当成「换头像」了
            avatarPath = originalAvatarPath;
            submitProfilePatch(nickname, null);
            return;
        }
        if (btnSave != null) btnSave.setEnabled(false);
        RequestBody fileBody = RequestBody.create(MediaType.parse("image/*"), avatarFile);
        MultipartBody.Part part =
                MultipartBody.Part.createFormData("file", avatarFile.getName(), fileBody);

        RetrofitClient.getApiService().uploadAvatar(part)
                .enqueue(new Callback<ApiResponse<Map<String, Object>>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<Map<String, Object>>> call,
                                           @NonNull Response<ApiResponse<Map<String, Object>>> response) {
                        ApiResponse<Map<String, Object>> body = response.body();
                        if (body != null && body.isSuccess() && body.getData() != null) {
                            Object url = body.getData().get("avatar");
                            final String serverAvatar = url == null ? null : String.valueOf(url);
                            // 把服务器返回的相对地址下载到本地缓存：SP 里始终是可直接 decode 的本地路径，
                            // 与显示层模型一致；同时后台下载，失败也不影响服务器已存的新头像。
                            if (serverAvatar != null && !serverAvatar.isEmpty()) {
                                final String finalNick = nickname;
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        final File f = AvatarCache.download(ProfileActivity.this, serverAvatar);
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (isFinishing()) return;
                                                if (f != null) {
                                                    avatarPath = f.getAbsolutePath();
                                                    // avatar_server 同步更新，避免下次 ProfileSync 误判为「服务端又变了」
                                                    sp.edit()
                                                            .putString("avatar", avatarPath)
                                                            .putString("avatar_server", serverAvatar)
                                                            .apply();
                                                }
                                                submitProfilePatch(finalNick, serverAvatar);
                                            }
                                        });
                                    }
                                }).start();
                            } else {
                                submitProfilePatch(nickname, null);
                            }
                        } else {
                            finishWithFailure(body != null ? body.getMessage() : "头像上传失败");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<Map<String, Object>>> call,
                                          @NonNull Throwable t) {
                        finishWithFailure("头像上传失败：" + t.getMessage());
                    }
                });
    }

    /**
     * @param nickname    新昵称
     * @param serverAvatar 服务器头像路径；null 表示不改头像，空串表示恢复默认
     */
    private void submitProfilePatch(String nickname, String serverAvatar) {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setNickname(nickname);
        request.setGender(currentGender);
        request.setAvatar(serverAvatar);

        if (btnSave != null) btnSave.setEnabled(false);
        RetrofitClient.getApiService().updateProfile(request)
                .enqueue(new Callback<ApiResponse<Map<String, Object>>>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse<Map<String, Object>>> call,
                                           @NonNull Response<ApiResponse<Map<String, Object>>> response) {
                        ApiResponse<Map<String, Object>> body = response.body();
                        if (body != null && body.isSuccess()) {
                            // 本次提交若带上了头像变更（含清空 → 空串），同步更新 avatar_server 标记，
                            // 使本地标记与服务端状态一致，ProfileSync 不会把刚存的头像再拉一遍
                            if (serverAvatar != null) {
                                sp.edit().putString("avatar_server", serverAvatar).apply();
                            }
                            Hint.show(ProfileActivity.this, "保存成功");
                            // 资料已落本地，回到上一页
                            finish();
                        } else {
                            finishWithFailure(body != null ? body.getMessage() : "云端同步失败");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse<Map<String, Object>>> call,
                                          @NonNull Throwable t) {
                        finishWithFailure("云端同步失败：" + t.getMessage());
                    }
                });
    }

    /** 同步失败：本地已经存好了，所以只提示、不撤销，并把按钮恢复可点供用户重试 */
    private void finishWithFailure(String message) {
        if (isFinishing()) return;
        if (btnSave != null) btnSave.setEnabled(true);
        Hint.showLong(this, "已保存到本机，" + message + "（可再点一次完成重试）");
    }

    /**
     * 退出登录：复用设置页的登出逻辑（先尽力把外站数据同步到服务器，再清理本地登录态，
     * 不跳转登录页——本项目支持游客浏览）。完成后直接关闭本页返回设置页。
     */
    private void doLogout() {
        if (LoginHelper.isLoggedIn(this)) {
            ExternalSyncManager.getInstance(this).flushAll();
        }
        ExternalSyncManager.getInstance(this).stopPeriodic();
        getSharedPreferences("user_info", MODE_PRIVATE).edit().clear().apply();
        Hint.show(this, "已退出登录");
        finish();
    }

    /**
     * 注销账号：先二次确认（国风探出式弹窗，与登录提示弹窗同语言），已登录则先调后端注销
     * （级联删除全部私有数据），成功后再清本地登录态并跳登录页（清空任务栈）；
     * 未登录（游客）没有服务端账号可注销，仅清本地态。
     * 后端返回失败或网络异常时<b>不清本地</b>，留在页面让用户重试，避免误删本地态。
     */
    private void showCancelAccountDialog() {
        View root = LayoutInflater.from(this).inflate(R.layout.dialog_cancel_account, null);

        Dialog dialog = new Dialog(this, R.style.LoginPromptDialogStyle);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.CENTER);
            // inflate(layout, null) 丢根布局 layout_*，窗口宽度必须显式给：
            // 312 = 卡片 280dp + 左右各 16dp（留卡片投影），同 LoginHelper.showLoginPrompt
            float density = getResources().getDisplayMetrics().density;
            window.setLayout((int) (312 * density), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setCanceledOnTouchOutside(true);

        // 「所有数据将被永久删除」朱砂加粗强调（与效果图一致）
        TextView line1 = root.findViewById(R.id.tv_cancel_account_line1);
        if (line1 != null) {
            String full = "注销后，您的所有数据将被永久删除";
            SpannableString span = new SpannableString(full);
            int start = full.indexOf('所');
            if (start >= 0) {
                span.setSpan(new StyleSpan(Typeface.BOLD), start, full.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                span.setSpan(new ForegroundColorSpan(0xFFA85B4A), start, full.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            line1.setText(span);
        }

        root.findViewById(R.id.btn_cancel_account_cancel)
                .setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btn_cancel_account_confirm).setOnClickListener(v -> {
            dialog.dismiss();
            doCancelAccount();
        });

        dialog.show();
    }

    /** 执行注销：未登录直接清本地；已登录先调后端，成功再清本地 */
    private void doCancelAccount() {
        if (!LoginHelper.isLoggedIn(this)) {
            clearLocalAndGotoLogin();
            return;
        }

        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在注销...");
        progress.setCancelable(false);
        progress.show();

        RetrofitClient.getApiService().cancelAccount()
                .enqueue(new Callback<ApiResponse<Void>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                        progress.dismiss();
                        ApiResponse<Void> body = response.body();
                        if (response.isSuccessful() && body != null && body.isSuccess()) {
                            Hint.show(ProfileActivity.this, "账号已注销");
                            clearLocalAndGotoLogin();
                            return;
                        }
                        // 登录态已失效（token 过期/无效，HTTP 401）：本地态一并清掉，跳登录页重登
                        if (!response.isSuccessful() && response.code() == 401) {
                            Hint.show(ProfileActivity.this, "登录已失效，请重新登录");
                            clearLocalAndGotoLogin();
                            return;
                        }
                        String msg = (body != null && body.getMessage() != null)
                                ? body.getMessage() : "注销失败，请稍后重试";
                        Hint.showLong(ProfileActivity.this, msg);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                        progress.dismiss();
                        Hint.showLong(ProfileActivity.this, "注销失败：" + t.getMessage());
                    }
                });
    }

    /** 清本地登录态并跳登录页（清空任务栈）。注销后服务端已删全部私数据，无需 flush 本地 → 服务器 */
    private void clearLocalAndGotoLogin() {
        ExternalSyncManager.getInstance(this).stopPeriodic();
        getSharedPreferences("user_info", MODE_PRIVATE).edit().clear().apply();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
