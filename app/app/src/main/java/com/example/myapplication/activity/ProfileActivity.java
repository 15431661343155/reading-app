package com.example.myapplication.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.example.myapplication.R;
import com.example.myapplication.utils.ThemeManager;

import java.io.File;

public class ProfileActivity extends BaseActivity {

    private ImageView ivAvatar;
    private EditText etNickname;
    private TextView tvGender;
    private TextView tvUid;
    private Button btnSave;

    private SharedPreferences sp;
    private int currentGender = 0; // 0=保密, 1=男, 2=女
    private String avatarPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        Toolbar toolbar = findViewById(R.id.toolbar_back);
        // extendToolbarToStatusBar removed: setDecorFits(true) handles system bar spacing

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

        sp = getSharedPreferences("user_info", MODE_PRIVATE);

        ivAvatar = findViewById(R.id.iv_avatar);
        etNickname = findViewById(R.id.et_nickname);
        tvGender = findViewById(R.id.tv_gender);
        tvUid = findViewById(R.id.tv_uid);
        btnSave = findViewById(R.id.btn_save);

        loadUserData();

        // 点击头像更换
        ivAvatar.setOnClickListener(v -> showAvatarOptions());

        // 点击性别选择
        findViewById(R.id.btn_gender).setOnClickListener(v -> showGenderDialog());

        // 保存按钮
        btnSave.setOnClickListener(v -> saveProfile());
    }

    private void loadUserData() {
        String nickname = sp.getString("nickname", "");
        currentGender = sp.getInt("gender", 0);
        String userIdStr = sp.getString("userId", "");
        avatarPath = sp.getString("avatar", "");

        etNickname.setText(nickname);
        updateGenderDisplay();
        tvUid.setText(userIdStr);

        // 加载头像（统一裁剪为圆形）
        if (!avatarPath.isEmpty()) {
            File avatarFile = new File(avatarPath);
            if (avatarFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(avatarPath);
                setCircularAvatar(bitmap);
                return;
            }
        }
        // 默认头像
        Bitmap defaultBitmap = decodeDrawableToBitmap(R.drawable.ic_detective);
        setCircularAvatar(defaultBitmap);
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

    private void showAvatarOptions() {
        String[] options = {"从相册选择", "使用默认头像"};
        new AlertDialog.Builder(this)
                .setTitle("更换头像")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // 从相册选择
                        pickImageFromGallery();
                    } else {
                        // 使用默认头像
                        setCircularAvatar(decodeDrawableToBitmap(R.drawable.ic_detective));
                        avatarPath = "";
                    }
                })
                .show();
    }

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_CROP_AVATAR = 1002;

    private Uri pendingImageUri;

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

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

    private void showGenderDialog() {
        String[] genders = {"保密", "男", "女"};
        new AlertDialog.Builder(this)
                .setTitle("选择性别")
                .setSingleChoiceItems(genders, currentGender, (dialog, which) -> {
                    currentGender = which;
                    updateGenderDisplay();
                    dialog.dismiss();
                })
                .show();
    }

    private void saveProfile() {
        String nickname = etNickname.getText().toString().trim();

        sp.edit()
                .putString("nickname", nickname)
                .putInt("gender", currentGender)
                .putString("avatar", avatarPath)
                .apply();

        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
        finish();
    }
}