package ac.robinson.pod;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.core.ImagePipeline;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

import ac.robinson.pod.browsers.ContactsActivity;
import ac.robinson.pod.browsers.GalleryActivity;
import ac.robinson.pod.browsers.SMSActivity;
import ac.robinson.pod.listeners.FileProcessedResponseListener;
import ac.robinson.pod.listeners.PodResponseDirectoryContentListener;
import ac.robinson.pod.listeners.PodResponseListener;
import ac.robinson.pod.service.PodManagerService;
import ac.robinson.pod.service.SynchronisedFile;

public class MainActivity extends BasePodActivity {

	private static final String TAG = "MainActivity";

	private static final int INITIALISE_POD = 100;
	private static final int EDIT_SETTINGS = 101;
	private static final int BROWSE_MEDIA = 102;

	private static final int PERMISSION_COARSE_LOCATION = 109;

	// note: used to use a dot file for this to hide it from the user, but there's no real need
	private static final Uri BACKGROUND_IMAGE = Uri.parse(
			PodManagerService.DEFAULT_POD_ROOT + "/" + PodManagerService.DEFAULT_POD_NAME +
					"/background.png"); // TODO: build path properly

	private static class AppLauncher {
		Class<? extends BaseBrowserActivity> mClass;
		int mIcon;
		int mTitle;
		String mStorageLocation;
		boolean mShouldSynchroniseLocalData;
		boolean mLocalDataSynchronised;
		boolean mVisitorDataSynchronised;
		boolean mForceSyncConfigFiles;

		AppLauncher(Class<? extends BaseBrowserActivity> cls, int icon, int title, String storageLocation, boolean
				forceUploadConfigFiles) {
			mClass = cls;
			mIcon = icon;
			mTitle = title;
			mStorageLocation = storageLocation;

			mLocalDataSynchronised = false;
			mVisitorDataSynchronised = false;
			mForceSyncConfigFiles = forceUploadConfigFiles;
		}
	}

	private static ArrayList<AppLauncher> sApps = new ArrayList<>();

	private LinearLayout mPodConnectionView;
	private EditText mPodConnectionPin;
	private LinearLayout mPodSearchIndicator;
	private RelativeLayout mPodSyncIndicator;
	private ProgressBar mPodSyncProgressCounter;
	private SimpleDraweeView mChooseAppBackgroundImage;
	private GridView mChooseAppView;
	private RelativeLayout mPodOwnSyncCompleteIndicator;

	private int mOwnPod = -1;
	private int mVisitorPod = -1;
	private String mConnectionRootFolder;
	private long mLastOwnPodSyncTime;
	private boolean mIsVisible;
	private boolean mOwnPodDetailsShown;

	private enum ConnectionState {
		DISCONNECTED, // not connected or connecting to a Pod - waiting for PIN entry (doesn't apply to own Pod)
		SEARCHING, // trying to find a Pod with the entered (visitor) or saved (own) PIN
		CONNECTED // connected to a Pod (either own or visitor)
	}

	private enum SyncState {
		INACTIVE, SYNCHRONISING, COMPLETE
	}

	private ConnectionState mConnectionState = ConnectionState.DISCONNECTED;
	private SyncState mSyncState = SyncState.INACTIVE;


	// add new modes here where desired
	static {
		sApps.add(new AppLauncher(GalleryActivity.class, R.drawable.ic_photos, R.string.title_gallery_browser,
				GalleryActivity.STORAGE_DIRECTORY_NAME, false));
		sApps.add(new AppLauncher(ContactsActivity.class, R.drawable.ic_contacts, R.string.title_contacts_browser,
				ContactsActivity.STORAGE_DIRECTORY_NAME, true));
		sApps.add(new AppLauncher(SMSActivity.class, R.drawable.ic_messenger, R.string.title_sms_browser, SMSActivity
				.STORAGE_DIRECTORY_NAME, true));
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
		mOwnPod = SettingsActivity.getOwnPodPin(MainActivity.this);
		if (mOwnPod < 0) { // if no pin set and this is the first launch, go through setup
			if (SettingsActivity.getVisitorOnlyMode(MainActivity.this)) { // visitor only = no setup
				SharedPreferences.Editor editor = preferences.edit();
				editor.putBoolean(getString(R.string.key_completed_initial_setup), true);
				editor.apply();
			} else if (!preferences.getBoolean(getString(R.string.key_completed_initial_setup), false)) {
				startActivityForResult(new Intent(MainActivity.this, WelcomeActivity.class), INITIALISE_POD);
			}
		}

		if (preferences.getBoolean(getString(R.string.key_completed_initial_setup), false)) {
			// need location permission to scan for Pod on more recent devices (to get network name)
			checkLocationPermission();
		}

		mPodConnectionView = findViewById(R.id.pod_connection_view);
		mPodConnectionPin = findViewById(R.id.pod_connection_pin);
		mPodConnectionPin.setOnEditorActionListener(mVisitorPinListener);
		mPodConnectionPin.addTextChangedListener(mVisitorPinWatcher);
		mPodSearchIndicator = findViewById(R.id.pod_search_indicator);
		mPodSyncIndicator = findViewById(R.id.pod_sync_indicator);
		mPodSyncProgressCounter = findViewById(R.id.sync_progress_count);
		mChooseAppBackgroundImage = findViewById(R.id.choose_app_background_image);
		mChooseAppView = findViewById(R.id.choose_app_grid);
		mPodOwnSyncCompleteIndicator = findViewById(R.id.pod_own_sync_complete_indicator);

		if (savedInstanceState != null) {
			mConnectionState = (ConnectionState) savedInstanceState.getSerializable("mConnectionState");
			mSyncState = (SyncState) savedInstanceState.getSerializable("mSyncState");
			mConnectionRootFolder = savedInstanceState.getString("mConnectionRootFolder");
			mLastOwnPodSyncTime = savedInstanceState.getLong("mLastOwnPodSyncTime");
			mVisitorPod = savedInstanceState.getInt("mVisitorPod");
			mOwnPodDetailsShown = savedInstanceState.getBoolean("mOwnPodDetailsShown");
		}
		if (mConnectionState == ConnectionState.DISCONNECTED) {
			mSyncState = SyncState.INACTIVE;
			if (mOwnPod >= 0) {
				Log.d(TAG, "Initialising - requesting connection to own Pod");
				mConnectionState = ConnectionState.SEARCHING;
				sendServiceMessage(PodManagerService.MSG_CONNECT_POD, String.valueOf(mOwnPod));
			}
		}

		configureUI(mConnectionState, mSyncState);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putSerializable("mConnectionState", mConnectionState);
		outState.putSerializable("mSyncState", mSyncState);
		outState.putString("mConnectionRootFolder", mConnectionRootFolder);
		outState.putLong("mLastOwnPodSyncTime", mLastOwnPodSyncTime);
		outState.putInt("mVisitorPod", mVisitorPod);
		outState.putBoolean("mOwnPodDetailsShown", mOwnPodDetailsShown);
		super.onSaveInstanceState(outState);
	}

