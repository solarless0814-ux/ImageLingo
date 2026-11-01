package com.rarisol.imagelingo;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends ComponentActivity {
    private static final String APP_PREFS = "app_state";
    private static final String OUTPUT_DIR_NAME = "ImageLingo_Translated";
    private static final int MAX_DISPLAY_EDGE = 2560;
    private static final long MAX_DISPLAY_PIXELS = 6_000_000L;
    private static final int MAX_OUTPUT_EDGE = 4096;
    private static final long MAX_OUTPUT_PIXELS = 10_000_000L;

    private static final int COLOR_BG = 0xFF080A0F;
    private static final int COLOR_SURFACE = 0xF21A1D27;
    private static final int COLOR_SURFACE_2 = 0xFF232733;
    private static final int COLOR_PRIMARY = 0xFF7867FF;
    private static final int COLOR_PRIMARY_2 = 0xFF4DA8FF;
    private static final int COLOR_TEXT = 0xFFF6F7FB;
    private static final int COLOR_MUTED = 0xFFA8ADBC;
    private static final int COLOR_STROKE = 0xFF343947;

    private final ExecutorService displayExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger imageLoadVersion = new AtomicInteger();
    private final AtomicInteger folderLoadVersion = new AtomicInteger();
    private final Map<String, TranslationClient.TranslationResult> translationCache = new ConcurrentHashMap<>();
    private final Map<String, Uri> translatedOutputMap = new ConcurrentHashMap<>();
    private Future<?> imageLoadTask;
    private Future<?> batchTask;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private ActivityResultLauncher<Intent> vertexJsonPickerLauncher;

    private ZoomImageView imageView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView progressTextView;
    private ProgressBar folderProgressBar;
    private ProgressBar progressBar;
    private LinearLayout translationPanel;
    private TextView translationTitle;
    private TextView translationText;
    private TextView tokenBadge;
    private Button translateButton;
    private Button copyButton;
    private Button stopButton;
    private Button modeButton;
    private TextView vertexStatusText;
    private FrameLayout rootView;

    private List<ImageRepository.ImageEntry> allImages = new ArrayList<>();
    private List<ImageRepository.ImageEntry> images = new ArrayList<>();
    private int currentIndex = -1;
    private Bitmap currentBitmap;
    private ApiSettings apiSettings;
    private SecureStore secureStore;
    private SharedPreferences appPrefs;
    private Uri currentTreeUri;
    private volatile boolean batchRunning;
    private volatile boolean batchStopRequested;
    private volatile String processingImageUri;
    private volatile int processingPageNumber;
    private volatile TranslationMemory translationMemory;
    private volatile TranslationManifest translationManifest;
    private volatile Uri outputDirectoryUri;
    private final Object rateLimitPauseLock = new Object();
    private volatile boolean rateLimitPaused;
    private volatile String rateLimitPauseMessage = "";
    private boolean translatedOnlyMode;
    private String currentTranslationText = "";
    private final OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            handleBackPressed();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        getWindow().setNavigationBarDividerColor(COLOR_BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        apiSettings = new ApiSettings(this);
        secureStore = new SecureStore(this);
        appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        translatedOnlyMode = appPrefs.getBoolean("translated_only_mode", false);
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleFolderPickerResult(result.getResultCode(), result.getData()));
        vertexJsonPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleVertexJsonPickerResult(result.getResultCode(), result.getData()));
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
        buildUi();

        String savedTree = appPrefs.getString("tree_uri", "");
        if (savedTree != null && !savedTree.isEmpty()) {
            loadFolder(Uri.parse(savedTree));
        } else {
            showWelcome();
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (rootView != null) {
            rootView.post(rootView::requestApplyInsets);
        }
    }

    private void buildUi() {
        rootView = new FrameLayout(this);
        FrameLayout root = rootView;
        root.setBackgroundColor(COLOR_BG);

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(COLOR_BG);
        imageView.setContentDescription("이미지 없음 · 폴더를 선택하세요");
        imageView.setPageSwipeListener(this::movePage);

        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        imageParams.topMargin = dp(126);
        imageParams.bottomMargin = dp(84);
        root.addView(imageView, imageParams);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackground(roundedBackground(COLOR_SURFACE, 20, COLOR_STROKE));
        topBar.setElevation(dp(8));

        Button folderButton = makeToolbarButton("폴더");
        folderButton.setOnClickListener(v -> chooseFolder());
        folderButton.setContentDescription("이미지 폴더 선택");
        folderButton.setOnLongClickListener(v -> {
            if (currentTreeUri != null && !batchRunning) {
                loadFolder(currentTreeUri);
                Toast.makeText(this, "현재 폴더를 새로고침합니다.", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setGravity(Gravity.CENTER);
        titleView = new TextView(this);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(13.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setAccessibilityHeading(true);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(COLOR_MUTED);
        subtitleView.setTextSize(12f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setSingleLine(true);
        progressTextView = new TextView(this);
        progressTextView.setTextColor(0xFF9FC4FF);
        progressTextView.setTextSize(12f);
        progressTextView.setTypeface(Typeface.DEFAULT_BOLD);
        progressTextView.setGravity(Gravity.CENTER);
        progressTextView.setSingleLine(true);
        progressTextView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        folderProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        folderProgressBar.setMax(100);
        folderProgressBar.setProgress(0);
        folderProgressBar.setProgressTintList(ColorStateList.valueOf(COLOR_PRIMARY_2));
        folderProgressBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2B3040));
        folderProgressBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleGroup.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleGroup.addView(subtitleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleGroup.addView(progressTextView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams folderProgressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        folderProgressParams.setMargins(dp(12), dp(3), dp(12), 0);
        titleGroup.addView(folderProgressBar, folderProgressParams);

        LinearLayout rightControls = new LinearLayout(this);
        rightControls.setOrientation(LinearLayout.VERTICAL);
        rightControls.setGravity(Gravity.CENTER);
        Button settingsButton = makeToolbarButton("설정");
        settingsButton.setContentDescription("번역 API와 모델 설정");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        modeButton = makeToolbarButton(translatedOnlyMode ? "전체 보기" : "번역본만");
        modeButton.setTextSize(12f);
        modeButton.setOnClickListener(v -> toggleTranslatedOnlyMode());
        updateModeButtonAppearance();
        LinearLayout.LayoutParams settingsButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        settingsButtonParams.setMargins(0, dp(1), 0, dp(1));
        LinearLayout.LayoutParams modeButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        modeButtonParams.setMargins(0, dp(1), 0, dp(1));
        rightControls.addView(settingsButton, settingsButtonParams);
        rightControls.addView(modeButton, modeButtonParams);

        topBar.addView(folderButton, new LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT));
        topBar.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.MATCH_PARENT);
        rightParams.setMargins(dp(4), 0, 0, 0);
        topBar.addView(rightControls, rightParams);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(112), Gravity.TOP);
        topParams.setMargins(dp(12), dp(8), dp(12), 0);
        root.addView(topBar, topParams);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bottomBar.setBackground(roundedBackground(COLOR_SURFACE, 22, COLOR_STROKE));
        bottomBar.setElevation(dp(10));

        Button previous = makeNavButton("〈  이전");
        previous.setOnClickListener(v -> movePage(-1));
        previous.setOnLongClickListener(v -> jumpToPage(0));
        previous.setContentDescription("이전 이미지 · 길게 눌러 첫 이미지로 이동");
        translateButton = makePrimaryButton("폴더 번역");
        translateButton.setContentDescription("폴더 번역 · 길게 누르면 기존 번역본도 모두 다시 번역");
        translateButton.setOnClickListener(v -> {
            if (rateLimitPaused) resumeRateLimitPause();
            else translateFolder(false);
        });
        translateButton.setOnLongClickListener(v -> {
            if (rateLimitPaused || batchRunning) return true;
            new AlertDialog.Builder(this)
                    .setTitle("폴더 전체 다시 번역")
                    .setMessage("현재 번역본도 모두 API로 다시 보내고 덮어씁니다. 추가 사용량과 비용이 발생할 수 있습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("모두 다시 번역", (dialog, which) -> translateFolder(true))
                    .show();
            return true;
        });
        Button next = makeNavButton("다음  〉");
        next.setOnClickListener(v -> movePage(1));
        next.setOnLongClickListener(v -> jumpToPage(images.size() - 1));
        next.setContentDescription("다음 이미지 · 길게 눌러 마지막 이미지로 이동");

        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        navParams.setMargins(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams translateParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.25f);
        translateParams.setMargins(dp(4), 0, dp(4), 0);
        bottomBar.addView(previous, navParams);
        bottomBar.addView(translateButton, translateParams);
        bottomBar.addView(next, navParams);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.BOTTOM);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(10));
        root.addView(bottomBar, bottomParams);

        translationPanel = new LinearLayout(this);
        translationPanel.setOrientation(LinearLayout.VERTICAL);
        translationPanel.setPadding(dp(18), dp(12), dp(18), dp(10));
        translationPanel.setBackground(roundedTopBackground(0xFA151821, 26, COLOR_STROKE));
        translationPanel.setElevation(dp(14));
        translationPanel.setVisibility(View.GONE);
        translationPanel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout panelHeader = new LinearLayout(this);
        panelHeader.setOrientation(LinearLayout.HORIZONTAL);
        panelHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout panelTitleGroup = new LinearLayout(this);
        panelTitleGroup.setOrientation(LinearLayout.VERTICAL);
        translationTitle = new TextView(this);
        translationTitle.setTextColor(0xFFBFD5FF);
        translationTitle.setTextSize(15f);
        translationTitle.setTypeface(Typeface.DEFAULT_BOLD);
        translationTitle.setAccessibilityHeading(true);
        TextView panelCaption = new TextView(this);
        panelCaption.setText("안내");
        panelCaption.setTextColor(COLOR_MUTED);
        panelCaption.setTextSize(12f);
        panelTitleGroup.addView(translationTitle);
        panelTitleGroup.addView(panelCaption);

        Button close = makePanelButton("닫기");
        close.setOnClickListener(v -> hideTranslationPanel());

        panelHeader.addView(panelTitleGroup, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams panelButtonParams = new LinearLayout.LayoutParams(dp(68), dp(48));
        panelButtonParams.setMargins(dp(5), 0, 0, 0);
        panelHeader.addView(close, panelButtonParams);
        translationPanel.addView(panelHeader);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_STROKE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(3), 0, dp(7));
        translationPanel.addView(divider, dividerParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        translationText = new TextView(this);
        translationText.setTextColor(COLOR_TEXT);
        translationText.setTextSize(16f);
        translationText.setTextIsSelectable(true);
        translationText.setLineSpacing(dp(2), 1.16f);
        translationText.setPadding(dp(2), dp(4), dp(2), dp(16));
        scroll.addView(translationText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        translationPanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int panelHeight = calculatePanelHeight(getResources().getDisplayMetrics().heightPixels);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelHeight, Gravity.BOTTOM);
        panelParams.setMargins(dp(8), 0, dp(8), dp(82));
        root.addView(translationPanel, panelParams);

        tokenBadge = new TextView(this);
        tokenBadge.setTextColor(COLOR_TEXT);
        tokenBadge.setTextSize(13f);
        tokenBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tokenBadge.setGravity(Gravity.CENTER);
        tokenBadge.setPadding(dp(12), dp(7), dp(12), dp(7));
        tokenBadge.setBackground(roundedBackground(0xF22A2E3B, 18, 0xFF4A5060));
        tokenBadge.setElevation(dp(18));
        tokenBadge.setVisibility(View.GONE);
        tokenBadge.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams tokenParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        tokenParams.setMargins(0, 0, dp(20), dp(94));
        root.addView(tokenBadge, tokenParams);

        copyButton = makePanelButton("번역문 복사");
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> copyTranslation());
        FrameLayout.LayoutParams leftButtonParams = new FrameLayout.LayoutParams(
                dp(120), dp(48), Gravity.BOTTOM | Gravity.START);
        leftButtonParams.setMargins(dp(20), 0, 0, dp(94));
        root.addView(copyButton, leftButtonParams);

        stopButton = makePanelButton("번역 중지");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> requestStopBatch());
        FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(
                dp(120), dp(48), Gravity.BOTTOM | Gravity.START);
        stopParams.setMargins(dp(20), 0, 0, dp(94));
        root.addView(stopButton, stopParams);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
        root.addView(progressBar, progressParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applySystemBarInsets(insets, imageParams, topParams, bottomParams, panelParams,
                    tokenParams, leftButtonParams, stopParams);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private void showWelcome() {
        titleView.setText("ImageLingo");
        subtitleView.setText("AI 이미지 번역 리더");
        progressTextView.setText("번역 0 / 0 · 0%");
        folderProgressBar.setProgress(0, true);
        translationTitle.setText("시작하기");
        translationTitle.setTextColor(0xFFBFD5FF);
        translationText.setText("상단의 ‘폴더’를 눌러 이미지가 들어 있는 폴더를 선택하세요.\n\n"
                + "‘설정’에서 Gemini, OpenAI 또는 Vertex AI 인증 정보와 시스템 프롬프트를 입력한 뒤, 하단의 ‘폴더 번역’을 누르면 선택한 폴더 전체가 순차적으로 번역됩니다.\n\n"
                + "원본 정보와 현재 번역 설정이 일치하는 PNG가 ‘" + OUTPUT_DIR_NAME
                + "’ 폴더에 있으면 자동으로 건너뜁니다.\n"
                + "번역된 이미지는 원문 위에 반투명 박스와 한국어 텍스트가 합성된 PNG 파일로 저장됩니다.\n\n"
                + "이미지·시스템 프롬프트·폴더 문맥은 선택한 외부 API로 전송되며 계정에 사용량과 비용이 발생할 수 있습니다. "
                + "문맥과 manifest JSON은 선택 폴더에 암호화되지 않은 파일로 저장됩니다.");
        tokenBadge.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        currentTranslationText = "";
        imageView.setContentDescription("이미지 없음 · 폴더를 선택하세요");
        showTranslationPanel();
    }

    private void chooseFolder() {
        if (batchRunning) {
            Toast.makeText(this, "폴더 전체를 번역 중입니다. 잠시만 기다려 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void chooseVertexJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        vertexJsonPickerLauncher.launch(intent);
    }

    private void handleFolderPickerResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Uri previousTree = currentTreeUri;
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if ((flags & requiredFlags) != requiredFlags) {
                throw new SecurityException("선택한 폴더의 읽기·쓰기 권한을 모두 받아야 합니다.");
            }
            getContentResolver().takePersistableUriPermission(uri, requiredFlags);
            if (!appPrefs.edit().putString("tree_uri", uri.toString()).commit()) {
                if (!uri.equals(previousTree)) releasePersistedTreePermission(uri);
                throw new IllegalStateException("선택한 폴더를 앱 설정에 저장하지 못했습니다.");
            }
            if (previousTree != null && !previousTree.equals(uri)) {
                releasePersistedTreePermission(previousTree);
            }
            loadFolder(uri);
        } catch (Exception permissionError) {
            Toast.makeText(this, "폴더 권한 오류: " + cleanMessage(permissionError),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void handleVertexJsonPickerResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importVertexJson(data.getData());
    }

    private void releasePersistedTreePermission(Uri treeUri) {
        if (treeUri == null) return;
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (!treeUri.equals(permission.getUri())) continue;
            int flags = 0;
            if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (flags == 0) return;
            try {
                getContentResolver().releasePersistableUriPermission(treeUri, flags);
            } catch (SecurityException ignored) {
                // The provider may already have revoked the old grant.
            }
            return;
        }
    }

    private void importVertexJson(Uri uri) {
        ioExecutor.execute(() -> {
            try {
                String jsonText = readUtf8(uri, 2_000_000);
                JSONObject json = new JSONObject(jsonText);
                if (!"service_account".equals(json.optString("type"))) {
                    throw new IllegalArgumentException("서비스 계정 JSON 형식이 아닙니다.");
                }
                if (json.optString("client_email").isEmpty() || json.optString("private_key").isEmpty()) {
                    throw new IllegalArgumentException("client_email 또는 private_key가 없습니다.");
                }
                secureStore.put("vertex_json", jsonText);
                TranslationClient.clearCachedCredentials();
                String project = json.optString("project_id", "");
                if (!project.isEmpty()) {
                    apiSettings.save(apiSettings.provider(), apiSettings.openAiModel(), apiSettings.openAiEndpoint(),
                            apiSettings.geminiModel(), apiSettings.vertexModel(), apiSettings.vertexLocation(),
                            project, apiSettings.systemPrompt());
                }
                runOnUiThread(() -> {
                    if (!isActivityAlive()) return;
                    if (vertexStatusText != null) vertexStatusText.setText("불러옴: " + json.optString("client_email"));
                    Toast.makeText(this, "Vertex AI JSON을 암호화해 저장했습니다.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isActivityAlive()) {
                        Toast.makeText(this, "JSON 오류: " + cleanMessage(e), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void loadFolder(Uri treeUri) {
        final int loadVersion = folderLoadVersion.incrementAndGet();
        currentTreeUri = treeUri;
        cancelImageLoadTask();
        imageLoadVersion.incrementAndGet();
        translationCache.clear();
        allImages = new ArrayList<>();
        images = new ArrayList<>();
        currentIndex = -1;
        outputDirectoryUri = null;
        translationMemory = null;
        translationManifest = null;
        swapDisplayedBitmap(null);
        translateButton.setEnabled(false);
        translateButton.setAlpha(0.65f);
        progressBar.setVisibility(View.VISIBLE);
        titleView.setText("폴더 읽는 중…");
        subtitleView.setText("");
        progressTextView.setText("번역 현황 확인 중…");
        translationPanel.setVisibility(View.GONE);
        tokenBadge.setVisibility(View.GONE);
        translatedOutputMap.clear();
        currentTranslationText = "";
        imageView.setContentDescription("폴더의 이미지 목록을 읽는 중");
        copyButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        ioExecutor.execute(() -> {
            try {
                List<ImageRepository.ImageEntry> loaded = ImageRepository.listImages(getContentResolver(), treeUri);
                Map<String, String> outputNames = outputNamesFor(loaded);
                Map<String, Uri> restoredOutputs = new HashMap<>();
                Uri outputDir = ImageRepository.findSubdirectory(getContentResolver(), treeUri, OUTPUT_DIR_NAME);
                TranslationMemory restoredMemory = TranslationMemory.load(getContentResolver(), outputDir);
                TranslationManifest restoredManifest = TranslationManifest.load(
                        getContentResolver(), outputDir);
                Map<String, TranslationClient.TranslationResult> restoredTranslations = new HashMap<>();
                if (outputDir != null) {
                    Map<String, Uri> outputsByName = ImageRepository.listImageDocumentsByName(
                            getContentResolver(), outputDir);
                    for (ImageRepository.ImageEntry entry : loaded) {
                        String outputName = outputNames.get(entry.uri.toString());
                        Uri translated = outputsByName.get(outputName);
                        String fingerprint = outputFingerprint(entry);
                        if (translated != null
                                && restoredManifest.isCurrent(entry, outputName, fingerprint)) {
                            restoredOutputs.put(entry.uri.toString(), translated);
                            String resultText = restoredManifest.resultText(entry, fingerprint);
                            if (!resultText.isEmpty()) {
                                restoredTranslations.put(cacheKeyFor(entry),
                                        new TranslationClient.TranslationResult(
                                                resultText, null, null, null));
                            }
                        }
                    }
                }
                runOnUiThread(() -> {
                    if (!isActivityAlive() || loadVersion != folderLoadVersion.get()
                            || !treeUri.equals(currentTreeUri)) return;
                    progressBar.setVisibility(View.GONE);
                    allImages = loaded;
                    outputDirectoryUri = outputDir;
                    translationMemory = restoredMemory;
                    translationManifest = restoredManifest;
                    translationCache.clear();
                    translationCache.putAll(restoredTranslations);
                    translatedOutputMap.clear();
                    translatedOutputMap.putAll(restoredOutputs);
                    translateButton.setEnabled(!loaded.isEmpty());
                    translateButton.setAlpha(loaded.isEmpty() ? 0.65f : 1f);
                    if (loaded.isEmpty()) {
                        images = new ArrayList<>();
                        currentIndex = -1;
                        titleView.setText("이미지 없음");
                        subtitleView.setText("다른 폴더를 선택하세요");
                        updateTopProgress(0);
                        translationTitle.setText("폴더가 비어 있습니다");
                        translationTitle.setTextColor(0xFFBFD5FF);
                        translationText.setText("선택한 폴더의 바로 아래에서 JPG, PNG, WEBP 등의 이미지 파일을 찾지 못했습니다.");
                        showTranslationPanel();
                        imageView.setImageDrawable(null);
                        imageView.setContentDescription("이미지 없음 · 다른 폴더를 선택하세요");
                    } else {
                        applyViewMode(null);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isActivityAlive() || loadVersion != folderLoadVersion.get()
                            || !treeUri.equals(currentTreeUri)) return;
                    progressBar.setVisibility(View.GONE);
                    titleView.setText("폴더 오류");
                    subtitleView.setText("");
                    progressTextView.setText("번역 현황을 읽지 못했습니다");
                    showResult("오류", cleanMessage(e), true);
                });
            }
        });
    }

    private void toggleTranslatedOnlyMode() {
        String preferredUri = currentVisibleUri();
        translatedOnlyMode = !translatedOnlyMode;
        appPrefs.edit().putBoolean("translated_only_mode", translatedOnlyMode).apply();
        applyViewMode(preferredUri);
    }

    private void updateModeButtonAppearance() {
        if (modeButton == null) return;
        modeButton.setText(translatedOnlyMode ? "전체 보기" : "번역본만");
        modeButton.setContentDescription(translatedOnlyMode
                ? "전체 이미지 보기로 전환" : "저장된 번역본만 보기");
        int color = translatedOnlyMode ? 0xFF37326B : COLOR_SURFACE_2;
        int stroke = translatedOnlyMode ? 0xFF7167D9 : COLOR_STROKE;
        modeButton.setBackground(rippleRoundedBackground(color, 14, stroke, 0x28FFFFFF));
    }

    private void applyViewMode(String preferredUri) {
        List<ImageRepository.ImageEntry> next = new ArrayList<>();
        if (translatedOnlyMode) {
            for (ImageRepository.ImageEntry entry : allImages) {
                String uri = entry.uri.toString();
                boolean translated = translatedOutputMap.containsKey(uri);
                boolean currentlyProcessing = batchRunning && uri.equals(processingImageUri);
                if (translated || currentlyProcessing) next.add(entry);
            }
        } else {
            next.addAll(allImages);
        }
        images = next;
        updateModeButtonAppearance();
        updateTopProgress(processingPageNumber);

        if (images.isEmpty()) {
            cancelImageLoadTask();
            imageLoadVersion.incrementAndGet();
            Bitmap old = currentBitmap;
            currentBitmap = null;
            imageView.setImageDrawable(null);
            if (old != null && !old.isRecycled()) old.recycle();
            currentIndex = -1;
            currentTranslationText = "";
            copyButton.setVisibility(View.GONE);
            titleView.setText(translatedOnlyMode ? "번역본 없음" : "이미지 없음");
            subtitleView.setText(translatedOnlyMode
                    ? "폴더 번역을 먼저 실행하세요" : "다른 폴더를 선택하세요");
            imageView.setContentDescription(translatedOnlyMode
                    ? "저장된 번역 이미지 없음" : "이미지 없음 · 다른 폴더를 선택하세요");
            if (translatedOnlyMode) {
                showResult("번역된 파일만 보기", "아직 저장된 번역본이 없습니다. ‘전체 보기’로 돌아가 폴더 번역을 실행하세요.", false);
            }
            return;
        }

        int nextIndex = findImageIndex(preferredUri);
        if (nextIndex < 0 && translatedOnlyMode && processingImageUri != null) {
            nextIndex = findImageIndex(processingImageUri);
        }
        currentIndex = Math.max(0, nextIndex);
        renderCurrent();
    }

    private int findImageIndex(String uri) {
        if (uri == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (uri.equals(images.get(i).uri.toString())) return i;
        }
        return -1;
    }

    private String currentVisibleUri() {
        return currentIndex >= 0 && currentIndex < images.size()
                ? images.get(currentIndex).uri.toString() : null;
    }

    private void refreshLiveView(String preferredUri, String changedUri) {
        if (translatedOnlyMode) {
            updateTranslatedOnlyListLive(preferredUri, false, changedUri);
        } else {
            updateTopProgress(processingPageNumber);
            if (changedUri != null && changedUri.equals(currentVisibleUri())) {
                renderCurrent();
            }
        }
    }

    private void updateTranslatedOnlyListLive(String preferredUri, boolean followNewest,
                                              String changedUri) {
        String oldVisibleUri = currentVisibleUri();
        List<ImageRepository.ImageEntry> next = new ArrayList<>();
        for (ImageRepository.ImageEntry entry : allImages) {
            String uri = entry.uri.toString();
            if (translatedOutputMap.containsKey(uri)
                    || (batchRunning && uri.equals(processingImageUri))) {
                next.add(entry);
            }
        }
        images = next;
        updateModeButtonAppearance();
        updateTopProgress(processingPageNumber);
        if (images.isEmpty()) {
            applyViewMode(preferredUri);
            return;
        }

        String targetUri = followNewest && processingImageUri != null
                ? processingImageUri : preferredUri;
        int nextIndex = findImageIndex(targetUri);
        if (nextIndex < 0 && processingImageUri != null) nextIndex = findImageIndex(processingImageUri);
        currentIndex = Math.max(0, nextIndex);
        ImageRepository.ImageEntry selected = images.get(currentIndex);
        String selectedUri = selected.uri.toString();
        boolean selectedChanged = oldVisibleUri == null || !oldVisibleUri.equals(selectedUri);
        boolean contentChanged = changedUri != null && changedUri.equals(selectedUri);
        if (selectedChanged || contentChanged || currentBitmap == null) {
            renderCurrent();
            return;
        }

        titleView.setText(selected.name);
        boolean saved = translatedOutputMap.containsKey(selectedUri);
        boolean processing = batchRunning && selectedUri.equals(processingImageUri) && !saved;
        subtitleView.setText("번역본 " + (currentIndex + 1) + " / " + images.size()
                + "  ·  " + providerLabel()
                + (saved ? "  ·  저장됨" : processing ? "  ·  번역 중" : ""));
        updateImageContentDescription(selected, saved, processing);
    }

    private int countTranslatedPages() {
        int count = 0;
        for (ImageRepository.ImageEntry entry : allImages) {
            if (translatedOutputMap.containsKey(entry.uri.toString())) count++;
        }
        return count;
    }

    private void updateTopProgress(int processingPage) {
        int total = allImages.size();
        int translated = countTranslatedPages();
        int percent = total <= 0 ? 0 : Math.round(translated * 100f / total);
        if (folderProgressBar != null) folderProgressBar.setProgress(percent, true);
        StringBuilder text = new StringBuilder();
        text.append("번역 완료 ").append(translated).append(" / ").append(total)
                .append(" · ").append(percent).append("%");
        if (batchRunning && processingPage > 0) {
            text.append(" · 현재 ").append(processingPage).append(" / ").append(total).append("페이지");
        }
        if (translatedOnlyMode) text.append(" · 번역본 모드");
        TranslationMemory memory = translationMemory;
        if (memory != null) {
            int memoryItems = memory.characterCount() + memory.termCount() + memory.honorificCount();
            if (memoryItems > 0) text.append(" · 문맥 ").append(memoryItems);
        }
        progressTextView.setText(text.toString());
    }

    private void updateImageContentDescription(ImageRepository.ImageEntry entry,
                                               boolean saved, boolean processing) {
        if (entry == null || imageView == null) return;
        String state = saved ? "저장된 번역 이미지" : processing ? "번역 중인 원본 이미지" : "원본 이미지";
        imageView.setContentDescription(entry.name + " · " + (currentIndex + 1) + " / "
                + images.size() + " · " + state + " · 두 번 탭하여 확대하거나 좌우로 넘기세요");
    }

    private void renderCurrent() {
        if (currentIndex < 0 || currentIndex >= images.size()) return;
        cancelImageLoadTask();
        int version = imageLoadVersion.incrementAndGet();
        ImageRepository.ImageEntry entry = images.get(currentIndex);
        titleView.setText(entry.name);
        boolean processing = batchRunning && entry.uri.toString().equals(processingImageUri)
                && !translatedOutputMap.containsKey(entry.uri.toString());
        subtitleView.setText((translatedOnlyMode ? "번역본 " : "")
                + (currentIndex + 1) + " / " + images.size() + "  ·  " + providerLabel()
                + (processing ? "  ·  번역 중" : ""));
        updateTopProgress(processingPageNumber);
        progressBar.setVisibility(View.VISIBLE);
        translationPanel.setVisibility(View.GONE);
        currentTranslationText = "";
        copyButton.setVisibility(View.GONE);
        imageView.setContentDescription(entry.name + " · 이미지 불러오는 중");
        imageLoadTask = displayExecutor.submit(() -> {
            Bitmap decoded = null;
            try {
                Uri displayUri = translatedOutputMap.get(entry.uri.toString());
                decoded = decodeForDisplay(displayUri != null ? displayUri : entry.uri);
                if (Thread.currentThread().isInterrupted() || version != imageLoadVersion.get()) return;
                String currentKey = cacheKeyFor(entry);
                TranslationClient.TranslationResult cached = translationCache.get(currentKey);
                Bitmap ready = decoded;
                decoded = null;
                runOnUiThread(() -> {
                    if (!isActivityAlive() || version != imageLoadVersion.get()) {
                        if (!ready.isRecycled()) ready.recycle();
                        return;
                    }
                    swapDisplayedBitmap(ready);
                    progressBar.setVisibility(View.GONE);
                    currentTranslationText = cached == null ? "" : cached.text;
                    copyButton.setVisibility(batchRunning || currentTranslationText.isEmpty() ? View.GONE : View.VISIBLE);
                    stopButton.setVisibility(batchRunning ? View.VISIBLE : View.GONE);
                    boolean processingNow = batchRunning
                            && entry.uri.toString().equals(processingImageUri)
                            && displayUri == null;
                    subtitleView.setText((translatedOnlyMode ? "번역본 " : "")
                            + (currentIndex + 1) + " / " + images.size() + "  ·  " + providerLabel()
                            + (displayUri != null ? "  ·  저장됨" : processingNow ? "  ·  번역 중" : ""));
                    updateImageContentDescription(entry, displayUri != null, processingNow);
                    updateTopProgress(processingPageNumber);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isActivityAlive() || version != imageLoadVersion.get()) return;
                    progressBar.setVisibility(View.GONE);
                    imageView.setContentDescription(entry.name + " · 이미지 불러오기 실패");
                    showResult("이미지 오류", cleanMessage(e), true);
                });
            } finally {
                if (decoded != null && !decoded.isRecycled()) decoded.recycle();
            }
        });
    }

    private void cancelImageLoadTask() {
        Future<?> task = imageLoadTask;
        imageLoadTask = null;
        if (task != null && !task.isDone()) task.cancel(true);
    }

    private Bitmap decodeForDisplay(Uri uri) throws Exception {
        return decodeBitmap(uri, MAX_DISPLAY_EDGE, MAX_DISPLAY_PIXELS);
    }

    private Bitmap decodeForOutput(Uri uri) throws Exception {
        return decodeBitmap(uri, MAX_OUTPUT_EDGE, MAX_OUTPUT_PIXELS);
    }

    private Bitmap decodeBitmap(Uri uri, int maximumEdge, long maximumPixels) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            Size size = info.getSize();
            int width = Math.max(1, size.getWidth());
            int height = Math.max(1, size.getHeight());
            int edge = Math.max(width, height);
            long pixels = (long) width * height;
            double edgeScale = edge > maximumEdge ? (double) maximumEdge / edge : 1d;
            double pixelScale = pixels > maximumPixels
                    ? Math.sqrt((double) maximumPixels / pixels) : 1d;
            double scale = Math.min(edgeScale, pixelScale);
            if (scale < 0.999d) {
                decoder.setTargetSize(Math.max(1, (int) Math.floor(width * scale)),
                        Math.max(1, (int) Math.floor(height * scale)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private void movePage(int direction) {
        if (images.isEmpty()) return;
        int next = currentIndex + direction;
        if (next < 0 || next >= images.size()) {
            Toast.makeText(this, next < 0 ? "첫 이미지입니다." : "마지막 이미지입니다.", Toast.LENGTH_SHORT).show();
            imageView.resetMatrix();
            return;
        }
        currentIndex = next;
        renderCurrent();
    }

    private boolean jumpToPage(int target) {
        if (images.isEmpty()) return false;
        int next = Math.max(0, Math.min(target, images.size() - 1));
        if (currentIndex == next) {
            imageView.resetMatrix();
            Toast.makeText(this, next == 0 ? "첫 이미지입니다." : "마지막 이미지입니다.",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        currentIndex = next;
        renderCurrent();
        return true;
    }

    private void translateFolder(boolean forceOverwrite) {
        if (batchRunning) return;
        if (allImages.isEmpty() || currentTreeUri == null) {
            Toast.makeText(this, "먼저 이미지 폴더를 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            TranslationClient.validateConfiguration(apiSettings, secureStore);
        } catch (Exception configurationError) {
            showResult("API 설정 확인 필요", cleanMessage(configurationError), true);
            Toast.makeText(this, cleanMessage(configurationError), Toast.LENGTH_LONG).show();
            return;
        }
        batchRunning = true;
        batchStopRequested = false;
        rateLimitPaused = false;
        rateLimitPauseMessage = "";
        processingImageUri = null;
        processingPageNumber = 0;
        imageLoadVersion.incrementAndGet();
        translationPanel.setVisibility(View.GONE);
        currentTranslationText = "";
        copyButton.setVisibility(View.GONE);
        stopButton.setText("번역 중지");
        stopButton.setEnabled(true);
        stopButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        tokenBadge.setVisibility(View.GONE);
        translateButton.setEnabled(false);
        translateButton.setText(forceOverwrite ? "전체 재번역 중…" : "폴더 번역 중…");
        translateButton.setAlpha(0.75f);
        modeButton.setEnabled(true);
        modeButton.setAlpha(1f);
        imageView.resetMatrix();

        List<ImageRepository.ImageEntry> snapshot = new ArrayList<>(allImages);
        Uri treeUri = currentTreeUri;
        String pinnedUri = currentIndex >= 0 && currentIndex < images.size()
                ? images.get(currentIndex).uri.toString()
                : allImages.get(0).uri.toString();
        updateTopProgress(0);
        batchTask = batchExecutor.submit(
                () -> runFolderTranslation(snapshot, treeUri, pinnedUri, forceOverwrite));
    }

    private void requestStopBatch() {
        if (!batchRunning || batchStopRequested) return;
        batchStopRequested = true;
        synchronized (rateLimitPauseLock) {
            rateLimitPaused = false;
            rateLimitPauseLock.notifyAll();
        }
        stopButton.setEnabled(false);
        stopButton.setText("중지 요청됨");
        Toast.makeText(this, "현재 요청을 마치거나 재시도 대기에서 빠져나온 뒤 중지합니다.", Toast.LENGTH_SHORT).show();
    }

    private TranslationClient.TranslationResult translateWithRateLimitRecovery(
            ImageRepository.ImageEntry entry, String continuityContext, int pageNumber, int totalPages)
            throws Exception {
        TranslationClient.RetryListener listener = new TranslationClient.RetryListener() {
            @Override public void onRateLimitRetry(int attempt, int maxRetries, long remainingSeconds,
                                                   String message) {
                runOnUiThread(() -> showRateLimitRetry(pageNumber, totalPages, attempt,
                        maxRetries, remainingSeconds, message));
            }

            @Override public boolean isCancelled() {
                return batchStopRequested;
            }
        };

        while (!batchStopRequested) {
            try {
                TranslationClient.TranslationResult result = TranslationClient.translate(
                        getContentResolver(), entry.uri, apiSettings, secureStore,
                        continuityContext, listener);
                if (Thread.currentThread().isInterrupted()) {
                    throw new TranslationClient.TranslationCancelledException();
                }
                return result;
            } catch (TranslationClient.RateLimitException rateLimit) {
                if (!pauseForRateLimitAndWait(pageNumber, totalPages, rateLimit)) {
                    throw new TranslationClient.TranslationCancelledException();
                }
            }
        }
        throw new TranslationClient.TranslationCancelledException();
    }

    private boolean pauseForRateLimitAndWait(int pageNumber, int totalPages,
                                             TranslationClient.RateLimitException error) {
        rateLimitPauseMessage = cleanMessage(error);
        synchronized (rateLimitPauseLock) {
            rateLimitPaused = true;
        }
        runOnUiThread(() -> {
            if (!isActivityAlive()) return;
            translateButton.setEnabled(true);
            translateButton.setText("계속하기");
            translateButton.setAlpha(1f);
            tokenBadge.setText("429/503 자동 재시도 한도 도달 · 일시정지\n"
                    + pageNumber + "/" + totalPages + "페이지 · 설정/한도 확인 후 계속하기");
            tokenBadge.setVisibility(View.VISIBLE);
            tokenBadge.bringToFront();
            Toast.makeText(this, "요청 제한(429) 또는 혼잡(503)으로 일시정지했습니다. 한도를 확인한 뒤 계속하기를 누르세요.",
                    Toast.LENGTH_LONG).show();
        });

        synchronized (rateLimitPauseLock) {
            while (rateLimitPaused && !batchStopRequested) {
                try { rateLimitPauseLock.wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !batchStopRequested;
    }

    private void resumeRateLimitPause() {
        if (!batchRunning || !rateLimitPaused) return;
        synchronized (rateLimitPauseLock) {
            rateLimitPaused = false;
            rateLimitPauseLock.notifyAll();
        }
        translateButton.setEnabled(false);
        translateButton.setText("폴더 번역 중…");
        translateButton.setAlpha(0.75f);
        tokenBadge.setText("429 일시정지 해제 · 같은 페이지 다시 시도 중");
        tokenBadge.setVisibility(View.VISIBLE);
    }

    private void showRateLimitRetry(int pageNumber, int totalPages, int attempt,
                                    int maxRetries, long remainingSeconds, String message) {
        if (!isActivityAlive() || !batchRunning || batchStopRequested || rateLimitPaused) return;
        String clean = message == null ? "" : message.trim();
        if (clean.length() > 80) clean = clean.substring(0, 80) + "…";
        StringBuilder value = new StringBuilder();
        value.append("429/503 · ").append(remainingSeconds).append("초 후 자동 재시도")
                .append(" (").append(attempt).append("/").append(maxRetries).append(")")
                .append("\n").append(pageNumber).append("/").append(totalPages).append("페이지");
        if (!clean.isEmpty()) value.append(" · ").append(clean);
        tokenBadge.setText(value.toString());
        tokenBadge.setVisibility(View.VISIBLE);
        tokenBadge.bringToFront();
    }

    /** True when this page will need an API call (not already current, not served from cache). */
    private boolean pageNeedsApiCall(ImageRepository.ImageEntry entry, Map<String, String> outputNames,
                                     Map<String, Uri> knownOutputs, TranslationManifest manifest,
                                     boolean forceOverwrite) {
        String outputName = outputNames.get(entry.uri.toString());
        Uri existing = knownOutputs.get(outputName);
        if (!forceOverwrite && existing != null
                && manifest.isCurrent(entry, outputName, outputFingerprint(entry))) {
            return false;
        }
        if (forceOverwrite) return true;
        TranslationClient.TranslationResult cached = translationCache.get(cacheKeyFor(entry));
        return cached == null || cached.blocks.isEmpty();
    }

    private void runFolderTranslation(List<ImageRepository.ImageEntry> snapshot, Uri treeUri,
                                      String pinnedUri, boolean forceOverwrite) {
        PageRequestPipeline<TranslationClient.TranslationResult> prefetcher = null;
        int successCount = 0;
        int failureCount = 0;
        int cacheCount = 0;
        int skippedCount = 0;
        long inputTokens = 0L;
        long outputTokens = 0L;
        long totalTokens = 0L;
        boolean hasUsage = false;
        boolean stopped = false;
        List<String> failures = new ArrayList<>();
        try {
            TranslationClient.validateConfiguration(apiSettings, secureStore);
            Uri outputDir = ImageRepository.ensureSubdirectory(getContentResolver(), treeUri, OUTPUT_DIR_NAME);
            ImageRepository.verifyWritableDirectory(getContentResolver(), outputDir);
            outputDirectoryUri = outputDir;
            TranslationMemory memory = TranslationMemory.load(getContentResolver(), outputDir);
            translationMemory = memory;
            TranslationManifest manifest = TranslationManifest.load(getContentResolver(), outputDir);
            translationManifest = manifest;
            Map<String, Uri> knownOutputs = ImageRepository.listImageDocumentsByName(
                    getContentResolver(), outputDir);
            Map<String, String> outputNames = outputNamesFor(snapshot);
            // Up to N pages are requested at once; each request captures the context memory as it
            // is when that request starts. Saving and memory updates below stay in page order.
            final int totalPages = snapshot.size();
            prefetcher = new PageRequestPipeline<TranslationClient.TranslationResult>(
                    apiSettings.concurrentRequests(), totalPages,
                    index -> pageNeedsApiCall(snapshot.get(index), outputNames, knownOutputs,
                            manifest, forceOverwrite),
                    index -> {
                        ImageRepository.ImageEntry page = snapshot.get(index);
                        String context = memory.buildContextPrompt();
                        return () -> translateWithRateLimitRecovery(page, context, index + 1, totalPages);
                    },
                    () -> batchStopRequested);
            for (int i = 0; i < snapshot.size(); i++) {
                if (batchStopRequested) {
                    stopped = true;
                    break;
                }
                final int displayIndex = i;
                ImageRepository.ImageEntry entry = snapshot.get(i);
                processingImageUri = entry.uri.toString();
                processingPageNumber = displayIndex + 1;
                runOnUiThread(() -> {
                    if (!isActivityAlive()) return;
                    String preferred = currentVisibleUri();
                    if (translatedOnlyMode) {
                        boolean followNewest = images.isEmpty() || currentIndex == images.size() - 1;
                        updateTranslatedOnlyListLive(preferred, followNewest, null);
                    } else {
                        updateTopProgress(processingPageNumber);
                        if (entry.uri.toString().equals(preferred)) renderCurrent();
                    }
                });
                try {
                    prefetcher.topUp(displayIndex);
                    String outputName = outputNames.get(entry.uri.toString());
                    Uri existingOutput = knownOutputs.get(outputName);
                    String fingerprint = outputFingerprint(entry);
                    if (!forceOverwrite && existingOutput != null
                            && manifest.isCurrent(entry, outputName, fingerprint)) {
                        prefetcher.discard(displayIndex);
                        translatedOutputMap.put(entry.uri.toString(), existingOutput);
                        String restoredText = manifest.resultText(entry, fingerprint);
                        if (!restoredText.isEmpty()) {
                            translationCache.put(cacheKeyFor(entry),
                                    new TranslationClient.TranslationResult(
                                            restoredText, null, null, null));
                        }
                        skippedCount++;
                        final int fSuccess = successCount;
                        final int fFailure = failureCount;
                        final int fCache = cacheCount;
                        final int fSkipped = skippedCount;
                        final long fInput = inputTokens;
                        final long fOutput = outputTokens;
                        final long fTotal = totalTokens;
                        final boolean fUsage = hasUsage;
                        runOnUiThread(() -> {
                            if (!isActivityAlive()) return;
                            showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                    fSuccess, fFailure, fCache, fSkipped, fUsage, fInput, fOutput, fTotal);
                            refreshLiveView(currentVisibleUri(), entry.uri.toString());
                        });
                        continue;
                    }

                    String key = cacheKeyFor(entry);
                    TranslationClient.TranslationResult result = forceOverwrite
                            ? null : translationCache.get(key);
                    if (result == null || result.blocks.isEmpty()) {
                        result = prefetcher.take(displayIndex);
                    } else {
                        prefetcher.discard(displayIndex);
                        cacheCount++;
                    }
                    translationCache.put(key, result);

                    Bitmap source = null;
                    Bitmap composed = null;
                    Uri savedUri;
                    try {
                        source = decodeForOutput(entry.uri);
                        composed = TranslatedImageRenderer.compose(source, result.blocks);
                        try {
                            savedUri = ImageRepository.saveBitmapKnown(
                                    getContentResolver(), outputDir, outputName, composed, existingOutput);
                        } catch (Exception saveError) {
                            throw new BatchStorageException(
                                    "번역 이미지 저장 실패: " + entry.name, saveError);
                        }
                    } finally {
                        if (composed != null && composed != source && !composed.isRecycled()) {
                            composed.recycle();
                        }
                        if (source != null && !source.isRecycled()) source.recycle();
                    }
                    memory.applyResult(entry.name, result);
                    try {
                        memory.save(getContentResolver(), outputDir);
                    } catch (Exception memorySaveError) {
                        throw new BatchStorageException("문맥 파일 저장 실패", memorySaveError);
                    }
                    try {
                        manifest.record(entry, outputName, fingerprint, result.text);
                        manifest.save(getContentResolver(), outputDir);
                    } catch (Exception manifestSaveError) {
                        throw new BatchStorageException("번역 manifest 저장 실패", manifestSaveError);
                    }
                    knownOutputs.put(outputName, savedUri);
                    translatedOutputMap.put(entry.uri.toString(), savedUri);
                    successCount++;

                    if (result.usage != null && result.usage.isAvailable()) {
                        if (result.usage.inputTokens >= 0) inputTokens += result.usage.inputTokens;
                        if (result.usage.outputTokens >= 0) outputTokens += result.usage.outputTokens;
                        if (result.usage.totalTokens >= 0) totalTokens += result.usage.totalTokens;
                        hasUsage = true;
                    }

                    final int fSuccess = successCount;
                    final int fFailure = failureCount;
                    final int fCache = cacheCount;
                    final int fSkipped = skippedCount;
                    final long fInput = inputTokens;
                    final long fOutput = outputTokens;
                    final long fTotal = totalTokens;
                    final boolean fUsage = hasUsage;
                    runOnUiThread(() -> {
                        if (!isActivityAlive()) return;
                        showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                fSuccess, fFailure, fCache, fSkipped, fUsage, fInput, fOutput, fTotal);
                        refreshLiveView(currentVisibleUri(), entry.uri.toString());
                    });
                } catch (TranslationClient.TranslationCancelledException cancelled) {
                    stopped = true;
                    break;
                } catch (BatchStorageException storageError) {
                    throw storageError;
                } catch (TranslationClient.HttpStatusException httpError) {
                    if (httpError.statusCode == 400 || httpError.statusCode == 401
                            || httpError.statusCode == 402 || httpError.statusCode == 403
                            || httpError.statusCode == 404 || httpError.statusCode == 422) {
                        throw httpError;
                    }
                    failureCount++;
                    failures.add(entry.name + "\n• " + cleanMessage(httpError));
                    final int fSuccess = successCount;
                    final int fFailure = failureCount;
                    final int fCache = cacheCount;
                    final int fSkipped = skippedCount;
                    final long fInput = inputTokens;
                    final long fOutput = outputTokens;
                    final long fTotal = totalTokens;
                    final boolean fUsage = hasUsage;
                    runOnUiThread(() -> {
                        if (!isActivityAlive()) return;
                        showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                fSuccess, fFailure, fCache, fSkipped, fUsage,
                                fInput, fOutput, fTotal);
                        refreshLiveView(currentVisibleUri(), entry.uri.toString());
                    });
                } catch (Exception imageError) {
                    // Settings can be edited while a 429 pause is active. If credentials or a
                    // required model were removed, stop here instead of repeating the same
                    // configuration failure (and image encoding work) for every remaining page.
                    try {
                        TranslationClient.validateConfiguration(apiSettings, secureStore);
                    } catch (Exception configurationError) {
                        throw configurationError;
                    }
                    failureCount++;
                    failures.add(entry.name + "\n• " + cleanMessage(imageError));
                    final int fSuccess = successCount;
                    final int fFailure = failureCount;
                    final int fCache = cacheCount;
                    final int fSkipped = skippedCount;
                    final long fInput = inputTokens;
                    final long fOutput = outputTokens;
                    final long fTotal = totalTokens;
                    final boolean fUsage = hasUsage;
                    runOnUiThread(() -> {
                        if (!isActivityAlive()) return;
                        showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                fSuccess, fFailure, fCache, fSkipped, fUsage, fInput, fOutput, fTotal);
                        refreshLiveView(currentVisibleUri(), entry.uri.toString());
                    });
                }
            }

            Map<String, Uri> currentOutputs = new HashMap<>();
            Map<String, TranslationClient.TranslationResult> currentResults = new HashMap<>();
            for (ImageRepository.ImageEntry entry : snapshot) {
                String outputName = outputNames.get(entry.uri.toString());
                Uri outputUri = knownOutputs.get(outputName);
                String fingerprint = outputFingerprint(entry);
                if (outputUri == null || !manifest.isCurrent(entry, outputName, fingerprint)) continue;
                currentOutputs.put(entry.uri.toString(), outputUri);
                String resultText = manifest.resultText(entry, fingerprint);
                if (!resultText.isEmpty()) {
                    currentResults.put(cacheKeyFor(entry),
                            new TranslationClient.TranslationResult(resultText, null, null, null));
                }
            }
            final int finalSuccessCount = successCount;
            final int finalFailureCount = failureCount;
            final int finalCacheCount = cacheCount;
            final int finalSkippedCount = skippedCount;
            final long finalInputTokens = inputTokens;
            final long finalOutputTokens = outputTokens;
            final long finalTotalTokens = totalTokens;
            final boolean finalHasUsage = hasUsage;
            final boolean finalStopped = stopped;
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                String visibleBeforeFinish = currentVisibleUri();
                translatedOutputMap.clear();
                translatedOutputMap.putAll(currentOutputs);
                translationCache.clear();
                translationCache.putAll(currentResults);
                processingImageUri = null;
                processingPageNumber = 0;
                finishBatchUi();
                applyViewMode(visibleBeforeFinish != null ? visibleBeforeFinish : pinnedUri);
                int processedCount = finalSuccessCount + finalFailureCount + finalSkippedCount;
                StringBuilder summary = new StringBuilder();
                summary.append("선택한 폴더 전체 ").append(snapshot.size()).append("장 중 ")
                        .append(processedCount).append("장을 확인했습니다.");
                if (finalStopped) {
                    summary.append("\n\n사용자 요청으로 중간에 번역을 멈췄습니다.");
                }
                summary.append("\n\n")
                        .append("새로 번역해 저장: ").append(finalSuccessCount).append("장\n")
                        .append("이미 번역되어 건너뜀: ").append(finalSkippedCount).append("장\n")
                        .append("캐시 사용: ").append(finalCacheCount).append("장\n")
                        .append("실패: ").append(finalFailureCount).append("장\n\n")
                        .append("문맥 메모리: 인물 ").append(memory.characterCount())
                        .append(" · 용어 ").append(memory.termCount())
                        .append(" · 호칭 ").append(memory.honorificCount()).append("\n")
                        .append("저장 위치: 선택한 폴더 안 ‘").append(OUTPUT_DIR_NAME).append("’ 폴더\n")
                        .append("문맥 파일: ").append(TranslationMemory.FILE_NAME).append("\n")
                        .append("출력 형식: 원문 위에 반투명 박스와 번역문이 합성된 PNG 파일");
                if (!failures.isEmpty()) {
                    summary.append("\n\n실패한 파일\n");
                    int limit = Math.min(4, failures.size());
                    for (int i = 0; i < limit; i++) {
                        if (i > 0) summary.append("\n\n");
                        summary.append(failures.get(i));
                    }
                    if (failures.size() > limit) {
                        summary.append("\n\n그 외 ").append(failures.size() - limit).append("건 더 있음");
                    }
                }
                showResult(finalStopped ? "폴더 번역 중지됨" : "폴더 번역 완료",
                        summary.toString(), finalFailureCount > 0);
                showBatchTokenProgress(processedCount, snapshot.size(), finalSuccessCount, finalFailureCount,
                        finalCacheCount, finalSkippedCount, finalHasUsage,
                        finalInputTokens, finalOutputTokens, finalTotalTokens);
                Toast.makeText(this,
                        finalStopped ? "번역을 중지했습니다." : "폴더 번역이 완료되었습니다.",
                        Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            String errorTitle;
            String errorMessage;
            if (e instanceof BatchStorageException) {
                errorTitle = "저장 오류 · 전체 번역 중단";
                errorMessage = e.getMessage() + "\n" + cleanMessage(e.getCause());
            } else if (e instanceof TranslationClient.HttpStatusException
                    && ((((TranslationClient.HttpStatusException) e).statusCode == 401)
                    || ((TranslationClient.HttpStatusException) e).statusCode == 403)) {
                errorTitle = "인증 오류 · 전체 번역 중단";
                errorMessage = cleanMessage(e);
            } else {
                errorTitle = providerLabel() + " 오류";
                errorMessage = cleanMessage(e);
            }
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                processingImageUri = null;
                processingPageNumber = 0;
                finishBatchUi();
                updateTopProgress(0);
                showResult(errorTitle, errorMessage, true);
                tokenBadge.setVisibility(View.GONE);
            });
        } finally {
            // Stops requests started ahead for pages the batch will not reach (stop, fatal error).
            if (prefetcher != null) prefetcher.shutdown();
        }
    }

    private static final class BatchStorageException extends Exception {
        BatchStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void finishBatchUi() {
        batchRunning = false;
        batchStopRequested = false;
        synchronized (rateLimitPauseLock) {
            rateLimitPaused = false;
            rateLimitPauseMessage = "";
            rateLimitPauseLock.notifyAll();
        }
        processingImageUri = null;
        processingPageNumber = 0;
        progressBar.setVisibility(View.GONE);
        translateButton.setEnabled(true);
        translateButton.setText("폴더 번역");
        translateButton.setAlpha(1f);
        stopButton.setVisibility(View.GONE);
        stopButton.setEnabled(true);
        stopButton.setText("번역 중지");
        modeButton.setEnabled(true);
        modeButton.setAlpha(1f);
        copyButton.setVisibility(currentTranslationText.isEmpty() ? View.GONE : View.VISIBLE);
        updateTopProgress(0);
    }

    private void showBatchTokenProgress(int completed, int total,
                                        int successCount, int failureCount, int cacheCount, int skippedCount,
                                        boolean hasUsage, long inputTokens, long outputTokens, long totalTokens) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        StringBuilder text = new StringBuilder();
        text.append(completed).append("/").append(total).append("  ·  저장 ").append(successCount);
        if (skippedCount > 0) text.append("  ·  건너뜀 ").append(skippedCount);
        if (failureCount > 0) text.append("  ·  실패 ").append(failureCount);
        if (cacheCount > 0) text.append("  ·  캐시 ").append(cacheCount);
        if (batchStopRequested) text.append("\n중지 요청됨");
        if (hasUsage) {
            text.append(batchStopRequested ? "  ·  " : "\n");
            text.append("누적 총 ").append(format.format(totalTokens >= 0 ? totalTokens : inputTokens + outputTokens));
            if (inputTokens > 0 || outputTokens > 0) {
                text.append("  ·  입력 ").append(format.format(inputTokens));
                text.append(" / 출력 ").append(format.format(outputTokens));
            }
        }
        TranslationMemory memory = translationMemory;
        if (memory != null) {
            int memoryItems = memory.characterCount() + memory.termCount() + memory.honorificCount();
            if (memoryItems > 0) text.append("\n문맥 항목 ").append(memoryItems);
        }
        tokenBadge.setText(text.toString());
        tokenBadge.setVisibility(View.VISIBLE);
        tokenBadge.bringToFront();
    }

    private void showResult(String title, String text, boolean error) {
        translationTitle.setText(title);
        translationTitle.setTextColor(error ? 0xFFFFA3A3 : 0xFFBFD5FF);
        translationText.setText(text);
        showTranslationPanel();
    }

    private void showTranslationPanel() {
        if (translationPanel == null) return;
        translationPanel.animate().cancel();
        if (translationPanel.getVisibility() != View.VISIBLE) {
            translationPanel.setAlpha(0f);
            translationPanel.setTranslationY(dp(18));
            translationPanel.setVisibility(View.VISIBLE);
        }
        translationPanel.animate().alpha(1f).translationY(0f).setDuration(190L).start();
    }

    private void hideTranslationPanel() {
        if (translationPanel == null || translationPanel.getVisibility() != View.VISIBLE) return;
        translationPanel.animate().cancel();
        translationPanel.animate().alpha(0f).translationY(dp(18)).setDuration(150L)
                .withEndAction(() -> {
                    translationPanel.setVisibility(View.GONE);
                    translationPanel.setAlpha(1f);
                    translationPanel.setTranslationY(0f);
                }).start();
    }

    private void copyTranslation() {
        CharSequence value = currentTranslationText;
        if (value == null || value.length() == 0) {
            Toast.makeText(this, "복사할 번역문이 아직 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ImageLingo 번역", value));
        Toast.makeText(this, "현재 이미지의 번역문을 복사했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void showSettingsDialog() {
        if (batchRunning && !rateLimitPaused) {
            Toast.makeText(this, "번역 중에는 설정을 바꿀 수 없습니다. 429 일시정지 상태에서는 변경할 수 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(22));
        scroll.addView(form);

        TextView intro = caption("API와 모델을 선택하고, 번역 방식은 시스템 프롬프트에서 자유롭게 조정할 수 있습니다.");
        form.addView(intro, matchWrap());

        form.addView(section("사용할 API"));
        Spinner provider = new Spinner(this);
        String[] providerNames = {"Gemini API", "OpenAI Responses API", "Vertex AI · 서비스 계정 JSON"};
        provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providerNames));
        provider.setSelection(ApiSettings.OPENAI.equals(apiSettings.provider()) ? 1
                : ApiSettings.VERTEX.equals(apiSettings.provider()) ? 2 : 0);
        provider.setContentDescription("사용할 번역 API 제공자");
        provider.setBackground(roundedBackground(0xFFF1F2F7, 12, 0xFFD5D8E2));
        provider.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        form.addView(provider, spinnerParams);

        form.addView(section("OpenAI"));
        EditText openAiKey = secretField("API 키 · sk-…", secureStore.get("openai_key"));
        EditText openAiModel = field("모델명", apiSettings.openAiModel());
        EditText openAiEndpoint = field("Responses 엔드포인트", apiSettings.openAiEndpoint());
        form.addView(openAiKey, fieldParams());
        form.addView(openAiModel, fieldParams());
        form.addView(openAiEndpoint, fieldParams());
        form.addView(caption("사용자 지정 엔드포인트에는 API 키 인증 헤더, 이미지, 프롬프트와 폴더 문맥이 전송됩니다. 신뢰하는 HTTPS 호스트만 사용하세요."),
                matchWrap());

        form.addView(section("OpenAI 서비스 티어"));
        form.addView(caption("Standard는 지연이 짧은 일반 처리, Flex는 비용을 줄이는 대신 혼잡할 때 대기할 수 있습니다."),
                matchWrap());
        Spinner serviceTier = new Spinner(this);
        String[] serviceTierNames = {
                "Standard · 빠른 일반 처리",
                "Flex · 비용 절약 · 대기 가능"
        };
        serviceTier.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, serviceTierNames));
        serviceTier.setSelection(apiSettings.usesFlexTier() ? 1 : 0);
        serviceTier.setContentDescription("OpenAI 서비스 티어");
        serviceTier.setBackground(roundedBackground(0xFFF1F2F7, 12, 0xFFD5D8E2));
        serviceTier.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout.LayoutParams tierParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        tierParams.setMargins(0, dp(4), 0, dp(4));
        form.addView(serviceTier, tierParams);

        form.addView(section("Gemini API"));
        EditText geminiKey = secretField("API 키 · AIza…", secureStore.get("gemini_key"));
        EditText geminiModel = field("모델명", apiSettings.geminiModel());
        EditText geminiProxy = field("리버스 프록시 URL · 비우면 공식 Google 주소", apiSettings.geminiProxyUrl());
        geminiProxy.setContentDescription("Gemini 리버스 프록시 URL");
        form.addView(geminiKey, fieldParams());
        form.addView(geminiModel, fieldParams());
        form.addView(geminiProxy, fieldParams());
        form.addView(caption("프록시를 쓰면 요청이 그 주소로 갑니다. 예: https://내프록시/google → /v1beta/models/모델:generateContent를 자동으로 붙입니다. "
                        + "프록시 비밀번호나 키는 위 API 키 칸에 넣으세요. 프록시 운영자는 이미지·프롬프트·키를 볼 수 있으니 신뢰하는 HTTPS 주소만 쓰세요."),
                matchWrap());

        form.addView(section("Gemini 처리 티어 · 3.8 Flash"));
        form.addView(caption("gemini-3.8-flash에만 적용됩니다(Gemini API·Vertex AI). Flex는 비용이 절반이지만 응답에 몇 분 걸릴 수 있고, 혼잡하면 자동으로 다시 시도합니다. "
                        + "Priority는 더 빠르고 비싸며(Gemini API는 결제 Tier 2 이상), 여유가 없으면 Standard로 처리됩니다. Vertex AI에서 Flex·Priority는 global 리전만 됩니다."),
                matchWrap());
        Spinner geminiTier = new Spinner(this);
        String[] geminiTierValues = {
                ApiSettings.GEMINI_TIER_STANDARD, ApiSettings.GEMINI_TIER_FLEX, ApiSettings.GEMINI_TIER_PRIORITY
        };
        String[] geminiTierNames = {
                "Standard · 기본",
                "Flex · 50% 저렴 · 느림",
                "Priority · 빠름 · 비쌈"
        };
        geminiTier.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, geminiTierNames));
        String savedGeminiTier = apiSettings.geminiServiceTier();
        for (int i = 0; i < geminiTierValues.length; i++) {
            if (geminiTierValues[i].equals(savedGeminiTier)) geminiTier.setSelection(i);
        }
        geminiTier.setContentDescription("Gemini 3.8 Flash 처리 티어");
        geminiTier.setBackground(roundedBackground(0xFFF1F2F7, 12, 0xFFD5D8E2));
        geminiTier.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout.LayoutParams geminiTierParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        geminiTierParams.setMargins(0, dp(4), 0, dp(4));
        form.addView(geminiTier, geminiTierParams);

        form.addView(section("Vertex AI"));
        EditText vertexModel = field("모델명", apiSettings.vertexModel());
        EditText vertexLocation = field("리전 · global 권장, us 또는 eu", apiSettings.vertexLocation());
        EditText vertexProject = field("프로젝트 ID", apiSettings.vertexProject());
        form.addView(vertexModel, fieldParams());
        form.addView(vertexLocation, fieldParams());
        form.addView(vertexProject, fieldParams());
        Button importJson = makeDialogButton("서비스 계정 JSON 불러오기");
        importJson.setOnClickListener(v -> chooseVertexJson());
        form.addView(importJson, fieldParams());
        vertexStatusText = caption(secureStore.get("vertex_json").isEmpty()
                ? "아직 JSON을 불러오지 않았습니다."
                : "서비스 계정 JSON이 암호화되어 저장되어 있습니다.");
        form.addView(vertexStatusText, matchWrap());

        form.addView(section("추론 단계 · Argon / 3.8 Flash"));
        form.addView(caption("Gemini API와 Vertex AI에서 gemini-4-argon 또는 gemini-3.8-flash를 쓸 때만 적용됩니다. 높을수록 정확도가 오를 수 있지만 느려지고 토큰 비용이 늘어납니다."),
                matchWrap());
        Spinner thinkingLevel = new Spinner(this);
        String[] thinkingLevelValues = {
                ApiSettings.THINKING_LOW, ApiSettings.THINKING_MEDIUM, ApiSettings.THINKING_HIGH
        };
        String[] thinkingLevelNames = {
                "Low · 빠르고 저렴",
                "Medium · 기본값",
                "High · 깊게 추론 · 느림"
        };
        thinkingLevel.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, thinkingLevelNames));
        String savedThinkingLevel = apiSettings.thinkingLevel();
        for (int i = 0; i < thinkingLevelValues.length; i++) {
            if (thinkingLevelValues[i].equals(savedThinkingLevel)) thinkingLevel.setSelection(i);
        }
        thinkingLevel.setContentDescription("Gemini 추론 단계");
        thinkingLevel.setBackground(roundedBackground(0xFFF1F2F7, 12, 0xFFD5D8E2));
        thinkingLevel.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout.LayoutParams thinkingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        thinkingParams.setMargins(0, dp(4), 0, dp(4));
        form.addView(thinkingLevel, thinkingParams);

        form.addView(section("번역 속도 · 동시 요청"));
        form.addView(caption("폴더 번역 때 여러 페이지를 한꺼번에 API로 보냅니다. 3장이면 가장 빠르지만, 같이 보낸 페이지끼리는 서로의 이름·용어를 문맥으로 못 받고 분당 요청 한도(429)에 더 빨리 닿습니다. 저장 순서는 그대로입니다."),
                matchWrap());
        Spinner concurrency = new Spinner(this);
        String[] concurrencyNames = {
                "1장씩 · 문맥 일관성 최우선",
                "2장 동시",
                "3장 동시 · 가장 빠름"
        };
        concurrency.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, concurrencyNames));
        concurrency.setSelection(apiSettings.concurrentRequests() - 1);
        concurrency.setContentDescription("동시 요청 페이지 수");
        concurrency.setBackground(roundedBackground(0xFFF1F2F7, 12, 0xFFD5D8E2));
        concurrency.setPadding(dp(12), 0, dp(8), 0);
        LinearLayout.LayoutParams concurrencyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        concurrencyParams.setMargins(0, dp(4), 0, dp(4));
        form.addView(concurrency, concurrencyParams);

        form.addView(section("저장된 인증 정보"));
        form.addView(caption("OpenAI·Gemini API 키와 Vertex 서비스 계정 JSON을 이 기기에서 모두 삭제합니다."),
                matchWrap());
        Button deleteCredentials = makeDialogButton("저장된 인증 정보 모두 삭제");
        deleteCredentials.setContentDescription("저장된 모든 API 인증 정보 삭제");
        deleteCredentials.setOnClickListener(v ->
                confirmDeleteAllCredentials(openAiKey, geminiKey));
        form.addView(deleteCredentials, fieldParams());

        form.addView(section("시스템 프롬프트"));
        form.addView(caption("번역 어조, 고유명사 처리, 출력 형식 등을 지정합니다. OpenAI의 instructions와 Gemini·Vertex의 system instruction으로 전송됩니다."), matchWrap());
        form.addView(caption("원문 위에 반투명 박스와 번역문을 합성하기 위해, 앱이 위치 좌표 JSON 출력을 자동으로 추가합니다."), matchWrap());
        EditText systemPrompt = field("비워 두면 기본 만화·게임 번역 프롬프트 사용", apiSettings.systemPrompt());
        systemPrompt.setMinLines(6);
        systemPrompt.setGravity(Gravity.TOP | Gravity.START);
        systemPrompt.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        promptParams.setMargins(0, dp(8), 0, dp(8));
        form.addView(systemPrompt, promptParams);
        Button fillDefault = makeDialogButton("기본 시스템 프롬프트 채우기");
        fillDefault.setOnClickListener(v -> systemPrompt.setText(TranslationClient.defaultSystemPrompt()));
        form.addView(fillDefault, fieldParams());

        form.addView(section("문맥 메모리"));
        TranslationMemory currentMemory = translationMemory;
        String memoryDescription = currentMemory == null
                ? "폴더를 선택하면 이름·고유명사·높임체·호칭을 폴더별로 자동 저장합니다."
                : "인물 " + currentMemory.characterCount() + " · 용어 " + currentMemory.termCount()
                + " · 호칭 " + currentMemory.honorificCount() + "\n" + currentMemory.summaryPreview();
        TextView memoryStatus = caption(memoryDescription);
        form.addView(memoryStatus, matchWrap());
        Button viewMemory = makeDialogButton("현재 문맥 규칙 보기");
        viewMemory.setOnClickListener(v -> showTranslationMemoryDialog());
        form.addView(viewMemory, fieldParams());
        Button resetMemory = makeDialogButton("현재 폴더 문맥 초기화");
        resetMemory.setOnClickListener(v -> confirmResetTranslationMemory(memoryStatus));
        form.addView(resetMemory, fieldParams());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("AI 번역 설정")
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        final String[] confirmedCustomEndpoint = {null};
        final String[] confirmedGeminiProxy = {apiSettings.geminiProxyUrl().trim()};
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String providerValue = provider.getSelectedItemPosition() == 1 ? ApiSettings.OPENAI
                        : provider.getSelectedItemPosition() == 2 ? ApiSettings.VERTEX : ApiSettings.GEMINI;
                String endpointValue = openAiEndpoint.getText().toString().trim();
                if (ApiSettings.OPENAI.equals(providerValue)) {
                    String host = NetworkPolicy.requireHttps(endpointValue).getHost();
                    if (!NetworkPolicy.isOfficialOpenAiEndpoint(endpointValue)
                            && !endpointValue.equals(confirmedCustomEndpoint[0])) {
                        new AlertDialog.Builder(this)
                                .setTitle("사용자 지정 엔드포인트 확인")
                                .setMessage(host + " 호스트로 API 키 인증 헤더, 이미지, 프롬프트와 폴더 문맥을 전송합니다. 이 호스트를 신뢰할 때만 계속하세요.")
                                .setNegativeButton("취소", null)
                                .setPositiveButton("신뢰하고 저장", (warning, which) -> {
                                    confirmedCustomEndpoint[0] = endpointValue;
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                                })
                                .show();
                        return;
                    }
                }
                String proxyValue = geminiProxy.getText().toString().trim();
                if (!proxyValue.isEmpty()) {
                    // Rejects http://, ?query and malformed URLs before anything is saved.
                    TranslationClient.geminiEndpoint(proxyValue, "gemini-3.8-flash");
                    if (!proxyValue.equals(confirmedGeminiProxy[0])) {
                        String proxyHost = NetworkPolicy.requireHttps(proxyValue).getHost();
                        new AlertDialog.Builder(this)
                                .setTitle("Gemini 프록시 확인")
                                .setMessage(proxyHost + " 호스트로 Gemini API 키, 이미지, 프롬프트와 폴더 문맥을 전송합니다. 이 프록시를 신뢰할 때만 계속하세요.")
                                .setNegativeButton("취소", null)
                                .setPositiveButton("신뢰하고 저장", (warning, which) -> {
                                    confirmedGeminiProxy[0] = proxyValue;
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                                })
                                .show();
                        return;
                    }
                }
                secureStore.put("openai_key", openAiKey.getText().toString().trim());
                secureStore.put("gemini_key", geminiKey.getText().toString().trim());
                apiSettings.save(providerValue,
                        openAiModel.getText().toString(), endpointValue,
                        geminiModel.getText().toString(), vertexModel.getText().toString(),
                        vertexLocation.getText().toString(), vertexProject.getText().toString(),
                        systemPrompt.getText().toString());
                apiSettings.setServiceTier(serviceTier.getSelectedItemPosition() == 1
                        ? ApiSettings.SERVICE_TIER_FLEX : ApiSettings.SERVICE_TIER_STANDARD);
                int thinkingIndex = thinkingLevel.getSelectedItemPosition();
                apiSettings.setThinkingLevel(thinkingIndex >= 0 && thinkingIndex < thinkingLevelValues.length
                        ? thinkingLevelValues[thinkingIndex] : ApiSettings.THINKING_MEDIUM);
                int geminiTierIndex = geminiTier.getSelectedItemPosition();
                apiSettings.setGeminiServiceTier(geminiTierIndex >= 0 && geminiTierIndex < geminiTierValues.length
                        ? geminiTierValues[geminiTierIndex] : ApiSettings.GEMINI_TIER_STANDARD);
                apiSettings.setGeminiProxyUrl(proxyValue);
                apiSettings.setConcurrentRequests(concurrency.getSelectedItemPosition() + 1);
                translationCache.clear();
                if (batchRunning && rateLimitPaused) {
                    tokenBadge.setText("설정 저장 완료 · 계속하기를 누르면 같은 페이지부터 재시도합니다.");
                    tokenBadge.setVisibility(View.VISIBLE);
                    stopButton.setVisibility(View.VISIBLE);
                    translateButton.setEnabled(true);
                    translateButton.setText("계속하기");
                    translateButton.setAlpha(1f);
                } else {
                    tokenBadge.setVisibility(View.GONE);
                    copyButton.setVisibility(View.GONE);
                    stopButton.setVisibility(View.GONE);
                    currentTranslationText = "";
                    Uri treeToReload = currentTreeUri;
                    if (treeToReload != null) {
                        dialog.dismiss();
                        loadFolder(treeToReload);
                        Toast.makeText(this, "설정을 저장하고 출력 현재성을 다시 확인합니다.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (currentIndex >= 0) renderCurrent();
                }
                Toast.makeText(this, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (Exception e) {
                Toast.makeText(this, "저장 오류: " + cleanMessage(e), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.setOnDismissListener(ignored -> vertexStatusText = null);
        dialog.show();
    }

    private void confirmDeleteAllCredentials(EditText openAiKey, EditText geminiKey) {
        new AlertDialog.Builder(this)
                .setTitle("인증 정보 모두 삭제")
                .setMessage("저장된 OpenAI·Gemini API 키와 Vertex 서비스 계정 JSON을 모두 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("모두 삭제", (dialog, which) -> {
                    secureStore.clearAll();
                    TranslationClient.clearCachedCredentials();
                    translationCache.clear();
                    openAiKey.setText("");
                    geminiKey.setText("");
                    if (vertexStatusText != null) {
                        vertexStatusText.setText("아직 JSON을 불러오지 않았습니다.");
                    }
                    currentTranslationText = "";
                    copyButton.setVisibility(View.GONE);
                    Toast.makeText(this, "저장된 인증 정보를 모두 삭제했습니다.",
                            Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void showTranslationMemoryDialog() {
        TranslationMemory memory = translationMemory;
        if (memory == null) {
            Toast.makeText(this, "먼저 이미지 폴더를 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject value = memory.copyRoot();
        String formatted;
        try { formatted = value.toString(2); }
        catch (Exception ignored) { formatted = value.toString(); }

        EditText editor = new EditText(this);
        editor.setText(formatted);
        editor.setTextColor(0xFF252833);
        editor.setTextSize(12f);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setPadding(dp(14), dp(12), dp(14), dp(20));
        editor.setMinLines(16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(editor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("현재 폴더 문맥 편집")
                .setMessage("characters, terms, honorifics 값을 수정하면 다음 페이지부터 통일 규칙에 반영됩니다. 줄거리 요약은 사용하지 않습니다.")
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        memory.replaceFromJson(editor.getText().toString());
                        Uri outputDir = outputDirectoryUri;
                        if (outputDir == null) {
                            outputDir = ImageRepository.ensureSubdirectory(
                                    getContentResolver(), currentTreeUri, OUTPUT_DIR_NAME);
                            outputDirectoryUri = outputDir;
                        }
                        memory.save(getContentResolver(), outputDir);
                        translationCache.clear();
                        updateTopProgress(processingPageNumber);
                        Toast.makeText(this, "문맥 메모리를 저장했습니다.", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (Exception e) {
                        Toast.makeText(this, "JSON 저장 오류: " + cleanMessage(e), Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private void confirmResetTranslationMemory(TextView statusView) {
        if (batchRunning) {
            Toast.makeText(this, "번역이 끝난 뒤 문맥을 초기화하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri treeUri = currentTreeUri;
        Uri outputDir = outputDirectoryUri;
        int folderVersion = folderLoadVersion.get();
        if (treeUri == null) {
            Toast.makeText(this, "먼저 이미지 폴더를 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("문맥 메모리 초기화")
                .setMessage("저장된 이름, 고유명사, 높임체, 호칭과 최근 번역 문맥을 모두 지울까요? 번역 이미지 파일은 삭제되지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("초기화", (dialog, which) -> ioExecutor.execute(() -> {
                    if (!isCurrentFolder(treeUri, folderVersion)) return;
                    try {
                        Uri target = outputDir != null ? outputDir
                                : ImageRepository.ensureSubdirectory(
                                        getContentResolver(), treeUri, OUTPUT_DIR_NAME);
                        TranslationMemory.reset(getContentResolver(), target);
                        TranslationMemory reset = TranslationMemory.load(getContentResolver(), target);
                        runOnUiThread(() -> {
                            if (!isActivityAlive() || !isCurrentFolder(treeUri, folderVersion)) return;
                            translationMemory = reset;
                            outputDirectoryUri = target;
                            translationCache.clear();
                            statusView.setText("인물 0 · 용어 0 · 호칭 0\n최근 번역 문맥 없음");
                            updateTopProgress(processingPageNumber);
                            Toast.makeText(this, "문맥 메모리를 초기화했습니다.", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            if (isActivityAlive() && isCurrentFolder(treeUri, folderVersion)) {
                                Toast.makeText(this, "초기화 오류: " + cleanMessage(e),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }))
                .show();
    }

    private boolean isCurrentFolder(Uri treeUri, int version) {
        return treeUri != null && treeUri.equals(currentTreeUri)
                && version == folderLoadVersion.get();
    }

    private String activeModel() {
        if (ApiSettings.OPENAI.equals(apiSettings.provider())) return apiSettings.openAiModel();
        if (ApiSettings.VERTEX.equals(apiSettings.provider())) return apiSettings.vertexModel();
        return apiSettings.geminiModel();
    }

    private String providerLabel() {
        if (ApiSettings.OPENAI.equals(apiSettings.provider())) {
            return "OpenAI · " + (apiSettings.usesFlexTier() ? "Flex" : "Standard");
        }
        String thinking = TranslationClient.supportsThinkingLevel(activeModel())
                ? " · 추론 " + apiSettings.thinkingLevel() : "";
        String tierValue = TranslationClient.geminiTierValue(activeModel(), apiSettings.geminiServiceTier());
        String tier = tierValue == null ? "" : ("flex".equals(tierValue) ? " · Flex" : " · Priority");
        if (ApiSettings.VERTEX.equals(apiSettings.provider())) return "Vertex AI" + tier + thinking;
        String proxy = TranslationClient.usesGeminiProxy(apiSettings.geminiProxyUrl()) ? " · 프록시" : "";
        return "Gemini" + proxy + tier + thinking;
    }

    private String cacheKeyFor(ImageRepository.ImageEntry entry) {
        return outputFingerprint(entry);
    }

    private String outputFingerprint(ImageRepository.ImageEntry entry) {
        String provider = apiSettings.provider();
        String endpoint = ApiSettings.OPENAI.equals(provider) ? apiSettings.openAiEndpoint() : "";
        String serviceTier = ApiSettings.OPENAI.equals(provider) ? apiSettings.serviceTier() : "";
        // Only Gemini 4 / 3.8 Flash requests carry a thinking level, so other models keep their old fingerprints.
        if (!ApiSettings.OPENAI.equals(provider) && TranslationClient.supportsThinkingLevel(activeModel())) {
            serviceTier = "thinking:" + apiSettings.thinkingLevel();
        }
        String project = ApiSettings.VERTEX.equals(provider) ? apiSettings.vertexProject() : "";
        String location = ApiSettings.VERTEX.equals(provider) ? apiSettings.vertexLocation() : "";
        String prompt = apiSettings.systemPrompt();
        if (prompt == null || prompt.trim().isEmpty()) prompt = TranslationClient.defaultSystemPrompt();
        return OutputFingerprint.create(entry.uri.toString(), entry.name, entry.size,
                entry.lastModified, provider, activeModel(), endpoint, serviceTier,
                project, location, prompt, "");
    }

    private String readUtf8(Uri uri, int maxCharacters) throws Exception {
        StringBuilder result = new StringBuilder();
        try (InputStream stream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, read);
                if (result.length() > maxCharacters) throw new IllegalArgumentException("파일이 너무 큽니다.");
            }
        }
        return result.toString();
    }

    private void swapDisplayedBitmap(Bitmap bitmap) {
        Bitmap old = currentBitmap;
        currentBitmap = bitmap;
        imageView.animate().cancel();
        imageView.setAlpha(bitmap == null ? 1f : 0.28f);
        imageView.setImageBitmap(bitmap);
        if (bitmap != null) {
            imageView.animate().alpha(1f).setDuration(170L).start();
        }
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    private Map<String, String> outputNamesFor(List<ImageRepository.ImageEntry> entries) {
        Map<String, Integer> baseCounts = new HashMap<>();
        for (ImageRepository.ImageEntry entry : entries) {
            String base = outputBaseName(entry.name).toLowerCase(Locale.ROOT);
            baseCounts.put(base, baseCounts.getOrDefault(base, 0) + 1);
        }
        Map<String, String> result = new HashMap<>();
        for (ImageRepository.ImageEntry entry : entries) {
            String sourceName = entry.name == null || entry.name.trim().isEmpty()
                    ? "image" : entry.name.trim();
            String base = outputBaseName(sourceName);
            boolean collides = baseCounts.getOrDefault(base.toLowerCase(Locale.ROOT), 0) > 1;
            result.put(entry.uri.toString(), collides
                    ? sourceName + ".translated.png" : base + "_translated.png");
        }
        return result;
    }

    private String outputBaseName(String inputName) {
        String sourceName = inputName == null || inputName.trim().isEmpty()
                ? "image" : inputName.trim();
        int dot = sourceName.lastIndexOf('.');
        return dot > 0 ? sourceName.substring(0, dot) : sourceName;
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets(WindowInsets insets,
                                      FrameLayout.LayoutParams imageParams,
                                      FrameLayout.LayoutParams topParams,
                                      FrameLayout.LayoutParams bottomParams,
                                      FrameLayout.LayoutParams panelParams,
                                      FrameLayout.LayoutParams tokenParams,
                                      FrameLayout.LayoutParams copyParams,
                                      FrameLayout.LayoutParams stopParams) {
        int left = Math.max(0, insets.getSystemWindowInsetLeft());
        int top = Math.max(0, insets.getSystemWindowInsetTop());
        int right = Math.max(0, insets.getSystemWindowInsetRight());
        int bottom = Math.max(0, insets.getSystemWindowInsetBottom());
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout != null) {
            left = Math.max(left, cutout.getSafeInsetLeft());
            top = Math.max(top, cutout.getSafeInsetTop());
            right = Math.max(right, cutout.getSafeInsetRight());
            bottom = Math.max(bottom, cutout.getSafeInsetBottom());
        }

        imageParams.topMargin = dp(126) + top;
        imageParams.bottomMargin = dp(84) + bottom;
        int availableHeight = Math.max(1,
                getResources().getDisplayMetrics().heightPixels - top - bottom);
        panelParams.height = calculatePanelHeight(availableHeight);
        topParams.setMargins(dp(12) + left, dp(8) + top, dp(12) + right, 0);
        bottomParams.setMargins(dp(12) + left, 0, dp(12) + right, dp(10) + bottom);
        panelParams.setMargins(dp(8) + left, 0, dp(8) + right, dp(82) + bottom);
        tokenParams.setMargins(0, 0, dp(20) + right, dp(94) + bottom);
        copyParams.setMargins(dp(20) + left, 0, 0, dp(94) + bottom);
        stopParams.setMargins(dp(20) + left, 0, 0, dp(94) + bottom);
    }

    private int calculatePanelHeight(int availableHeight) {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int minimum = landscape ? dp(112) : dp(180);
        int desired = Math.round(availableHeight * (landscape ? 0.54f : 0.48f));
        int viewportCap = Math.max(dp(96), availableHeight - dp(126));
        return Math.min(viewportCap, Math.max(minimum, Math.min(dp(340), desired)));
    }

    private Button makeToolbarButton(String text) {
        Button button = baseButton(text, 13f, COLOR_TEXT);
        button.setBackground(rippleRoundedBackground(COLOR_SURFACE_2, 14, COLOR_STROKE,
                0x28FFFFFF));
        return button;
    }

    private Button makeNavButton(String text) {
        Button button = baseButton(text, 13f, COLOR_TEXT);
        button.setBackground(rippleRoundedBackground(COLOR_SURFACE_2, 16, COLOR_STROKE,
                0x28FFFFFF));
        return button;
    }

    private Button makePrimaryButton(String text) {
        Button button = baseButton(text, 14f, Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_PRIMARY, COLOR_PRIMARY_2});
        background.setCornerRadius(dp(16));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), background, null));
        return button;
    }

    private Button makePanelButton(String text) {
        Button button = baseButton(text, 12f, COLOR_TEXT);
        button.setBackground(rippleRoundedBackground(COLOR_SURFACE_2, 13, COLOR_STROKE,
                0x28FFFFFF));
        return button;
    }

    private Button baseButton(String text, float textSize, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(textSize);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setLetterSpacing(0.01f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setStateListAnimator(null);
        return button;
    }

    private Button makeDialogButton(String text) {
        Button button = baseButton(text, 14f, 0xFF2E3150);
        button.setBackground(rippleRoundedBackground(0xFFE9E7FF, 12, 0xFFC9C4F5,
                0x247867FF));
        return button;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(0xFF262936);
        view.setPadding(0, dp(20), 0, dp(7));
        view.setAccessibilityHeading(true);
        return view;
    }

    private TextView caption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12.5f);
        view.setTextColor(0xFF666B7A);
        view.setLineSpacing(dp(1), 1.1f);
        view.setPadding(0, dp(3), 0, dp(5));
        return view;
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(0xFF8D92A0);
        field.setTextColor(0xFF252833);
        field.setContentDescription(hint);
        field.setText(value == null ? "" : value);
        field.setSingleLine(false);
        field.setTextSize(14f);
        field.setPadding(dp(14), dp(8), dp(14), dp(8));
        field.setBackground(roundedBackground(0xFFF4F5F9, 12, 0xFFD8DBE4));
        return field;
    }

    private EditText secretField(String hint, String value) {
        EditText field = field(hint, value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable roundedBackground(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private RippleDrawable rippleRoundedBackground(int color, int radiusDp, int strokeColor,
                                                    int rippleColor) {
        GradientDrawable content = roundedBackground(color, radiusDp, strokeColor);
        GradientDrawable mask = roundedBackground(Color.WHITE, radiusDp, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private GradientDrawable roundedTopBackground(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float radius = dp(radiusDp);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String cleanMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        return message;
    }

    private void handleBackPressed() {
        if (translationPanel != null && translationPanel.getVisibility() == View.VISIBLE) {
            hideTranslationPanel();
            return;
        }
        if (batchRunning) {
            new AlertDialog.Builder(this)
                    .setTitle("번역이 진행 중입니다")
                    .setMessage("현재 작업을 중지할까요? 완료된 이미지는 그대로 저장됩니다.")
                    .setNegativeButton("계속 번역", null)
                    .setPositiveButton("중지", (dialog, which) -> requestStopBatch())
                    .show();
            return;
        }
        backPressedCallback.setEnabled(false);
        getOnBackPressedDispatcher().onBackPressed();
    }

    @Override protected void onDestroy() {
        folderLoadVersion.incrementAndGet();
        imageLoadVersion.incrementAndGet();
        batchStopRequested = true;
        synchronized (rateLimitPauseLock) {
            rateLimitPaused = false;
            rateLimitPauseLock.notifyAll();
        }
        cancelImageLoadTask();
        Future<?> runningBatch = batchTask;
        batchTask = null;
        if (runningBatch != null && !runningBatch.isDone()) runningBatch.cancel(true);
        displayExecutor.shutdownNow();
        batchExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        if (imageView != null) imageView.setImageDrawable(null);
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        currentBitmap = null;
        super.onDestroy();
    }

    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }
}
