/*
 * Copyright (C) 2008 ZXing authors
 * Copyright 2011 Robert Theis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.sfsu.cs.orange.ocr;



import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.memetix.mst.language.Language;
import com.memetix.mst.translate.Translate;

import edu.sfsu.cs.orange.ocr.ShutterButton;
import edu.sfsu.cs.orange.ocr.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
// import WindowUtils for voice commands
import com.google.android.glass.view.WindowUtils;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the text correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing/
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback,
  ShutterButton.OnShutterButtonListener {

    private static final String TAG = CaptureActivity.class.getSimpleName();

    // Note: These constants will be overridden by any default values defined in preferences.xml.

    /** ISO 639-3 language code indicating the default recognition language. */
    public static final String DEFAULT_SOURCE_LANGUAGE_CODE = "eng";

    /** ISO 639-1 language code indicating the default target language for translation. */
    public static final String DEFAULT_TARGET_LANGUAGE_CODE = "zh-CHT";

    /** The default online machine translation service to use. */
    public static final String DEFAULT_TRANSLATOR = "Google Translate";

    /** The default OCR engine to use. */
    public static final String DEFAULT_OCR_ENGINE_MODE = "Tesseract";

    /** The default page segmentation mode to use. */
    public static final String DEFAULT_PAGE_SEGMENTATION_MODE = "Auto";

    /** Whether to use autofocus by default. */
    public static final boolean DEFAULT_TOGGLE_AUTO_FOCUS = true;

    /** Whether to initially disable continuous-picture and continuous-video focus modes. */
    public static final boolean DEFAULT_DISABLE_CONTINUOUS_FOCUS = true;

    /** Whether to beep by default when the shutter button is pressed. */
    public static final boolean DEFAULT_TOGGLE_BEEP = false;

    /** Whether to initially show a looping, real-time OCR display. */
    public static final boolean DEFAULT_TOGGLE_CONTINUOUS = false;

    /** Whether to initially reverse the image returned by the camera. */
    public static final boolean DEFAULT_TOGGLE_REVERSED_IMAGE = false;

    /** Whether to enable the use of online translation services be default. */
    public static final boolean DEFAULT_TOGGLE_TRANSLATION = true;

    /** Whether the light should be initially activated by default. */
    public static final boolean DEFAULT_TOGGLE_LIGHT = false;


    /** Flag to display the real-time recognition results at the top of the scanning screen. */
    private static final boolean CONTINUOUS_DISPLAY_RECOGNIZED_TEXT = true;

    /** Flag to display recognition-related statistics on the scanning screen. */
    private static final boolean CONTINUOUS_DISPLAY_METADATA = true;

    /** Flag to enable display of the on-screen shutter button. */
    private static final boolean DISPLAY_SHUTTER_BUTTON = true;

    /** Languages for which Cube data is available. */
    static final String[] CUBE_SUPPORTED_LANGUAGES = {
      "ara", // Arabic
      "eng", // English
      "hin" // Hindi
    };

    /** Languages that require Cube, and cannot run using Tesseract. */
    private static final String[] CUBE_REQUIRED_LANGUAGES = {
      "ara" // Arabic
    };

    /** Resource to use for data file downloads. */
    static final String DOWNLOAD_BASE = "http://tesseract-ocr.googlecode.com/files/";

    /** Download filename for orientation and script detection (OSD) data. */
    static final String OSD_FILENAME = "tesseract-ocr-3.01.osd.tar";

    /** Destination filename for orientation and script detection (OSD) data. */
    static final String OSD_FILENAME_BASE = "osd.traineddata";

    /** Minimum mean confidence score necessary to not reject single-shot OCR result. Currently unused. */
    static final int MINIMUM_MEAN_CONFIDENCE = 0; // 0 means don't reject any scored results

    // Context menu
    private static final int SETTINGS_ID = Menu.FIRST;
    private static final int ABOUT_ID = Menu.FIRST + 1;

    // Options menu, for copy to clipboard
    private static final int OPTIONS_COPY_RECOGNIZED_TEXT_ID = Menu.FIRST;
    private static final int OPTIONS_COPY_TRANSLATED_TEXT_ID = Menu.FIRST + 1;
    private static final int OPTIONS_SHARE_RECOGNIZED_TEXT_ID = Menu.FIRST + 2;
    private static final int OPTIONS_SHARE_TRANSLATED_TEXT_ID = Menu.FIRST + 3;

    private CameraManager cameraManager;
    private CaptureActivityHandler handler;
    private ViewfinderView viewfinderView;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private TextView statusViewBottom;
    private TextView statusViewTop;
    private TextView ocrResultView;
    private TextView translationView;
    private View cameraButtonView;
    private View resultView;
    private View progressView;
    private OcrResult lastResult;
    private Bitmap lastBitmap;
    private boolean hasSurface;
    private TessBaseAPI baseApi; // Java interface for the Tesseract OCR engine
    private String sourceLanguageCodeOcr; // ISO 639-3 language code
    private String sourceLanguageReadable; // Language name, for example, "English"
    private String sourceLanguageCodeTranslation; // ISO 639-1 language code
    private String targetLanguageCodeTranslation; // ISO 639-1 language code
    private String targetLanguageReadable; // Language name, for example, "English"
    private int pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD;
    private int ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
    private String characterBlacklist;
    private String characterWhitelist;
    private ShutterButton shutterButton;
    private boolean isTranslationActive; // Whether we want to show translations
    private boolean isContinuousModeActive; // Whether we are doing OCR in continuous mode
    private SharedPreferences prefs;
    private OnSharedPreferenceChangeListener listener;
    private ProgressDialog dialog; // for initOcr - language download & unzip
    private ProgressDialog indeterminateDialog; // also for initOcr - init OCR engine
    private boolean isEngineReady;
    private boolean isPaused;
    private static boolean isFirstLaunch; // True if this is the first time the app is being run

    Handler getHandler() {
      return handler;
    }

    TessBaseAPI getBaseApi() {
      return baseApi;
    }

    CameraManager getCameraManager() {
      return cameraManager;
    }

    @
    Override
    public void onCreate(Bundle icicle) {
      super.onCreate(icicle);

      checkFirstLaunch();

      if (isFirstLaunch) {
        setDefaultPreferences();
      }

      Window window = getWindow();
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
      // Requests a voice menu on this activity. As for any other
      // window feature, be sure to request this before
      // setContentView() is called
      getWindow().requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS);
      setContentView(R.layout.activity_main);
      viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
      cameraButtonView = findViewById(R.id.camera_button_view);
      resultView = findViewById(R.id.result_view);

      statusViewBottom = (TextView) findViewById(R.id.status_view_bottom);
      registerForContextMenu(statusViewBottom);
      statusViewTop = (TextView) findViewById(R.id.status_view_top);
      registerForContextMenu(statusViewTop);

      handler = null;
      lastResult = null;
      hasSurface = false;

      // Camera shutter button
      if (DISPLAY_SHUTTER_BUTTON) {
        shutterButton = (ShutterButton) findViewById(R.id.shutter_button);
        shutterButton.setOnShutterButtonListener(this);
      }

      ocrResultView = (TextView) findViewById(R.id.ocr_result_text_view);
      registerForContextMenu(ocrResultView);
      translationView = (TextView) findViewById(R.id.translation_text_view);
      registerForContextMenu(translationView);

      progressView = (View) findViewById(R.id.indeterminate_progress_indicator_view);

      cameraManager = new CameraManager(getApplication());
      viewfinderView.setCameraManager(cameraManager);

      // Set listener to change the size of the viewfinder rectangle.
      viewfinderView.setOnTouchListener(new View.OnTouchListener() {
        int lastX = -1;
        int lastY = -1;

        @
        Override
        public boolean onTouch(View v, MotionEvent event) {
          switch (event.getAction()) {
          case MotionEvent.ACTION_DOWN:
            lastX = -1;
            lastY = -1;
            return true;
          case MotionEvent.ACTION_MOVE:
            int currentX = (int) event.getX();
            int currentY = (int) event.getY();

            try {
              Rect rect = cameraManager.getFramingRect();

              final int BUFFER = 50;
              final int BIG_BUFFER = 60;
              if (lastX >= 0) {
                // Adjust the size of the viewfinder rectangle. Check if the touch event occurs in the corner areas first, because the regions overlap.
                if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER)) && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER))) {
                  // Top left corner: adjust both top and left sides
                  cameraManager.adjustFramingRect(2 * (lastX - currentX), 2 * (lastY - currentY));
                  viewfinderView.removeResultText();
                } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER)) && ((currentY <= rect.top + BIG_BUFFER && currentY >= rect.top - BIG_BUFFER) || (lastY <= rect.top + BIG_BUFFER && lastY >= rect.top - BIG_BUFFER))) {
                  // Top right corner: adjust both top and right sides
                  cameraManager.adjustFramingRect(2 * (currentX - lastX), 2 * (lastY - currentY));
                  viewfinderView.removeResultText();
                } else if (((currentX >= rect.left - BIG_BUFFER && currentX <= rect.left + BIG_BUFFER) || (lastX >= rect.left - BIG_BUFFER && lastX <= rect.left + BIG_BUFFER)) && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                  // Bottom left corner: adjust both bottom and left sides
                  cameraManager.adjustFramingRect(2 * (lastX - currentX), 2 * (currentY - lastY));
                  viewfinderView.removeResultText();
                } else if (((currentX >= rect.right - BIG_BUFFER && currentX <= rect.right + BIG_BUFFER) || (lastX >= rect.right - BIG_BUFFER && lastX <= rect.right + BIG_BUFFER)) && ((currentY <= rect.bottom + BIG_BUFFER && currentY >= rect.bottom - BIG_BUFFER) || (lastY <= rect.bottom + BIG_BUFFER && lastY >= rect.bottom - BIG_BUFFER))) {
                  // Bottom right corner: adjust both bottom and right sides
                  cameraManager.adjustFramingRect(2 * (currentX - lastX), 2 * (currentY - lastY));
                  viewfinderView.removeResultText();
                } else if (((currentX >= rect.left - BUFFER && currentX <= rect.left + BUFFER) || (lastX >= rect.left - BUFFER && lastX <= rect.left + BUFFER)) && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                  // Adjusting left side: event falls within BUFFER pixels of left side, and between top and bottom side limits
                  cameraManager.adjustFramingRect(2 * (lastX - currentX), 0);
                  viewfinderView.removeResultText();
                } else if (((currentX >= rect.right - BUFFER && currentX <= rect.right + BUFFER) || (lastX >= rect.right - BUFFER && lastX <= rect.right + BUFFER)) && ((currentY <= rect.bottom && currentY >= rect.top) || (lastY <= rect.bottom && lastY >= rect.top))) {
                  // Adjusting right side: event falls within BUFFER pixels of right side, and between top and bottom side limits
                  cameraManager.adjustFramingRect(2 * (currentX - lastX), 0);
                  viewfinderView.removeResultText();
                } else if (((currentY <= rect.top + BUFFER && currentY >= rect.top - BUFFER) || (lastY <= rect.top + BUFFER && lastY >= rect.top - BUFFER)) && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                  // Adjusting top side: event falls within BUFFER pixels of top side, and between left and right side limits
                  cameraManager.adjustFramingRect(0, 2 * (lastY - currentY));
                  viewfinderView.removeResultText();
                } else if (((currentY <= rect.bottom + BUFFER && currentY >= rect.bottom - BUFFER) || (lastY <= rect.bottom + BUFFER && lastY >= rect.bottom - BUFFER)) && ((currentX <= rect.right && currentX >= rect.left) || (lastX <= rect.right && lastX >= rect.left))) {
                  // Adjusting bottom side: event falls within BUFFER pixels of bottom side, and between left and right side limits
                  cameraManager.adjustFramingRect(0, 2 * (currentY - lastY));
                  viewfinderView.removeResultText();
                }
              }
            } catch (NullPointerException e) {
              Log.e(TAG, "Framing rect not available", e);
            }
            v.invalidate();
            lastX = currentX;
            lastY = currentY;
            return true;
          case MotionEvent.ACTION_UP:
            lastX = -1;
            lastY = -1;
            return true;
          }
          return false;
        }
      });

      isEngineReady = false;
    }

    @
    Override
    protected void onResume() {
      super.onResume();
      resetStatusView();

      String previousSourceLanguageCodeOcr = sourceLanguageCodeOcr;
      int previousOcrEngineMode = ocrEngineMode;

      retrievePreferences();

      // Set up the camera preview surface.
      surfaceView = (SurfaceView) findViewById(R.id.preview_view);
      surfaceHolder = surfaceView.getHolder();
      if (!hasSurface) {
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
      }

      // Comment out the following block to test non-OCR functions without an SD card

      // Do OCR engine initialization, if necessary
      boolean doNewInit = (baseApi == null) || !sourceLanguageCodeOcr.equals(previousSourceLanguageCodeOcr) ||
        ocrEngineMode != previousOcrEngineMode;
      if (doNewInit) {
        // Initialize the OCR engine
        File storageDirectory = getStorageDirectory();
        if (storageDirectory != null) {
          initOcrEngine(storageDirectory, sourceLanguageCodeOcr, sourceLanguageReadable);
        }
      } else {
        // We already have the engine initialized, so just start the camera.
        resumeOCR();
      }
    }

    /**
     * Method to start or restart recognition after the OCR engine has been initialized,
     * or after the app regains focus. Sets state related settings and OCR engine parameters,
     * and requests camera initialization.
     */
    void resumeOCR() {
      Log.d(TAG, "resumeOCR()");

      // This method is called when Tesseract has already been successfully initialized, so set
      // isEngineReady = true here.
      isEngineReady = true;

      isPaused = false;

      if (handler != null) {
        handler.resetState();
      }
      if (baseApi != null) {
        baseApi.setPageSegMode(pageSegmentationMode);
        baseApi.setVariable(TessBaseAPI.VAR_CHAR_BLACKLIST, characterBlacklist);
        baseApi.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, characterWhitelist);
      }

      if (hasSurface) {
        // The activity was paused but not stopped, so the surface still exists. Therefore
        // surfaceCreated() won't be called, so init the camera here.
        initCamera(surfaceHolder);
      }
    }

    /** Called when the shutter button is pressed in continuous mode. */
    void onShutterButtonPressContinuous() {
      isPaused = true;
      handler.stop();
      if (lastResult != null) {
        handleOcrDecode(lastResult);
      } else {
        Toast toast = Toast.makeText(this, "OCR failed. Please try again.", Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
        resumeContinuousDecoding();
      }
    }

    /** Called to resume recognition after translation in continuous mode. */
    @
    SuppressWarnings("unused")
    void resumeContinuousDecoding() {
      isPaused = false;
      resetStatusView();
      setStatusViewForContinuous();
      DecodeHandler.resetDecodeState();
      handler.resetState();
      if (shutterButton != null && DISPLAY_SHUTTER_BUTTON) {
        shutterButton.setVisibility(View.VISIBLE);
      }
    }

    @
    Override
    public void surfaceCreated(SurfaceHolder holder) {
      Log.d(TAG, "surfaceCreated()");

      if (holder == null) {
        Log.e(TAG, "surfaceCreated gave us a null surface");
      }

      // Only initialize the camera if the OCR engine is ready to go.
      if (!hasSurface && isEngineReady) {
        Log.d(TAG, "surfaceCreated(): calling initCamera()...");
        initCamera(holder);
      }
      hasSurface = true;
    }

    /** Initializes the camera and starts the handler to begin previewing. */
    private void initCamera(SurfaceHolder surfaceHolder) {
      Log.d(TAG, "initCamera()");
      if (surfaceHolder == null) {
        throw new IllegalStateException("No SurfaceHolder provided");
      }
      try {

        // Open and initialize the camera
        cameraManager.openDriver(surfaceHolder);

        // Creating the handler starts the preview, which can also throw a RuntimeException.
        handler = new CaptureActivityHandler(this, cameraManager, isContinuousModeActive);

      } catch (IOException ioe) {
        showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
      } catch (RuntimeException e) {
        // Barcode Scanner has seen crashes in the wild of this variety:
        // java.?lang.?RuntimeException: Fail to connect to camera service
        showErrorMessage("Error", "Could not initialize camera. Please try restarting device.");
      }
    }

    @
    Override
    protected void onPause() {
      if (handler != null) {
        handler.quitSynchronously();
      }

      // Stop using the camera, to avoid conflicting with other camera-based apps
      cameraManager.closeDriver();

      if (!hasSurface) {
        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        surfaceHolder.removeCallback(this);
      }
      super.onPause();
    }

    void stopHandler() {
      if (handler != null) {
        handler.stop();
      }
    }

    @
    Override
    protected void onDestroy() {
      if (baseApi != null) {
        baseApi.end();
      }
      super.onDestroy();
    }

    @
    Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
      if (keyCode == KeyEvent.KEYCODE_BACK) {

        // First check if we're paused in continuous mode, and if so, just unpause.
        if (isPaused) {
          Log.d(TAG, "only resuming continuous recognition, not quitting...");
          resumeContinuousDecoding();
          return true;
        }

        // Exit the app if we're not viewing an OCR result.
        if (lastResult == null) {
          setResult(RESULT_CANCELED);
          finish();
          return true;
        } else {
          // Go back to previewing in regular OCR mode.
          resetStatusView();
          if (handler != null) {
            handler.sendEmptyMessage(R.id.restart_preview);
          }
          return true;
        }
      } else if (keyCode == KeyEvent.KEYCODE_CAMERA) {
        if (isContinuousModeActive) {
          onShutterButtonPressContinuous();
        } else {
          handler.hardwareShutterButtonClick();
        }
        return true;
      } else if (keyCode == KeyEvent.KEYCODE_FOCUS) {
        // Only perform autofocus if user is not holding down the button.
        if (event.getRepeatCount() == 0) {
          cameraManager.requestAutoFocus(500L);
        }
        return true;
      }
      return super.onKeyDown(keyCode, event);
    }

    @
    Override
    public boolean onCreateOptionsMenu(Menu menu) {
      //    MenuInflater inflater = getMenuInflater();
      //    inflater.inflate(R.menu.options_menu, menu);
      super.onCreateOptionsMenu(menu);
      menu.add(0, SETTINGS_ID, 0, "Settings").setIcon(android.R.drawable.ic_menu_preferences);
      menu.add(0, ABOUT_ID, 0, "About").setIcon(android.R.drawable.ic_menu_info_details);
      return true;
    }

    @
    Override
    public boolean onOptionsItemSelected(MenuItem item) {
      Intent intent;
      switch (item.getItemId()) {
      case SETTINGS_ID:
        {
          intent = new Intent().setClass(this, PreferencesActivity.class);
          startActivity(intent);
          break;
        }
      }
      return super.onOptionsItemSelected(item);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
      hasSurface = false;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    /** Sets the necessary language code values for the given OCR language. */
    private boolean setSourceLanguage(String languageCode) {
      sourceLanguageCodeOcr = languageCode;
      sourceLanguageCodeTranslation = LanguageCodeHelper.mapLanguageCode(languageCode);
      sourceLanguageReadable = LanguageCodeHelper.getOcrLanguageName(this, languageCode);
      return true;
    }

    /** Sets the necessary language code values for the translation target language. */
    private boolean setTargetLanguage(String languageCode) {
      targetLanguageCodeTranslation = languageCode;
      targetLanguageReadable = LanguageCodeHelper.getTranslationLanguageName(this, languageCode);
      return true;
    }

    /** Finds the proper location on the SD card where we can save files. */
    private File getStorageDirectory() {
      //Log.d(TAG, "getStorageDirectory(): API level is " + Integer.valueOf(android.os.Build.VERSION.SDK_INT));

      String state = null;
      try {
        state = Environment.getExternalStorageState();
      } catch (RuntimeException e) {
        Log.e(TAG, "Is the SD card visible?", e);
        showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable.");
      }

      if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {

        // We can read and write the media
        //    	if (Integer.valueOf(android.os.Build.VERSION.SDK_INT) > 7) {
        // For Android 2.2 and above

        try {
          return getExternalFilesDir(Environment.MEDIA_MOUNTED);
        } catch (NullPointerException e) {
          // We get an error here if the SD card is visible, but full
          Log.e(TAG, "External storage is unavailable");
          showErrorMessage("Error", "Required external storage (such as an SD card) is full or unavailable.");
        }

        //        } else {
        //          // For Android 2.1 and below, explicitly give the path as, for example,
        //          // "/mnt/sdcard/Android/data/edu.sfsu.cs.orange.ocr/files/"
        //          return new File(Environment.getExternalStorageDirectory().toString() + File.separator +
        //                  "Android" + File.separator + "data" + File.separator + getPackageName() +
        //                  File.separator + "files" + File.separator);
        //        }

      } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
        // We can only read the media
        Log.e(TAG, "External storage is read-only");
        showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable for data storage.");
      } else {
        // Something else is wrong. It may be one of many other states, but all we need
        // to know is we can neither read nor write
        Log.e(TAG, "External storage is unavailable");
        showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable or corrupted.");
      }
      return null;
    }

    /**
     * Requests initialization of the OCR engine with the given parameters.
     *
     * @param storageRoot Path to location of the tessdata directory to use
     * @param languageCode Three-letter ISO 639-3 language code for OCR
     * @param languageName Name of the language for OCR, for example, "English"
     */
    private void initOcrEngine(File storageRoot, String languageCode, String languageName) {
      isEngineReady = false;

      // Set up the dialog box for the thermometer-style download progress indicator
      if (dialog != null) {
        dialog.dismiss();
      }
      dialog = new ProgressDialog(this);

      // If we have a language that only runs using Cube, then set the ocrEngineMode to Cube
      if (ocrEngineMode != TessBaseAPI.OEM_CUBE_ONLY) {
        for (String s: CUBE_REQUIRED_LANGUAGES) {
          if (s.equals(languageCode)) {
            ocrEngineMode = TessBaseAPI.OEM_CUBE_ONLY;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putString(PreferencesActivity.KEY_OCR_ENGINE_MODE, getOcrEngineModeName()).commit();
          }
        }
      }

      // If our language doesn't support Cube, then set the ocrEngineMode to Tesseract
      if (ocrEngineMode != TessBaseAPI.OEM_TESSERACT_ONLY) {
        boolean cubeOk = false;
        for (String s: CUBE_SUPPORTED_LANGUAGES) {
          if (s.equals(languageCode)) {
            cubeOk = true;
          }
        }
        if (!cubeOk) {
          ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
          SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
          prefs.edit().putString(PreferencesActivity.KEY_OCR_ENGINE_MODE, getOcrEngineModeName()).commit();
        }
      }

      // Display the name of the OCR engine we're initializing in the indeterminate progress dialog box
      indeterminateDialog = new ProgressDialog(this);
      indeterminateDialog.setTitle("Please wait");
      String ocrEngineModeName = getOcrEngineModeName();
      if (ocrEngineModeName.equals("Both")) {
        indeterminateDialog.setMessage("Initializing Cube and Tesseract OCR engines for " + languageName + "...");
      } else {
        indeterminateDialog.setMessage("Initializing " + ocrEngineModeName + " OCR engine for " + languageName + "...");
      }
      indeterminateDialog.setCancelable(false);
      indeterminateDialog.show();

      if (handler != null) {
        handler.quitSynchronously();
      }

      // Disable continuous mode if we're using Cube. This will prevent bad states for devices
      // with low memory that crash when running OCR with Cube, and prevent unwanted delays.
      if (ocrEngineMode == TessBaseAPI.OEM_CUBE_ONLY || ocrEngineMode == TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED) {
        Log.d(TAG, "Disabling continuous preview");
        isContinuousModeActive = false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, false);
      }

      // Start AsyncTask to install language data and init OCR
      baseApi = new TessBaseAPI();
      new OcrInitAsyncTask(this, baseApi, dialog, indeterminateDialog, languageCode, languageName, ocrEngineMode)
        .execute(storageRoot.toString());
    }

    /**
     * Displays information relating to the result of OCR, and requests a translation if necessary.
     *
     * @param ocrResult Object representing successful OCR results
     * @return True if a non-null result was received for OCR
     */
    boolean handleOcrDecode(OcrResult ocrResult) {
      lastResult = ocrResult;

      // Test whether the result is null
      if (ocrResult.getText() == null || ocrResult.getText().equals("")) {
        Toast toast = Toast.makeText(this, "OCR failed. Please try again.", Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
        return false;
      }

      // Turn off capture-related UI elements
      shutterButton.setVisibility(View.GONE);
      statusViewBottom.setVisibility(View.GONE);
      statusViewTop.setVisibility(View.GONE);
      cameraButtonView.setVisibility(View.GONE);
      viewfinderView.setVisibility(View.GONE);
      resultView.setVisibility(View.VISIBLE);

      ImageView bitmapImageView = (ImageView) findViewById(R.id.image_view);
      lastBitmap = ocrResult.getBitmap();
      if (lastBitmap == null) {
        bitmapImageView.setImageBitmap(BitmapFactory.decodeResource(getResources(),
          R.drawable.ic_launcher));
      } else {
        bitmapImageView.setImageBitmap(lastBitmap);
      }

      // Display the recognized text
      TextView sourceLanguageTextView = (TextView) findViewById(R.id.source_language_text_view);
      sourceLanguageTextView.setText(sourceLanguageReadable);
      TextView ocrResultTextView = (TextView) findViewById(R.id.ocr_result_text_view);
      ocrResultTextView.setText(ocrResult.getText());
      // Crudely scale betweeen 22 and 32 -- bigger font for shorter text
      int scaledSize = Math.max(22, 32 - ocrResult.getText().length() / 4);
      ocrResultTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);

      TextView translationLanguageLabelTextView = (TextView) findViewById(R.id.translation_language_label_text_view);
      TextView translationLanguageTextView = (TextView) findViewById(R.id.translation_language_text_view);
      TextView translationTextView = (TextView) findViewById(R.id.translation_text_view);
      if (isTranslationActive) {
        // Handle translation text fields
        translationLanguageLabelTextView.setVisibility(View.VISIBLE);
        translationLanguageTextView.setText(targetLanguageReadable);
        translationLanguageTextView.setTypeface(Typeface.defaultFromStyle(Typeface.NORMAL), Typeface.NORMAL);
        translationLanguageTextView.setVisibility(View.VISIBLE);

        // Activate/re-activate the indeterminate progress indicator
        translationTextView.setVisibility(View.GONE);
        progressView.setVisibility(View.VISIBLE);
        setProgressBarVisibility(true);

        // Get the translation asynchronously
        new TranslateAsyncTask(this, sourceLanguageCodeTranslation, targetLanguageCodeTranslation,
          ocrResult.getText()).execute();
      } else {
        translationLanguageLabelTextView.setVisibility(View.GONE);
        translationLanguageTextView.setVisibility(View.GONE);
        translationTextView.setVisibility(View.GONE);
        progressView.setVisibility(View.GONE);
        setProgressBarVisibility(false);
      }
      return true;
    }

    /**
     * Displays information relating to the results of a successful real-time OCR request.
     *
     * @param ocrResult Object representing successful OCR results
     */
    void handleOcrContinuousDecode(OcrResult ocrResult) {

      lastResult = ocrResult;

      // Send an OcrResultText object to the ViewfinderView for text rendering
      viewfinderView.addResultText(new OcrResultText(ocrResult.getText(),
        ocrResult.getWordConfidences(),
        ocrResult.getMeanConfidence(),
        ocrResult.getBitmapDimensions(),
        ocrResult.getRegionBoundingBoxes(),
        ocrResult.getTextlineBoundingBoxes(),
        ocrResult.getStripBoundingBoxes(),
        ocrResult.getWordBoundingBoxes(),
        ocrResult.getCharacterBoundingBoxes()));

      Integer meanConfidence = ocrResult.getMeanConfidence();

      if (CONTINUOUS_DISPLAY_RECOGNIZED_TEXT) {
        // Display the recognized text on the screen
        statusViewTop.setText(ocrResult.getText());
        int scaledSize = Math.max(22, 32 - ocrResult.getText().length() / 4);
        statusViewTop.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);
        statusViewTop.setTextColor(Color.BLACK);
        statusViewTop.setBackgroundResource(R.color.status_top_text_background);

        statusViewTop.getBackground().setAlpha(meanConfidence * (255 / 100));
      }

      if (CONTINUOUS_DISPLAY_METADATA) {
        // Display recognition-related metadata at the bottom of the screen
        long recognitionTimeRequired = ocrResult.getRecognitionTimeRequired();
        statusViewBottom.setTextSize(14);
        statusViewBottom.setText("OCR: " + sourceLanguageReadable + " - Mean confidence: " +
          meanConfidence.toString() + " - Time required: " + recognitionTimeRequired + " ms");
      }
    }

    /**
     * Version of handleOcrContinuousDecode for failed OCR requests. Displays a failure message.
     *
     * @param obj Metadata for the failed OCR request.
     */
    void handleOcrContinuousDecode(OcrResultFailure obj) {
      lastResult = null;
      viewfinderView.removeResultText();

      // Reset the text in the recognized text box.
      statusViewTop.setText("");

      if (CONTINUOUS_DISPLAY_METADATA) {
        // Color text delimited by '-' as red.
        statusViewBottom.setTextSize(14);
        CharSequence cs = setSpanBetweenTokens("OCR: " + sourceLanguageReadable + " - OCR failed - Time required: " + obj.getTimeRequired() + " ms", "-", new ForegroundColorSpan(0xFFFF0000));
        statusViewBottom.setText(cs);
      }
    }

    /**
     * Given either a Spannable String or a regular String and a token, apply
     * the given CharacterStyle to the span between the tokens.
     *
     * NOTE: This method was adapted from:
     *  http://www.androidengineer.com/2010/08/easy-method-for-formatting-android.html
     *
     * <p>
     * For example, {@code setSpanBetweenTokens("Hello ##world##!", "##", new
     * ForegroundColorSpan(0xFFFF0000));} will return a CharSequence {@code
     * "Hello world!"} with {@code world} in red.
     *
     */
    private CharSequence setSpanBetweenTokens(CharSequence text, String token,
      CharacterStyle...cs) {
      // Start and end refer to the points where the span will apply
      int tokenLen = token.length();
      int start = text.toString().indexOf(token) + tokenLen;
      int end = text.toString().indexOf(token, start);

      if (start > -1 && end > -1) {
        // Copy the spannable string to a mutable spannable string
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        for (CharacterStyle c: cs)
          ssb.setSpan(c, start, end, 0);
        text = ssb;
      }
      return text;
    }

    @
    Override
    public void onCreateContextMenu(ContextMenu menu, View v,
      ContextMenuInfo menuInfo) {
      super.onCreateContextMenu(menu, v, menuInfo);
      if (v.equals(ocrResultView)) {
        menu.add(Menu.NONE, OPTIONS_COPY_RECOGNIZED_TEXT_ID, Menu.NONE, "Copy recognized text");
        menu.add(Menu.NONE, OPTIONS_SHARE_RECOGNIZED_TEXT_ID, Menu.NONE, "Share recognized text");
      } else if (v.equals(translationView)) {
        menu.add(Menu.NONE, OPTIONS_COPY_TRANSLATED_TEXT_ID, Menu.NONE, "Copy translated text");
        menu.add(Menu.NONE, OPTIONS_SHARE_TRANSLATED_TEXT_ID, Menu.NONE, "Share translated text");
      }
    }

    @
    Override
    public boolean onContextItemSelected(MenuItem item) {
      ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      switch (item.getItemId()) {

      case OPTIONS_COPY_RECOGNIZED_TEXT_ID:
        clipboardManager.setText(ocrResultView.getText());
        if (clipboardManager.hasText()) {
          Toast toast = Toast.makeText(this, "Text copied.", Toast.LENGTH_LONG);
          toast.setGravity(Gravity.BOTTOM, 0, 0);
          toast.show();
        }
        return true;
      case OPTIONS_SHARE_RECOGNIZED_TEXT_ID:
        Intent shareRecognizedTextIntent = new Intent(android.content.Intent.ACTION_SEND);
        shareRecognizedTextIntent.setType("text/plain");
        shareRecognizedTextIntent.putExtra(android.content.Intent.EXTRA_TEXT, ocrResultView.getText());
        startActivity(Intent.createChooser(shareRecognizedTextIntent, "Share via"));
        return true;
      case OPTIONS_COPY_TRANSLATED_TEXT_ID:
        clipboardManager.setText(translationView.getText());
        if (clipboardManager.hasText()) {
          Toast toast = Toast.makeText(this, "Text copied.", Toast.LENGTH_LONG);
          toast.setGravity(Gravity.BOTTOM, 0, 0);
          toast.show();
        }
        return true;
      case OPTIONS_SHARE_TRANSLATED_TEXT_ID:
        Intent shareTranslatedTextIntent = new Intent(android.content.Intent.ACTION_SEND);
        shareTranslatedTextIntent.setType("text/plain");
        shareTranslatedTextIntent.putExtra(android.content.Intent.EXTRA_TEXT, translationView.getText());
        startActivity(Intent.createChooser(shareTranslatedTextIntent, "Share via"));
        return true;
      default:
        return super.onContextItemSelected(item);
      }
    }

    /**
     * Resets view elements.
     */
    private void resetStatusView() {
      resultView.setVisibility(View.GONE);
      if (CONTINUOUS_DISPLAY_METADATA) {
        statusViewBottom.setText("");
        statusViewBottom.setTextSize(14);
        statusViewBottom.setTextColor(getResources().getColor(R.color.status_text));
        statusViewBottom.setVisibility(View.VISIBLE);
      }
      if (CONTINUOUS_DISPLAY_RECOGNIZED_TEXT) {
        statusViewTop.setText("");
        statusViewTop.setTextSize(14);
        statusViewTop.setVisibility(View.VISIBLE);
      }
      viewfinderView.setVisibility(View.VISIBLE);
      cameraButtonView.setVisibility(View.VISIBLE);
      if (DISPLAY_SHUTTER_BUTTON) {
        shutterButton.setVisibility(View.VISIBLE);
      }
      lastResult = null;
      viewfinderView.removeResultText();
    }

    /** Displays a pop-up message showing the name of the current OCR source language. */
    void showLanguageName() {
      Toast toast = Toast.makeText(this, "OCR: " + sourceLanguageReadable, Toast.LENGTH_LONG);
      toast.setGravity(Gravity.TOP, 0, 0);
      toast.show();
    }

    /**
     * Displays an initial message to the user while waiting for the first OCR request to be
     * completed after starting realtime OCR.
     */
    void setStatusViewForContinuous() {
      viewfinderView.removeResultText();
      if (CONTINUOUS_DISPLAY_METADATA) {
        statusViewBottom.setText("OCR: " + sourceLanguageReadable + " - waiting for OCR...");
      }
    }

    @
    SuppressWarnings("unused")
    void setButtonVisibility(boolean visible) {
      if (shutterButton != null && visible == true && DISPLAY_SHUTTER_BUTTON) {
        shutterButton.setVisibility(View.VISIBLE);
      } else if (shutterButton != null) {
        shutterButton.setVisibility(View.GONE);
      }
    }

    /**
     * Enables/disables the shutter button to prevent double-clicks on the button.
     *
     * @param clickable True if the button should accept a click
     */
    void setShutterButtonClickable(boolean clickable) {
      shutterButton.setClickable(clickable);
    }

    /** Request the viewfinder to be invalidated. */
    void drawViewfinder() {
      viewfinderView.drawViewfinder();
    }

    @
    Override
    public void onShutterButtonClick(ShutterButton b) {
      if (isContinuousModeActive) {
        onShutterButtonPressContinuous();
      } else {
        if (handler != null) {
          handler.shutterButtonClick();
        }
      }
    }

    @
    Override
    public void onShutterButtonFocus(ShutterButton b, boolean pressed) {
      requestDelayedAutoFocus();
    }

    /**
     * Requests autofocus after a 350 ms delay. This delay prevents requesting focus when the user
     * just wants to click the shutter button without focusing. Quick button press/release will
     * trigger onShutterButtonClick() before the focus kicks in.
     */
    private void requestDelayedAutoFocus() {
      // Wait 350 ms before focusing to avoid interfering with quick button presses when
      // the user just wants to take a picture without focusing.
      cameraManager.requestAutoFocus(350L);
    }

    static boolean getFirstLaunch() {
      return isFirstLaunch;
    }

    /**
     * We want the help screen to be shown automatically the first time a new version of the app is
     * run. The easiest way to do this is to check android:versionCode from the manifest, and compare
     * it to a value stored as a preference.
     */
    private boolean checkFirstLaunch() {
      try {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        int currentVersion = info.versionCode;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int lastVersion = prefs.getInt(PreferencesActivity.KEY_HELP_VERSION_SHOWN, 0);
        if (lastVersion == 0) {
          isFirstLaunch = true;
        } else {
          isFirstLaunch = false;
        }
      } catch (PackageManager.NameNotFoundException e) {
        Log.w(TAG, e);
      }
      return false;
    }

    /**
     * Returns a string that represents which OCR engine(s) are currently set to be run.
     *
     * @return OCR engine mode
     */
    String getOcrEngineModeName() {
      String ocrEngineModeName = "";
      String[] ocrEngineModes = getResources().getStringArray(R.array.ocrenginemodes);
      if (ocrEngineMode == TessBaseAPI.OEM_TESSERACT_ONLY) {
        ocrEngineModeName = ocrEngineModes[0];
      } else if (ocrEngineMode == TessBaseAPI.OEM_CUBE_ONLY) {
        ocrEngineModeName = ocrEngineModes[1];
      } else if (ocrEngineMode == TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED) {
        ocrEngineModeName = ocrEngineModes[2];
      }
      return ocrEngineModeName;
    }

    /**
     * Gets values from shared preferences and sets the corresponding data members in this activity.
     */
    private void retrievePreferences() {
      prefs = PreferenceManager.getDefaultSharedPreferences(this);

      // Retrieve from preferences, and set in this Activity, the language preferences
      PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
      setSourceLanguage(prefs.getString(PreferencesActivity.KEY_SOURCE_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE));
      setTargetLanguage(prefs.getString(PreferencesActivity.KEY_TARGET_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_TARGET_LANGUAGE_CODE));
      isTranslationActive = prefs.getBoolean(PreferencesActivity.KEY_TOGGLE_TRANSLATION, false);

      // Retrieve from preferences, and set in this Activity, the capture mode preference
      if (prefs.getBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, CaptureActivity.DEFAULT_TOGGLE_CONTINUOUS)) {
        isContinuousModeActive = true;
      } else {
        isContinuousModeActive = false;
      }

      // Retrieve from preferences, and set in this Activity, the page segmentation mode preference
      String[] pageSegmentationModes = getResources().getStringArray(R.array.pagesegmentationmodes);
      String pageSegmentationModeName = prefs.getString(PreferencesActivity.KEY_PAGE_SEGMENTATION_MODE, pageSegmentationModes[0]);
      if (pageSegmentationModeName.equals(pageSegmentationModes[0])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[1])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_AUTO;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[2])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[3])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_CHAR;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[4])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[5])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[6])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_WORD;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[7])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK_VERT_TEXT;
      } else if (pageSegmentationModeName.equals(pageSegmentationModes[8])) {
        pageSegmentationMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT;
      }

      // Retrieve from preferences, and set in this Activity, the OCR engine mode
      String[] ocrEngineModes = getResources().getStringArray(R.array.ocrenginemodes);
      String ocrEngineModeName = prefs.getString(PreferencesActivity.KEY_OCR_ENGINE_MODE, ocrEngineModes[0]);
      if (ocrEngineModeName.equals(ocrEngineModes[0])) {
        ocrEngineMode = TessBaseAPI.OEM_TESSERACT_ONLY;
      } else if (ocrEngineModeName.equals(ocrEngineModes[1])) {
        ocrEngineMode = TessBaseAPI.OEM_CUBE_ONLY;
      } else if (ocrEngineModeName.equals(ocrEngineModes[2])) {
        ocrEngineMode = TessBaseAPI.OEM_TESSERACT_CUBE_COMBINED;
      }

      // Retrieve from preferences, and set in this Activity, the character blacklist and whitelist
      characterBlacklist = OcrCharacterHelper.getBlacklist(prefs, sourceLanguageCodeOcr);
      characterWhitelist = OcrCharacterHelper.getWhitelist(prefs, sourceLanguageCodeOcr);

      prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * Sets default values for preferences. To be called the first time this app is run.
     */
    private void setDefaultPreferences() {
      prefs = PreferenceManager.getDefaultSharedPreferences(this);

      // Continuous preview
      prefs.edit().putBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, CaptureActivity.DEFAULT_TOGGLE_CONTINUOUS).commit();

      // Recognition language
      prefs.edit().putString(PreferencesActivity.KEY_SOURCE_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE).commit();

      // Translation
      prefs.edit().putBoolean(PreferencesActivity.KEY_TOGGLE_TRANSLATION, CaptureActivity.DEFAULT_TOGGLE_TRANSLATION).commit();

      // Translation target language
      prefs.edit().putString(PreferencesActivity.KEY_TARGET_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_TARGET_LANGUAGE_CODE).commit();

      // Translator
      prefs.edit().putString(PreferencesActivity.KEY_TRANSLATOR, CaptureActivity.DEFAULT_TRANSLATOR).commit();

      // OCR Engine
      prefs.edit().putString(PreferencesActivity.KEY_OCR_ENGINE_MODE, CaptureActivity.DEFAULT_OCR_ENGINE_MODE).commit();

      // Autofocus
      prefs.edit().putBoolean(PreferencesActivity.KEY_AUTO_FOCUS, CaptureActivity.DEFAULT_TOGGLE_AUTO_FOCUS).commit();

      // Disable problematic focus modes
      prefs.edit().putBoolean(PreferencesActivity.KEY_DISABLE_CONTINUOUS_FOCUS, CaptureActivity.DEFAULT_DISABLE_CONTINUOUS_FOCUS).commit();

      // Beep
      prefs.edit().putBoolean(PreferencesActivity.KEY_PLAY_BEEP, CaptureActivity.DEFAULT_TOGGLE_BEEP).commit();

      // Character blacklist
      prefs.edit().putString(PreferencesActivity.KEY_CHARACTER_BLACKLIST,
        OcrCharacterHelper.getDefaultBlacklist(CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE)).commit();

      // Character whitelist
      prefs.edit().putString(PreferencesActivity.KEY_CHARACTER_WHITELIST,
        OcrCharacterHelper.getDefaultWhitelist(CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE)).commit();

      // Page segmentation mode
      prefs.edit().putString(PreferencesActivity.KEY_PAGE_SEGMENTATION_MODE, CaptureActivity.DEFAULT_PAGE_SEGMENTATION_MODE).commit();

      // Reversed camera image
      prefs.edit().putBoolean(PreferencesActivity.KEY_REVERSE_IMAGE, CaptureActivity.DEFAULT_TOGGLE_REVERSED_IMAGE).commit();

      // Light
      prefs.edit().putBoolean(PreferencesActivity.KEY_TOGGLE_LIGHT, CaptureActivity.DEFAULT_TOGGLE_LIGHT).commit();
    }

    void displayProgressDialog() {
      // Set up the indeterminate progress dialog box
      indeterminateDialog = new ProgressDialog(this);
      indeterminateDialog.setTitle("Please wait");
      String ocrEngineModeName = getOcrEngineModeName();
      if (ocrEngineModeName.equals("Both")) {
        indeterminateDialog.setMessage("Performing OCR using Cube and Tesseract...");
      } else {
        indeterminateDialog.setMessage("Performing OCR using " + ocrEngineModeName + "...");
      }
      indeterminateDialog.setCancelable(false);
      indeterminateDialog.show();
    }

    ProgressDialog getProgressDialog() {
      return indeterminateDialog;
    }

    /**
     * Displays an error message dialog box to the user on the UI thread.
     *
     * @param title The title for the dialog box
     * @param message The error message to be displayed
     */
    void showErrorMessage(String title, String message) {
      new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setOnCancelListener(new FinishListener(this))
        .setPositiveButton("Done", new FinishListener(this))
        .show();
    }
  }



