package com.rarisol.imagelingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
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

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger imageLoadVersion = new AtomicInteger();
    private final Map<String, String> translationCache = new HashMap<>();

    private ZoomImageView imageView;
    private TextView titleView;
    private ProgressBar progressBar;
    private LinearLayout translationPanel;
    private TextView translationTitle;
    private TextView translationText;
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
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
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
        root.setBackgroundColor(Color.BLACK);

        imageView = new ZoomImageView(this);
        imageView.setPageSwipeListener(this::movePage);
        root.addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int barHeight = dp(56);
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        topBar.setBackgroundColor(0xCC111111);
        Button folderButton = makeButton("폴더");
        folderButton.setOnClickListener(v -> chooseFolder());
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14f);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        Button settingsButton = makeButton("설정");
        settingsButton.setOnClickListener(v -> showSettingsDialog());
        topBar.addView(folderButton, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT));
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        topBar.addView(settingsButton, new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP);
        root.addView(topBar, topParams);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bottomBar.setBackgroundColor(0xDD111111);
        Button previous = makeButton("〈 이전");
        previous.setOnClickListener(v -> movePage(-1));
        translateButton = makeButton("번역");
        translateButton.setTypeface(Typeface.DEFAULT_BOLD);
        translateButton.setOnClickListener(v -> translateCurrent());
        Button next = makeButton("다음 〉");
        next.setOnClickListener(v -> movePage(1));
        bottomBar.addView(previous, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomBar.addView(translateButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomBar.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM);
        root.addView(bottomBar, bottomParams);

        translationPanel = new LinearLayout(this);
        translationPanel.setOrientation(LinearLayout.VERTICAL);
        translationPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        translationPanel.setBackgroundColor(0xEE181818);
        translationPanel.setVisibility(View.GONE);
        LinearLayout panelHeader = new LinearLayout(this);
        panelHeader.setOrientation(LinearLayout.HORIZONTAL);
        panelHeader.setGravity(Gravity.CENTER_VERTICAL);
        translationTitle = new TextView(this);
        translationTitle.setTextColor(0xFFB7DCFF);
        translationTitle.setTextSize(14f);
        translationTitle.setTypeface(Typeface.DEFAULT_BOLD);
        Button copy = makeSmallButton("복사");
        copy.setOnClickListener(v -> copyTranslation());
        Button close = makeSmallButton("닫기");
        close.setOnClickListener(v -> translationPanel.setVisibility(View.GONE));
        panelHeader.addView(translationTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        panelHeader.addView(copy, new LinearLayout.LayoutParams(dp(62), dp(42)));
        panelHeader.addView(close, new LinearLayout.LayoutParams(dp(62), dp(42)));
        translationPanel.addView(panelHeader);
        ScrollView scroll = new ScrollView(this);
        translationText = new TextView(this);
        translationText.setTextColor(Color.WHITE);
        translationText.setTextSize(17f);
        translationText.setTextIsSelectable(true);
        translationText.setLineSpacing(0f, 1.15f);
        translationText.setPadding(dp(4), dp(4), dp(4), dp(12));
        scroll.addView(translationText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        translationPanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM);
        panelParams.bottomMargin = barHeight;
        root.addView(translationPanel, panelParams);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        root.addView(progressBar, progressParams);

        setContentView(root);
    }

    private void showWelcome() {
        titleView.setText("ImageLingo");
        translationTitle.setText("시작하기");
        translationText.setText("상단의 ‘폴더’를 눌러 이미지가 들어 있는 폴더를 선택하세요.\n\n"
                + "그다음 ‘설정’에서 Gemini, OpenAI 또는 Vertex AI 인증 정보를 입력하고 이미지 아래의 ‘번역’을 누르면 됩니다.");
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
                            project, apiSettings.customPrompt());
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
        translationPanel.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                List<ImageRepository.ImageEntry> loaded = ImageRepository.listImages(getContentResolver(), treeUri);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    images = loaded;
                    currentIndex = loaded.isEmpty() ? -1 : 0;
                    if (loaded.isEmpty()) {
                        titleView.setText("이미지 없음");
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
                    showResult("오류", cleanMessage(e), true);
                });
            }
        });
    }

    private void renderCurrent() {
        if (currentIndex < 0 || currentIndex >= images.size()) return;
        int version = imageLoadVersion.incrementAndGet();
        ImageRepository.ImageEntry entry = images.get(currentIndex);
        titleView.setText((currentIndex + 1) + " / " + images.size() + "  " + entry.name);
        translationPanel.setVisibility(View.GONE);
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
        String cacheKey = entry.uri + "|" + apiSettings.provider() + "|" + activeModel();
        String cached = translationCache.get(cacheKey);
        if (cached != null) {
            showResult(providerLabel() + " · 캐시", cached, false);
            return;
        }
        translateButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        showResult(providerLabel(), "번역 중…", false);
        executor.execute(() -> {
            try {
                String result = TranslationClient.translate(getContentResolver(), entry.uri, apiSettings, secureStore);
                translationCache.put(cacheKey, result);
                runOnUiThread(() -> {
                    translateButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    showResult(providerLabel(), result, false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    translateButton.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    showResult(providerLabel() + " 오류", cleanMessage(e), true);
                });
            }
        });
    }

    private void showResult(String title, String text, boolean error) {
        translationTitle.setText(title);
        translationTitle.setTextColor(error ? 0xFFFF9B9B : 0xFFB7DCFF);
        translationText.setText(text);
        translationPanel.setVisibility(View.VISIBLE);
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
        form.setPadding(dp(20), dp(8), dp(20), dp(16));
        scroll.addView(form);

        form.addView(label("사용할 API"));
        Spinner provider = new Spinner(this);
        String[] providerNames = {"Gemini API", "OpenAI Responses API", "Vertex AI (서비스 계정 JSON)"};
        provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providerNames));
        provider.setSelection(ApiSettings.OPENAI.equals(apiSettings.provider()) ? 1
                : ApiSettings.VERTEX.equals(apiSettings.provider()) ? 2 : 0);
        form.addView(provider, matchWrap());

        form.addView(section("OpenAI"));
        EditText openAiKey = secretField("sk-…", secureStore.get("openai_key"));
        EditText openAiModel = field("모델명", apiSettings.openAiModel());
        EditText openAiEndpoint = field("Responses 엔드포인트", apiSettings.openAiEndpoint());
        form.addView(openAiKey, matchWrap());
        form.addView(openAiModel, matchWrap());
        form.addView(openAiEndpoint, matchWrap());

        form.addView(section("Gemini API"));
        EditText geminiKey = secretField("AIza…", secureStore.get("gemini_key"));
        EditText geminiModel = field("모델명", apiSettings.geminiModel());
        form.addView(geminiKey, matchWrap());
        form.addView(geminiModel, matchWrap());

        form.addView(section("Vertex AI"));
        EditText vertexModel = field("모델명", apiSettings.vertexModel());
        EditText vertexLocation = field("리전: global, us-central1 등", apiSettings.vertexLocation());
        EditText vertexProject = field("프로젝트 ID (JSON에서 자동 입력 가능)", apiSettings.vertexProject());
        form.addView(vertexModel, matchWrap());
        form.addView(vertexLocation, matchWrap());
        form.addView(vertexProject, matchWrap());
        Button importJson = makeDialogButton("서비스 계정 JSON 불러오기");
        importJson.setOnClickListener(v -> chooseVertexJson());
        form.addView(importJson, matchWrap());
        vertexStatusText = new TextView(this);
        vertexStatusText.setTextColor(0xFF555555);
        vertexStatusText.setTextSize(13f);
        String vertexJson = secureStore.get("vertex_json");
        vertexStatusText.setText(vertexJson.isEmpty() ? "아직 JSON을 불러오지 않았습니다."
                : "서비스 계정 JSON이 암호화되어 저장되어 있습니다.");
        form.addView(vertexStatusText, matchWrap());

        form.addView(section("번역 지시문 (선택)"));
        EditText prompt = field("비워 두면 만화·게임용 기본 번역 지시문 사용", apiSettings.customPrompt());
        prompt.setMinLines(3);
        prompt.setGravity(Gravity.TOP | Gravity.START);
        form.addView(prompt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("번역 API 설정")
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
                        prompt.getText().toString());
                translationCache.clear();
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

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setBackgroundColor(0xFF2B2B2B);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private Button makeSmallButton(String text) {
        Button button = makeButton(text);
        button.setTextSize(12f);
        return button;
    }

    private Button makeDialogButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setTextColor(0xFF333333);
        view.setPadding(0, dp(8), 0, dp(3));
        return view;
    }

    private TextView section(String text) {
        TextView view = label(text);
        view.setTextSize(17f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(18), 0, dp(5));
        return view;
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setSingleLine(false);
        field.setTextSize(14f);
        return field;
    }

    private EditText secretField(String hint, String value) {
        EditText field = field(hint, value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
