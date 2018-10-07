package ac.robinson.pod.models;

import android.support.annotation.NonNull;

import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

import ac.robinson.pod.BasePodApplication;

public class ImageModel implements Serializable, Comparable<ImageModel> {
	private final static long serialVersionUID = 1;

	public String mName;
	public String mPath; // where this file was originally stored (not including filename)
	public long mLastModified;
	public long mSize;

	public ImageModel() {
	}

	public static ImageModel readImageModel(InputStream stream) throws Exception {
		FSTObjectInput in = BasePodApplication.getFSTConfiguration().getObjectInput(stream);
		// don't in.close() here - prevents reuse and will result in an exception
		ImageModel result = (ImageModel) in.readObject(ImageModel.class);
		stream.close();
		return result;
	}

	public static void writeImageModel(OutputStream stream, ImageModel toWrite) throws IOException {
		FSTObjectOutput out = BasePodApplication.getFSTConfiguration().getObjectOutput(stream);
		out.writeObject(toWrite, ImageModel.class);
		out.flush(); // don't out.close() here when using factory method;
		stream.close();
	}

	@Override
	public int compareTo(@NonNull ImageModel imageModel) {
		return -Long.valueOf(mLastModified).compareTo(imageModel.mLastModified); // negative so *newest* are first
	}
}
