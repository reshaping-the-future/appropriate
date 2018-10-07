package ac.robinson.pod;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import ac.robinson.pod.browsers.ContactsActivity;
import ac.robinson.pod.browsers.GalleryActivity;
import ac.robinson.pod.browsers.SMSActivity;
import ac.robinson.pod.listeners.PodResponseListener;
import ac.robinson.pod.service.PodManagerService;
import ac.robinson.pod.views.CustomSwipeViewPager;

import static ac.robinson.pod.service.PodManagerService.DATA_POD_CONNECTED;
import static ac.robinson.pod.service.PodManagerService.EVENT_CONNECTION_ROOT_FOLDER_UPDATE;
import static ac.robinson.pod.service.PodManagerService.EVENT_CONNECTION_STATUS_UPDATE;

public class WelcomeActivity extends BasePodActivity implements WelcomeFragment.OnCompleteListener {

	private static final String TAG = "WelcomeActivity";

	public static final String CHANGE_PIN_MODE = "change_pin";
	private static final int WELCOME_PAGES = 6;

	private static final int PERMISSION_WRITE_STORAGE = 105;
	private static final int PERMISSION_SMS = 106;
	private static final int PERMISSION_CONTACTS = 107;
	private static final int PERMISSION_COARSE_LOCATION = 108;

	private CustomSwipeViewPager mViewPager;
	private LinearLayout mIndicatorCircles;
	private Button mSkipButton;
	private Button mDoneButton;
	private ImageButton mPreviousButton;
	private ImageButton mNextButton;
	private EditText mPodConnectionPin;
	private CheckBox mSyncPhotosCheckBox;
	private CheckBox mSyncSMSCheckBox;
	private CheckBox mSyncContactsCheckBox;

	private int mNewPodPin = -1;
	private boolean mChangePinMode;
	private boolean mLayoutManipulated;

	private enum ConnectionState {
		DISCONNECTED, SEARCHING, CONNECTED
	}

