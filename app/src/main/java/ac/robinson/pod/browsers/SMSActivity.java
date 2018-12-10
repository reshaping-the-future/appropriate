package ac.robinson.pod.browsers;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ac.robinson.pod.BaseBrowserActivity;
import ac.robinson.pod.R;
import ac.robinson.pod.Utilities;
import ac.robinson.pod.listeners.FileProcessedResponseListener;
import ac.robinson.pod.models.SmsMessage;
import ac.robinson.pod.models.SmsModel;
import ac.robinson.pod.service.PodManagerService;

public class SMSActivity extends BaseBrowserActivity {
	// TODO: this, getFileProviderUri, getFilesDirectoryForData and getVisitorSyncPath have a lot of duplication
	public static final String STORAGE_DIRECTORY_NAME = "sms"; // on both Pod and, where applicable, local phone
	public static final String TAG = "SMSActivity";

	@NonNull
	@Override
	public ArrayList<String> getBackupItems(Context context, int rowLimit) {
		LinkedHashMap<String, SmsModel> smsThreads = getSms(context, rowLimit);

		File outputFile = new File(getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME),
				STORAGE_DIRECTORY_NAME + PodManagerService.POD_CONFIGURATION_FILE_NAME);
		FileOutputStream fileOutputStream = null;
		// context.openFileOutput(STORAGE_DIRECTORY_NAME + PodManagerService.POD_CONFIGURATION_FILE_NAME, Context
		// .MODE_PRIVATE);
		try {
			fileOutputStream = new FileOutputStream(outputFile);
			SmsModel.writeSmsList(fileOutputStream, smsThreads);
		} catch (IOException e) {
			Log.d(TAG, "Unable to save sms - permission or storage error?");
		} finally {
			Utilities.closeStream(fileOutputStream);
		}