/**
 * This view is overlaid on top of the camera preview. It adds the viewfinder rectangle and partial
 * transparency outside it, as well as the result text.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing
 */
class ViewfinderView extends View {
  //private static final long ANIMATION_DELAY = 80L;

  /** Flag to draw boxes representing the results from TessBaseAPI::GetRegions(). */
  static final boolean DRAW_REGION_BOXES = false;

  /** Flag to draw boxes representing the results from TessBaseAPI::GetTextlines(). */
  static final boolean DRAW_TEXTLINE_BOXES = true;

  /** Flag to draw boxes representing the results from TessBaseAPI::GetStrips(). */
  static final boolean DRAW_STRIP_BOXES = false;

  /** Flag to draw boxes representing the results from TessBaseAPI::GetWords(). */
  static final boolean DRAW_WORD_BOXES = true;

  /** Flag to draw word text with a background varying from transparent to opaque. */
  static final boolean DRAW_TRANSPARENT_WORD_BACKGROUNDS = false;

  /** Flag to draw boxes representing the results from TessBaseAPI::GetCharacters(). */
  static final boolean DRAW_CHARACTER_BOXES = false;

  /** Flag to draw the text of words within their respective boxes from TessBaseAPI::GetWords(). */
  static final boolean DRAW_WORD_TEXT = false;

