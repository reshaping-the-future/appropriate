package ac.robinson.pod.models;

import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import ac.robinson.pod.BasePodApplication;

public class SmsModel implements Serializable {
	private final static long serialVersionUID = 1;

	public boolean mNewThread;
	public String mThreadId;
	public String mContactName;
	public String mContactNumber;
	public String mThreadSnippet;
	public ArrayList<SmsMessage> mMessages;

	public SmsModel(String threadId) {
		mThreadId = threadId;
		mMessages = new ArrayList<>();
	}

	public static LinkedHashMap<String, SmsModel> readSmslist(InputStream stream) throws Exception {
		FSTObjectInput in = BasePodApplication.getFSTConfiguration().getObjectInput(stream);
		// don't in.close() here - prevents reuse and will result in an exception
		LinkedHashMap<String, SmsModel> result = (LinkedHashMap<String, SmsModel>) in.readObject(SmsModel.class);
		stream.close();
		return result;
	}

	public static void writeSmsList(OutputStream stream, LinkedHashMap<String, SmsModel> toWrite) throws IOException {
		FSTObjectOutput out = BasePodApplication.getFSTConfiguration().getObjectOutput(stream);
		out.writeObject(toWrite, SmsModel.class);
		out.flush(); // don't out.close() here when using factory method;
		stream.close();
	}
}

