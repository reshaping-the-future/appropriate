package ac.robinson.pod.models;

import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import org.nustaq.serialization.annotations.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import ac.robinson.pod.BasePodApplication;

public class ContactModel implements Serializable {
	private final static long serialVersionUID = 1;

	public String mId;
	public String mName;
	public ArrayList<String> mMobileNumbers;

	@Version(5) // added in version 5 of Pod app - will not affect older versions as they could not edit contacts
	public boolean mContactEdited;

	public ContactModel() {
		mMobileNumbers = new ArrayList<>();
	}

	public static LinkedHashMap<String, ContactModel> readContactList(InputStream stream) throws Exception {
		FSTObjectInput in = BasePodApplication.getFSTConfiguration().getObjectInput(stream);
		// don't in.close() here - prevents reuse and will result in an exception
		LinkedHashMap<String, ContactModel> result = (LinkedHashMap<String, ContactModel>) in.readObject(ContactModel
				.class);
		stream.close();
		return result;
	}

	public static void writeContactList(OutputStream stream, LinkedHashMap<String, ContactModel> toWrite) throws
			IOException {
		FSTObjectOutput out = BasePodApplication.getFSTConfiguration().getObjectOutput(stream);
		out.writeObject(toWrite, ContactModel.class);
		out.flush(); // don't out.close() here when using factory method;
		stream.close();
	}
}
