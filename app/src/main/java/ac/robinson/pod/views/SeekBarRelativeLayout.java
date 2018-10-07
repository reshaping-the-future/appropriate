package ac.robinson.pod.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.RelativeLayout;
import android.widget.SeekBar;

import ac.robinson.pod.R;

public class SeekBarRelativeLayout extends RelativeLayout {

	private float mLastX;
	private float mLastY;
	private int mTouchSlop;
	private boolean mDownSent;
	private SeekBar mSeekBar;

	public SeekBarRelativeLayout(Context context) {
		super(context);
		initialise(context);
	}

	public SeekBarRelativeLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
		initialise(context);
	}

	public SeekBarRelativeLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initialise(context);
	}

	private void initialise(Context context) {
		ViewConfiguration vc = ViewConfiguration.get(context);
		mTouchSlop = vc.getScaledTouchSlop();
	}

	private void sendDuplicateEvent(MotionEvent event, int action) {
		int originalAction = event.getAction();
		event.setAction(action);
		if (mSeekBar == null) {
			mSeekBar = findViewById(R.id.preference_seek_bar);
		}
		if (mSeekBar != null) {
			mSeekBar.dispatchTouchEvent(event);
		}
		event.setAction(originalAction);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
		float xDistance, yDistance;
		switch (ev.getAction()) {
			case MotionEvent.ACTION_DOWN:
				mDownSent = false;
				mLastX = ev.getX();
				mLastY = ev.getY();

				// setAction doesn't work for the initial event (more likely, it modifies the next event instead...)
				// - here we cancel the down event before it is propagated to the seek bar
				sendDuplicateEvent(ev, MotionEvent.ACTION_CANCEL);
				return false;

			case MotionEvent.ACTION_MOVE:
				xDistance = Math.abs(ev.getX() - mLastX);
				yDistance = Math.abs(ev.getY() - mLastY);
				if (xDistance >= mTouchSlop || yDistance >= mTouchSlop) {
					if (yDistance >= xDistance) {
						return true; // don't pass any more events to the child - we're scrolling vertically
					} else if (!mDownSent) {
						// we're scrolling horizontally - send the initial down action that we cancelled previously
						sendDuplicateEvent(ev, MotionEvent.ACTION_DOWN); // this is now the initial down action
						mDownSent = true;
					}
				} else {
					// could also send a down event here if (ev.getEventTime() - original event's time) is larger than
					// the view configuration's tap timeout, but this causes accidental changes on scroll more often
					sendDuplicateEvent(ev, MotionEvent.ACTION_CANCEL); // don't do the down action on the seek bar
				}
				break;

			case MotionEvent.ACTION_UP:
				xDistance = Math.abs(ev.getX() - mLastX);
				yDistance = Math.abs(ev.getY() - mLastY);
				if (!mDownSent && xDistance < mTouchSlop && yDistance < mTouchSlop) {
					// they tapped on the bar (note: not checking tap duration here, as we cancelled original event)
					sendDuplicateEvent(ev, MotionEvent.ACTION_DOWN);
					mDownSent = true;
				}
				break;

			default:
				break;
		}

		return super.onInterceptTouchEvent(ev);
	}
}
