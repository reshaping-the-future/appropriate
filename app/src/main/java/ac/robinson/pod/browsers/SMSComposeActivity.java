package ac.robinson.pod.browsers;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ac.robinson.pod.BaseBrowserActivity;
import ac.robinson.pod.BasePodActivity;
import ac.robinson.pod.R;
import ac.robinson.pod.SettingsActivity;
import ac.robinson.pod.Utilities;
import ac.robinson.pod.models.SmsMessage;
import ac.robinson.pod.models.SmsModel;

public class SMSComposeActivity extends BasePodActivity {

	public static final String CONTACT_NUMBER = "contact_number";
	public static final String CONTACT_NAME = "contact_name";
	public static final String VISITOR_MODE = "visitor_mode";
	public static final String SMS_FILE = "sms_file";
	public static final String TAG = "SMSComposeActivity";

	private String mContactNumber;
	private String mContactName;
	public String mFilePath;

	private ArrayList<MessageItem> mMessages;
	private ListAdapter mListAdapter;
	private ListView mListView;
	private EditText mEditText;

	private boolean mAllowNewMessages;
	private boolean mHasEdited; // track separately here - we don't extend BaseBrowserActivity

	private static class MessageItem {
		MessageItem(String message, boolean ownMessage) {
			mMessage = message;
			mIsOwnMessage = ownMessage;
		}

		String mMessage;
		boolean mIsOwnMessage;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initialiseViewAndToolbar(R.layout.browser_message_entry);

		if (savedInstanceState != null) {
			mContactNumber = savedInstanceState.getString("mContactNumber");
			mContactName = savedInstanceState.getString("mContactName");
			mFilePath = savedInstanceState.getString("mFilePath");
			mAllowNewMessages = savedInstanceState.getBoolean("mAllowNewMessages");
			mHasEdited = savedInstanceState.getBoolean("mHasEdited");
		} else {
			Intent intent = getIntent();
			if (intent != null) {
				mContactNumber = intent.getStringExtra(CONTACT_NUMBER);
				mContactName = intent.getStringExtra(CONTACT_NAME);
				mAllowNewMessages = intent.getBooleanExtra(VISITOR_MODE, false);
				mFilePath = intent.getStringExtra(SMS_FILE);
				mHasEdited = false;
			}
		}

		if (mContactNumber == null || mFilePath == null) { // must have data to display
			Log.d(TAG, "Unable to read contact or message details");
			finish();
			return;
		}
		Log.d(TAG, "Loading SMS from " + mFilePath);

		// get this contact's messages
		SmsModel selectedThread = null;
		LinkedHashMap<String, SmsModel> items = SMSActivity.loadSmsMessages(mFilePath);
		for (Map.Entry<String, SmsModel> entry : items.entrySet()) {
			SmsModel currentThread = entry.getValue();
			if (mContactNumber.equals(currentThread.mContactNumber)) {
				selectedThread = currentThread;
				break;
			}
		}

		// either continue the conversation or start from empty
		mMessages = new ArrayList<>();
		if (selectedThread != null) {
			setTitle(selectedThread.mContactName);
			for (SmsMessage message : selectedThread.mMessages) {
				mMessages.add(new MessageItem(message.mMessage, message.mIsOwnMessage));
			}
		} else {
			Log.d(TAG, "Contact " + mContactNumber + " not found - starting new message");
			setTitle(mContactName == null ? mContactNumber : mContactName);
		}

