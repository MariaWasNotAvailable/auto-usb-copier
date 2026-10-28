package com.autousbcopier;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFERENCES = "auto_usb_copier";
    static final String PREF_AUTO_NOTIFICATION = "auto_notification";
    private static final String PREF_USB_FOLDER = "usb_folder";
    private static final String PREF_PHONE_FOLDER = "phone_folder";
    private static final String PREF_CUSTOM_SOURCES = "custom_sources";
    private static final String PREF_MODE = "copy_mode";
    private static final String PREF_DIRECTION = "copy_direction";
    private static final String PREF_OVERWRITE = "overwrite";
    private static final String PREF_SKIP_IDENTICAL = "skip_identical";
    private static final String PREF_REMEMBER = "remember";
    private static final String PREF_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested";

    private static final int REQUEST_USB_FOLDER = 1;
    private static final int REQUEST_PHONE_FOLDER = 2;
    private static final int REQUEST_CUSTOM_FOLDER = 3;
    private static final int REQUEST_CUSTOM_FILES = 4;
    private static final int REQUEST_NOTIFICATIONS = 5;
    private static final int REQUEST_DEBUG_STORAGE = 6;

    private final ArrayList<Uri> customSources = new ArrayList<>();
    private final BroadcastReceiver copyResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && CopyService.ACTION_RESULT.equals(intent.getAction())) {
                copyRunning = false;
                updateStorageUi();
                if (intent.getBooleanExtra(CopyService.EXTRA_NO_MEDIA, false)) {
                    showNoMediaDialog();
                } else {
                    String summary = intent.getStringExtra(CopyService.EXTRA_SUMMARY);
                    if (summary != null) {
                        Toast.makeText(MainActivity.this, summary, Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    };
    private final BroadcastReceiver storageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (storageStatus != null) {
                storageStatus.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        updateStorageUi();
                    }
                }, 1200);
            }
        }
    };

    private SharedPreferences preferences;
    private Uri usbFolder;
    private Uri phoneFolder;
    private TextView storageStatus;
    private TextView usbFolderStatus;
    private TextView phoneFolderStatus;
    private TextView customSelectionStatus;
    private RadioGroup modeGroup;
    private RadioGroup directionGroup;
    private RadioButton[] modeButtons;
    private RadioButton usbToPhoneButton;
    private RadioButton phoneToUsbButton;
    private Button usbFolderButton;
    private Button phoneFolderButton;
    private CheckBox rememberCheckBox;
    private CheckBox overwriteCheckBox;
    private CheckBox skipIdenticalCheckBox;
    private CheckBox autoNotificationCheckBox;
    private CheckBox debugCheckBox;
    private Button startButton;
    private LinearLayout customControls;
    private boolean usbConnected;
    private boolean copyRunning;
    private boolean resultReceiverRegistered;
    private boolean pendingDebugPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        } else {
            loadPreferences();
        }
        buildInterface();
        updateStorageUi();
        handleNoMediaIntent(getIntent());
        maybeRequestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerCopyResultReceiver();
        registerStorageReceiver();
        copyRunning = CopyService.isRunning();
        updateStorageUi();
        StorageMonitorReceiver.cancelConnectionNotification(this);
    }

    @Override
    protected void onPause() {
        if (resultReceiverRegistered) {
            unregisterReceiver(copyResultReceiver);
            resultReceiverRegistered = false;
        }
        try {
            unregisterReceiver(storageChangedReceiver);
        } catch (IllegalArgumentException exception) {
            android.util.Log.w("AutoUsbCopier", "Storage receiver was already unregistered", exception);
        }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNoMediaIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("usb_folder", uriString(usbFolder));
        outState.putString("phone_folder", uriString(phoneFolder));
        ArrayList<String> custom = new ArrayList<>();
        for (Uri uri : customSources) {
            custom.add(uri.toString());
        }
        outState.putStringArrayList("custom_sources", custom);
        outState.putBoolean("copy_running", copyRunning);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null
                && data.getClipData() == null) {
            return;
        }
        if (requestCode == REQUEST_USB_FOLDER) {
            usbFolder = data.getData();
            saveSelectedUri(usbFolder, data.getFlags(), PREF_USB_FOLDER);
            refreshFolderLabels();
        } else if (requestCode == REQUEST_PHONE_FOLDER) {
            phoneFolder = data.getData();
            saveSelectedUri(phoneFolder, data.getFlags(), PREF_PHONE_FOLDER);
            refreshFolderLabels();
        } else if (requestCode == REQUEST_CUSTOM_FOLDER) {
            addCustomUri(data.getData(), data.getFlags());
            refreshFolderLabels();
        } else if (requestCode == REQUEST_CUSTOM_FILES) {
            addSelectedFiles(data);
            refreshFolderLabels();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_DEBUG_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            pendingDebugPermission = false;
            if (granted) {
                DebugLog.setEnabled(this, true);
                DebugLog.log(this, "Debug logging enabled.");
                Toast.makeText(this, R.string.debug_log_enabled, Toast.LENGTH_LONG).show();
                if (debugCheckBox != null) {
                    debugCheckBox.setChecked(true);
                    debugCheckBox.setEnabled(true);
                }
            } else {
                Toast.makeText(this, R.string.debug_log_permission_denied, Toast.LENGTH_LONG).show();
                if (debugCheckBox != null) {
                    debugCheckBox.setChecked(false);
                    debugCheckBox.setEnabled(true);
                }
            }
            return;
        }
        if (requestCode == REQUEST_NOTIFICATIONS
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            showNotificationPermissionExplanation();
        }
    }

    private void buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        content.setPadding(horizontal, dp(18), horizontal, dp(24));
        if (Build.VERSION.SDK_INT >= 35) {
            final int contentLeft = horizontal;
            final int contentRight = horizontal;
            final int contentTop = dp(18);
            final int contentBottom = dp(24);
            scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    view.setPadding(contentLeft + bars.left, contentTop + bars.top,
                            contentRight + bars.right, contentBottom + bars.bottom);
                    return insets;
                }
            });
        }
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTextColor(0xff212121);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button aboutButton = new Button(this);
        aboutButton.setText(R.string.about);
        aboutButton.setMinHeight(dp(40));
        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAboutDialog();
            }
        });
        titleRow.addView(aboutButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(titleRow, fullWidth());

        storageStatus = new TextView(this);
        storageStatus.setTextSize(16);
        storageStatus.setPadding(0, dp(12), 0, dp(12));
        content.addView(storageStatus, fullWidth());

        addSectionTitle(content, R.string.what_to_copy);
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        String[] modeLabels = {
                getString(R.string.just_photos),
                getString(R.string.photos_and_videos),
                getString(R.string.videos),
                getString(R.string.everything),
                getString(R.string.custom)
        };
        modeButtons = new RadioButton[modeLabels.length];
        for (int i = 0; i < modeLabels.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(modeLabels[i]);
            button.setTag(i);
            modeGroup.addView(button, fullWidth());
            modeButtons[i] = button;
        }
        modeButtons[clampMode(preferences.getInt(PREF_MODE, CopyService.MODE_PHOTOS))].setChecked(true);
        modeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int mode = selectedMode();
                preferences.edit().putInt(PREF_MODE, mode).apply();
                updateCustomControls();
            }
        });
        content.addView(modeGroup, fullWidth());

        customControls = new LinearLayout(this);
        customControls.setOrientation(LinearLayout.VERTICAL);
        Button chooseFilesButton = new Button(this);
        chooseFilesButton.setText(R.string.choose_files);
        chooseFilesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchCustomFilePicker();
            }
        });
        customControls.addView(chooseFilesButton, fullWidth());
        Button addFolderButton = new Button(this);
        addFolderButton.setText(R.string.add_folder);
        addFolderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchTreePicker(REQUEST_CUSTOM_FOLDER);
            }
        });
        customControls.addView(addFolderButton, fullWidth());
        Button clearCustomButton = new Button(this);
        clearCustomButton.setText(R.string.clear_custom);
        clearCustomButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearCustomSources();
            }
        });
        customControls.addView(clearCustomButton, fullWidth());
        customSelectionStatus = new TextView(this);
        customSelectionStatus.setPadding(dp(4), dp(4), dp(4), dp(8));
        customControls.addView(customSelectionStatus, fullWidth());
        content.addView(customControls, fullWidth());
        updateCustomControls();

        addSectionTitle(content, R.string.direction);
        directionGroup = new RadioGroup(this);
        directionGroup.setOrientation(RadioGroup.VERTICAL);
        usbToPhoneButton = new RadioButton(this);
        usbToPhoneButton.setId(View.generateViewId());
        usbToPhoneButton.setText(R.string.usb_to_phone);
        phoneToUsbButton = new RadioButton(this);
        phoneToUsbButton.setId(View.generateViewId());
        phoneToUsbButton.setText(R.string.phone_to_usb);
        directionGroup.addView(usbToPhoneButton, fullWidth());
        directionGroup.addView(phoneToUsbButton, fullWidth());
        directionGroup.check(preferences.getInt(PREF_DIRECTION, 0) == 0
                ? usbToPhoneButton.getId() : phoneToUsbButton.getId());
        directionGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                preferences.edit().putInt(PREF_DIRECTION,
                        checkedId == usbToPhoneButton.getId() ? 0 : 1).apply();
                refreshFolderLabels();
            }
        });
        content.addView(directionGroup, fullWidth());

        rememberCheckBox = new CheckBox(this);
        rememberCheckBox.setText(R.string.remember_selections);
        rememberCheckBox.setChecked(preferences.getBoolean(PREF_REMEMBER, true));
        rememberCheckBox.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_REMEMBER, checked).apply());
        content.addView(rememberCheckBox, fullWidth());

        addFolderChooser(content, true);
        addFolderChooser(content, false);

        addSectionTitle(content, R.string.overwrite_files);
        overwriteCheckBox = new CheckBox(this);
        overwriteCheckBox.setText(R.string.overwrite_files);
        overwriteCheckBox.setChecked(preferences.getBoolean(PREF_OVERWRITE, false));
        overwriteCheckBox.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_OVERWRITE, checked).apply();
            skipIdenticalCheckBox.setEnabled(checked);
        });
        content.addView(overwriteCheckBox, fullWidth());

        skipIdenticalCheckBox = new CheckBox(this);
        skipIdenticalCheckBox.setText(R.string.skip_identical);
        skipIdenticalCheckBox.setChecked(preferences.getBoolean(PREF_SKIP_IDENTICAL, true));
        skipIdenticalCheckBox.setEnabled(overwriteCheckBox.isChecked());
        skipIdenticalCheckBox.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(PREF_SKIP_IDENTICAL, checked).apply());
        content.addView(skipIdenticalCheckBox, fullWidth());

        autoNotificationCheckBox = new CheckBox(this);
        autoNotificationCheckBox.setText(R.string.auto_notification);
        autoNotificationCheckBox.setChecked(preferences.getBoolean(PREF_AUTO_NOTIFICATION, true));
        autoNotificationCheckBox.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_AUTO_NOTIFICATION, checked).apply();
            if (checked) {
                maybeRequestNotificationPermission();
            } else {
                StorageMonitorReceiver.cancelConnectionNotification(MainActivity.this);
            }
        });
        content.addView(autoNotificationCheckBox, fullWidth());

        startButton = new Button(this);
        startButton.setText(R.string.start_copy);
        startButton.setTextSize(18);
        startButton.setMinHeight(dp(56));
        startButton.setGravity(Gravity.CENTER);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onStartCopyClicked();
            }
        });
        LinearLayout.LayoutParams startParams = fullWidth();
        startParams.topMargin = dp(12);
        content.addView(startButton, startParams);

        TextView notice = new TextView(this);
        notice.setText(R.string.copy_notice);
        notice.setTextSize(14);
        notice.setPadding(0, dp(8), 0, 0);
        content.addView(notice, fullWidth());

        setContentView(scrollView);
        refreshFolderLabels();
    }

    private void addFolderChooser(LinearLayout parent, final boolean usb) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(this);
        heading.setText(usb ? R.string.usb_folder : R.string.phone_folder);
        heading.setTextSize(16);
        heading.setPadding(0, dp(10), 0, dp(2));
        section.addView(heading, fullWidth());

        TextView status = new TextView(this);
        status.setText(R.string.no_folder_selected);
        status.setTextSize(14);
        if (usb) {
            usbFolderStatus = status;
        } else {
            phoneFolderStatus = status;
        }
        section.addView(status, fullWidth());

        Button choose = new Button(this);
        choose.setText(R.string.choose_folder);
        choose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (usb && !usbConnected) {
                    Toast.makeText(MainActivity.this, R.string.no_usb_storage, Toast.LENGTH_SHORT).show();
                    return;
                }
                launchTreePicker(usb ? REQUEST_USB_FOLDER : REQUEST_PHONE_FOLDER);
            }
        });
        if (usb) {
            usbFolderButton = choose;
        } else {
            phoneFolderButton = choose;
        }
        section.addView(choose, fullWidth());
        parent.addView(section, fullWidth());
    }

    private void addSectionTitle(LinearLayout parent, int titleResource) {
        TextView title = new TextView(this);
        title.setText(titleResource);
        title.setTextSize(18);
        title.setTextColor(0xff1565c0);
        title.setPadding(0, dp(14), 0, dp(4));
        parent.addView(title, fullWidth());
    }

    private void updateCustomControls() {
        if (customControls != null) {
            customControls.setVisibility(selectedMode() == CopyService.MODE_CUSTOM ? View.VISIBLE : View.GONE);
        }
        if (customSelectionStatus != null) {
            customSelectionStatus.setText(getString(R.string.custom_selection, customSources.size()));
        }
    }

    private void updateStorageUi() {
        usbConnected = UsbStorageDetector.hasMountedStorage(this);
        if (storageStatus != null) {
            storageStatus.setText(usbConnected ? R.string.usb_storage_ready : R.string.no_usb_storage);
            storageStatus.setTextColor(usbConnected ? 0xff2e7d32 : 0xffb71c1c);
        }
        if (startButton != null) {
            startButton.setEnabled(usbConnected && !copyRunning);
        }
        boolean editable = !copyRunning;
        if (modeButtons != null) {
            for (RadioButton button : modeButtons) {
                button.setEnabled(editable);
            }
        }
        if (usbToPhoneButton != null) {
            usbToPhoneButton.setEnabled(editable);
            phoneToUsbButton.setEnabled(editable);
            rememberCheckBox.setEnabled(editable);
            overwriteCheckBox.setEnabled(editable);
            skipIdenticalCheckBox.setEnabled(editable && overwriteCheckBox.isChecked());
            autoNotificationCheckBox.setEnabled(editable);
            usbFolderButton.setEnabled(editable && usbConnected);
            phoneFolderButton.setEnabled(editable);
            for (int i = 0; i < customControls.getChildCount(); i++) {
                customControls.getChildAt(i).setEnabled(editable);
            }
        }
        refreshFolderLabels();
    }

    private void refreshFolderLabels() {
        if (usbFolderStatus != null) {
            usbFolderStatus.setText(folderDisplayName(usbFolder));
        }
        if (phoneFolderStatus != null) {
            phoneFolderStatus.setText(folderDisplayName(phoneFolder));
        }
        if (customSelectionStatus != null) {
            customSelectionStatus.setText(customSources.isEmpty()
                    ? getString(R.string.no_files_selected)
                    : getString(R.string.custom_selection, customSources.size()));
        }
    }

    private String folderDisplayName(Uri uri) {
        if (uri == null) {
            return getString(R.string.no_folder_selected);
        }
        Uri documentUri = uri;
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        uri, DocumentsContract.getTreeDocumentId(uri));
            }
            try (Cursor cursor = getContentResolver().query(documentUri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0) {
                        String name = cursor.getString(column);
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
        } catch (Exception exception) {
            android.util.Log.w("AutoUsbCopier", "Unable to read selected folder name", exception);
        }
        return getString(R.string.no_folder_selected);
    }

    private void onStartCopyClicked() {
        if (copyRunning) {
            return;
        }
        if (!usbConnected) {
            updateStorageUi();
            return;
        }
        boolean custom = selectedMode() == CopyService.MODE_CUSTOM;
        boolean usbToPhone = isUsbToPhone();
        Uri source = usbToPhone ? usbFolder : phoneFolder;
        Uri destination = usbToPhone ? phoneFolder : usbFolder;
        if (custom && customSources.isEmpty()) {
            Toast.makeText(this, R.string.choose_source_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!custom && source == null) {
            Toast.makeText(this, R.string.choose_source_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (destination == null) {
            Toast.makeText(this, R.string.choose_destination_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!usbToPhone) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.reverse_copy_warning_title)
                    .setMessage(R.string.reverse_copy_warning_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.continue_copying, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startCopy();
                        }
                    })
                    .show();
        } else {
            startCopy();
        }
    }

    private void startCopy() {
        Intent serviceIntent = new Intent(this, CopyService.class);
        serviceIntent.setAction(CopyService.ACTION_START);
        serviceIntent.putExtra(CopyService.EXTRA_SOURCE,
                uriString(isUsbToPhone() ? usbFolder : phoneFolder));
        serviceIntent.putExtra(CopyService.EXTRA_DESTINATION,
                uriString(isUsbToPhone() ? phoneFolder : usbFolder));
        serviceIntent.putExtra(CopyService.EXTRA_MODE, selectedMode());
        serviceIntent.putExtra(CopyService.EXTRA_DIRECTION,
                getString(isUsbToPhone() ? R.string.usb_to_phone : R.string.phone_to_usb));
        serviceIntent.putExtra(CopyService.EXTRA_OVERWRITE, overwriteCheckBox.isChecked());
        serviceIntent.putExtra(CopyService.EXTRA_SKIP_IDENTICAL, skipIdenticalCheckBox.isChecked());
        ArrayList<String> custom = new ArrayList<>();
        for (Uri uri : customSources) {
            custom.add(uri.toString());
        }
        serviceIntent.putStringArrayListExtra(CopyService.EXTRA_CUSTOM_SOURCES, custom);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            copyRunning = true;
            startButton.setEnabled(false);
        } catch (RuntimeException exception) {
            android.util.Log.e("AutoUsbCopier", "Unable to start copy service", exception);
            DebugLog.log(this, "ERROR: Could not start the copy service.");
            Toast.makeText(this, R.string.error_copy_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showNoMediaDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.media_not_found_title)
                .setMessage(R.string.media_not_found_message)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.choose_custom, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        modeButtons[CopyService.MODE_CUSTOM].setChecked(true);
                        launchCustomFilePicker();
                    }
                })
                .setPositiveButton(R.string.copy_everything, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        modeButtons[CopyService.MODE_EVERYTHING].setChecked(true);
                        if (isUsbToPhone()) {
                            startCopy();
                        } else {
                            onStartCopyClicked();
                        }
                    }
                })
                .show();
    }

    private void handleNoMediaIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(CopyService.EXTRA_NO_MEDIA, false)) {
            intent.removeExtra(CopyService.EXTRA_NO_MEDIA);
            if (storageStatus != null) {
                storageStatus.post(new Runnable() {
                    @Override
                    public void run() {
                        showNoMediaDialog();
                    }
                });
            } else {
                getWindow().getDecorView().post(new Runnable() {
                    @Override
                    public void run() {
                        showNoMediaDialog();
                    }
                });
            }
        }
    }

    private void launchTreePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, requestCode);
        } catch (RuntimeException exception) {
            android.util.Log.e("AutoUsbCopier", "Unable to open the system folder picker", exception);
            Toast.makeText(this, R.string.error_folder_permission, Toast.LENGTH_LONG).show();
        }
    }

    private void launchCustomFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CUSTOM_FILES);
        } catch (RuntimeException exception) {
            android.util.Log.e("AutoUsbCopier", "Unable to open the system file picker", exception);
            Toast.makeText(this, R.string.error_read_file, Toast.LENGTH_LONG).show();
        }
    }

    private void addSelectedFiles(Intent data) {
        LinkedHashSet<Uri> selected = new LinkedHashSet<>(customSources);
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    addCustomUri(uri, data.getFlags());
                    selected.add(uri);
                }
            }
        } else if (data.getData() != null) {
            addCustomUri(data.getData(), data.getFlags());
            selected.add(data.getData());
        }
        customSources.clear();
        customSources.addAll(selected);
        saveCustomSources();
    }

    private void addCustomUri(Uri uri, int flags) {
        if (uri == null || customSources.contains(uri)) {
            return;
        }
        persistOrForget(uri, flags, PREF_CUSTOM_SOURCES);
        customSources.add(uri);
        saveCustomSources();
    }

    private void saveSelectedUri(Uri uri, int flags, String key) {
        persistOrForget(uri, flags, key);
        if (PREF_USB_FOLDER.equals(key)) {
            usbFolder = uri;
        } else {
            phoneFolder = uri;
        }
    }

    private void persistOrForget(Uri uri, int flags, String key) {
        String prior = PREF_CUSTOM_SOURCES.equals(key) ? null : preferences.getString(key, null);
        if (!rememberCheckBox.isChecked()) {
            releasePermission(prior);
            preferences.edit().remove(key).apply();
            return;
        }
        if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            releasePermission(prior);
            if (!PREF_CUSTOM_SOURCES.equals(key)) {
                preferences.edit().remove(key).apply();
            }
            Toast.makeText(this, R.string.permission_not_persisted, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            int accessFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, accessFlags);
            if (!PREF_CUSTOM_SOURCES.equals(key)) {
                preferences.edit().putString(key, uri.toString()).apply();
                if (prior != null && !prior.equals(uri.toString())) {
                    releasePermission(prior);
                }
            }
        } catch (SecurityException exception) {
            android.util.Log.w("AutoUsbCopier", "Android did not persist the selected URI", exception);
            releasePermission(prior);
            if (!PREF_CUSTOM_SOURCES.equals(key)) {
                preferences.edit().remove(key).apply();
            }
            Toast.makeText(this, R.string.permission_not_persisted, Toast.LENGTH_LONG).show();
        }
    }

    private void saveCustomSources() {
        if (!rememberCheckBox.isChecked()) {
            preferences.edit().remove(PREF_CUSTOM_SOURCES).apply();
            return;
        }
        Set<String> values = new LinkedHashSet<>();
        for (Uri uri : customSources) {
            values.add(uri.toString());
        }
        preferences.edit().putStringSet(PREF_CUSTOM_SOURCES, values).apply();
    }

    private void loadPreferences() {
        Uri savedUsb = parseUri(preferences.getString(PREF_USB_FOLDER, null));
        Uri savedPhone = parseUri(preferences.getString(PREF_PHONE_FOLDER, null));
        usbFolder = savedUsb != null && hasPersistedPermission(savedUsb) ? savedUsb : null;
        phoneFolder = savedPhone != null && hasPersistedPermission(savedPhone) ? savedPhone : null;
        Set<String> savedCustom = preferences.getStringSet(PREF_CUSTOM_SOURCES, Collections.<String>emptySet());
        if (savedCustom != null) {
            for (String value : new LinkedHashSet<>(savedCustom)) {
                Uri uri = parseUri(value);
                if (uri != null && hasPersistedPermission(uri, false)) {
                    customSources.add(uri);
                }
            }
        }
    }

    private void restoreState(Bundle state) {
        usbFolder = parseUri(state.getString("usb_folder"));
        phoneFolder = parseUri(state.getString("phone_folder"));
        ArrayList<String> values = state.getStringArrayList("custom_sources");
        if (values != null) {
            for (String value : values) {
                Uri uri = parseUri(value);
                if (uri != null) {
                    customSources.add(uri);
                }
            }
        }
        copyRunning = state.getBoolean("copy_running", false);
    }

    private boolean hasPersistedPermission(Uri uri) {
        return hasPersistedPermission(uri, true);
    }

    private boolean hasPersistedPermission(Uri uri, boolean requireWrite) {
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri())) {
                return permission.isReadPermission() && (!requireWrite || permission.isWritePermission());
            }
        }
        return false;
    }

    private void releasePermission(String value) {
        Uri uri = parseUri(value);
        if (uri == null) {
            return;
        }
        int flags = 0;
        for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri())) {
                if (permission.isReadPermission()) {
                    flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                }
                if (permission.isWritePermission()) {
                    flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                }
                break;
            }
        }
        if (flags == 0) {
            return;
        }
        try {
            getContentResolver().releasePersistableUriPermission(uri, flags);
        } catch (SecurityException exception) {
            android.util.Log.w("AutoUsbCopier", "Could not release previous folder access", exception);
        }
    }

    private void registerCopyResultReceiver() {
        if (resultReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(CopyService.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(copyResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(copyResultReceiver, filter);
        }
        resultReceiverRegistered = true;
    }

    private void registerStorageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(storageChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(storageChangedReceiver, filter);
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && preferences != null
                && preferences.getBoolean(PREF_AUTO_NOTIFICATION, true)
                && !preferences.getBoolean(PREF_NOTIFICATION_PERMISSION_REQUESTED, false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !isFinishing()) {
            preferences.edit().putBoolean(PREF_NOTIFICATION_PERMISSION_REQUESTED, true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void clearCustomSources() {
        Set<String> saved = preferences.getStringSet(PREF_CUSTOM_SOURCES, Collections.<String>emptySet());
        if (saved != null) {
            for (String value : new LinkedHashSet<>(saved)) {
                releasePermission(value);
            }
        }
        customSources.clear();
        preferences.edit().remove(PREF_CUSTOM_SOURCES).apply();
        refreshFolderLabels();
    }

    private void showNotificationPermissionExplanation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showAboutDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);
        TextView aboutText = new TextView(this);
        aboutText.setText(R.string.about_text);
        aboutText.setTextSize(16);
        content.addView(aboutText, fullWidth());
        debugCheckBox = new CheckBox(this);
        debugCheckBox.setText(R.string.debug);
        debugCheckBox.setChecked(DebugLog.isEnabled(this));
        debugCheckBox.setOnCheckedChangeListener((button, checked) -> {
            if (pendingDebugPermission) {
                return;
            }
            if (checked && requiresLegacyStoragePermission()
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDebugPermission = true;
                debugCheckBox.setEnabled(false);
                debugCheckBox.setChecked(false);
                Toast.makeText(this, R.string.debug_permission_required, Toast.LENGTH_LONG).show();
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_DEBUG_STORAGE);
                return;
            }
            DebugLog.setEnabled(this, checked);
            if (checked) {
                DebugLog.log(this, "Debug logging enabled.");
                Toast.makeText(this, R.string.debug_log_enabled, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.debug_log_disabled, Toast.LENGTH_SHORT).show();
            }
        });
        content.addView(debugCheckBox, fullWidth());
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private boolean requiresLegacyStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    private int selectedMode() {
        if (modeGroup != null) {
            int checkedId = modeGroup.getCheckedRadioButtonId();
            for (RadioButton button : modeButtons) {
                if (button.getId() == checkedId) {
                    return (Integer) button.getTag();
                }
            }
        }
        return clampMode(preferences.getInt(PREF_MODE, CopyService.MODE_PHOTOS));
    }

    private static int clampMode(int mode) {
        return mode < CopyService.MODE_PHOTOS || mode > CopyService.MODE_CUSTOM
                ? CopyService.MODE_PHOTOS : mode;
    }

    private boolean isUsbToPhone() {
        return directionGroup == null
                ? preferences.getInt(PREF_DIRECTION, 0) == 0
                : directionGroup.getCheckedRadioButtonId() == usbToPhoneButton.getId();
    }

    private static Uri parseUri(String value) {
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    private static String uriString(Uri uri) {
        return uri == null ? null : uri.toString();
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
