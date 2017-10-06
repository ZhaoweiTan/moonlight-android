package com.limelight;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground;
    private LocationManager mLocationManager = null;
    private LocationListener mLocationListeners;
    private AlertDialog.Builder alertDialogBuilder;
    private Location mLocation;

    private Button band_button;
    final Context context = this;

    private static final String TAG = "PCView";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder) binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Reinitialize views just in case orientation changed
        initializeViews();
    }

    private class LocationListener implements android.location.LocationListener {
        Location mLastLocation;

        public LocationListener(String provider) {
            Log.e(TAG, "LocationListener " + provider);
            mLastLocation = new Location(provider);
        }

        @Override
        public void onLocationChanged(Location location) {

            mLocation.set(location);
//            Log.i(TAG, "onLocationChanged: " + Double.toString(mLocation.getLatitude()));
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.e(TAG, "onProviderDisabled: " + provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.e(TAG, "onProviderEnabled: " + provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
//            Log.e(TAG, "onStatusChanged: " + provider);
        }
    }

    private final static int APP_LIST_ID = 1;
    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;


    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Setup the list view
        ImageButton settingsButton = (ImageButton) findViewById(R.id.settingsButton);
        ImageButton addComputerButton = (ImageButton) findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = (ImageButton) findViewById(R.id.helpButton);
        band_button = (Button)findViewById(R.id.button_band);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });
        band_button.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                adaptFreqBand(mLocation);
            }
        });

        getFragmentManager().beginTransaction()
                .replace(R.id.pcFragmentContainer, new AdapterFragment())
                .commitAllowingStateLoss();

        noPcFoundLayout = (RelativeLayout) findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        } else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLocation = new Location("");

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this,
                PreferenceConfiguration.readPreferences(this).listMode,
                PreferenceConfiguration.readPreferences(this).smallIconMode);

        // Create location listner for the LTE freq-band adaptation
        try {

            int permissionCheck = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            if(permissionCheck!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 0 );

            mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            mLocationListeners = new LocationListener(LocationManager.GPS_PROVIDER);
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0,
                    0,
                    mLocationListeners
            );
        } catch (java.lang.SecurityException ex) {
            Log.i(TAG, "fail to request location update, ignore", ex);
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "network provider does not exist, " + ex.getMessage());
        }

        initializeViews();
    }


    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);

        // Inflate the context menu
        if (computer.details.reachability == ComputerDetails.Reachability.OFFLINE ||
                computer.details.reachability == ComputerDetails.Reachability.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, DELETE_ID, 2, getResources().getString(R.string.pcview_menu_delete_pc));
        } else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            menu.add(Menu.NONE, DELETE_ID, 2, getResources().getString(R.string.pcview_menu_delete_pc));
        } else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            menu.add(Menu.NONE, APP_LIST_ID, 3, getResources().getString(R.string.pcview_menu_app_list));

            // FIXME: We used to be able to unpair here but it's been broken since GFE 2.1.x, so I've replaced
            // it with delete which actually work
            menu.add(Menu.NONE, DELETE_ID, 4, getResources().getString(R.string.pcview_menu_delete_pc));
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (computer.runningGameId != 0) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_ingame), Toast.LENGTH_LONG).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    InetAddress addr;
                    if (computer.reachability == ComputerDetails.Reachability.LOCAL) {
                        addr = computer.localIp;
                    } else if (computer.reachability == ComputerDetails.Reachability.REMOTE) {
                        addr = computer.remoteIp;
                    } else {
                        LimeLog.warning("Unknown reachability - using local IP");
                        addr = computer.localIp;
                    }

                    httpConn = new NvHTTP(addr,
                            managerBinder.getUniqueId(),
                            PlatformBinding.getDeviceName(),
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    } else {
                        final String pinStr = PairingManager.generatePinString();

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_pairing_msg) + " " + pinStr, false);

                        PairingManager.PairState pairState = httpConn.pair(httpConn.getServerInfo(), pinStr);
                        if (pairState == PairingManager.PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        } else if (pairState == PairingManager.PairState.FAILED) {
                            message = getResources().getString(R.string.pair_fail);
                        } else if (pairState == PairingManager.PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        } else if (pairState == PairingManager.PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        } else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (Exception e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer);
                        } else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.wol_waking_pc), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    InetAddress addr;
                    if (computer.reachability == ComputerDetails.Reachability.LOCAL) {
                        addr = computer.localIp;
                    } else if (computer.reachability == ComputerDetails.Reachability.REMOTE) {
                        addr = computer.remoteIp;
                    } else {
                        LimeLog.warning("Unknown reachability - using local IP");
                        addr = computer.localIp;
                    }

                    httpConn = new NvHTTP(addr,
                            managerBinder.getUniqueId(),
                            PlatformBinding.getDeviceName(),
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        } else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    } else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (Exception e) {
                    message = e.getMessage();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid.toString());
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }
                managerBinder.removeComputer(computer.details.name);
                removeComputer(computer.details);
                return true;

            case APP_LIST_ID:
                doAppList(computer.details);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this,
                                ServerHelper.getCurrentAddressFromComputer(computer.details),
                                new NvApp("app", 0), managerBinder, null);
                    }
                }, null);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    private void removeComputer(ComputerDetails details) {
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableShortcut(details.uuid.toString(),
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }

    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        // Add a launcher shortcut for this PC
        if (details.pairState == PairState.PAIRED) {
            shortcutHelper.createAppViewShortcut(details.uuid.toString(), details, false);
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        } else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(this).listMode ?
                R.layout.list_view : (PreferenceConfiguration.readPreferences(this).smallIconMode ?
                R.layout.pc_grid_view_small : R.layout.pc_grid_view);
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.reachability == ComputerDetails.Reachability.UNKNOWN ||
                        computer.details.reachability == ComputerDetails.Reachability.OFFLINE) {
                    // Open the context menu if a PC is offline or refreshing
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    doPair(computer.details);
                } else {
                    doAppList(computer.details);
                }
            }
        });
        registerForContextMenu(listView);
    }

    public class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }

    private float round_2(float value){
        return Math.round(value*100)/100;
    }



    private void adaptFreqBand(Location location) {



        // int service_code = get_service_code("com.android.internal.telephony.ITelephony", "getPreferredNetworkType");

        alertDialogBuilder = new AlertDialog.Builder(context);

        // set title
        alertDialogBuilder.setTitle("Boost your game");
        // set dialog message
        alertDialogBuilder
                .setMessage("Click yes to exit!")
                .setCancelable(false)
                .setPositiveButton("Boost now!", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        RootCommand("am broadcast -a \"android.provider.Telephony.SECRET_CODE\" -d \"android_secret_code://2263\" ",false);
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // if this button is clicked, just close
                        // the dialog box and do nothing
                        dialog.cancel();
                    }
                });


        Map<Float, Map<String, String>> freq_list = getBandFromKPIMap(location);
        String information = "We have helped you find the following LTE bands:\n\n";
