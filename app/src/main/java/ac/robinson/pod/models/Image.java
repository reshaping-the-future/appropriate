package ac.robinson.pod.models;

import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.MediaStore;

// based on https://github.com/EverythingMe/easy-content-providers
public class Image extends Entity {

	@IgnoreMapping
	public static final Uri uriExternal = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

	@IgnoreMapping
	public static final Uri uriInternal = MediaStore.Images.Media.INTERNAL_CONTENT_URI;

	@FieldMapping(columnName = BaseColumns._ID, physicalType = FieldMapping.PhysicalType.Long)
	public long id;

	@FieldMapping(columnName = MediaStore.MediaColumns.DATA, physicalType = FieldMapping.PhysicalType.String)
	public String data;

	@FieldMapping(columnName = MediaStore.MediaColumns.SIZE, physicalType = FieldMapping.PhysicalType.Int)
	public int size;

	@FieldMapping(columnName = MediaStore.MediaColumns.TITLE, physicalType = FieldMapping.PhysicalType.String)
	public String title;

	@FieldMapping(columnName = MediaStore.Images.ImageColumns.DESCRIPTION, physicalType = FieldMapping.PhysicalType
			.String)
	public String description;

	@FieldMapping(columnName = MediaStore.Images.ImageColumns.ORIENTATION, physicalType = FieldMapping.PhysicalType
			.Int)
	public int orientation;
}
