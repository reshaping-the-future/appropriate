package ac.robinson.pod;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.Toolbar;
import android.telephony.TelephonyManager;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import ac.robinson.pod.browsers.ContactsActivity;
import ac.robinson.pod.browsers.GalleryActivity;
import ac.robinson.pod.browsers.SMSActivity;
import ac.robinson.pod.service.PodManagerServiceCommunicator;

import static ac.robinson.pod.R.xml.preferences;

public class SettingsActivity extends PreferenceActivity implements OnPreferenceChangeListener,
		PodManagerServiceCommunicator.HotspotServiceCallback {

	private PodManagerServiceCommunicator mServiceCommunicator;
	private AppCompatDelegate mDelegate;

	private static final int PERMISSION_WRITE_STORAGE = 102;
	private static final int PERMISSION_SMS = 103;
	private static final int PERMISSION_CONTACTS = 104;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		getDelegate().installViewFactory();
		getDelegate().onCreate(savedInstanceState);
		super.onCreate(savedInstanceState);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		mServiceCommunicator = new PodManagerServiceCommunicator(SettingsActivity.this);
		mServiceCommunicator.bindService(SettingsActivity.this);

		setupPreferences();
	}

	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		getDelegate().onPostCreate(savedInstanceState);
	}

	public ActionBar getSupportActionBar() {
		return getDelegate().getSupportActionBar();
	}

	@SuppressWarnings("unused")
	public void setSupportActionBar(@Nullable Toolbar toolbar) {
		getDelegate().setSupportActionBar(toolbar);
	}

	@NonNull
	@Override
	public MenuInflater getMenuInflater() {
		return getDelegate().getMenuInflater();
	}

	@Override
	public void setContentView(@LayoutRes int layoutResID) {
		getDelegate().setContentView(layoutResID);
	}

	@Override
	public void setContentView(View view) {
		getDelegate().setContentView(view);
	}

	@Override
	public void setContentView(View view, ViewGroup.LayoutParams params) {
		getDelegate().setContentView(view, params);
	}

	@Override
	public void addContentView(View view, ViewGroup.LayoutParams params) {
		getDelegate().addContentView(view, params);
	}

	@Override
	protected void onPostResume() {
		super.onPostResume();
		getDelegate().onPostResume();
	}

	@Override
	protected void onTitleChanged(CharSequence title, int color) {
		super.onTitleChanged(title, color);
		getDelegate().setTitle(title);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		getDelegate().onConfigurationChanged(newConfig);
	}

	@Override
	protected void onStop() {
		super.onStop();
		getDelegate().onStop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		getDelegate().onDestroy();
		if (mServiceCommunicator != null) {
			// don't kill service if just rotating the screen
			mServiceCommunicator.unbindService(SettingsActivity.this, !isFinishing());
		}
	}

	@SuppressWarnings("unused")
	public void sendServiceMessage(int type, String data) {
		mServiceCommunicator.sendServiceMessage(type, data);
	}

	@Override
	public void onServiceMessageReceived(int type, String data) {
		/*
		switch (type) {
			case PodManagerService.EVENT_CONNECTION_STATUS_UPDATE:
				switch (data) {
					case PodManagerService.DATA_POD_CONNECTED:
					case PodManagerService.DATA_POD_CONNECTION_DROPPED:
					case PodManagerService.DATA_POD_CONNECTION_FAILURE:
					default:
						break;
				}

			default:
				break;
		}
		*/
	}

	public void invalidateOptionsMenu() {
		getDelegate().invalidateOptionsMenu();
	}

	private AppCompatDelegate getDelegate() {
		if (mDelegate == null) {
			mDelegate = AppCompatDelegate.create(this, null);
		}
		return mDelegate;
	}

	public static boolean getVisitorOnlyMode(Context context) {
		// in some contexts, it is useful to have a version of the app that doesn't act like it has its own
		// device to synchronise to - a "visitor-only" mode of the app: set on first-run by, e.g.:
		// 	SharedPreferences.Editor prefsEditor = preferences.edit();
		// 	prefsEditor.putBoolean(context.getString(R.string.key_visitor_only_mode), true);
		// 	prefsEditor.apply();
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		return preferences.getBoolean(context.getString(R.string.key_visitor_only_mode), false);
	}

	public static int getOwnPodPin(Context context) {
		// if -1 they haven't configured a Pod
		int ownPodPin;
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		try {
			ownPodPin = Integer.valueOf(preferences.getString(context.getString(R.string.key_own_pod_pin), "-1"));
		} catch (NumberFormatException e) {
			ownPodPin = -1; // may not happen, but just in case they get through integer checking in setup activity
		}
		return ownPodPin;
	}

	public static int getItemSyncLimit(Context context) {
		TypedValue resourceValue = new TypedValue();
		context.getResources().getValue(R.dimen.default_item_sync_count, resourceValue, true);
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		return (int) sharedPreferences.getFloat(context.getString(R.string.key_sync_item_count), resourceValue
				.getFloat());
	}

	public static boolean getShouldSync(Context context, String mediaType) {
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		switch (mediaType) {
			case GalleryActivity.STORAGE_DIRECTORY_NAME:
				return sharedPreferences.getBoolean(context.getString(R.string.key_sync_images), false) &&
						ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
								PackageManager.PERMISSION_GRANTED;
			case ContactsActivity.STORAGE_DIRECTORY_NAME:
				return sharedPreferences.getBoolean(context.getString(R.string.key_sync_contacts), false) &&
						ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
								PackageManager.PERMISSION_GRANTED &&
						ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
								PackageManager.PERMISSION_GRANTED;
			case SMSActivity.STORAGE_DIRECTORY_NAME:
				return sharedPreferences.getBoolean(context.getString(R.string.key_sync_sms), false) &&
						ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
								PackageManager.PERMISSION_GRANTED &&
						ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
								PackageManager.PERMISSION_GRANTED;
			default:
				return false;
		}
	}

	public static boolean getHasSIM(Context context) {
		TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		return telephonyManager != null && telephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_save, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
			case R.id.menu_save_pod_setup:
				finish();
				return true;

			default:
				break;
		}
		return super.onOptionsItemSelected(item);
	}

	private void setupPreferences() {
		addPreferencesFromResource(preferences);

		int[] preferencesRequiringPermissions = {
				R.string.key_sync_images, R.string.key_sync_sms, R.string.key_sync_contacts
		};
		for (int preferenceKey : preferencesRequiringPermissions) {
			SwitchPreference preference = (SwitchPreference) findPreference(getString(preferenceKey));
			preference.setOnPreferenceChangeListener(SettingsActivity.this);
			if (preference.isChecked()) {
				onPreferenceChange(preference, Boolean.TRUE);
			}
		}

		Intent pinIntent = new Intent(SettingsActivity.this, WelcomeActivity.class);
		pinIntent.putExtra(WelcomeActivity.CHANGE_PIN_MODE, true);
		findPreference(getString(R.string.key_own_pod_pin)).setIntent(pinIntent);

		Intent backgroundIntent = new Intent(SettingsActivity.this, ChangeBackgroundActivity.class);
		findPreference(getString(R.string.key_own_pod_background)).setIntent(backgroundIntent);
	}

	@Override
	public boolean onPreferenceChange(Preference preference, Object o) {
		final String key = preference.getKey();
		if (getString(R.string.key_sync_images).equals(key) && (Boolean) o) {
			if (ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
					PackageManager.PERMISSION_GRANTED) {
				if (ActivityCompat.shouldShowRequestPermissionRationale(SettingsActivity.this, Manifest.permission
						.WRITE_EXTERNAL_STORAGE)) {
					Toast.makeText(SettingsActivity.this, R.string.permission_write_storage_rationale, Toast
							.LENGTH_LONG)
							.show();
				}
				ActivityCompat.requestPermissions(SettingsActivity.this, new String[]{
						Manifest.permission.WRITE_EXTERNAL_STORAGE
				}, PERMISSION_WRITE_STORAGE);
			}

		} else if (getString(R.string.key_sync_sms).equals(key) && (Boolean) o) {
			if (ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.READ_SMS) !=
					PackageManager.PERMISSION_GRANTED ||
					ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.SEND_SMS) !=
							PackageManager.PERMISSION_GRANTED) {
				if (ActivityCompat.shouldShowRequestPermissionRationale(SettingsActivity.this, Manifest.permission
						.READ_SMS) ||
						ActivityCompat.shouldShowRequestPermissionRationale(SettingsActivity.this, Manifest.permission
								.SEND_SMS)) {
					Toast.makeText(SettingsActivity.this, R.string.permission_sms_rationale, Toast.LENGTH_LONG).show();
				}
				ActivityCompat.requestPermissions(SettingsActivity.this, new String[]{
						Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS
				}, PERMISSION_SMS);
			}

		} else if (getString(R.string.key_sync_contacts).equals(key) && (Boolean) o) {
			if (ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.READ_CONTACTS) !=
					PackageManager.PERMISSION_GRANTED ||
					ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.WRITE_CONTACTS) !=
							PackageManager.PERMISSION_GRANTED) {
				if (ActivityCompat.shouldShowRequestPermissionRationale(SettingsActivity.this, Manifest.permission
						.READ_CONTACTS) ||
						ActivityCompat.shouldShowRequestPermissionRationale(SettingsActivity.this, Manifest.permission
								.WRITE_CONTACTS)) {
					Toast.makeText(SettingsActivity.this, R.string.permission_contacts_rationale, Toast.LENGTH_LONG)
							.show();
				}
				ActivityCompat.requestPermissions(SettingsActivity.this, new String[]{
						Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS
				}, PERMISSION_CONTACTS);
			}
		}

		return true;
	}

	// TODO: share this and other permissions-related code with WelcomeActivity and MainActivity
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[]
			grantResults) {
		switch (requestCode) {
			case PERMISSION_WRITE_STORAGE:
				if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					Toast.makeText(SettingsActivity.this, R.string.permission_write_storage_error, Toast.LENGTH_LONG)
							.show();
					SwitchPreference preference = (SwitchPreference) findPreference(getString(R.string
							.key_sync_images));
					preference.setChecked(false);
				}
				break;

			case PERMISSION_SMS:
				// TODO: could use grantResults but docs say permissions request can be interrupted and change length
				boolean noReadSMS =
						ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.READ_SMS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean noSendSMS =
						ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.SEND_SMS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean smsGranted = !(noReadSMS || noSendSMS);
				if (!smsGranted) {
					Toast.makeText(SettingsActivity.this, R.string.permission_sms_error, Toast.LENGTH_LONG).show();
					SwitchPreference preference = (SwitchPreference) findPreference(getString(R.string.key_sync_sms));
					preference.setChecked(false);
				}
				break;

			case PERMISSION_CONTACTS:
				// TODO: could use grantResults but docs say permissions request can be interrupted and change length
				boolean noReadContacts =
						ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.READ_CONTACTS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean noWriteContacts =
						ContextCompat.checkSelfPermission(SettingsActivity.this, Manifest.permission.WRITE_CONTACTS) !=
								PackageManager.PERMISSION_GRANTED;
				boolean contactsGranted = !(noReadContacts || noWriteContacts);
				if (!contactsGranted) {
					Toast.makeText(SettingsActivity.this, R.string.permission_contacts_error, Toast.LENGTH_LONG)
							.show();
					SwitchPreference preference = (SwitchPreference) findPreference(getString(R.string
							.key_sync_contacts));
					preference.setChecked(false);
				}
				break;

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}
	}
}