  /** Flag to draw each character in its respective box from TessBaseAPI::GetCharacters(). */
  static final boolean DRAW_CHARACTER_TEXT = false;

  private CameraManager cameraManager;
  private final Paint paint;
  private final int maskColor;
  private final int frameColor;
  private final int cornerColor;
  private OcrResultText resultText;
  private String[] words;
  private List < Rect > regionBoundingBoxes;
  private List < Rect > textlineBoundingBoxes;
  private List < Rect > stripBoundingBoxes;
  private List < Rect > wordBoundingBoxes;
  private List < Rect > characterBoundingBoxes;
  //  Rect bounds;
  private Rect previewFrame;
  private Rect rect;

  // This constructor is used when the class is built from an XML resource.
  public ViewfinderView(Context context, AttributeSet attrs) {
    super(context, attrs);

    // Initialize these once for performance rather than calling them every time in onDraw().
    paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Resources resources = getResources();
    maskColor = resources.getColor(R.color.viewfinder_mask);
    frameColor = resources.getColor(R.color.viewfinder_frame);
    cornerColor = resources.getColor(R.color.viewfinder_corners);

    //    bounds = new Rect();
    previewFrame = new Rect();
    rect = new Rect();
  }

  public void setCameraManager(CameraManager cameraManager) {
    this.cameraManager = cameraManager;
  }

