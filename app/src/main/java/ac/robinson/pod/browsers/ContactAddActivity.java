package ac.robinson.pod.browsers;

import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.TextInputEditText;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import ac.robinson.pod.BaseBrowserActivity;
import ac.robinson.pod.BasePodActivity;
import ac.robinson.pod.R;
import ac.robinson.pod.Utilities;
import ac.robinson.pod.models.ContactModel;

public class ContactAddActivity extends BasePodActivity {

	public static final String CONTACT_FILE = "contact_file";
	public static final String TAG = "ContactAddActivity";

	private String mFilePath;

	private TextInputEditText mContactName;
	private TextInputEditText mContactNumber;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initialiseViewAndToolbar(R.layout.browser_add_contact);

		if (savedInstanceState != null) {
			mFilePath = savedInstanceState.getString("mFilePath");
		} else {
			Intent intent = getIntent();
			if (intent != null) {
				mFilePath = intent.getStringExtra(CONTACT_FILE);
			}
		}

		if (mFilePath == null) { // must have data file to add to
			Log.d(TAG, "Unable to read contacts file");
			finish();
			return;
		}

		mContactName = findViewById(R.id.contact_name);
		mContactNumber = findViewById(R.id.contact_phone);
		mContactName.requestFocus();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putString("mFilePath", mFilePath);
		super.onSaveInstanceState(outState);
	}

	public void handleClick(View view) {
		switch (view.getId()) {
			case R.id.contact_save:
				saveContact();
				break;

			default:
				break;
		}
	}

	private void saveContact() {
		String newContactName = mContactName.getText().toString();
		String newContactNumber = mContactNumber.getText().toString();
		if (TextUtils.isEmpty(newContactName) || TextUtils.isEmpty(newContactNumber)) {
			Toast.makeText(ContactAddActivity.this, R.string.hint_add_contact_empty, Toast.LENGTH_SHORT).show();
			return;
		}

		LinkedHashMap<String, ContactModel> existingContactItems = ContactsActivity.loadContacts(mFilePath);

		// place the new contact at the start of the list - we could try to insert alphabetically, but would require
		// changing IDs; instead find a unique negative ID (to signify new item), then add other items into a new list
		int newContactId = -1;
		while (existingContactItems.get(String.valueOf(newContactId)) != null) {
			newContactId -= 1;
		}

		String contactId = String.valueOf(newContactId);
		ContactModel newContact = new ContactModel();
		newContact.mId = contactId;
		newContact.mName = newContactName;
		newContact.mMobileNumbers.add(newContactNumber);
		newContact.mContactEdited = true;

		LinkedHashMap<String, ContactModel> newContactItems = new LinkedHashMap<>();
		newContactItems.put(contactId, newContact);
		for (Map.Entry<String, ContactModel> entry : existingContactItems.entrySet()) {
			newContactItems.put(entry.getKey(), entry.getValue());
		}

		FileOutputStream fileOutputStream = null;
		try {
			fileOutputStream = new FileOutputStream(mFilePath);
			ContactModel.writeContactList(fileOutputStream, newContactItems);
		} catch (IOException e) {
			Log.d(TAG, "Unable to save contact - permission or storage error?");
			Toast.makeText(ContactAddActivity.this, R.string.hint_error_saving_messages, Toast.LENGTH_SHORT).show();
			return;
		} finally {
			Utilities.closeStream(fileOutputStream);
		}

		setResult(BaseBrowserActivity.MEDIA_UPDATED); // so the previous activity updates, and we sync
		finish();
	}
}