		ArrayList<String> fileList = new ArrayList<>();
		fileList.add(outputFile.getAbsolutePath());
		return fileList;
	}

	@Override
	public void processNewFile(Context context, File newFile, FileProcessedResponseListener responseListener) {
		LinkedHashMap<String, SmsModel> smsThreads = loadSmsMessages(newFile.getAbsolutePath());
		for (Map.Entry<String, SmsModel> entry : smsThreads.entrySet()) {
			SmsModel currentThread = entry.getValue();
			for (SmsMessage message : currentThread.mMessages) {
				if (message.mShouldImportMessage) {
					Log.d(TAG, "Found message to import to " + currentThread.mContactNumber);

					if (!message.mMessageSent) {
						SMSComposeActivity.sendSMS(context, currentThread.mContactNumber, message.mMessage, false);
						Log.d(TAG, "Sent messsage");
					}

					// before KitKat, we must insert into the SMS database manually (after KitKat this isn't
					// possible, so messages sent on visitor phones are lost :-( )
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
						ContentValues values = new ContentValues();
						values.put("address", currentThread.mContactNumber);
						values.put("body", message.mMessage);
						values.put("read", 1);
						values.put("date", message.mMessageDate);
						context.getContentResolver().insert(Uri.parse("content://sms/sent"), values);
						Log.d(TAG, "Added message to database");
					}
				}
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
		return BaseBrowserActivity.getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME);
	}

	public LinkedHashMap<String, SmsModel> getSms(Context context, int rowLimit) {
		LinkedHashMap<String, SmsModel> smsMapList = new LinkedHashMap<>();
		ContentResolver contentResolver = context.getContentResolver();

		// manual Uris here are because the Telephony constant was only added in API v19 - same for
		// Telephony.TextBasedSmsColumns.ADDRESS, Telephony.TextBasedSmsColumns.BODY,
		// Telephony.TextBasedSmsColumns.DATE, Telephony.Sms.Conversations.CONTENT_URI, etc

		// first get the most recent contacts from SMS
		Uri conversationsUri = Uri.parse("content://sms/conversations/");
		Cursor cursor = contentResolver.query(conversationsUri, new String[]{
				"thread_id", "snippet"
		}, null, null, "date DESC");
		if (cursor != null) {
			int idIndex = cursor.getColumnIndex("thread_id");
			int snippetIndex = cursor.getColumnIndex("snippet");
			while (cursor.moveToNext()) {
				String id = cursor.getString(idIndex);
				String snippet = cursor.getString(snippetIndex);

				SmsModel smsThread = smsMapList.get(id);
				if (smsThread == null) {
					smsThread = new SmsModel(id);
					smsThread.mThreadSnippet = snippet;
					smsMapList.put(id, smsThread);
				}

				if (smsMapList.size() >= rowLimit) {
					break;
				}
			}
			cursor.close();
		}

		// get the most recent messages from inbox and sent items (address is other side of conversation in both cases)
		Uri[] smsUris = { Uri.parse("content://sms/inbox"), Uri.parse("content://sms/sent") };
		for (Map.Entry<String, SmsModel> smsEntry : smsMapList.entrySet()) {
			SmsModel smsThread = smsEntry.getValue();
			String[] selection = new String[]{ smsThread.mThreadId };
			boolean ownMessages = false;

			for (Uri uri : smsUris) {
				cursor = contentResolver.query(uri, new String[]{ "address", "body", "date" }, "thread_id=?",
						selection,
						"date " + "DESC LIMIT " + rowLimit);
				if (cursor != null) {
					while (cursor.moveToNext()) {
						smsThread.mMessages.add(new SmsMessage(cursor.getString(cursor.getColumnIndex("body")), cursor
								.getLong(cursor
								.getColumnIndex("date")), ownMessages));

						if (smsThread.mContactNumber == null) {
							smsThread.mContactNumber = ContactsActivity.getNormalisedPhoneNumber(context, cursor
									.getString(cursor
									.getColumnIndex("address")));
							smsThread.mContactName = ContactsActivity.getContactName(context, smsThread
									.mContactNumber);
						}
					}
					cursor.close();
				}
				ownMessages = true;
			}

			Collections.sort(smsThread.mMessages);
			if (smsThread.mContactName == null) {
				smsThread.mContactName = smsThread.mContactNumber; // make sure we have a name
			}
		}

		return smsMapList;
	}

	/*
	--------------------------------------------------------------------------------------------------------------------
	--------------------------------------------------------------------------------------------------------------------
	 */

	private static final int CAPTURE_SMS = 202;

	private ArrayList<String> mMediaItems;
	private ListAdapter mListAdapter;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initialiseViewAndToolbar(R.layout.browser_list);

		mMediaItems = getMediaItemList();
		mListAdapter = new ListAdapter(mMediaItems);
		ListView listView = findViewById(R.id.list_view);
		listView.setEmptyView(findViewById(R.id.empty_list_view));
		listView.setAdapter(mListAdapter);
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
				mListAdapter.onClick(position);
			}
		});
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case CAPTURE_SMS:
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

	public static LinkedHashMap<String, SmsModel> loadSmsMessages(String filePath) {
		FileInputStream fileInputStream = null;
		try {
			fileInputStream = new FileInputStream(filePath);
			return SmsModel.readSmslist(fileInputStream);
		} catch (Exception e) {
			Log.d(TAG, "Unable to load SMS threads - storage or unpacking error?");
		} finally {
			Utilities.closeStream(fileInputStream);
		}
		return new LinkedHashMap<>();
	}

	private class ListAdapter extends BaseAdapter {
		final LinkedHashMap<String, SmsModel> mItems;
		List<String> mItemIndexes;

		private ListAdapter(ArrayList<String> smsThreads) {
			mItems = new LinkedHashMap<>();
			mItemIndexes = new ArrayList<>();
			setData(smsThreads);
		}

		private void setData(ArrayList<String> smsThreads) {
			mItems.clear();
			mItemIndexes.clear();
			if (smsThreads.size() > 0) {
				LinkedHashMap<String, SmsModel> items = loadSmsMessages(smsThreads.get(0));
				mItems.putAll(items);

				// bit of a workaround so we can get the item at the specific position
				String[] keys = new String[mItems.size()];
				mItems.keySet().toArray(keys);
				mItemIndexes = Arrays.asList(keys);
			}
		}

		@Override
		public int getCount() {
			return mItems.size();
		}

		@Override
		public Object getItem(int position) {
			return mItems.get(mItemIndexes.get(position));
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			SmsViewHolder viewHolder;
			if (view == null) {
				view = LayoutInflater.from(parent.getContext())
						.inflate(R.layout.item_contact_with_snippet, parent, false);
				viewHolder = new SmsViewHolder();
				viewHolder.contact = view.findViewById(R.id.contact_name);
				viewHolder.preview = view.findViewById(R.id.message_snippet);
				view.setTag(viewHolder);
			} else {
				viewHolder = (SmsViewHolder) view.getTag();
			}
			SmsModel currentContact = (SmsModel) getItem(position);
			viewHolder.contact.setText(currentContact.mContactName);
			viewHolder.preview.setText(currentContact.mThreadSnippet);
			return view;
		}

		void onClick(int position) {
			SmsModel currentThread = (SmsModel) getItem(position);
			Intent launchIntent = new Intent(SMSActivity.this, SMSComposeActivity.class);
			launchIntent.putExtra(SMSComposeActivity.CONTACT_NUMBER, currentThread.mContactNumber);
			launchIntent.putExtra(SMSComposeActivity.SMS_FILE, mMediaItems.get(0));
			launchIntent.putExtra(SMSComposeActivity.VISITOR_MODE, isVisitor());
			startActivityForResult(launchIntent, CAPTURE_SMS);
		}
	}

	private static class SmsViewHolder {
		TextView contact;
		TextView preview;
	}
}