		mListAdapter = new ListAdapter(mMessages);
		mListView = findViewById(R.id.message_list);
		mListView.setAdapter(mListAdapter);
		if (mMessages.size() > 0) {
			mListView.setSelection(mListAdapter.getCount() - 1);
		}
		mEditText = findViewById(R.id.entered_message);
		findViewById(R.id.message_send_button).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				sendMessage();
			}
		});
		if (!mAllowNewMessages) {
			findViewById(R.id.message_entry).setVisibility(View.GONE); // can't send messages on own pod
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putString("mContactNumber", mContactNumber);
		outState.putString("mContactName", mContactName);
		outState.putString("mFilePath", mFilePath);
		outState.putBoolean("mAllowNewMessages", mAllowNewMessages);
		outState.putBoolean("mHasEdited", mHasEdited);
		super.onSaveInstanceState(outState);
	}

	private void sendMessage() {
		final String text = mEditText.getText().toString();
		if (TextUtils.isEmpty(text)) {
			return;
		}

		SmsModel selectedThread = null;
		final LinkedHashMap<String, SmsModel> smsItems = SMSActivity.loadSmsMessages(mFilePath);
		for (Map.Entry<String, SmsModel> entry : smsItems.entrySet()) {
			SmsModel currentThread = entry.getValue();
			if (mContactNumber.equals(currentThread.mContactNumber)) {
				selectedThread = currentThread;
				break;
			}
		}

		if (selectedThread == null) {
			String id = UUID.randomUUID().toString();
			selectedThread = new SmsModel(id);
			selectedThread.mNewThread = true;
			selectedThread.mContactNumber = mContactNumber;
			selectedThread.mContactName = mContactName;
			smsItems.put(id, selectedThread);
		}
		selectedThread.mThreadSnippet = text;

		final SmsMessage newMessage = new SmsMessage(text, System.currentTimeMillis(), true);
		newMessage.mShouldImportMessage = true;
		selectedThread.mMessages.add(newMessage);

		DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				newMessage.mMessageSent = which == DialogInterface.BUTTON_POSITIVE;
				if (newMessage.mMessageSent) { // send the message directly, from the visitor phone owner's number
					sendSMS(SMSComposeActivity.this, mContactNumber, text, true);
				} else {
					Toast.makeText(SMSComposeActivity.this, R.string.hint_messages_saved_for_later, Toast.LENGTH_SHORT)
							.show();
				}

				FileOutputStream fileOutputStream = null;
				try {
					fileOutputStream = new FileOutputStream(mFilePath);
					SmsModel.writeSmsList(fileOutputStream, smsItems);
				} catch (IOException e) {
					Log.d(TAG, "Unable to save SMS - permission or storage error?");
					Toast.makeText(SMSComposeActivity.this, R.string.hint_error_saving_messages, Toast.LENGTH_SHORT)
							.show();
					return;
				} finally {
					Utilities.closeStream(fileOutputStream);
				}

				mEditText.setText("");
				mMessages.add(new MessageItem(newMessage.mMessage, newMessage.mIsOwnMessage));
				mListAdapter.setData(mMessages);
				mListAdapter.notifyDataSetChanged();
				mListView.setSelection(mListAdapter.getCount() - 1);
				mHasEdited = true;
				setResult(BaseBrowserActivity.MEDIA_UPDATED); // so the previous activity updates, and we sync
			}
		};

		if (SettingsActivity.getHasSIM(SMSComposeActivity.this)) {
			if (SettingsActivity.getVisitorOnlyMode(SMSComposeActivity.this)) {
				listener.onClick(null, DialogInterface.BUTTON_POSITIVE);
			} else {
				AlertDialog.Builder dialog = new AlertDialog.Builder(SMSComposeActivity.this);
				dialog.setTitle(R.string.title_send_sms);
				dialog.setMessage(R.string.message_send_sms);
				dialog.setPositiveButton(R.string.menu_now, listener);
				dialog.setNegativeButton(R.string.menu_later, listener);
				dialog.show();
			}
		} else {
			if (!SettingsActivity.getVisitorOnlyMode(SMSComposeActivity.this)) {
				listener.onClick(null, DialogInterface.BUTTON_NEGATIVE);
				Toast.makeText(SMSComposeActivity.this, R.string.hint_messages_saved_for_later, Toast.LENGTH_SHORT)
						.show();
			} else {
				// TODO: can't send - we don't have a "home phone"
			}
		}
	}

	public static void sendSMS(Context context, String number, String text, boolean appendPodNote) {
		String message = text + (appendPodNote ? context.getString(R.string.pod_sms_note) : "");

		// note: as of late 2018 this is now banned (and Google rejected an exception for this app)
		// SmsManager smsManager = SmsManager.getDefault();
		// ArrayList<String> parts = smsManager.divideMessage(message);
		// smsManager.sendMultipartTextMessage(number, null, parts, null, null);

		// we now have to resort to manual sending, for which there are two options (first probably outdated)
		//Intent smsIntent = new Intent(android.content.Intent.ACTION_VIEW);
		//smsIntent.setType("vnd.android-dir/mms-sms");
		//smsIntent.putExtra("address", number);
		//smsIntent.putExtra("sms_body", message);

		Intent smsIntent = new Intent(Intent.ACTION_SEND);
		smsIntent.setData(Uri.parse("smsto:" + number));  // This ensures only SMS apps respond
		smsIntent.putExtra("sms_body", message);

		if (smsIntent.resolveActivity(context.getPackageManager()) != null) {
			context.startActivity(smsIntent);
			Toast.makeText(context, R.string.hint_press_send_to_deliver, Toast.LENGTH_SHORT).show();
		}
	}

	private class ListAdapter extends BaseAdapter {
		final ArrayList<MessageItem> mItems;

		private ListAdapter(final ArrayList<MessageItem> messages) {
			mItems = new ArrayList<>();
			setData(messages);
		}

		private void setData(ArrayList<MessageItem> messages) {
			mItems.clear();
			mItems.addAll(messages);
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
			MessageViewHolder viewHolder;
			if (view == null) {
				view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
				viewHolder = new MessageViewHolder();
				viewHolder.messageLeft = view.findViewById(R.id.message_text_left);
				viewHolder.messageRight = view.findViewById(R.id.message_text_right);
				view.setTag(viewHolder);
			} else {
				viewHolder = (MessageViewHolder) view.getTag();
			}

			MessageItem item = mItems.get(position);
			if (item.mIsOwnMessage) {
				viewHolder.messageRight.setBackgroundColor(Color.WHITE);
				viewHolder.messageRight.setTextColor(Color.DKGRAY);
				viewHolder.messageRight.setText(item.mMessage);
				viewHolder.messageRight.setVisibility(View.VISIBLE);
				viewHolder.messageLeft.setVisibility(View.GONE);
			} else {
				viewHolder.messageLeft.setBackgroundColor(getResources().getColor(R.color.colorPrimary));
				viewHolder.messageLeft.setTextColor(Color.WHITE);
				viewHolder.messageLeft.setText(item.mMessage);
				viewHolder.messageLeft.setVisibility(View.VISIBLE);
				viewHolder.messageRight.setVisibility(View.GONE);
			}
			return view;
		}
	}

	private static class MessageViewHolder {
		TextView messageLeft;
		TextView messageRight;
	}
}
