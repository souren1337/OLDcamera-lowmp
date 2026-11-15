package com.pocketretro.camera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.MediaController;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/** PocketRetro Android prototype. All media remains on-device. */
public class MainActivity extends ComponentActivity {
    private static final int[][] RESOLUTIONS = {
            {640, 480}, {800, 600}, {1024, 768}, {1280, 960}, {1600, 1200}
    };
    private static final String[] RESOLUTION_LABELS = {
            "0.3 MP • 640×480", "0.5 MP • 800×600", "0.8 MP • 1024×768",
            "1.2 MP • 1280×960", "1.9 MP • 1600×1200"
    };

    private PreviewView previewView;
    private ImageView importedImage;
    private VideoView importedVideo;
    private TextView statusText;
    private TextView profileText;
    private Button shutterButton;
    private Button modeButton;
    private Button flashButton;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private Camera camera;
    private CameraProfile selectedProfile = CameraProfile.VGA_2001;
    private int resolutionIndex = 1;
    private boolean videoMode = false;
    private boolean torchEnabled = false;
    private boolean dateStamp = true;

    private final ActivityResultLauncher<String[]> imagePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onImageSelected);
    private final ActivityResultLauncher<String[]> videoPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onVideoSelected);
    private final ActivityResultLauncher<String[]> permissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean storageReady = Build.VERSION.SDK_INT > 28 ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                if (hasCameraPermission() && storageReady) startCamera();
                else setStatus("CAMERA / STORAGE PERMISSION REQUIRED");
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resolutionIndex = getPreferences(MODE_PRIVATE).getInt("resolution", 0);
        dateStamp = getPreferences(MODE_PRIVATE).getBoolean("dateStamp", true);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(buildInterface());
        ensurePermissions();
    }

    private View buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 22, 24));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(18));
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView brand = text("POCKETRETRO", 22, Color.rgb(217, 228, 188), true);
        brand.setLetterSpacing(0.10f);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView version = text("DIGICAM / 01", 11, Color.rgb(132, 157, 133), true);
        header.addView(version);
        page.addView(header);

        TextView hint = text("DIGITAL MEMORIES // 1988 — 2005", 10, Color.rgb(121, 140, 124), true);
        hint.setPadding(dp(2), 0, 0, dp(10));
        page.addView(hint);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackground(roundRect(0xFF242C2C, 0xFF3B4844, dp(18)));
        shell.setPadding(dp(9), dp(9), dp(9), dp(9));
        LinearLayout.LayoutParams shellParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(390));
        page.addView(shell, shellParams);

        FrameLayout viewport = new FrameLayout(this);
        viewport.setBackground(roundRect(0xFF182322, 0xFF65766A, dp(10)));
        shell.addView(viewport, new FrameLayout.LayoutParams(-1, -1));
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        viewport.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        importedImage = new ImageView(this);
        importedImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        importedImage.setBackgroundColor(Color.BLACK);
        importedImage.setVisibility(View.GONE);
        viewport.addView(importedImage, new FrameLayout.LayoutParams(-1, -1));
        importedVideo = new VideoView(this);
        importedVideo.setBackgroundColor(Color.BLACK);
        importedVideo.setVisibility(View.GONE);
        viewport.addView(importedVideo, new FrameLayout.LayoutParams(-1, -1));

        TextView overlay = text("● REC READY     VGA CMOS     00:00", 10, 0xFFD7E3B5, true);
        overlay.setPadding(dp(10), dp(7), dp(10), dp(7));
        overlay.setBackground(roundRect(0xAA121B19, 0x00000000, dp(5)));
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        overlayParams.setMargins(dp(10), dp(10), 0, 0);
        viewport.addView(overlay, overlayParams);

        Button liveButton = button("LIVE VIEW", 10, 0xFFB8D77A, 0xFF1D2A22);
        FrameLayout.LayoutParams liveParams = new FrameLayout.LayoutParams(-2, dp(34), Gravity.BOTTOM | Gravity.RIGHT);
        liveParams.setMargins(0, 0, dp(10), dp(10));
        viewport.addView(liveButton, liveParams);
        liveButton.setOnClickListener(v -> showLiveView());

        TextView profileInfo = text(selectedProfile.label + "  /  " + selectedProfile.subtitle,
                11, Color.rgb(199, 216, 168), true);
        profileInfo.setGravity(Gravity.CENTER);
        profileInfo.setPadding(0, dp(9), 0, dp(3));
        page.addView(profileInfo);
        profileText = profileInfo;

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER);
        modeRow.setPadding(0, dp(8), 0, dp(6));
        page.addView(modeRow);
        modeButton = button("PHOTO MODE", 12, 0xFF2E3B33, 0xFFE1E8CE);
        modeRow.addView(modeButton, new LinearLayout.LayoutParams(dp(130), dp(38)));
        modeButton.setOnClickListener(v -> {
            videoMode = !videoMode;
            modeButton.setText(videoMode ? "VIDEO MODE" : "PHOTO MODE");
            shutterButton.setText(videoMode ? "●  REC / STOP" : "◎  TAKE PHOTO");
            setStatus(videoMode ? "VIDEO // SD QUALITY" : "PHOTO // " + RESOLUTION_LABELS[resolutionIndex]);
        });

        shutterButton = button("◎  TAKE PHOTO", 17, 0xFFB7D96C, 0xFF172018);
        shutterButton.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(-1, dp(58));
        shutterParams.setMargins(dp(20), dp(4), dp(20), dp(8));
        page.addView(shutterButton, shutterParams);
        shutterButton.setOnClickListener(v -> {
            if (videoMode) toggleRecording(); else takePhoto();
        });

        TextView chooseCamera = text("CHOOSE CAMERA  ↓", 10, 0xFF879A83, true);
        chooseCamera.setGravity(Gravity.CENTER);
        chooseCamera.setPadding(0, dp(4), 0, dp(6));
        page.addView(chooseCamera);
        HorizontalScrollView cameraScroll = new HorizontalScrollView(this);
        cameraScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cameraChoices = new LinearLayout(this);
        cameraChoices.setOrientation(LinearLayout.HORIZONTAL);
        cameraScroll.addView(cameraChoices);
        for (CameraProfile profile : CameraProfile.values()) {
            Button item = button(profile.label, 10,
                    profile == selectedProfile ? 0xFFB7D96C : 0xFF27322D,
                    profile == selectedProfile ? 0xFF182018 : 0xFFD1DCC5);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-2, dp(42));
            itemParams.setMargins(0, 0, dp(7), 0);
            cameraChoices.addView(item, itemParams);
            item.setOnClickListener(v -> {
                selectedProfile = profile;
                profileText.setText(profile.label + "  /  " + profile.subtitle);
                setStatus("CAMERA SELECTED // " + profile.label);
                for (int i = 0; i < cameraChoices.getChildCount(); i++) {
                    Button other = (Button) cameraChoices.getChildAt(i);
                    other.setBackground(roundRect(profile.label.equals(other.getText().toString())
                            ? 0xFFB7D96C : 0xFF27322D, 0xFF53634E, dp(9)));
                    other.setTextColor(profile.label.equals(other.getText().toString()) ? 0xFF182018 : 0xFFD1DCC5);
                }
            });
        }
        page.addView(cameraScroll);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(0, dp(12), 0, dp(5));
        page.addView(tools);
        Button settings = button("SETTINGS", 10, 0xFF28342E, 0xFFD4DFC6);
        Button importPhoto = button("OPEN PHOTO", 10, 0xFF28342E, 0xFFD4DFC6);
        Button importVideo = button("OPEN VIDEO", 10, 0xFF28342E, 0xFFD4DFC6);
        Button flash = button("FLASH OFF", 10, 0xFF28342E, 0xFFD4DFC6);
        for (Button b : new Button[]{settings, importPhoto, importVideo, flash}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            tools.addView(b, p);
        }
        settings.setOnClickListener(v -> showSettings());
        importPhoto.setOnClickListener(v -> imagePicker.launch(new String[]{"image/*"}));
        importVideo.setOnClickListener(v -> videoPicker.launch(new String[]{"video/*"}));
        flashButton = flash;
        flash.setOnClickListener(v -> toggleFlash());

        statusText = text("READY  //  KEEP IT IMPERFECT", 10, 0xFF91A18C, true);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(10), 0, 0);
        page.addView(statusText);
        return scroll;
    }

    private void ensurePermissions() {
        boolean cameraGranted = hasCameraPermission();
        boolean legacyStorageGranted = Build.VERSION.SDK_INT > 28 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted && legacyStorageGranted) {
            startCamera();
            return;
        }
        if (Build.VERSION.SDK_INT <= 28)
            permissionRequest.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE});
        else permissionRequest.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, videoCapture);
                setStatus("CAMERA ONLINE // " + RESOLUTION_LABELS[resolutionIndex]);
            } catch (ExecutionException | InterruptedException e) {
                setStatus("CAMERA ERROR // " + e.getClass().getSimpleName());
            } catch (Exception e) {
                setStatus("CAMERA NOT AVAILABLE");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) { setStatus("WAITING FOR CAMERA"); return; }
        File temp = new File(getCacheDir(), "pocketretro_capture.jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(temp).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults output) {
                try {
                    Bitmap input = BitmapFactory.decodeFile(temp.getAbsolutePath());
                    Bitmap cooked = RetroProcessor.apply(input, selectedProfile,
                            RESOLUTIONS[resolutionIndex][0], RESOLUTIONS[resolutionIndex][1], dateStamp);
                    Uri saved = saveBitmap(cooked, "PR_" + timestamp() + ".jpg");
                    input.recycle(); cooked.recycle(); temp.delete();
                    setStatus("SAVED TO PHOTOS // " + RESOLUTION_LABELS[resolutionIndex]);
                    Toast.makeText(MainActivity.this, "Photo saved to PocketRetro", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    setStatus("PHOTO PROCESSING FAILED");
                }
            }
            @Override public void onError(ImageCaptureException exception) {
                setStatus("CAPTURE FAILED // " + exception.getMessage());
            }
        });
    }

    private void toggleRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
            shutterButton.setText("●  REC / STOP");
            setStatus("STOPPING VIDEO…");
            return;
        }
        if (videoCapture == null) { setStatus("VIDEO CAMERA NOT READY"); return; }
        File folder = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PocketRetro");
        if (!folder.exists() && !folder.mkdirs()) { setStatus("CANNOT CREATE MOVIE FOLDER"); return; }
        File file = new File(folder, "PR_" + timestamp() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(file).build();
        androidx.camera.video.PendingRecording pending = videoCapture.getOutput().prepareRecording(this, options);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                shutterButton.setText("■  STOP RECORDING");
                setStatus("● RECORDING // SD VIDEO");
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                activeRecording = null;
                shutterButton.setText("●  REC / STOP");
                if (!finalized.hasError()) {
                    try {
                        publishVideo(file);
                        setStatus("VIDEO SAVED TO GALLERY // " + file.getName());
                    } catch (Exception e) {
                        setStatus("VIDEO SAVED IN APP FOLDER // " + file.getName());
                    }
                } else setStatus("VIDEO ERROR // " + finalized.getError());
            }
        });
    }


    private void publishVideo(File source) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PocketRetro");
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Cannot create gallery entry");
            try (InputStream input = new java.io.FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Cannot open gallery output");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            }
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PocketRetro");
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create Movies folder");
            File destination = new File(folder, source.getName());
            try (InputStream input = new java.io.FileInputStream(source);
                 OutputStream output = new java.io.FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            }
            MediaScannerConnection.scanFile(this, new String[]{destination.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        }
    }

    private Uri saveBitmap(Bitmap bitmap, String filename) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketRetro");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");
        try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
            if (stream == null) throw new IllegalStateException("Cannot open output stream");
            bitmap.compress(Bitmap.CompressFormat.JPEG, 91, stream);
        }
        return uri;
    }

    private void onImageSelected(Uri uri) {
        if (uri == null) return;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            Bitmap source = BitmapFactory.decodeStream(stream);
            Bitmap cooked = RetroProcessor.apply(source, selectedProfile,
                    RESOLUTIONS[resolutionIndex][0], RESOLUTIONS[resolutionIndex][1], dateStamp);
            Uri saved = saveBitmap(cooked, "PR_IMPORT_" + timestamp() + ".jpg");
            importedVideo.stopPlayback();
            importedVideo.setVisibility(View.GONE);
            previewView.setVisibility(View.VISIBLE);
            importedImage.setImageBitmap(cooked);
            importedImage.setVisibility(View.VISIBLE);
            setStatus("IMPORTED PHOTO DEVELOPED // " + RESOLUTION_LABELS[resolutionIndex]);
            source.recycle();
            // The view owns the processed bitmap until the next import.
        } catch (Exception e) {
            setStatus("CANNOT OPEN THIS PHOTO");
        }
    }

    private void onVideoSelected(Uri uri) {
        if (uri == null) return;
        try {
            importedImage.setVisibility(View.GONE);
            previewView.setVisibility(View.GONE);
            importedVideo.setVisibility(View.VISIBLE);
            importedVideo.setVideoURI(uri);
            importedVideo.setMediaController(new MediaController(this));
            importedVideo.requestFocus();
            importedVideo.start();
            setStatus("VIDEO IMPORTED // PREVIEW MODE");
        } catch (Exception e) {
            setStatus("CANNOT OPEN THIS VIDEO");
        }
    }

    private void showLiveView() {
        importedVideo.stopPlayback();
        importedVideo.setVisibility(View.GONE);
        importedImage.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        setStatus("LIVE CAMERA VIEW");
    }

    private void showSettings() {
        String[] items = new String[RESOLUTION_LABELS.length + 1];
        System.arraycopy(RESOLUTION_LABELS, 0, items, 0, RESOLUTION_LABELS.length);
        items[items.length - 1] = "Date stamp: " + (dateStamp ? "ON" : "OFF");
        new AlertDialog.Builder(this)
                .setTitle("CAMERA SETTINGS")
                .setItems(items, (dialog, which) -> {
                    if (which < RESOLUTION_LABELS.length) {
                        resolutionIndex = which;
                        getPreferences(MODE_PRIVATE).edit().putInt("resolution", resolutionIndex).apply();
                        setStatus("OUTPUT // " + RESOLUTION_LABELS[resolutionIndex]);
                    } else {
                        dateStamp = !dateStamp;
                        getPreferences(MODE_PRIVATE).edit().putBoolean("dateStamp", dateStamp).apply();
                        setStatus("DATE STAMP // " + (dateStamp ? "ON" : "OFF"));
                    }
                })
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void toggleFlash() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) { setStatus("FLASH NOT SUPPORTED"); return; }
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        flashButton.setText(torchEnabled ? "FLASH ON" : "FLASH OFF");
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    private String timestamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private Button button(String label, int size, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(size);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(roundRect(background, 0xFF53634E, dp(9)));
        return button;
    }

    private GradientDrawable roundRect(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (Color.alpha(stroke) > 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    @Override protected void onPause() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        super.onPause();
    }
}
