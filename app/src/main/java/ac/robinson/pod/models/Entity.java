package ac.robinson.pod.models;

import android.database.Cursor;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

// based on https://github.com/EverythingMe/easy-content-providers
public abstract class Entity {

	/**
	 * Get content provider columns.
	 *
	 * @return Array of column names.
	 */
	public static <T> String[] getColumns(Class<T> cls) {
		List<String> columns = new ArrayList<>();
		Field[] fields = cls.getDeclaredFields();
		for (Field field : fields) {
			if (!field.isAnnotationPresent(IgnoreMapping.class)) {
				FieldMapping contentField = field.getAnnotation(FieldMapping.class);
				if (contentField == null) {
					continue;
				}
				String columnName = contentField.columnName();
				columns.add(columnName);
			}
		}
		return columns.toArray(new String[columns.size()]);
	}

	/**
	 * Create new entity instance from database raw instance.
	 *
	 * @param <T>
	 * @param cursor
	 */
	public static <T extends Entity> T create(Cursor cursor, Class<T> cls) {
		try {
			T entity = cls.newInstance();
			Field[] fields = cls.getDeclaredFields();
			return create(entity, cursor, cls, fields);
		} catch (Exception e) {
			// e.printStackTrace();
			return null;
		}
	}

	private static <T extends Entity> T create(T entity, Cursor cursor, Class<T> cls, Field[] fields) {
		for (Field field : fields) {
			try {
				FieldMapping contentField = field.getAnnotation(FieldMapping.class);
				IgnoreMapping ignoreMapping = field.getAnnotation(IgnoreMapping.class);
				if (contentField != null && ignoreMapping == null) {
					String columnName = contentField.columnName();
					String methodName = "get" + contentField.physicalType().name();

					Method method = Cursor.class.getDeclaredMethod(methodName, int.class);
					int columnIndex = cursor.getColumnIndexOrThrow(columnName);
					Object object = method.invoke(cursor, columnIndex);
					switch (contentField.logicalType()) {
						case Boolean:
							boolean value = Integer.valueOf(String.valueOf(object)) != 0;
							field.setAccessible(true);
							field.setBoolean(entity, value);
							break;
						case EnumInt:
							@SuppressWarnings("unchecked") Class<? extends EnumInt> enumType = (Class<? extends
									EnumInt>) field
									.getType();
							Method enumStaticMethod = enumType.getMethod("fromVal", int.class);
							Object enumInstance = enumStaticMethod.invoke(null, object);
							field.setAccessible(true);
							field.set(entity, enumInstance);
							break;
						case Array:
							String[] strings = String.valueOf(object).split(contentField.splitRegex());
							field.setAccessible(true);
							field.set(entity, strings);
							break;
						default:
							field.setAccessible(true);
							field.set(entity, object);
							break;
					}
				}
			} catch (Exception e) {
				Log.e(Entity.class.getName(), "field=" + field.getName(), e);
			}
		}
		return entity;
	}

	@Override
	public String toString() {
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append('{');
		Field[] fields = getClass().getDeclaredFields();
		boolean firstTime = true;
		for (Field field : fields) {
			try {
				if (!firstTime) {
					stringBuilder.append(", ");
				}
				field.setAccessible(true);
				stringBuilder.append(field.getName());
				stringBuilder.append('=');
				stringBuilder.append(field.get(this));
				firstTime = false;
			} catch (Exception e) {
				// e.printStackTrace();
			}
		}
		stringBuilder.append('}');
		return stringBuilder.toString();
	}
}
