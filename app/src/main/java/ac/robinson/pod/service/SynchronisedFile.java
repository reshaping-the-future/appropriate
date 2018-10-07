package ac.robinson.pod.service;

import android.support.annotation.NonNull;

import java.util.Comparator;
import java.util.Date;

public class SynchronisedFile {
	public String mPath; // includes filename
	public String mName;

	public long mSize;
	public String mMimeType;
	public Date mCreationDate;

	public boolean mSynchronised;

	@SuppressWarnings("unused")
	private SynchronisedFile() {
	}

	public SynchronisedFile(@NonNull String path, long size, @NonNull String mimeType, @NonNull Date creationDate) {
		mPath = path;
		mName = mPath.substring(mPath.lastIndexOf('/') + 1); // TODO: hacky
		mSize = size;
		mMimeType = mimeType; // alternative, from AndroidNetworking: getMimeType(filename);
		mCreationDate = new Date(creationDate.getTime());

		mSynchronised = false;
	}

	public SynchronisedFile(@NonNull SynchronisedFile from) {
		mPath = from.mPath;
		mName = from.mName;
		mSize = from.mSize;
		mMimeType = from.mMimeType;
		mCreationDate = new Date(from.mCreationDate.getTime());

		mSynchronised = false;
	}

	static class SynchronisedFileComparator implements Comparator<SynchronisedFile> {
		@Override
		public int compare(SynchronisedFile o1, SynchronisedFile o2) {
			return -o1.mCreationDate.compareTo(o2.mCreationDate); // negative so we get the *newest* files first
		}
	}
}
