package com.aman_kumar.giphyshot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class OverlayActivity extends Activity {

//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//
//        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
//                WindowManager.LayoutParams.FLAG_FULLSCREEN,
//                WindowManager.LayoutParams.FLAG_FULLSCREEN,
//                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
//                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
//                PixelFormat.TRANSLUCENT);
//
//        WindowManager wm = (WindowManager) getApplicationContext()
//                .getSystemService(Context.WINDOW_SERVICE);
//
//        ViewGroup mTopView = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_overlay, null);
//        getWindow().setAttributes(params);
//        wm.addView(mTopView, params);
//
////        setContentView(R.layout.activity_overlay);
//    }

    static final String TAG = OverlayActivity.class.getName();
    static final int REQUEST_CODE = 100;
    static String STORE_DIRECTORY;
    static int IMAGES_PRODUCED;
    static final String SCREENCAP_NAME = "GiphyShot";
    static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    static MediaProjection sMediaProjection;

    static MediaProjectionManager mProjectionManager;
    static ImageReader mImageReader;
    public static Handler mHandler;
    public static boolean isRecording;
    static Display mDisplay;
    static VirtualDisplay mVirtualDisplay;
    private int mDensity;
    private int mWidth;
    private int mHeight;
    private int mRotation;
    private OrientationChangeCallback mOrientationChangeCallback;

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            FileOutputStream fos = null;
            Bitmap bitmap = null;

            try {
                image = mImageReader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mWidth;

                    // create bitmap
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    // write bitmap to a file
                    fos = new FileOutputStream(STORE_DIRECTORY + "/myscreen_" + IMAGES_PRODUCED + ".png");
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);

                    IMAGES_PRODUCED++;
                    Log.e(TAG, "captured image: " + IMAGES_PRODUCED);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }

                if (bitmap != null) {
                    bitmap.recycle();
                }

                if (image != null) {
                    image.close();
                }
            }
        }
    }

    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.e("ScreenCapture", "stopping projection.");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                    sMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                }
            });
        }
    }

    /****************************************** Activity Lifecycle methods ************************/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overlay);

        // call for the projection manager
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // start projection
        Button startButton = (Button) findViewById(R.id.startButton);
        startButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                startProjection();
            }
        });

        // stop projection
        Button stopButton = (Button) findViewById(R.id.stopButton);
        stopButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                stopProjection();
            }
        });

        // start capture handling thread
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE) {
            sMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);

            if (sMediaProjection != null) {
                File externalFilesDir = getExternalFilesDir(null);
                if (externalFilesDir != null) {
                    STORE_DIRECTORY = externalFilesDir.getAbsolutePath() + "/screenshots/";
                    File storeDirectory = new File(STORE_DIRECTORY);
                    if (!storeDirectory.exists()) {
                        boolean success = storeDirectory.mkdirs();
                        if (!success) {
                            Log.e(TAG, "failed to create file storage directory.");
                            return;
                        }
                    }
                } else {
                    Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.");
                    return;
                }

                // display metrics
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                mDensity = metrics.densityDpi;
                mDisplay = getWindowManager().getDefaultDisplay();

                // create virtual display depending on device width / height
                createVirtualDisplay();

                // register orientation change callback
                mOrientationChangeCallback = new OrientationChangeCallback(this);
                if (mOrientationChangeCallback.canDetectOrientation()) {
                    mOrientationChangeCallback.enable();
                }

                // register media projection stop callback
                sMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);

                // recording flag
                isRecording = true;

                // starting service for floating button
                Intent i = new Intent(OverlayActivity.this, OverlayService.class);
                startService(i);
                finish();
            }
        }
    }

    /****************************************** UI Widget Callbacks *******************************/
    private void startProjection() {
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    private void stopProjection() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sMediaProjection != null) {
                    sMediaProjection.stop();
                }
            }
        });
    }

    /****************************************** Factoring Virtual Display creation ****************/
    private void createVirtualDisplay() {
        // get width and height
        Point size = new Point();
        mDisplay.getSize(size);
        mWidth = size.x;
        mHeight = size.y;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBX_8888, 2);
        mVirtualDisplay = sMediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight, mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mHandler);
        mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
    }
}