	private ConnectionState mConnectionState = ConnectionState.DISCONNECTED;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_welcome);

		mChangePinMode = getIntent().getBooleanExtra(CHANGE_PIN_MODE, false);

		if (savedInstanceState != null) {
			mConnectionState = (ConnectionState) savedInstanceState.getSerializable("mConnectionState");
			mNewPodPin = savedInstanceState.getInt("mNewPodPin");
			mChangePinMode = savedInstanceState.getBoolean("mChangePinMode");
			mLayoutManipulated = savedInstanceState.getBoolean("mLayoutManipulated");
		}

		mSkipButton = findViewById(R.id.btn_skip);
		mSkipButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showSkipSetupDialog();
			}
		});

		mPreviousButton = findViewById(R.id.btn_previous);
		mPreviousButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				int currentItem = mViewPager.getCurrentItem();
				if (currentItem == 3 || currentItem == 5) {
					mViewPager.setCurrentItem(currentItem - 1, true);
				}
			}
		});

		mNextButton = findViewById(R.id.btn_next);
		mNextButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				int currentItem = mViewPager.getCurrentItem();
				if (currentItem == 1 || currentItem == 2 || currentItem == 3) {
					mPodConnectionPin.setError(getString(R.string.hint_incorrect_pin));
				} else {
					mViewPager.setCurrentItem(currentItem + 1, true);
				}
			}
		});

		mNextButton.setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				// allow skipping pin change step by long clicking on next button
				// (useful for setting up a Pod on a new phone or after app reinstallation)
				int currentItem = mViewPager.getCurrentItem();
				if (currentItem == 2 && !mChangePinMode) {
					AlertDialog.Builder builder = new AlertDialog.Builder(WelcomeActivity.this);
					builder.setTitle(R.string.title_skip_pin);
					builder.setMessage(R.string.message_skip_pin);
					builder.setNegativeButton(R.string.menu_cancel, null);
					builder.setPositiveButton(R.string.welcome_skip, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int whichButton) {
							SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences
									(WelcomeActivity.this);
							SharedPreferences.Editor editor = preferences.edit();
							editor.putString(getString(R.string.key_own_pod_pin), String.valueOf(mNewPodPin));
							editor.apply();

							mViewPager.setCurrentItem(4, false); // switch to choosing sync items
						}
					});
					builder.show();
				}
				return false;
			}
		});

		mDoneButton = findViewById(R.id.done);
		mDoneButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finishSetup(WelcomeActivity.this);
			}
		});

		mViewPager = findViewById(R.id.pager);
		PagerAdapter pagerAdapter = new ScreenSlideAdapter(getSupportFragmentManager());
		mViewPager.setAdapter(pagerAdapter);
		mViewPager.setPageTransformer(true, new CrossFadePageTransformer());
		mViewPager.addOnPageChangeListener(mViewPagerChangeListener);

		if (!mChangePinMode) {
			buildCircles(); // page indicators - not applicable for change PIN mode
		}

		if (mChangePinMode && mNewPodPin < 0) { // first launch of change pin mode
			mViewPager.setCurrentItem(1);

			// have to do here as onPageSelected isn't called on setup after changing pages, annoyingly
			mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
			mSkipButton.setVisibility(View.GONE);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (mViewPager != null) {
			mViewPager.clearOnPageChangeListeners();
		}
	}

	@Override
	public void onFragmentInflationComplete(int layoutId) {
		// this odd way of getting layout 1's views is necessary so we can start from position 1 in pin change mode
		int currentItem = mViewPager.getCurrentItem();
		if (currentItem == 1) {
			// make sure we have set up PIN entry correctly - duplicated from screen switching :-(
			mPodConnectionPin = findViewById(R.id.welcome_connect_initial_pin);
			if (mPodConnectionPin != null) {
				mPodConnectionPin.setText("");
				mPodConnectionPin.setError(null);
				mPodConnectionPin.setOnEditorActionListener(mPinListener);
				mPodConnectionPin.addTextChangedListener(mPinWatcher);
				if (!mLayoutManipulated) {
					Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, true);
				}
			}
		}
	}

	@Override
	public void onBackPressed() {
		if (mChangePinMode) {
			super.onBackPressed();
			return;
		}
		if (mViewPager.getCurrentItem() <= 1) {
			showSkipSetupDialog();
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putSerializable("mConnectionState", mConnectionState);
		outState.putInt("mNewPodPin", mNewPodPin);
		outState.putBoolean("mChangePinMode", mChangePinMode);
		outState.putBoolean("mLayoutManipulated", mLayoutManipulated);
		super.onSaveInstanceState(outState);
	}

	private void showSkipSetupDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(WelcomeActivity.this);
		builder.setTitle(R.string.title_skip_setup);
		builder.setMessage(R.string.message_skip_setup);
		builder.setNegativeButton(R.string.menu_cancel, null);
		builder.setPositiveButton(R.string.welcome_skip, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int whichButton) {
				finishSetup(WelcomeActivity.this);
			}
		});
		builder.show();
	}

	private EditText.OnEditorActionListener mPinListener = new EditText.OnEditorActionListener() {
		@Override
		public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				if (!TextUtils.isEmpty(mPodConnectionPin.getText())) {
					pinEntered();
				} else {
					mPodConnectionPin.setError(getString(R.string.hint_incorrect_pin));
					Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, true);
				}
			}
			return false;
		}
	};

	private TextWatcher mPinWatcher = new TextWatcher() {
		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
		}

		@Override
		public void afterTextChanged(Editable s) {
			if (s.length() == 4) {
				pinEntered();
			}
		}
	};

	private void pinEntered() {
		String enteredPin = mPodConnectionPin.getText().toString();
		int podPin = -1;
		try {
			podPin = Integer.parseInt(enteredPin);
		} catch (NumberFormatException ignored) {
		}
		if (podPin >= 0) {
			int currentItem = mViewPager.getCurrentItem();
			mPodConnectionPin.setText("");

			switch (currentItem) {
				case 1:
					Log.d(TAG, "Initialising - requesting connection to Pod");
					mNewPodPin = podPin;
					mConnectionState = ConnectionState.SEARCHING;
					sendServiceMessage(PodManagerService.MSG_CONNECT_POD, String.valueOf(mNewPodPin));

					mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
					findViewById(R.id.welcome_screen_initial_connect).setVisibility(View.GONE);
					findViewById(R.id.initial_pod_search_indicator).setVisibility(View.VISIBLE);
					Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, false);
					mLayoutManipulated = true;
					break;

				case 2:
					mNewPodPin = podPin;
					mViewPager.setCurrentItem(3);
					break;

				case 3:
					if (podPin == mNewPodPin) {
						Log.d(TAG, "Changing PIN...");
						PodManagerService.changeNetworkName(podPin, new PodResponseListener() {
							@Override
							public void onResponse(final boolean success, String originalRequestPath) {
								runOnUiThread(new Runnable() {
									@Override
									public void run() {
										if (success) {
											// if they exit from here then they miss the step of configuring the sync
											// items, but saving the pin is better than, e.g., being instructed to read
											// from the Pod and it being wrong
											// (this also allows us to perform any pin change using this activity)
											// TODO: pin change in visitor only mode is bit of a hack
											if (!SettingsActivity.getVisitorOnlyMode(WelcomeActivity.this)) {
												SharedPreferences preferences = PreferenceManager
														.getDefaultSharedPreferences(WelcomeActivity.this);

												SharedPreferences.Editor editor = preferences.edit();
												editor.putString(getString(R.string.key_own_pod_pin), String.valueOf
														(mNewPodPin));
												editor.apply();
												sendServiceMessage(PodManagerService.MSG_DISCONNECT_POD, null);
											}

											mConnectionState = ConnectionState.DISCONNECTED;
											TextView pinChangeText = findViewById(R.id.pod_pin_change_indicator_text);
											if (pinChangeText != null) {
												// TODO: after screen rotation pinChangeText is visible but null - why?
												// so we can visually tell when PIN change happens
												pinChangeText.setText(R.string.updating_pod_settings);
											}
											sendServiceMessage(PodManagerService.MSG_CONNECT_POD, String.valueOf
													(mNewPodPin));
										} else {
											Log.d(TAG, "Unable to change Pod PIN");
											Toast.makeText(WelcomeActivity.this, R.string.hint_error_changing_pin,
													Toast.LENGTH_LONG)
													.show();
											mLayoutManipulated = false;
											mViewPager.setCurrentItem(1);
											mNewPodPin = -1;
										}
									}
								});
							}
						});

						mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
						findViewById(R.id.welcome_screen_confirm_pin).setVisibility(View.GONE);
						findViewById(R.id.pod_pin_change_indicator).setVisibility(View.VISIBLE);
						mPreviousButton.setVisibility(View.INVISIBLE); // invisible not gone so we still have height
						mNextButton.setVisibility(View.INVISIBLE);
						Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, false);
						mLayoutManipulated = true;
					} else {
						mPodConnectionPin.setError(getString(R.string.hint_non_matching_pin));
					}
					break;

				default:
					break;
			}
		}
	}

	public void handleClick(View view) {
		switch (view.getId()) {
			case R.id.welcome_connect_cancel:
				sendServiceMessage(PodManagerService.MSG_DISCONNECT_POD, null);
				mConnectionState = ConnectionState.DISCONNECTED;
				mLayoutManipulated = false;
				mViewPager.setCurrentItem(1); // start again
				mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.LEFT);
				findViewById(R.id.welcome_screen_initial_connect).setVisibility(View.VISIBLE);
				findViewById(R.id.initial_pod_search_indicator).setVisibility(View.GONE);
				Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, true);
				break;

			default:
				break;
		}
	}

	private ViewPager.OnPageChangeListener mViewPagerChangeListener = new ViewPager.OnPageChangeListener() {
		// private boolean isOpaque = true;

		@Override
		public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
			// TODO: old code, but do we need this? (e.g., for lower API versions?)
			/*
			if (position == WELCOME_PAGES - 2 && positionOffset > 0) {
				if (isOpaque) {
					mViewPager.setBackgroundColor(Color.TRANSPARENT);
					isOpaque = false;
				}
			} else {
				if (!isOpaque) {
					mViewPager.setBackgroundColor(getResources().getColor(R.color.android_material_grey_100));
					isOpaque = true;
				}
			}
			*/
		}

		@Override
		public void onPageSelected(int position) {
			setIndicator(position);
			switch (position) {
				case 0: // welcome
					mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.ALL);
					mSkipButton.setVisibility(View.VISIBLE);
					mPreviousButton.setVisibility(View.GONE);
					mNextButton.setVisibility(View.VISIBLE);
					mDoneButton.setVisibility(View.GONE);
					break;

				case 1: // connect with initial pin
					if (mChangePinMode) { // this only happens on screen rotation - see above for initial setup
						mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
						mSkipButton.setVisibility(View.GONE);
					} else {
						mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.LEFT);
						mSkipButton.setVisibility(View.VISIBLE);
					}
					mPreviousButton.setVisibility(View.GONE);
					mNextButton.setVisibility(View.VISIBLE);
					mDoneButton.setVisibility(View.GONE);

					if (mLayoutManipulated) { // when this is called after onCreate on rotation - need to redo changes
						mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
						findViewById(R.id.welcome_screen_initial_connect).setVisibility(View.GONE);
						findViewById(R.id.initial_pod_search_indicator).setVisibility(View.VISIBLE);
						Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, false);
					}
					break;

				case 2: // set new pin
					mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
					mSkipButton.setVisibility(View.GONE);
					mPreviousButton.setVisibility(View.GONE);
					mNextButton.setVisibility(View.VISIBLE);
					mDoneButton.setVisibility(View.GONE);
					break;

				case 3: // confirm new pin
					mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.LEFT);
					mSkipButton.setVisibility(View.GONE);
					mPreviousButton.setVisibility(View.VISIBLE);
					mNextButton.setVisibility(View.VISIBLE);
					mDoneButton.setVisibility(View.GONE);

					if (mLayoutManipulated) { // when this is called after onCreate on rotation - need to redo changes
						mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
						findViewById(R.id.welcome_screen_confirm_pin).setVisibility(View.GONE);
						findViewById(R.id.pod_pin_change_indicator).setVisibility(View.VISIBLE);
						mPreviousButton.setVisibility(View.INVISIBLE); // invisible not gone so we still benefit from
						// their
						// height
						mNextButton.setVisibility(View.INVISIBLE);
						Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, false);
					}
					break;

				case 4: // choose sync settings
					mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.RIGHT);
					mNextButton.setVisibility(View.VISIBLE);
					mSkipButton.setVisibility(View.GONE);
					mPreviousButton.setVisibility(View.GONE);
					mDoneButton.setVisibility(View.GONE);
					break;

				case 5: // finished
					mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.LEFT);
					mSkipButton.setVisibility(View.GONE);
					mPreviousButton.setVisibility(View.VISIBLE);
					mNextButton.setVisibility(View.GONE);
					mDoneButton.setVisibility(View.VISIBLE);
					break;

				default:
					break;
			}

			// make sure we have set up PIN entry correctly
			if (position == 1 || position == 2 || position == 3) {
				int viewId = -1;
				switch (position) {
					case 1:
						viewId = R.id.welcome_connect_initial_pin;
						break;
					case 2:
						viewId = R.id.welcome_connect_change_pin;
						break;
					case 3:
						viewId = R.id.welcome_connect_change_pin_confirmation;
						break;
					default:
						break;
				}
				mPodConnectionPin = findViewById(viewId);
				if (mPodConnectionPin != null) {
					mPodConnectionPin.setText("");
					mPodConnectionPin.setError(null);
					mPodConnectionPin.setOnEditorActionListener(mPinListener);
					mPodConnectionPin.addTextChangedListener(mPinWatcher);
					if (!mLayoutManipulated) {
						Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, true);
					}
				}
			} else {
				Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, false);
			}

			if (position == 1) {
				if (ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
						.ACCESS_COARSE_LOCATION) !=
						PackageManager.PERMISSION_GRANTED) {
					if (ActivityCompat.shouldShowRequestPermissionRationale(WelcomeActivity.this, Manifest.permission
							.ACCESS_COARSE_LOCATION)) {
						Toast.makeText(WelcomeActivity.this, R.string.permission_access_coarse_location_rationale,
								Toast.LENGTH_LONG)
								.show();
					}
					ActivityCompat.requestPermissions(WelcomeActivity.this, new String[]{
							Manifest.permission.ACCESS_COARSE_LOCATION
					}, PERMISSION_COARSE_LOCATION);
				}
			}

			if (position == 4) {
				mSyncPhotosCheckBox = findViewById(R.id.welcome_sync_photos);
				mSyncSMSCheckBox = findViewById(R.id.welcome_sync_sms);
				mSyncContactsCheckBox = findViewById(R.id.welcome_sync_contacts);

				mSyncPhotosCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
					@Override
					public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
						if (isChecked &&
								ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
										.WRITE_EXTERNAL_STORAGE) !=
										PackageManager.PERMISSION_GRANTED) {
							if (ActivityCompat.shouldShowRequestPermissionRationale(WelcomeActivity.this, Manifest
									.permission.WRITE_EXTERNAL_STORAGE)) {
								Toast.makeText(WelcomeActivity.this, R.string.permission_write_storage_rationale,
										Toast.LENGTH_LONG)
										.show();
							}
							ActivityCompat.requestPermissions(WelcomeActivity.this, new String[]{
									Manifest.permission.WRITE_EXTERNAL_STORAGE
							}, PERMISSION_WRITE_STORAGE);
						} else {
							SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences
									(WelcomeActivity.this);
							SharedPreferences.Editor editor = preferences.edit();
							editor.putBoolean(getString(R.string.key_sync_images), isChecked);
							editor.apply();
						}
					}
				});

				// TODO: this currently requests permissions twice if the first attempt is denied
				mSyncSMSCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
					@Override
					public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
						boolean noRead =
								ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
										.READ_SMS) !=
										PackageManager.PERMISSION_GRANTED;
						boolean noSend =
								ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
										.SEND_SMS) !=
										PackageManager.PERMISSION_GRANTED;
						if (isChecked && noRead || noSend) {
							if (ActivityCompat.shouldShowRequestPermissionRationale(WelcomeActivity.this, Manifest
									.permission.READ_SMS) ||
									ActivityCompat.shouldShowRequestPermissionRationale(WelcomeActivity.this, Manifest
											.permission.SEND_SMS)) {
								Toast.makeText(WelcomeActivity.this, R.string.permission_sms_rationale, Toast
										.LENGTH_LONG)
										.show();
							}
							ActivityCompat.requestPermissions(WelcomeActivity.this, new String[]{
									Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS
							}, PERMISSION_SMS);
						} else {
							SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences
									(WelcomeActivity.this);
							SharedPreferences.Editor editor = preferences.edit();
							editor.putBoolean(getString(R.string.key_sync_sms), isChecked);
							editor.apply();
						}
					}
				});

				// TODO: this currently requests permissions twice if the first attempt is denied
				mSyncContactsCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
					@Override
					public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
						boolean noRead =
								ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
										.READ_CONTACTS) !=
										PackageManager.PERMISSION_GRANTED;
						boolean noWrite =
								ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
										.WRITE_CONTACTS) !=
										PackageManager.PERMISSION_GRANTED;
						if (isChecked && noRead || noWrite) {
							if (ActivityCompat.shouldShowRequestPermissionRationale(WelcomeActivity.this, Manifest
									.permission.READ_CONTACTS) ||
									ActivityCompat.shouldShowRequestPermissionRationale(WelcomeActivity.this, Manifest
											.permission.WRITE_CONTACTS)) {
								Toast.makeText(WelcomeActivity.this, R.string.permission_contacts_rationale, Toast
										.LENGTH_LONG)
										.show();
							}
							ActivityCompat.requestPermissions(WelcomeActivity.this, new String[]{
									Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS
							}, PERMISSION_CONTACTS);
						} else {
							SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences
									(WelcomeActivity.this);
							SharedPreferences.Editor editor = preferences.edit();
							editor.putBoolean(getString(R.string.key_sync_contacts), isChecked);
							editor.apply();
						}
					}
				});

				if (SettingsActivity.getShouldSync(WelcomeActivity.this, GalleryActivity.STORAGE_DIRECTORY_NAME)) {
					mSyncPhotosCheckBox.setChecked(true);
				}

				if (SettingsActivity.getShouldSync(WelcomeActivity.this, SMSActivity.STORAGE_DIRECTORY_NAME)) {
					mSyncSMSCheckBox.setChecked(true);
				}

				if (SettingsActivity.getShouldSync(WelcomeActivity.this, ContactsActivity.STORAGE_DIRECTORY_NAME)) {
					mSyncContactsCheckBox.setChecked(true);
				}
			}
		}

		@Override
		public void onPageScrollStateChanged(int state) {
		}
	};

	@Override
	public void onServiceMessageReceived(int type, String data) {
		switch (type) {
			case EVENT_CONNECTION_ROOT_FOLDER_UPDATE:
			case EVENT_CONNECTION_STATUS_UPDATE:
				switch (data) {
					case DATA_POD_CONNECTED:
						mConnectionState = ConnectionState.CONNECTED;
						int currentItem = mViewPager.getCurrentItem();
						if (currentItem == 1) {
							mLayoutManipulated = false;
							mViewPager.setCurrentItem(2); // initial connection
						} else if (currentItem == 3) {
							mLayoutManipulated = false;
							if (mChangePinMode) {
								Toast.makeText(WelcomeActivity.this, R.string.hint_pin_changed, Toast.LENGTH_SHORT)
										.show();
								finish();
							} else {
								mViewPager.setCurrentItem(4); // reconnected after PIN change
							}
						}
						Log.d(TAG, "Pod connected");
						break;

					case PodManagerService.DATA_POD_CONNECTION_DROPPED:
						mConnectionState = ConnectionState.SEARCHING;
						Log.d(TAG, "Pod connection dropped");
						break;

					case PodManagerService.DATA_POD_CONNECTION_FAILURE:
						Log.d(TAG, "Pod connection failed");
						Toast.makeText(WelcomeActivity.this, R.string.hint_error_error_connecting_pod, Toast
								.LENGTH_SHORT)
								.show();
						mConnectionState = ConnectionState.DISCONNECTED;
						mLayoutManipulated = false;
						mViewPager.setCurrentItem(1); // start again

						// need to do this as setCurrentItem doesn't call onPageSelected if on current item
						if (mChangePinMode) {
							mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.NONE);
							mSkipButton.setVisibility(View.GONE);
						} else {
							mViewPager.setAllowedSwipeDirection(CustomSwipeViewPager.SwipeDirection.LEFT);
							mSkipButton.setVisibility(View.VISIBLE);
						}
						mPreviousButton.setVisibility(View.GONE);
						mNextButton.setVisibility(View.VISIBLE);
						mDoneButton.setVisibility(View.GONE);
						findViewById(R.id.welcome_screen_initial_connect).setVisibility(View.VISIBLE);
						findViewById(R.id.initial_pod_search_indicator).setVisibility(View.GONE);
						Utilities.setKeyboardVisibility(WelcomeActivity.this, mPodConnectionPin, true);
						break;

					default:
						Log.d(TAG, "Connection status update: " + data);
						break;
				}
				break;
		}
	}

	private void finishSetup(Activity activity) {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		SharedPreferences.Editor editor = preferences.edit();
		editor.putBoolean(activity.getString(R.string.key_completed_initial_setup), true);
		editor.apply();
		sendServiceMessage(PodManagerService.MSG_DISCONNECT_POD, null);
		activity.setResult(Activity.RESULT_OK);
		activity.finish();
	}

	private void buildCircles() {
		mIndicatorCircles = findViewById(R.id.welcome_activity_indicators);

		float scale = getResources().getDisplayMetrics().density;
		int padding = (int) (5 * scale + 0.5f);

		for (int i = 0; i < WELCOME_PAGES; i++) {
			ImageView circle = new ImageView(this);
			circle.setImageResource(R.drawable.ic_pager_indicator);
			circle.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup
					.LayoutParams.WRAP_CONTENT));
			circle.setAdjustViewBounds(true);
			circle.setPadding(padding, 0, padding, 0);
			mIndicatorCircles.addView(circle);
		}

		setIndicator(0);
	}

	private void setIndicator(int index) {
		if (index < WELCOME_PAGES && mIndicatorCircles != null) {
			for (int i = 0; i < WELCOME_PAGES; i++) {
				ImageView circle = (ImageView) mIndicatorCircles.getChildAt(i);
				if (i == index) {
					circle.setColorFilter(android.R.color.white);
				} else {
					circle.setColorFilter(getResources().getColor(R.color.pagerIndicator));
				}
			}
		}
	}

	private class ScreenSlideAdapter extends FragmentStatePagerAdapter {
		ScreenSlideAdapter(FragmentManager fragmentManager) {
			super(fragmentManager);
		}

		@Override
		public Fragment getItem(int position) {
			WelcomeFragment welcomeFragment = null;
			switch (position) {
				case 0:
					welcomeFragment = WelcomeFragment.newInstance(R.layout.fragment_welcome_0, mChangePinMode);
					break;
				case 1:
					welcomeFragment = WelcomeFragment.newInstance(R.layout.fragment_welcome_1, mChangePinMode);
					break;
				case 2:
					welcomeFragment = WelcomeFragment.newInstance(R.layout.fragment_welcome_2, mChangePinMode);
					break;
				case 3:
					welcomeFragment = WelcomeFragment.newInstance(R.layout.fragment_welcome_3, mChangePinMode);
					break;
				case 4:
					welcomeFragment = WelcomeFragment.newInstance(R.layout.fragment_welcome_4, mChangePinMode);
					break;
				case 5:
					welcomeFragment = WelcomeFragment.newInstance(R.layout.fragment_welcome_5, mChangePinMode);
					break;
				default:
					break;
			}

			return welcomeFragment;
		}

		@Override
		public int getCount() {
			return WELCOME_PAGES;
		}
	}

	// animate transforms between pages
	private static class CrossFadePageTransformer implements ViewPager.PageTransformer {
		@Override
		public void transformPage(@NonNull View page, float position) {
			int pageWidth = page.getWidth();

			View background = page.findViewById(R.id.welcome_fragment);
			View image = page.findViewById(R.id.welcome_screen_top_icon);
			View heading = page.findViewById(R.id.welcome_screen_heading);
			View content = page.findViewById(R.id.welcome_screen_description);

			if (0 <= position && position < 1) {
				page.setTranslationX(pageWidth * -position);
			}
			if (-1 < position && position < 0) {
				page.setTranslationX(pageWidth * -position);
			}

			if (-1 < position && position != 0 && position < 1) {
				if (background != null) {
					background.setAlpha(1.0f - Math.abs(position));
				}

				if (image != null) {
					image.setTranslationX(pageWidth * position);
					image.setAlpha(1.0f - Math.abs(position));
				}

				if (heading != null) {
					heading.setTranslationX(pageWidth * position);
					heading.setAlpha(1.0f - Math.abs(position));
				}

				if (content != null) {
					content.setTranslationX(pageWidth * position);
					content.setAlpha(1.0f - Math.abs(position));
				}
			}
		}
	}

	// TODO: share this and other permissions-related code with SettingsActivity and MainActivity
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[]
			grantResults) {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(WelcomeActivity.this);
		SharedPreferences.Editor editor = preferences.edit();

		switch (requestCode) {
			case PERMISSION_WRITE_STORAGE:
				boolean photosGranted = false;
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					photosGranted = true;
				} else {
					Toast.makeText(WelcomeActivity.this, R.string.permission_write_storage_error, Toast.LENGTH_LONG)
							.show();
					if (mSyncPhotosCheckBox != null) {
						mSyncPhotosCheckBox.setChecked(false);
					}
				}
				editor.putBoolean(getString(R.string.key_sync_images), photosGranted);
				break;

			case PERMISSION_SMS:
				// TODO: could use grantResults but docs say permissions request can be interrupted and change length
				boolean noReadSMS =
						ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission.READ_SMS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean noSendSMS =
						ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission.SEND_SMS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean smsGranted = !(noReadSMS || noSendSMS);
				if (!smsGranted) {
					Toast.makeText(WelcomeActivity.this, R.string.permission_sms_error, Toast.LENGTH_LONG).show();
					if (mSyncSMSCheckBox != null) {
						mSyncSMSCheckBox.setChecked(false);
					}
				}
				editor.putBoolean(getString(R.string.key_sync_sms), smsGranted);
				break;

			case PERMISSION_CONTACTS:
				// TODO: could use grantResults but docs say permissions request can be interrupted and change length
				boolean noReadContacts =
						ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission.READ_CONTACTS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean noWriteContacts =
						ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission.WRITE_CONTACTS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean contactsGranted = !(noReadContacts || noWriteContacts);
				if (!contactsGranted) {
					Toast.makeText(WelcomeActivity.this, R.string.permission_contacts_error, Toast.LENGTH_LONG).show();
					if (mSyncContactsCheckBox != null) {
						mSyncContactsCheckBox.setChecked(false);
					}
				}
				editor.putBoolean(getString(R.string.key_sync_contacts), contactsGranted);
				break;

			case PERMISSION_COARSE_LOCATION:
				if (ContextCompat.checkSelfPermission(WelcomeActivity.this, Manifest.permission
						.ACCESS_COARSE_LOCATION) !=
						PackageManager.PERMISSION_GRANTED) {
					Toast.makeText(WelcomeActivity.this, R.string.permission_access_coarse_location_error, Toast
							.LENGTH_LONG)
							.show();
					finish();
				}
				break;

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}

		editor.apply();
	}
}
