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
import android.graphics.Typeface;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

import com.googlecode.leptonica.android.ReadFile;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.memetix.mst.language.Language;
import com.memetix.mst.translate.Translate;

import edu.sfsu.cs.orange.ocr.R;
import edu.sfsu.cs.orange.ocr.ShutterButton;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.logging.Logger;
import java.util.Timer;
import java.util.TimerTask;
// import WindowUtils for voice commands
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.google.android.glass.view.WindowUtils;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the text correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing/
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback,
  ShutterButton.OnShutterButtonListener {

    private static final String TAG = MainActivity.class.getSimpleName();

    // Note: These constants will be overridden by any default values defined in preferences.xml.

    /** ISO 639-3 language code indicating the default recognition language. */
    public static final String DEFAULT_SOURCE_LANGUAGE_CODE = "eng";

    /** ISO 639-1 language code indicating the default target language for translation. */
    public static final String DEFAULT_TARGET_LANGUAGE_CODE = "zh-CHT";

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
    private boolean isContinuousModeActive; // Whether we are doing OCR in continuous mode
    private SharedPreferences prefs;
    private OnSharedPreferenceChangeListener listener;
    private ProgressDialog dialog; // for initOcr - language download & unzip
    private ProgressDialog indeterminateDialog; // also for initOcr - init OCR engine
    private boolean isEngineReady;
    private boolean isPaused;
    private static boolean isFirstLaunch; // True if this is the first time the app is being run

    Logger log = Logger.getLogger("MainActivity");
    private GestureDetector mGestureDetector;

    private GestureDetector createGestureDetector(Context context) {
    GestureDetector gestureDetector = new GestureDetector(context);
        //Create a base listener for generic gestures
        gestureDetector.setBaseListener( new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                log.info(gesture.name());
                if (gesture == Gesture.TAP) {
                    // do something on tap
                    if (isContinuousModeActive) {
                      onShutterButtonPressContinuous();
                    } else {
                      handler.hardwareShutterButtonClick();
                    }
                    return true;
                } else if (gesture == Gesture.TWO_TAP) {
                    // do something on two finger tap
                    return true;
                } else if (gesture == Gesture.SWIPE_RIGHT) {
                    // do something on right (forward) swipe
                    findViewById(R.id.scroll_result_text_view).scrollBy(0, 180);
                    log.info("scroll right");
                    return true;
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    // do something on left (backwards) swipe
                    findViewById(R.id.scroll_result_text_view).scrollBy(0, -180);
                    log.info("scroll left");
                    return true;
                }
                return false;
            }
        });
        //!! gestureDetector.setFingerListener(new GestureDetector.FingerListener() {
            //!! @Override
            //!! public void onFingerCountChanged(int previousCount, int currentCount) {
              //!! // do something on finger count changes
            //!! }
        //!! });
        //!! gestureDetector.setScrollListener(new GestureDetector.ScrollListener() {
            //!! @Override
            //!! public boolean onScroll(float displacement, float delta, float velocity) {
                //!! // do something on scrolling
            //!! }
        //!! });
        return gestureDetector;
    }

    /*
     * Send generic motion events to the gesture detector
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return false;
    }


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
      statusViewTop = (TextView) findViewById(R.id.status_view_top);

      handler = null;
      lastResult = null;
      hasSurface = false;

      // Camera shutter button
      if (DISPLAY_SHUTTER_BUTTON) {
        shutterButton = (ShutterButton) findViewById(R.id.shutter_button);
        shutterButton.setOnShutterButtonListener(this);
      }

      ocrResultView = (TextView) findViewById(R.id.ocr_result_text_view);
      translationView = (TextView) findViewById(R.id.translation_text_view);

      progressView = (View) findViewById(R.id.indeterminate_progress_indicator_view);

      cameraManager = new CameraManager(getApplication());
      viewfinderView.setCameraManager(cameraManager);
      isEngineReady = false;

      mGestureDetector = createGestureDetector(this);
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
      boolean doNewInit = (baseApi == null) || !sourceLanguageCodeOcr.equals(previousSourceLanguageCodeOcr) || ocrEngineMode != previousOcrEngineMode;
      if (doNewInit) {
        // Initialize the OCR engine
      String state = null;
      try {
        state = Environment.getExternalStorageState();
      } catch (RuntimeException e) {
        Log.e(TAG, "Is the SD card visible?", e);
        showErrorMessage("Error", "Required external storage (such as an SD card) is unavailable.");
      }

      if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {

        try {
          /** Finds the proper location on the SD card where we can save files. */
          initOcrEngine(getExternalFilesDir(Environment.MEDIA_MOUNTED), sourceLanguageCodeOcr, sourceLanguageReadable);
          } catch (NullPointerException e) {
            // We get an error here if the SD card is visible, but full
            Log.e(TAG, "External storage is unavailable");
            showErrorMessage("Error", "Required external storage (such as an SD card) is full or unavailable.");
          }

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
      initCamera(holder);
      hasSurface = true;
    }

    /** Initializes the camera and starts the handler to begin previewing. */
    private void initCamera(SurfaceHolder surfaceHolder) {
      Log.d(TAG, "initCamera()");
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
      // capture image for ocr on camera key or glass tap
      } else if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
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
      setSourceLanguage(prefs.getString(PreferencesActivity.KEY_SOURCE_LANGUAGE_PREFERENCE, MainActivity.DEFAULT_SOURCE_LANGUAGE_CODE));
      setTargetLanguage(prefs.getString(PreferencesActivity.KEY_TARGET_LANGUAGE_PREFERENCE, MainActivity.DEFAULT_TARGET_LANGUAGE_CODE));

      // Retrieve from preferences, and set in this Activity, the capture mode preference
      if (prefs.getBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, MainActivity.DEFAULT_TOGGLE_CONTINUOUS)) {
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
      prefs.edit().putBoolean(PreferencesActivity.KEY_CONTINUOUS_PREVIEW, MainActivity.DEFAULT_TOGGLE_CONTINUOUS).commit();

      // Recognition language
      prefs.edit().putString(PreferencesActivity.KEY_SOURCE_LANGUAGE_PREFERENCE, MainActivity.DEFAULT_SOURCE_LANGUAGE_CODE).commit();

      // Translation
      prefs.edit().putBoolean(PreferencesActivity.KEY_TOGGLE_TRANSLATION, MainActivity.DEFAULT_TOGGLE_TRANSLATION).commit();

      // Translation target language
      prefs.edit().putString(PreferencesActivity.KEY_TARGET_LANGUAGE_PREFERENCE, MainActivity.DEFAULT_TARGET_LANGUAGE_CODE).commit();

      // OCR Engine
      prefs.edit().putString(PreferencesActivity.KEY_OCR_ENGINE_MODE, MainActivity.DEFAULT_OCR_ENGINE_MODE).commit();

      // Autofocus
      prefs.edit().putBoolean(PreferencesActivity.KEY_AUTO_FOCUS, MainActivity.DEFAULT_TOGGLE_AUTO_FOCUS).commit();

      // Disable problematic focus modes
      prefs.edit().putBoolean(PreferencesActivity.KEY_DISABLE_CONTINUOUS_FOCUS, MainActivity.DEFAULT_DISABLE_CONTINUOUS_FOCUS).commit();

      // Beep
      prefs.edit().putBoolean(PreferencesActivity.KEY_PLAY_BEEP, MainActivity.DEFAULT_TOGGLE_BEEP).commit();

      // Character blacklist
      prefs.edit().putString(PreferencesActivity.KEY_CHARACTER_BLACKLIST,
        OcrCharacterHelper.getDefaultBlacklist(MainActivity.DEFAULT_SOURCE_LANGUAGE_CODE)).commit();

      // Character whitelist
      prefs.edit().putString(PreferencesActivity.KEY_CHARACTER_WHITELIST,
        OcrCharacterHelper.getDefaultWhitelist(MainActivity.DEFAULT_SOURCE_LANGUAGE_CODE)).commit();

      // Page segmentation mode
      prefs.edit().putString(PreferencesActivity.KEY_PAGE_SEGMENTATION_MODE, MainActivity.DEFAULT_PAGE_SEGMENTATION_MODE).commit();

      // Reversed camera image
      prefs.edit().putBoolean(PreferencesActivity.KEY_REVERSE_IMAGE, MainActivity.DEFAULT_TOGGLE_REVERSED_IMAGE).commit();

      // Light
      prefs.edit().putBoolean(PreferencesActivity.KEY_TOGGLE_LIGHT, MainActivity.DEFAULT_TOGGLE_LIGHT).commit();
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
      previewFrame = cameraManager.getFramingRect();
      if (bitmapSize.x == previewFrame.width() && bitmapSize.y == previewFrame.height()) {


        float scaleX = frame.width() / (float) previewFrame.width();
        float scaleY = frame.height() / (float) previewFrame.height();

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

  private final MainActivity activity;
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

  CaptureActivityHandler(MainActivity activity, CameraManager cameraManager, boolean isContinuousModeActive) {
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
    // cameraResolution = new Point(640, 480);
    cameraResolution = new Point(1280, 960);
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

  private final Context context;
  private final CameraConfigurationManager configManager;
  private Camera camera;
  private AutoFocusManager autoFocusManager;
  private Rect framingRect;
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
      framingRect = new Rect(160, 120, 480, 240);
    }
    return framingRect;
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
    Rect rect = new Rect(320, 240, 960, 480);
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
    } else if (key.equals(KEY_SOURCE_LANGUAGE_PREFERENCE)) {

      // Set the summary text for the source language name
      listPreferenceSourceLanguage.setSummary(LanguageCodeHelper.getOcrLanguageName(getBaseContext(), sharedPreferences.getString(key, MainActivity.DEFAULT_SOURCE_LANGUAGE_CODE)));

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
      listPreferenceTargetLanguage.setSummary(LanguageCodeHelper.getTranslationLanguageName(this, sharedPreferences.getString(key, MainActivity.DEFAULT_TARGET_LANGUAGE_CODE)));
    } else if (key.equals(KEY_PAGE_SEGMENTATION_MODE)) {
      listPreferencePageSegmentationMode.setSummary(sharedPreferences.getString(key, MainActivity.DEFAULT_PAGE_SEGMENTATION_MODE));
    } else if (key.equals(KEY_OCR_ENGINE_MODE)) {
      listPreferenceOcrEngineMode.setSummary(sharedPreferences.getString(key, MainActivity.DEFAULT_OCR_ENGINE_MODE));
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
    listPreferenceSourceLanguage.setSummary(LanguageCodeHelper.getOcrLanguageName(getBaseContext(), sharedPreferences.getString(KEY_SOURCE_LANGUAGE_PREFERENCE, MainActivity.DEFAULT_SOURCE_LANGUAGE_CODE)));
    listPreferenceTargetLanguage.setSummary(LanguageCodeHelper.getTranslationLanguageName(getBaseContext(), sharedPreferences.getString(KEY_TARGET_LANGUAGE_PREFERENCE, MainActivity.DEFAULT_TARGET_LANGUAGE_CODE)));
    listPreferencePageSegmentationMode.setSummary(sharedPreferences.getString(KEY_PAGE_SEGMENTATION_MODE, MainActivity.DEFAULT_PAGE_SEGMENTATION_MODE));
    listPreferenceOcrEngineMode.setSummary(sharedPreferences.getString(KEY_OCR_ENGINE_MODE, MainActivity.DEFAULT_OCR_ENGINE_MODE));
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
 * Class to perform translations in the background.
 */
class TranslateAsyncTask extends AsyncTask < String, String, Boolean > {

  private static final String TAG = TranslateAsyncTask.class.getSimpleName();

  private MainActivity activity;
  private TextView textView;
  private View progressView;
  private TextView targetLanguageTextView;
  private String sourceLanguageCode;
  private String targetLanguageCode;
  private String sourceText;
  private String translatedText = "";

  public TranslateAsyncTask(MainActivity activity, String sourceLanguageCode, String targetLanguageCode,
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
    Translate.setClientId("davidbranniganz-translate");
    Translate.setClientSecret("HxwO7LY6xkLB5EU9jWcR1MJWE0dI1AwMPTVBp13l+ek=");
    try {
      Log.d(TAG, "en -> zh-CHT");
      translatedText = Translate.execute(sourceText, Language.fromString("en"),
        Language.fromString("zh-CHT"));
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Caught exeption in translation request.");
      e.printStackTrace();
      translatedText = "[Translation unavailable]";
      return false;
    }
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
      textView.setText(translatedText + "\n\n");
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



/**
 * Class to send bitmap data for OCR.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing/
 */
class DecodeHandler extends Handler {

  private final MainActivity activity;
  private boolean running = true;
  private final TessBaseAPI baseApi;
  private Bitmap bitmap;
  private static boolean isDecodePending;
  private long timeRequired;

  DecodeHandler(MainActivity activity) {
    this.activity = activity;
    baseApi = activity.getBaseApi();
  }

  @Override
  public void handleMessage(Message message) {
    if (!running) {
      return;
    }
    if (message.what == R.id.ocr_continuous_decode) {
		// Only request a decode if a request is not already pending.
		  if (!isDecodePending) {
		    isDecodePending = true;
		    ocrContinuousDecode((byte[]) message.obj, message.arg1, message.arg2);
		  }
	} else if (message.what == R.id.ocr_decode) {
		ocrDecode((byte[]) message.obj, message.arg1, message.arg2);
	} else if (message.what == R.id.quit) {
		running = false;
		Looper.myLooper().quit();
	}
  }

  static void resetDecodeState() {
    isDecodePending = false;
  }

  /**
   *  Launch an AsyncTask to perform an OCR decode for single-shot mode.
   *
   * @param data Image data
   * @param width Image width
   * @param height Image height
   */
  private void ocrDecode(byte[] data, int width, int height) {
    activity.displayProgressDialog();

    // Launch OCR asynchronously, so we get the dialog box displayed immediately
    new OcrRecognizeAsyncTask(activity, baseApi, data, width, height).execute();
  }

  /**
   *  Perform an OCR decode for realtime recognition mode.
   *
   * @param data Image data
   * @param width Image width
   * @param height Image height
   */
  private void ocrContinuousDecode(byte[] data, int width, int height) {
    PlanarYUVLuminanceSource source = activity.getCameraManager().buildLuminanceSource(data, width, height);
    if (source == null) {
      sendContinuousOcrFailMessage();
      return;
    }
    bitmap = source.renderCroppedGreyscaleBitmap();

    OcrResult ocrResult = getOcrResult();
    Handler handler = activity.getHandler();
    if (handler == null) {
      return;
    }

    if (ocrResult == null) {
      try {
        sendContinuousOcrFailMessage();
      } catch (NullPointerException e) {
        activity.stopHandler();
      } finally {
        bitmap.recycle();
        baseApi.clear();
      }
      return;
    }

    try {
      Message message = Message.obtain(handler, R.id.ocr_continuous_decode_succeeded, ocrResult);
      message.sendToTarget();
    } catch (NullPointerException e) {
      activity.stopHandler();
    } finally {
      baseApi.clear();
    }
  }

  @SuppressWarnings("unused")
	private OcrResult getOcrResult() {
    OcrResult ocrResult;
    String textResult;
    long start = System.currentTimeMillis();

    try {
      textResult = baseApi.getUTF8Text();
      timeRequired = System.currentTimeMillis() - start;

      // Check for failure to recognize text
      if (textResult == null || textResult.equals("")) {
        return null;
      }
      ocrResult = new OcrResult();
      ocrResult.setWordConfidences(baseApi.wordConfidences());
      ocrResult.setMeanConfidence( baseApi.meanConfidence());

      // Always get the word bounding boxes--we want it for annotating the bitmap after the user
      // presses the shutter button, in addition to maybe wanting to draw boxes/words during the
      // continuous mode recognition.
      ocrResult.setWordBoundingBoxes(baseApi.getWords().getBoxRects());

    } catch (RuntimeException e) {
      Log.e("OcrRecognizeAsyncTask", "Caught RuntimeException in request to Tesseract. Setting state to CONTINUOUS_STOPPED.");
      e.printStackTrace();
      try {
        baseApi.clear();
        activity.stopHandler();
      } catch (NullPointerException e1) {
        // Continue
      }
      return null;
    }
    timeRequired = System.currentTimeMillis() - start;
    ocrResult.setBitmap(bitmap);
    ocrResult.setText(textResult);
    ocrResult.setRecognitionTimeRequired(timeRequired);
    return ocrResult;
  }

  private void sendContinuousOcrFailMessage() {
    Handler handler = activity.getHandler();
    if (handler != null) {
      Message message = Message.obtain(handler, R.id.ocr_continuous_decode_failed, new OcrResultFailure(timeRequired));
      message.sendToTarget();
    }
  }

}












/**
 * This thread does all the heavy lifting of decoding the images.
 *
 * The code for this class was adapted from the ZXing project: http://code.google.com/p/zxing
 */
final class DecodeThread extends Thread {

  private final MainActivity activity;
  private Handler handler;
  private final CountDownLatch handlerInitLatch;

  DecodeThread(MainActivity activity) {
    this.activity = activity;
    handlerInitLatch = new CountDownLatch(1);
  }

  Handler getHandler() {
    try {
      handlerInitLatch.await();
    } catch (InterruptedException ie) {
      // continue?
    }
    return handler;
  }

  @Override
  public void run() {
    Looper.prepare();
    handler = new DecodeHandler(activity);
    handlerInitLatch.countDown();
    Looper.loop();
  }
}
