package ac.robinson.pod;

import android.app.Application;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.ImagePipelineConfig;

import org.nustaq.serialization.FSTConfiguration;

import ac.robinson.pod.service.OkHttpHelper;
import okhttp3.OkHttpClient;

public class BasePodApplication extends Application {

	public static final String FILE_PROVIDER_NAME = BuildConfig.APPLICATION_ID + ".fileprovider";
	private static FSTConfiguration sFSTConfiguration;

	@Override
	public void onCreate() {
		super.onCreate();
		ImagePipelineConfig config = ImagePipelineConfig.newBuilder(BasePodApplication.this)
				.setDownsampleEnabled(true)
				.build();
		Fresco.initialize(BasePodApplication.this, config);
	}

	public static FSTConfiguration getFSTConfiguration() {
		if (sFSTConfiguration == null) {
			sFSTConfiguration = FSTConfiguration.createAndroidDefaultConfiguration();
		}
		return sFSTConfiguration;
	}

	public static OkHttpClient getOkHttpClient() {
		return OkHttpHelper.getOkHttpClient();
	}

	public static OkHttpClient getCustomTimeoutOkHttpClient(int timeout) {
		return OkHttpHelper.getCustomTimeoutOkHttpClient(timeout);
	}
}
