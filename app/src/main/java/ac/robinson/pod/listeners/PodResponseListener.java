package ac.robinson.pod.listeners;

// for when all we care about is whether the response succeeded or failed
public interface PodResponseListener {
	void onResponse(boolean success, String originalRequestPath);
}
