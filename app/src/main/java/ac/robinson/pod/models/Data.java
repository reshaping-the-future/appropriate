package ac.robinson.pod.models;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

// based on https://github.com/EverythingMe/easy-content-providers
public class Data<T extends Entity> {

	private final Class<T> mCls;
	private Cursor mCursor;

	Data(Cursor cursor, Class<T> cls) {
		mCursor = cursor;
		mCls = cls;
	}

	public List<T> getList() {
		List<T> data = new ArrayList<>();
		if (mCursor == null) {
			return data;
		}
		try {
			while (mCursor.moveToNext()) {
				T t = Entity.create(mCursor, mCls);
				data.add(t);
			}
		} finally {
			mCursor.close();
		}
		return data;
	}
}
