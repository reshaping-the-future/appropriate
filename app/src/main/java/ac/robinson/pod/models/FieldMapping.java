package ac.robinson.pod.models;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// based on https://github.com/EverythingMe/easy-content-providers
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldMapping {

	String columnName();

	PhysicalType physicalType();

	LogicalType logicalType() default LogicalType.Physical;

	String splitRegex() default "\\s+"; // white space

	/**
	 * The type as it persisted in database
	 */
	enum PhysicalType {
		String, Long, Int, Double, Blob
	}

	/**
	 * The type of java field
	 */
	enum LogicalType {
		String, Long, Int, Boolean, Array, EnumInt, Double, Physical
	}
}
