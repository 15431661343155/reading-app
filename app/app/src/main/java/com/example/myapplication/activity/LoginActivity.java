package com.example.myapplication.activity;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import java.io.File;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.myapplication.widget.MorphSubmitButton;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.R;
import com.example.myapplication.api.RetrofitClient;
import com.example.myapplication.api.SafeCallback;
import com.example.myapplication.bean.CodeLoginRequest;
import com.example.myapplication.bean.LoginRequest;
import com.example.myapplication.bean.LoginResponse;
import com.example.myapplication.bean.SendCodeRequest;
import com.example.myapplication.utils.DocLinks;
import com.example.myapplication.utils.AvatarCache;
import com.example.myapplication.utils.ExternalPrefs;
import com.example.myapplication.utils.ExternalSyncManager;
import com.example.myapplication.utils.Hint;

/**
 * 登录页：背景图 + 毛玻璃卡片，支持「密码登录」与「邮箱验证码登录」两种方式。
 *
 * <p>验证码登录为两步式：第一步填邮箱（校验协议勾选后发送验证码），第二步输入 6 位验证码。
 * 验证码模式下未注册邮箱首次验证通过会自动创建账号，因此不再单独提供「邮箱注册页」，
 * 密码态的「还没有账号？注册」直接切到验证码登录。
 */
public class LoginActivity extends BaseActivity {

    /** 从某功能入口跳转登录时带此 extra=true，登录成功后直接返回原页面而非主页。 */
    public static final String EXTRA_RETURN_TO_CALLER = "return_to_caller";

    /** 登录方式：验证码登录 */
    private static final int MODE_CODE = 0;
    /** 登录方式：密码登录 */
    private static final int MODE_PASSWORD = 1;

    /** 验证码流程：第一步，填写邮箱 */
    private static final int STEP_EMAIL = 1;
    /** 验证码流程：第二步，输入验证码 */
    private static final int STEP_CODE = 2;

    /** 协议文档与 Web 端共用同一份，协议更新即时生效（常量与跳转见 DocLinks） */

    private static final long COUNTDOWN_MS = 60_000L;

    /** 验证码格的个数 */
    private static final int CODE_LENGTH = 6;

    /** 浮动标签浮起时相对输入行的上移距离（dp） */
    private static final float LABEL_RISE_DP = 20f;
    /** 浮动标签浮起后的缩放比（15sp → 12sp） */
    private static final float LABEL_SCALE = 0.8f;
    /** 浮动标签浮起/落下的动画时长 */
    private static final long LABEL_ANIM_MS = 160L;

    /**
     * 密码态的卡片上方留白（dp）—— 指 spacer_top 自身的高度，不含顶部导航栏。
     *
     * <p>屏幕上卡片上方总留白恒为 {@link #CARD_TOP_SPACE_TOTAL_DP}：
     * 导航栏 bar_login 占 {@link #NAV_BAR_FOOTPRINT_DP}，余下的才是 spacer_top。
     * 卡片位置只能守不能推 —— 实测卡底已超出屏底约 102px（见 activity_login.xml 注释）。
     */
    private static final float CARD_TOP_SPACE_DP = 144f;
    /** 卡片上方留白总量（导航栏 + spacer 之和，dp）；改导航栏高度时要同步调 spacer 常量 */
    private static final float CARD_TOP_SPACE_TOTAL_DP = 214f;
    /** 导航栏占的垂直尺寸（dp）：marginTop 8 + 高 52 + marginBottom 10 */
    private static final float NAV_BAR_FOOTPRINT_DP = 70f;
    /**
     * 验证码态相对密码态再下移的距离（dp）。
     *
     * <p>验证码态卡片只有密码态的三分之二高，两者上缘对齐时卡片下方会多出几百像素空白，
     * 观感是「卡片浮在顶上、下面空一片」。下移这一档后卡片上下留白接近平衡。
     * 两种验证码步骤共用同一位移，步骤之间切换时卡片上缘保持不动。
     */
    private static final float CARD_TOP_SPACE_EXTRA_CODE_DP = 30f;

