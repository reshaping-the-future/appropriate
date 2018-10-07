package ac.robinson.pod.models;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

// based on https://github.com/EverythingMe/easy-content-providers
abstract class AbstractProvider {

	private ContentResolver mContentResolver;

	AbstractProvider(Context context) {
		mContentResolver = context.getContentResolver();
	}

	<T extends Entity> Data<T> getContentTableData(Uri uri, Class<T> cls) {
		Cursor cursor = mContentResolver.query(uri, Entity.getColumns(cls), null, null, null);
		if (cursor == null) {
			return null;
		}
		return new Data<>(cursor, cls);
	}

	<T extends Entity> Data<T> getContentTableData(Uri uri, String sortOrder, Class<T> cls) {
		Cursor cursor = mContentResolver.query(uri, Entity.getColumns(cls), null, null, sortOrder);
		if (cursor == null) {
			return null;
		}
		return new Data<>(cursor, cls);
	}
}
