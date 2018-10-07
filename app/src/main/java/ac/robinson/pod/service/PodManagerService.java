package ac.robinson.pod.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ac.robinson.pod.BasePodApplication;
import ac.robinson.pod.BuildConfig;
import ac.robinson.pod.listeners.PodResponseDirectoryContentListener;
import ac.robinson.pod.listeners.PodResponseListener;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

public class PodManagerService extends Service {

	private static final String TAG = "PodManagerService";

	public static final String DEFAULT_POD_NAME = "Pod";
	private static final String DEFAULT_POD_NAME_FORMAT = DEFAULT_POD_NAME + "-%s"; // used for generating SSIDs
	public static final String DEFAULT_POD_ROOT = "http://10.10.10.254/data/UsbDisk1/Volume1";

	private static final String POD_ADMIN_PASSWORD = BuildConfig.POD_ADMIN_PASSWORD;
	private static final String POD_NETWORK_PASSWORD = BuildConfig.POD_NETWORK_PASSWORD;

	private WifiManager mWifiManager;

	// for trying to restore the previous state on exit
	private boolean mOriginalWifiStatus = false;
	private int mPodNetworkId = -1;

	private ConnectionOptions mConnectionOptions = null;
	private ConnectionState mConnectionState = ConnectionState.DISCONNECTED;

	private static class ConnectionOptions {
		String mName;
		String mPassword;
		String mPodRootPath;
	}

	private enum ConnectionState {
		DISCONNECTED, CONNECTING, VERIFIED
	}

	// error handling
	private ErrorHandler mConnectionErrorHandler;
	private int mWifiConnectionErrorCount = 0;
	private static final int MAX_CONNECTION_ERRORS = 3;
	private static final int CONNECTION_TIMEOUT = 10000; // milliseconds
	public static final float CONNECTION_DELAY_INCREMENT_MULTIPLIER = 1.3f; // multiply delay by this on every failure
	private static final int MSG_CONNECTION_ERROR = 1;
	private int mWifiConnectionTimeout = CONNECTION_TIMEOUT;
	private ConnectivityManager.NetworkCallback mNetworkCallback; // on API 21 and later we need to bind to WiFi
	// directly

	// service messages and communication
	private boolean mIsBound = false;
	private final Messenger mMessenger;
	private ArrayList<Messenger> mClients = new ArrayList<>();

	public static final int MSG_REGISTER_CLIENT = 1; // service management
	public static final int MSG_UNREGISTER_CLIENT = 2; // service management
	public static final int MSG_CONNECT_POD = 3; // pod management
	public static final int MSG_DISCONNECT_POD = 4; // pod management

	// events sent as messages to local clients
	public static final int EVENT_CONNECTION_STATUS_UPDATE = 5;
	public static final int EVENT_CONNECTION_ROOT_FOLDER_UPDATE = 6;

	public static final String KEY_POD_CONNECTION_STATE = "state";
	public static final String DATA_POD_CONNECTED = "connected";
	public static final String DATA_POD_CONNECTION_FAILURE = "connection_failure";
	public static final String DATA_POD_CONNECTION_DROPPED = "connection_dropped";

	private static final SimpleDateFormat RFC_1123_DATE_TIME_FORMATTER = new SimpleDateFormat(
			"EEE, dd MMM yyyy " + "HH:mm:ss zzz", Locale.US);
	public static final String POD_CONFIGURATION_FILE_NAME = ".podconfig"; // MUST contain the a for file extension
	public static final String POD_CONFIGURATION_FILE_CONFLICT_EXTENSION = ".newpc"; // MUST contain a dot for file
	// extension

	public PodManagerService() {
		// initialise the handler - actual service is initialised in onBind as we can't get a context here
		mMessenger = new Messenger(new IncomingHandler(PodManagerService.this));
		mConnectionErrorHandler = new ErrorHandler(PodManagerService.this);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		if (!mIsBound) {
			mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

			// try to get the original state to restore later
			int wifiState = mWifiManager.getWifiState();
			switch (wifiState) {
				case WifiManager.WIFI_STATE_ENABLED:
				case WifiManager.WIFI_STATE_ENABLING:
					mOriginalWifiStatus = true;
					break;
				case WifiManager.WIFI_STATE_DISABLED:
				case WifiManager.WIFI_STATE_DISABLING:
				case WifiManager.WIFI_STATE_UNKNOWN:
					mOriginalWifiStatus = false;
					break;
				default:
					break;
			}

			// listen for network state changes
			IntentFilter intentFilter = new IntentFilter();
			intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); // WiFi on/off (inaccurate during actual
			// state change)
			intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); // network connection (with name)
			registerReceiver(mBroadcastReceiver, intentFilter);
			Log.d(TAG, "Registering receiver");

			mWifiManager.setWifiEnabled(true);