  @
  SuppressWarnings("unused")@ Override
  public void onDraw(Canvas canvas) {
    Rect frame = cameraManager.getFramingRect();
    if (frame == null) {
      return;
    }
    int width = canvas.getWidth();
    int height = canvas.getHeight();

    // Draw the exterior (i.e. outside the framing rect) darkened
    paint.setColor(maskColor);
    canvas.drawRect(0, 0, width, frame.top, paint);
    canvas.drawRect(0, frame.top, frame.left, frame.bottom + 1, paint);
    canvas.drawRect(frame.right + 1, frame.top, width, frame.bottom + 1, paint);
    canvas.drawRect(0, frame.bottom + 1, width, height, paint);

    // If we have an OCR result, overlay its information on the viewfinder.
    if (resultText != null) {

      // Only draw text/bounding boxes on viewfinder if it hasn't been resized since the OCR was requested.
      Point bitmapSize = resultText.getBitmapDimensions();
      previewFrame = cameraManager.getFramingRectInPreview();
      if (bitmapSize.x == previewFrame.width() && bitmapSize.y == previewFrame.height()) {


        float scaleX = frame.width() / (float) previewFrame.width();
        float scaleY = frame.height() / (float) previewFrame.height();

        if (DRAW_REGION_BOXES) {
          regionBoundingBoxes = resultText.getRegionBoundingBoxes();
          for (int i = 0; i < regionBoundingBoxes.size(); i++) {
            paint.setAlpha(0xA0);
            paint.setColor(Color.MAGENTA);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            rect = regionBoundingBoxes.get(i);
            canvas.drawRect(frame.left + rect.left * scaleX,
              frame.top + rect.top * scaleY,
              frame.left + rect.right * scaleX,
              frame.top + rect.bottom * scaleY, paint);
          }
        }

        if (DRAW_TEXTLINE_BOXES) {
          // Draw each textline
          textlineBoundingBoxes = resultText.getTextlineBoundingBoxes();
          paint.setAlpha(0xA0);
          paint.setColor(Color.RED);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1);
          for (int i = 0; i < textlineBoundingBoxes.size(); i++) {
            rect = textlineBoundingBoxes.get(i);
            canvas.drawRect(frame.left + rect.left * scaleX,
              frame.top + rect.top * scaleY,
              frame.left + rect.right * scaleX,
              frame.top + rect.bottom * scaleY, paint);
          }
        }

        if (DRAW_STRIP_BOXES) {
          stripBoundingBoxes = resultText.getStripBoundingBoxes();
          paint.setAlpha(0xFF);
          paint.setColor(Color.YELLOW);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1);
          for (int i = 0; i < stripBoundingBoxes.size(); i++) {
            rect = stripBoundingBoxes.get(i);
            canvas.drawRect(frame.left + rect.left * scaleX,
              frame.top + rect.top * scaleY,
              frame.left + rect.right * scaleX,
              frame.top + rect.bottom * scaleY, paint);
          }
        }

        if (DRAW_WORD_BOXES || DRAW_WORD_TEXT) {
          // Split the text into words
          wordBoundingBoxes = resultText.getWordBoundingBoxes();
          //      for (String w : words) {
          //        Log.e("ViewfinderView", "word: " + w);
          //      }
          //Log.d("ViewfinderView", "There are " + words.length + " words in the string array.");
          //Log.d("ViewfinderView", "There are " + wordBoundingBoxes.size() + " words with bounding boxes.");
        }

        if (DRAW_WORD_BOXES) {
          paint.setAlpha(0xFF);
          paint.setColor(0xFF00CCFF);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(1);
          for (int i = 0; i < wordBoundingBoxes.size(); i++) {
            // Draw a bounding box around the word
            rect = wordBoundingBoxes.get(i);
            canvas.drawRect(
              frame.left + rect.left * scaleX,
              frame.top + rect.top * scaleY,
              frame.left + rect.right * scaleX,
              frame.top + rect.bottom * scaleY, paint);
          }
        }

        if (DRAW_WORD_TEXT) {
          words = resultText.getText().replace("\n", " ").split(" ");
          int[] wordConfidences = resultText.getWordConfidences();
          for (int i = 0; i < wordBoundingBoxes.size(); i++) {
            boolean isWordBlank = true;
            try {
              if (!words[i].equals("")) {
                isWordBlank = false;
              }
            } catch (ArrayIndexOutOfBoundsException e) {
              e.printStackTrace();
            }

            // Only draw if word has characters
            if (!isWordBlank) {
              // Draw a white background around each word
              rect = wordBoundingBoxes.get(i);
              paint.setColor(Color.WHITE);
              paint.setStyle(Paint.Style.FILL);
              if (DRAW_TRANSPARENT_WORD_BACKGROUNDS) {
                // Higher confidence = more opaque, less transparent background
                paint.setAlpha(wordConfidences[i] * 255 / 100);
              } else {
                paint.setAlpha(255);
              }
              canvas.drawRect(frame.left + rect.left * scaleX,
                frame.top + rect.top * scaleY,
                frame.left + rect.right * scaleX,
                frame.top + rect.bottom * scaleY, paint);

              // Draw the word in black text
              paint.setColor(Color.BLACK);
              paint.setAlpha(0xFF);
              paint.setAntiAlias(true);
              paint.setTextAlign(Paint.Align.LEFT);

              // Adjust text size to fill rect
              paint.setTextSize(100);
              paint.setTextScaleX(1.0f);
              // ask the paint for the bounding rect if it were to draw this text
              Rect bounds = new Rect();
              paint.getTextBounds(words[i], 0, words[i].length(), bounds);
              // get the height that would have been produced
              int h = bounds.bottom - bounds.top;
              // figure out what textSize setting would create that height of text
              float size = (((float)(rect.height()) / h) * 100f);
              // and set it into the paint
              paint.setTextSize(size);
              // Now set the scale.
              // do calculation with scale of 1.0 (no scale)
              paint.setTextScaleX(1.0f);
              // ask the paint for the bounding rect if it were to draw this text.
              paint.getTextBounds(words[i], 0, words[i].length(), bounds);
              // determine the width
              int w = bounds.right - bounds.left;
              // calculate the baseline to use so that the entire text is visible including the descenders
              int text_h = bounds.bottom - bounds.top;
              int baseline = bounds.bottom + ((rect.height() - text_h) / 2);
              // determine how much to scale the width to fit the view
              float xscale = ((float)(rect.width())) / w;
              // set the scale for the text paint
              paint.setTextScaleX(xscale);
              canvas.drawText(words[i], frame.left + rect.left * scaleX, frame.top + rect.bottom * scaleY - baseline, paint);
            }

          }
        }

        //        if (DRAW_CHARACTER_BOXES || DRAW_CHARACTER_TEXT) {
        //          characterBoundingBoxes = resultText.getCharacterBoundingBoxes();
        //        }
        //
        //        if (DRAW_CHARACTER_BOXES) {
        //          // Draw bounding boxes around each character
        //          paint.setAlpha(0xA0);
        //          paint.setColor(0xFF00FF00);
        //          paint.setStyle(Style.STROKE);
        //          paint.setStrokeWidth(1);
        //          for (int c = 0; c < characterBoundingBoxes.size(); c++) {
        //            Rect characterRect = characterBoundingBoxes.get(c);
        //            canvas.drawRect(frame.left + characterRect.left * scaleX,
        //                frame.top + characterRect.top * scaleY,
        //                frame.left + characterRect.right * scaleX,
        //                frame.top + characterRect.bottom * scaleY, paint);
        //          }
        //        }
        //
        //        if (DRAW_CHARACTER_TEXT) {
        //          // Draw letters individually
        //          for (int i = 0; i < characterBoundingBoxes.size(); i++) {
        //            Rect r = characterBoundingBoxes.get(i);
        //
        //            // Draw a white background for every letter
        //            int meanConfidence = resultText.getMeanConfidence();
        //            paint.setColor(Color.WHITE);
        //            paint.setAlpha(meanConfidence * (255 / 100));
        //            paint.setStyle(Style.FILL);
        //            canvas.drawRect(frame.left + r.left * scaleX,
        //                frame.top + r.top * scaleY,
        //                frame.left + r.right * scaleX,
        //                frame.top + r.bottom * scaleY, paint);
        //
        //            // Draw each letter, in black
        //            paint.setColor(Color.BLACK);
        //            paint.setAlpha(0xFF);
        //            paint.setAntiAlias(true);
        //            paint.setTextAlign(Align.LEFT);
        //            String letter = "";
        //            try {
        //              char c = resultText.getText().replace("\n","").replace(" ", "").charAt(i);
        //              letter = Character.toString(c);
        //
        //              if (!letter.equals("-") && !letter.equals("_")) {
        //
        //                // Adjust text size to fill rect
        //                paint.setTextSize(100);
        //                paint.setTextScaleX(1.0f);
        //
        //                // ask the paint for the bounding rect if it were to draw this text
        //                Rect bounds = new Rect();
        //                paint.getTextBounds(letter, 0, letter.length(), bounds);
        //
        //                // get the height that would have been produced
        //                int h = bounds.bottom - bounds.top;
        //
        //                // figure out what textSize setting would create that height of text
        //                float size  = (((float)(r.height())/h)*100f);
        //
        //                // and set it into the paint
        //                paint.setTextSize(size);
        //
        //                // Draw the text as is. We don't really need to set the text scale, because the dimensions
        //                // of the Rect should already be suited for drawing our letter.
        //                canvas.drawText(letter, frame.left + r.left * scaleX, frame.top + r.bottom * scaleY, paint);
        //              }
        //            } catch (StringIndexOutOfBoundsException e) {
        //              e.printStackTrace();
        //            } catch (Exception e) {
        //              e.printStackTrace();
        //            }
        //          }
        //        }
      }

    }
    // Draw a two pixel solid border inside the framing rect
    paint.setAlpha(0);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(frameColor);
    canvas.drawRect(frame.left, frame.top, frame.right + 1, frame.top + 2, paint);
    canvas.drawRect(frame.left, frame.top + 2, frame.left + 2, frame.bottom - 1, paint);
    canvas.drawRect(frame.right - 1, frame.top, frame.right + 1, frame.bottom - 1, paint);
    canvas.drawRect(frame.left, frame.bottom - 1, frame.right + 1, frame.bottom + 1, paint);

    // Draw the framing rect corner UI elements
    paint.setColor(cornerColor);
    canvas.drawRect(frame.left - 15, frame.top - 15, frame.left + 15, frame.top, paint);
    canvas.drawRect(frame.left - 15, frame.top, frame.left, frame.top + 15, paint);
    canvas.drawRect(frame.right - 15, frame.top - 15, frame.right + 15, frame.top, paint);
    canvas.drawRect(frame.right, frame.top - 15, frame.right + 15, frame.top + 15, paint);
    canvas.drawRect(frame.left - 15, frame.bottom, frame.left + 15, frame.bottom + 15, paint);
    canvas.drawRect(frame.left - 15, frame.bottom - 15, frame.left, frame.bottom, paint);
    canvas.drawRect(frame.right - 15, frame.bottom, frame.right + 15, frame.bottom + 15, paint);
    canvas.drawRect(frame.right, frame.bottom - 15, frame.right + 15, frame.bottom + 15, paint);


    // Request another update at the animation interval, but don't repaint the entire viewfinder mask.
    //postInvalidateDelayed(ANIMATION_DELAY, frame.left, frame.top, frame.right, frame.bottom);
  }

  public void drawViewfinder() {
    invalidate();
  }

  /**
   * Adds the given OCR results for drawing to the view.
   *
   * @param text Object containing OCR-derived text and corresponding data.
   */
  public void addResultText(OcrResultText text) {
    resultText = text;
  }

  /**
   * Nullifies OCR text to remove it at the next onDraw() drawing.
   */
  public void removeResultText() {
    resultText = null;
  }
}


