package com.limelight;


import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.TouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.utils.Dialog;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.text.DateFormat;
import java.util.Date;


public class Game extends Activity implements SurfaceHolder.Callback,
    OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
    OnSystemUiVisibilityChangeListener, GameGestures
{
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private int lastButtonState = 0;

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private long threeFingerDownTime = 0;

    private static final int REFERENCE_HORIZ_RES = 1280;
    private static final int REFERENCE_VERT_RES = 720;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;

    private ControllerHandler controllerHandler;
    private VirtualController virtualController;

    private PreferenceConfiguration prefConfig;

    private NvConnection conn;
    private SpinnerDialog spinner;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;

    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean grabComboDown = false;
    private StreamView streamView;

    private ShortcutHelper shortcutHelper;

    private MediaCodecDecoderRenderer decoderRenderer;

    private WifiManager.WifiLock wifiLock;

    private boolean connectedToUsbDriverService = false;
    private ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
            binder.setListener(controllerHandler);
            connectedToUsbDriverService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            connectedToUsbDriverService = false;
        }
    };

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_STREAMING_REMOTE = "Remote";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";

    // private boolean new_packet = false;
    // private boolean new_packet = true;
    private boolean new_rlc = false;
    private boolean new_mac = false;


    private int pkt_size, wait_delay, proc_delay, trans_delay, ul_total_delay;
    private float dl_bandwidth;
    private float handover_disruption;
    private int sr_period, sr_config_index;
    private float ho_prediction_timestamp = -1, ho_timestamp = -1;
    private String ho_prediction_target, ho_target;

    private float cell_load, estimated_bandwidth;
    private float mac_loss, rlc_loss;
    private float mac_retx_delay, rlc_retx_delay;
    private int ul_queue_length;

    private final BroadcastReceiver MobileInsight_Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals("android.appwidget.action.APPWIDGET_ENABLED")) {
                //TODO: ???
            } else if (intent.getAction().equals("MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN")) {

//                Log.i("Yuanjie-Game","MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN");
                if (intent.getStringExtra("pkt_size") != null && intent.getStringExtra("wait_delay") != null
                        && intent.getStringExtra("proc_delay") != null && intent.getStringExtra("trans_delay") != null) {
                    pkt_size = Integer.parseInt(intent.getStringExtra("pkt_size"));
                    wait_delay = Integer.parseInt(intent.getStringExtra("wait_delay"));
                    proc_delay = Integer.parseInt(intent.getStringExtra("proc_delay"));
                    trans_delay = Integer.parseInt(intent.getStringExtra("trans_delay"));
                    ul_total_delay = wait_delay + proc_delay + trans_delay;
                }
            } else if (intent.getAction().equals("MobileInsight.LtePhyAnalyzer.LTE_DL_BW")) {

//                Log.i("Yuanjie-Game","MobileInsight.RrcSrAnalyzer.RRC_SR");
                dl_bandwidth = Float.parseFloat(intent.getStringExtra("Predicted Bandwidth (Mbps)"));

            } else if (intent.getAction().equals("MobileInsight.LteHandoverDisruptionAnalyzer.HANDOVER_LATENCY")) {

//                Log.i("Yuanjie-Game","MobileInsight.RrcSrAnalyzer.RRC_SR");
                handover_disruption = Float.parseFloat(intent.getStringExtra("uplink_disruption"));

            } else if (intent.getAction().equals("MobileInsight.RrcConfigAnalyzer.SR_CONFIGIDX")) {

//                Log.i("Yuanjie-Game","MobileInsight.RrcSrAnalyzer.RRC_SR");
                sr_period = Integer.parseInt(intent.getStringExtra("period"));
                sr_config_index = Integer.parseInt(intent.getStringExtra("config idx"));


            } else if (intent.getAction().equals("MobileInsight.LteHandoverPredictionAnalyzer.HANDOVER_PREDICTION")) {

                Log.i("Yuanjie-Game", "MobileInsight.LteHandoverPredictionAnalyzer.HANDOVER_PREDICTION");
                ho_prediction_timestamp = Float.parseFloat(intent.getStringExtra("Timestamp"));
                ho_prediction_target = intent.getStringExtra("event");

            } else if (intent.getAction().equals("MobileInsight.LteHandoverPredictionAnalyzer.HANDOVER_EVENT")) {

                Log.i("Yuanjie-Game", "MobileInsight.LteHandoverPredictionAnalyzer.HANDOVER_EVENT");
                ho_timestamp = Float.parseFloat(intent.getStringExtra("Timestamp"));
                ho_target = intent.getStringExtra("event");

            } else if (intent.getAction().equals("MobileInsight.LteBandwidthPredictor.BANDWIDTH_PREDICTION")) {
                cell_load = Float.parseFloat(intent.getStringExtra("Cell load"));
                estimated_bandwidth = Float.parseFloat(intent.getStringExtra("Estimated free bandwidth (Mbps)"));

            } else if (intent.getAction().equals("MobileInsight.LteMacAnalyzer.MAC_RETX")) {
                new_mac = true;
                mac_loss = Float.parseFloat(intent.getStringExtra("packet loss (pkt/s)"));
                mac_retx_delay =  Float.parseFloat(intent.getStringExtra("retransmission delay (ms/pkt)"));

            } else if (intent.getAction().equals("MobileInsight.LteMacAnalyzer.RLC_RETX")) {
                new_rlc = true;
                rlc_loss = Float.parseFloat(intent.getStringExtra("packet loss (pkt/s)"));
                rlc_retx_delay =  Float.parseFloat(intent.getStringExtra("retransmission delay (ms/pkt)"));
            }
            else if (intent.getAction().equals("MobileInsight.LteMacAnalyzer.UL_QUEUE_LENGTH")) {
                ul_queue_length = Integer.parseInt(intent.getStringExtra("length"));
            }
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // MobileInsight: Register broadcast receiver
        IntentFilter ul_latency_filter = new IntentFilter("MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN");
        IntentFilter rrc_sr_filter = new IntentFilter("MobileInsight.RrcSrAnalyzer.RRC_SR");
        IntentFilter phy_filter = new IntentFilter("MobileInsight.LtePhyAnalyzer.LTE_DL_BW");
        IntentFilter handover_disruption_filter = new IntentFilter("MobileInsight.LteHandoverDisruptionAnalyzer.HANDOVER_LATENCY");
        IntentFilter handover_prediction_filter = new IntentFilter("MobileInsight.LteHandoverPredictionAnalyzer.HANDOVER_EVENT");
        IntentFilter handover_prediction_filter_2 = new IntentFilter("MobileInsight.LteHandoverPredictionAnalyzer.HANDOVER_PREDICTION");
        IntentFilter sr_config_filter = new IntentFilter("MobileInsight.RrcConfigAnalyzer.SR_CONFIGIDX");
        IntentFilter bandwidth_prediction_filter = new IntentFilter("MobileInsight.LteBandwidthPredictor.BANDWIDTH_PREDICTION");
        IntentFilter mac_loss_filter = new IntentFilter("MobileInsight.LteMacAnalyzer.MAC_RETX");
        IntentFilter rlc_loss_filter = new IntentFilter("MobileInsight.LteMacAnalyzer.RLC_RETX");
        registerReceiver(MobileInsight_Receiver, ul_latency_filter);
        registerReceiver(MobileInsight_Receiver, rrc_sr_filter);
        registerReceiver(MobileInsight_Receiver, phy_filter);
        registerReceiver(MobileInsight_Receiver, handover_disruption_filter);
        registerReceiver(MobileInsight_Receiver, sr_config_filter);
        registerReceiver(MobileInsight_Receiver, handover_prediction_filter);
        registerReceiver(MobileInsight_Receiver, handover_prediction_filter_2);
        registerReceiver(MobileInsight_Receiver, bandwidth_prediction_filter);
        registerReceiver(MobileInsight_Receiver, mac_loss_filter);
        registerReceiver(MobileInsight_Receiver, rlc_loss_filter);


        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen and don't let the display go off
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        }

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        // Start the spinner
        spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.conn_establishing_msg), true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);

        // Listen for events on the game surface
        streamView = (StreamView) findViewById(R.id.surfaceView);
        streamView.setOnGenericMotionListener(this);
        streamView.setOnTouchListener(this);

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The view must be focusable for pointer capture to work.
            streamView.setFocusable(true);
            streamView.setDefaultFocusHighlightEnabled(false);
            streamView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
                    return handleMotionEvent(motionEvent);
                }
            });
        }

        // Warn the user if they're on a metered connection
        checkDataConnection();

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Limelight");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        String appName = Game.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        int appId = Game.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        boolean remote = Game.this.getIntent().getBooleanExtra(EXTRA_STREAMING_REMOTE, false);
        String uuid = Game.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        String pcName = Game.this.getIntent().getStringExtra(EXTRA_PC_NAME);

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Add a launcher shortcut for this PC (forced, since this is user interaction)
        shortcutHelper.createAppViewShortcut(uuid, pcName, uuid, true);
        shortcutHelper.reportShortcutUsed(uuid);

        // Initialize the MediaCodec helper before creating the decoder
        MediaCodecHelper.initializeWithContext(this);

