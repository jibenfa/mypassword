package com.example.coffeecat.mypassword.next;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_BACKUP = 4101;
    private static final int REQUEST_IMPORT_BACKUP = 4102;
    private static final int MAX_BACKUP_BYTES = 5 * 1024 * 1024;
    private static final int INK = Color.rgb(238, 242, 247);
    private static final int MUTED = Color.rgb(163, 174, 190);
    private static final int ACCENT = Color.rgb(39, 146, 133);
    private static final int PAGE = Color.rgb(19, 26, 37);
    private static final int CARD = Color.rgb(31, 41, 56);
    private static final int FIELD = Color.rgb(24, 33, 47);
    private static final int RESULT_BG = Color.rgb(25, 35, 49);
    private static final int BORDER = Color.rgb(61, 74, 93);

    private EditText masterInput;
    private EditText secondInput;
    private EditText noteInput;
    private Button masterButton;
    private Button generateButton;
    private TextView menuButton;
    private Spinner lengthSpinner;
    private LinearLayout masterCard;
    private LinearLayout generatorCard;
    private LinearLayout resultCard;
    private TextView result;
    private String fingerprint;
    private String masterPassword;
    private String loadedSecondInput;
    private int lastSpinnerLength = 8;
    private int failedAttempts;
    private boolean loadingSavedEntry;
    private boolean storageErrorShown;
    private SavedEntryStore savedEntryStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        savedEntryStore = new SavedEntryStore(this);
        getWindow().setStatusBarColor(PAGE);
        getWindow().setNavigationBarColor(PAGE);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(24));
        page.setBackgroundColor(PAGE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView appName = new TextView(this);
        appName.setText("MyPassword");
        appName.setTextColor(INK);
        appName.setTextSize(29);
        appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(appName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        menuButton = new TextView(this);
        menuButton.setText("⋮");
        menuButton.setTextColor(INK);
        menuButton.setTextSize(28);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setContentDescription("更多选项");
        menuButton.setPadding(dp(14), 0, dp(14), 0);
        menuButton.setBackground(rounded(CARD, BORDER, 14));
        menuButton.setVisibility(View.GONE);
        header.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        menuButton.setOnClickListener(this::showOverflowMenu);
        page.addView(header, matchWrap());

        TextView tagline = new TextView(this);
        tagline.setText("离线密码生成器");
        tagline.setTextColor(MUTED);
        tagline.setTextSize(15);
        LinearLayout.LayoutParams taglineParams = matchWrap();
        taglineParams.topMargin = dp(5);
        taglineParams.bottomMargin = dp(24);
        page.addView(tagline, taglineParams);

        buildMasterCard(page);
        buildGeneratorCard(page);
        buildResultCard(page);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.addView(page);
        setContentView(scrollView);
    }

    private void buildMasterCard(LinearLayout page) {
        masterCard = card();
        masterCard.addView(sectionTitle("验证主密码"), matchWrap());
        masterCard.addView(sectionCaption("输入主密码以继续。"), withTopMargin(matchWrap(), 6));

        masterInput = input("请输入主密码",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        masterInput.setSingleLine(true);
        masterInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        styleInput(masterInput);
        masterCard.addView(masterInput, withTopMargin(matchWrap(), 18));

        masterButton = primaryButton("解锁");
        masterCard.addView(masterButton, withTopMargin(matchWrap(), 16));
        page.addView(masterCard, bottomMargin(matchWrap(), 16));
        masterButton.setOnClickListener(v -> verifyMaster());
    }

    private void buildGeneratorCard(LinearLayout page) {
        generatorCard = card();
        generatorCard.setVisibility(View.GONE);
        generatorCard.addView(sectionTitle("生成密码"), matchWrap());
        generatorCard.addView(sectionCaption("输入内容相同，可恢复之前保存的长度和备注。"),
                withTopMargin(matchWrap(), 6));

        generatorCard.addView(fieldLabel("输入内容"), withTopMargin(matchWrap(), 19));
        secondInput = input("输入内容", InputType.TYPE_CLASS_TEXT);
        secondInput.setSingleLine(true);
        styleInput(secondInput);
        generatorCard.addView(secondInput, withTopMargin(matchWrap(), 6));

        generatorCard.addView(fieldLabel("密码长度"), withTopMargin(matchWrap(), 16));
        lengthSpinner = new Spinner(this);
        Integer[] lengths = {8, 9, 10, 11, 12};
        ArrayAdapter<Integer> lengthAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Arrays.asList(lengths));
        lengthAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        lengthSpinner.setAdapter(lengthAdapter);
        lengthSpinner.setSelection(0);
        lengthSpinner.setPadding(dp(10), 0, dp(10), 0);
        lengthSpinner.setBackground(rounded(FIELD, BORDER, 12));
        generatorCard.addView(lengthSpinner, withTopMargin(matchWrap(), 6));

        generatorCard.addView(fieldLabel("备注"), withTopMargin(matchWrap(), 16));
        noteInput = input("添加备注（可选）", InputType.TYPE_CLASS_TEXT);
        noteInput.setSingleLine(true);
        styleInput(noteInput);
        generatorCard.addView(noteInput, withTopMargin(matchWrap(), 6));

        generateButton = primaryButton("生成密码");
        generatorCard.addView(generateButton, withTopMargin(matchWrap(), 20));

        page.addView(generatorCard, bottomMargin(matchWrap(), 16));

        addFieldListeners();
    }

    private void buildResultCard(LinearLayout page) {
        resultCard = card();
        resultCard.setVisibility(View.GONE);
        resultCard.addView(fieldLabel("生成结果"), matchWrap());
        result = new TextView(this);
        result.setTextColor(INK);
        result.setTextSize(22);
        result.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setTextIsSelectable(true);
        result.setMinHeight(dp(58));
        result.setPadding(dp(14), dp(10), dp(14), dp(10));
        result.setBackground(rounded(RESULT_BG, BORDER, 12));
        result.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("生成的密码", result.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            return true;
        });
        resultCard.addView(result, withTopMargin(matchWrap(), 10));

        TextView copyHint = sectionCaption("长按结果可选择并复制");
        resultCard.addView(copyHint, withTopMargin(matchWrap(), 9));
        page.addView(resultCard);
    }

    private void addFieldListeners() {
        secondInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String current = s.toString();
                if (current.equals(loadedSecondInput)) return;
                loadedSecondInput = current;
                resultCard.setVisibility(View.GONE);
                loadSavedFields(current);
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        lengthSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedLength = (Integer) parent.getItemAtPosition(position);
                if (selectedLength != lastSpinnerLength) {
                    lastSpinnerLength = selectedLength;
                    saveCurrentEntry();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        noteInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                saveCurrentEntry();
            }

            @Override public void afterTextChanged(Editable s) {}
        });

        generateButton.setOnClickListener(v -> {
            saveCurrentEntry();
            int length = (Integer) lengthSpinner.getSelectedItem();
            result.setText(LegacyPasswordAlgorithm.generate(
                    secondInput.getText().toString(), fingerprint, length));
            resultCard.setVisibility(View.VISIBLE);
        });

    }

    private void verifyMaster() {
        String password = masterInput.getText().toString();
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date());
        if (LegacyPasswordAlgorithm.isMasterPasswordValid(password, timestamp)) {
            masterPassword = password;
            fingerprint = LegacyPasswordAlgorithm.fingerprint(password);
            failedAttempts = 0;
            masterCard.setVisibility(View.GONE);
            generatorCard.setVisibility(View.VISIBLE);
            menuButton.setVisibility(View.VISIBLE);
            loadedSecondInput = null;
            loadSavedFields(secondInput.getText().toString());
        } else {
            failedAttempts++;
            Toast.makeText(this, "密码不正确", Toast.LENGTH_SHORT).show();
            if (failedAttempts >= 3) masterCard.setVisibility(View.GONE);
        }
    }

    private void loadSavedFields(String secondValue) {
        loadingSavedEntry = true;
        try {
            SavedEntryStore.Entry entry = secondValue.isEmpty()
                    ? null : savedEntryStore.load(fingerprint, secondValue);
            setSpinnerLength(entry == null ? 8 : entry.length);
            noteInput.setText(entry == null ? "" : entry.note);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            setSpinnerLength(8);
            noteInput.setText("");
            showStorageError();
        } finally {
            loadingSavedEntry = false;
        }
    }

    private void setSpinnerLength(int length) {
        int position = Math.max(8, Math.min(12, length)) - 8;
        lengthSpinner.setSelection(position);
        lastSpinnerLength = position + 8;
    }

    private void saveCurrentEntry() {
        if (loadingSavedEntry || fingerprint == null) return;
        String secondValue = secondInput.getText().toString();
        if (secondValue.isEmpty()) return;
        int length = (Integer) lengthSpinner.getSelectedItem();
        try {
            savedEntryStore.save(fingerprint, secondValue, length, noteInput.getText().toString());
            storageErrorShown = false;
        } catch (GeneralSecurityException | IOException | JSONException e) {
            showStorageError();
        }
    }

    private void searchNotes(String query) {
        query = query.trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "请输入备注关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            List<SavedEntryStore.NoteMatch> matches = savedEntryStore.searchByNote(fingerprint, query);
            if (matches.isEmpty()) {
                Toast.makeText(this, "没有找到匹配的备注", Toast.LENGTH_SHORT).show();
                return;
            }
            SavedEntryStore.NoteMatch match = matches.get(0);
            secondInput.setText(match.secondInput);
            loadedSecondInput = match.secondInput;
            loadSavedFields(match.secondInput);
            String message = matches.size() == 1
                    ? "已载入匹配记录"
                    : "找到 " + matches.size() + " 条，已载入第一条；可缩小关键词继续搜索";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (GeneralSecurityException | IOException | JSONException | IllegalArgumentException e) {
            Toast.makeText(this, "备注检索失败，保存数据暂时无法读取", Toast.LENGTH_LONG).show();
        }
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("按备注检索").setOnMenuItemClickListener(item -> {
            showNoteSearchDialog();
            return true;
        });

        SubMenu migration = popup.getMenu().addSubMenu("设备迁移");
        migration.add("导出备份").setOnMenuItemClickListener(item -> {
            chooseBackupDestination();
            return true;
        });
        migration.add("恢复备份").setOnMenuItemClickListener(item -> {
            chooseBackupFile();
            return true;
        });
        popup.show();
    }

    private void showNoteSearchDialog() {
        EditText queryInput = input("备注关键词", InputType.TYPE_CLASS_TEXT);
        queryInput.setSingleLine(true);
        styleInput(queryInput);
        new AlertDialog.Builder(this)
                .setTitle("按备注检索")
                .setMessage("输入备注中的关键词，查找已保存的记录。")
                .setView(queryInput)
                .setPositiveButton("搜索", (dialog, which) -> searchNotes(queryInput.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showStorageError() {
        if (storageErrorShown) return;
        storageErrorShown = true;
        Toast.makeText(this, "本机保存数据暂时无法读取或写入", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        saveCurrentEntry();
        super.onPause();
    }

    private void chooseBackupDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "MyPassword-backup.mpbackup");
        startActivityForResult(intent, REQUEST_EXPORT_BACKUP);
    }

    private void chooseBackupFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_BACKUP) writeBackup(uri);
        else if (requestCode == REQUEST_IMPORT_BACKUP) readBackup(uri);
    }

    private void writeBackup(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException("Could not open backup destination");
            output.write(savedEntryStore.exportBackup(masterPassword));
            output.flush();
            Toast.makeText(this, "加密备份已导出", Toast.LENGTH_LONG).show();
        } catch (GeneralSecurityException | IOException | JSONException | RuntimeException e) {
            Toast.makeText(this, "备份导出失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void readBackup(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Could not open backup file");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BACKUP_BYTES) throw new IOException("备份文件过大");
                bytes.write(buffer, 0, count);
            }
            int imported = savedEntryStore.importBackup(bytes.toByteArray(), masterPassword, fingerprint);
            loadedSecondInput = null;
            loadSavedFields(secondInput.getText().toString());
            Toast.makeText(this, "已恢复 " + imported + " 条记录", Toast.LENGTH_LONG).show();
        } catch (GeneralSecurityException e) {
            Toast.makeText(this, "恢复失败：主密码不匹配或备份文件已损坏", Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException | RuntimeException e) {
            Toast.makeText(this, "备份恢复失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "文件无法读取" : message;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(CARD, Color.TRANSPARENT, 18));
        card.setElevation(dp(3));
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(INK);
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView sectionCaption(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(13);
        return view;
    }

    private TextView fieldLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(INK);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setInputType(inputType);
        return editText;
    }

    private void styleInput(EditText editText) {
        editText.setTextColor(INK);
        editText.setHintTextColor(Color.rgb(151, 161, 175));
        editText.setTextSize(15);
        editText.setPadding(dp(15), dp(13), dp(15), dp(13));
        editText.setBackground(rounded(FIELD, BORDER, 12));
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(rounded(ACCENT, ACCENT, 13));
        button.setElevation(dp(2));
        return button;
    }

    private GradientDrawable rounded(int color, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeColor != Color.TRANSPARENT) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams withTopMargin(LinearLayout.LayoutParams params, int margin) {
        params.topMargin = dp(margin);
        return params;
    }

    private LinearLayout.LayoutParams bottomMargin(LinearLayout.LayoutParams params, int margin) {
        params.bottomMargin = dp(margin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