/**
 * This class handles all the messaging which comprises the state machine for capture.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing/
 */
class CaptureActivityHandler extends Handler {

  private static final String TAG = CaptureActivityHandler.class.getSimpleName();

  private final CaptureActivity activity;
  private final DecodeThread decodeThread;
  private static State state;
  private final CameraManager cameraManager;

  private enum State {
    PREVIEW,
    PREVIEW_PAUSED,
    CONTINUOUS,
    CONTINUOUS_PAUSED,
    SUCCESS,
    DONE
  }

  CaptureActivityHandler(CaptureActivity activity, CameraManager cameraManager, boolean isContinuousModeActive) {
    this.activity = activity;
    this.cameraManager = cameraManager;

    // Start ourselves capturing previews (and decoding if using continuous recognition mode).
    cameraManager.startPreview();

    decodeThread = new DecodeThread(activity);
    decodeThread.start();

    if (isContinuousModeActive) {
      state = State.CONTINUOUS;

      // Show the shutter and torch buttons
      activity.setButtonVisibility(true);

      // Display a "be patient" message while first recognition request is running
      activity.setStatusViewForContinuous();

      restartOcrPreviewAndDecode();
    } else {
      state = State.SUCCESS;

      // Show the shutter and torch buttons
      activity.setButtonVisibility(true);

      restartOcrPreview();
    }
  }

  @
  Override
  public void handleMessage(Message message) {

    if (message.what == R.id.restart_preview) {
      restartOcrPreview();
    } else if (message.what == R.id.ocr_continuous_decode_failed) {
      DecodeHandler.resetDecodeState();
      try {
        activity.handleOcrContinuousDecode((OcrResultFailure) message.obj);
      } catch (NullPointerException e) {
        Log.w(TAG, "got bad OcrResultFailure", e);
      }
      if (state == State.CONTINUOUS) {
        restartOcrPreviewAndDecode();
      }
    } else if (message.what == R.id.ocr_continuous_decode_succeeded) {
      DecodeHandler.resetDecodeState();
      try {
        activity.handleOcrContinuousDecode((OcrResult) message.obj);
      } catch (NullPointerException e) {
        // Continue
      }
      if (state == State.CONTINUOUS) {
        restartOcrPreviewAndDecode();
      }
    } else if (message.what == R.id.ocr_decode_succeeded) {
      state = State.SUCCESS;
      activity.setShutterButtonClickable(true);
      activity.handleOcrDecode((OcrResult) message.obj);
    } else if (message.what == R.id.ocr_decode_failed) {
      state = State.PREVIEW;
      activity.setShutterButtonClickable(true);
      Toast toast = Toast.makeText(activity.getBaseContext(), "OCR failed. Please try again.", Toast.LENGTH_SHORT);
      toast.setGravity(Gravity.TOP, 0, 0);
      toast.show();
    }
  }

  void stop() {
    // TODO See if this should be done by sending a quit message to decodeHandler as is done
    // below in quitSynchronously().

    Log.d(TAG, "Setting state to CONTINUOUS_PAUSED.");
    state = State.CONTINUOUS_PAUSED;
    removeMessages(R.id.ocr_continuous_decode);
    removeMessages(R.id.ocr_decode);
    removeMessages(R.id.ocr_continuous_decode_failed);
    removeMessages(R.id.ocr_continuous_decode_succeeded); // TODO are these removeMessages() calls doing anything?

    // Freeze the view displayed to the user.
    //    CameraManager.get().stopPreview();
  }

  void resetState() {
    //Log.d(TAG, "in restart()");
    if (state == State.CONTINUOUS_PAUSED) {
      Log.d(TAG, "Setting state to CONTINUOUS");
      state = State.CONTINUOUS;
      restartOcrPreviewAndDecode();
    }
  }

  void quitSynchronously() {
    state = State.DONE;
    if (cameraManager != null) {
      cameraManager.stopPreview();
    }
    //Message quit = Message.obtain(decodeThread.getHandler(), R.id.quit);
    try {
      //quit.sendToTarget(); // This always gives "sending message to a Handler on a dead thread"

      // Wait at most half a second; should be enough time, and onPause() will timeout quickly
      decodeThread.join(500L);
    } catch (InterruptedException e) {
      Log.w(TAG, "Caught InterruptedException in quitSyncronously()", e);
      // continue
    } catch (RuntimeException e) {
      Log.w(TAG, "Caught RuntimeException in quitSyncronously()", e);
      // continue
    } catch (Exception e) {
      Log.w(TAG, "Caught unknown Exception in quitSynchronously()", e);
    }

    // Be absolutely sure we don't send any queued up messages
    removeMessages(R.id.ocr_continuous_decode);
    removeMessages(R.id.ocr_decode);

  }

  /**
   *  Start the preview, but don't try to OCR anything until the user presses the shutter button.
   */
  private void restartOcrPreview() {
    // Display the shutter and torch buttons
    activity.setButtonVisibility(true);

    if (state == State.SUCCESS) {
      state = State.PREVIEW;

      // Draw the viewfinder.
      activity.drawViewfinder();
    }
  }

  /**
   *  Send a decode request for realtime OCR mode
   */
  private void restartOcrPreviewAndDecode() {
    // Continue capturing camera frames
    cameraManager.startPreview();

    // Continue requesting decode of images
    cameraManager.requestOcrDecode(decodeThread.getHandler(), R.id.ocr_continuous_decode);
    activity.drawViewfinder();
  }

  /**
   * Request OCR on the current preview frame.
   */
  private void ocrDecode() {
    state = State.PREVIEW_PAUSED;
    cameraManager.requestOcrDecode(decodeThread.getHandler(), R.id.ocr_decode);
  }

  /**
   * Request OCR when the hardware shutter button is clicked.
   */
  void hardwareShutterButtonClick() {
    // Ensure that we're not in continuous recognition mode
    if (state == State.PREVIEW) {
      ocrDecode();
    }
  }

