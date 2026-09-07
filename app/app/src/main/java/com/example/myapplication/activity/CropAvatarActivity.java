package com.example.myapplication.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import com.example.myapplication.R;
import com.example.myapplication.view.CropCircleView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 头像裁剪页面。
 * 接收图片 URI，用户拖动/缩放调整裁剪区域后确认，
 * 将裁剪结果保存为文件并通过 Intent 返回文件路径。
 */
public class CropAvatarActivity extends BaseActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_AVATAR_PATH = "avatar_path";

    private CropCircleView cropView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop_avatar);

        cropView = findViewById(R.id.crop_view);

        // 加载传入的图片
        Uri imageUri = getIntent().getParcelableExtra(EXTRA_IMAGE_URI);
        if (imageUri == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        try {
            InputStream is = getContentResolver().openInputStream(imageUri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, options);
            if (is != null) is.close();

            // 大图采样压缩，避免 OOM
            options.inSampleSize = calculateInSampleSize(options, 1080, 1080);
            options.inJustDecodeBounds = false;

            is = getContentResolver().openInputStream(imageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
            if (is != null) is.close();

            if (bitmap == null) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
            cropView.setBitmap(bitmap);
        } catch (Exception e) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        // 取消
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        // 确认裁剪
        findViewById(R.id.btn_confirm).setOnClickListener(v -> {
            Bitmap cropped = cropView.getCroppedBitmap();
            if (cropped == null) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }

            // 保存到本地
            try {
                File avatarDir = new File(getFilesDir(), "avatars");
                if (!avatarDir.exists()) avatarDir.mkdirs();

                // 用时间戳命名避免缓存问题
                File avatarFile = new File(avatarDir,
                        "avatar_cropped_" + System.currentTimeMillis() + ".jpg");
                FileOutputStream fos = new FileOutputStream(avatarFile);
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
                fos.close();

                Intent result = new Intent();
                result.putExtra(EXTRA_AVATAR_PATH, avatarFile.getAbsolutePath());
                setResult(RESULT_OK, result);
            } catch (Exception e) {
                setResult(RESULT_CANCELED);
            } finally {
                cropped.recycle();
            }
            finish();
        });
    }

    /**
     * 计算采样率，将大图缩小到指定尺寸以内
     */
    private int calculateInSampleSize(BitmapFactory.Options options, int reqW, int reqH) {
        int w = options.outWidth;
        int h = options.outHeight;
        int inSampleSize = 1;
        while (w / inSampleSize > reqW || h / inSampleSize > reqH) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }
}