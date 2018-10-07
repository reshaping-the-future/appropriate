package ac.robinson.pod.models;

import android.support.annotation.NonNull;

import java.io.Serializable;

public class SmsMessage implements Serializable, Comparable<SmsMessage> {
	private final static long serialVersionUID = 1;

	public boolean mShouldImportMessage;
	public boolean mMessageSent;

	public String mMessage;
	public long mMessageDate;
	public boolean mIsOwnMessage;

	public SmsMessage(String message, long date, boolean ownMessage) {
		mMessage = message;
		mMessageDate = date;
		mIsOwnMessage = ownMessage;
	}

	@Override
	public int compareTo(@NonNull SmsMessage smsMessage) {
		return Long.valueOf(mMessageDate).compareTo(smsMessage.mMessageDate);
	}
}
