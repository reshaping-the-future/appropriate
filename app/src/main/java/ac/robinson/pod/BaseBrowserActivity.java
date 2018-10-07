package ac.robinson.pod;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.UiThread;
import android.support.v4.content.FileProvider;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ac.robinson.pod.listeners.FileProcessedResponseListener;
import ac.robinson.pod.service.PodManagerService;

public abstract class BaseBrowserActivity extends BasePodActivity {
	// called when backup starts - get item file paths on the local device (e.g., gallery, processed SMS archive, etc)
	@SuppressWarnings("unused") // used only via reflection
	@NonNull
	@UiThread
	@Keep
	public abstract ArrayList<String> getBackupItems(Context context, int rowLimit);

	// process a new item - scan the media directory, process SMS, etc (note: both config and actual items call this)
	@SuppressWarnings("unused") // used only via reflection
	@UiThread
	@Keep
	public abstract void processNewFile(Context context, File newFile, FileProcessedResponseListener responseListener);

	// called when all synchronisation for this data type has completed
	@SuppressWarnings("unused") // used only via reflection
	@UiThread
	@Keep
	public abstract void onSessionFinished(Context context);

	// get the directory to save updated files to - simple for some (contacts); for others must post-process (photos)
	@UiThread
	@Keep
	public abstract String getLocalSyncPath(Context context);

	public static final String OWN_ID = "own_id";
	public static final String VISITOR_ID = "visitor_id";
	public static final String DATA_TYPE = "data_type";

	public static final int MEDIA_UPDATED = 100;

	private int mOwnId;
	private int mVisitorId;
	private String mDataType;

	private boolean mHasEdited;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (savedInstanceState != null) {
			mOwnId = savedInstanceState.getInt("mOwnId");
			mVisitorId = savedInstanceState.getInt("mVisitorId");
			mDataType = savedInstanceState.getString("mDataType");
			mHasEdited = savedInstanceState.getBoolean("mHasEdited");
		} else {
			Intent intent = getIntent();
			if (intent != null) {
				mOwnId = intent.getIntExtra(OWN_ID, -1);
				mVisitorId = intent.getIntExtra(VISITOR_ID, -1);
				mDataType = intent.getStringExtra(DATA_TYPE);
				mHasEdited = false; // first run
			}
		}

		if (mDataType == null || (mOwnId < 0 && mVisitorId < 0)) { // must have data type and at least one of the IDs
			throw new RuntimeException("Unable to read Pod data type or identifiers");
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putInt("mOwnId", mOwnId);
		outState.putInt("mVisitorId", mVisitorId);
		outState.putString("mDataType", mDataType);
		outState.putBoolean("mHasEdited", mHasEdited);
		super.onSaveInstanceState(outState);
	}

	protected int getOwnId() {
		return mOwnId;
	}

	protected int getVisitorId() {
		return mVisitorId;
	}

	protected boolean isVisitor() {
		return getVisitorId() >= 0;
	}

	protected String getDataType() {
		return mDataType;
	}

	protected void setHasEdited(boolean hasEdited) {
		mHasEdited = hasEdited;
		setResult(mHasEdited ? BaseBrowserActivity.MEDIA_UPDATED : RESULT_CANCELED);
	}