	@Override
	protected void onPause() {
		super.onPause();
		mIsVisible = false;
	}

	@Override
	protected void onResume() {
		super.onResume();
		mIsVisible = true;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (isFinishing()) { // don't delete when rotating
			removeVisitorData();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (mConnectionState == ConnectionState.CONNECTED) {
			if (mOwnPod >= 0) {
				getMenuInflater().inflate(R.menu.menu_main, menu); // connected to own pod
			}
		} else {
			// visitors do not get a menu; own pods do (for switching between own / visitor)
			getMenuInflater().inflate(mOwnPod >= 0 ? R.menu.menu_main : R.menu.menu_initialise, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_connect_pod:
				if (mConnectionState == ConnectionState.DISCONNECTED && mOwnPod >= 0) {
					Log.d(TAG, "Re-initialising - requesting connection to own Pod");
					mSyncState = SyncState.INACTIVE;
					mConnectionState = ConnectionState.SEARCHING;
					sendServiceMessage(PodManagerService.MSG_CONNECT_POD, String.valueOf(mOwnPod));
					configureUI(mConnectionState, mSyncState);
				} else {
					handlePodExit();
				}
				return true;

			case R.id.menu_initialise_pod:
				startActivityForResult(new Intent(MainActivity.this, WelcomeActivity.class), INITIALISE_POD);
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onBackPressed() {
		if (handlePodExit()) {
			return;
		}
		super.onBackPressed();
	}

	public void handleClick(View view) {
		switch (view.getId()) {
			case R.id.pod_search_cancel:
				handlePodExit();
				break;

			case R.id.pod_sync_complete_hint:
				mOwnPodDetailsShown = true;
				configureUI(mConnectionState, mSyncState);
				break;

			case R.id.pod_sync_complete_disconnect:
				sendServiceMessage(PodManagerService.MSG_DISCONNECT_POD, null);
				finish();
				break;

			default:
				break;
		}
	}

	// return true if we should stop an attempt to exit the app
	private boolean handlePodExit() {
		switch (mConnectionState) {
			case DISCONNECTED:
				// used to toggle between own and guest Pod on back press - that was confusing, so we no-longer do this
				break;

			case SEARCHING:
			case CONNECTED:
				removeVisitorData();
				mOwnPodDetailsShown = false;
				mSyncState = SyncState.INACTIVE;
				mConnectionState = ConnectionState.DISCONNECTED;
				sendServiceMessage(PodManagerService.MSG_DISCONNECT_POD, null);
				configureUI(mConnectionState, mSyncState);
				return true;
		}
		return false;
	}

	private EditText.OnEditorActionListener mVisitorPinListener = new EditText.OnEditorActionListener() {
		@Override
		public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				submitVisitorPin();
			}
			return false;
		}
	};

	private TextWatcher mVisitorPinWatcher = new TextWatcher() {
		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
		}

		@Override
		public void afterTextChanged(Editable s) {
			if (s.length() == 4) {
				submitVisitorPin();
			}
		}
	};

	private void submitVisitorPin() {
		String enteredPin = mPodConnectionPin.getText().toString();
		int visitorPin = -1;
		try {
			visitorPin = Integer.parseInt(enteredPin);
		} catch (NumberFormatException ignored) {
		}
		if (visitorPin >= 0) {
			Log.d(TAG, "Initialising - requesting connection to visitor Pod");
			mPodConnectionPin.setText("");
			mVisitorPod = visitorPin;
			mSyncState = SyncState.INACTIVE;
			mConnectionState = ConnectionState.SEARCHING;
			configureUI(mConnectionState, mSyncState);
			sendServiceMessage(PodManagerService.MSG_CONNECT_POD, String.valueOf(mVisitorPod));
		} else {
			// shouldn't ever happen; only positive integers allowed
			mPodConnectionPin.setError(getString(R.string.hint_incorrect_pin));
			Utilities.setKeyboardVisibility(MainActivity.this, mPodConnectionPin, true);
		}
	}

	@Override
	public void onServiceMessageReceived(int type, String data) {
		switch (type) {
			case PodManagerService.EVENT_CONNECTION_ROOT_FOLDER_UPDATE:
				mConnectionRootFolder = data;
				break;

			case PodManagerService.EVENT_CONNECTION_STATUS_UPDATE:
				switch (data) {
					case PodManagerService.DATA_POD_CONNECTED:
						Log.d(TAG, "Pod message: connected");
						// don't do this, so we don't interrupt existing synchronisation if the message appears twice
						// mSyncState = SyncState.INACTIVE;
						mConnectionState = ConnectionState.CONNECTED;

						// they haven't configured a Pod - launch initial setup
						if (mVisitorPod < 0) {
							if (mOwnPod < 0) {
								// happens either when we connect to a Pod without knowing its PIN (first launch); or,
								// when we were connected to a visitor's pod and the connection was dropped
								if (mIsVisible) { // only want to show this when we're the frontmost activity
									Toast.makeText(MainActivity.this, R.string.hint_pod_connection_reconnect, Toast
											.LENGTH_SHORT)
											.show();
								}
							} else {
								synchroniseOwnData();
							}
						} else {
							synchroniseVisitorData(mVisitorPod);
						}
						break;

					case PodManagerService.DATA_POD_CONNECTION_DROPPED:
						Log.d(TAG, "Pod message: dropped");
						mSyncState = SyncState.INACTIVE; // TODO: we should actually stop sync...
						mConnectionState = ConnectionState.SEARCHING;
						configureUI(mConnectionState, mSyncState);
						break;

					case PodManagerService.DATA_POD_CONNECTION_FAILURE:
						Log.d(TAG, "Pod message: connection failure");
						Toast.makeText(MainActivity.this, R.string.hint_error_error_connecting_pod, Toast.LENGTH_SHORT)
								.show();
						removeVisitorData();
						mSyncState = SyncState.INACTIVE;
						mConnectionState = ConnectionState.DISCONNECTED;
						configureUI(mConnectionState, mSyncState);
						break;

					default:
						Log.d(TAG, "Connection status update: " + data);
						break;
				}
				break;
		}
	}

	private void configureUI(ConnectionState connectionState, SyncState syncState) {
		supportInvalidateOptionsMenu();

		switch (connectionState) {
			case DISCONNECTED:
				mPodConnectionPin.setText("");
				mPodConnectionView.setVisibility(View.VISIBLE);
				mPodSearchIndicator.setVisibility(View.GONE);
				mPodSyncIndicator.setVisibility(View.GONE);
				mPodOwnSyncCompleteIndicator.setVisibility(View.GONE);
				mChooseAppBackgroundImage.setVisibility(View.GONE);
				mChooseAppView.setVisibility(View.GONE);
				Utilities.setKeyboardVisibility(MainActivity.this, mPodConnectionPin, true);
				break;

			case SEARCHING:
				mPodConnectionView.setVisibility(View.GONE);
				mPodSearchIndicator.setVisibility(View.VISIBLE);
				mPodSyncIndicator.setVisibility(View.GONE);
				mPodOwnSyncCompleteIndicator.setVisibility(View.GONE);
				mChooseAppBackgroundImage.setVisibility(View.GONE);
				mChooseAppView.setVisibility(View.GONE);
				Utilities.setKeyboardVisibility(MainActivity.this, mPodConnectionPin, false);
				break;

			case CONNECTED:
				Utilities.setKeyboardVisibility(MainActivity.this, mPodConnectionPin, false);
				switch (syncState) {
					case INACTIVE:
						// TODO: do we need to handle this if they rotate the screen?
						break; // shouldn't happen - we automatically sync after connecting

					case SYNCHRONISING:
						mPodConnectionView.setVisibility(View.GONE);
						mPodSearchIndicator.setVisibility(View.GONE);
						mPodSyncIndicator.setVisibility(View.VISIBLE);
						mPodOwnSyncCompleteIndicator.setVisibility(View.GONE);
						mChooseAppBackgroundImage.setVisibility(View.GONE);
						mChooseAppView.setVisibility(View.GONE);
						break;

					case COMPLETE:
						if (mVisitorPod < 0 && !mOwnPodDetailsShown) {
							mPodSyncIndicator.setVisibility(View.GONE);
							mPodOwnSyncCompleteIndicator.setVisibility(View.VISIBLE);

						} else {
							mPodOwnSyncCompleteIndicator.setVisibility(View.GONE);
							mChooseAppBackgroundImage.setImageURI(BACKGROUND_IMAGE);
							mPodConnectionView.setVisibility(View.GONE);
							mPodSearchIndicator.setVisibility(View.GONE);
							mPodSyncIndicator.setVisibility(View.GONE);
							mChooseAppBackgroundImage.setVisibility(View.VISIBLE);
							mChooseAppView.setAdapter(new GridAdapter(sApps));
							mChooseAppView.setVisibility(View.VISIBLE);
							mChooseAppView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
								@Override
								public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
									AppLauncher clickedMode = (AppLauncher) parent.getAdapter().getItem(position);
									if (clickedMode.mStorageLocation != null) {
										Intent launchIntent = new Intent(MainActivity.this, clickedMode.mClass);
										launchIntent.putExtra(BaseBrowserActivity.OWN_ID, mOwnPod);
										launchIntent.putExtra(BaseBrowserActivity.VISITOR_ID, mVisitorPod);
										launchIntent.putExtra(BaseBrowserActivity.DATA_TYPE, clickedMode
												.mStorageLocation);
										startActivityForResult(launchIntent, BROWSE_MEDIA);
									} else {
										// dummy items used to launch settings and exit current Pod
										int itemCount = mChooseAppView.getCount();
										if (position == itemCount - 2) {
											startActivityForResult(new Intent(MainActivity.this, SettingsActivity
													.class), EDIT_SETTINGS);
										} else if (position == itemCount - 1) {
											if (mVisitorPod >= 0) {
												handlePodExit();
											} else {
												finish(); // exit when finished with own pod
											}
										}
									}
								}
							});
						}
						break;
				}
				break;
		}
	}

	private String buildFilePath(String rootFolder, String... pathComponents) {
		StringBuilder builder = new StringBuilder();
		builder.append(rootFolder);
		for (String component : pathComponents) {
			builder.append('/');
			builder.append(component);
		}
		return builder.toString();
	}

	private String buildPodFilePath(String... pathComponents) {
		String[] components = new String[pathComponents.length + 1];
		System.arraycopy(pathComponents, 0, components, 1, pathComponents.length);
		components[0] = PodManagerService.DEFAULT_POD_NAME;
		return buildFilePath(mConnectionRootFolder, components);
	}

	// TODO: all of the synchronisation code here should probably be moved out of this activity
	// TODO: in addition, local vs. visitor is heavily duplicated - needs rationalising
	@UiThread
	private void synchroniseOwnData() {
		Log.d(TAG, "Beginning synchronisation of own data");
		if (mSyncState == SyncState.SYNCHRONISING) {
			Log.d(TAG, "Ignoring own data sync request - already in progress");
			return;
		}

		// mark all data types we are synchronising as unsynchronised before we begin
		mSyncState = SyncState.SYNCHRONISING;
		configureUI(mConnectionState, mSyncState);
		for (AppLauncher dataType : sApps) {
			dataType.mShouldSynchroniseLocalData = SettingsActivity.getShouldSync(MainActivity.this, dataType
					.mStorageLocation);
			dataType.mLocalDataSynchronised = false;
		}
		mPodSyncProgressCounter.setMax(1);
		mPodSyncProgressCounter.setProgress(1); // simulate some progress initially

		final String ownPodRootLocation = buildPodFilePath();
		PodManagerService.getFileExists(ownPodRootLocation, new PodResponseListener() {
			@Override
			public void onResponse(boolean success, String message) {
				if (!success) {
					final HashMap<String, Boolean> createdFolders = new HashMap<>();
					for (AppLauncher dataType : sApps) {
						createdFolders.put(buildPodFilePath(dataType.mStorageLocation), Boolean.FALSE);
					}

					// root folder doesn't yet exist - initialise and create folders for ALL data types
					PodResponseListener createRootFolderStructureListener = new PodResponseListener() {
						@Override
						public void onResponse(boolean success, String message) {
							if (success) {
								Log.d(TAG, "Pod folder creation succeeded: " + message);

								// if the folder isn't in the list, this is the main root folder - create its children
								if (!createdFolders.containsKey(message)) {
									createRootSubFolders(createdFolders);
								}
							} else {
								Log.d(TAG, "Pod folder creation failed: " + message);
								// nothing much we can do (perhaps try again?)...
							}
						}
					};

					// create the root folder
					Log.d(TAG, "First time Pod creation - initialising folder structure");
					PodManagerService.createDirectory(ownPodRootLocation, createRootFolderStructureListener);

				} else {
					// start the actual synchronisation straight away (must be UI thread to access media/cursors etc)
					// TODO: will fail if directory structure has changed for any reason - should we check ALL folders?
					MainActivity.this.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							getUpdatedRemoteFiles();
						}
					});
				}
			}
		});
	}

	private void createRootSubFolders(final HashMap<String, Boolean> createdFolders) {
		// finish creating the remaining root folders below the user's pin folder
		PodResponseListener createApplicationFolderStructureListener = new PodResponseListener() {
			@Override
			public void onResponse(boolean success, String message) {
				if (success) {
					Log.d(TAG, "Application folder creation succeeded: " + message);
					createdFolders.put(message, Boolean.TRUE);
					checkFolderCreationCompletion();
				} else {
					Log.d(TAG, "Application folder creation failed: " + message);
					createdFolders.put(message, Boolean.TRUE); // TODO: don't synchronise this any more - update prefs
					checkFolderCreationCompletion();
				}
			}

			private void checkFolderCreationCompletion() {
				for (Boolean state : createdFolders.values()) {
					if (!state) {
						return;
					}
				}

				// completed - continue sync (must be run on UI thread to access media / cursors etc)
				MainActivity.this.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						getUpdatedRemoteFiles();
					}
				});
			}
		};

		// once the root folder is created, make sure ALL the subfolders exist too
		for (String folder : createdFolders.keySet()) {
			PodManagerService.createDirectory(folder, createApplicationFolderStructureListener);
		}
	}

	@UiThread
	private void getUpdatedRemoteFiles() {
		Log.d(TAG, "Pod initialised - getting files from Pod");

		boolean hasSynchronisedDataType = false;
		for (final AppLauncher dataType : sApps) {
			if (!dataType.mShouldSynchroniseLocalData) {
				continue; // only sync when we have permissions and user's authorisation
			}

			hasSynchronisedDataType = true;
			final String fileSavePath;
			try {
				Method method = dataType.mClass.getMethod("getLocalSyncPath", Context.class);
				fileSavePath = (String) method.invoke(dataType.mClass.newInstance(), MainActivity.this);
			} catch (Exception e) {
				Log.d(TAG, "Accessing getLocalSyncPath via reflection failed for " + dataType.mStorageLocation +
						" - skipping");
				// e.printStackTrace();
				continue;
			}

			// get a list of remote files that are on the pod but don't exist locally
			PodManagerService.getDirectoryList(buildPodFilePath(dataType.mStorageLocation), new
					PodResponseDirectoryContentListener() {
				@Override
				public void onResponse(boolean success, final String originalRequestPath, @Nullable final
				ArrayList<SynchronisedFile> fileList) {

					// list will never be null if request succeeds (could only be empty)
					if (success && fileList != null && fileList.size() > 0) {

						Log.d(TAG, "New files to download from " + originalRequestPath + ":");
						mPodSyncProgressCounter.setMax(mPodSyncProgressCounter.getMax() + fileList.size());
						for (final SynchronisedFile file : fileList) {

							Log.d(TAG, "File: " + file.mPath + ", created on " + file.mCreationDate + ", " + "type: " +
									file.mMimeType);
							PodManagerService.downloadFile(file.mPath, fileSavePath, file.mName, dataType
									.mForceSyncConfigFiles, new PodResponseListener() {
								@Override
								public void onResponse(boolean success, final String originalRequestPath) {
									final File localFile = new File(fileSavePath, file.mName);
									if (success && localFile.exists()) {
										if (file.mPath.equals(originalRequestPath)) {
											// default situation, for normal files
											// note: setLastModified often doesn't work (see bug report below) -
											// instead, we rely on Pod-based media being newer than local device media
											// (as the user will only really be using one device (Pod/phone) at once)
											// https://code.google.com/p/android/issues/detail?id=18624
											// Math.abs as sometimes we get an incorrect, negative time value
											localFile.setLastModified(Math.abs(file.mCreationDate.getTime()));

											// process new file - must be on UI thread to access media / cursors etc
											MainActivity.this.runOnUiThread(new Runnable() {
												@Override
												public void run() {
													try {
														Method method = dataType.mClass.getMethod("processNewFile",
																Context.class, File.class,
																FileProcessedResponseListener.class);
														method.invoke(dataType.mClass.newInstance(), MainActivity
																.this, localFile, new FileProcessedResponseListener() {
															@Override
															public void onResponse() {
																Log.d(TAG, "File download completed for " +
																		originalRequestPath);
																file.mSynchronised = true;
																checkDownloadComplete();
															}
														});
													} catch (Exception e) {
														Log.d(TAG,
																"Accessing processNewFile via reflection failed for " +
																		originalRequestPath);
														// e.printStackTrace();
														file.mSynchronised = true;
														checkDownloadComplete();
													}
												}
											});

										} else if (originalRequestPath.endsWith(PodManagerService
												.POD_CONFIGURATION_FILE_CONFLICT_EXTENSION)) { // TODO: enough check?

											// TODO: we don't do anything with conflicting config files as they
											// contain updates (on either side) that we need to incorporate - future
											// version that update contacts will need to improve this, which will
											// require handling of the file date issue above (or another method)
											final File conflictFile = new File(localFile.getAbsolutePath() +
													PodManagerService.POD_CONFIGURATION_FILE_CONFLICT_EXTENSION);
											// process new file - must be on UI thread to access media / cursors etc
											MainActivity.this.runOnUiThread(new Runnable() {
												@Override
												public void run() {
													try {
														Method method = dataType.mClass.getMethod("processNewFile",
																Context.class, File.class,
																FileProcessedResponseListener.class);
														method.invoke(dataType.mClass.newInstance(), MainActivity
																.this, conflictFile, new FileProcessedResponseListener
																() {
															@Override
															public void onResponse() {
																boolean success = conflictFile.delete();
																Log.d(TAG,
																		"Conflict config file download completed for" +
																				" " + originalRequestPath + ", " +
																				success);
																file.mSynchronised = true;
																checkDownloadComplete();
															}
														});
													} catch (Exception e) {
														boolean success = conflictFile.delete();
														Log.d(TAG, "Accessing processNewFile for config file via " +
																"reflection " + "failed for " + originalRequestPath +
																", " + success);
														// e.printStackTrace();
														file.mSynchronised = true;
														checkDownloadComplete();
													}
												}
											});

										} else {
											Log.d(TAG,
													"Unknown download error - skipping " + originalRequestPath + " :" +
															" " + fileSavePath);
											file.mSynchronised = true;
											checkDownloadComplete();
										}
									} else {
										Log.d(TAG, "File exists or download failure - skipping " +
												originalRequestPath);
										file.mSynchronised = true;
										checkDownloadComplete();
									}
								}

								private void checkDownloadComplete() {
									// must be run on UI thread to access views
									MainActivity.this.runOnUiThread(new Runnable() {
										@Override
										public void run() {
											mPodSyncProgressCounter.setProgress(
													mPodSyncProgressCounter.getProgress() + 1);
										}
									});

									for (SynchronisedFile file : fileList) {
										if (!file.mSynchronised) {
											return;
										}
									}

									// must be run on UI thread to access media / cursors etc
									MainActivity.this.runOnUiThread(new Runnable() {
										@Override
										public void run() {
											Log.d(TAG, "All downloads completed for " + dataType.mStorageLocation);
											sendUpdatedLocalFiles(fileList, dataType);
										}
									});
								}
							});
						}

					} else {
						// must be run on UI thread to access media / cursors etc
						MainActivity.this.runOnUiThread(new Runnable() {
							@Override
							public void run() {
								// nothing much we can do (perhaps try again?)...
								Log.d(TAG, "Folder listing empty or failed for " + originalRequestPath);
								sendUpdatedLocalFiles(new ArrayList<SynchronisedFile>(), dataType);
							}
						});
					}
				}
			});
		}

		// if nothing was synchronised, skip straight to completion (displaying settings only)
		if (!hasSynchronisedDataType) {
			completeOwnSynchronisation();
		}
	}

	@UiThread // because getBackupItems needs access to media / cursors etc
	private void sendUpdatedLocalFiles(ArrayList<SynchronisedFile> remoteFileList, final AppLauncher dataType) {
		Log.w(TAG, "Sending updated local files for " + dataType.mStorageLocation);

		final int itemLimit = SettingsActivity.getItemSyncLimit(MainActivity.this);
		final ArrayList<String> localFiles;
		try {
			Method method = dataType.mClass.getMethod("getBackupItems", Context.class, int.class);
			localFiles = (ArrayList<String>) method.invoke(dataType.mClass.newInstance(), MainActivity.this,
					itemLimit);
		} catch (Exception e) {
			Log.d(TAG,
					"Accessing getBackupItems via reflection failed for " + dataType.mStorageLocation + " - skipping");
			// e.printStackTrace();
			return;
		}

		// remove files that exist on the remote device from the local list (no need to sync these items)
		Log.d(TAG, "New local files from " + dataType.mStorageLocation + ":");
		final ArrayList<SynchronisedFile> filteredLocalFiles = new ArrayList<>();
		for (String localFilePath : localFiles) {
			File localFile = new File(localFilePath);
			boolean found = false;

			// for some apps, we always upload local config files - they are used to sync non-file items (e.g., SMS)
			for (Iterator<SynchronisedFile> remoteIterator = remoteFileList.iterator(); remoteIterator.hasNext(); ) {
				// check similarity based solely on name - could compare size, but cannot duplicate names, so no point
				SynchronisedFile remoteFile = remoteIterator.next();
				boolean isConfigFile = localFile.getName().endsWith(PodManagerService.POD_CONFIGURATION_FILE_NAME);
				if (remoteFile.mName.equals(localFile.getName())) {
					if (!(dataType.mForceSyncConfigFiles && isConfigFile)) {
						Log.d(TAG, "Remote file list already contains " + remoteFile.mName + " - skipping");
						remoteIterator.remove();
						found = true;
					} else {
						Log.d(TAG, "Remote config file upload requested " + remoteFile.mName + " - not skipping");
					}
					break;
				}
			}

			if (!found) { // file doesn't exist - needs to be synchronised
				String absolutePath = localFile.getAbsolutePath();
				SynchronisedFile newLocalFile = new SynchronisedFile(absolutePath, localFile.length(),
						PodManagerService
						.getMimeType(absolutePath), new Date(localFile.lastModified()));
				filteredLocalFiles.add(newLocalFile);
				Log.d(TAG,
						"File to upload: " + newLocalFile.mPath + ", created on " + newLocalFile.mCreationDate + ", " +
								"type: " + newLocalFile.mMimeType);
			}
		}

		if (filteredLocalFiles.size() == 0) {
			removeOutdatedRemoteFiles(localFiles, dataType); // nothing we can upload - skip to finalisation
			return;
		} else {
			mPodSyncProgressCounter.setMax(mPodSyncProgressCounter.getMax() + filteredLocalFiles.size());
		}

		// send updated local files to the Pod
		for (final SynchronisedFile localFile : filteredLocalFiles) {
			PodManagerService.uploadFile(buildPodFilePath(dataType.mStorageLocation), localFile, new
					PodResponseListener() {
				@Override
				public void onResponse(boolean success, String originalRequestPath) {
					if (success) {
						Log.d(TAG, "File upload completed for " + localFile.mPath + " to " + originalRequestPath);
					} else {
						Log.d(TAG, "File upload failed for " + localFile.mPath + " to " + originalRequestPath);
					}
					localFile.mSynchronised = true;

					// must be run on UI thread to access views
					MainActivity.this.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							mPodSyncProgressCounter.setProgress(mPodSyncProgressCounter.getProgress() + 1);
						}
					});

					for (SynchronisedFile file : filteredLocalFiles) {
						if (!file.mSynchronised) {
							return;
						}
					}

					removeOutdatedRemoteFiles(localFiles, dataType);
				}
			});
		}
	}

	private void removeOutdatedRemoteFiles(final ArrayList<String> localFiles, final AppLauncher dataType) {
		Log.d(TAG, "Removing outdated remote files for " + dataType.mStorageLocation);
		final int itemLimit = SettingsActivity.getItemSyncLimit(MainActivity.this);

		// remove any extra remote files above the limit set by the user
		PodManagerService.getDirectoryList(buildPodFilePath(dataType.mStorageLocation), new
				PodResponseDirectoryContentListener() {
			@Override
			public void onResponse(boolean success, String originalRequestPath, @Nullable final
			ArrayList<SynchronisedFile> fileList) {

				// list will never be null if request succeeds (could only be empty)
				if (success && fileList != null) {
					Log.d(TAG, "File count limit: " + itemLimit + "; current: " + fileList.size());

					// filter files that are on the local device sync list from the remote list
					// (we will remove all other items)
					for (String localFilePath : localFiles) {
						File localFile = new File(localFilePath);
						for (Iterator<SynchronisedFile> remoteIterator = fileList.iterator();
							 remoteIterator.hasNext(); ) {
							SynchronisedFile remoteFile = remoteIterator.next(); // similarity is based solely on name
							if (remoteFile.mName.equals(localFile.getName())) {
								Log.d(TAG, "Remote file list contains " + remoteFile.mName + " - keeping file");
								remoteIterator.remove();
								break;
							}
						}
					}

					if (fileList.size() > 0) {
						for (final SynchronisedFile file : fileList) {
							PodManagerService.deleteRemoteFile(file.mPath, new PodResponseListener() {
								@Override
								public void onResponse(boolean success, String originalRequestPath) {
									if (success) {
										Log.d(TAG, "Remote file deletion completed for " + originalRequestPath);
									} else {
										Log.d(TAG, "Remote file deletion failed for " + originalRequestPath);
									}
									file.mSynchronised = true;

									// note: we don't update the progress bar here as deletion is quick
									for (SynchronisedFile file : fileList) {
										if (!file.mSynchronised) {
											return;
										}
									}

									// must be run on UI thread to access media / cursors etc
									MainActivity.this.runOnUiThread(new Runnable() {
										@Override
										public void run() {
											finaliseOwnDataSynchronisation(dataType);
										}
									});
								}
							});
						}
					} else {
						// must be run on UI thread to access media / cursors etc
						MainActivity.this.runOnUiThread(new Runnable() {
							@Override
							public void run() {
								finaliseOwnDataSynchronisation(dataType);
							}
						});
					}

				} else {
					// must be run on UI thread to access media / cursors etc
					MainActivity.this.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							finaliseOwnDataSynchronisation(dataType);
						}
					});
				}
			}
		});
	}

	@UiThread // so we can show a Toast to confirm sync completion
	private void finaliseOwnDataSynchronisation(AppLauncher dataType) {
		Log.d(TAG, "All synchronisation tasks completed for " + dataType.mStorageLocation);
		dataType.mLocalDataSynchronised = true;

		for (final AppLauncher type : sApps) {
			if (type.mShouldSynchroniseLocalData && !type.mLocalDataSynchronised) {
				return; // wait for remaining data types to complete
			}
		}

		if (mSyncState == SyncState.COMPLETE) {
			return; // we've already completed synchronisation in another call to this method
		}

		completeOwnSynchronisation();
	}

	private void completeOwnSynchronisation() {
		// TODO: do this in a more appropriate way
		ImagePipeline imagePipeline = Fresco.getImagePipeline();
		imagePipeline.evictFromCache(BACKGROUND_IMAGE);

		Log.d(TAG, "All sync steps complete");
		mLastOwnPodSyncTime = System.currentTimeMillis();
		mPodSyncProgressCounter.setProgress(mPodSyncProgressCounter.getMax());
		mSyncState = SyncState.COMPLETE;
		configureUI(mConnectionState, mSyncState);
		if (mIsVisible) { // TODO: should we just not synchronise when not visible?
			Toast.makeText(MainActivity.this, R.string.hint_own_data_sync_complete, Toast.LENGTH_SHORT).show();
		}
	}

	private void synchroniseVisitorData(final int visitorId) {
		Log.d(TAG, "Pod connected - retrieving visitor content for " + visitorId);

		// mark ALL data types as unsynchronised before we begin
		mSyncState = SyncState.SYNCHRONISING;
		configureUI(mConnectionState, mSyncState);
		for (AppLauncher dataType : sApps) {
			dataType.mVisitorDataSynchronised = false;
		}
		mPodSyncProgressCounter.setMax(1);
		mPodSyncProgressCounter.setProgress(1); // simulate some progress initially

		// for the second deployment, we encounter uninitialised visitors, so need to create their root folders
		final String visitorPodRootLocation = buildPodFilePath();
		PodManagerService.getFileExists(visitorPodRootLocation, new PodResponseListener() {
			@Override
			public void onResponse(boolean success, String message) {
				if (!success) {
					final HashMap<String, Boolean> createdFolders = new HashMap<>();
					for (AppLauncher dataType : sApps) {
						createdFolders.put(buildPodFilePath(dataType.mStorageLocation), Boolean.FALSE);
					}

					// root folder doesn't yet exist - initialise and create folders for ALL data types
					PodResponseListener createRootFolderStructureListener = new PodResponseListener() {
						@Override
						public void onResponse(boolean success, String message) {
							if (success) {
								Log.d(TAG, "Visitor Pod folder creation succeeded: " + message);

								// if the folder isn't in the list, this is the main root folder - create its children
								if (!createdFolders.containsKey(message)) {
									createRootVisitorSubFolders(visitorId, createdFolders);
								}
							} else {
								Log.d(TAG, "Visitor Pod folder creation failed: " + message);
								// nothing much we can do (perhaps try again?)...
							}
						}
					};

					// create the root folder
					Log.d(TAG, "First time visitor Pod creation - initialising folder structure");
					PodManagerService.createDirectory(visitorPodRootLocation, createRootFolderStructureListener);

				} else {
					// start the actual file synchronisation straight away (must be run on UI thread to access
					// media/cursors etc)
					// TODO: will fail if directory structure has changed for any reason - should we check ALL folders?
					MainActivity.this.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							getUpdatedVisitorFiles(visitorId);
						}
					});
				}
			}
		});
	}

	private void createRootVisitorSubFolders(final int visitorId, final HashMap<String, Boolean> createdFolders) {
		// finish creating the remaining root folders below the user's pin folder
		PodResponseListener createApplicationFolderStructureListener = new PodResponseListener() {
			@Override
			public void onResponse(boolean success, String message) {
				if (success) {
					Log.d(TAG, "Application folder creation succeeded: " + message);
					createdFolders.put(message, Boolean.TRUE);
					checkFolderCreationCompletion();
				} else {
					Log.d(TAG, "Application folder creation failed: " + message);
					createdFolders.put(message, Boolean.TRUE); // TODO: don't synchronise this any more - update prefs
					checkFolderCreationCompletion();
				}
			}

			private void checkFolderCreationCompletion() {
				for (Boolean state : createdFolders.values()) {
					if (!state) {
						return;
					}
				}

				// completed - continue sync (must be run on UI thread to access media / cursors etc)
				MainActivity.this.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						getUpdatedVisitorFiles(visitorId);
					}
				});
			}
		};

		// once the root folder is created, make sure ALL the subfolders exist too
		for (String folder : createdFolders.keySet()) {
			PodManagerService.createDirectory(folder, createApplicationFolderStructureListener);
		}
	}

	private void getUpdatedVisitorFiles(final int visitorId) {
		for (final AppLauncher dataType : sApps) {
			final String fileSavePath = BaseBrowserActivity.getVisitorSyncPath(MainActivity.this, visitorId, dataType
					.mStorageLocation);
			Log.d(TAG, "Saving visitor content to " + fileSavePath);

			// get a list of remote files that are on the pod but don't exist locally
			PodManagerService.getDirectoryList(buildPodFilePath(dataType.mStorageLocation), new
					PodResponseDirectoryContentListener() {
				@Override
				public void onResponse(boolean success, final String originalRequestPath, @Nullable final
				ArrayList<SynchronisedFile> fileList) {

					// list will never be null if request succeeds (could only be empty)
					if (success && fileList != null && fileList.size() > 0) {

						Log.d(TAG, "New visitor files to download from " + originalRequestPath + ":");
						mPodSyncProgressCounter.setMax(mPodSyncProgressCounter.getMax() + fileList.size());
						for (final SynchronisedFile file : fileList) {

							Log.d(TAG, "File: " + file.mPath + ", created on " + file.mCreationDate + ", " + "type: " +
									file.mMimeType);
							PodManagerService.downloadFile(file.mPath, fileSavePath, file.mName, dataType
									.mForceSyncConfigFiles, new PodResponseListener() {
								@Override
								public void onResponse(boolean success, final String originalRequestPath) {
									final File localFile = new File(fileSavePath, file.mName);
									if (success && localFile.exists()) {
										if (file.mPath.equals(originalRequestPath)) {
											// default situation, for normal files
											Log.d(TAG, "File download completed for visitor " + originalRequestPath);
											file.mSynchronised = true;
											checkDownloadComplete();

										} else if (originalRequestPath.endsWith(PodManagerService
												.POD_CONFIGURATION_FILE_CONFLICT_EXTENSION)) { // enough check?

											// TODO: we don't do anything with conflicting config files as our local
											// copy, if it exists, will always have any updates (could only be a
											// problem if local and visitor connect to the same pod at the same time)

											// configuration file conflict - check which file is newer and use that one
											File conflictFile = new File(localFile.getAbsolutePath() +
													PodManagerService.POD_CONFIGURATION_FILE_CONFLICT_EXTENSION);
											boolean conflictSuccess = conflictFile.delete();
											Log.w(TAG, "Visitor configuration file conflict - choosing local " +
													localFile.getAbsolutePath() + " vs " +
													conflictFile.getAbsolutePath() + " - " + conflictSuccess);
											file.mSynchronised = true;
											checkDownloadComplete();

										} else {
											Log.d(TAG, "Unknown download error - skipping visitor file " +
													originalRequestPath + " : " + fileSavePath);
											file.mSynchronised = true;
											checkDownloadComplete();
										}
									} else {
										Log.d(TAG, "File exists or download failure - skipping visitor " +
												originalRequestPath);
										file.mSynchronised = true;
										checkDownloadComplete();
									}
								}

								private void checkDownloadComplete() {
									// must be run on UI thread to access views
									MainActivity.this.runOnUiThread(new Runnable() {
										@Override
										public void run() {
											mPodSyncProgressCounter.setProgress(
													mPodSyncProgressCounter.getProgress() + 1);
										}
									});

									for (SynchronisedFile file : fileList) {
										if (!file.mSynchronised) {
											return;
										}
									}

									// must be run on UI thread to access media / cursors etc
									MainActivity.this.runOnUiThread(new Runnable() {
										@Override
										public void run() {
											Log.d(TAG,
													"All downloads completed for visitor " + dataType
															.mStorageLocation);
											sendUpdatedVisitorFiles(visitorId, fileList, dataType);
										}
									});
								}
							});
						}

					} else {
						// must be run on UI thread to access media / cursors etc
						MainActivity.this.runOnUiThread(new Runnable() {
							@Override
							public void run() {
								// nothing much we can do (perhaps try again?)...
								Log.d(TAG, "Folder listing empty or failed for visitor " + originalRequestPath);
								sendUpdatedVisitorFiles(visitorId, fileList, dataType);
							}
						});
					}
				}
			});
		}
	}

	@UiThread // because getBackupItems needs access to media / cursors etc
	private void sendUpdatedVisitorFiles(int visitorId, ArrayList<SynchronisedFile> remoteFileList, final AppLauncher
			dataType) {
		Log.d(TAG, "Sending updated visitor files for " + dataType.mStorageLocation);

		// rowLimit = -1 => synchronise *ALL* updated visitor files
		ArrayList<String> localFiles = BaseBrowserActivity.getRecencySortedFileList(BaseBrowserActivity
				.getVisitorSyncPath(MainActivity.this, visitorId, dataType.mStorageLocation), -1);

		// remove files that exist on the remote device from the local list (no need to sync these items)
		Log.d(TAG, "New local visitor files from " + dataType.mStorageLocation + ":");
		final ArrayList<SynchronisedFile> filteredLocalFiles = new ArrayList<>();
		for (String localFilePath : localFiles) {
			File localFile = new File(localFilePath);
			boolean found = false;

			// always upload visitor config files for some app types - they are used to sync non-file items (e.g., SMS)
			for (Iterator<SynchronisedFile> remoteIterator = remoteFileList.iterator(); remoteIterator.hasNext(); ) {
				// check similarity based solely on name - could compare size, but cannot duplicate names, so no point
				SynchronisedFile remoteFile = remoteIterator.next();
				boolean isConfigFile = localFile.getName().endsWith(PodManagerService.POD_CONFIGURATION_FILE_NAME);
				if (remoteFile.mName.equals(localFile.getName())) {
					if (!(dataType.mForceSyncConfigFiles && isConfigFile)) {
						Log.d(TAG, "Remote visitor file list already contains " + remoteFile.mName + " - skipping");
						remoteIterator.remove();
						found = true;
					} else {
						Log.d(TAG,
								"Remote visitor config file upload requested " + remoteFile.mName + " - not skipping");
					}
					break;
				}
			}

			if (!found) { // file doesn't exist - needs to be synchronised
				String absolutePath = localFile.getAbsolutePath();
				SynchronisedFile newLocalFile = new SynchronisedFile(absolutePath, localFile.length(),
						PodManagerService
						.getMimeType(absolutePath), new Date(localFile.lastModified()));
				filteredLocalFiles.add(newLocalFile);
				Log.d(TAG,
						"Visitor file to upload: " + newLocalFile.mPath + ", created on " + newLocalFile
								.mCreationDate +
								", " + "type: " + newLocalFile.mMimeType);
			}
		}

		if (filteredLocalFiles.size() == 0) {
			finaliseVisitorSynchronisation(dataType); // nothing we can upload - skip to finalisation
			return;
		} else {
			mPodSyncProgressCounter.setMax(mPodSyncProgressCounter.getMax() + filteredLocalFiles.size());
		}

		// send updated local files to the Pod
		for (final SynchronisedFile localFile : filteredLocalFiles) {
			PodManagerService.uploadFile(buildPodFilePath(dataType.mStorageLocation), localFile, new
					PodResponseListener() {
				@Override
				public void onResponse(boolean success, String originalRequestPath) {
					if (success) {
						Log.d(TAG, "Visitor file upload completed for " + originalRequestPath);
					} else {
						Log.d(TAG, "Visitor file upload failed for " + originalRequestPath);
					}
					localFile.mSynchronised = true;

					// must be run on UI thread to access views
					MainActivity.this.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							mPodSyncProgressCounter.setProgress(mPodSyncProgressCounter.getProgress() + 1);
						}
					});

					for (SynchronisedFile file : filteredLocalFiles) {
						if (!file.mSynchronised) {
							return;
						}
					}

					// must be run on UI thread to access media / cursors etc
					MainActivity.this.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							finaliseVisitorSynchronisation(dataType);
						}
					});
				}
			});
		}
	}

	@UiThread // so we can show Toast to confirm sync completion
	private void finaliseVisitorSynchronisation(AppLauncher dataType) {
		Log.d(TAG, "All visitor synchronisation tasks completed for " + dataType.mStorageLocation);
		dataType.mVisitorDataSynchronised = true;

		for (final AppLauncher type : sApps) {
			if (!type.mVisitorDataSynchronised) {
				return; // wait for remaining data
			}
		}

		if (mSyncState == SyncState.COMPLETE) {
			return; // we've already completed synchronisation in another call to this method
		}

		// TODO: do this in a more appropriate way
		ImagePipeline imagePipeline = Fresco.getImagePipeline();
		imagePipeline.evictFromCache(BACKGROUND_IMAGE);

		Log.d(TAG, "All visitor sync steps complete");
		mPodSyncProgressCounter.setProgress(mPodSyncProgressCounter.getMax());
		mSyncState = SyncState.COMPLETE;
		configureUI(mConnectionState, mSyncState);
		if (mIsVisible) { // TODO: should we just not synchronise when not visible?
			Toast.makeText(MainActivity.this, R.string.hint_visitor_data_sync_complete, Toast.LENGTH_SHORT).show();
		}
	}

	private void removeVisitorData() {
		if (mConnectionState == ConnectionState.CONNECTED &&
				mVisitorPod >= 0) { // we only remove visitor data, not own data
			Toast.makeText(MainActivity.this, R.string.hint_visitor_data_removed, Toast.LENGTH_SHORT).show();
		}
		mVisitorPod = -1; // so we don't automatically log back in again
		try {
			deleteLocalDirectory(getCacheDir());
		} catch (Exception ignored) {
		}
		for (AppLauncher dataType : sApps) {
			// sync completed - handle any cleanup
			Log.d(TAG, "Cleaning up " + dataType.mStorageLocation);
			try {
				Method method = dataType.mClass.getMethod("onSessionFinished", Context.class);
				method.invoke(dataType.mClass.newInstance(), MainActivity.this);
			} catch (Exception e) {
				Log.d(TAG, "Accessing onSessionFinished via reflection failed for " + dataType.mStorageLocation +
						" - skipping");
				// e.printStackTrace();
				return;
			}
		}
	}

	private boolean deleteLocalDirectory(File directory) {
		if (directory != null && directory.isDirectory()) {
			String[] children = directory.list();
			if (children != null) {
				for (String child : children) {
					boolean success = deleteLocalDirectory(new File(directory, child));
					if (!success) {
						return false;
					}
				}
			}
		}
		return directory != null && directory.delete();
	}

	private class GridAdapter extends BaseAdapter {
		final ArrayList<AppLauncher> mItems;

		private GridAdapter(final ArrayList<AppLauncher> modes) {
			mItems = new ArrayList<>();
			for (AppLauncher mode : modes) {
				if (mVisitorPod < 0) {
					if (mode.mShouldSynchroniseLocalData) {
						mItems.add(mode); // only add local items when they are synchronised
					}
				} else {
					mItems.add(mode); // always add all items for visitors
				}
			}
			// add the settings and exit buttons if not a visitor, or if in visitor only mode
			if (mVisitorPod < 0 || SettingsActivity.getVisitorOnlyMode(MainActivity.this)) {
				mItems.add(new AppLauncher(BaseBrowserActivity.class, R.drawable.ic_settings, R.string.menu_setup_pod,
						null, false));
			}
			mItems.add(new AppLauncher(BaseBrowserActivity.class, R.drawable.ic_check_circle_white_48dp, R.string
					.finished_browsing, null, false));
		}

		@Override
		public int getCount() {
			return mItems.size();
		}

		@Override
		public Object getItem(final int position) {
			return mItems.get(position);
		}

		@Override
		public long getItemId(final int position) {
			return position;
		}

		@Override
		public View getView(final int position, final View convertView, final ViewGroup parent) {
			View view = convertView;
			AppViewHolder viewHolder;
			if (view == null) {
				view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
				viewHolder = new AppViewHolder();
				viewHolder.icon = view.findViewById(R.id.app_icon);
				viewHolder.title = view.findViewById(R.id.app_title);
				view.setTag(viewHolder);
			} else {
				viewHolder = (AppViewHolder) view.getTag();
			}
			viewHolder.icon.setImageResource(mItems.get(position).mIcon);
			viewHolder.title.setText(getString(mItems.get(position).mTitle));
			return view;
		}
	}

	private static class AppViewHolder {
		ImageView icon;
		TextView title;
	}

	private void checkLocationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
				ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
						PackageManager.PERMISSION_GRANTED) {
			if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, Manifest.permission
					.ACCESS_COARSE_LOCATION)) {
				Toast.makeText(MainActivity.this, R.string.permission_access_coarse_location_rationale, Toast
						.LENGTH_LONG)
						.show();
			}
			ActivityCompat.requestPermissions(MainActivity.this, new String[]{
					Manifest.permission.ACCESS_COARSE_LOCATION
			}, PERMISSION_COARSE_LOCATION);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case INITIALISE_POD: // after first run (Pod setup)
				SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
				boolean hasAccepted = preferences.getBoolean(getString(R.string.key_completed_initial_setup), false);
				if (resultCode != Activity.RESULT_OK || !hasAccepted) {
					finish(); // don't allow anything else - must either explicitly skip or setup pod before continuing
				} else {
					// initiate the new connection and synchronise
					checkLocationPermission();
					mOwnPod = SettingsActivity.getOwnPodPin(MainActivity.this);
					if (mOwnPod >= 0) {
						Log.d(TAG, "First time initialising - requesting connection to own Pod");
						removeVisitorData();
						sendServiceMessage(PodManagerService.MSG_DISCONNECT_POD, null);

						mSyncState = SyncState.INACTIVE;
						mConnectionState = ConnectionState.SEARCHING;
						sendServiceMessage(PodManagerService.MSG_CONNECT_POD, String.valueOf(mOwnPod));
						configureUI(mConnectionState, mSyncState);
					}
				}
				break;

			case EDIT_SETTINGS:
				// mOwnPod will always exist as settings only accessible when connected to a pod
				mOwnPod = SettingsActivity.getOwnPodPin(MainActivity.this);
				supportInvalidateOptionsMenu();
				if (mConnectionState == ConnectionState.CONNECTED && mVisitorPod < 0) {
					mSyncState = SyncState.INACTIVE;
					synchroniseOwnData();
				}
				break;

			// update local or visitor media when returning
			case BROWSE_MEDIA:
				if (mConnectionState == ConnectionState.CONNECTED && resultCode == BaseBrowserActivity.MEDIA_UPDATED) {
					mSyncState = SyncState.INACTIVE;
					if (mVisitorPod >= 0) {
						synchroniseVisitorData(mVisitorPod);
					} else {
						synchroniseOwnData();
					}
				}
				break;

			default:
				break;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	// TODO: share this and other permissions-related code with SettingsActivity and WelcomeActivity
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[]
			grantResults) {
		switch (requestCode) {
			case PERMISSION_COARSE_LOCATION:
				if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
						PackageManager.PERMISSION_GRANTED) {
					Toast.makeText(MainActivity.this, R.string.permission_access_coarse_location_error, Toast
							.LENGTH_LONG)
							.show();
					finish();
				}
				break;

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}
	}
}