  /**
   * Request OCR when the on-screen shutter button is clicked.
   */
  void shutterButtonClick() {
    // Disable further clicks on this button until OCR request is finished
    activity.setShutterButtonClickable(false);
    ocrDecode();
  }

}



class AutoFocusManager implements Camera.AutoFocusCallback {

  private static final String TAG = AutoFocusManager.class.getSimpleName();

  private static final long AUTO_FOCUS_INTERVAL_MS = 3500L;
  private static final Collection < String > FOCUS_MODES_CALLING_AF;
  static {
    FOCUS_MODES_CALLING_AF = new ArrayList < String > (2);
    FOCUS_MODES_CALLING_AF.add(Camera.Parameters.FOCUS_MODE_AUTO);
    FOCUS_MODES_CALLING_AF.add(Camera.Parameters.FOCUS_MODE_MACRO);
  }

  private boolean active;
  private boolean manual;
  private final boolean useAutoFocus;
  private final Camera camera;
  private final Timer timer;
  private TimerTask outstandingTask;

  AutoFocusManager(Context context, Camera camera) {
    this.camera = camera;
    timer = new Timer(true);
    SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    String currentFocusMode = camera.getParameters().getFocusMode();
    useAutoFocus =
      sharedPrefs.getBoolean(PreferencesActivity.KEY_AUTO_FOCUS, true) &&
      FOCUS_MODES_CALLING_AF.contains(currentFocusMode);
    Log.i(TAG, "Current focus mode '" + currentFocusMode + "'; use auto focus? " + useAutoFocus);
    manual = false;
    checkAndStart();
  }

  @
  Override
  public synchronized void onAutoFocus(boolean success, Camera theCamera) {
    if (active && !manual) {
      outstandingTask = new TimerTask() {@
        Override
        public void run() {
          checkAndStart();
        }
      };
      timer.schedule(outstandingTask, AUTO_FOCUS_INTERVAL_MS);
    }
    manual = false;
  }

  void checkAndStart() {
    if (useAutoFocus) {
      active = true;
      start();
    }
  }

  synchronized void start() {
    try {
      camera.autoFocus(this);
    } catch (RuntimeException re) {
      // Have heard RuntimeException reported in Android 4.0.x+; continue?
      Log.w(TAG, "Unexpected exception while focusing", re);
    }
  }

  /**
   * Performs a manual auto-focus after the given delay.
   * @param delay Time to wait before auto-focusing, in milliseconds
   */
  synchronized void start(long delay) {
    outstandingTask = new TimerTask() {@
      Override
      public void run() {
        manual = true;
        start();
      }
    };
    timer.schedule(outstandingTask, delay);
  }

  synchronized void stop() {
    if (useAutoFocus) {
      camera.cancelAutoFocus();
    }
    if (outstandingTask != null) {
      outstandingTask.cancel();
      outstandingTask = null;
    }
    active = false;
    manual = false;
  }

}


/**
 * A class which deals with reading, parsing, and setting the camera parameters which are used to
 * configure the camera hardware.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing
 */
class CameraConfigurationManager {

  private static final String TAG = "CameraConfiguration";
  // This is bigger than the size of a small screen, which is still supported. The routine
  // below will still select the default (presumably 320x240) size for these. This prevents
  // accidental selection of very low resolution on some devices.
  private static final int MIN_PREVIEW_PIXELS = 470 * 320; // normal screen
  private static final int MAX_PREVIEW_PIXELS = 800 * 600; // more than large/HD screen

  private final Context context;
  private Point screenResolution;
  private Point cameraResolution;

  CameraConfigurationManager(Context context) {
    this.context = context;
  }

  /**
   * Reads, one time, values from the camera that are needed by the app.
   */
  void initFromCameraParameters(Camera camera) {
    Camera.Parameters parameters = camera.getParameters();
    WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    Display display = manager.getDefaultDisplay();
    int width = display.getWidth();
    int height = display.getHeight();
    screenResolution = new Point(width, height);
    Log.i(TAG, "Screen resolution: " + screenResolution);
    cameraResolution = findBestPreviewSizeValue(parameters, screenResolution);
    Log.i(TAG, "Camera resolution: " + cameraResolution);
  }

  void setDesiredCameraParameters(Camera camera) {
    Camera.Parameters parameters = camera.getParameters();

    if (parameters == null) {
      Log.w(TAG, "Device error: no camera parameters are available. Proceeding without configuration.");
      return;
    }

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

    String focusMode = null;
    if (prefs.getBoolean(PreferencesActivity.KEY_AUTO_FOCUS, true)) {
      if (prefs.getBoolean(PreferencesActivity.KEY_DISABLE_CONTINUOUS_FOCUS, false)) {
        focusMode = findSettableValue(parameters.getSupportedFocusModes(),
          Camera.Parameters.FOCUS_MODE_AUTO);
      } else {
        focusMode = findSettableValue(parameters.getSupportedFocusModes(),
          "continuous-video", // Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in 4.0+
          "continuous-picture", // Camera.Paramters.FOCUS_MODE_CONTINUOUS_PICTURE in 4.0+
          Camera.Parameters.FOCUS_MODE_AUTO);
      }
    }
    // Maybe selected auto-focus but not available, so fall through here:
    if (focusMode == null) {
      focusMode = findSettableValue(parameters.getSupportedFocusModes(),
        Camera.Parameters.FOCUS_MODE_MACRO,
        "edof"); // Camera.Parameters.FOCUS_MODE_EDOF in 2.2+
    }
    if (focusMode != null) {
      parameters.setFocusMode(focusMode);
    }

    parameters.setPreviewSize(cameraResolution.x, cameraResolution.y);
    camera.setParameters(parameters);
  }

  Point getCameraResolution() {
    return cameraResolution;
  }

  Point getScreenResolution() {
    return screenResolution;
  }

  private Point findBestPreviewSizeValue(Camera.Parameters parameters, Point screenResolution) {

    // Sort by size, descending
    List < Camera.Size > supportedPreviewSizes = new ArrayList < Camera.Size > (parameters.getSupportedPreviewSizes());
    Collections.sort(supportedPreviewSizes, new Comparator < Camera.Size > () {@
      Override
      public int compare(Camera.Size a, Camera.Size b) {
        int aPixels = a.height * a.width;
        int bPixels = b.height * b.width;
        if (bPixels < aPixels) {
          return -1;
        }
        if (bPixels > aPixels) {
          return 1;
        }
        return 0;
      }
    });

    if (Log.isLoggable(TAG, Log.INFO)) {
      StringBuilder previewSizesString = new StringBuilder();
      for (Camera.Size supportedPreviewSize: supportedPreviewSizes) {
        previewSizesString.append(supportedPreviewSize.width).append('x')
          .append(supportedPreviewSize.height).append(' ');
      }
      Log.i(TAG, "Supported preview sizes: " + previewSizesString);
    }

    Point bestSize = null;
    float screenAspectRatio = (float) screenResolution.x / (float) screenResolution.y;

    float diff = Float.POSITIVE_INFINITY;
    for (Camera.Size supportedPreviewSize: supportedPreviewSizes) {
      int realWidth = supportedPreviewSize.width;
      int realHeight = supportedPreviewSize.height;
      int pixels = realWidth * realHeight;
      if (pixels < MIN_PREVIEW_PIXELS || pixels > MAX_PREVIEW_PIXELS) {
        continue;
      }
      Point exactPoint = new Point(realWidth, realHeight);
      Log.i(TAG, "Found preview size exactly matching screen size: " + exactPoint);
      return exactPoint;
    }

    if (bestSize == null) {
      Camera.Size defaultSize = parameters.getPreviewSize();
      bestSize = new Point(defaultSize.width, defaultSize.height);
      Log.i(TAG, "No suitable preview sizes, using default: " + bestSize);
    }

    Log.i(TAG, "Found best approximate preview size: " + bestSize);
    return bestSize;
  }

  private static String findSettableValue(Collection < String > supportedValues,
    String...desiredValues) {
    Log.i(TAG, "Supported values: " + supportedValues);
    String result = null;
    if (supportedValues != null) {
      for (String desiredValue: desiredValues) {
        if (supportedValues.contains(desiredValue)) {
          result = desiredValue;
          break;
        }
      }
    }
    Log.i(TAG, "Settable value: " + result);
    return result;
  }

}


/**
 * Called when the next preview frame is received.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing
 */
final class PreviewCallback implements Camera.PreviewCallback {

  private static final String TAG = PreviewCallback.class.getSimpleName();

  private final CameraConfigurationManager configManager;
  private Handler previewHandler;
  private int previewMessage;

  PreviewCallback(CameraConfigurationManager configManager) {
    this.configManager = configManager;
  }

  void setHandler(Handler previewHandler, int previewMessage) {
    this.previewHandler = previewHandler;
    this.previewMessage = previewMessage;
  }

  // Since we're not calling setPreviewFormat(int), the data arrives here in the YCbCr_420_SP
  // (NV21) format.
  @
  Override
  public void onPreviewFrame(byte[] data, Camera camera) {
    Point cameraResolution = configManager.getCameraResolution();
    Handler thePreviewHandler = previewHandler;
    if (cameraResolution != null && thePreviewHandler != null) {
      Message message = thePreviewHandler.obtainMessage(previewMessage, cameraResolution.x,
        cameraResolution.y, data);
      message.sendToTarget();
      previewHandler = null;
    } else {
      Log.d(TAG, "Got preview callback, but no handler or resolution available");
    }
  }

}



/**
 * This object wraps the Camera service object and expects to be the only one talking to it. The
 * implementation encapsulates the steps needed to take preview-sized images, which are used for
 * both preview and decoding.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing
 */
class CameraManager {

  private static final String TAG = CameraManager.class.getSimpleName();

  private static final int MIN_FRAME_WIDTH = 50; // originally 240
  private static final int MIN_FRAME_HEIGHT = 20; // originally 240
  private static final int MAX_FRAME_WIDTH = 800; // originally 480
  private static final int MAX_FRAME_HEIGHT = 600; // originally 360

  private final Context context;
  private final CameraConfigurationManager configManager;
  private Camera camera;
  private AutoFocusManager autoFocusManager;
  private Rect framingRect;
  private Rect framingRectInPreview;
  private boolean initialized;
  private boolean previewing;
  private boolean reverseImage;
  private int requestedFramingRectWidth;
  private int requestedFramingRectHeight;
  /**
   * Preview frames are delivered here, which we pass on to the registered handler. Make sure to
   * clear the handler so it will only receive one message.
   */
  private final PreviewCallback previewCallback;

  public CameraManager(Context context) {
    this.context = context;
    this.configManager = new CameraConfigurationManager(context);
    previewCallback = new PreviewCallback(configManager);
  }

  /**
   * Opens the camera driver and initializes the hardware parameters.
   *
   * @param holder The surface object which the camera will draw preview frames into.
   * @throws IOException Indicates the camera driver failed to open.
   */
  public synchronized void openDriver(SurfaceHolder holder) throws IOException {
    Camera theCamera = camera;
    if (theCamera == null) {
      theCamera = Camera.open();
      if (theCamera == null) {
        throw new IOException();
      }
      camera = theCamera;
      //!! camera.setDisplayOrientation(90);
    }
    camera.setPreviewDisplay(holder);
    if (!initialized) {
      initialized = true;
      configManager.initFromCameraParameters(theCamera);
      if (requestedFramingRectWidth > 0 && requestedFramingRectHeight > 0) {
        adjustFramingRect(requestedFramingRectWidth, requestedFramingRectHeight);
        requestedFramingRectWidth = 0;
        requestedFramingRectHeight = 0;
      }
    }
    configManager.setDesiredCameraParameters(theCamera);

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    reverseImage = prefs.getBoolean(PreferencesActivity.KEY_REVERSE_IMAGE, false);
  }

  /**
   * Closes the camera driver if still in use.
   */
  public synchronized void closeDriver() {
    if (camera != null) {
      camera.release();
      camera = null;

      // Make sure to clear these each time we close the camera, so that any scanning rect
      // requested by intent is forgotten.
      framingRect = null;
      framingRectInPreview = null;
    }
  }

  /**
   * Asks the camera hardware to begin drawing preview frames to the screen.
   */
  public synchronized void startPreview() {
    Camera theCamera = camera;
    if (theCamera != null && !previewing) {
      theCamera.startPreview();
      previewing = true;
      autoFocusManager = new AutoFocusManager(context, camera);
    }
  }

  /**
   * Tells the camera to stop drawing preview frames.
   */
  public synchronized void stopPreview() {
    if (autoFocusManager != null) {
      autoFocusManager.stop();
      autoFocusManager = null;
    }
    if (camera != null && previewing) {
      camera.stopPreview();
      previewCallback.setHandler(null, 0);
      previewing = false;
    }
  }