    // ---------- 视图 ----------
    private TextView tvHint, tvCodeSent, tvCountdown, tvRegister, tvSwitch, tvBack;
    private LinearLayout grpPassword, grpCodeInput, rowConsent;
    private EditText etAccount, etPassword, etCodeEmail;
    private final EditText[] codeTiles = new EditText[CODE_LENGTH];
    private ImageView ivEye;
    /** 验证码第一步的邮箱框容器：整块显隐（输入框外套了浮动标签，不能只切 EditText） */
    private FrameLayout boxCodeEmail;
    /** 浮动标签：未聚焦且为空时停在输入行当占位，聚焦或有内容时上移缩小并转主色紫 */
    private TextView labelAccount, labelPassword, labelCodeEmail;
    /** 输入行占位文字：标签浮起后才淡入，提示可输入什么 */
    private TextView phAccount, phPassword, phCodeEmail;
    private MorphSubmitButton btnLogin;
    /** 协议勾选态图标（用 ImageView 而非 CheckBox，避开主题 buttonTint 把选中图重涂成主题色） */
    private ImageView ivConsent;
    /** 协议整句（含两个可点链接），必须是一个 TextView，见 setupConsentText() */
    private TextView tvConsent;
    /** 卡片上方留白：高度按登录形态设置，让两种形态的卡片垂直位置略有差异 */
    private View spacerTop;

    private SharedPreferences sp;

    // ---------- 状态 ----------
    private int mode = MODE_CODE;
    private int codeStep = STEP_EMAIL;
    private boolean consentChecked = false;
    private boolean sendingCode = false;
    private boolean passwordVisible = false;
    /** 验证码实际发往的邮箱：登录校验用它，避免用户改了输入框却用旧验证码提交 */
    private String sendingEmail = "";
    private CountDownTimer countDownTimer;
    /** 遮罩上次应用的画布高度与卡片上缘比例，两者都没变时跳过重建背景 */
    private int lastMaskHeight = -1;
    private float lastMaskRatio = Float.NaN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // 沉浸式：背景图延伸至状态栏，状态栏图标用深色（背景上部为浅色画面）
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setLightStatusBar(true);

        initView();
        applyWindowInsets();
        setupMask();

        sp = getSharedPreferences("user_info", MODE_PRIVATE);
        String savedToken = sp.getString("token", "");
        if (!savedToken.isEmpty()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        bindEvents();
        applyState();
    }

    private void initView() {
        tvHint = findViewById(R.id.tv_login_hint);
        grpPassword = findViewById(R.id.grp_password);
        grpCodeInput = findViewById(R.id.grp_code_input);
        etAccount = findViewById(R.id.et_account);
        etPassword = findViewById(R.id.et_password);
        ivEye = findViewById(R.id.iv_password_eye);
        etCodeEmail = findViewById(R.id.et_code_email);
        boxCodeEmail = findViewById(R.id.box_code_email);
        labelAccount = findViewById(R.id.label_account);
        labelPassword = findViewById(R.id.label_password);
        labelCodeEmail = findViewById(R.id.label_code_email);
        phAccount = findViewById(R.id.ph_account);
        phPassword = findViewById(R.id.ph_password);
        phCodeEmail = findViewById(R.id.ph_code_email);
        tvCodeSent = findViewById(R.id.tv_code_sent);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvRegister = findViewById(R.id.tv_register);
        rowConsent = findViewById(R.id.row_consent);
        ivConsent = findViewById(R.id.iv_consent);
        tvSwitch = findViewById(R.id.tv_switch);
        tvBack = findViewById(R.id.tv_back);
        btnLogin = findViewById(R.id.btn_login);
        // 形变按钮空闲/加载态沿用登录页主题紫，成功转黄、失败转红由控件内部处理
        btnLogin.setIdleColor(getColor(R.color.login_accent));
        spacerTop = findViewById(R.id.spacer_top);

        codeTiles[0] = findViewById(R.id.et_code_1);
        codeTiles[1] = findViewById(R.id.et_code_2);
        codeTiles[2] = findViewById(R.id.et_code_3);
        codeTiles[3] = findViewById(R.id.et_code_4);
        codeTiles[4] = findViewById(R.id.et_code_5);
        codeTiles[5] = findViewById(R.id.et_code_6);
    }

