package ac.robinson.pod.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class PodManagerServiceCommunicator {

	private boolean mIsBound;

	private Messenger mService = null;
	private final Messenger mMessenger;
	private HotspotServiceCallback mCallback;

	private ArrayList<Message> mQueuedMessages = new ArrayList<>();

	public interface HotspotServiceCallback {
		void onServiceMessageReceived(int type, String data);
	}

	public PodManagerServiceCommunicator(HotspotServiceCallback callback) {
		mMessenger = new Messenger(new IncomingHandler(PodManagerServiceCommunicator.this));
		mCallback = callback;
	}

	private static class IncomingHandler extends Handler {
		private final WeakReference<PodManagerServiceCommunicator> mCommunicatorReference; // allow garbage collection

		IncomingHandler(PodManagerServiceCommunicator instance) {
			mCommunicatorReference = new WeakReference<>(instance);
		}

		@Override
		public void handleMessage(Message msg) {
			PodManagerServiceCommunicator mCommunicator = mCommunicatorReference.get();
			if (mCommunicator == null) {
				// TODO: anything to do here?
				return;
			}

			switch (msg.what) {
				//TODO: better to separate into types? (see Better Together)
				default:
					mCommunicator.mCallback.onServiceMessageReceived(msg.what, msg.getData()
							.getString(PodManagerService.KEY_POD_CONNECTION_STATE));
					break;
			}
		}
	}

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			mService = new Messenger(service);
			try {
				Message message = Message.obtain(null, PodManagerService.MSG_REGISTER_CLIENT);
				message.replyTo = mMessenger;
				mService.send(message);
				for (Message queuedMessage : mQueuedMessages) {
					mService.send(queuedMessage);
				}
			} catch (RemoteException ignored) {
				// crashed before started - will reconnect
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			mService = null; // unexpectedly disconnected - e.g., crashed
		}
	};

	// service messages are to the PodManagerService
	public boolean sendServiceMessage(int type, String data) {
		try {
			Message message = Message.obtain(null, type);
			message.replyTo = mMessenger;
			Bundle bundle = new Bundle(1);
			bundle.putString(PodManagerService.KEY_POD_CONNECTION_STATE, data);
			message.setData(bundle);
			if (mService != null) {
				mService.send(message);
			} else {
				mQueuedMessages.add(message);
			}
			return true;
		} catch (RemoteException e) {
			// e.printStackTrace();
			return false;
		}
	}

	// connect to the service
	public void bindService(Context context) {
		context.startService(new Intent(context, PodManagerService.class));
		context.bindService(new Intent(context, PodManagerService.class), mConnection, Context.BIND_AUTO_CREATE);
		mIsBound = true;
	}

	// disconnect from the service
	public void unbindService(Context context, boolean keepServiceAlive) {
		if (mIsBound) {
			if (mService != null) {
				try {
					Message message = Message.obtain(null, PodManagerService.MSG_UNREGISTER_CLIENT);
					message.replyTo = mMessenger;
					message.arg1 = keepServiceAlive ? 1 : 0;
					mService.send(message);
				} catch (RemoteException ignored) {
					// crashed - nothing to do
				}
			}
			context.unbindService(mConnection);
			mIsBound = false;
		}
	}
}
