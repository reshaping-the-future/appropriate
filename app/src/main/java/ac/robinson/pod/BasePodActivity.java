package ac.robinson.pod;

import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;

import ac.robinson.pod.service.PodManagerService;
import ac.robinson.pod.service.PodManagerServiceCommunicator;

public abstract class BasePodActivity extends AppCompatActivity implements PodManagerServiceCommunicator
		.HotspotServiceCallback {

	private PodManagerServiceCommunicator mServiceCommunicator;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mServiceCommunicator = new PodManagerServiceCommunicator(BasePodActivity.this);
		mServiceCommunicator.bindService(BasePodActivity.this);
	}

	protected void initialiseViewAndToolbar(int layout) {
		setContentView(layout);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (mServiceCommunicator != null) {
			mServiceCommunicator.unbindService(BasePodActivity.this, !isFinishing());  // don't kill on rotation
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				// NavUtils.navigateUpFromSameTask(BaseHotspotActivity.this); // only API 16+; requires manifest tag
				finish();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	public void sendServiceMessage(int type, String data) {
		mServiceCommunicator.sendServiceMessage(type, data);
	}

	@Override
	public void onServiceMessageReceived(int type, String data) {
		switch (type) {
			case PodManagerService.EVENT_CONNECTION_STATUS_UPDATE:
				switch (data) {
					case PodManagerService.DATA_POD_CONNECTED:
						break;

					case PodManagerService.DATA_POD_CONNECTION_DROPPED:
						if (!(this instanceof MainActivity)) { // TODO: this is bad - should warn first
							finish(); // sub-activities can't continue when pod is disconnected
						}
						break;

					default:
						break;
				}
				break;

			default:
				break;
		}
	}
}