    /** 状态栏/导航栏内边距交给滚动容器，内容滚动时不会钻进状态栏下面。 */
    private void applyWindowInsets() {
        View scroll = findViewById(R.id.sv_login);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), bars.bottom);
            return insets;
        });
    }

    /**
     * 白色渐变遮罩：卡片上方完整透出背景画面，卡片区域柔和转白（玻璃质感来源），底部纯白。
     *
     * <p>XML 的 gradient 只支持三个色标，这里按真实高度用多点色标精确还原设计稿曲线。
     * 色标位置以「卡片上缘」为基准推算，并挂在布局回调上：改动卡片上方的留白高度、
     * 或状态栏内边距变化时过渡带都会自动跟随，不会出现「卡片下移了、渐变还停在原处」的错位。
     */
    private void setupMask() {
        View mask = findViewById(R.id.v_login_mask);
        View card = findViewById(R.id.card_login);
        View scroll = findViewById(R.id.sv_login);

        View.OnLayoutChangeListener recompute = (v, l, t, r, b, ol, ot, or, ob) ->
                applyMaskGradient(mask, card, scroll);
        mask.addOnLayoutChangeListener(recompute);
        card.addOnLayoutChangeListener(recompute);
        scroll.addOnLayoutChangeListener(recompute);
        // 兜底：若注册时布局已经完成（不会再有 layout 回调），下一帧主动补算一次
        mask.post(() -> applyMaskGradient(mask, card, scroll));
    }

    /**
     * 用卡片上缘的实际位置重算遮罩色标，保证过渡带始终贴着卡片上缘。
     *
     * <p>色标相对卡片上缘的偏移沿用设计稿曲线：起点在上缘之上 3.3%，透明段在上缘处结束，
     * 其后 11.7% / 28.7% / 45.7% 三处分别过渡到白 55% / 90% / 100%。
     */
    private void applyMaskGradient(View mask, View card, View scroll) {
        int h = mask.getHeight();
        if (h <= 0 || card.getHeight() <= 0) {
            return;
        }
        // 卡片在屏幕坐标系里的上缘：ScrollView 顶部 + 其内边距（含状态栏 inset）+ 内容偏移 - 滚动量
        float cardTop = scroll.getTop() + scroll.getPaddingTop()
                + card.getTop() - scroll.getScrollY();
        float ratio = cardTop / h;

        // 位置没变就不重建背景，避免在布局回调里反复 setBackground
        if (h == lastMaskHeight && Math.abs(ratio - lastMaskRatio) < 0.0005f) {
            return;
        }
        lastMaskHeight = h;
        lastMaskRatio = ratio;

        float[] stops = {0f, ratio - 0.033f, ratio + 0.117f, ratio + 0.287f, ratio + 0.457f, 1f};
        // 归一化到 [0,1] 且严格递增：LinearGradient 要求色标单调
        stops[stops.length - 1] = 1f;
        for (int i = 1; i < stops.length - 1; i++) {
            float upper = 1f - 0.004f * (stops.length - 1 - i);
            stops[i] = Math.min(Math.max(stops[i], stops[i - 1] + 0.004f), upper);
        }

        LinearGradient gradient = new LinearGradient(
                0f, 0f, 0f, h,
                new int[]{
                        0x00FFFFFF, // 完全透明
                        0x00FFFFFF, // 仍然透明，背景画面一直完整露到卡片上缘附近
                        0x8CFFFFFF, // 白 55%：玻璃卡片上缘
                        0xE6FFFFFF, // 白 90%
                        0xFFFFFFFF, // 纯白
                        0xFFFFFFFF  // 到底纯白
                },
                stops,
                Shader.TileMode.CLAMP);
        ShapeDrawable drawable = new ShapeDrawable(new RectShape());
        drawable.getPaint().setShader(gradient);
        mask.setBackground(drawable);
    }

    private void bindEvents() {
        // 顶部导航栏返回：必须调本类的 onBackPressed()，与系统返回完全同一条路径
        //（验证码第二步会先退回第一步改邮箱，而不是直接退出登录页）。
        // 不能用 getOnBackPressedDispatcher().onBackPressed()：其兜底 Runnable 走的是
        // ComponentActivity.super.onBackPressed()，会绕过这里对 onBackPressed() 的重写。
        findViewById(R.id.btn_login_back).setOnClickListener(v -> onBackPressed());

        btnLogin.setOnClickListener(v -> submit());

        // 已无独立注册页：密码态点「还没有账号？注册」等价于切到验证码登录，
        // 验证码登录对未注册邮箱会自动建号，这条路就是注册入口。
        tvRegister.setOnClickListener(v -> switchToCodeLogin());

        tvSwitch.setOnClickListener(v -> {
            if (mode == MODE_PASSWORD) {
                switchToCodeLogin();
            } else {
                mode = MODE_PASSWORD;
                applyState();
            }
        });

        tvBack.setOnClickListener(v -> {
            codeStep = STEP_EMAIL;
            applyState();
        });

        // 勾选框本身不接收点击，整行都可点，触摸区更大
        rowConsent.setOnClickListener(v -> toggleConsent());

        tvConsent = findViewById(R.id.tv_consent);
        setupConsentText();

        ivEye.setOnClickListener(v -> togglePasswordVisible());

        tvCountdown.setOnClickListener(v -> {
            if (tvCountdown.isClickable() && !sendingCode && !sendingEmail.isEmpty()) {
                doSendCode(sendingEmail, false);
            }
        });

        setupFloatLabel(etAccount, labelAccount, phAccount);
        setupFloatLabel(etPassword, labelPassword, phPassword);
        setupFloatLabel(etCodeEmail, labelCodeEmail, phCodeEmail);

        setupCodeTiles();
    }

    // ==================== 浮动标签 ====================

    /**
     * 让输入框的标签「上移缩小」：未聚焦且为空时标签以 15sp 灰色停在输入行当占位符；
     * 聚焦或有内容时上移 {@link #LABEL_RISE_DP}、缩到 {@link #LABEL_SCALE}、转主色紫，
     * 同时把占位提示淡入到输入行，效果与设计稿一致。
     *
     * <p>标签缩放围绕自身中心，所以位移只由 translationY 决定，不受 pivot 影响；
     * pivotX 固定为 0 让缩放时左边缘不动，与输入文字左对齐。占位提示单独用 TextView 而不是
     * EditText 的 hint，是因为 hint 无法做淡入，切换态会硬闪一下。
     */
    private void setupFloatLabel(final EditText et, final TextView label, final TextView placeholder) {
        label.setPivotX(0f);

        final Runnable syncLabel = () -> {
            boolean up = et.isFocused() || et.getText().length() > 0;
            Object tag = label.getTag();
            if (tag instanceof Boolean && (Boolean) tag == up) {
                return;
            }
            label.setTag(up);
            label.setPivotY(contentCenterY(label));
            animateFloatLabel(label, up);
        };

        final Runnable syncPlaceholder = () -> {
            boolean show = et.isFocused() && et.getText().length() == 0;
            placeholder.animate().cancel();
            placeholder.animate()
                    .alpha(show ? 1f : 0f)
                    .setDuration(LABEL_ANIM_MS)
                    .start();
        };

        et.setOnFocusChangeListener((v, hasFocus) -> {
            syncLabel.run();
            syncPlaceholder.run();
        });

        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                syncLabel.run();
                syncPlaceholder.run();
            }
        });

        // 首帧：Enter 键收起键盘等场景不会触发焦点回调，先按当前状态对齐一次
        et.post(() -> {
            syncLabel.run();
            syncPlaceholder.run();
        });
    }

    /** 标签在「停靠输入行」与「浮起」之间过渡：位移、缩放、颜色同步推进。 */
    private void animateFloatLabel(final TextView label, final boolean up) {
        float toY = up ? -dp(LABEL_RISE_DP) : 0f;
        float toScale = up ? LABEL_SCALE : 1f;
        int toColor = getColor(up ? R.color.login_accent_text : R.color.login_text_hint);

        float fromY = label.getTranslationY();
        float fromScale = label.getScaleX();
        int fromColor = label.getCurrentTextColor();

        final ArgbEvaluator argb = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LABEL_ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float p = a.getAnimatedFraction();
            label.setTranslationY(fromY + (toY - fromY) * p);
            float scale = fromScale + (toScale - fromScale) * p;
            label.setScaleX(scale);
            label.setScaleY(scale);
            label.setTextColor((int) argb.evaluate(p, fromColor, toColor));
        });
        animator.start();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /**
     * 标签「文字所在内容区」的垂直中心。
     *
     * <p>标签和输入框用同一套定位参数（撑满框高 + paddingTop + center_vertical），所以文字必然同高；
     * 但标签自身高度因此等于整个输入框，直接拿 {@code getHeight()/2} 当缩放轴会把文字中心带偏，
     * 必须取内容区中心，缩放时文字中心才不动。
     */
    private float contentCenterY(TextView view) {
        int paddingTop = view.getPaddingTop();
        return paddingTop + (view.getHeight() - paddingTop - view.getPaddingBottom()) / 2f;
    }

    private void openDoc(String title, String url) {
        DocLinks.open(this, title, url);
    }

    private void toggleConsent() {
        consentChecked = !consentChecked;
        ivConsent.setSelected(consentChecked);
    }

    /**
     * 把「我已阅读并同意《用户协议》和《隐私政策》」渲染成一段带链接的富文本。
     *
     * <p>用单个 TextView + {@link ClickableSpan} 而不是并排几个 TextView：并排时一旦整行放不下，
     * 靠后的文本只会被分到剩余的几个像素宽，中文会逐字换行成竖排、行高暴增，勾选框上下留下大片空白。
     * 这里整句自己排版，最坏情况只是整齐折成两行。链接以外的文字也可点，等价于勾选整行。
     */
    private void setupConsentText() {
        String prefix = "我已阅读并同意";
        String terms = "《用户协议》";
        String joiner = "和";
        String privacy = "《隐私政策》";
        String sentence = prefix + terms + joiner + privacy;

        SpannableString span = new SpannableString(sentence);
        int termsStart = prefix.length();
        int termsEnd = termsStart + terms.length();
        int joinerEnd = termsEnd + joiner.length();

        span.setSpan(new ToggleConsentSpan(), 0, termsStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new DocLinkSpan("用户协议", DocLinks.TERMS), termsStart, termsEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new ToggleConsentSpan(), termsEnd, joinerEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new DocLinkSpan("隐私政策", DocLinks.PRIVACY), joinerEnd, sentence.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        tvConsent.setText(span);
        tvConsent.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /** 点协议正文 = 切换勾选，整句没有点不到的死区。 */
    private class ToggleConsentSpan extends ClickableSpan {
        @Override
        public void onClick(View widget) {
            toggleConsent();
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(getColor(R.color.login_text_muted));
            ds.setUnderlineText(false);
        }
    }

    /** 点《用户协议》/《隐私政策》= 打开内嵌文档页。 */
    private class DocLinkSpan extends ClickableSpan {
        private final String title;
        private final String url;

        DocLinkSpan(String title, String url) {
            this.title = title;
            this.url = url;
        }

        @Override
        public void onClick(View widget) {
            openDoc(title, url);
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(getColor(R.color.login_accent_text));
            ds.setUnderlineText(false);
        }
    }

    /** 按当前「方式 + 验证码步骤」刷新所有视图，三种形态共用同一套控件。 */
    private void applyState() {
        boolean passwordMode = mode == MODE_PASSWORD;
        boolean stepEmail = mode == MODE_CODE && codeStep == STEP_EMAIL;
        boolean stepCode = mode == MODE_CODE && codeStep == STEP_CODE;

        applyCardOffset(passwordMode);

        tvHint.setVisibility(stepCode ? View.GONE : View.VISIBLE);
        grpPassword.setVisibility(passwordMode ? View.VISIBLE : View.GONE);
        // 显隐切在容器上：输入框外面还套着浮动标签与占位文字，单独隐藏 EditText 会留下空壳
        boxCodeEmail.setVisibility(stepEmail ? View.VISIBLE : View.GONE);
        grpCodeInput.setVisibility(stepCode ? View.VISIBLE : View.GONE);

        if (passwordMode) {
            tvHint.setText("使用已注册的邮箱与密码登录。");
            btnLogin.setIdleText("登录");
        } else if (stepEmail) {
            tvHint.setText("请使用邮箱登录（首次登录自动注册）");
            btnLogin.setIdleText("使用邮箱验证码继续");
        } else {
            btnLogin.setIdleText("验证并登录");
        }

        // 协议勾选、方式切换只在「填账号」的两种形态出现；第二步聚焦验证码输入
        int presetVisibility = stepCode ? View.GONE : View.VISIBLE;
        // 注册入口只在密码态：验证码态本身就是注册入口，再摆一个「注册」是重复信息
        tvRegister.setVisibility(passwordMode ? View.VISIBLE : View.GONE);
        rowConsent.setVisibility(presetVisibility);
        tvSwitch.setVisibility(presetVisibility);
        tvBack.setVisibility(stepCode ? View.VISIBLE : View.GONE);

        tvSwitch.setText(passwordMode ? "使用邮箱验证码登录" : "使用密码登录");
    }

    /**
     * 设卡片上方留白高度：密码态 {@link #CARD_TOP_SPACE_DP}，验证码态再 +{@link #CARD_TOP_SPACE_EXTRA_CODE_DP}。
     *
     * <p>只改这一块留白，卡片以下的内容（按钮、协议）都在同一个垂直流里，
     * 会随之下移；底部那块弹性 Space 自动收缩吸收，所以验证码态不会因此出现滚动。
     * 遮罩渐变的色标以卡片上缘为基准，卡片一动会通过布局回调重算（见 {@link #setupMask()}）。
     */
    private void applyCardOffset(boolean passwordMode) {
        float heightDp = passwordMode
                ? CARD_TOP_SPACE_DP
                : CARD_TOP_SPACE_DP + CARD_TOP_SPACE_EXTRA_CODE_DP;
        int height = Math.round(dp(heightDp));
        ViewGroup.LayoutParams params = spacerTop.getLayoutParams();
        // 形态没变时高度相同，跳过 setLayoutParams 避免多余的一次重新布局
        if (params.height == height) {
            return;
        }
        params.height = height;
        spacerTop.setLayoutParams(params);
    }

    /** 切到验证码登录第一步：未注册邮箱首次验证即自动建号，密码态的「注册」入口走的也是这条路。 */
    private void switchToCodeLogin() {
        mode = MODE_CODE;
        codeStep = STEP_EMAIL;
        applyState();
    }

    private void togglePasswordVisible() {
        passwordVisible = !passwordVisible;
        int selection = etPassword.getSelectionEnd();
        etPassword.setInputType(passwordVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // 切换 inputType 会重置字体与光标，需要手动还原
        etPassword.setTypeface(Typeface.DEFAULT);
        if (selection >= 0 && selection <= etPassword.getText().length()) {
            etPassword.setSelection(selection);
        }
        ivEye.setImageResource(passwordVisible ? R.drawable.ic_login_eye_off : R.drawable.ic_login_eye);
        ivEye.setContentDescription(passwordVisible ? "隐藏密码" : "显示密码");
    }

    // ==================== 验证码 6 格 ====================

    private void setupCodeTiles() {
        for (int i = 0; i < codeTiles.length; i++) {
            final int index = i;
            final EditText tile = codeTiles[i];

            tile.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (s.length() == 1 && index < codeTiles.length - 1) {
                        focusTile(index + 1);
                    }
                }
            });

            // 空格子按退格：清掉上一格并把焦点退回去，符合逐位输入的直觉
            tile.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (tile.getText().length() == 0 && index > 0) {
                        codeTiles[index - 1].setText("");
                        focusTile(index - 1);
                        return true;
                    }
                }
                return false;
            });

            // 点回已填的格子时光标停在末尾，避免插到中间
            tile.setOnClickListener(v -> {
                Editable text = tile.getText();
                if (text != null) {
                    tile.setSelection(text.length());
                }
            });
        }
    }

    private void focusTile(int index) {
        if (index < 0 || index >= codeTiles.length) {
            return;
        }
        EditText tile = codeTiles[index];
        tile.requestFocus();
        Editable text = tile.getText();
        if (text != null) {
            tile.setSelection(text.length());
        }
    }

    private void clearCodeTiles() {
        for (EditText tile : codeTiles) {
            tile.setText("");
        }
    }

    private String readCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (EditText tile : codeTiles) {
            builder.append(tile.getText().toString().trim());
        }
        return builder.toString();
    }

    // ==================== 提交流程 ====================

    private void submit() {
        if (mode == MODE_PASSWORD) {
            loginByPassword();
        } else if (codeStep == STEP_EMAIL) {
            sendCodeFirstStep();
        } else {
            loginByCode();
        }
    }

    private void sendCodeFirstStep() {
        if (sendingCode) {
            return;
        }
        String email = etCodeEmail.getText().toString().trim();
        if (email.isEmpty()) {
            Hint.show(this, "请输入邮箱");
            return;
        }
        if (!isEmailFormat(email)) {
            Hint.show(this, "邮箱格式不正确");
            return;
        }
        if (!consentChecked) {
            Hint.show(this, "请先阅读并同意《用户协议》和《隐私政策》");
            return;
        }
        doSendCode(email, true);
    }

    /**
     * 请求发送邮箱验证码。
     *
     * @param email   目标邮箱
     * @param advance true=首次发送（成功后进入第二步）；false=在第二步重发（留在原步骤）
     */
    private void doSendCode(final String email, final boolean advance) {
        sendingCode = true;
        btnLogin.setEnabled(false);
        tvCountdown.setClickable(false);
        if (!advance) {
            tvCountdown.setText("发送中...");
        }

        SendCodeRequest request = new SendCodeRequest(email, "auth");
        RetrofitClient.getApiService().sendAuthEmailCode(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    sendingCode = false;
                    btnLogin.setEnabled(true);
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        sendingEmail = email;
                        tvCodeSent.setText("验证码已发送至 " + maskEmail(email));
                        clearCodeTiles();
                        if (advance) {
                            codeStep = STEP_CODE;
                            applyState();
                        }
                        focusTile(0);
                        startCountdown();
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "发送失败";
                        Hint.show(LoginActivity.this, msg);
                        restoreResendIfNeeded(advance);
                    }
                },
                (call, t) -> {
                    sendingCode = false;
                    btnLogin.setEnabled(true);
                    Hint.show(LoginActivity.this, "网络错误: " + t.getMessage());
                    restoreResendIfNeeded(advance);
                }
        ));
    }

    /** 重发失败时把「重新获取」恢复成可点，避免用户被卡住。 */
    private void restoreResendIfNeeded(boolean advance) {
        if (!advance) {
            tvCountdown.setText("重新获取验证码");
            tvCountdown.setTextColor(getColor(R.color.login_accent_text));
            tvCountdown.setClickable(true);
        }
    }

    private void startCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        tvCountdown.setClickable(false);
        tvCountdown.setTextColor(getColor(R.color.login_text_dim));
        countDownTimer = new CountDownTimer(COUNTDOWN_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvCountdown.setText((millisUntilFinished / 1000) + " 秒后可重新获取");
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("重新获取验证码");
                tvCountdown.setTextColor(getColor(R.color.login_accent_text));
                tvCountdown.setClickable(true);
            }
        }.start();
    }

    /** 邮箱脱敏：只留首字符，如 r***@163.com。 */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String name = email.substring(0, at);
        String domain = email.substring(at);
        if (name.length() <= 1) {
            return name + "***" + domain;
        }
        return name.charAt(0) + "***" + domain;
    }

    private void loginByCode() {
        String email = sendingEmail;
        String code = readCode();

        if (email.isEmpty()) {
            Hint.show(this, "请返回上一步填写邮箱");
            return;
        }
        if (code.length() != CODE_LENGTH) {
            Hint.show(this, "请输入 6 位验证码");
            return;
        }
        if (!isDigits(code)) {
            Hint.show(this, "验证码为 6 位数字");
            return;
        }

        btnLogin.startLoading();
        CodeLoginRequest request = new CodeLoginRequest(email, code);
        RetrofitClient.getApiService().loginByEmailCode(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        LoginResponse data = response.body().getData();
                        btnLogin.succeed(Boolean.TRUE.equals(data.getNewUser()) ? "注册成功" : "登录成功");
                        final LoginResponse finalData = data;
                        btnLogin.postDelayed(() -> handleLoginSuccess(finalData), 1000);
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "登录失败，请检查网络";
                        btnLogin.fail(msg);
                    }
                },
                (call, t) -> {
                    btnLogin.fail("网络错误: " + t.getMessage());
                }
        ));
    }

    private void loginByPassword() {
        String account = etAccount.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (account.isEmpty()) {
            Hint.show(this, "请输入邮箱");
            return;
        }
        if (password.isEmpty()) {
            Hint.show(this, "请输入密码");
            return;
        }
        if (!consentChecked) {
            Hint.show(this, "请先阅读并同意《用户协议》和《隐私政策》");
            return;
        }

        btnLogin.startLoading();
        LoginRequest request = new LoginRequest(account, password);
        RetrofitClient.getApiService().login(request).enqueue(SafeCallback.from(this,
                (call, response) -> {
                    if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                        LoginResponse data = response.body().getData();
                        btnLogin.succeed(Boolean.TRUE.equals(data.getNewUser()) ? "注册成功" : "登录成功");
                        final LoginResponse finalData = data;
                        btnLogin.postDelayed(() -> handleLoginSuccess(finalData), 1000);
                    } else {
                        String msg = (response.body() != null) ? response.body().getMessage() : "登录失败，请检查网络";
                        btnLogin.fail(msg);
                    }
                },
                (call, t) -> {
                    btnLogin.fail("网络错误: " + t.getMessage());
                }
        ));
    }

    // ==================== 会话与跳转 ====================

    /** 登录/注册成功后：写入会话、迁移外站数据、启动同步并按需跳转（两种登录方式共用）。 */
    private void handleLoginSuccess(LoginResponse data) {
        if (data == null) {
            Hint.show(this, "登录失败，请稍后重试");
            return;
        }

        String username = (data.getUsername() != null && !data.getUsername().isEmpty())
                ? data.getUsername()
                : sp.getString("username", "");

        sp.edit()
                .putString("username", username)
                .putString("userId", String.valueOf(data.getId()))
                .putString("token", data.getToken())
                .putString("nickname", data.getNickname())
                .putString("email", data.getEmail() != null ? data.getEmail() : "")
                .putString("phone", data.getPhone() != null ? data.getPhone() : "")
                .apply();

        // 头像：登录响应里带有服务器头像地址，下载到本地缓存后写回 SP。
        // 否则退出重登（SP 被 clear）后头像必然丢失。后台线程下载，失败则回落默认头像。
        final String loginAvatar = data.getAvatar();
        if (loginAvatar != null && !loginAvatar.isEmpty()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    File f = AvatarCache.download(LoginActivity.this, loginAvatar);
                    if (f != null) {
                        // avatar_server 标记一并写入，ProfileSync 不会再把同一头像拉一遍；
                        // 下载失败则不写标记，下次进「我的」页由 ProfileSync 自动重试
                        sp.edit()
                                .putString("avatar", f.getAbsolutePath())
                                .putString("avatar_server", loginAvatar)
                                .apply();
                    } else {
                        sp.edit().putString("avatar", "").apply();
                    }
                }
            }).start();
        } else {
            sp.edit()
                    .putString("avatar", "")
                    .putString("avatar_server", "")
                    .apply();
        }

        // 旧版全局外站数据迁移到当前登录用户命名空间（幂等）
        ExternalPrefs.migrateIfNeeded(LoginActivity.this);

        // 登录成功：从服务器拉取外站书数据（书架/记录/书签）并启动周期同步
        ExternalSyncManager.getInstance(LoginActivity.this).pullAll();
        ExternalSyncManager.getInstance(LoginActivity.this).startPeriodic();

        // 成功反馈已由按钮形变条（黄底「登录成功 / 注册成功」）呈现，这里不再弹 Toast 重复提示。

        // 从功能入口跳转来的登录（EXTRA_RETURN_TO_CALLER=true）登录后返回原页面；
        // 否则（如注销后重登）进入主页。
        boolean returnToCaller = getIntent().getBooleanExtra(EXTRA_RETURN_TO_CALLER, false);
        if (!returnToCaller) {
            startActivity(new Intent(LoginActivity.this, MainActivity.class));
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // 第二步按返回：先退回第一步改邮箱，而不是直接退出登录页
        if (mode == MODE_CODE && codeStep == STEP_CODE) {
            codeStep = STEP_EMAIL;
            applyState();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        super.onDestroy();
    }

    // ==================== 校验工具 ====================

    /** 邮箱格式：必须含 "@" 且 "@" 之后至少有一个 "."。 */
    private boolean isEmailFormat(String email) {
        int at = email.indexOf('@');
        return at > 0 && email.indexOf('.', at) > at + 1 && email.lastIndexOf('.') < email.length() - 1;
    }

    private boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
