package ac.robinson.pod.listeners;

import android.support.annotation.Nullable;

import java.util.ArrayList;

import ac.robinson.pod.service.SynchronisedFile;

public interface PodResponseDirectoryContentListener {
	void onResponse(boolean success, String originalRequestPath, @Nullable ArrayList<SynchronisedFile> fileList);
}
