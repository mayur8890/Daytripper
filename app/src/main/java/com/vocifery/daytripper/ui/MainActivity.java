package com.vocifery.daytripper.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.neura.sdk.config.NeuraConsts;
import com.neura.sdk.object.AuthenticationRequest;
import com.neura.sdk.object.Permission;
import com.neura.sdk.object.SubscriptionRequest;
import com.neura.sdk.service.NeuraApiClient;
import com.neura.sdk.service.NeuraServices;
import com.neura.sdk.service.SubscriptionRequestCallbacks;
import com.neura.sdk.util.Builder;
import com.neura.sdk.util.NeuraAuthUtil;
import com.neura.sdk.util.NeuraUtil;
import com.vocifery.daytripper.R;
import com.vocifery.daytripper.service.RequestConstants;
import com.vocifery.daytripper.service.ResponderService;
import com.vocifery.daytripper.ui.components.IntroFragment;
import com.vocifery.daytripper.ui.components.Refreshable;
import com.vocifery.daytripper.ui.components.ResultFragment;
import com.vocifery.daytripper.util.NeuraUtils;
import com.vocifery.daytripper.util.ResourceUtils;

import org.alicebot.ab.Chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressLint("InflateParams")
public class MainActivity extends AppCompatActivity implements
        LocationListener,
        Refreshable,
        TextToSpeech.OnInitListener,
        RequestConstants,
        SharedPreferences.OnSharedPreferenceChangeListener,
        Handler.Callback {

    public static final String ACTION_NOTIFY = "com.vocifery.daytripper.NOTIFY";
    public static final String ACTION_GET_CONVERSATION = "com.vocifery.daytripper.CONVERSATION";

    private static final String TAG = "MainActivity";
    private static final String APP_REFERRER = "Daytripper";
    private static final long MEASURE_TIME = 1000 * 60;
    private static final long POLLING_FREQ = 1000 * 20;
    private static final long ONE_MIN = 1000 * 60;
    private static final long TWO_MIN = ONE_MIN * 2;
    private static final long FIVE_MIN = ONE_MIN * 5;
    private static final long TEN_MIN = FIVE_MIN * 2;
    private static final float MIN_LAST_READ_ACCURACY = 1000.0f;
    private static final float MIN_ACCURACY = 50.0f;
    private static final float MIN_DISTANCE = 20.0f;
    private static final int NEURA_AUTHENTICATION_REQUEST_CODE = 0;

    private LocationManager locationManager;
    private Location location;

    private TextToSpeech tts;
    private ProgressBar mainProgressBar;
    private SearchView searchView;
    private Dialog helpDialog;
    private BroadcastReceiver broadcastReceiver;
    private IntroFragment introFragment;
    private ResultFragment resultFragment;
    private NeuraApiClient neuraClient;
    private boolean vociferous = true;

    private NeuraApiClient.ConnectionCallbacks neuraServiceConnectionCallbacks = new NeuraApiClient.ConnectionCallbacks() {
        @Override
        public void onConnected() {
            String accessToken = NeuraUtils.getAccessToken(MainActivity.this);
            String eventName = NeuraUtils.getEventName(MainActivity.this);
            registerNeuraEvent(accessToken, MainActivity.this, eventName);
        }

        @Override
        public void onFailedToConnect(int errorCode) {
            receivedResponse("Error: Failed to connect to Neura's service. Error code: " + NeuraUtil.errorCodeToString(errorCode), true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        createHelpDialog();

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(false);
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setCustomView(R.layout.actionbar_custom);
        mainProgressBar = (ProgressBar) actionBar.getCustomView().findViewById(R.id.main_progress);

        final Context context = this;
        TextView helpText = (TextView) actionBar.getCustomView().findViewById(R.id.help_text);
        helpText.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                WebView webView = (WebView) helpDialog.findViewById(R.id.html_help);
                webView.loadData(ResourceUtils.readTextFromResource(context, R.raw.help), "text/html", null);
                helpDialog.show();
            }
        });

        tts = new TextToSpeech(this, this);
        searchView = (SearchView) findViewById(R.id.search_view);
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        searchView.setSearchableInfo(searchManager
                .getSearchableInfo(getComponentName()));

        if (findViewById(R.id.fragment_container) != null) {
            FragmentManager fm = getSupportFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();

            IntroFragment introFrag = getIntroFragment();
            ft.add(R.id.fragment_container, introFrag);

            ResultFragment resultFrag = getResultFragment();
            ft.add(R.id.fragment_container, resultFrag);

            ft.show(introFrag);
            ft.hide(resultFrag);
            ft.commit();
        }

        initLocationManager();
        location = bestLastKnownLocation(MIN_LAST_READ_ACCURACY, TEN_MIN);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!TextUtils.isEmpty(action) && action.equalsIgnoreCase(ResponderService.RESPONSE_ACTION)) {
                    processMessage(intent);
                }

            }
        };
        startListening();

        SharedPreferences prefs = getApplicationContext().getSharedPreferences(Daytripper.class.getName(), Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);

        String appId = getString(R.string.app_uid_production);
        String appSecret = getString(R.string.app_secret_production);

        Builder builder = new Builder(this);
        builder.addConnectionCallbacks(neuraServiceConnectionCallbacks);
        neuraClient = builder.build();
        neuraClient.setAppUid(appId);
        neuraClient.setAppSecret(appSecret);
    }

    @Override
    public void onLocationChanged(Location updatedLocation) {
        if (location == null || updatedLocation.getAccuracy() < location.getAccuracy()) {
            location = updatedLocation;

            if (location.getAccuracy() < MIN_ACCURACY) {
                locationManager.removeUpdates(this);
            }
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle bundle) {
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.UK);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language is not available.");
                }
            }
            tts.setSpeechRate(1.0f);
        } else {
            Log.e(TAG, "Could not initialize TextToSpeech.");
        }
    }

    @Override
    public void refresh(int page, int count) {
        updateLocation();
        String locationString = null;
        if (location != null) {
            locationString = location.getLatitude() + ", "
                    + location.getLongitude();
        }

        String lastQuery = getLastQuery();
        Log.i(TAG, "refresh - sending query " + lastQuery + " with location "
                + locationString);
        startWork(lastQuery, locationString);
    }

    @Override
    public void receivedResponse(String response, boolean vocalize) {
        try {
            if (vocalize) {
                say(response);
            }
        } finally {
            lockOrientation(false);
        }
    }

    @Override
    public void requestDenied(String reason) {
        say(reason);
    }

    @Override
    public void onSaveInstanceState(Bundle savedState) {
        super.onSaveInstanceState(savedState);
    }

    @Override
    protected void onStart() {
        Log.i(TAG, "onStart()");
        super.onStart();
        neuraClient.connect();
    }

    @Override
    protected void onStop() {
        neuraClient.disconnect();
        super.onStop();
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume()");
        super.onResume();
        requestLocationUpdates(this);
        startListening();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause()");
        super.onPause();
        searchView.clearFocus();
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }

        if (helpDialog.isShowing()) {
            helpDialog.dismiss();
        }
        stopListening();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy()");
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void startProgress() {
        lockOrientation(true);
        mainProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void stopProgress() {
        mainProgressBar.setVisibility(View.INVISIBLE);
    }

    @Override
    public void cancel() {
        lockOrientation(false);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!TextUtils.isEmpty(key) && key.equals(ResponderService.VOICE_FLAG)) {
            vociferous = sharedPreferences.getBoolean(ResponderService.VOICE_FLAG, true);
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NEURA_AUTHENTICATION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                String accessToken = NeuraAuthUtil.extractToken(data);
                NeuraUtils.saveAccessTokenPersistent(MainActivity.this, accessToken);
                Toast.makeText(MainActivity.this, "Authenticate Success!", Toast.LENGTH_SHORT)
                        .show();
                Log.i(TAG, String.format("Successfully logged in with accessToken %s", accessToken));
            } else {
                int errorCode = data.getIntExtra(NeuraConsts.EXTRA_ERROR_CODE, -1);
                Log.e(TAG, String.format("Authentication failed due to %s", NeuraUtil.errorCodeToString(errorCode)));
            }
        }
    }

    private void createHelpDialog() {
        helpDialog = new Dialog(this);
        helpDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        helpDialog.setContentView(R.layout.help_content);

        TextView helpClose = (TextView) helpDialog.findViewById(R.id.help_close);
        helpClose.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                helpDialog.dismiss();
            }
        });
    }

    private void lockOrientation(boolean lock) {
        if (lock) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }
    }

    private void startWork(final String query, final String locationString) {
        startProgress();
        Intent serviceIntent = new Intent(this, ResponderService.class);
        serviceIntent.setAction(ResponderService.USER_ACTION);
        serviceIntent.putExtra(ResponderService.KEY_QUERY, query);
        serviceIntent.putExtra(ResponderService.KEY_lOCATION, locationString);
        startService(serviceIntent);
    }

    private void initLocationManager() {
        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }
    }

    private Location bestLastKnownLocation(float minAccuracy, long maxAge) {
        Location updatedLocation = null;
        if (locationManager == null) {
            return null;
        }

        updatedLocation = locationManager
                .getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        if (updatedLocation != null) {
            float accuracy = updatedLocation.getAccuracy();
            long time = updatedLocation.getTime();
            if (accuracy <= minAccuracy || (System.currentTimeMillis() - time) <= maxAge) {
                return updatedLocation;
            }
        }

        updatedLocation = locationManager
                .getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (updatedLocation != null) {
            float accuracy = updatedLocation.getAccuracy();
            long time = updatedLocation.getTime();
            if (accuracy <= minAccuracy || (System.currentTimeMillis() - time) <= maxAge) {
                return updatedLocation;
            }
        }

        updatedLocation = locationManager
                .getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (updatedLocation != null) {
            float accuracy = updatedLocation.getAccuracy();
            long time = updatedLocation.getTime();
            if (accuracy <= minAccuracy || (System.currentTimeMillis() - time) <= maxAge) {
                return updatedLocation;
            }
        }
        return updatedLocation;
    }

    private void handleIntent(Intent intent) {
        try {
            String intentAction = intent.getAction();
            if (intentAction.equals(Intent.ACTION_SEARCH)) {
                updateLocation();
                String query = intent.getStringExtra(SearchManager.QUERY);
                if (query == null || query.isEmpty()) {
                    Log.i(TAG, "query is null");
                    return;
                }
                query = query.trim();

                if (searchView.getQuery() == null || searchView.getQuery().length() == 0) {
                    searchView.setQuery(query, false);
                }

                String locationString = null;
                if (location != null) {
                    locationString = location.getLatitude() + ", "
                            + location.getLongitude();
                }

                Log.i(TAG, "handleIntent - sending query " + query
                        + " with location " + locationString);
                startWork(query, locationString);
            } else if (intentAction.equals(ResponderService.ROBOT_ACTION)) {
                chatMessage(intent);
            }
        } finally {
            searchView.clearFocus();
        }
    }

    private void say(final String text) {
        if (!vociferous) {
            return;
        }

        try {
            runOnUiThread(new Runnable() {
                @SuppressWarnings("deprecation")
                public void run() {
                    if (tts != null) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                    }
                }
            });
        } catch (Exception e) {
        }
    }

    private String getMessage(int resourceId) {
        return getResources().getString(resourceId);
    }

    private void processMessage(Intent intent) {
        try {
            if (intent == null) {
                String errorMessage = getMessage(R.string.error_message);
                say(errorMessage);
                showContent(errorMessage);
                return;
            }

            if (intent.hasExtra(ResponderService.VOICE_FLAG)) {
                toggleVoice(intent.getStringExtra(ResponderService.VOICE_FLAG),
                        intent.getStringExtra(ResponderService.EXTRA_TEXT_MESSAGE));
            } else if (intent.hasExtra(ResponderService.NEURA_USER_LEFT_WORK)) {
                String userLeftWork = intent.getStringExtra(ResponderService.NEURA_USER_LEFT_WORK);
                handleNeuraEvent(ResponderService.NEURA_USER_LEFT_WORK, userLeftWork, intent);
            } else if (intent.hasExtra(ResponderService.NEURA_USER_ARRIVED_HOME)) {
                String userArrivedHome = intent.getStringExtra(ResponderService.NEURA_USER_ARRIVED_HOME);
                handleNeuraEvent(ResponderService.NEURA_USER_ARRIVED_HOME, userArrivedHome, intent);
            } else if (intent.hasExtra(ResponderService.NEURA_USER_LEFT_HOME)) {
                String userLeftHome = intent.getStringExtra(ResponderService.NEURA_USER_LEFT_HOME);
                handleNeuraEvent(ResponderService.NEURA_USER_LEFT_HOME, userLeftHome, intent);
            } else if (intent.hasExtra(ResponderService.NEURA_USER_ARRIVED_TO_WORK)) {
                String userArrivedToWork = intent.getStringExtra(ResponderService.NEURA_USER_ARRIVED_TO_WORK);
                handleNeuraEvent(ResponderService.NEURA_USER_ARRIVED_TO_WORK, userArrivedToWork, intent);
            } else {
                chatMessage(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
            String errorMessage = getMessage(R.string.system_error_message);
            say(errorMessage);
            showContent(errorMessage);
        } finally {
            stopProgress();
        }
    }

    private void chatMessage(Intent intent) {
        String textMessage = intent.getStringExtra(ResponderService.EXTRA_TEXT_MESSAGE);
        if (!TextUtils.isEmpty(textMessage)) {
            receivedResponse(textMessage, true);
        }

        String url = intent.getStringExtra(ResponderService.EXTRA_URL_MESSAGE);
        String content = intent.getStringExtra(ResponderService.EXTRA_CONTENT_MESSAGE);
        if (!TextUtils.isEmpty(url)) {
            showUrl(url);
        } else if (!TextUtils.isEmpty(content)) {
            showContent(content);
        }
    }

    private void startListening() {
        IntentFilter filter = new IntentFilter(ResponderService.RESPONSE_ACTION);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filter);
    }

    private void stopListening() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
    }

    private void showUrl(String url) {
        if (findViewById(R.id.fragment_container) != null) {
            IntroFragment introFrag = getIntroFragment();
            ResultFragment resultFrag = getResultFragment();
            FragmentManager fm = getSupportFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            ft.hide(introFrag);
            ft.show(resultFrag);
            ft.commit();
            resultFrag.updateWebviewUrl(url);
        }
    }

    private void showContent(String content) {
        if (findViewById(R.id.fragment_container) != null) {
            IntroFragment introFrag = getIntroFragment();
            ResultFragment resultFrag = getResultFragment();
            FragmentManager fm = getSupportFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            ft.hide(introFrag);
            ft.show(resultFrag);
            ft.commit();
            resultFrag.updateWebviewContent(content);
        }
    }

    private void requestLocationUpdates(final LocationListener listener) {
        if (locationManager != null && location != null) {
            if (location.getAccuracy() > MIN_LAST_READ_ACCURACY || location.getTime() < (System.currentTimeMillis() - TWO_MIN)) {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, POLLING_FREQ, MIN_DISTANCE, this);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, POLLING_FREQ, MIN_DISTANCE, this);
                Executors.newScheduledThreadPool(1).schedule(new Runnable() {
                    @Override
                    public void run() {
                        locationManager.removeUpdates(listener);
                    }
                }, MEASURE_TIME, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void updateLocation() {
        Location updatedLocation = bestLastKnownLocation(MIN_LAST_READ_ACCURACY, TEN_MIN);
        if (updatedLocation != null) {
            location = updatedLocation;

            Chat.locationKnown = true;
            Chat.longitude = Double.toString(location.getLongitude());
            Chat.latitude = Double.toString(location.getLatitude());
        }
    }

    private void toggleVoice(String voiceFlag, String response) {
        if (TextUtils.isEmpty(voiceFlag)) {
            Log.w(TAG, "Null voice flag");
            return;
        }

        say(response);
        vociferous = (voiceFlag.equals("on") ? Boolean.TRUE : Boolean.FALSE);

        SharedPreferences prefs = getApplicationContext().getSharedPreferences(Daytripper.class.getName(), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(ResponderService.VOICE_FLAG, vociferous);
        editor.commit();
    }

    private IntroFragment getIntroFragment() {
        if (introFragment == null) {
            introFragment = new IntroFragment();
        }
        return introFragment;
    }

    private ResultFragment getResultFragment() {
        if (resultFragment == null) {
            resultFragment = new ResultFragment();
        }
        return resultFragment;
    }

    private static String getLastQuery() {
        final Daytripper daytripper = (Daytripper) Daytripper.getAppContext();
        return daytripper.getLastQuery();
    }

    private void performNeuraAuthentication() {
        String appId = getString(R.string.app_uid_production);
        String appSecret = getString(R.string.app_secret_production);

        AuthenticationRequest authenticationRequest = new AuthenticationRequest();
        authenticationRequest.setAppId(appId);
        authenticationRequest.setAppSecret(appSecret);

        String[] permissions = getString(R.string.neura_permissions).split(",");
        ArrayList<Permission> permissionsList = Permission.list(permissions);
        authenticationRequest.setPermissions(permissionsList);

        Log.i(TAG, String.format("Neura permissions: %s", Arrays.toString(permissions)));
        boolean neuraInstalled = new NeuraAuthUtil().authenticate(MainActivity.this,
                NEURA_AUTHENTICATION_REQUEST_CODE, authenticationRequest);

        if (!neuraInstalled) {
            NeuraUtil.redirectToGooglePlayNeuraMeDownloadPage(this, APP_REFERRER);
        }
    }

    private void handleNeuraEvent(String eventName, String eventDetails, Intent intent) {
        boolean neuraSupported = NeuraUtil.isNeuraAppSupported(MainActivity.this);
        if (!neuraSupported) {
            receivedResponse("This device cannot support the Neura app", true);
        } else {
            NeuraUtils.saveEventName(MainActivity.this, eventName);
            NeuraUtils.saveEventDetails(MainActivity.this, eventName, eventDetails);
            performNeuraAuthentication();
            chatMessage(intent);
        }
    }

    private void registerNeuraEvent(String accessToken, Context context, String eventName) {
        if (!neuraClient.isConnected()) {
            Toast.makeText(
                    MainActivity.this,
                    "Error: You attempted to register to receive an event without being connected to Neura's service.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Log.i(TAG, String.format("Subscribing to event %s", eventName));
        SubscriptionRequest subscriptionRequest = new SubscriptionRequest.Builder(context)
                .setAccessToken(accessToken)
                .setAction(NeuraConsts.ACTION_SUBSCRIBE)
                .setEventName(eventName)
                .build();

        NeuraServices.SubscriptionsAPI.executeSubscriptionRequest(neuraClient,
                subscriptionRequest, new SubscriptionRequestCallbacks() {
                    @Override
                    public void onSuccess(String eventName, Bundle resultData, String identifier) {
                        Toast.makeText(MainActivity.this,
                                "Success: You subscribed to the event " + eventName,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(String eventName, Bundle resultData, int errorCode) {
                        String text = String.format("Failed subscribing to event %s due to error %s",
                                eventName, NeuraUtil.errorCodeToString(errorCode));
                        Log.e(TAG, text);
                        Toast.makeText(
                                MainActivity.this,
                                text,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}
