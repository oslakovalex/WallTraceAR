
package ru.walltrace.ar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 41;
    private static final int CAMERA_PERMISSION = 42;

    private GLSurfaceView surfaceView;
    private WallRenderer renderer;
    private Session session;
    private boolean installRequested;
    private TextView status;
    private Button lockButton;
    private boolean locked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION
            );
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101820);

        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        renderer = new WallRenderer(this);
        surfaceView.setRenderer(renderer);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        root.addView(
                surfaceView,
                new LinearLayout.LayoutParams(-1, 0, 1f)
        );

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(10));
        panel.setBackgroundColor(0xF2162029);

        TextView title = new TextView(this);
        title.setText("WallTrace AR • перенос эскиза на стену");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(32)));

        status = new TextView(this);
        status.setText(
                "1. Загрузите эскиз • 2. Наведите камеру на стену • 3. Коснитесь стены"
        );
        status.setTextColor(0xFFD7E2EA);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        panel.addView(status, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button load = makeButton("Загрузить эскиз");
        load.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_IMAGE);
        });
        row.addView(load, new LinearLayout.LayoutParams(0, dp(46), 1f));

        lockButton = makeButton("Закрепить");
        lockButton.setOnClickListener(v -> {
            locked = !locked;
            renderer.setLocked(locked);
            lockButton.setText(locked ? "Разблокировать" : "Закрепить");
            setStatus(locked
                    ? "Эскиз закреплён. Перемещение отключено."
                    : "Коснитесь нужного места на стене, чтобы переместить эскиз.");
        });
        row.addView(lockButton, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button reset = makeButton("Сбросить");
        reset.setOnClickListener(v -> {
            locked = false;
            renderer.setLocked(false);
            lockButton.setText("Закрепить");
            renderer.clearAnchor();
            setStatus("Привязка сброшена. Коснитесь стены для размещения эскиза.");
        });
        row.addView(reset, new LinearLayout.LayoutParams(0, dp(46), 1f));

        panel.addView(row);

        panel.addView(makeLabel("Размер эскиза"));
        SeekBar scale = new SeekBar(this);
        scale.setMax(180);
        scale.setProgress(60);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                renderer.setScale(0.25f + progress / 60f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        panel.addView(scale, new LinearLayout.LayoutParams(-1, dp(30)));

        panel.addView(makeLabel("Прозрачность изображения"));
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(100);
        opacity.setProgress(65);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                renderer.setOpacity(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        panel.addView(opacity, new LinearLayout.LayoutParams(-1, dp(30)));

        TextView note = new TextView(this);
        note.setText(
                "Лучше работает при хорошем освещении и на стене с заметной фактурой. "
                        + "Нужна поддержка Google Play Services for AR."
        );
        note.setTextColor(0xFFB4C2CC);
        note.setTextSize(10);
        note.setGravity(Gravity.CENTER);
        panel.addView(note, new LinearLayout.LayoutParams(-1, dp(34)));

        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        surfaceView.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (!renderer.hasImage()) {
                    setStatus("Сначала нажмите «Загрузить эскиз».");
                } else if (locked) {
                    setStatus("Эскиз закреплён. Нажмите «Разблокировать», чтобы изменить положение.");
                } else {
                    renderer.requestPlace(event.getX(), event.getY());
                    setStatus("Ищу стену… Медленно поводите камерой и коснитесь нужного места.");
                }
                return true;
            }
            return true;
        });
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xFFE5EDF3);
        label.setTextSize(12);
        label.setPadding(dp(2), dp(3), 0, 0);
        return label;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Доступен WallRenderer, который находится в том же пакете.
    public void setStatus(String text) {
        runOnUiThread(() -> {
            if (status != null) {
                status.setText(text);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            Uri uri = data.getData();

            try (InputStream input = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);

                if (bitmap == null) {
                    throw new IllegalArgumentException("Не удалось прочитать изображение.");
                }

                renderer.setImage(bitmap);
                locked = false;
                renderer.setLocked(false);
                lockButton.setText("Закрепить");

                setStatus(
                        "Эскиз загружен. Наведите камеру на стену и коснитесь места размещения."
                );
            } catch (Exception exception) {
                new AlertDialog.Builder(this)
                        .setTitle("Не удалось открыть изображение")
                        .setMessage(exception.getMessage() == null
                                ? "Проверьте формат изображения."
                                : exception.getMessage())
                        .setPositiveButton("ОК", null)
                        .show();
            }
        }
    }

    private void createSession() throws Exception {
        if (session != null) {
            return;
        }

        ArCoreApk.InstallStatus installStatus =
                ArCoreApk.getInstance().requestInstall(this, !installRequested);

        if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
            installRequested = true;
            return;
        }

        session = new Session(this);

        Config config = new Config(session);
        config.setPlaneFindingMode(Config.PlaneFindingMode.VERTICAL);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);

        session.configure(config);
        renderer.setSession(session);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            createSession();

            if (session != null) {
                session.resume();
                surfaceView.onResume();
            }
        } catch (UnavailableArcoreNotInstalledException
                 | UnavailableApkTooOldException
                 | UnavailableSdkTooOldException
                 | UnavailableDeviceNotCompatibleException exception) {
            showArError(
                    "Не поддерживается ARCore или не установлены сервисы дополненной реальности."
            );
        } catch (Exception exception) {
            showArError("Не удалось запустить AR: " + exception.getMessage());
        }
    }

    private void showArError(String message) {
        setStatus(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        if (session != null) {
            session.pause();
        }
        surfaceView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            recreate();
        } else if (requestCode == CAMERA_PERMISSION) {
            showArError("Для работы приложения нужен доступ к камере.");
        }
    }
}
