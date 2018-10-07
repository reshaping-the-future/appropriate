package ac.robinson.pod;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.Toast;

import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import ac.robinson.pod.listeners.PodResponseListener;
import ac.robinson.pod.service.PodManagerService;
import ac.robinson.pod.service.SynchronisedFile;

public class ChangeBackgroundActivity extends BasePodActivity {

	private int[] mBackgroundItems = {
			R.drawable.background_1,
			R.drawable.background_2,
			R.drawable.background_3,
			R.drawable.background_4,
			R.drawable.background_5
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initialiseViewAndToolbar(R.layout.browser_grid);

		GridAdapter gridAdapter = new GridAdapter(mBackgroundItems);
		GridView gridView = findViewById(R.id.grid_view);
		gridView.setEmptyView(findViewById(R.id.empty_grid_view));
		gridView.setAdapter(gridAdapter);
		gridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				File rootFolder = getFilesDir();
				boolean success;
				if (!rootFolder.exists()) {
					success = rootFolder.mkdirs();
				} else {
					success = true;
				}

				if (success) {
					File outputFile = new File(rootFolder, "background.png");
					try {
						Utilities.copyFile(getResources().openRawResource(mBackgroundItems[position]), outputFile);
						String absolutePath = outputFile.getAbsolutePath();
						SynchronisedFile backgroundFile = new SynchronisedFile(absolutePath, outputFile.length(),
								PodManagerService
								.getMimeType(absolutePath), new Date(outputFile.lastModified()));
						PodManagerService.uploadFile(PodManagerService.DEFAULT_POD_ROOT + "/" +
								PodManagerService.DEFAULT_POD_NAME, backgroundFile, new PodResponseListener() { //
							// TODO: build path properly
							@Override
							public void onResponse(final boolean success, String originalRequestPath) {
								// completed - must be run on UI thread to show toast
								ChangeBackgroundActivity.this.runOnUiThread(new Runnable() {
									@Override
									public void run() {
										Toast.makeText(ChangeBackgroundActivity.this, success ? R.string
												.message_change_background_succeeded : R.string
												.message_change_background_failed, Toast.LENGTH_SHORT)
												.show();
									}
								});
								finish();
							}
						});
					} catch (IOException ignored) {
					}
				}
			}
		});
	}

	private static class GridAdapter extends BaseAdapter {
		final ArrayList<Integer> mItems;

		private GridAdapter(int[] photos) {
			mItems = new ArrayList<>();
			setData(photos);
		}

		private void setData(int[] photos) {
			mItems.clear();
			for (int photo : photos) {
				mItems.add(photo);
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
			viewHolder.image.setImageURI(ImageRequestBuilder.newBuilderWithResourceId(mItems.get(position))
					.build()
					.getSourceUri());
			return view;
		}
	}

	private static class PhotoViewHolder {
		SimpleDraweeView image;
	}
}