//        int service_code = get_service_code("com.android.internal.telephony.ITelephony",
//                        "getPreferredNetworkType");

//        RootCommand("service call phone " + Integer.toString(service_code) + " i32 " + Integer.toString(10), false);

        decoderRenderer = new MediaCodecDecoderRenderer(prefConfig.videoFormat, prefConfig.bitrate, prefConfig.batterySaver);

        // Display a message to the user if H.265 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FORCE_H265_ON && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No H.265 decoder found.\nFalling back to H.264.", Toast.LENGTH_LONG).show();
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (spinner != null) {
                spinner.dismiss();
                spinner = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }
        
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setRefreshRate(prefConfig.fps)
                .setApp(new NvApp(appName, appId))
                .setBitrate(prefConfig.bitrate * 1000)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(remote ? 1024 : 1292)
                .setRemote(remote)
                .setHevcSupported(decoderRenderer.isHevcSupported())
                .setAudioConfiguration(prefConfig.enable51Surround ?
                        MoonBridge.AUDIO_CONFIGURATION_51_SURROUND :
                        MoonBridge.AUDIO_CONFIGURATION_STEREO)
                .build();

        // Initialize the connection
        conn = new NvConnection(host, uniqueId, config, PlatformBinding.getCryptoProvider(this));
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig.multiController, prefConfig.deadzonePercentage);

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(controllerHandler, null);

        // Set to the optimal mode for streaming
        prepareDisplayForRendering();

        // Initialize touch contexts
        for (int i = 0; i < touchContextMap.length; i++) {
            touchContextMap[i] = new TouchContext(conn, i,
                    REFERENCE_HORIZ_RES, REFERENCE_VERT_RES,
                    streamView);
        }

        // Use sustained performance mode on N+ to ensure consistent
        // CPU availability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getWindow().setSustainedPerformanceMode(true);
        }

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            virtualController = new VirtualController(conn,
                    (FrameLayout)findViewById(R.id.surfaceView).getParent(),
                    this);
            virtualController.refreshLayout();
        }

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        // The connection will be started when the surface gets created
        streamView.getHolder().addCallback(this);


        final TextView statsTextView = (TextView) findViewById(R.id.textViewStat);

        // Create UI change thread
        Runnable printStats = new Runnable() {
            @Override
            public void run() {
                String filename = "results.csv";
                FileOutputStream outputStream = null;
                try {
                    outputStream = openFileOutput(filename, MODE_APPEND);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }

                while(true){
                    SystemClock.sleep(100);
                    // decoderRenderer.setFPS(30);
                    int totalFrames = decoderRenderer.getTotalFrames();
                    int avgLatency = decoderRenderer.getAverageDecoderLatency();
                    int transLatency = decoderRenderer.getTransDelay();
                    int currentFps = decoderRenderer.getFPSPeriod();
                    int targetFps = decoderRenderer.getTargetFPS();
                    int avg_e2e_delay = decoderRenderer.getAverageEndToEndLatency();
                    int framesLost = decoderRenderer.getFramesLostPeriod();
                    int bitrate = decoderRenderer.getBitrate();

                    if (!new_mac) {
                        mac_loss = 0;
                        mac_retx_delay = 0;
                    }

                    if (!new_rlc) {
                        rlc_loss = 0;
                        rlc_retx_delay = 0;
                    }


                    String stats = "";
                    // stats += "Total frames: " + String.valueOf(totalFrames) + "\n";
                    stats += "Frame loss in last period: " + String.valueOf(framesLost) + "\n";
                    stats += "FPS in last period: " + String.valueOf(currentFps) + " Target: "+targetFps+"\n";
                    stats += "Transmission delay: " + String.valueOf(transLatency) + "\n";
                    // stats += "Target bitrate: " + String.valueOf(bitrate) + "Mbps\n";
                    // stats += "Runtime bandwidth: " + String.valueOf(dl_bandwidth) +" Mbps\n";
                    stats += "--- (LTE KPIs) ---\n";
                    stats += "Cell load: " + String.valueOf(cell_load) + "\n";
                    stats += "Estimated bandwidth: " + String.valueOf(estimated_bandwidth) +" Mbps\n";

                    // stats += "Average end-to-end delay: " + String.valueOf(avg_e2e_delay) + "\n";
                    // stats += "Average decoding latency: " + String.valueOf(avgLatency) + "\n";
                    stats += "uplink delay: "+String.valueOf(ul_total_delay)+" ms\n";
                    stats += "    (wait: " + String.valueOf(wait_delay)
                            + " proc: " + String.valueOf(proc_delay)
                            + " trans: " + String.valueOf(trans_delay) + ")\n";
                    stats += "uplink queue length: "+String.valueOf(ul_queue_length)+" ms\n";
                    stats += "MAC loss/retx: "+String.valueOf(mac_loss)+" pkt/s, "+ String.valueOf(mac_retx_delay) +" ms/pkt\n";
                    stats += "RLC loss/retx: "+String.valueOf(rlc_loss)+" pkt/s, "+ String.valueOf(rlc_retx_delay) +" ms/pkt\n";
                    stats += "SrConfig Period: " + String.valueOf(sr_period) +"ms\n";

                    stats += "handover disruption: " + String.valueOf(handover_disruption)+"ms\n";
                    if (ho_prediction_timestamp!=-1 && ho_timestamp!=-1)
                        stats += "handover prediction: " + String.valueOf(ho_timestamp-ho_prediction_timestamp)+"ms"
                                +" "+String.valueOf(ho_prediction_timestamp)
                                +" "+String.valueOf(ho_timestamp)+"\n";
                    setStatsText(statsTextView, stats);
                    // Log.i("game","Zhaowei: UI thread running");
                    // new_packet = false;



                    String csvString = "";

                    csvString += String.valueOf(framesLost) + ',';
                    csvString += String.valueOf(cell_load) + ',';
                    csvString += String.valueOf(mac_loss) + ',' + String.valueOf(mac_retx_delay) + ',';
                    csvString += String.valueOf(rlc_loss) + ',' + String.valueOf(rlc_retx_delay) + ',';
                    csvString += String.valueOf(transLatency) + ',';
                    csvString += '\n';

                    try {
                        outputStream.write(csvString.getBytes());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

//                        if (framesLost > 0) {
//                            Log.i("decoder", "Zhaowei: " + stats);
//                        }
                    new_mac = false;
                    new_rlc = false;

                }
            }
        };

        Thread statsThread = new Thread(printStats);
        statsThread.start();
    }

    private void setStatsText(final TextView text,final String value){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                text.setText(value);
            }
        });
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Capture is lost when focus is lost, so it must be requested again
            // when focus is regained.
            if (inputCaptureProvider.isCapturingEnabled() && hasFocus) {
                // Recapture the pointer if focus was regained
                streamView.requestPointerCapture();
            }
        }
    }

    private void prepareDisplayForRendering() {
        Display display = getWindowManager().getDefaultDisplay();
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode bestMode = display.getMode();
            for (Display.Mode candidate : display.getSupportedModes()) {
                boolean refreshRateOk = candidate.getRefreshRate() >= bestMode.getRefreshRate() &&
                        candidate.getRefreshRate() < 63;
                boolean resolutionOk = candidate.getPhysicalWidth() >= bestMode.getPhysicalWidth() &&
                        candidate.getPhysicalHeight() >= bestMode.getPhysicalHeight() &&
                        candidate.getPhysicalWidth() <= 4096;

                LimeLog.info("Examining display mode: "+candidate.getPhysicalWidth()+"x"+
                        candidate.getPhysicalHeight()+"x"+candidate.getRefreshRate());

                // On non-4K streams, we force the resolution to never change
                if (prefConfig.width < 3840) {
                    if (display.getMode().getPhysicalWidth() != candidate.getPhysicalWidth() ||
                            display.getMode().getPhysicalHeight() != candidate.getPhysicalHeight()) {
                        continue;
                    }
                }

                // Make sure the refresh rate doesn't regress
                if (!refreshRateOk) {
                    continue;
                }

                // Make sure the resolution doesn't regress
                if (!resolutionOk) {
                    continue;
                }

                bestMode = candidate;
            }
            LimeLog.info("Selected display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());
            windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
        }
        // On L, we can at least tell the OS that we want 60 Hz
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                if (candidate > bestRefreshRate && candidate < 63) {
                    LimeLog.info("Examining refresh rate: "+candidate);
                    bestRefreshRate = candidate;
                }
            }
            LimeLog.info("Selected refresh rate: "+bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
        }

        // Apply the display mode change
        getWindow().setAttributes(windowLayoutParams);

        // From 4.4 to 5.1 we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // On KitKat and later (where we can use the whole screen via immersive mode), we'll
            // calculate whether we need to scale by aspect ratio or not. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            Point screenSize = new Point(0, 0);
            display.getSize(screenSize);

            double screenAspectRatio = ((double)screenSize.y) / screenSize.x;
            double streamAspectRatio = ((double)prefConfig.height) / prefConfig.width;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

        if (prefConfig.stretchVideo || aspectRatioMatch) {
            // Set the surface to the size of the video
            streamView.getHolder().setFixedSize(prefConfig.width, prefConfig.height);
        }
        else {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView.setDesiredAspectRatio((double)prefConfig.width / (double)prefConfig.height);
        }
    }

    private void checkDataConnection()
    {
        ConnectivityManager mgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mgr.isActiveNetworkMetered()) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
            @Override
            public void run() {
                // Use immersive mode on 4.4+ or standard low profile on previous builds
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
                else {
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LOW_PROFILE);
                }
            }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(MobileInsight_Receiver);

        if (controllerHandler != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(controllerHandler);
        }

        wifiLock.release();

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            unbindService(usbDriverServiceConnection);
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();
    }

    @Override
    protected void onStop() {
        super.onStop();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (conn != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();

            displayedFailureDialog = true;
            stopConnection();

            int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
            int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
            String message = null;
            if (averageEndToEndLat > 0) {
                message = getResources().getString(R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
                if (averageDecoderLat > 0) {
                    message += " ("+getResources().getString(R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
                }
            }
            else if (averageDecoderLat > 0) {
                message = getResources().getString(R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
            }

            // Add the video codec to the post-stream toast
            if (message != null) {
                if (videoFormat == MoonBridge.VIDEO_FORMAT_H265) {
                    message += " [H.265]";
                }
                else if (videoFormat == MoonBridge.VIDEO_FORMAT_H264) {
                    message += " [H.264]";
                }
            }

            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }

        finish();
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            if (grabbedInput) {
                inputCaptureProvider.disableCapture();
            }
            else {
                inputCaptureProvider.enableCapture();
            }

            grabbedInput = !grabbedInput;
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(short translatedKey, boolean down) {
        int modifierMask = 0;

        // Mask off the high byte
        translatedKey &= 0xff;

        if (translatedKey == KeyboardTranslator.VK_CONTROL) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (translatedKey == KeyboardTranslator.VK_SHIFT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (translatedKey == KeyboardTranslator.VK_ALT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Check if Ctrl+Shift+Z is pressed
        if (translatedKey == KeyboardTranslator.VK_Z &&
            (modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT))
        {
            if (down) {
                // Now that we've pressed the magic combo
                // we'll wait for one of the keys to come up
                grabComboDown = true;
            }
            else {
                // Toggle the grab if Z comes up
                Handler h = getWindow().getDecorView().getHandler();
                if (h != null) {
                    h.postDelayed(toggleGrab, 250);
                }

                grabComboDown = false;
            }

            return true;
        }
        // Toggle the grab if control or shift comes up
        else if (grabComboDown) {
            Handler h = getWindow().getDecorView().getHandler();
            if (h != null) {
                h.postDelayed(toggleGrab, 250);
            }

            grabComboDown = false;
            return true;
        }

        // Not a special combo
        return false;
    }

    private static byte getModifierState(KeyEvent event) {
        byte modifier = 0;
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return super.onKeyDown(keyCode, event);
        }

        boolean handled = false;

        boolean detectedGamepad = event.getDevice() == null ? false :
                ((event.getDevice().getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                        (event.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
        if (detectedGamepad || (event.getDevice() == null ||
                event.getDevice().getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        )) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        if (!handled) {
            // Try the keyboard handler
            short translated = KeyboardTranslator.translate(event.getKeyCode());
            if (translated == 0) {
                return super.onKeyDown(keyCode, event);
            }

            // Let this method take duplicate key down events
            if (handleSpecialKeys(translated, true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return super.onKeyDown(keyCode, event);
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState());
        }

        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return super.onKeyUp(keyCode, event);
        }

        boolean handled = false;
        boolean detectedGamepad = event.getDevice() == null ? false :
                ((event.getDevice().getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                        (event.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
        if (detectedGamepad || (event.getDevice() == null ||
                event.getDevice().getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC
        )) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        if (!handled) {
            // Try the keyboard handler
            short translated = KeyboardTranslator.translate(event.getKeyCode());
            if (translated == 0) {
                return super.onKeyUp(keyCode, event);
            }

            if (handleSpecialKeys(translated, false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return super.onKeyUp(keyCode, event);
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event));
        }

        return true;
    }

    private TouchContext getTouchContext(int actionIndex)
    {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    @Override
    public void showKeyboard() {
        LimeLog.info("Showing keyboard overlay");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    // Returns true if the event was consumed
    private boolean handleMotionEvent(MotionEvent event) {

        String currentDateTimeString = DateFormat.getDateTimeInstance().format(new Date());
        // Log.i("decoder","Zhaowei: The motion uploading time is " + String.valueOf(currentDateTimeString));

        // Pass through keyboard input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                  event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            // This case is for mice
            if (event.getSource() == InputDevice.SOURCE_MOUSE ||
                    (event.getPointerCount() >= 1 &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE))
            {
                int changedButtons = event.getButtonState() ^ lastButtonState;

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    return false;
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    byte vScrollClicks = (byte) event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    conn.sendMouseScroll(vScrollClicks);
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                // Get relative axis values if we can
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    conn.sendMouseMove((short) inputCaptureProvider.getRelativeAxisX(event),
                            (short) inputCaptureProvider.getRelativeAxisY(event));

                    // We have to also update the position Android thinks the cursor is at
                    // in order to avoid jumping when we stop moving or click.
                    lastMouseX = (int)event.getX();
                    lastMouseY = (int)event.getY();
                }
                else {
                    // First process the history
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        updateMousePosition((int)event.getHistoricalX(i), (int)event.getHistoricalY(i));
                    }

                    // Now process the current values
                    updateMousePosition((int)event.getX(), (int)event.getY());
                }

                lastButtonState = event.getButtonState();
            }
            // This case is for touch-based input devices
            else
            {
                if (virtualController != null &&
                        virtualController.getControllerMode() == VirtualController.ControllerMode.Configuration) {
                    // Ignore presses when the virtual controller is in configuration mode
                    return true;
                }

                int actionIndex = event.getActionIndex();

                int eventX = (int)event.getX(actionIndex);
                int eventY = (int)event.getY(actionIndex);

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    // Three fingers down
                    threeFingerDownTime = SystemClock.uptimeMillis();

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked())
                {
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_DOWN:
                    context.touchDownEvent(eventX, eventY);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if (event.getPointerCount() == 1) {
                        // All fingers up
                        if (SystemClock.uptimeMillis() - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            showKeyboard();
                            return true;
                        }
                    }
                    context.touchUpEvent(eventX, eventY);
                    if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                        // The original secondary touch now becomes primary
                        context.touchDownEvent((int)event.getX(1), (int)event.getY(1));
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    // ACTION_MOVE is special because it always has actionIndex == 0
                    // We'll call the move handlers for all indexes manually



                    // First process the historical events
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                            {
                                aTouchContextMap.touchMoveEvent(
                                        (int)event.getHistoricalX(aTouchContextMap.getActionIndex(), i),
                                        (int)event.getHistoricalY(aTouchContextMap.getActionIndex(), i));
                            }
                        }
                    }

                    // Now process the current values
                    for (TouchContext aTouchContextMap : touchContextMap) {
                        if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                        {
                            aTouchContextMap.touchMoveEvent(
                                    (int)event.getX(aTouchContextMap.getActionIndex()),
                                    (int)event.getY(aTouchContextMap.getActionIndex()));
                        }
                    }
                    break;
                default:
                    return false;
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleMotionEvent(event) || super.onTouchEvent(event);

    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(event) || super.onGenericMotionEvent(event);

    }

    private void updateMousePosition(int eventX, int eventY) {
        // Send a mouse move if we already have a mouse location
        // and the mouse coordinates change
        if (lastMouseX != Integer.MIN_VALUE &&
            lastMouseY != Integer.MIN_VALUE &&
            !(lastMouseX == eventX && lastMouseY == eventY))
        {
            int deltaX = eventX - lastMouseX;
            int deltaY = eventY - lastMouseY;

            // Scale the deltas if the device resolution is different
            // than the stream resolution
            deltaX = (int)Math.round((double)deltaX * (REFERENCE_HORIZ_RES / (double)streamView.getWidth()));
            deltaY = (int)Math.round((double)deltaY * (REFERENCE_VERT_RES / (double)streamView.getHeight()));

            conn.sendMouseMove((short)deltaX, (short)deltaY);
        }

        // Update pointer location for delta calculation next time
        lastMouseX = eventX;
        lastMouseY = eventY;
    }

    @Override
    public boolean onGenericMotion(View v, MotionEvent event) {
        return handleMotionEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return handleMotionEvent(event);
    }

    @Override
    public void stageStarting(String stage) {
        if (spinner != null) {
            spinner.setMessage(getResources().getString(R.string.conn_starting)+" "+stage);
        }
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        if (connecting || connected) {
            connecting = connected = false;
            conn.stop();
        }
    }

    @Override
    public void stageFailed(String stage, long errorCode) {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        // Enable cursor visibility again
        inputCaptureProvider.disableCapture();

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            LimeLog.severe(stage+" failed: "+errorCode);

            // If video initialization failed and the surface is still valid, display extra information for the user
            if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(Game.this, "Video decoder failed to initialize. Your device may not support the selected resolution.", Toast.LENGTH_LONG).show();
                    }
                });
            }

            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.conn_error_msg)+" "+stage, true);
        }
    }

    @Override
    public void connectionTerminated(long errorCode) {
        // Enable cursor visibility again
        inputCaptureProvider.disableCapture();

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            LimeLog.severe("Connection terminated: "+errorCode);
            stopConnection();

            Dialog.displayDialog(this, getResources().getString(R.string.conn_terminated_title),
                    getResources().getString(R.string.conn_terminated_msg), true);
        }
    }

    @Override
    public void connectionStarted() {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        connecting = false;
        connected = true;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Hide the mouse cursor now. Doing it before
                // dismissing the spinner seems to be undone
                // when the spinner gets displayed.
                inputCaptureProvider.enableCapture();
            }
        });

        hideSystemUi(1000);
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!connected && !connecting) {
            connecting = true;

            decoderRenderer.setRenderTarget(holder);

            conn.start(PlatformBinding.getAudioRenderer(), decoderRenderer, Game.this);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Let the decoder know immediately that the surface is gone
        decoderRenderer.prepareForStop();

        if (connected) {
            stopConnection();
        }
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId)
        {
        case EvdevListener.BUTTON_LEFT:
            buttonIndex = MouseButtonPacket.BUTTON_LEFT;
            break;
        case EvdevListener.BUTTON_MIDDLE:
            buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
            break;
        case EvdevListener.BUTTON_RIGHT:
            buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
            break;
        default:
            LimeLog.warning("Unhandled button: "+buttonId);
            return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = KeyboardTranslator.translate(keyCode);
        if (keyMap != 0) {
            if (handleSpecialKeys(keyMap, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState());
            }
            else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState());
            }
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set on 4.4+
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT &&
                 (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set before 4.4+
        else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT &&
                 (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0) {
            hideSystemUi(2000);
        }
    }

    public static int get_service_code(String str, String str2) {
        int i = -1;
        try {
            loop0:
            for (Class declaredFields : Class.forName(str).getDeclaredClasses()) {
                Field[] declaredFields2 = declaredFields.getDeclaredFields();
                int length = declaredFields2.length;
                int i2 = 0;
                while (i2 < length) {
                    Field field = declaredFields2[i2];
                    String name = field.getName();
                    if (name == null || !name.equals("TRANSACTION_" + str2)) {
                        i2++;
                    } else {
                        try {
                            field.setAccessible(true);
                            i = field.getInt(field);
                            break loop0;
                        } catch (IllegalAccessException e) {
                        } catch (IllegalArgumentException e2) {
                        }
                    }
                }
            }
        } catch (ClassNotFoundException e3) {
        }
        return i;
    }

    private String RootCommand(String command, boolean need_res) {

        Process process = null;
        DataOutputStream os = null;
        DataInputStream is = null;
        String res = "";
        BufferedReader bf = null;

        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            bf = new BufferedReader(new InputStreamReader(process.getInputStream()));
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");

            String tmp;

            if (need_res) {
                while ((tmp = bf.readLine()) != null) {
                    res = res + "\n" + tmp;
                }
            }

            os.flush();
            process.waitFor();

        } catch (Exception e) {
            return res;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                process.destroy();
            } catch (Exception e) {
            }
        }
        return res;
    }
}
