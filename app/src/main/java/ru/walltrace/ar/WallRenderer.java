package ru.walltrace.ar;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class WallRenderer implements GLSurfaceView.Renderer {
    private final MainActivity activity;
    private volatile Session session;
    private volatile Bitmap pendingBitmap;
    private volatile boolean hasImage;
    private volatile boolean locked;
    private volatile float scale = 1.25f;
    private volatile float opacity = 0.65f;
    private volatile float tapX = -1, tapY = -1;
    private final AtomicBoolean placeRequested = new AtomicBoolean(false);
    private final AtomicBoolean clearRequested = new AtomicBoolean(false);
    private Anchor anchor;
    private int cameraTexture = -1;
    private int imageTexture = -1;
    private boolean cameraTextureBound;
    private int screenWidth = 1, screenHeight = 1;
    private int cameraProgram, imageProgram;
    private int cameraPosition, cameraUv, cameraSampler;
    private int imagePosition, imageUv, imageMvp, imageSampler, imageOpacity;
    private FloatBuffer quadVertices, quadUvs, imageVertices, imageUvs;
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mv = new float[16];
    private final float[] mvp = new float[16];
    private final float[] cameraUvTransformed = new float[8];
    private final float[] ndcVertices = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
    private final FloatBuffer ndcBuffer = directFloatBuffer(ndcVertices);
    private final float[] imageUvData = {0f,1f, 1f,1f, 0f,0f, 1f,0f};
    private final FloatBuffer imageUvBuffer = directFloatBuffer(imageUvData);
    private float imageAspect = 1f;
    private final float[] planeQuad = {-0.5f,0f,-0.5f, 0.5f,0f,-0.5f, -0.5f,0f,0.5f, 0.5f,0f,0.5f};

    WallRenderer(MainActivity activity) { this.activity = activity; }
    void setSession(Session s) { session = s; }
    boolean hasImage() { return hasImage || pendingBitmap != null; }
    void setLocked(boolean value) { locked = value; }
    void setScale(float value) { scale = Math.max(0.25f, Math.min(3.25f, value)); }
    void setOpacity(float value) { opacity = Math.max(0f, Math.min(1f, value)); }
    void requestPlace(float x, float y) { tapX = x; tapY = y; placeRequested.set(true); }
    void clearAnchor() { clearRequested.set(true); }
    void setImage(Bitmap bitmap) { pendingBitmap = bitmap; hasImage = true; }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        cameraProgram = createProgram(CAMERA_VERTEX, CAMERA_FRAGMENT);
        imageProgram = createProgram(IMAGE_VERTEX, IMAGE_FRAGMENT);
        cameraPosition = GLES20.glGetAttribLocation(cameraProgram, "a_Position");
        cameraUv = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord");
        cameraSampler = GLES20.glGetUniformLocation(cameraProgram, "sTexture");
        imagePosition = GLES20.glGetAttribLocation(imageProgram, "a_Position");
        imageUv = GLES20.glGetAttribLocation(imageProgram, "a_TexCoord");
        imageMvp = GLES20.glGetUniformLocation(imageProgram, "u_MVP");
        imageSampler = GLES20.glGetUniformLocation(imageProgram, "sTexture");
        imageOpacity = GLES20.glGetUniformLocation(imageProgram, "u_Opacity");
        quadVertices = ndcBuffer;
        quadUvs = directFloatBuffer(new float[]{0f,0f, 1f,0f, 0f,1f, 1f,1f});
        imageVertices = directFloatBuffer(planeQuad);
        imageUvs = imageUvBuffer;
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0); cameraTexture = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        bindCameraTextureIfNeeded();
    }

    private void bindCameraTextureIfNeeded() {
        Session s = session;
        if (s != null && cameraTexture > 0 && !cameraTextureBound) {
            try { s.setCameraTextureNames(new int[]{cameraTexture}); cameraTextureBound = true; }
            catch (Exception ignored) {}
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        screenWidth = Math.max(1, width); screenHeight = Math.max(1, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (s == null) return;
        bindCameraTextureIfNeeded();
        try {
            Frame frame = s.update();
            if (clearRequested.compareAndSet(true, false)) { if (anchor != null) anchor.detach(); anchor = null; }
            Camera camera = frame.getCamera();
            drawCamera(frame);
            if (camera.getTrackingState() != TrackingState.TRACKING) return;
            camera.getProjectionMatrix(projection, 0, 0.1f, 100f);
            camera.getViewMatrix(view, 0);
            if (placeRequested.compareAndSet(true, false) && !locked && hasImage()) placeOnWall(frame, tapX, tapY);
            uploadPendingImage();
            if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING && imageTexture > 0) drawImage();
        } catch (Throwable t) {
            // ARCore may briefly lose tracking while the camera is moving; keep the preview alive.
        }
    }

    private void placeOnWall(Frame frame, float x, float y) {
        try {
            for (HitResult hit : frame.hitTest(x, y)) {
                if (!(hit.getTrackable() instanceof Plane)) continue;
                Plane plane = (Plane) hit.getTrackable();
                if (plane.getType() != Plane.Type.VERTICAL || plane.getTrackingState() != TrackingState.TRACKING) continue;
                if (!plane.isPoseInPolygon(hit.getHitPose())) continue;
                Anchor next = hit.createAnchor();
                if (anchor != null) anchor.detach();
                anchor = next;
                activity.runOnUiThread(() -> activity.setStatus("Эскиз размещён на стене. Отрегулируйте размер и прозрачность, затем нажмите «Закрепить»."));
                return;
            }
            activity.runOnUiThread(() -> activity.setStatus("Стена пока не распознана. Медленно поводите камерой по стене, затем коснитесь её ещё раз."));
        } catch (Exception e) {
            activity.runOnUiThread(() -> activity.setStatus("Не удалось закрепить изображение. Попробуйте ещё раз при хорошем освещении."));
        }
    }

    private void uploadPendingImage() {
        Bitmap bitmap = pendingBitmap;
        if (bitmap == null) return;
        pendingBitmap = null;
        if (anchor != null) { anchor.detach(); anchor = null; }
        if (imageTexture > 0) { int[] old = {imageTexture}; GLES20.glDeleteTextures(1, old, 0); imageTexture = 0; }
        int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); imageTexture = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        imageAspect = (float) bitmap.getWidth() / Math.max(1, bitmap.getHeight());
        bitmap.recycle();
        updateImageGeometry();
    }

    private void updateImageGeometry() {
        float w = scale;
        float h = w / Math.max(0.01f, imageAspect);
        float[] vertices = {-w/2f,0f,-h/2f, w/2f,0f,-h/2f, -w/2f,0f,h/2f, w/2f,0f,h/2f};
        imageVertices = directFloatBuffer(vertices);
    }

    private float lastScale = -1f;
    private void drawImage() {
        if (Math.abs(lastScale - scale) > 0.001f) { updateImageGeometry(); lastScale = scale; }
        anchor.getPose().toMatrix(model, 0);
        Matrix.multiplyMM(mv, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0);
        GLES20.glUseProgram(imageProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexture);
        GLES20.glUniform1i(imageSampler, 0);
        GLES20.glUniform1f(imageOpacity, opacity);
        GLES20.glUniformMatrix4fv(imageMvp, 1, false, mvp, 0);
        GLES20.glEnableVertexAttribArray(imagePosition);
        GLES20.glVertexAttribPointer(imagePosition, 3, GLES20.GL_FLOAT, false, 0, imageVertices);
        GLES20.glEnableVertexAttribArray(imageUv);
        GLES20.glVertexAttribPointer(imageUv, 2, GLES20.GL_FLOAT, false, 0, imageUvs);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(imagePosition); GLES20.glDisableVertexAttribArray(imageUv);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawCamera(Frame frame) {
        float[] uv = new float[8];
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcVertices, Coordinates2d.TEXTURE_NORMALIZED, uv);
        FloatBuffer uvBuffer = directFloatBuffer(uv);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(cameraProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glUniform1i(cameraSampler, 0);
        GLES20.glEnableVertexAttribArray(cameraPosition);
        GLES20.glVertexAttribPointer(cameraPosition, 2, GLES20.GL_FLOAT, false, 0, ndcBuffer);
        GLES20.glEnableVertexAttribArray(cameraUv);
        GLES20.glVertexAttribPointer(cameraUv, 2, GLES20.GL_FLOAT, false, 0, uvBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(cameraPosition); GLES20.glDisableVertexAttribArray(cameraUv);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private static FloatBuffer directFloatBuffer(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0); return b;
    }
    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) { String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new RuntimeException("Shader compile: " + log); }
        return shader;
    }
    private static int createProgram(String vertex, String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex), fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p);
        int[] status = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) { String log = GLES20.glGetProgramInfoLog(p); GLES20.glDeleteProgram(p); throw new RuntimeException("Program link: " + log); }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs); return p;
    }

    private static final String CAMERA_VERTEX = "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){ gl_Position=a_Position; v_TexCoord=a_TexCoord; }";
    private static final String CAMERA_FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES sTexture; varying vec2 v_TexCoord; void main(){ gl_FragColor=texture2D(sTexture,v_TexCoord); }";
    private static final String IMAGE_VERTEX = "attribute vec4 a_Position; attribute vec2 a_TexCoord; uniform mat4 u_MVP; varying vec2 v_TexCoord; void main(){ gl_Position=u_MVP*a_Position; v_TexCoord=a_TexCoord; }";
    private static final String IMAGE_FRAGMENT = "precision mediump float; uniform sampler2D sTexture; uniform float u_Opacity; varying vec2 v_TexCoord; void main(){ vec4 c=texture2D(sTexture,v_TexCoord); gl_FragColor=vec4(c.rgb,c.a*u_Opacity); }";
}
