package ac.robinson.pod.models;


import android.content.Context;
import android.provider.MediaStore;

public class MediaProvider extends AbstractProvider {

	private final static String ORDER_BY_COLUMN = MediaStore.MediaColumns.DATE_MODIFIED;

	public MediaProvider(Context context) {
		super(context);
	}

	public Data<Image> getImages(Storage storage, int limit) {
		switch (storage) {
			case INTERNAL:
				return getContentTableData(Image.uriInternal, Image.class);
			default:
				return getContentTableData(Image.uriExternal,
						ORDER_BY_COLUMN + " DESC" + " LIMIT " + limit, Image.class);
		}
	}

	public enum Storage {
		INTERNAL, EXTERNAL
	}
}