  /**
   * A single preview frame will be returned to the handler supplied. The data will arrive as byte[]
   * in the message.obj field, with width and height encoded as message.arg1 and message.arg2,
   * respectively.
   *
   * @param handler The handler to send the message to.
   * @param message The what field of the message to be sent.
   */
  public synchronized void requestOcrDecode(Handler handler, int message) {
    Camera theCamera = camera;
    if (theCamera != null && previewing) {
      previewCallback.setHandler(handler, message);
      theCamera.setOneShotPreviewCallback(previewCallback);
    }
  }

  /**
   * Asks the camera hardware to perform an autofocus.
   * @param delay Time delay to send with the request
   */
  public synchronized void requestAutoFocus(long delay) {
    autoFocusManager.start(delay);
  }

  /**
   * Calculates the framing rect which the UI should draw to show the user where to place the
   * barcode. This target helps with alignment as well as forces the user to hold the device
   * far enough away to ensure the image will be in focus.
   *
   * @return The rectangle to draw on screen in window coordinates.
   */
  public synchronized Rect getFramingRect() {
    if (framingRect == null) {
      if (camera == null) {
        return null;
      }
      Point screenResolution = configManager.getScreenResolution();
      if (screenResolution == null) {
        // Called early, before init even finished
        return null;
      }
      int width = screenResolution.x * 3 / 5;
      if (width < MIN_FRAME_WIDTH) {
        width = MIN_FRAME_WIDTH;
      } else if (width > MAX_FRAME_WIDTH) {
        width = MAX_FRAME_WIDTH;
      }
      int height = screenResolution.y * 1 / 5;
      if (height < MIN_FRAME_HEIGHT) {
        height = MIN_FRAME_HEIGHT;
      } else if (height > MAX_FRAME_HEIGHT) {
        height = MAX_FRAME_HEIGHT;
      }
      int leftOffset = (screenResolution.x - width) / 2;
      int topOffset = (screenResolution.y - height) / 2;
      framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
    }
    return framingRect;
  }

  /**
   * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
   * not UI / screen.
   */
  public synchronized Rect getFramingRectInPreview() {
    if (framingRectInPreview == null) {
      Rect rect = new Rect(getFramingRect());
      Point cameraResolution = configManager.getCameraResolution();
      Point screenResolution = configManager.getScreenResolution();
      if (cameraResolution == null || screenResolution == null) {
        // Called early, before init even finished
        return null;
      }
      rect.left = rect.left * cameraResolution.x / screenResolution.x;
      rect.right = rect.right * cameraResolution.x / screenResolution.x;
      rect.top = rect.top * cameraResolution.y / screenResolution.y;
      rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
      framingRectInPreview = rect;
    }
    return framingRectInPreview;
  }

  /**
   * Changes the size of the framing rect.
   *
   * @param deltaWidth Number of pixels to adjust the width
   * @param deltaHeight Number of pixels to adjust the height
   */
  public synchronized void adjustFramingRect(int deltaWidth, int deltaHeight) {
    if (initialized) {
      Point screenResolution = configManager.getScreenResolution();

      // Set maximum and minimum sizes
      if ((framingRect.width() + deltaWidth > screenResolution.x - 4) || (framingRect.width() + deltaWidth < 50)) {
        deltaWidth = 0;
      }
      if ((framingRect.height() + deltaHeight > screenResolution.y - 4) || (framingRect.height() + deltaHeight < 50)) {
        deltaHeight = 0;
      }

      int newWidth = framingRect.width() + deltaWidth;
      int newHeight = framingRect.height() + deltaHeight;
      int leftOffset = (screenResolution.x - newWidth) / 2;
      int topOffset = (screenResolution.y - newHeight) / 2;
      framingRect = new Rect(leftOffset, topOffset, leftOffset + newWidth, topOffset + newHeight);
      framingRectInPreview = null;
    } else {
      requestedFramingRectWidth = deltaWidth;
      requestedFramingRectHeight = deltaHeight;
    }
  }

  /**
   * A factory method to build the appropriate LuminanceSource object based on the format
   * of the preview buffers, as described by Camera.Parameters.
   *
   * @param data A preview frame.
   * @param width The width of the image.
   * @param height The height of the image.
   * @return A PlanarYUVLuminanceSource instance.
   */
  public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
    Rect rect = getFramingRectInPreview();
    if (rect == null) {
      return null;
    }
    // Go ahead and assume it's YUV rather than die.
    return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
      rect.width(), rect.height(), reverseImage);
  }

}




/**
 * Class for handling functions relating to converting between standard language
 * codes, and converting language codes to language names.
 */
class LanguageCodeHelper {
  public static final String TAG = "LanguageCodeHelper";

  /**
   * Private constructor to enforce noninstantiability
   */
  private LanguageCodeHelper() {
    throw new AssertionError();
  }

  /**
   * Map an ISO 639-3 language code to an ISO 639-1 language code.
   *
   * There is one entry here for each language recognized by the OCR engine.
   *
   * @param languageCode
   *            ISO 639-3 language code
   * @return ISO 639-1 language code
   */
  public static String mapLanguageCode(String languageCode) {
    if (languageCode.equals("afr")) { // Afrikaans
      return "af";
    } else if (languageCode.equals("sqi")) { // Albanian
      return "sq";
    } else if (languageCode.equals("ara")) { // Arabic
      return "ar";
    } else if (languageCode.equals("aze")) { // Azeri
      return "az";
    } else if (languageCode.equals("eus")) { // Basque
      return "eu";
    } else if (languageCode.equals("bel")) { // Belarusian
      return "be";
    } else if (languageCode.equals("ben")) { // Bengali
      return "bn";
    } else if (languageCode.equals("bul")) { // Bulgarian
      return "bg";
    } else if (languageCode.equals("cat")) { // Catalan
      return "ca";
    } else if (languageCode.equals("chi_sim")) { // Chinese (Simplified)
      return "zh-CN";
    } else if (languageCode.equals("chi_tra")) { // Chinese (Traditional)
      return "zh-TW";
    } else if (languageCode.equals("hrv")) { // Croatian
      return "hr";
    } else if (languageCode.equals("ces")) { // Czech
      return "cs";
    } else if (languageCode.equals("dan")) { // Danish
      return "da";
    } else if (languageCode.equals("nld")) { // Dutch
      return "nl";
    } else if (languageCode.equals("eng")) { // English
      return "en";
    } else if (languageCode.equals("est")) { // Estonian
      return "et";
    } else if (languageCode.equals("fin")) { // Finnish
      return "fi";
    } else if (languageCode.equals("fra")) { // French
      return "fr";
    } else if (languageCode.equals("glg")) { // Galician
      return "gl";
    } else if (languageCode.equals("deu")) { // German
      return "de";
    } else if (languageCode.equals("ell")) { // Greek
      return "el";
    } else if (languageCode.equals("heb")) { // Hebrew
      return "he";
    } else if (languageCode.equals("hin")) { // Hindi
      return "hi";
    } else if (languageCode.equals("hun")) { // Hungarian
      return "hu";
    } else if (languageCode.equals("isl")) { // Icelandic
      return "is";
    } else if (languageCode.equals("ind")) { // Indonesian
      return "id";
    } else if (languageCode.equals("ita")) { // Italian
      return "it";
    } else if (languageCode.equals("jpn")) { // Japanese
      return "ja";
    } else if (languageCode.equals("kan")) { // Kannada
      return "kn";
    } else if (languageCode.equals("kor")) { // Korean
      return "ko";
    } else if (languageCode.equals("lav")) { // Latvian
      return "lv";
    } else if (languageCode.equals("lit")) { // Lithuanian
      return "lt";
    } else if (languageCode.equals("mkd")) { // Macedonian
      return "mk";
    } else if (languageCode.equals("msa")) { // Malay
      return "ms";
    } else if (languageCode.equals("mal")) { // Malayalam
      return "ml";
    } else if (languageCode.equals("mlt")) { // Maltese
      return "mt";
    } else if (languageCode.equals("nor")) { // Norwegian
      return "no";
    } else if (languageCode.equals("pol")) { // Polish
      return "pl";
    } else if (languageCode.equals("por")) { // Portuguese
      return "pt";
    } else if (languageCode.equals("ron")) { // Romanian
      return "ro";
    } else if (languageCode.equals("rus")) { // Russian
      return "ru";
    } else if (languageCode.equals("srp")) { // Serbian (Latin) // TODO is google expecting Cyrillic?
      return "sr";
    } else if (languageCode.equals("slk")) { // Slovak
      return "sk";
    } else if (languageCode.equals("slv")) { // Slovenian
      return "sl";
    } else if (languageCode.equals("spa")) { // Spanish
      return "es";
    } else if (languageCode.equals("swa")) { // Swahili
      return "sw";
    } else if (languageCode.equals("swe")) { // Swedish
      return "sv";
    } else if (languageCode.equals("tgl")) { // Tagalog
      return "tl";
    } else if (languageCode.equals("tam")) { // Tamil
      return "ta";
    } else if (languageCode.equals("tel")) { // Telugu
      return "te";
    } else if (languageCode.equals("tha")) { // Thai
      return "th";
    } else if (languageCode.equals("tur")) { // Turkish
      return "tr";
    } else if (languageCode.equals("ukr")) { // Ukrainian
      return "uk";
    } else if (languageCode.equals("vie")) { // Vietnamese
      return "vi";
    } else {
      return "";
    }
  }

  /**
   * Map the given ISO 639-3 language code to a name of a language, for example,
   * "Spanish"
   *
   * @param context
   *            interface to calling application environment. Needed to access
   *            values from strings.xml.
   * @param languageCode
   *            ISO 639-3 language code
   * @return language name
   */
  public static String getOcrLanguageName(Context context, String languageCode) {
    Resources res = context.getResources();
    String[] language6393 = res.getStringArray(R.array.iso6393);
    String[] languageNames = res.getStringArray(R.array.languagenames);
    int len;

    // Finds the given language code in the iso6393 array, and takes the name with the same index
    // from the languagenames array.
    for (len = 0; len < language6393.length; len++) {
      if (language6393[len].equals(languageCode)) {
        Log.d(TAG, "getOcrLanguageName: " + languageCode + "->" + languageNames[len]);
        return languageNames[len];
      }
    }

    Log.d(TAG, "languageCode: Could not find language name for ISO 693-3: " + languageCode);
    return languageCode;
  }

  /**
   * Map the given ISO 639-1 language code to a name of a language, for example,
   * "Spanish"
   *
   * @param languageCode
   *             ISO 639-1 language code
   * @return name of the language. For example, "English"
   */
  public static String getTranslationLanguageName(Context context, String languageCode) {
    Resources res = context.getResources();
    String[] language6391 = res.getStringArray(R.array.translationtargetiso6391_google);
    String[] languageNames = res.getStringArray(R.array.translationtargetlanguagenames_google);
    int len;

    // Finds the given language code in the translationtargetiso6391 array, and takes the name
    // with the same index from the translationtargetlanguagenames array.
    for (len = 0; len < language6391.length; len++) {
      if (language6391[len].equals(languageCode)) {
        Log.d(TAG, "getTranslationLanguageName: " + languageCode + "->" + languageNames[len]);
        return languageNames[len];
      }
    }

    // Now look in the Microsoft Translate API list. Currently this will only be needed for
    // Haitian Creole.
    language6391 = res.getStringArray(R.array.translationtargetiso6391_microsoft);
    languageNames = res.getStringArray(R.array.translationtargetlanguagenames_microsoft);
    for (len = 0; len < language6391.length; len++) {
      if (language6391[len].equals(languageCode)) {
        Log.d(TAG, "languageCode: " + languageCode + "->" + languageNames[len]);
        return languageNames[len];
      }
    }

    Log.d(TAG, "getTranslationLanguageName: Could not find language name for ISO 693-1: " +
      languageCode);
    return "";
  }

}



/**
 * Class to handle preferences that are saved across sessions of the app. Shows
 * a hierarchy of preferences to the user, organized into sections. These
 * preferences are displayed in the options menu that is shown when the user
 * presses the MENU button.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing
 */