//        information += "         BW Load Radio Delay Error\n";

        Iterator it = freq_list.entrySet().iterator();
        //TODO: Algorithm to determine the best frequency band

        //Approach 1: List the first one
        Map.Entry pair = (Map.Entry)it.next();
        int band_indicator = Math.round((float)pair.getKey());
        Map<String, Float> values = (Map<String, Float>) pair.getValue();
        information += "LTE Band "+ Integer.toString(band_indicator)+": \n";
            information += "  - Bandwidth: " + values.get("DL_Bandwidth") + "MHz \n";
            information += "  - Network load: " + values.get("Cell load") + "\n";
            information += "  - Signal strength: " + values.get("RSRQ") + "dB \n";
            information += "  - Delay: " + values.get("UL_Delay") + "ms \n";
            information += "  - Error rate: " + values.get("Symbol Error Rate") + "\n";
        information += "\n";


        //Approach 2: List all bands
//        while (it.hasNext()) {
//            Map.Entry pair = (Map.Entry)it.next();
//
//            int band_indicator = Math.round((float)pair.getKey());
//            Map<String, Float> values = (Map<String, Float>) pair.getValue();
//
//            information += "  - Band "+ Integer.toString(band_indicator)+": ";
//            information += values.get("DL_Bandwidth") + "MHz ";
//            information += values.get("Cell load") + " ";
//            information += values.get("RSRQ") + "dB ";
//            information += values.get("UL_Delay") + "ms ";
//            information += values.get("Symbol Error Rate") + " ";
//            information += "\n";
//            it.remove(); // avoids a ConcurrentModificationException
//        }
        information += "Please choose the above band ONLY in \"LTE Band Preference\", and click \"Apply band configuration\"";

        // (deprecated) use secret code to change the frequency band
        // RootCommand("service call phone " + Integer.toString(service_code) + " i32 " + Integer.toString(10), false);

        // TODO: do sth with max_freq -- integrate with MI info and AT command

        AlertDialog alert = alertDialogBuilder.create();
        alert.setMessage(information);
        alert.show();


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

    private Map<Float, Map<String, String>> getBandFromKPIMap(Location location) {

        try{
            double longitude = location.getLongitude();
            double latitude = location.getLatitude();


            Log.i(TAG, "Logitude is " + String.valueOf(longitude) + "; latitude is " + String.valueOf(latitude));

            TelephonyManager tel = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            String networkOperator = tel.getNetworkOperator();


            // Substitute the default with the one using our own GPS later
            String[] urls = new String[2];
            urls[0] = "http://knowledge-map.xyz/kpi_log/frequency_band/?Lat=" + String.valueOf(latitude) + "&Lng=" + String.valueOf(longitude);
            urls[1] = networkOperator;

            // Note that if there're no records near the location, could return empty list
            Map<Float, Map<String, String>> fband_list = new HashMap<Float, Map<String, String>>(); // Band indicator -> bandwidth
            try {
                fband_list = new GetKPITask().execute(urls).get();

            } catch (Exception e) {
                Log.e(TAG, e.toString());

            }
            if (fband_list != null) {
            }

            return fband_list;

        }catch(Exception e){

            Log.e(TAG, e.toString());

            return null;

        }



    }

    class GetKPITask extends AsyncTask<String, Void, Map<Float, Map<String, String>> >
    {


        protected void onPreExecute() {
            //display progress dialog.

        }
        protected Map<Float, Map<String, String>> doInBackground(String... urls) {

            Map<Float, Map<String, String>> band_info = new HashMap<Float, Map<String, String>>(); // Band indicator -> KPIs

            try {
                URL KPIMap = new URL(urls[0]);
                HttpURLConnection urlConnection = (HttpURLConnection) KPIMap.openConnection();


                int code = urlConnection.getResponseCode();

                Log.i(TAG, String.valueOf(code));

                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader((urlConnection.getInputStream())));
                    String inputLine;
                    String html = "";


                    while ((inputLine = br.readLine()) != null)
                        html = html + inputLine;
                    Log.i(TAG, html);
                    JSONObject fband = new JSONObject(html);
                    if(!fband.has(urls[1]))
                        return null;
                    JSONArray fband_carrier = fband.getJSONArray(urls[1]);
                    for (int i = 0; i < fband_carrier.length(); i++) {

                        Map<String, String> kpis = new HashMap<String, String>();
                        kpis.put("DL_Bandwidth", fband_carrier.getJSONObject(i).getString("DL_Bandwidth"));


                        kpis.put("RSRQ", fband_carrier.getJSONObject(i).getString("RSRQ"));
                        kpis.put("Cell load", fband_carrier.getJSONObject(i).getString("Cell load"));
                        kpis.put("Symbol Error Rate", fband_carrier.getJSONObject(i).getString("Symbol Error Rate"));
                        JSONObject ul_latency_breakdown = fband_carrier.getJSONObject(i).getJSONObject("ul_latency_breakdown");
                            float total_latency = 0;
                            total_latency += Float.parseFloat(ul_latency_breakdown.getString("trans_delay"));
                            total_latency += Float.parseFloat(ul_latency_breakdown.getString("proc_delay"));
                            total_latency += Float.parseFloat(ul_latency_breakdown.getString("wait_delay"));
                        kpis.put("UL_Delay", String.valueOf(total_latency));

                        band_info.put(Float.parseFloat(fband_carrier.getJSONObject(i).getString("Band indicator")), kpis);
                    }

                }

            } catch (MalformedURLException e) {
                Log.e(TAG, "MalformedURLException!!!");
                Log.e(TAG, e.toString());
            } catch (IOException e) {
                Log.e(TAG, "IOException!!!",e);
            } catch (JSONException e) {
                Log.e(TAG, "JSONException!!!",e);
            } catch (Exception e) {
                Log.e(TAG, "Exception!!!",e);
            }

            return band_info;
        }

    }

    private List<Integer> getFreqBand(String... urls){
        List<Integer> fband_avail = new ArrayList<Integer>();

        try {
                URL KPIMap = new URL(urls[0]);
                HttpURLConnection urlConnection = (HttpURLConnection) KPIMap.openConnection();


                int code = urlConnection.getResponseCode();

                Log.i(TAG, String.valueOf(code));

                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader((urlConnection.getInputStream())));
                    String inputLine;
                    String html = "";


                    while ((inputLine = br.readLine()) != null)
                        html = html + inputLine;
                    Log.i(TAG, html);
                    JSONObject fband = new JSONObject(html);
                    JSONArray fband_carrier = fband.getJSONArray(urls[1]);
                    for (int i = 0; i < fband_carrier.length(); i++) {
                        Log.i(TAG, fband_carrier.getJSONObject(i).getString(urls[2]));
                        fband_avail.add(Integer.parseInt(fband_carrier.getJSONObject(i).getString(urls[2])));
                    }

                }

        } catch (MalformedURLException e) {
            Log.e(TAG, "MalformedURLException!!!");
            Log.e(TAG, e.toString());
        } catch (IOException e) {
            Log.e(TAG, "IOException!!!");
            Log.e(TAG, e.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSONException!!!");
            Log.e(TAG, e.toString());
        } catch (Exception e) {
            Log.e(TAG, "Exception!!!",e);
//            Log.e(TAG, e.toString());
        }

        return fband_avail;

    }


}
