package ac.robinson.pod.browsers;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.Toast;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import ac.robinson.pod.BaseBrowserActivity;
import ac.robinson.pod.R;
import ac.robinson.pod.SettingsActivity;
import ac.robinson.pod.Utilities;
import ac.robinson.pod.listeners.FileProcessedResponseListener;
import ac.robinson.pod.models.Data;
import ac.robinson.pod.models.Image;
import ac.robinson.pod.models.ImageModel;
import ac.robinson.pod.models.MediaProvider;
import ac.robinson.pod.service.PodManagerService;

public class GalleryActivity extends BaseBrowserActivity {
	// TODO: this, getFileProviderUri, getFilesDirectoryForData and getVisitorSyncPath have a lot of duplication
	public static final String STORAGE_DIRECTORY_NAME = "gallery"; // on both Pod and, where applicable, local phone
	public static final String TAG = "GalleryActivity";

	private static final HashMap<String, Boolean> sSyncedFiles = new HashMap<>();

	@NonNull
	@Override
	public ArrayList<String> getBackupItems(Context context, int rowLimit) {
		// TODO: check between internal and external on older devices?
		ArrayList<String> imagePaths = new ArrayList<>();
		List<Image> imageList = getLatestImages(context, rowLimit);
		for (Image image : imageList) {
			// TODO: see documentation for MediaStore.MediaColumns.DATA - may not always have path access permissions
			Log.d(TAG, "Found new image: " + image.data);
			imagePaths.add(image.data);
			imagePaths.add(createImageConfigFile(image.data, getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME)
					, false));
		}
		return imagePaths;
	}

	@Override
	public void processNewFile(Context context, File newFile, final FileProcessedResponseListener responseListener) {
		Log.d(TAG, "Processing new imported file " + newFile.getName());

		// get root image name
		String imageName = newFile.getName();
		if (imageName.endsWith(PodManagerService.POD_CONFIGURATION_FILE_NAME)) {
			imageName = Utilities.removeFileExtension(imageName);
		}

		// we haven't yet processed either the corresponding source image or the config file - wait until this happens
		if (!sSyncedFiles.containsKey(imageName)) {
			sSyncedFiles.put(imageName, Boolean.TRUE);
			responseListener.onResponse();
			return;
		}

		// get the image and config files and decide how to proceed
		File imageFile = new File(newFile.getParent(), imageName);
		File imageConfigFile = new File(newFile.getParent(), imageName + PodManagerService
				.POD_CONFIGURATION_FILE_NAME);

		FileInputStream fileInputStream = null;
		try {
			fileInputStream = new FileInputStream(imageConfigFile);
			ImageModel imageModel = ImageModel.readImageModel(fileInputStream);

			// this image was taken as a guest on another device - put in our camera folder
			if (imageModel.mPath == null) {
				Log.d(TAG, "Image path not set - loading image taken on visitor");
				File cameraFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);

				// TODO: we assume the first result from this query will be from the camera - if not will place wrongly
				List<Image> latestImage = getLatestImages(context, 1);
				if (latestImage.size() >= 1) {
					File galleryFolder = new File(new File(latestImage.get(0).data).getParent());
					if (galleryFolder.exists()) {
						cameraFolder = galleryFolder;
						Log.d(TAG, "Switching to camera folder " + cameraFolder.getAbsolutePath());
					}
				}

				cameraFolder.mkdirs();
				if (cameraFolder.exists()) {
					Log.d(TAG, "Moving image file " + imageModel.mName + " to local presumed camera folder");
					File newImageFile = new File(cameraFolder, imageModel.mName);
					Utilities.moveFile(imageFile, newImageFile);
					scanNewLocalFile(context, newImageFile, responseListener);
					return;

				} else {
					Log.d(TAG, "Gallery directory does not exist (1)");
					responseListener.onResponse();
					return;
				}
			}

			// if no file exists at the original location, we simply move the image there - this will not happen
			// unless the user deletes the original file and then syncs with the pod to restore it
			File localOutputFolder = new File(imageModel.mPath);
			File localOutputFile = new File(localOutputFolder, imageModel.mName);

			if (!localOutputFile.exists()) {
				// TODO we don't deal with private access here at all
				localOutputFolder.mkdirs();
				if (localOutputFolder.exists()) {
					Log.d(TAG, "Moving image file " + imageModel.mName + " to " + imageModel.mPath);
					Utilities.moveFile(imageFile, localOutputFile);
					// note: setLastModified rarely works - see note in MainActivity
					localOutputFile.setLastModified(imageModel.mLastModified);
					scanNewLocalFile(context, localOutputFile, responseListener);
					return;

				} else {
					Log.d(TAG, "Gallery directory does not exist (2)");
					responseListener.onResponse();
					return;
				}

			} else { // the file already exists - need to decide what to do with our copy from the pod
				if (localOutputFile.lastModified() == imageModel.mLastModified &&
						localOutputFile.length() == imageModel.mSize) {
					// dates and sizes match so probably the same file - nothing more to do
					imageFile.delete();
					Log.d(TAG, "Removed duplicate image file " + imageModel.mName);
					responseListener.onResponse();
					return;

				} else {
					// this *might* be a new file - copy to local folder under a new name just in case
					localOutputFolder.mkdirs();
					if (localOutputFolder.exists()) {
						File localRenamedFile = new File(localOutputFolder, getDatedRandomisedFilename("jpg"));
						Log.d(TAG, "Moving potential new image file " + imageModel.mName + " to " + imageModel.mPath +
								" as " + localRenamedFile.getName());
						Utilities.moveFile(imageFile, localRenamedFile);
						localRenamedFile.setLastModified(imageModel.mLastModified); // rarely works - see note in
						// MainActivity
						scanNewLocalFile(context, localRenamedFile, responseListener);
						return;

					} else {
						Log.d(TAG, "Gallery directory does not exist (3)");
						responseListener.onResponse();
						return;
					}
				}
			}

		} catch (Exception e) {
			Log.d(TAG, "Unable to load image configuration - storage or unpacking error?");
			// e.printStackTrace();
		} finally {
			Utilities.closeStream(fileInputStream);
		}

		responseListener.onResponse(); // failure...
	}

	@Override
	public void onSessionFinished(Context context) {
		File galleryConfigStorage = new File(getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME));
		File[] galleryFiles = galleryConfigStorage.listFiles();
		if (galleryFiles != null) {
			for (File child : galleryFiles) {
				child.delete();
			}
		}
		sSyncedFiles.clear(); // sync completed - we regenerate the list next time we sync
	}

	private void scanNewLocalFile(Context context, File imageFile, final FileProcessedResponseListener
			responseListener) {
		// TODO: use getCanonicalPath() instead?
		MediaScannerConnection.scanFile(context, new String[]{ imageFile.getAbsolutePath() }, null, new
				MediaScannerConnection.OnScanCompletedListener() {
			@Override
			public void onScanCompleted(String path, Uri uri) {
				Log.v(TAG, "File " + path + " was scanned successfully: " + uri);
				responseListener.onResponse();
			}
		});
	}

	@Override
	public String getLocalSyncPath(Context context) {
		// note - this must *never* be the user's actual gallery folder
		// (we clear this folder's contents in onSessionFinished!)
		return BaseBrowserActivity.getFilesDirectoryForData(context, STORAGE_DIRECTORY_NAME);
	}

	private List<Image> getLatestImages(Context context, int rowLimit) {
		MediaProvider mediaProvider = new MediaProvider(context);
		Data<Image> imageData = mediaProvider.getImages(MediaProvider.Storage.EXTERNAL, rowLimit);
		return imageData.getList(); // TODO: may be slow if rowLimit is high
	}

	private String createImageConfigFile(String imagePath, String outputFolder, boolean newVisitorFile) {
		File imageFile = new File(imagePath);
		ImageModel image = new ImageModel();
		image.mName = imageFile.getName();
		image.mPath = !newVisitorFile ? imageFile.getParent() : null;
		image.mLastModified = imageFile.lastModified();
		image.mSize = imageFile.length();

		File outputFile = new File(outputFolder, image.mName + PodManagerService.POD_CONFIGURATION_FILE_NAME);
		FileOutputStream fileOutputStream = null;
		try {
			fileOutputStream = new FileOutputStream(outputFile);
			ImageModel.writeImageModel(fileOutputStream, image);
		} catch (IOException e) {
			Log.d(TAG, "Unable to save image configuration - permission or storage error?");
		} finally {
			Utilities.closeStream(fileOutputStream);
		}

		return outputFile.getAbsolutePath();
	}

	/*
	--------------------------------------------------------------------------------------------------------------------
	--------------------------------------------------------------------------------------------------------------------
	 */

	private static final int CAPTURE_IMAGE = 201;

	private ArrayList<String> mMediaItems;
	private GridAdapter mGridAdapter;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initialiseViewAndToolbar(R.layout.browser_grid);

		mMediaItems = getMediaItemList();
		mGridAdapter = new GridAdapter(mMediaItems);
		GridView gridView = findViewById(R.id.grid_view);
		gridView.setEmptyView(findViewById(R.id.empty_grid_view));
		gridView.setAdapter(mGridAdapter);
		gridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				mGridAdapter.onClick(position);
			}
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		if (isVisitor()) {
			// only visitors can add new items (own media is via normal apps)
			getMenuInflater().inflate(R.menu.menu_photo, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}


	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_take_photo:
				try {
					Uri photoUri = getFileProviderUri(getDatedRandomisedFilename("jpg"));
					Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
					grantIntentUriPermission(intent, photoUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
					intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
					startActivityForResult(intent, CAPTURE_IMAGE);
				} catch (ActivityNotFoundException e) {
					Toast.makeText(GalleryActivity.this, R.string.no_suitable_app_found, Toast.LENGTH_SHORT).show();
				}
				break;

			default:
				break;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case CAPTURE_IMAGE:
				if (resultCode == RESULT_OK) {
					mMediaItems = getMediaItemList();
					if (mMediaItems.size() >
							0) { // add a new config file - the first new media item will be our new photo
						mMediaItems.add(0, createImageConfigFile(mMediaItems.get(0), getVisitorSyncPath
								(GalleryActivity.this, getVisitorId(), getDataType()), true));
					}
					mGridAdapter.setData(mMediaItems);
					mGridAdapter.notifyDataSetChanged();
					setHasEdited(true);
				}
				break;

			default:
				break;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	private class GridAdapter extends BaseAdapter {
		final ArrayList<String> mItems;

		private GridAdapter(ArrayList<String> photos) {
			mItems = new ArrayList<>();
			setData(photos);
		}

		private void setData(ArrayList<String> photos) {
			// get the images with their attribute, sorted into date order
			ArrayList<ImageModel> sortedImages = new ArrayList<>();
			for (String photo : photos) {
				if (photo.endsWith(PodManagerService.POD_CONFIGURATION_FILE_NAME)) {
					// only continue if we have both the image and its configuration file
					FileInputStream fileInputStream = null;
					try {
						fileInputStream = new FileInputStream(photo);
						ImageModel imageModel = ImageModel.readImageModel(fileInputStream);
						File photoFile;
						if (isVisitor()) {
							photoFile = new File(new File(photo).getParent(), imageModel.mName);
						} else {
							photoFile = new File(imageModel.mPath, imageModel.mName);
						}

						if (photoFile.exists()) {
							// bit hacky - update to use local image path, so we don't have to create files everywhere
							imageModel.mPath = photoFile.getAbsolutePath();
							sortedImages.add(imageModel);
						}

					} catch (Exception e) {
						Log.d(TAG, "Unable to load image configuration - storage or unpacking error?");
					} finally {
						Utilities.closeStream(fileInputStream);
					}
				}
			}

			// sort the filtered collection and display it
			Collections.sort(sortedImages);
			mItems.clear();
			int itemLimit = isVisitor() ? Integer.MAX_VALUE : SettingsActivity.getItemSyncLimit(GalleryActivity.this);
			int count = 0;
			for (ImageModel imageModel : sortedImages) {
				Log.d(TAG, imageModel.mPath);
				if (count < itemLimit) {
					mItems.add(imageModel.mPath);
				} else {
					break;
				}
				count += 1;
			}
		}

		@Override
		public int getCount() {
			return mItems.size();
		}

		@Override
		public Object getItem(int position) {
			return mItems.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			PhotoViewHolder viewHolder;
			if (view == null) {
				view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_thumbnail, parent, false);
				viewHolder = new PhotoViewHolder();
				viewHolder.image = view.findViewById(R.id.gallery_image);
				view.setTag(viewHolder);
			} else {
				viewHolder = (PhotoViewHolder) view.getTag();
			}

			// TODO: Fresco does *not* resize images to fit views?!
			int imageSize = getWindowManager().getDefaultDisplay().getWidth() / 2;
			ImageRequest imageRequest = ImageRequestBuilder.newBuilderWithSource(Uri.parse(
					"file://" + mItems.get(position)))
					.setProgressiveRenderingEnabled(true)
					.setLocalThumbnailPreviewsEnabled(true)
					.setResizeOptions(new ResizeOptions(imageSize, imageSize))
					.setRotationOptions(RotationOptions.autoRotateAtRenderTime())
					.build();
			viewHolder.image.setController(Fresco.newDraweeControllerBuilder().setImageRequest(imageRequest).build());
			return view;
		}

		void onClick(int position) {
			onMediaClick(mItems.get(position), isVisitor());
		}
	}

	private static class PhotoViewHolder {
		SimpleDraweeView image;
	}
}