	// we have to do this separately as earlier SDK versions can't handle permissions properly, and crash
	// see: https://stackoverflow.com/questions/33650632/fileprovider-not-working-with-camera
	// https://stackoverflow.com/questions/18249007/how-to-use-support-fileprovider-for-sharing-content-to-other-apps
	protected void grantIntentUriPermission(Intent intent, Uri uri, int permission) {
		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
			List<ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, PackageManager
					.MATCH_DEFAULT_ONLY);
			for (ResolveInfo resolveInfo : resInfoList) {
				String packageName = resolveInfo.activityInfo.packageName;
				// TODO: should we revoke after too? https://stackoverflow.com/a/32950381/1993220
				grantUriPermission(packageName, uri, permission);
			}
		}
		intent.addFlags(permission);
	}

	// for image/video/audio and other viewable files, just click on the file to launch
	protected void onMediaClick(String path, boolean isVisitor) {
		try {
			File file = new File(path);
			Intent intent = new Intent(Intent.ACTION_VIEW);
			if (isVisitor) {
				Uri uri = FileProvider.getUriForFile(BaseBrowserActivity.this, BasePodApplication.FILE_PROVIDER_NAME,
						file);
				grantIntentUriPermission(intent, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
				intent.setDataAndType(uri, PodManagerService.getMimeType(file.getName()));
				startActivity(intent);
			} else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
				//TODO: newer devices throw FileUriExposedException outside app's base - workaround for older ones
				intent.setDataAndType(Uri.fromFile(file), PodManagerService.getMimeType(file.getName()));
				startActivity(intent);
			} else {
				// TODO: get a content:// uri instead (when adding to media lib?)
				// (or keep duplicate files on sync just for this?)
			}
		} catch (ActivityNotFoundException e) {
			Toast.makeText(BaseBrowserActivity.this, R.string.no_suitable_app_found, Toast.LENGTH_SHORT).show();
		}
	}

	// get a list of the files in the own or visitor file directories, limited in quantity by the value set in settings
	protected ArrayList<String> getMediaItemList() {
		ArrayList<String> mediaItems;
		if (isVisitor()) {
			mediaItems = getRecencySortedFileList(getVisitorSyncPath(BaseBrowserActivity.this, getVisitorId(),
					getDataType()), -1); // no limit on how many items to show as visitor - include everything they've
			// added
		} else {
			mediaItems = getRecencySortedFileList(getLocalSyncPath(BaseBrowserActivity.this), -1);
		}
		return mediaItems;
	}

	// for use with taking photo via intent; viewing media, etc as the owner or visitor
	// see: https://developer.android.com/reference/android/support/v4/content/FileProvider.html#Permissions
	// use with, e.g., intent.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
	// TODO: this, getFilesDirectoryForData, getVisitorSyncPath, and each STORAGE_DIRECTORY_NAME - lots of duplication
	protected Uri getFileProviderUri(String filename) {
		File mediaFile;
		if (isVisitor()) {
			// visitor paths have both the type (as root) and the visitor id to ensure no cross-contamination of data
			mediaFile = new File(new File(getCacheDir(), getDataType()), String.valueOf(getVisitorId()));
		} else {
			mediaFile = new File(getFilesDir(), getDataType());
		}
		if (!mediaFile.exists()) {
			mediaFile.mkdirs();
		}
		File newFile = new File(mediaFile, filename);
		return FileProvider.getUriForFile(BaseBrowserActivity.this, BasePodApplication.FILE_PROVIDER_NAME, newFile);
	}

	protected String getDatedRandomisedFilename(String extension) {
		String random = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 6);
		return new SimpleDateFormat(
				"yyyy-MM-dd_HH-mm-ss_'" + random + "." + extension + "'", Locale.US).format(new Date());
	}

	// where to store files for our own Pod (when using simulated data, or when making a combined file - eg contacts)
	// TODO: this, getFileProviderUri, getVisitorSyncPath, and each STORAGE_DIRECTORY_NAME have a lot of duplication
	public static String getFilesDirectoryForData(Context context, String dataType) {
		File rootFolder = new File(context.getFilesDir(), dataType);
		if (!rootFolder.exists()) {
			rootFolder.mkdirs();
		}
		return rootFolder.getAbsolutePath();
	}

	// where to store files for a visitor's Pod - here we use the Pod's id just in case - although we delete files at
	// every opportunity, we don't want there to be any chance of one visitor's files getting mixed up with another's
	// (note that path is reversed from usual /data/id/type format so we can use file providers for access to roots)
	// TODO: this, getFileProviderUri, getFilesDirectoryForData, and each STORAGE_DIRECTORY_NAME - lots of duplication
	public static String getVisitorSyncPath(Context context, int podId, String dataType) {
		File rootTypeFolder = new File(context.getCacheDir(), dataType);
		if (!rootTypeFolder.exists()) {
			rootTypeFolder.mkdirs();
		}
		File podDataFolder = new File(rootTypeFolder, String.valueOf(podId));
		if (!podDataFolder.exists()) {
			podDataFolder.mkdirs();
		}
		return podDataFolder.getAbsolutePath();
	}

	// rowLimit of 0 or negative means return ALL files
	@NonNull
	public static ArrayList<String> getRecencySortedFileList(String path, int rowLimit) {
		ArrayList<String> media = new ArrayList<>();

		File directory = new File(path);
		File[] files = directory.listFiles();

		if (files != null) {
			Arrays.sort(files, new Comparator<File>() {
				public int compare(File f1, File f2) {
					return -Long.valueOf(f1.lastModified())
							.compareTo(f2.lastModified()); // negative as we want most recent
				}
			});

			int index = 0;
			for (File file : files) {
				if (!file.isDirectory()) {
					media.add(file.getAbsolutePath()); // TODO: use getCanonicalPath() instead?
					index += 1;
					if (rowLimit > 0 && index >= rowLimit) {
						break;
					}
				}
			}
		}

		return media;
	}
}
