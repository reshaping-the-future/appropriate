package ac.robinson.pod.browsers;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import ac.robinson.pod.BaseBrowserActivity;
import ac.robinson.pod.R;
import ac.robinson.pod.SettingsActivity;
import ac.robinson.pod.Utilities;
import ac.robinson.pod.listeners.FileProcessedResponseListener;
import ac.robinson.pod.models.ContactModel;
import ac.robinson.pod.service.PodManagerService;

public class ContactsActivity extends BaseBrowserActivity {
	// TODO: this, getFileProviderUri, getFilesDirectoryForData and getVisitorSyncPath have a lot of duplication
	public static final String STORAGE_DIRECTORY_NAME = "people"; // on both Pod and, where applicable, local phone
	public static final String TAG = "ContactsActivity";
	private static final int PERMISSION_CALL_PHONE = 105;

	@NonNull
	@Override
	public ArrayList<String> getBackupItems(Context context, int rowLimit) {
		LinkedHashMap<String, ContactModel> contacts = getContacts(context);

		File outputFile = new File(getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME),
				STORAGE_DIRECTORY_NAME + PodManagerService.POD_CONFIGURATION_FILE_NAME);
		FileOutputStream fileOutputStream = null;
		// context.openFileOutput(STORAGE_DIRECTORY_NAME + PodManagerService.POD_CONFIGURATION_FILE_NAME, Context
		// .MODE_PRIVATE);
		try {
			fileOutputStream = new FileOutputStream(outputFile);
			ContactModel.writeContactList(fileOutputStream, contacts);
		} catch (IOException e) {
			Log.d(TAG, "Unable to save contacts - permission or storage error?");
		} finally {
			Utilities.closeStream(fileOutputStream);
		}

