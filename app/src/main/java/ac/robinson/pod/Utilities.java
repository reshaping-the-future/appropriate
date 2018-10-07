package ac.robinson.pod;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Utilities {
	private static final int IO_BUFFER_SIZE = 4 * 1024;

	public static void copyFile(InputStream in, File targetLocation) throws IOException {
		OutputStream out = new FileOutputStream(targetLocation);
		byte[] buf = new byte[IO_BUFFER_SIZE];
		int len;
		while ((len = in.read(buf)) > 0) {
			out.write(buf, 0, len);
		}
		closeStream(in); // TODO: should we do this here?
		closeStream(out);
	}

	public static void moveFile(File sourceLocation, File targetLocation) throws IOException {
		InputStream in = new FileInputStream(sourceLocation);
		copyFile(in, targetLocation);
		sourceLocation.delete();
	}

	public static void closeStream(Closeable stream) {
		if (stream != null) {
			try {
				stream.close();
			} catch (Throwable ignored) {
			}
		}
	}

	public static String removeFileExtension(String s) {
		String separator = System.getProperty("file.separator");
		String filename;

		int lastSeparatorIndex = s.lastIndexOf(separator);
		if (lastSeparatorIndex == -1) {
			filename = s;
		} else {
			filename = s.substring(lastSeparatorIndex + 1);
		}

		int extensionIndex = filename.lastIndexOf('.');
		if (extensionIndex == -1) {
			return filename;
		}

		return filename.substring(0, extensionIndex);
	}

	// Android is so frustrating sometimes
	public static void setKeyboardVisibility(final Activity context, final View view, final boolean visible) {
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				InputMethodManager inputMethodManager = (InputMethodManager) context.getSystemService(Context
						.INPUT_METHOD_SERVICE);
				if (inputMethodManager != null) {
					if (view != null) {
						if (visible) {
							view.requestFocus();
							inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
						} else {
							View currentFocus = context.getCurrentFocus();
							if (currentFocus != null) {
								inputMethodManager.hideSoftInputFromWindow(currentFocus.getWindowToken(),
										InputMethodManager.HIDE_NOT_ALWAYS);
							} else {
								inputMethodManager.hideSoftInputFromWindow(context.findViewById(android.R.id.content)
										.getRootView()
										.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
							}
						}
					} else if (!visible) {
						inputMethodManager.hideSoftInputFromWindow(context.findViewById(android.R.id.content)
								.getRootView()
								.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
					}
				}
			}
		}, 150);
	}
}