			mIsBound = true;
		}

		return mMessenger.getBinder();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY; // so we don't get destroyed on rotation
	}

	@Override
	public void onDestroy() {
		// note: catching errors here is purely in case a binding activity crashes before completing setup - if that
		// happens, stack traces end up coming from exceptions here, rather than their actual source - this fixes it
		try {
			unregisterReceiver(mBroadcastReceiver);
		} catch (IllegalArgumentException ignored) {
		}

		if (mWifiManager != null) {
			if (mPodNetworkId >= 0) {
				mWifiManager.disableNetwork(mPodNetworkId);
				mWifiManager.saveConfiguration();
			}
			mWifiManager.setWifiEnabled(mOriginalWifiStatus);
			if (mOriginalWifiStatus) {
				mWifiManager.reconnect();
			}
		}
	}

	// http://stackoverflow.com/a/12816123/1993220
	private static String byteArrayToHex(byte[] bytes) {
		StringBuilder stringBuilder = new StringBuilder(bytes.length * 2);
		char[] hex = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
		for (final byte b : bytes) {
			stringBuilder.append(hex[(b & 0xF0) >> 4]);
			stringBuilder.append(hex[b & 0x0F]);
		}
		return stringBuilder.toString();
	}

	public static String getHotspotNameHash(String podId) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			messageDigest.update(podId.getBytes());
			String hashedString = byteArrayToHex(messageDigest.digest());
			// trim length as for some devices, max SSID length is 20 characters
			return String.format(Locale.US, DEFAULT_POD_NAME_FORMAT, hashedString.substring(0, 16));
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}

	private ConnectionOptions getConnectionOptions(String podId) {
		// convert pod PIN, e.g., 1234 into Pod-03ac674216f3e15c
		ConnectionOptions connectionOptions = new ConnectionOptions();
		connectionOptions.mName = TextUtils.isEmpty(podId) ? String.format(Locale.US, DEFAULT_POD_NAME_FORMAT,
				"Unknown") : getHotspotNameHash(podId);
		connectionOptions.mPassword = POD_NETWORK_PASSWORD;
		connectionOptions.mPodRootPath = DEFAULT_POD_ROOT;
		Log.d(TAG, "Connection options: " + connectionOptions.mName + ", " + connectionOptions.mPodRootPath);
		return connectionOptions;
	}

	// update a WifiConfiguration with the given name (SSID) and password
	// see: https://stackoverflow.com/questions/2140133/
	private void setConfigurationAttributes(@NonNull WifiConfiguration wifiConfiguration, @NonNull String name,
											@NonNull String password) {

		// set up new network - *must* be surrounded by " (see: https://stackoverflow.com/questions/2140133/)
		// TODO: is this Lollipop workaround still relevant?
		//if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
		//	wifiConfiguration.SSID = name; // Android loves to make things difficult
		//} else {
		wifiConfiguration.SSID = "\"" + name + "\"";
		//}
		wifiConfiguration.preSharedKey = "\"" + password + "\"";

		// see http://stackoverflow.com/a/13875379 / http://stackoverflow.com/a/28313511/1993220
		wifiConfiguration.status = WifiConfiguration.Status.CURRENT;
		wifiConfiguration.allowedAuthAlgorithms.clear();
		wifiConfiguration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
		wifiConfiguration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
		wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
		wifiConfiguration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
		wifiConfiguration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
		wifiConfiguration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
		wifiConfiguration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);

		// so that we don't get prompted to disconnect from the network
		try {
			Field field = wifiConfiguration.getClass().getDeclaredField("noInternetAccessExpected");
			Log.d(TAG, "No internet access expected: " + field.getBoolean(wifiConfiguration));
			field.setBoolean(wifiConfiguration, true);
			Log.d(TAG, "New no internet access expected: " + field.getBoolean(wifiConfiguration));
		} catch (Exception e) {
			Log.d(TAG, "Failed to set no internet access expected");
		}
	}

	// connect to a hotspot with the given name (SSID) and password
	private void preparePodConnection(@NonNull ConnectionOptions options) {
		mWifiConnectionErrorCount = 0;
		mConnectionState = ConnectionState.DISCONNECTED;
		sendServiceMessageToAllLocalClients(EVENT_CONNECTION_ROOT_FOLDER_UPDATE, options.mPodRootPath);

		Log.d(TAG, "Attempting Pod connection");
		switch (mWifiManager.getWifiState()) {
			case WifiManager.WIFI_STATE_ENABLED:
				// note: often WIFI_STATE_ENABLED is returned when in actual fact it is WIFI_STATE_ENABLING
				Log.d(TAG, "Completing Pod connection");
				completePodConnection(options);
				break;

			case WifiManager.WIFI_STATE_ENABLING:
				Log.d(TAG, "Waiting for WiFi to be enabled (will connect later)");
				break; // will connect in receiver

			case WifiManager.WIFI_STATE_DISABLING:
				Log.d(TAG, "Waiting for WiFi to be disabled (will re-enable later)");
				break; // will re-enable in receiver

			case WifiManager.WIFI_STATE_DISABLED:
			case WifiManager.WIFI_STATE_UNKNOWN:
			default:
				Log.d(TAG, "Enabling WiFi");
				mWifiManager.setWifiEnabled(true); // will connect in receiver
				break;
		}
	}

	private void completePodConnection(@NonNull ConnectionOptions connectionOptions) {
		Log.d(TAG, "Connecting to WiFi network " + connectionOptions.mName);
		mConnectionState = ConnectionState.CONNECTING;
		WifiConfiguration wifiConfiguration = new WifiConfiguration();
		setConfigurationAttributes(wifiConfiguration, connectionOptions.mName, connectionOptions.mPassword);

		int savedNetworkId = getWifiNetworkId(connectionOptions.mName);
		if (savedNetworkId >= 0 && mWifiConnectionErrorCount < 1) { // only used saved networks on the first attempt
			Log.d(TAG, "Found saved WiFi network id");
			mPodNetworkId = savedNetworkId;
		} else {
			Log.d(TAG, "Adding WiFi network");
			if (savedNetworkId >= 0) {
				mWifiManager.removeNetwork(savedNetworkId);
			}
			mPodNetworkId = mWifiManager.addNetwork(wifiConfiguration);
			mWifiManager.saveConfiguration(); // note: can change network IDs(!)
			mPodNetworkId = getWifiNetworkId(connectionOptions.mName); // TODO: can we rely on this?
			if (mPodNetworkId < 0) {
				Log.d(TAG, "Couldn't add WiFi network - id: " + mPodNetworkId);
				mWifiConnectionErrorCount += 1;
				retryRemoteConnection();
				return;
			}
		}

		// if we're auto-connected to a previous network (unlikely!), continue straight away; if not, reconnect
		WifiInfo currentConnection = mWifiManager.getConnectionInfo();
		if (currentConnection != null && connectionOptions.mName.equals(trimQuotes(currentConnection.getSSID()))) {
			Log.d(TAG, "Continuing with current WiFi connection");
			verifyPodConnection(connectionOptions.mPodRootPath);
			setWifiErrorTimeout();
		} else {
			Log.d(TAG, "Enabling WiFi network");
			// mWifiManager.disconnect(); // TODO: only do this if we're not connected to our own network
			if (!mWifiManager.enableNetwork(mPodNetworkId, true)) { // connect to our network - handle connection in
				// receiver
				Log.d(TAG, "Couldn't enable WiFi network");
				mWifiConnectionErrorCount += 1;
				retryRemoteConnection();
			} else {
				Log.d(TAG, "WiFi network enabled");
				mWifiManager.reconnect();
				setWifiErrorTimeout();
			}
		}
	}

	private void disconnectPod() {
		// TODO: is there anything else to do when we disconnect (temporarily)?
		removeConnectionErrorHandlers();
		mWifiManager.disconnect();
		if (mPodNetworkId >= 0) {
			mWifiManager.disableNetwork(mPodNetworkId);
			mWifiManager.saveConfiguration();
		}
		mConnectionOptions = null;
	}

	private int getWifiNetworkId(@NonNull String name) {
		// find previous networks with the same SSID so we can connect, update or remove
		List<WifiConfiguration> configuredNetworks = mWifiManager.getConfiguredNetworks();
		if (configuredNetworks != null) { // can be null when WiFi is turned off
			for (WifiConfiguration network : configuredNetworks) {
				String ssid = trimQuotes(network.SSID);
				if (!TextUtils.isEmpty(ssid) && (ssid.equals(name))) {
					return network.networkId;
				}
			}
		}
		return -1;
	}

	private String trimQuotes(String name) {
		if (TextUtils.isEmpty(name)) {
			return null;
		}
		Matcher m = Pattern.compile("^\"(.*)\"$").matcher(name);
		if (m.find()) {
			return m.group(1);
		}
		return name;
	}

	private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			// TODO: will if (!isInitialStickyBroadcast()) { help?
			String action = intent.getAction();
			if (action == null) {
				return;
			}
			switch (action) {
				case WifiManager.WIFI_STATE_CHANGED_ACTION:
					Log.d(TAG, "Wifi state changed to: " +
							intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN));
					switch (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
						case WifiManager.WIFI_STATE_DISABLING:
							// wait for disconnection to finish
							Log.d(TAG, "Waiting for WiFi to be disabled (2)");
							break;

						case WifiManager.WIFI_STATE_DISABLED:
							Log.d(TAG, "WiFi disabled (2)");
							if (mConnectionState == ConnectionState.VERIFIED && mConnectionOptions != null) {
								Log.d(TAG, "Connection dropped - retrying (2)");
								sendServiceMessageToAllLocalClients(EVENT_CONNECTION_STATUS_UPDATE,
										DATA_POD_CONNECTION_DROPPED);
							}
							mConnectionErrorHandler.postDelayed(new Runnable() {
								@Override
								public void run() {
									mWifiManager.setWifiEnabled(true);
								}
							}, 1000); // delay as some devices can't cope with fast on/off switching
							break;

						case WifiManager.WIFI_STATE_ENABLING:
							// wait for connection to finish
							Log.d(TAG, "Waiting for WiFi to be enabled (2)");
							break;

						case WifiManager.WIFI_STATE_ENABLED:
							if (mConnectionState != ConnectionState.CONNECTING && mConnectionOptions != null) {
								Log.d(TAG, "Wifi enabled - finishing connection");
								completePodConnection(mConnectionOptions);
							} else {
								Log.d(TAG, "Wifi enabled - no Pod name set or connection setup already in progress");
							}
							break;

						case WifiManager.WIFI_STATE_UNKNOWN:
							// not much else we can do
							Log.d(TAG, "Unknown WiFi state - retrying (2)");
							if (mConnectionOptions != null) {
								retryRemoteConnection();
							}
							break;

						default:
							break;
					}
					break;

				case WifiManager.NETWORK_STATE_CHANGED_ACTION:
					// TODO: we get double reports of many state changes - can we do anything to avoid this?
					if (mConnectionState != ConnectionState.VERIFIED && mConnectionOptions != null) {
						boolean isConnected = false;
						String networkName1 = null;
						NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
						if (networkInfo != null) {
							networkName1 = trimQuotes(networkInfo.getExtraInfo());
							isConnected = networkInfo.isConnected();
						}
						String networkName2 = null;
						WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
						if (wifiInfo != null) {
							networkName2 = trimQuotes(wifiInfo.getSSID());
							// try to speed up connection by actively disconnecting other networks, but doesn't work
							// reliably as we can't always get the correct network name (so sometimes disconnect own)
							// if (!mPodName.equals(networkName2) && !"0x".equals(networkName2)) {
							// mWifiManager.disconnect();
							// }
							//isConnected = true; // not necessarily the case - could be connecting
						}

						Log.d(TAG,
								"State change for network: " + networkName1 + " / " + networkName2 + "; connected: " +
										isConnected);

						if (isConnected) {
							boolean name1Match = mConnectionOptions.mName.equals(networkName1);
							boolean name2Match = mConnectionOptions.mName.equals(networkName2);
							if (!name1Match && !name2Match && TextUtils.equals(networkName1, networkName2)) {
								Log.d(TAG, "Connected to wrong network - cancelling");
								mWifiManager.disconnect();
							} else if (name1Match || name2Match) {
								Log.d(TAG, "Continuing with current WiFi connection (2)");
								mConnectionErrorHandler.postDelayed(new Runnable() {
									@Override
									public void run() {
										// need to delay slightly - access fails immediately after being connected
										if (mConnectionOptions == null) {
											return; // in case we cancelled just as this method was being called
										}
										verifyPodConnection(mConnectionOptions.mPodRootPath);
									}
								}, 250);
							}
						} else {
							Log.d(TAG, "Ignoring - connection already in progress");
						}
					} else {
						if (mConnectionOptions != null) {
							Log.d(TAG, "Connection dropped - retrying");
							sendServiceMessageToAllLocalClients(EVENT_CONNECTION_STATUS_UPDATE,
									DATA_POD_CONNECTION_DROPPED);
							retryRemoteConnection();
						} else {
							Log.d(TAG, "WiFi network state changed - ignoring");
						}
					}
					break;

				default:
					break;
			}
		}
	};

	private static class ErrorHandler extends Handler {
		private final WeakReference<PodManagerService> mPodManagerService;

		ErrorHandler(PodManagerService myClassInstance) {
			mPodManagerService = new WeakReference<>(myClassInstance);
		}

		@Override
		public void handleMessage(Message msg) {
			PodManagerService podManagerService = mPodManagerService.get();
			if (podManagerService != null) {
				switch (msg.what) {
					case MSG_CONNECTION_ERROR:
						podManagerService.mWifiConnectionErrorCount += 1;
						Log.d(TAG, "WiFi connection error, attempt " + podManagerService.mWifiConnectionErrorCount);
						podManagerService.retryRemoteConnection();
						break;

					default:
						break;
				}
			}
		}
	}

	private void setWifiErrorTimeout() {
		Message message = Message.obtain(null, MSG_CONNECTION_ERROR);
		mConnectionErrorHandler.sendMessageDelayed(message, mWifiConnectionTimeout);
		mWifiConnectionTimeout *= CONNECTION_DELAY_INCREMENT_MULTIPLIER;
	}

	private void retryRemoteConnection() {
		removeConnectionErrorHandlers();
		mConnectionState = ConnectionState.DISCONNECTED;

		if (mWifiConnectionErrorCount > MAX_CONNECTION_ERRORS) {
			sendServiceMessageToAllLocalClients(EVENT_CONNECTION_STATUS_UPDATE, DATA_POD_CONNECTION_FAILURE);
			disconnectPod();
			return; // abort on failure
		}

		if (mConnectionOptions == null) {
			return; // in case we cancelled just as this method was being called
		}

		sendServiceMessageToAllLocalClients(EVENT_CONNECTION_STATUS_UPDATE,
				"Couldn't connect – retrying " + "connection...");
		Log.d(TAG, "Retrying remote connection, attempt " + mWifiConnectionErrorCount);

		if (mWifiConnectionErrorCount > 1) {
			int savedNetworkId = getWifiNetworkId(mConnectionOptions.mName);
			if (savedNetworkId >= 0) {
				boolean success = mWifiManager.removeNetwork(savedNetworkId); // sometimes saved networks won't
				// connect - remove
				mWifiManager.saveConfiguration();
				Log.d(TAG, "Removing old saved network: " + success);
			}
		}

		mWifiConnectionErrorCount += 1;
		mWifiManager.setWifiEnabled(!mWifiManager.isWifiEnabled()); // will re-enable automatically in receiver
	}

	private void removeConnectionErrorHandlers() {
		mConnectionErrorHandler.removeMessages(MSG_CONNECTION_ERROR);
	}

	// handler for messages from local clients (e.g., activities that have connected to this service)
	private static class IncomingHandler extends Handler {
		private final WeakReference<PodManagerService> mServiceReference; // so we don't prevent garbage collection

		IncomingHandler(PodManagerService instance) {
			mServiceReference = new WeakReference<>(instance);
		}

		@Override
		public void handleMessage(Message msg) {
			PodManagerService mService = mServiceReference.get();
			if (mService == null) {
				// TODO: anything to do here?
				return;
			}

			switch (msg.what) {
				case MSG_REGISTER_CLIENT:
					mService.mClients.add(msg.replyTo);
					break;

				case MSG_UNREGISTER_CLIENT:
					mService.mClients.remove(msg.replyTo);
					if (mService.mClients.size() <= 0 &&
							msg.arg1 != 1) { // arg1 == 1 means keep alive (activity is rotating)
						mService.stopSelf(); // so we stop the hotspot etc when our final activity is killed
					}
					break;

				case MSG_CONNECT_POD:
					mService.mConnectionOptions = mService.getConnectionOptions(msg.getData()
							.getString(KEY_POD_CONNECTION_STATE));
					mService.preparePodConnection(mService.mConnectionOptions);
					break;

				case MSG_DISCONNECT_POD:
					mService.disconnectPod();
					break;

				default:
					super.handleMessage(msg);
					break;
			}
		}
	}

	// sends a service message (e.g., PodManagerService events) to ALL local clients
	private void sendServiceMessageToAllLocalClients(int type, String data) {
		for (int i = mClients.size() - 1; i >= 0; i--) {
			try {
				Messenger client = mClients.get(i);
				Message message = Message.obtain(null, type);
				message.replyTo = mMessenger;
				if (data != null) {
					Bundle bundle = new Bundle(1);
					bundle.putString(KEY_POD_CONNECTION_STATE, data);
					message.setData(bundle);
				}
				client.send(message);
			} catch (RemoteException e) {
				// e.printStackTrace();
				mClients.remove(i); // client is dead - ok to remove here as we're reversing through the list
			}
		}
	}

	private void verifyPodConnection(final String address) {
		if (mConnectionState != ConnectionState.VERIFIED) {
			Log.d(TAG, "Connected to pod - verifying network: " + address);

			// on SDK 21 and later, networks without internet connectivity are de-prioritised. Fix by binding to WiFi
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (mNetworkCallback == null) {
					mNetworkCallback = new ConnectivityManager.NetworkCallback() {
						@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
						@Override
						public void onAvailable(Network network) {
							if (mConnectionState == ConnectionState.CONNECTING && mConnectionOptions != null) {
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
									ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService
											(Context.CONNECTIVITY_SERVICE);
									if (connectivityManager != null) {
										connectivityManager.bindProcessToNetwork(network);
									}
								} else {
									try {
										ConnectivityManager.setProcessDefaultNetwork(network);
									} catch (IllegalStateException ignored) {
									}
								}
								Log.d(TAG, "Network binding completed - continuing to check connection");
								checkPodDirectoryConnection(address);
							}
						}

						@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
						@Override
						public void onLost(Network network) {
							Log.d(TAG, "Network lost - unbinding");
							ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context
									.CONNECTIVITY_SERVICE);
							if (connectivityManager != null) {
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
									connectivityManager.bindProcessToNetwork(null);
								} else {
									try {
										ConnectivityManager.setProcessDefaultNetwork(null);
									} catch (IllegalStateException ignored) {
									}
								}
								if (mNetworkCallback != null) {
									try {
										// TODO: this doesn't actually work - callback is still bound (see rcheck
										// above)
										connectivityManager.unregisterNetworkCallback(mNetworkCallback);
									} catch (IllegalArgumentException ignored) {
									}
								}
							}
						}
					};
				}

				ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context
						.CONNECTIVITY_SERVICE);
				if (connectivityManager != null) {
					NetworkRequest.Builder request = new NetworkRequest.Builder();
					request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
					connectivityManager.registerNetworkCallback(request.build(), mNetworkCallback);
				}
			} else {
				// otherwise, just go straight to the connection check
				checkPodDirectoryConnection(address);
			}
		}
	}

	private void checkPodDirectoryConnection(String address) {
		Log.d(TAG, "Checking Pod directory exists");
		getFileExists(address, new PodResponseListener() {
			@Override
			public void onResponse(boolean success, String originalRequestPath) {
				if (success) {
					if (mConnectionState != ConnectionState.VERIFIED) {
						//Log.d(TAG, "Headers :" + response.headers());
						mConnectionState = ConnectionState.VERIFIED;
						removeConnectionErrorHandlers();
						sendServiceMessageToAllLocalClients(EVENT_CONNECTION_STATUS_UPDATE, DATA_POD_CONNECTED);
					} else {
						Log.d(TAG, "Connection already verified");
					}
				} else {
					Log.w(TAG, "Failed to verify connection - retrying");
					mWifiConnectionErrorCount += 1;
					retryRemoteConnection();
				}
			}
		});
	}

	// check for file/folder presence with shorter timeout than other requests
	// TODO: check whether timeout actually works!
	public static void getFileExists(final String path, final PodResponseListener responseListener) {
		Request request = new Request.Builder().method("PROPFIND", null).addHeader("Depth", "1").url(path).build();
		BasePodApplication.getCustomTimeoutOkHttpClient(OkHttpHelper.SOCKET_TIMEOUT_SHORT)
				.newCall(request)
				.enqueue(new Callback() {
					@Override
					public void onResponse(@NonNull Call call, @NonNull Response response) {
						// response: either a directory list, file UUID or 404 (not found)
						response.body().close();
						responseListener.onResponse(response.isSuccessful(), path);
					}

					@Override
					public void onFailure(@NonNull Call call, @NonNull IOException e) {
						// TODO: error here means connection failure or invalid request - should we have a connection
						// failed status code?
						Log.w(TAG, "getFileExists request error: " + call.request().headers());
						// e.printStackTrace();
						responseListener.onResponse(false, path);
					}
				});
	}

	public static void createDirectory(final String path, final PodResponseListener responseListener) {
		Request request = new Request.Builder().method("MKCOL", null).url(path).build();
		BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) {
				// response: always empty - need another request to verify the file exists
				// TODO: without a pre-request query, we can't detect failure (e.g., folder already exists)
				response.body().close();
				if (response.isSuccessful()) {
					getFileExists(path, new PodResponseListener() {
						@Override
						public void onResponse(boolean success, String responseString) {
							responseListener.onResponse(success, path);
						}
					});
				} else {
					responseListener.onResponse(false, path);
				}
			}

			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				// TODO: error here means connection failure or invalid request
				// should we have a connection failed status code?
				Log.w(TAG, "createDirectory request error: " + call.request().headers());
				// e.printStackTrace();
				responseListener.onResponse(false, path);
			}
		});
	}

	public static void getDirectoryList(final String path, final PodResponseDirectoryContentListener
			responseListener) {
		Request request = new Request.Builder().method("PROPFIND", null).addHeader("Depth", "1").url(path).build();
		BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
				String responseBody = response.body().string();
				response.body().close();
				if (TextUtils.isEmpty(responseBody)) {
					responseListener.onResponse(false, path, null); // fail - can't have empty response body
					return;
				}

				Document document = Jsoup.parse(responseBody);
				Elements multistatus = document.getElementsByTag("d:multistatus");
				if (multistatus.size() != 1) {
					responseListener.onResponse(false, path, null); // fail - only ever 1 multistatus, so an error
					return;
				}

				Elements responses = multistatus.get(0).children();
				if (responses.size() <= 0) {
					responseListener.onResponse(false, path, null); // fail - this is a file, not a directory
					return;
				}

				final ArrayList<SynchronisedFile> remoteFiles = new ArrayList<>();
				if (responses.size() == 1) {
					responseListener.onResponse(true, path, remoteFiles); // this is an empty directory
					return;
				}

				// example:
				// <D:getcontentlength>11354726</D:getcontentlength>
				// <D:getcontenttype>image/png</D:getcontenttype>
				// <D:getlastmodified ns0:dt="dateTime.rfc1123">Sun, 01 Jan 2012 00:46:14 GMT</D:getlastmodified>
				for (Element r : responses) {
					// decode the URL so we can properly compare filenames (e.g. with spaces, etc)
					String filePath = URLDecoder.decode(r.getElementsByTag("d:href").get(0).html(), "UTF-8");
					Elements properties = r.getElementsByTag("d:propstat")
							.get(0)
							.getElementsByTag("d:prop")
							.get(0)
							.getAllElements();

					int fileSize = 0;
					String fileMimeType = null;
					Date fileCreationDate = null;

					for (Element p : properties) {
						switch (p.tagName()) {
							case "d:getcontentlength":
								try {
									fileSize = Integer.parseInt(p.html());
								} catch (NumberFormatException ignored) { // size will be zero - handled later
								}
								break; // we ignore file sizes currently
							case "d:getcontenttype":
								fileMimeType = p.html();
								break;
							case "d:getlastmodified":
								try {
									// TODO: these are almost always wrong on the new Pod as date isn't kept when off
									fileCreationDate = RFC_1123_DATE_TIME_FORMATTER.parse(p.html());
								} catch (ParseException ignored) { // date will be null - handled later
								}
								break;
							default:
								break;
						}
					}

					if (filePath != null && fileSize > 0 && fileMimeType != null && fileCreationDate != null) {
						switch (fileMimeType) {
							case "httpd/unix-directory":
								break; // don't include directories
							default:
								remoteFiles.add(new SynchronisedFile(filePath, fileSize, fileMimeType,
										fileCreationDate));
								break;
						}
					}
				}

				Collections.sort(remoteFiles, new SynchronisedFile.SynchronisedFileComparator());
				responseListener.onResponse(true, path, remoteFiles);
			}

			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				// TODO: error here means connection failure or invalid request
				// should we have a connection failed status code?
				Log.w(TAG, "getDirectoryList request error for " + path + ": " + call.request().headers());
				// e.printStackTrace();
				responseListener.onResponse(false, path, null);
			}
		});
	}

	public static void downloadFile(final String originalPath, String saveToDirectory, String saveToFileName, boolean
			forceOverwriteConfigFiles, final PodResponseListener responseListener) {

		final File localFile = new File(saveToDirectory, saveToFileName);
		final String path;
		String saveToFileNameFinal;
		if (localFile.exists()) {
			if (!localFile.getName().endsWith(POD_CONFIGURATION_FILE_NAME)) {
				Log.w(TAG, "Download request ignored - local file already exists");
				responseListener.onResponse(false, originalPath);
				return;
			} else {
				if (forceOverwriteConfigFiles) {
					Log.w(TAG, "Downloading duplicate local configuration as " + POD_CONFIGURATION_FILE_NAME +
							POD_CONFIGURATION_FILE_CONFLICT_EXTENSION);
					path = originalPath + POD_CONFIGURATION_FILE_CONFLICT_EXTENSION;
					saveToFileNameFinal = saveToFileName + POD_CONFIGURATION_FILE_CONFLICT_EXTENSION;
				} else {
					Log.w(TAG, "Config download request ignored - local file already exists and force download is " +
							"false");
					responseListener.onResponse(false, originalPath);
					return;
				}
			}
		} else {
			path = originalPath;
			saveToFileNameFinal = saveToFileName;
		}
		final File saveToLocation = new File(saveToDirectory, saveToFileNameFinal);

		Request request = new Request.Builder().get().url(originalPath).build();
		BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
				boolean success = response.isSuccessful();
				if (success) {
					// see: http://stackoverflow.com/a/29012988/1993220
					BufferedSink sink = Okio.buffer(Okio.sink(saveToLocation));
					sink.writeAll(response.body().source());
					sink.close();
				}
				responseListener.onResponse(success, path);
			}

			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				// TODO: error here means connection failure or invalid request
				// should we have a connection failed status code?
				Log.w(TAG, "downloadFile request error for " + originalPath + ": " + call.request().headers());
				// e.printStackTrace();
				responseListener.onResponse(false, path);
			}
		});
	}

	// note: this always overwrites remote files without warning - this is intentional, to allow updating the
	// configuration files without having to delete them first, but could be improved
	public static void uploadFile(final String path, SynchronisedFile localFile, final PodResponseListener
			responseListener) {
		// need to format path string to add file date
		// http://10.10.10.254/upload.csp?uploadpath=[destination]&file=[callback identifier]
		int separator = ordinalIndexOf(path, "/", 2); // first slash after http://
		String requestUrl = path.substring(0, separator + 1) + "upload.csp?uploadpath=" + path.substring(separator) +
				"&file=newpoduploadfile";
		Log.d(TAG, "Uploading " + localFile.mName + " (" + localFile.mMimeType + ") to " + requestUrl);

		RequestBody formBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("file", localFile.mName, RequestBody.create(MediaType.parse(localFile.mMimeType), new
						File(localFile.mPath)))
				.build();
		Request request = new Request.Builder().post(formBody).url(requestUrl).build();
		BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
				String responseBody = response.body().string();
				response.body().close();
				if (response.isSuccessful() && !TextUtils.isEmpty(responseBody)) {
					if (responseBody.contains(".uploadComplete(\"newpoduploadfile\",0)")) {
						responseListener.onResponse(true, path);
						return;
					}
				}
				responseListener.onResponse(false, path);
			}

			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				// TODO: error here means connection failure or invalid request
				// should we have a connection failed status code?
				Log.w(TAG, "uploadFile request error for " + path + ": " + call.request().headers());
				// e.printStackTrace();
				responseListener.onResponse(false, path);
			}
		});
	}

	public static void deleteRemoteFile(final String path, final PodResponseListener responseListener) {
		Request request = new Request.Builder().method("DELETE", null).url(path).build();
		BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) {
				// response: always empty - need another request to verify the file has been deleted
				response.body().close();
				if (response.isSuccessful()) {
					getFileExists(path, new PodResponseListener() {
						@Override
						public void onResponse(boolean success, String responseString) {
							responseListener.onResponse(!success, path); // note: inverted
						}
					});
				} else {
					responseListener.onResponse(false, path);
				}
			}

			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				// TODO: error here means connection failure or invalid request
				// should we have a connection failed status code?
				Log.w(TAG, "deleteFile request error for " + path + ": " + call.request().headers());
				// e.printStackTrace();
				responseListener.onResponse(false, path);
			}
		});
	}

	public static void changeNetworkName(int newPodId, final PodResponseListener responseListener) {
		final String newNetworkName = getHotspotNameHash(String.valueOf(newPodId));
		Log.d(TAG, "Changing pod to " + newNetworkName);
		String requestUrl = "http://10.10.10.254/protocol.csp?function=set";
		RequestBody formBody = new FormBody.Builder().add("fname", "security")
				.add("opt", "pwdchk")
				.add("name", "admin")
				.add("pwd1", POD_ADMIN_PASSWORD)
				.build();
		Request request = new Request.Builder().post(formBody).url(requestUrl).build();

		BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
				String responseBody = response.body().string();
				response.body().close();

				if (response.isSuccessful() && !TextUtils.isEmpty(responseBody) &&
						responseBody.contains("<errno>0</errno>")) {
					Log.d(TAG, "Login successful; updating network...");
					String requestUrl = "http://10.10.10.254/protocol.csp?fname=net&opt=wifi_ap&encode=1&function=set";
					RequestBody formBody = new FormBody.Builder().add("HTBSSCoexistence", "0")
							.add("mode", "4")
							.add("channel", "0")
							.add("security", "3")
							.add("hide_ssid", "0")
							.add("SSID", newNetworkName)
							.add("passwd", POD_NETWORK_PASSWORD)
							.build();
					Request request = new Request.Builder().post(formBody).url(requestUrl).build();

					BasePodApplication.getOkHttpClient().newCall(request).enqueue(new Callback() {
						@Override
						public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
							String responseBody = response.body().string();
							response.body().close();

							if (response.isSuccessful() && !TextUtils.isEmpty(responseBody) &&
									responseBody.contains("<SSID>" + newNetworkName + "</SSID>")) {
								Log.d(TAG, "Update successful; restarting device...");
								String requestUrl =
										"http://10.10.10.254/protocol" + "" + "" + "" + "" + "" + "" + "" + "" + "" +
												".csp?fname=net&opt=wifi_active&function=set&active=wifi_ap";
								Request request = new Request.Builder().get().url(requestUrl).build();
								BasePodApplication.getCustomTimeoutOkHttpClient(OkHttpHelper.SOCKET_TIMEOUT_SHORT)
										.newCall(request)
										.enqueue(new Callback() {
											@Override
											public void onResponse(@NonNull Call call, @NonNull Response response) {
												Log.d(TAG, "Device restarted (response success)");
												responseListener.onResponse(true, newNetworkName);
											}

											@Override
											public void onFailure(@NonNull Call call, @NonNull IOException e) {
												Log.d(TAG, "Device restarted (response timeout)");
												responseListener.onResponse(true, newNetworkName);
											}
										});
							}
						}

						@Override
						public void onFailure(@NonNull Call call, @NonNull IOException e) {
							// TODO: error here means connection failure or invalid request - should we have a status
							// code?
							Log.w(TAG, "changeNetworkName request error: " + call.request().headers());
							// e.printStackTrace();
							responseListener.onResponse(false, newNetworkName);
						}
					});
				}
			}

			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				// TODO: error here means connection failure or invalid request
				// should we have a connection failed status code?
				Log.w(TAG, "changeNetworkName request error: " + call.request().headers());
				// e.printStackTrace();
				responseListener.onResponse(false, newNetworkName);
			}
		});
	}

	public static String getMimeType(String filename) {
		String type = null;
		// MimeTypeMap.getFileExtensionFromUrl(url); //doesn't work with spaces
		String extension = getFileExtension(filename);
		if (extension != null) {
			type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
		}
		if (type == null) {
			type = "text/plain"; // default file type if we don't know via extension
		}
		return type;
	}

	public static int ordinalIndexOf(String s, String search, int index) {
		int pos = s.indexOf(search);
		while (index-- > 0 && pos != -1) {
			pos = s.indexOf(search, pos + 1);
		}
		return pos;
	}

	public static String getFileExtension(String filename) {
		int dotIndex = filename.lastIndexOf('.');
		if (dotIndex >= 0) {
			return filename.substring(dotIndex + 1);
		}
		return null;
	}
}
