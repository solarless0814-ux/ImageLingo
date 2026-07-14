package com.rarisol.imagelingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int REQUEST_FOLDER = 1001;
    private static final int REQUEST_VERTEX_JSON = 1002;
    private static final String APP_PREFS = "app_state";
    private static final String OUTPUT_DIR_NAME = "ImageLingo_Translated";

    private static final int COLOR_BG = 0xFF080A0F;
    private static final int COLOR_SURFACE = 0xF21A1D27;
    private static final int COLOR_SURFACE_2 = 0xFF232733;
    private static final int COLOR_PRIMARY = 0xFF7867FF;
    private static final int COLOR_PRIMARY_2 = 0xFF4DA8FF;
    private static final int COLOR_TEXT = 0xFFF6F7FB;
    private static final int COLOR_MUTED = 0xFFA8ADBC;
    private static final int COLOR_STROKE = 0xFF343947;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger imageLoadVersion = new AtomicInteger();
    private final Map<String, TranslationClient.TranslationResult> translationCache = new ConcurrentHashMap<>();
    private final Map<String, Uri> translatedOutputMap = new ConcurrentHashMap<>();

    private ZoomImageView imageView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView progressTextView;
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
    private volatile Uri outputDirectoryUri;
    private boolean translatedOnlyMode;
    private String currentTranslationText = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        apiSettings = new ApiSettings(this);
        secureStore = new SecureStore(this);
        appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        translatedOnlyMode = appPrefs.getBoolean("translated_only_mode", false);
        buildUi();

        String savedTree = appPrefs.getString("tree_uri", "");
        if (savedTree != null && !savedTree.isEmpty()) {
            loadFolder(Uri.parse(savedTree));
        } else {
            showWelcome();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(COLOR_BG);
        imageView.setPageSwipeListener(this::movePage);

        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        imageParams.topMargin = dp(104);
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

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.setGravity(Gravity.CENTER);
        titleView = new TextView(this);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(13.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(COLOR_MUTED);
        subtitleView.setTextSize(10.5f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setSingleLine(true);
        progressTextView = new TextView(this);
        progressTextView.setTextColor(0xFF9FC4FF);
        progressTextView.setTextSize(10.5f);
        progressTextView.setTypeface(Typeface.DEFAULT_BOLD);
        progressTextView.setGravity(Gravity.CENTER);
        progressTextView.setSingleLine(true);
        titleGroup.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleGroup.addView(subtitleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleGroup.addView(progressTextView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout rightControls = new LinearLayout(this);
        rightControls.setOrientation(LinearLayout.VERTICAL);
        rightControls.setGravity(Gravity.CENTER);
        Button settingsButton = makeToolbarButton("설정");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        modeButton = makeToolbarButton(translatedOnlyMode ? "전체 보기" : "번역본만");
        modeButton.setTextSize(11f);
        modeButton.setOnClickListener(v -> toggleTranslatedOnlyMode());
        LinearLayout.LayoutParams rightButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rightButtonParams.setMargins(0, dp(1), 0, dp(1));
        rightControls.addView(settingsButton, rightButtonParams);
        rightControls.addView(modeButton, rightButtonParams);

        topBar.addView(folderButton, new LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.MATCH_PARENT));
        topBar.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.MATCH_PARENT);
        rightParams.setMargins(dp(4), 0, 0, 0);
        topBar.addView(rightControls, rightParams);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(90), Gravity.TOP);
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
        translateButton = makePrimaryButton("폴더 번역");
        translateButton.setOnClickListener(v -> translateFolder());
        Button next = makeNavButton("다음  〉");
        next.setOnClickListener(v -> movePage(1));

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

        LinearLayout panelHeader = new LinearLayout(this);
        panelHeader.setOrientation(LinearLayout.HORIZONTAL);
        panelHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout panelTitleGroup = new LinearLayout(this);
        panelTitleGroup.setOrientation(LinearLayout.VERTICAL);
        translationTitle = new TextView(this);
        translationTitle.setTextColor(0xFFBFD5FF);
        translationTitle.setTextSize(15f);
        translationTitle.setTypeface(Typeface.DEFAULT_BOLD);
        TextView panelCaption = new TextView(this);
        panelCaption.setText("안내");
        panelCaption.setTextColor(COLOR_MUTED);
        panelCaption.setTextSize(11f);
        panelTitleGroup.addView(translationTitle);
        panelTitleGroup.addView(panelCaption);

        Button close = makePanelButton("닫기");
        close.setOnClickListener(v -> translationPanel.setVisibility(View.GONE));

        panelHeader.addView(panelTitleGroup, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams panelButtonParams = new LinearLayout.LayoutParams(dp(62), dp(38));
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

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM);
        panelParams.setMargins(dp(8), 0, dp(8), dp(82));
        root.addView(translationPanel, panelParams);

        tokenBadge = new TextView(this);
        tokenBadge.setTextColor(COLOR_TEXT);
        tokenBadge.setTextSize(11f);
        tokenBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tokenBadge.setGravity(Gravity.CENTER);
        tokenBadge.setPadding(dp(12), dp(7), dp(12), dp(7));
        tokenBadge.setBackground(roundedBackground(0xF22A2E3B, 18, 0xFF4A5060));
        tokenBadge.setElevation(dp(18));
        tokenBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams tokenParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        tokenParams.setMargins(0, 0, dp(20), dp(94));
        root.addView(tokenBadge, tokenParams);

        copyButton = makePanelButton("번역문 복사");
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> copyTranslation());
        FrameLayout.LayoutParams leftButtonParams = new FrameLayout.LayoutParams(
                dp(112), dp(38), Gravity.BOTTOM | Gravity.START);
        leftButtonParams.setMargins(dp(20), 0, 0, dp(94));
        root.addView(copyButton, leftButtonParams);

        stopButton = makePanelButton("번역 중지");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> requestStopBatch());
        FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(
                dp(112), dp(38), Gravity.BOTTOM | Gravity.START);
        stopParams.setMargins(dp(20), 0, 0, dp(94));
        root.addView(stopButton, stopParams);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
        root.addView(progressBar, progressParams);

        setContentView(root);
    }

    private void showWelcome() {
        titleView.setText("ImageLingo");
        subtitleView.setText("AI 이미지 번역 리더");
        progressTextView.setText("번역 0 / 0 · 0%");
        translationTitle.setText("시작하기");
        translationTitle.setTextColor(0xFFBFD5FF);
        translationText.setText("상단의 ‘폴더’를 눌러 이미지가 들어 있는 폴더를 선택하세요.\n\n"
                + "‘설정’에서 Gemini, OpenAI 또는 Vertex AI 인증 정보와 시스템 프롬프트를 입력한 뒤, 하단의 ‘폴더 번역’을 누르면 선택한 폴더 전체가 순차적으로 번역됩니다.\n\n"
                + "이미 번역된 PNG가 ‘" + OUTPUT_DIR_NAME + "’ 폴더에 있으면 자동으로 건너뜁니다.\n"
                + "번역된 이미지는 원문 위에 반투명 박스와 한국어 텍스트가 합성된 PNG 파일로 저장됩니다.");
        tokenBadge.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        currentTranslationText = "";
        translationPanel.setVisibility(View.VISIBLE);
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
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    private void chooseVertexJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_VERTEX_JSON);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_FOLDER) {
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}
            appPrefs.edit().putString("tree_uri", uri.toString()).apply();
            loadFolder(uri);
        } else if (requestCode == REQUEST_VERTEX_JSON) {
            importVertexJson(uri);
        }
    }

    private void importVertexJson(Uri uri) {
        executor.execute(() -> {
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
                String project = json.optString("project_id", "");
                if (!project.isEmpty()) {
                    apiSettings.save(apiSettings.provider(), apiSettings.openAiModel(), apiSettings.openAiEndpoint(),
                            apiSettings.geminiModel(), apiSettings.vertexModel(), apiSettings.vertexLocation(),
                            project, apiSettings.systemPrompt());
                }
                runOnUiThread(() -> {
                    if (vertexStatusText != null) vertexStatusText.setText("불러옴: " + json.optString("client_email"));
                    Toast.makeText(this, "Vertex AI JSON을 암호화해 저장했습니다.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "JSON 오류: " + cleanMessage(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadFolder(Uri treeUri) {
        currentTreeUri = treeUri;
        progressBar.setVisibility(View.VISIBLE);
        titleView.setText("폴더 읽는 중…");
        subtitleView.setText("");
        progressTextView.setText("번역 현황 확인 중…");
        translationPanel.setVisibility(View.GONE);
        tokenBadge.setVisibility(View.GONE);
        translatedOutputMap.clear();
        currentTranslationText = "";
        copyButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                List<ImageRepository.ImageEntry> loaded = ImageRepository.listImages(getContentResolver(), treeUri);
                Map<String, Uri> restoredOutputs = new HashMap<>();
                Uri outputDir = ImageRepository.findSubdirectory(getContentResolver(), treeUri, OUTPUT_DIR_NAME);
                TranslationMemory restoredMemory = TranslationMemory.load(getContentResolver(), outputDir);
                if (outputDir != null) {
                    Map<String, Uri> outputsByName = ImageRepository.listImageDocumentsByName(
                            getContentResolver(), outputDir);
                    for (ImageRepository.ImageEntry entry : loaded) {
                        Uri translated = outputsByName.get(outputNameFor(entry.name));
                        if (translated != null) restoredOutputs.put(entry.uri.toString(), translated);
                    }
                }
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    allImages = loaded;
                    outputDirectoryUri = outputDir;
                    translationMemory = restoredMemory;
                    translatedOutputMap.clear();
                    translatedOutputMap.putAll(restoredOutputs);
                    if (loaded.isEmpty()) {
                        images = new ArrayList<>();
                        currentIndex = -1;
                        titleView.setText("이미지 없음");
                        subtitleView.setText("다른 폴더를 선택하세요");
                        updateTopProgress(0);
                        translationTitle.setText("폴더가 비어 있습니다");
                        translationTitle.setTextColor(0xFFBFD5FF);
                        translationText.setText("선택한 폴더의 바로 아래에서 JPG, PNG, WEBP 등의 이미지 파일을 찾지 못했습니다.");
                        translationPanel.setVisibility(View.VISIBLE);
                        imageView.setImageDrawable(null);
                    } else {
                        applyViewMode(null);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
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
        if (modeButton != null) modeButton.setText(translatedOnlyMode ? "전체 보기" : "번역본만");
        updateTopProgress(processingPageNumber);

        if (images.isEmpty()) {
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
        modeButton.setText("전체 보기");
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

    private void renderCurrent() {
        if (currentIndex < 0 || currentIndex >= images.size()) return;
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
        executor.execute(() -> {
            try {
                Uri displayUri = translatedOutputMap.get(entry.uri.toString());
                Bitmap bitmap = decodeForDisplay(displayUri != null ? displayUri : entry.uri);
                String currentKey = cacheKeyFor(entry);
                TranslationClient.TranslationResult cached = translationCache.get(currentKey);
                runOnUiThread(() -> {
                    if (version != imageLoadVersion.get()) {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        return;
                    }
                    swapDisplayedBitmap(bitmap);
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
                    updateTopProgress(processingPageNumber);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showResult("이미지 오류", cleanMessage(e), true);
                });
            }
        });
    }

    private Bitmap decodeForDisplay(Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            Size size = info.getSize();
            int max = Math.max(size.getWidth(), size.getHeight());
            if (max > 4096) {
                float ratio = 4096f / max;
                decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * ratio)),
                        Math.max(1, Math.round(size.getHeight() * ratio)));
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

    private void translateFolder() {
        if (batchRunning) return;
        if (allImages.isEmpty() || currentTreeUri == null) {
            Toast.makeText(this, "먼저 이미지 폴더를 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        batchRunning = true;
        batchStopRequested = false;
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
        translateButton.setText("폴더 번역 중…");
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
        executor.execute(() -> runFolderTranslation(snapshot, treeUri, pinnedUri));
    }

    private void requestStopBatch() {
        if (!batchRunning || batchStopRequested) return;
        batchStopRequested = true;
        stopButton.setEnabled(false);
        stopButton.setText("중지 요청됨");
        Toast.makeText(this, "현재 처리 중인 이미지를 마치면 중지합니다.", Toast.LENGTH_SHORT).show();
    }

    private void runFolderTranslation(List<ImageRepository.ImageEntry> snapshot, Uri treeUri, String pinnedUri) {
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
        List<String> warnings = new ArrayList<>();
        try {
            Uri outputDir = ImageRepository.ensureSubdirectory(getContentResolver(), treeUri, OUTPUT_DIR_NAME);
            outputDirectoryUri = outputDir;
            TranslationMemory memory = TranslationMemory.load(getContentResolver(), outputDir);
            translationMemory = memory;
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
                    String outputName = outputNameFor(entry.name);
                    Uri existingOutput = ImageRepository.findChildDocumentByName(getContentResolver(), outputDir, outputName);
                    if (existingOutput != null) {
                        translatedOutputMap.put(entry.uri.toString(), existingOutput);
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
                            showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                    fSuccess, fFailure, fCache, fSkipped, fUsage, fInput, fOutput, fTotal);
                            refreshLiveView(currentVisibleUri(), entry.uri.toString());
                        });
                        continue;
                    }

                    String key = cacheKeyFor(entry);
                    String continuityContext = memory.buildContextPrompt();
                    TranslationClient.TranslationResult result = TranslationClient.translate(
                            getContentResolver(), entry.uri, apiSettings, secureStore, continuityContext);
                    translationCache.put(key, result);

                    Bitmap source = decodeForDisplay(entry.uri);
                    Bitmap composed = TranslatedImageRenderer.compose(source, result.blocks);
                    if (source != composed && source != null && !source.isRecycled()) source.recycle();
                    Uri savedUri = ImageRepository.saveBitmap(getContentResolver(), outputDir, outputName, composed);
                    translatedOutputMap.put(entry.uri.toString(), savedUri);
                    composed.recycle();
                    successCount++;
                    memory.applyResult(entry.name, result);
                    try {
                        memory.save(getContentResolver(), outputDir);
                    } catch (Exception memorySaveError) {
                        warnings.add(entry.name + "\n• 번역 이미지는 저장됐지만 문맥 파일 저장 실패: "
                                + cleanMessage(memorySaveError));
                    }

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
                        showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                fSuccess, fFailure, fCache, fSkipped, fUsage, fInput, fOutput, fTotal);
                        refreshLiveView(currentVisibleUri(), entry.uri.toString());
                    });
                } catch (Exception imageError) {
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
                        showBatchTokenProgress(displayIndex + 1, snapshot.size(),
                                fSuccess, fFailure, fCache, fSkipped, fUsage, fInput, fOutput, fTotal);
                        refreshLiveView(currentVisibleUri(), entry.uri.toString());
                    });
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
                String visibleBeforeFinish = currentVisibleUri();
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
                if (!warnings.isEmpty()) {
                    summary.append("\n\n문맥 저장 경고\n");
                    int limit = Math.min(3, warnings.size());
                    for (int i = 0; i < limit; i++) {
                        if (i > 0) summary.append("\n\n");
                        summary.append(warnings.get(i));
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
            runOnUiThread(() -> {
                processingImageUri = null;
                processingPageNumber = 0;
                finishBatchUi();
                updateTopProgress(0);
                showResult(providerLabel() + " 오류", cleanMessage(e), true);
                tokenBadge.setVisibility(View.GONE);
            });
        }
    }

    private void finishBatchUi() {
        batchRunning = false;
        batchStopRequested = false;
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
        translationPanel.setVisibility(View.VISIBLE);
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
        if (batchRunning) {
            Toast.makeText(this, "폴더 번역이 끝난 뒤 설정을 바꿔 주세요.", Toast.LENGTH_SHORT).show();
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

        form.addView(section("Gemini API"));
        EditText geminiKey = secretField("API 키 · AIza…", secureStore.get("gemini_key"));
        EditText geminiModel = field("모델명", apiSettings.geminiModel());
        form.addView(geminiKey, fieldParams());
        form.addView(geminiModel, fieldParams());

        form.addView(section("Vertex AI"));
        EditText vertexModel = field("모델명", apiSettings.vertexModel());
        EditText vertexLocation = field("리전 · global, us-central1 등", apiSettings.vertexLocation());
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
        Button viewMemory = makeDialogButton("현재 문맥 요약 보기");
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
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String providerValue = provider.getSelectedItemPosition() == 1 ? ApiSettings.OPENAI
                        : provider.getSelectedItemPosition() == 2 ? ApiSettings.VERTEX : ApiSettings.GEMINI;
                secureStore.put("openai_key", openAiKey.getText().toString().trim());
                secureStore.put("gemini_key", geminiKey.getText().toString().trim());
                apiSettings.save(providerValue,
                        openAiModel.getText().toString(), openAiEndpoint.getText().toString(),
                        geminiModel.getText().toString(), vertexModel.getText().toString(),
                        vertexLocation.getText().toString(), vertexProject.getText().toString(),
                        systemPrompt.getText().toString());
                translationCache.clear();
                tokenBadge.setVisibility(View.GONE);
                copyButton.setVisibility(View.GONE);
                stopButton.setVisibility(View.GONE);
                currentTranslationText = "";
                if (currentIndex >= 0) {
                    renderCurrent();
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
                .setMessage("characters, terms, honorifics 값을 수정하면 다음 페이지부터 통일 규칙에 반영됩니다.")
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
        Uri outputDir = outputDirectoryUri;
        if (currentTreeUri == null) {
            Toast.makeText(this, "먼저 이미지 폴더를 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("문맥 메모리 초기화")
                .setMessage("저장된 이름, 고유명사, 높임체, 호칭과 줄거리 요약을 모두 지울까요? 번역 이미지 파일은 삭제되지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("초기화", (dialog, which) -> executor.execute(() -> {
                    try {
                        Uri target = outputDir != null ? outputDir
                                : ImageRepository.ensureSubdirectory(getContentResolver(), currentTreeUri, OUTPUT_DIR_NAME);
                        TranslationMemory.reset(getContentResolver(), target);
                        TranslationMemory reset = TranslationMemory.load(getContentResolver(), target);
                        translationMemory = reset;
                        outputDirectoryUri = target;
                        translationCache.clear();
                        runOnUiThread(() -> {
                            statusView.setText("인물 0 · 용어 0 · 호칭 0\n아직 누적 줄거리 없음");
                            updateTopProgress(processingPageNumber);
                            Toast.makeText(this, "문맥 메모리를 초기화했습니다.", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "초기화 오류: " + cleanMessage(e), Toast.LENGTH_LONG).show());
                    }
                }))
                .show();
    }

    private String activeModel() {
        if (ApiSettings.OPENAI.equals(apiSettings.provider())) return apiSettings.openAiModel();
        if (ApiSettings.VERTEX.equals(apiSettings.provider())) return apiSettings.vertexModel();
        return apiSettings.geminiModel();
    }

    private String providerLabel() {
        if (ApiSettings.OPENAI.equals(apiSettings.provider())) return "OpenAI";
        if (ApiSettings.VERTEX.equals(apiSettings.provider())) return "Vertex AI";
        return "Gemini";
    }

    private String cacheKeyFor(ImageRepository.ImageEntry entry) {
        return entry.uri + "|" + apiSettings.provider() + "|" + activeModel()
                + "|" + apiSettings.openAiEndpoint() + "|" + apiSettings.systemPrompt().hashCode();
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
        imageView.setImageBitmap(bitmap);
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    private String outputNameFor(String inputName) {
        int dot = inputName.lastIndexOf('.');
        String base = dot > 0 ? inputName.substring(0, dot) : inputName;
        return base + "_translated.png";
    }

    private Button makeToolbarButton(String text) {
        Button button = baseButton(text, 13f, COLOR_TEXT);
        button.setBackground(roundedBackground(COLOR_SURFACE_2, 14, COLOR_STROKE));
        return button;
    }

    private Button makeNavButton(String text) {
        Button button = baseButton(text, 13f, COLOR_TEXT);
        button.setBackground(roundedBackground(COLOR_SURFACE_2, 16, COLOR_STROKE));
        return button;
    }

    private Button makePrimaryButton(String text) {
        Button button = baseButton(text, 14f, Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_PRIMARY, COLOR_PRIMARY_2});
        background.setCornerRadius(dp(16));
        button.setBackground(background);
        return button;
    }

    private Button makePanelButton(String text) {
        Button button = baseButton(text, 12f, COLOR_TEXT);
        button.setBackground(roundedBackground(COLOR_SURFACE_2, 13, COLOR_STROKE));
        return button;
    }

    private Button baseButton(String text, float textSize, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(textSize);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    private Button makeDialogButton(String text) {
        Button button = baseButton(text, 14f, 0xFF2E3150);
        button.setBackground(roundedBackground(0xFFE9E7FF, 12, 0xFFC9C4F5));
        return button;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(0xFF262936);
        view.setPadding(0, dp(20), 0, dp(7));
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

    @Override protected void onDestroy() {
        imageLoadVersion.incrementAndGet();
        executor.shutdownNow();
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        super.onDestroy();
    }
}