class PreferencesActivity extends PreferenceActivity implements
OnSharedPreferenceChangeListener {

  // Preference keys not carried over from ZXing project
  public static final String KEY_SOURCE_LANGUAGE_PREFERENCE = "sourceLanguageCodeOcrPref";
  public static final String KEY_TARGET_LANGUAGE_PREFERENCE = "targetLanguageCodeTranslationPref";
  public static final String KEY_TOGGLE_TRANSLATION = "preference_translation_toggle_translation";
  public static final String KEY_CONTINUOUS_PREVIEW = "preference_capture_continuous";
  public static final String KEY_PAGE_SEGMENTATION_MODE = "preference_page_segmentation_mode";
  public static final String KEY_OCR_ENGINE_MODE = "preference_ocr_engine_mode";
  public static final String KEY_CHARACTER_BLACKLIST = "preference_character_blacklist";
  public static final String KEY_CHARACTER_WHITELIST = "preference_character_whitelist";
  public static final String KEY_TOGGLE_LIGHT = "preference_toggle_light";
  public static final String KEY_TRANSLATOR = "preference_translator";

  // Preference keys carried over from ZXing project
  public static final String KEY_AUTO_FOCUS = "preferences_auto_focus";
  public static final String KEY_DISABLE_CONTINUOUS_FOCUS = "preferences_disable_continuous_focus";
  public static final String KEY_HELP_VERSION_SHOWN = "preferences_help_version_shown";
  public static final String KEY_NOT_OUR_RESULTS_SHOWN = "preferences_not_our_results_shown";
  public static final String KEY_REVERSE_IMAGE = "preferences_reverse_image";
  public static final String KEY_PLAY_BEEP = "preferences_play_beep";
  public static final String KEY_VIBRATE = "preferences_vibrate";

  public static final String TRANSLATOR_BING = "Bing Translator";

  private ListPreference listPreferenceSourceLanguage;
  private ListPreference listPreferenceTargetLanguage;
  private ListPreference listPreferenceTranslator;
  private ListPreference listPreferenceOcrEngineMode;
  private EditTextPreference editTextPreferenceCharacterBlacklist;
  private EditTextPreference editTextPreferenceCharacterWhitelist;
  private ListPreference listPreferencePageSegmentationMode;

  private static SharedPreferences sharedPreferences;

  /**
   * Set the default preference values.
   *
   * @param Bundle
   *            savedInstanceState the current Activity's state, as passed by
   *            Android
   */
  @
  Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    addPreferencesFromResource(R.xml.preferences);

    sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

    listPreferenceSourceLanguage = (ListPreference) getPreferenceScreen().findPreference(KEY_SOURCE_LANGUAGE_PREFERENCE);
    listPreferenceTargetLanguage = (ListPreference) getPreferenceScreen().findPreference(KEY_TARGET_LANGUAGE_PREFERENCE);
    listPreferenceTranslator = (ListPreference) getPreferenceScreen().findPreference(KEY_TRANSLATOR);
    listPreferenceOcrEngineMode = (ListPreference) getPreferenceScreen().findPreference(KEY_OCR_ENGINE_MODE);
    editTextPreferenceCharacterBlacklist = (EditTextPreference) getPreferenceScreen().findPreference(KEY_CHARACTER_BLACKLIST);
    editTextPreferenceCharacterWhitelist = (EditTextPreference) getPreferenceScreen().findPreference(KEY_CHARACTER_WHITELIST);
    listPreferencePageSegmentationMode = (ListPreference) getPreferenceScreen().findPreference(KEY_PAGE_SEGMENTATION_MODE);

  }

  /**
   * Interface definition for a callback to be invoked when a shared
   * preference is changed. Sets summary text for the app's preferences. Summary text values show the
   * current settings for the values.
   *
   * @param sharedPreferences
   *            the Android.content.SharedPreferences that received the change
   * @param key
   *            the key of the preference that was changed, added, or removed
   */
  @
  Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
    String key) {
    // Update preference summary values to show current preferences
    if (key.equals(KEY_TRANSLATOR)) {
      listPreferenceTranslator.setSummary(sharedPreferences.getString(key, CaptureActivity.DEFAULT_TRANSLATOR));
    } else if (key.equals(KEY_SOURCE_LANGUAGE_PREFERENCE)) {

      // Set the summary text for the source language name
      listPreferenceSourceLanguage.setSummary(LanguageCodeHelper.getOcrLanguageName(getBaseContext(), sharedPreferences.getString(key, CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE)));

      // Retrieve the character blacklist/whitelist for the new language
      String blacklist = OcrCharacterHelper.getBlacklist(sharedPreferences, listPreferenceSourceLanguage.getValue());
      String whitelist = OcrCharacterHelper.getWhitelist(sharedPreferences, listPreferenceSourceLanguage.getValue());

      // Save the character blacklist/whitelist to preferences
      sharedPreferences.edit().putString(KEY_CHARACTER_BLACKLIST, blacklist).commit();
      sharedPreferences.edit().putString(KEY_CHARACTER_WHITELIST, whitelist).commit();

      // Set the blacklist/whitelist summary text
      editTextPreferenceCharacterBlacklist.setSummary(blacklist);
      editTextPreferenceCharacterWhitelist.setSummary(whitelist);

    } else if (key.equals(KEY_TARGET_LANGUAGE_PREFERENCE)) {
      listPreferenceTargetLanguage.setSummary(LanguageCodeHelper.getTranslationLanguageName(this, sharedPreferences.getString(key, CaptureActivity.DEFAULT_TARGET_LANGUAGE_CODE)));
    } else if (key.equals(KEY_PAGE_SEGMENTATION_MODE)) {
      listPreferencePageSegmentationMode.setSummary(sharedPreferences.getString(key, CaptureActivity.DEFAULT_PAGE_SEGMENTATION_MODE));
    } else if (key.equals(KEY_OCR_ENGINE_MODE)) {
      listPreferenceOcrEngineMode.setSummary(sharedPreferences.getString(key, CaptureActivity.DEFAULT_OCR_ENGINE_MODE));
    } else if (key.equals(KEY_CHARACTER_BLACKLIST)) {

      // Save a separate, language-specific character blacklist for this language
      OcrCharacterHelper.setBlacklist(sharedPreferences,
        listPreferenceSourceLanguage.getValue(),
        sharedPreferences.getString(key, OcrCharacterHelper.getDefaultBlacklist(listPreferenceSourceLanguage.getValue())));

      // Set the summary text
      editTextPreferenceCharacterBlacklist.setSummary(sharedPreferences.getString(key, OcrCharacterHelper.getDefaultBlacklist(listPreferenceSourceLanguage.getValue())));

    } else if (key.equals(KEY_CHARACTER_WHITELIST)) {

      // Save a separate, language-specific character blacklist for this language
      OcrCharacterHelper.setWhitelist(sharedPreferences,
        listPreferenceSourceLanguage.getValue(),
        sharedPreferences.getString(key, OcrCharacterHelper.getDefaultWhitelist(listPreferenceSourceLanguage.getValue())));

      // Set the summary text
      editTextPreferenceCharacterWhitelist.setSummary(sharedPreferences.getString(key, OcrCharacterHelper.getDefaultWhitelist(listPreferenceSourceLanguage.getValue())));

    }

  }

  /**
   * Sets up initial preference summary text
   * values and registers the OnSharedPreferenceChangeListener.
   */
  @
  Override
  protected void onResume() {
    super.onResume();
    // Set up the initial summary values
    listPreferenceTranslator.setSummary(sharedPreferences.getString(KEY_TRANSLATOR, CaptureActivity.DEFAULT_TRANSLATOR));
    listPreferenceSourceLanguage.setSummary(LanguageCodeHelper.getOcrLanguageName(getBaseContext(), sharedPreferences.getString(KEY_SOURCE_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_SOURCE_LANGUAGE_CODE)));
    listPreferenceTargetLanguage.setSummary(LanguageCodeHelper.getTranslationLanguageName(getBaseContext(), sharedPreferences.getString(KEY_TARGET_LANGUAGE_PREFERENCE, CaptureActivity.DEFAULT_TARGET_LANGUAGE_CODE)));
    listPreferencePageSegmentationMode.setSummary(sharedPreferences.getString(KEY_PAGE_SEGMENTATION_MODE, CaptureActivity.DEFAULT_PAGE_SEGMENTATION_MODE));
    listPreferenceOcrEngineMode.setSummary(sharedPreferences.getString(KEY_OCR_ENGINE_MODE, CaptureActivity.DEFAULT_OCR_ENGINE_MODE));
    editTextPreferenceCharacterBlacklist.setSummary(sharedPreferences.getString(KEY_CHARACTER_BLACKLIST, OcrCharacterHelper.getDefaultBlacklist(listPreferenceSourceLanguage.getValue())));
    editTextPreferenceCharacterWhitelist.setSummary(sharedPreferences.getString(KEY_CHARACTER_WHITELIST, OcrCharacterHelper.getDefaultWhitelist(listPreferenceSourceLanguage.getValue())));

    // Set up a listener whenever a key changes
    getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
  }

  /**
   * Called when Activity is about to lose focus. Unregisters the
   * OnSharedPreferenceChangeListener.
   */
  @
  Override
  protected void onPause() {
    super.onPause();
    getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
  }
}




/**
 * Delegates translation requests to the appropriate translation service.
 */
class Translator {
  public static final String BAD_TRANSLATION_MSG = "[Translation unavailable]";
  static String translate(Activity activity, String sourceLanguageCode, String targetLanguageCode, String sourceText) {
    return TranslatorBing.translate("en", "zh-CHT", sourceText);
  }
}




class TranslatorBing {
  private static final String TAG = TranslatorBing.class.getSimpleName();

  /**
   *  Translate using Microsoft Translate API
   * @param sourceLanguageCode Source language code, for example, "en"
   * @param targetLanguageCode Target language code, for example, "es"
   * @param sourceText Text to send for translation
   * @return Translated text
   */
  static String translate(String sourceLanguageCode, String targetLanguageCode, String sourceText) {
    Translate.setClientId("davidbranniganz-translate");
    Translate.setClientSecret("HxwO7LY6xkLB5EU9jWcR1MJWE0dI1AwMPTVBp13l+ek=");
    try {
      Log.d(TAG, sourceLanguageCode + " -> " + targetLanguageCode);
      return Translate.execute(sourceText, Language.fromString(sourceLanguageCode),
        Language.fromString(targetLanguageCode));
    } catch (Exception e) {
      Log.e(TAG, "Caught exeption in translation request.");
      e.printStackTrace();
      return Translator.BAD_TRANSLATION_MSG;
    }
  }

  /**
   * Convert the given name of a natural language into a Language from the enum of Languages
   * supported by this translation service.
   *
   * @param languageName The name of the language, for example, "English"
   * @return code representing this language, for example, "en", for this translation API
   * @throws IllegalArgumentException
   */
  public static String toLanguage(String languageName) throws IllegalArgumentException {
    // Convert string to all caps
    String standardizedName = languageName.toUpperCase();

    // Replace spaces with underscores
    standardizedName = standardizedName.replace(' ', '_');

    // Remove parentheses
    standardizedName = standardizedName.replace("(", "");
    standardizedName = standardizedName.replace(")", "");

    // Map Norwegian-Bokmal to Norwegian
    if (standardizedName.equals("NORWEGIAN_BOKMAL")) {
      standardizedName = "NORWEGIAN";
    }

    try {
      return Language.valueOf(standardizedName).toString();
    } catch (IllegalArgumentException e) {
      Log.e(TAG, "Not found--returning default language code");
      return CaptureActivity.DEFAULT_TARGET_LANGUAGE_CODE;
    }
  }
}




/**
 * Class to perform translations in the background.
 */
class TranslateAsyncTask extends AsyncTask < String, String, Boolean > {

  private static final String TAG = TranslateAsyncTask.class.getSimpleName();

  private CaptureActivity activity;
  private TextView textView;
  private View progressView;
  private TextView targetLanguageTextView;
  private String sourceLanguageCode;
  private String targetLanguageCode;
  private String sourceText;
  private String translatedText = "";

  public TranslateAsyncTask(CaptureActivity activity, String sourceLanguageCode, String targetLanguageCode,
    String sourceText) {
    this.activity = activity;
    this.sourceLanguageCode = sourceLanguageCode;
    this.targetLanguageCode = targetLanguageCode;
    this.sourceText = sourceText;
    textView = (TextView) activity.findViewById(R.id.translation_text_view);
    progressView = (View) activity.findViewById(R.id.indeterminate_progress_indicator_view);
    targetLanguageTextView = (TextView) activity.findViewById(R.id.translation_language_text_view);
  }

  @
  Override
  protected Boolean doInBackground(String...arg0) {
    translatedText = Translator.translate(activity, sourceLanguageCode, targetLanguageCode, sourceText);

    // Check for failed translations.
    if (translatedText.equals(Translator.BAD_TRANSLATION_MSG)) {
      return false;
    }

    return true;
  }

  @
  Override
  protected synchronized void onPostExecute(Boolean result) {
    super.onPostExecute(result);

    if (result) {
      //Log.i(TAG, "SUCCESS");
      if (targetLanguageTextView != null) {
        targetLanguageTextView.setTypeface(Typeface.defaultFromStyle(Typeface.NORMAL), Typeface.NORMAL);
      }
      textView.setText(translatedText);
      textView.setVisibility(View.VISIBLE);
      textView.setTextColor(activity.getResources().getColor(R.color.translation_text));

      // Crudely scale betweeen 22 and 32 -- bigger font for shorter text
      int scaledSize = Math.max(22, 32 - translatedText.length() / 4);
      textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize);

    } else {
      Log.e(TAG, "FAILURE");
      targetLanguageTextView.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC), Typeface.ITALIC);
      targetLanguageTextView.setText("Unavailable");

    }

    // Turn off the indeterminate progress indicator
    if (progressView != null) {
      progressView.setVisibility(View.GONE);
    }
  }
}