		ArrayList<String> fileList = new ArrayList<>();
		fileList.add(outputFile.getAbsolutePath());
		return fileList;
	}

	@Override
	public void processNewFile(Context context, File newFile, FileProcessedResponseListener responseListener) {
		LinkedHashMap<String, ContactModel> contactItems = loadContacts(newFile.getAbsolutePath());
		ArrayList<ContentProviderOperation> contactOperations = new ArrayList<>();
		boolean addingContacts = false;

		for (Map.Entry<String, ContactModel> entry : contactItems.entrySet()) {
			ContactModel currentContact = entry.getValue();
			if (currentContact.mContactEdited) {
				addingContacts = true;
				int rawContactInsertIndex = contactOperations.size();
				contactOperations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
						.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
						.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
						.build());
				contactOperations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
						.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
						.withValue(ContactsContract.Contacts.Data.MIMETYPE, ContactsContract.CommonDataKinds
								.StructuredName.CONTENT_ITEM_TYPE)
						.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, currentContact.mName)
						.build());
				contactOperations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
						.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
						.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone
								.CONTENT_ITEM_TYPE)
						.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, currentContact.mMobileNumbers.get(0))
						.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone
								.TYPE_MOBILE)
						.build()); // type of number - just default to mobile for now
			}
		}

		if (addingContacts) {
			try {
				ContentProviderResult[] results = context.getContentResolver()
						.applyBatch(ContactsContract.AUTHORITY, contactOperations);

				Log.d(TAG, "Importing new contacts");
				for (ContentProviderResult result : results) {
					Log.d(TAG, "Importing contact item: " + result);
				}
			} catch (RemoteException | OperationApplicationException ignored) {
				Log.e(TAG, "Contact importing error"); // nothing we can do
			}
		}

		responseListener.onResponse();
	}

	@Override
	public void onSessionFinished(Context context) {
		// nothing to do here (yet)
	}

	@Override
	public String getLocalSyncPath(Context context) {
		// TODO: would getDataType work here? (just duplication otherwise, but don't have context when via reflection)
		return BaseBrowserActivity.getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME);
	}

	/*
	--------------------------------------------------------------------------------------------------------------------
	--------------------------------------------------------------------------------------------------------------------
	 */

	private static final int CAPTURE_CONTACT = 202;

	private ArrayList<String> mMediaItems;
	private ListAdapter mListAdapter;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initialiseViewAndToolbar(R.layout.browser_filterable_list);

		mMediaItems = getMediaItemList();
		mListAdapter = new ListAdapter(mMediaItems);
		ListView listView = findViewById(R.id.list_view);
		listView.setEmptyView(findViewById(R.id.empty_list_view));
		listView.setAdapter(mListAdapter);
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				mListAdapter.onClick(position);
			}
		});

		((TextView) findViewById(R.id.list_filter)).addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				mListAdapter.getFilter().filter(s);
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (isVisitor()) {
			// only visitors can add new items (own is via normal apps)
			getMenuInflater().inflate(R.menu.menu_contact, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_add_contact:
				Intent launchIntent = new Intent(ContactsActivity.this, ContactAddActivity.class);
				// launchIntent.putExtra(ContactAddActivity.CONTACT_FILE, mMediaItems.get(0));
				//hacky! (but we need to be able to handle the case where there are no contacts)
				File contactsFile = new File(getVisitorSyncPath(ContactsActivity.this, getVisitorId(), ContactsActivity
						.STORAGE_DIRECTORY_NAME),
						ContactsActivity.STORAGE_DIRECTORY_NAME + PodManagerService.POD_CONFIGURATION_FILE_NAME);
				launchIntent.putExtra(ContactAddActivity.CONTACT_FILE, contactsFile.getAbsolutePath());
				startActivityForResult(launchIntent, CAPTURE_CONTACT);
				break;

			default:
				break;
		}
		return super.onOptionsItemSelected(item);
	}

	public LinkedHashMap<String, ContactModel> getContacts(Context context) {
		LinkedHashMap<String, ContactModel> contactMapList = new LinkedHashMap<>();
		ContentResolver contentResolver = context.getContentResolver();

		Cursor cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, new String[]{
				ContactsContract.Contacts._ID,
				ContactsContract.Contacts.DISPLAY_NAME,
				ContactsContract.Contacts.HAS_PHONE_NUMBER
		}, null, null, ContactsContract.Contacts.DISPLAY_NAME + " ASC");
		if (cursor != null) {
			int idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID);
			while (cursor.moveToNext()) {
				String id = cursor.getString(idIndex);
				if (cursor.getInt(cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0) {
					Cursor cursorInfo = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, new
							String[]{
							ContactsContract.CommonDataKinds.Phone.NUMBER
					}, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[]{ id }, null);
					if (cursorInfo != null) {
						int numberIndex = cursorInfo.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
						int nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
						while (cursorInfo.moveToNext()) {
							ContactModel contact = contactMapList.get(id);
							String mobileNumber = getNormalisedPhoneNumber(context, cursorInfo.getString(numberIndex));
							if (contact != null) {
								if (!contact.mMobileNumbers.contains(mobileNumber)) {
									contact.mMobileNumbers.add(mobileNumber);
								}
							} else {
								contact = new ContactModel();
								// not technically needed here yet, but will be useful for future two-way sync
								contact.mId = id;
								contact.mName = cursor.getString(nameIndex);
								contact.mMobileNumbers.add(mobileNumber);
								contactMapList.put(id, contact);
							}
						}
						cursorInfo.close();
					}
				}
			}
			cursor.close();
		}

		return contactMapList;
	}

	public static String getNormalisedPhoneNumber(Context context, String number) {
		TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		String countryCode = telephonyManager.getSimCountryIso();

		if (TextUtils.isEmpty(countryCode)) {
			Log.d(TAG, "Unable to detect country code - using default number format");
			return number;
		}
		countryCode = countryCode.toUpperCase();
		Log.d(TAG, "Country code: " + countryCode);

		PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
		try {
			Phonenumber.PhoneNumber phoneNumber = phoneUtil.parse(number, countryCode);
			String normalisedNumber = phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);
			Log.d(TAG, "Normalising: " + number + " to " + normalisedNumber);
			return normalisedNumber;
		} catch (NumberParseException e) {
			Log.d(TAG, "NumberParseException: " + number + " error: " + e);
		}
		return number;
	}

	public static String getContactName(Context context, String phoneNumber) {
		ContentResolver contentResolver = context.getContentResolver();
		Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
		Cursor cursor = contentResolver.query(uri, new String[]{ ContactsContract.PhoneLookup.DISPLAY_NAME }, null,
				null, null);
		if (cursor != null) {
			if (cursor.moveToFirst()) {
				return cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME));
			}
			cursor.close();
		}
		return null;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case CAPTURE_CONTACT:
				if (resultCode == BaseBrowserActivity.MEDIA_UPDATED) {
					mMediaItems = getMediaItemList();
					mListAdapter.setData(mMediaItems);
					mListAdapter.notifyDataSetChanged();
					setHasEdited(true);
				}
				break;

			default:
				break;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	public static LinkedHashMap<String, ContactModel> loadContacts(String filePath) {
		FileInputStream fileInputStream = null;
		try {
			fileInputStream = new FileInputStream(filePath);
			return ContactModel.readContactList(fileInputStream);
		} catch (Exception e) {
			Log.d(TAG, "Unable to load contacts - storage or unpacking error?");
		} finally {
			Utilities.closeStream(fileInputStream);
		}
		return new LinkedHashMap<>();
	}

	private class ListAdapter extends BaseAdapter implements Filterable {
		private final ArrayList<ContactModel> mItems;
		private ArrayList<ContactModel> mFilteredItems;

		private ListAdapter(ArrayList<String> contacts) {
			mItems = new ArrayList<>();
			mFilteredItems = null;
			setData(contacts);
		}

		private void setData(ArrayList<String> contacts) {
			mItems.clear();
			if (contacts.size() > 0) {
				LinkedHashMap<String, ContactModel> items = loadContacts(contacts.get(0));
				ContactModel[] itemsArray = new ContactModel[items.size()];
				items.values().toArray(itemsArray);
				mItems.addAll(Arrays.asList(itemsArray));
			}
		}

		@Override
		public int getCount() {
			return mFilteredItems == null ? mItems.size() : mFilteredItems.size();
		}

		@Override
		public Object getItem(int position) {
			return mFilteredItems == null ? mItems.get(position) : mFilteredItems.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			ContactViewHolder viewHolder;
			if (view == null) {
				view = LayoutInflater.from(parent.getContext())
						.inflate(R.layout.item_contact_with_image, parent, false);
				viewHolder = new ContactViewHolder();
				viewHolder.contact = view.findViewById(R.id.contact_name);
				view.setTag(viewHolder);
			} else {
				viewHolder = (ContactViewHolder) view.getTag();
			}
			ContactModel currentContact = (ContactModel) getItem(position);
			viewHolder.contact.setText(currentContact.mName);
			return view;
		}

		void onClick(int position) {
			final ContactModel currentContact = (ContactModel) getItem(position);

			AlertDialog.Builder dialog = new AlertDialog.Builder(ContactsActivity.this);
			dialog.setTitle(currentContact.mName);
			dialog.setPositiveButton(R.string.menu_done, null);

			final boolean hasSim = SettingsActivity.getHasSIM(ContactsActivity.this);
			ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(ContactsActivity.this, R.layout.item_phone_number);
			boolean visitorWithSIM = hasSim && isVisitor();
			for (String number : currentContact.mMobileNumbers) {
				if (visitorWithSIM) {
					arrayAdapter.add("Call " + number);
				}
			}
			dialog.setAdapter(arrayAdapter, !isVisitor() ? null : new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					// only phones with SIMs can call
					if (hasSim) {
						String number = currentContact.mMobileNumbers.get((int) Math.floor(which / 2));
						Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
						if (ContextCompat.checkSelfPermission(ContactsActivity.this, Manifest.permission
								.CALL_PHONE) !=
								PackageManager.PERMISSION_GRANTED) {

							if (ActivityCompat.shouldShowRequestPermissionRationale(ContactsActivity.this,
									Manifest.permission.CALL_PHONE)) {
								Toast.makeText(ContactsActivity.this, R.string.permission_call_phone_rationale,
										Toast.LENGTH_SHORT)
										.show();
							}
							ActivityCompat.requestPermissions(ContactsActivity.this, new String[]{
									Manifest.permission.CALL_PHONE
							}, PERMISSION_CALL_PHONE);
						} else {
							try {
								startActivity(intent);
							} catch (Exception ignored) {
								Toast.makeText(ContactsActivity.this, R.string.permission_call_phone_error, Toast
										.LENGTH_SHORT)
										.show(); // TODO: this is not necessarily permission related
							}
						}
					}
				}
			});
			dialog.show();
		}

		@Override
		public Filter getFilter() {
			return new Filter() {
				@Override
				protected FilterResults performFiltering(CharSequence constraint) {
					FilterResults result = new FilterResults();
					if (constraint == null || constraint.length() == 0) {
						result.values = mItems;
						result.count = mItems.size();
					} else {
						ArrayList<ContactModel> filteredList = new ArrayList<>();
						String search = constraint.toString().toLowerCase();
						for (ContactModel contactModel : mItems) {
							if (contactModel.mName.toLowerCase().contains(search)) {
								filteredList.add(contactModel);
							}
						}
						result.values = filteredList;
						result.count = filteredList.size();
					}
					return result;
				}

				@SuppressWarnings("unchecked")
				@Override
				protected void publishResults(CharSequence constraint, FilterResults results) {
					if (results.count == 0) {
						notifyDataSetInvalidated();
					} else {
						mFilteredItems = (ArrayList<ContactModel>) results.values;
						notifyDataSetChanged();
					}
				}
			};
		}
	}

	private static class ContactViewHolder {
		TextView contact;
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[]
			grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		switch (requestCode) {
			case PERMISSION_CALL_PHONE:
				if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					Toast.makeText(ContactsActivity.this, R.string.permission_call_phone_error, Toast.LENGTH_SHORT)
							.show();
				}
				break;

			default:
				break;
		}
	}
}
