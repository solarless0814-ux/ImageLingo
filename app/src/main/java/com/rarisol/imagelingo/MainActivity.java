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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int REQUEST_FOLDER = 1001;
    private static final int REQUEST_VERTEX_JSON = 1002;
    private static final String APP_PREFS = "app_state";

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
    private final Map<String, TranslationClient.TranslationResult> translationCache = new HashMap<>();

    private ZoomImageView imageView;
    private TextView titleView;
    private TextView subtitleView;
    private ProgressBar progressBar;
    private LinearLayout translationPanel;
    private TextView translationTitle;
    private TextView translationText;
    private TextView tokenBadge;
    private Button translateButton;
    private TextView vertexStatusText;

    private List<ImageRepository.ImageEntry> images = new ArrayList<>();
    private int currentIndex = -1;
    private Bitmap currentBitmap;
    private ApiSettings apiSettings;
    private SecureStore secureStore;
    private SharedPreferences appPrefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        apiSettings = new ApiSettings(this);
        secureStore = new SecureStore(this);
        appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
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
        imageParams.topMargin = dp(76);
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
        titleView.setTextSize(14f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        subtitleView = new TextView(this);
        subtitleView.setTextColor(COLOR_MUTED);
        subtitleView.setTextSize(11f);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setSingleLine(true);
        titleGroup.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleGroup.addView(subtitleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button settingsButton = makeToolbarButton("설정");
        settingsButton.setOnClickListener(v -> showSettingsDialog());

        topBar.addView(folderButton, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT));
        topBar.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        topBar.addView(settingsButton, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.TOP);
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
        translateButton = makePrimaryButton("이미지 번역");
        translateButton.setOnClickListener(v -> translateCurrent());
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
        panelCaption.setText("번역 결과");
        panelCaption.setTextColor(COLOR_MUTED);
        panelCaption.setTextSize(11f);
        panelTitleGroup.addView(translationTitle);
        panelTitleGroup.addView(panelCaption);

        Button copy = makePanelButton("복사");
        copy.setOnClickListener(v -> copyTranslation());
        Button close = makePanelButton("닫기");
        close.setOnClickListener(v -> {
            translationPanel.setVisibility(View.GONE);
            tokenBadge.setVisibility(View.GONE);
        });

        panelHeader.addView(panelTitleGroup, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams panelButtonParams = new LinearLayout.LayoutParams(dp(62), dp(38));
        panelButtonParams.setMargins(dp(5), 0, 0, 0);
        panelHeader.addView(copy, panelButtonParams);
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
        translationText.setTextSize(17f);
        translationText.setTextIsSelectable(true);
        translationText.setLineSpacing(dp(2), 1.16f);
        translationText.setPadding(dp(2), dp(4), dp(2), dp(52));
        scroll.addView(translationText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        translationPanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(350), Gravity.BOTTOM);
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

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER);
        root.addView(progressBar, progressParams);

        setContentView(root);
    }

    private void showWelcome() {
        titleView.setText("ImageLingo");
        subtitleView.setText("AI 이미지 번역 리더");
        translationTitle.setText("시작하기");
        translationText.setText("상단의 ‘폴더’를 눌러 이미지가 들어 있는 폴더를 선택하세요.\n\n"
                + "‘설정’에서 Gemini, OpenAI 또는 Vertex AI 인증 정보와 시스템 프롬프트를 입력한 뒤, 하단의 ‘이미지 번역’을 누르면 됩니다.");
        tokenBadge.setVisibility(View.GONE);
        translationPanel.setVisibility(View.VISIBLE);
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
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
        progressBar.setVisibility(View.VISIBLE);
        titleView.setText("폴더 읽는 중…");
        subtitleView.setText("");
        translationPanel.setVisibility(View.GONE);
        tokenBadge.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                List<ImageRepository.ImageEntry> loaded = ImageRepository.listImages(getContentResolver(), treeUri);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    images = loaded;
                    currentIndex = loaded.isEmpty() ? -1 : 0;
                    if (loaded.isEmpty()) {
                        titleView.setText("이미지 없음");
                        subtitleView.setText("다른 폴더를 선택하세요");
                        translationTitle.setText("폴더가 비어 있습니다");
                        translationText.setText("선택한 폴더의 바로 아래에서 JPG, PNG, WEBP 등의 이미지 파일을 찾지 못했습니다.");
                        translationPanel.setVisibility(View.VISIBLE);
                        imageView.setImageDrawable(null);
                    } else {
                        renderCurrent();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    titleView.setText("폴더 오류");
                    subtitleView.setText("");
                    showResult("오류", cleanMessage(e), true);
                });
            }
        });
    }

    private void renderCurrent() {
        if (currentIndex < 0 || currentIndex >= images.size()) return;
        int version = imageLoadVersion.incrementAndGet();
        ImageRepository.ImageEntry entry = images.get(currentIndex);
        titleView.setText(entry.name);
        subtitleView.setText((currentIndex + 1) + " / " + images.size() + "  ·  " + providerLabel());
        translationPanel.setVisibility(View.GONE);
        tokenBadge.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                Bitmap bitmap = decodeForDisplay(entry.uri);
                runOnUiThread(() -> {
                    if (version != imageLoadVersion.get()) {
                        bitmap.recycle();
                        return;
                    }
                    Bitmap old = currentBitmap;
                    currentBitmap = bitmap;
                    imageView.setImageBitmap(bitmap);
                    if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
                    progressBar.setVisibility(View.GONE);
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

    private void translateCurrent() {
        if (currentIndex < 0 || currentIndex >= images.size()) {
            Toast.makeText(this, "먼저 이미지 폴더를 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageRepository.ImageEntry entry = images.get(currentIndex);
        String cacheKey = entry.uri + "|" + apiSettings.provider() + "|" + activeModel()
                + "|" + apiSettings.openAiEndpoint() + "|" + apiSettings.systemPrompt().hashCode();
        TranslationClient.TranslationResult cached = translationCache.get(cacheKey);
        if (cached != null) {
            showResult(providerLabel() + " · 캐시", cached.text, false);
            showTokenUsage(cached.usage, true);
            return;
        }

        translateButton.setEnabled(false);
        translateButton.setText("번역 중…");
        progressBar.setVisibility(View.VISIBLE);
        tokenBadge.setVisibility(View.GONE);
        showResult(providerLabel(), "이미지의 글자를 읽고 번역하고 있습니다…", false);
        executor.execute(() -> {
            try {
                TranslationClient.TranslationResult result = TranslationClient.translate(
                        getContentResolver(), entry.uri, apiSettings, secureStore);
                translationCache.put(cacheKey, result);
                runOnUiThread(() -> {
                    translateButton.setEnabled(true);
                    translateButton.setText("이미지 번역");
                    progressBar.setVisibility(View.GONE);
                    showResult(providerLabel(), result.text, false);
                    showTokenUsage(result.usage, false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    translateButton.setEnabled(true);
                    translateButton.setText("이미지 번역");
                    progressBar.setVisibility(View.GONE);
                    tokenBadge.setVisibility(View.GONE);
                    showResult(providerLabel() + " 오류", cleanMessage(e), true);
                });
            }
        });
    }

    private void showResult(String title, String text, boolean error) {
        translationTitle.setText(title);
        translationTitle.setTextColor(error ? 0xFFFFA3A3 : 0xFFBFD5FF);
        translationText.setText(text);
        translationPanel.setVisibility(View.VISIBLE);
    }

    private void showTokenUsage(TranslationClient.TokenUsage usage, boolean cached) {
        if (usage == null || !usage.isAvailable()) {
            tokenBadge.setText(cached ? "캐시 · 토큰 정보 없음" : "토큰 정보 없음");
            tokenBadge.setVisibility(View.VISIBLE);
            return;
        }
        NumberFormat format = NumberFormat.getIntegerInstance();
        StringBuilder value = new StringBuilder();
        if (cached) value.append("캐시 · ");
        if (usage.totalTokens >= 0) value.append("총 ").append(format.format(usage.totalTokens));
        if (usage.inputTokens >= 0 || usage.outputTokens >= 0) {
            if (value.length() > 0) value.append("  ·  ");
            if (usage.inputTokens >= 0) value.append("입력 ").append(format.format(usage.inputTokens));
            if (usage.inputTokens >= 0 && usage.outputTokens >= 0) value.append(" / ");
            if (usage.outputTokens >= 0) value.append("출력 ").append(format.format(usage.outputTokens));
        }
        tokenBadge.setText(value.length() == 0 ? "토큰 정보 없음" : value.toString());
        tokenBadge.setVisibility(View.VISIBLE);
        tokenBadge.bringToFront();
    }

    private void copyTranslation() {
        CharSequence value = translationText.getText();
        if (value == null || value.length() == 0) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ImageLingo 번역", value));
        Toast.makeText(this, "번역문을 복사했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void showSettingsDialog() {
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
                if (currentIndex >= 0) {
                    subtitleView.setText((currentIndex + 1) + " / " + images.size() + "  ·  " + providerLabel());
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
